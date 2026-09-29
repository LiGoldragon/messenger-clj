(ns messenger-clj.legacy-import-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [messenger-clj.legacy-import :as legacy]
            [messenger-clj.typed-store :as store]))

(def thread "00000000-0000-0000-0000-000000000000")
(def route {:session "s" :name "Alpha" :pane_id "p" :terminal_id "t" :agent "codex"
            :native_thread thread})
(def pending-id "11111111111111111111111111111111")
(def sent-id "22222222222222222222222222222222")
(def orphan-id "33333333333333333333333333333333")
(defn write-json! [path value]
  (fs/create-dirs (fs/parent path))
  (spit (str path) (str (json/generate-string value) "\n")))
(defn attempts! [source values]
  (spit (str (fs/path source "attempts.jsonl"))
        (str (str/join "\n" (map json/generate-string values)) "\n")))
(defn base-attempt [id flow reason grade binding]
  {:id id :at "2026-09-25T00:00:00Z" :flow flow :reason reason :grade grade :binding binding})
(defn fixture! []
  (let [source (str (fs/create-temp-dir {:prefix "hm-json-source-"}))
        evidence (fs/create-temp-file {:prefix "hm-import-evidence-"})
        pending (base-attempt pending-id "alpha" "NotRegistered" nil nil)
        sent (base-attempt sent-id "beta" "sent" "Transported"
                           {:pane_id "old" :terminal_id "old-terminal" :native_thread thread})
        orphan (base-attempt orphan-id "orphan" "Uncertain" nil nil)]
    (spit (str evidence) "retirement evidence")
    (write-json! (fs/path source "alpha.json") route)
    (write-json! (fs/path source "beta.json")
                 {:session "s" :name "Beta" :pane_id "q" :terminal_id "u" :agent "claude"})
    ;; Non-.json artifacts never enter the snapshot.
    (spit (str (fs/path source "alpha.json.backup")) "not json")
    (attempts! source [pending sent orphan])
    (write-json! (fs/path source "pending" (str pending-id ".json"))
                 (assoc pending :message "secret pending body" :state "held"))
    (write-json! (fs/path source "retired" "alpha.json")
                 {:version 1 :state "retired" :flow "alpha"
                  :record (select-keys route [:session :name :pane_id :terminal_id :agent])
                  :native_thread thread
                  :evidence {:path (str (fs/absolutize evidence)) :sha256 (legacy/sha256-file evidence)}
                  :retired_by "" :retired_at "2026-09-25T01:00:00Z"})
    {:source source :evidence evidence :attempts [pending sent orphan]}))

(deftest dry-run-receipt-and-idempotent-apply-preserve-the-legacy-shape
  (let [{:keys [source]} (fixture!)
        target (str (fs/path (fs/create-temp-dir {:prefix "hm-json-target-parent-"}) "target"))
        receipt (str (fs/path (fs/create-temp-dir {:prefix "hm-json-receipt-"}) "receipt.edn"))
        dry-output (legacy/import-json! source target receipt false)
        dry-report (edn/read-string dry-output)
        dry-receipt (legacy/read-receipt (slurp receipt))]
    (is (= :dry-run (:mode dry-report)))
    (is (= {:source-routes 2 :source-bound-routes 1 :source-needs-binding-routes 1
            :effective-routes 1 :attempts 3 :attempt-bodies 1
            :pending 1 :retirements 1 :flow-ids 3}
           (:counts dry-report)))
    (is (= ["alpha"] (:retirement-route-overlaps dry-report)))
    (is (= ["orphan"] (:orphan-flow-refs dry-report)))
    (is (not (str/includes? dry-output "secret pending body")) "reports never log bodies")
    (is (= 6 (count (:export/entities dry-receipt))))
    (is (not (fs/exists? (store/database-path target))) "dry-run does not create the target database")

    (let [applied (edn/read-string (legacy/import-json! source target receipt true))]
      (is (= :applied (:mode applied)))
      (is (= 6 (:inserted applied)))
      (is (zero? (:unchanged applied)))
      (is (= "NeedsBinding" (:state (store/route-for target "beta"))))
      (is (nil? (store/route-for target "alpha")) "retirement suppresses an overlapping route")
      (is (= "" (:retired_by (store/retirement-for target "alpha"))))
      (is (= "Alpha" (get-in (store/retirement-for target "alpha") [:record :name])))
      (is (= "secret pending body" (:body (store/attempt-by-id target pending-id))))
      (is (not (contains? (store/attempt-by-id target sent-id) :body)))
      (is (= {:pane_id "old" :terminal_id "old-terminal" :native_thread thread}
             (:binding (store/attempt-by-id target sent-id))))
      (is (= :Transported (:grade (store/attempt-by-id target sent-id))))
      (is (not (contains? (store/attempt-by-id target orphan-id) :grade)))
      (is (= "secret pending body" (:message (store/pending-by-id target pending-id)))))

    (let [again (edn/read-string (legacy/import-json! source target receipt true))]
      (is (zero? (:inserted again)))
      (is (= 6 (:unchanged again)))
      (is (= 3 (count (concat (store/attempts-for target "alpha")
                              (store/attempts-for target "beta")
                              (store/attempts-for target "orphan"))))))))

