(ns messenger-clj.typed-store-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [messenger-clj.typed-store :as typed]))

(def flow "00f95a")
(def route
  {:session "s" :name "Mind Sol 00f95a" :pane_id "p" :terminal_id "t"
   :agent "codex" :native_thread "00000000-0000-0000-0000-000000000000"})
(def attempt
  {:id "attempt-1" :flow flow :at "2026-09-25T00:00:00Z"
   :grade :Held :reason :NotRegistered
   :binding {:pane_id "p" :terminal_id "t"
             :native_thread "00000000-0000-0000-0000-000000000000"}})
(def retirement
  {:version 1 :state "retired" :flow flow
   :record {:session "s" :name "Mind Sol 00f95a" :pane_id "p"
            :terminal_id "t" :agent "codex"}
   :native_thread "00000000-0000-0000-0000-000000000000"
   :evidence {:path "/tmp/evidence" :sha256 (apply str (repeat 64 "a"))}
   :retired_by "owner" :retired_at "2026-09-25T01:00:00Z"})

(defn with-temp-store [f]
  (let [root (str (fs/create-temp-dir {:prefix "hm-typed-"}))]
    (try (f root) (finally (fs/delete-tree root)))))

(deftest real-pod-roundtrips-routes-and-needs-binding
  (with-temp-store
    (fn [root]
      (typed/put-route! root flow route)
      (is (= (typed/route! route) (typed/route-for root flow)))
      (is (= {flow (typed/route! route)} (typed/routes root)))
      (let [unbound (dissoc route :native_thread)]
        (typed/put-route! root flow unbound)
        (is (= "NeedsBinding" (:state (typed/route-for root flow)))
            "an absent native thread remains absent and unroutable"))
      (typed/remove-route! root flow)
      (is (nil? (typed/route-for root flow))))))

(deftest real-pod-resolves-attempt-and-pending-links-without-inventing-fields
  (with-temp-store
    (fn [root]
      (typed/put-attempt! root attempt)
      (let [with-body (assoc attempt :id "attempt-2" :body "payload")
            pending {:attempt attempt :message "held body" :state "held"}]
        (typed/put-attempt! root with-body)
        (typed/put-pending! root pending)
        (is (= attempt (typed/attempt-by-id root "attempt-1")))
        (is (not (contains? (typed/attempt-by-id root "attempt-1") :body)))
        (is (= "payload" (:body (typed/attempt-by-id root "attempt-2"))))
        (is (= [attempt with-body] (typed/attempts-for root flow)))
        (is (= pending (typed/pending-by-id root "attempt-1")))
        (is (= [pending] (typed/pending-for root flow)))))))

(deftest real-pod-roundtrips-whole-psyche-variant-and-exact-submission
  (with-temp-store
    (fn [root]
      (let [submitted "#psyche [\"sender\" \"context\" \"verbatim λ \"]"
            psyche (assoc attempt :id "attempt-psyche" :variant :psyche
                          :context "context" :body "verbatim λ "
                          :submitted submitted)
            pending {:attempt psyche :message "verbatim λ " :variant :psyche
                     :context "context" :state "held"}]
        (typed/put-attempt! root psyche)
        (typed/put-pending! root pending)
        (is (= psyche (typed/attempt-by-id root "attempt-psyche")))
        (is (= pending (typed/pending-by-id root "attempt-psyche")))))))

(deftest historical-numbered-psyche-rows-remain-readable
  (with-temp-store
    (fn [root]
      (let [historical (assoc attempt :id "historical-psyche" :variant :psyche
                              :context nil :body "rest" :part_index 2 :part_count 2
                              :submitted "#psyche [\"sender\" nil \"2/2\" \"rest\"]")
            pending {:attempt historical :message "rest" :variant :psyche
                     :context nil :part_index 2 :part_count 2 :state "held"}]
        (typed/put-attempt! root historical)
        (typed/put-pending! root pending)
        (is (= historical (typed/attempt-by-id root "historical-psyche")))
        (is (= pending (typed/pending-by-id root "historical-psyche")))))))

(deftest retirement-preserves-evidence-and-wins-over-a-route
  (with-temp-store
    (fn [root]
      (typed/put-route! root flow route)
      (typed/put-retirement! root retirement)
      (is (= retirement (typed/retirement-for root flow)))
      (is (= [retirement] (typed/retirements root)))
      (is (nil? (typed/route-for root flow)))
      (is (= (typed/route! route) (typed/stored-route-for root flow))))))

(deftest edn-is-an-explicit-lossless-boundary
  (let [entities [{:entity/type :route :entity/flow flow :entity/value (typed/route! route)}
                  {:entity/type :attempt :entity/value attempt}
                  {:entity/type :retirement :entity/value retirement}]
        encoded (typed/export-edn entities)]
    (is (= entities (typed/import-edn encoded)))
    (is (string? encoded))
    (is (thrown? Exception (typed/import-edn "{:not-a-sequence true}")))
    (is (thrown? Exception (typed/import-edn entities)))
    (is (thrown? Exception (typed/import-edn "[{:entity/type :unknown}]")))
    (is (thrown? Exception (typed/export-edn {:not "a sequence"})))))

(deftest malformed-query-results-and-pulled-values-are-rejected
  (testing "row shape"
    (is (thrown? Exception (typed/checked-rows! {:not "rows"} 1)))
    (is (thrown? Exception (typed/checked-rows! #{["x"]} 2))))
  (testing "missing ref"
    (with-redefs [typed/query (fn [& _] #{[{:attempt/id "bad"}]})]
      (is (thrown? Exception (typed/attempt-by-id "unused" "bad")))))
  (testing "real persisted row missing required values"
    (with-temp-store
      (fn [root]
        (typed/transact! root [{:flow/id flow}
                               {:attempt/id "malformed"
                                :attempt/flow [:flow/id flow]
                                :attempt/at "now"}])
        (is (thrown? Exception (typed/attempts-for root flow)))))))

(deftest pod-version-is-explicitly-pinned
  (is (= "0.8.25" typed/pod-version)))