(deftest malformed-legacy-input-is-rejected-before-target-creation
  (doseq [[label mutate expected]
          [["duplicate attempt"
            (fn [{:keys [source attempts]}] (attempts! source (conj attempts (first attempts))))
            #"Duplicate attempt ID"]
           ["dangling pending"
            (fn [{:keys [source]}]
              (let [path (first (fs/glob source "pending/*.json"))
                    value (json/parse-string (slurp (str path)) true)]
                (write-json! path (assoc value :id "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))))
            #"Pending filename does not match|Dangling pending"]
           ["malformed flow"
            (fn [{:keys [source]}] (fs/move (fs/path source "beta.json") (fs/path source "bad flow.json")))
            #"Malformed Flow ID"]
           ["wrong reason case"
            (fn [{:keys [source attempts]}]
              (attempts! source (assoc-in attempts [1 :reason] "Sent")))
            #"Unknown attempt reason or case"]
           ["evidence mismatch"
            (fn [{:keys [evidence]}] (spit (str evidence) "changed evidence"))
            #"evidence SHA-256 mismatch"]]]
    (testing label
      (let [fixture (fixture!)
            target (str (fs/path (fs/create-temp-dir {:prefix "hm-negative-target-"}) "target"))
            receipt (str (fs/path (fs/create-temp-dir {:prefix "hm-negative-receipt-"}) "receipt.edn"))]
        (mutate fixture)
        (is (re-find expected
                     (try (legacy/import-json! (:source fixture) target receipt true) "no failure"
                          (catch Exception error (.getMessage error)))))
        (is (not (fs/exists? (store/database-path target))))))))

(deftest conflicting-routes-and-target-values-fail-closed
  (let [{:keys [source]} (fixture!)
        target (str (fs/create-temp-dir {:prefix "hm-conflict-target-"}))
        receipt (str (fs/path (fs/create-temp-dir {:prefix "hm-conflict-receipt-"}) "receipt.edn"))]
    (write-json! (fs/path source "gamma.json")
                 {:session "s" :name "Gamma" :pane_id "r" :terminal_id "u" :agent "codex"
                  :native_thread thread})
    (is (re-find #"Conflicting route session/terminal"
                 (try (legacy/import-json! source target receipt false) "no failure"
                      (catch Exception error (.getMessage error)))))
    (fs/delete-if-exists (fs/path source "gamma.json"))
    (store/put-route! target "beta"
                      {:session "other" :name "Different" :pane_id "x" :terminal_id "z"
                       :agent "codex" :native_thread thread})
    (is (re-find #"Target conflicts with route identity"
                 (try (legacy/import-json! source target receipt true) "no failure"
                      (catch Exception error (.getMessage error)))))))

(deftest receipt-rejects-tampered-typed-entities
  (let [{:keys [source]} (fixture!)
        target (str (fs/path (fs/create-temp-dir {:prefix "hm-receipt-target-"}) "target"))
        receipt (str (fs/path (fs/create-temp-dir {:prefix "hm-receipt-file-"}) "receipt.edn"))]
    (legacy/import-json! source target receipt false)
    (let [value (edn/read-string (slurp receipt))
          tampered (assoc-in value [:export/entities 0 :entity/value :unexpected] true)]
      (is (thrown? Exception (legacy/read-receipt (pr-str tampered)))))))
