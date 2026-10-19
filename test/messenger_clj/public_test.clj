(ns messenger-clj.public-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [cheshire.core :as json]
            [messenger-clj.public :as public]
            [messenger-clj.flow-bridge :as bridge]
            [messenger-clj.core :as runtime]
            [messenger-clj.typed-store :as store]))

(use-fixtures :each (fn [f]
  (binding [bridge/*call* (fn [program _]
                          (if (= program "flow") {:exit 0 :out "Current.Unknown"}
                              (throw (ex-info "Unexpected Message call" {}))))]
    (f))))

(def target "Field Core Secondary")
(def route {:session "s" :name "internal-agent-00f95a" :pane_id "p" :terminal_id "t"
            :agent "codex" :native_thread "00000000-0000-0000-0000-000000000000"
            :aspect "Field" :topic "Core" :layer "Secondary"})
(defn in-registry [f]
  (let [root (str (fs/create-temp-dir {:prefix "hm-public-contract-"}))]
    (try
      (binding [runtime/*root* root runtime/*flow-id* "sender"
                runtime/*with-reservation* (fn [_ operation] (operation))]
        (store/put-route! root "00f95a" route)
        (f root))
      (finally (fs/delete-tree root)))))

(def controls
  [[public/deregister! #'runtime/deregister! ["s" "p" "t" "agent"] "Deregistered"]
   [public/rebind! #'runtime/rebind! ["old" "new" "s" "p" "t" "codex" (:native_thread route)] "Rebound"]
   [public/move! #'runtime/move! ["s" "p" "t" "agent" "codex" (:native_thread route) 123 "workspace"] "Moved"]
   [public/retire! #'runtime/retire! [] "Retired"]
   [public/import-retirement! #'runtime/import-retirement! ["s" "p" "t" "agent" "codex" (:native_thread route) "/evidence" "hash"] "Retired"]])
(def deliveries
  [[public/send! #'runtime/send! ["body" false nil 10]]
   [public/send-psyche! #'runtime/send-psyche! ["context" "whole verbatim" false nil 10]]
   [public/send-psyches! #'runtime/send-psyches! ["[[\"context\" \"whole\"]]" false nil 10]]
   [public/send-abrupt! #'runtime/send-abrupt! ["body" false]]
   [public/send-abrupt-psyche! #'runtime/send-abrupt-psyche! ["context" "whole" false]]
   [public/send-abrupt-request! #'runtime/send-abrupt-request! [{:variant :psyches :body "[[\"c\" \"v\"]]"} false]]])

(deftest every-public-identity-command-requires-a-metaflow-before-runtime-work
  (doseq [bad ["00f95a" "Field.Core.Secondary" "Field Core" "Field  Core Secondary" "Field Core Secondary extra"]]
    (doseq [[command _ arguments] (concat controls deliveries [[public/repair! nil ["pending" "s" "p" "t" "agent" "codex"]]])]
      (is (thrown-with-msg? Exception #"MetaflowRequired" (apply command bad arguments))))
    (is (thrown-with-msg? Exception #"MetaflowRequired" (public/register! bad "agent" "s" nil)))))

(deftest all-controls-resolve-the-current-name-and-keep-internal-key-out-of-output
  (in-registry
   (fn [_]
     (doseq [[command implementation arguments label] controls]
       (let [seen (atom nil)]
         (with-redefs-fn {implementation (fn [& args] (reset! seen args) "internal result 00f95a")}
           #(is (= (str label ".{ «Field Core Secondary» }") (apply command target arguments))))
         (is (= (cons "00f95a" arguments) @seen)))))))

(deftest public-delivery-preserves-variant-arguments-and-runtime-grade
  (in-registry
   (fn [_]
     (doseq [[command implementation arguments] deliveries]
       (let [seen (atom nil)]
         (with-redefs-fn {implementation (fn [& args] (reset! seen args) "Presented.{ 00f95a idle }")}
           #(is (= "Presented.{ «Field Core Secondary» idle }" (apply command target arguments))))
         (is (= (cons target arguments) @seen)))))))

(deftest missing-ambiguous-and-replaced-current-identities
  (in-registry
   (fn [root]
     (is (thrown-with-msg? Exception #"TargetNotRegistered" (public/send! "Mind Missing Primary" "x" false nil 10)))
     (store/put-route! root "other" (assoc route :pane_id "other" :terminal_id "other"))
     (is (thrown-with-msg? Exception #"AmbiguousTarget" (public/retire! target)))
     (is (thrown-with-msg? Exception #"AmbiguousTarget" (public/send! target "x" false nil 10)))
     (store/remove-route! root "00f95a")
     (is (= "other" (public/resolve! target true)) "A current route supersedes historical identity, not by arbitrary ordering")
     (store/remove-route! root "other")
     (is (thrown-with-msg? Exception #"AmbiguousTarget" (public/resolve! target true)) "Two historical identities cannot be guessed")
     (is (thrown-with-msg? Exception #"TargetNotRegistered" (public/resolve! target false))))))

(deftest registration-bootstrap-and-native-conflict-boundary
  (in-registry
   (fn [root]
     (let [seen (atom nil)]
       (with-redefs [runtime/register! (fn [& args] (reset! seen args))]
         (is (= "Registered.{ «Field Core Secondary» }"
                (public/register! target "agent" "s" (:native_thread route))))
         (is (= ["00f95a" "agent" "s" (:native_thread route) (public/metadata target)] @seen))
         (binding [public/*registration-flow-id* "other"]
           (is (thrown-with-msg? Exception #"MetaflowAlreadyRegistered" (public/register! target "agent" "s" nil))))
         (is (thrown-with-msg? Exception #"RegistrationIdentityConflict"
                              (public/register! target "agent" "s" "different-native-thread")))
         (is (thrown-with-msg? Exception #"RegistrationIdentityConflict"
                              (public/register! target "agent" "s" nil {:aspect "Mind" :topic "Core" :layer "Secondary"})))
         (binding [public/*registration-flow-id* "fresh-runtime-claim"]
           (is (= "Registered.{ «Mind New Primary» }" (public/register! "Mind New Primary" "new-agent" "s" nil)))
           (is (= "fresh-runtime-claim" (first @seen)))))
       (is (= "Field Core Secondary" (get (store/metaflow-identities root) "00f95a")))))))

(deftest names-survive-removal-and-public-state-hides-runtime-identifiers
  (in-registry
   (fn [root]
     (with-redefs [runtime/live-agents (constantly [])]
       (is (= "TITLE\tSESSION\tSTATE\n{ Field Core Secondary 00f95a }\ts\tSTALE" (public/listing!))))
     (let [state (public/heartbeat-state!)]
       (is (not (str/includes? state "00f95a")))
       (is (not (str/includes? state (:native_thread route))))
       (is (= "messenger-clj" (:lookup_owner (json/parse-string state true)))))
     (store/remove-route! root "00f95a")
     (is (= "00f95a" (public/resolve! target true)))
     (is (thrown-with-msg? Exception #"TargetNotRegistered" (public/resolve! target false)))
     (store/put-route! root "unqualified" (dissoc route :aspect :topic :layer))
     (is (thrown-with-msg? Exception #"MetaflowMetadataRequired" (public/heartbeat-state!))))))

(deftest refusal-keeps-held-and-uncertain-meaning-without-public-key-leak
  (in-registry
   (fn [_]
     (with-redefs [runtime/send! (fn [& _] (throw (ex-info "Held.{ 00f95a RepairRequired attempt-0123456789ab }"
                                                                   {:hm/held true :hm/attempt-id "0123456789ab"})))]
       (is (thrown-with-msg? Exception #"Held\.\{ «Field Core Secondary» RepairRequired attempt-0123456789ab"
                            (public/send! target "body" false nil 10))))
     ;; core renders the first twelve characters of a UUID attempt id, hyphen included.
     (with-redefs [runtime/send! (fn [& _] (throw (ex-info "Held.{ 42265e Blocked attempt-337b9573-5fa }"
                                                                   {:hm/held true :hm/attempt-id "337b9573-5fa0"})))]
       (is (thrown-with-msg? Exception #"Held\.\{ «Field Core Secondary» Blocked attempt-337b9573-5fa"
                            (public/send! target "body" false nil 10))))
     (with-redefs [runtime/send! (fn [& _] (throw (ex-info "Uncertain.{ 00f95a attempt-0123456789ab } do not retry" {})))]
       (try (public/send! target "body" false nil 10)
            (is false "must refuse uncertain delivery")
            (catch Exception error
              (is (str/includes? (.getMessage error) "Uncertain.{ «Field Core Secondary» }"))
              (is (str/includes? (.getMessage error) "Do not retry"))
              (is (not (str/includes? (.getMessage error) "00f95a")))))))))

(deftest repair-required-keeps-hyphenated-attempt-and-original-evidence
  (in-registry
   (fn [_]
     (let [calls (atom 0)
           attempt "337b9573-5fa0-4000-8000-000000000000"
           original (ex-info "Held.{ 00f95a RepairRequired attempt-337b9573-5fa }"
                             {:hm/held true :hm/attempt-id attempt})]
       (with-redefs [runtime/send! (fn [& _] (swap! calls inc) (throw original))]
         (let [failure (try (public/send! target "whole body" false nil 10)
                            nil
                            (catch Exception error error))]
           (is (some? failure))
           (when failure
             (is (= "Held.{ «Field Core Secondary» RepairRequired attempt-337b9573-5fa }"
                    (.getMessage failure)))
             (is (= attempt (:hm/attempt-id (ex-data failure))))
             (is (true? (:hm/held (ex-data failure))))
             (is (identical? original (.getCause failure))))
           (is (= 1 @calls))))))))

(deftest stale-route-real-hold-keeps-full-pending-id-and-body
  (in-registry
   (fn [root]
     (store/put-route! root "sender"
                       (assoc route :pane_id "sender-pane" :terminal_id "sender-terminal"
                              :aspect "Mind" :topic "tests" :layer "Primary"))
     (let [prompts (atom 0)
           sends (atom 0)
           real-send runtime/send!
           before (store/route-for root "00f95a")
           body "whole body λ\nsecond line"]
       (with-redefs [runtime/live-agents (constantly [])
                     runtime/send! (fn [& args] (swap! sends inc) (apply real-send args))
                     runtime/prompt!* (fn [& _] (swap! prompts inc))]
         (let [failure (try (public/send! target body false nil 0)
                            nil (catch Exception error error))
               pending (store/pending-for root "00f95a")
               id (get-in (first pending) [:attempt :id])]
           (is (some? failure))
           (is (= 1 (count pending)))
           (is (string? id))
           (when failure
             (is (= (str "Held.{ «Field Core Secondary» RepairRequired " id " }")
                    (.getMessage failure)))
             (is (= id (:hm/attempt-id (ex-data failure))))
             (is (true? (:hm/held (ex-data failure))))
             (is (some? (.getCause failure))))
           (is (= body (:message (first pending))))
           (is (= body (get-in (first pending) [:attempt :body])))
           (is (= :RepairRequired (get-in (first pending) [:attempt :reason])))
           (is (= before (store/route-for root "00f95a")))
           (is (= 1 @sends))
           (is (zero? @prompts))))))))

(deftest held-text-alone-or-mismatched-identity-is-not-held
  (doseq [data [{} {:hm/held false :hm/attempt-id "337b9573-5fa0"}
                  {:hm/held true :hm/attempt-id "different"}]]
    (let [original (ex-info "Held.{ 00f95a RepairRequired 337b9573-5fa0 }" data)
          failure (public/named-error target original)]
      (is (str/starts-with? (.getMessage failure) "Refused.{"))
      (is (identical? original (.getCause failure))))))

(deftest failed-pending-confirmation-never-becomes-a-held-receipt
  (in-registry
   (fn [root]
     (store/put-route! root "sender"
                       (assoc route :pane_id "sender-pane" :terminal_id "sender-terminal"
                              :aspect "Mind" :topic "tests" :layer "Primary"))
     (let [prompts (atom 0)]
       (with-redefs [runtime/live-agents (constantly [])
                     runtime/prompt!* (fn [& _] (swap! prompts inc))
                     store/pending-by-id (fn [& _] nil)]
         (let [failure (try (public/send! target "retained body" false nil 0)
                            nil (catch Exception error error))]
           (is (some? failure))
           (when failure
             (is (str/starts-with? (.getMessage failure) "Refused.{"))
             (is (not (true? (:hm/held (ex-data failure)))))
             (is (some #(= "Pending ledger index did not confirm persistence" (.getMessage %))
                       (take-while some? (iterate #(.getCause %) failure)))))
           (is (zero? @prompts))))))))

(deftest repair-retains-the-real-delivery-grade-and-an-internal-exact-key
  (in-registry
   (fn [_]
     (let [seen (atom nil)]
       (with-redefs [runtime/repair! (fn [& args] (reset! seen args) "Transported.{ 00f95a working }")]
         (is (= "Transported.{ «Field Core Secondary» working }"
                (public/repair! target "pending" "s" "p" "t" "agent" "codex")))
         (is (= "00f95a" (first @seen))))))))

(deftest managed-asleep-delivery-needs-no-legacy-registration
  (let [calls (atom [])]
    (binding [bridge/*call* (fn [program query]
                             (swap! calls conj [program query])
                             {:exit 0 :out (if (= program "flow") "Current.Asleep" "Queued")})]
      (with-redefs [runtime/route-records (fn [] (throw (ex-info "Legacy registry must not be read" {})))
                    runtime/send! (fn [& _] (throw (ex-info "No pane fallback" {})))]
        (is (= "Queued.{ «Field Core Secondary» }" (public/send! target "whole » body" false nil 10)))
        (is (= [["flow" "Current.{ Field «Core» Secondary }"]
                ["message" "Send.{ Address.{ Field «Core» Secondary } Order.«whole \\» body» }"]]
               @calls))))))

(deftest managed-psyche-keeps-context-and-verbatim-without-sender-spoofing
  (let [queries (atom [])]
    (binding [bridge/*call* (fn [program query]
                             (swap! queries conj [program query])
                             {:exit 0 :out (if (= program "flow") "Current.Awake.«internal-id»" "Delivered")})]
      (is (= "Delivered.{ «Field Core Secondary» }"
             (public/send-psyche! target "context" "verbatim" false nil)))
      (is (= "Send.{ Address.{ Field «Core» Secondary } Psyche.{ «context» «verbatim» } }"
             (second (last @queries))))
      (public/send-psyches! target "[[\"c1\" \"v1\"] [\"c2\" \"v2\"]]" false nil)
      (is (= "Send.{ Address.{ Field «Core» Secondary } Psyches.[ { «c1» «v1» } { «c2» «v2» } ] }"
             (second (last @queries)))))))

(deftest managed-refusals-and-uncertainty-never-fall-back
  (doseq [reply [{:exit 0 :out "Refused.Unidentified.{ 42 99 }"}
                 {:exit 0 :out "Refused.Ended.{ Field Core Secondary }"}
                 {:exit 2 :out ""} {:exit 0 :out "not a response"}]]
    (binding [bridge/*call* (fn [program _] (if (= program "flow") {:exit 0 :out "Current.Ended"} reply))]
      (with-redefs [runtime/send! (fn [& _] (throw (AssertionError. "No fallback")))]
        (is (thrown? clojure.lang.ExceptionInfo (public/send! target "body" false nil))))))
  (doseq [reply [{:exit 1 :out ""} {:exit 0 :out "Refused.Store.«offline»"}
                 {:exit 0 :out "Current.Unknown trailing"}]]
    (let [calls (atom 0)]
      (binding [bridge/*call* (fn [_ _] (swap! calls inc) reply)]
        (is (thrown? clojure.lang.ExceptionInfo (public/send! target "body" false nil)))
        (is (= 1 @calls))))))

(deftest managed-pane-interrupt-and-presentation-options-refuse-before-message
  (doseq [call [#(public/send! target "body" true nil)
                #(public/send! target "body" false "s:p")
                #(public/send-abrupt! target "body" false)]]
    (binding [bridge/*call* (fn [program _]
                             (is (= "flow" program))
                             {:exit 0 :out "Current.Asleep"})]
      (is (thrown-with-msg? Exception #"ManagedDeliveryOptionUnsupported" (call))))))

(deftest datom-quoting-preserves-backslashes-delimiters-and-unicode
  (is (= "«»" (runtime/quote-datom "")))
  (is (= "«\\\\»" (runtime/quote-datom "\\")))
  (is (= "«\\»»" (runtime/quote-datom "»")))
  (is (= "««λ\n\\\\\\»»" (runtime/quote-datom "«λ\n\\»")))
  (is (= "Order.«a\\\\b\\»c»"
         (bridge/request-datom {:variant :msg :body "a\\b»c"}))))

(deftest roster-title-is-metadata-and-record-id-not-transport-name
  (in-registry
   (fn [root]
     (let [before (store/routes root)]
       (with-redefs [runtime/live-agents (constantly [(assoc route :agent_status "working")])]
         (is (= "TITLE\tSESSION\tSTATE\n{ Field Core Secondary 00f95a }\ts\tworking"
                (public/listing!))))
       (is (= before (store/routes root)))
       (is (= "00f95a" (public/resolve! target false)))
       (is (thrown-with-msg? Exception #"MetaflowRequired"
                            (public/name! "{ Field Core Secondary 00f95a }")))
       (is (= "{ Field Core Secondary aabbcc }"
              (public/display-title! "aabbcc" (assoc route :name "claude-ffffffffffffffff"))))
       (doseq [bad ["flow-aabbcc" "aabbccdd" "native-uuid" nil]]
         (is (thrown-with-msg? Exception #"DisplayIdentityRequired"
                              (public/display-title! bad route))))
       (is (thrown-with-msg? Exception #"MetaflowMetadataRequired"
                            (public/display-title! "00f95a" (dissoc route :topic))))
       (store/put-route! root "aabbcc" (dissoc route :topic))
       (with-redefs [runtime/live-agents (constantly [])]
         (is (thrown-with-msg? Exception #"MetaflowMetadataRequired" (public/listing!))))))))

(def moved-agent
  (assoc route :pane_id "new-pane" :terminal_id "new-terminal" :agent_status "working"
         :agent_session {:agent "codex" :kind "id" :source "herdr:codex"
                         :value (:native_thread route)}))
(defn migrate-fixture [root agents target-reader process-reader f]
  (let [prompts (atom 0)]
    (with-redefs [runtime/live-agents agents
                  runtime/target-agent* (fn [& args] {:agent (apply target-reader args)})
                  runtime/herdr! (fn [& _] {:panes []})
                  runtime/process-info* (fn [& _] {:foreground_processes [{:pid 123}]})
                  runtime/migration-process-start! process-reader
                  runtime/migration-current! (fn [& _] nil)
                  runtime/prompt!* (fn [& _] (swap! prompts inc))]
      (f prompts))))
(defn register-moved []
  (public/register! target (:name route) (:session route) (:native_thread route)))

(deftest same-native-pane-migration-preserves-real-held-intent-and-history
  (in-registry
   (fn [root]
     (store/put-route! root "sender"
                       (assoc route :pane_id "sender-pane" :terminal_id "sender-terminal"
                              :native_thread "11111111-1111-1111-1111-111111111111"
                              :aspect "Mind" :topic "tests" :layer "Primary"))
     (with-redefs [runtime/live-agents (constantly [])]
       (is (thrown-with-msg? Exception #"Held.*RepairRequired"
                            (public/send! target "whole Unicode λ\nbody retained" false nil 0))))
     (store/put-route! root "00f95a" (assoc route :route_hold "retain-this-hold"))
     (let [before (store/route-for root "00f95a")
           pending (store/pending-for root "00f95a")
           history (store/metaflow-identities root)]
       (migrate-fixture root (constantly [moved-agent]) (fn [& _] moved-agent)
                        (constantly "12345")
         (fn [prompts]
           (is (= "Registered.{ «Field Core Secondary» }" (register-moved)))
           (is (= (assoc before :pane_id "new-pane" :terminal_id "new-terminal")
                  (store/route-for root "00f95a")))
           (is (= pending (store/pending-for root "00f95a")))
           (is (= history (store/metaflow-identities root)))
           (is (zero? @prompts))))))))

(deftest migration-refusals-do-not-change-route-or-ledger
  (doseq [[label agents reader starts expected]
          [[:missing (constantly []) (fn [& _] moved-agent) (constantly "1") "CandidateMissing"]
           [:duplicate (constantly [moved-agent moved-agent]) (fn [& _] moved-agent) (constantly "1") "CandidateAmbiguous"]
           [:native (constantly [(assoc-in moved-agent [:agent_session :value] "11111111-1111-1111-1111-111111111111")])
            (fn [& _] moved-agent) (constantly "1") "NativeMismatch"]
           [:unofficial (constantly [(dissoc moved-agent :agent_session)]) (fn [& _] moved-agent) (constantly "1") "OfficialNativeRequired"]
           [:changed-readback (constantly [moved-agent]) (fn [& _] (assoc moved-agent :terminal_id "changed")) (constantly "1") "CandidateChanged"]
           [:process-unknown (constantly [moved-agent]) (fn [& _] moved-agent)
            (fn [_] (runtime/migration-refuse! :ProcessUnavailable)) "ProcessUnavailable"]]]
    (in-registry
     (fn [root]
       (let [before (store/route-for root "00f95a")
             pending (store/pending-for root "00f95a")
             history (store/metaflow-identities root)]
         (migrate-fixture root agents reader starts
           (fn [prompts]
             (is (thrown-with-msg? Exception (re-pattern (str "MigrationRefused.*" expected))
                                  (register-moved)) (name label))
             (is (= before (store/route-for root "00f95a")))
             (is (= pending (store/pending-for root "00f95a")))
             (is (= history (store/metaflow-identities root)))
             (is (zero? @prompts)))))))))

(deftest migration-rechecks-candidate-process-old-binding-and-ownership
  (doseq [[case expected] [[:moved-again "EvidenceChanged"] [:old-returned "OldBindingLive"]
                           [:pid-reused "EvidenceChanged"] [:foreign "ForeignRegistration"]
                           [:transition "TransitionPending"] [:retired "Retired"]]]
    (in-registry
     (fn [root]
       (when (= case :foreign)
         (store/put-route! root "foreign" (assoc route :pane_id "new-pane" :terminal_id "new-terminal"
                                                  :topic "other")))
       (when (= case :transition)
         (store/put-route! root "00f95a" (assoc route :transition true)))
       (let [before (store/route-for root "00f95a")
             pending (store/pending-for root "00f95a")
             calls (atom 0) process-calls (atom 0)
             retirement store/retirement-for]
         (migrate-fixture root
           (fn []
             (let [n (swap! calls inc)]
               (cond
                 (and (= case :moved-again) (>= n 3)) [(assoc moved-agent :pane_id "third-pane")]
                 (and (= case :old-returned) (>= n 3)) [moved-agent route]
                 :else [moved-agent])))
           (fn [& _] moved-agent)
           (fn [_] (str (if (= case :pid-reused) (swap! process-calls inc) 1)))
           (fn [prompts]
             (with-redefs [store/retirement-for (fn [r id]
                                                 (if (= case :retired) {:state "retired"}
                                                     (retirement r id)))]
               (is (thrown-with-msg? Exception (re-pattern (str "MigrationRefused.* " expected " "))
                                    (if (= case :retired)
                                      (public/named-call target #(runtime/migrate-registration! "00f95a" before (:name route) (:session route) (:native_thread route) (public/metadata target)))
                                      (register-moved))) (name case)))
             (is (= before (store/route-for root "00f95a")))
             (when (contains? #{:moved-again :old-returned :pid-reused} case)
               (is (= 3 @calls) "reaches the second candidate observation"))
             (when (= case :pid-reused)
               (is (= 2 @process-calls) "compares two kernel-start witnesses"))
             (is (zero? @prompts))))
         (is (= pending (store/pending-for root "00f95a"))))))))

(deftest migration-current-requires-exact-awake-holder
  (doseq [answer ["Current.Unknown" "Current.Asleep" "Current.Awake.other" "malformed"]]
    (binding [runtime/*shell* (fn [& _] {:exit 0 :out answer})]
      (is (thrown-with-msg? Exception #"FlowHolderMismatch"
                           (runtime/migration-current! "00f95a" route)))))
  (binding [runtime/*shell* (fn [& _] {:exit 0 :out "Current.Awake.00f95a"})]
    (is (nil? (runtime/migration-current! "00f95a" route)))))

(deftest migration-observation-refusals-preserve-route-pending-and-cause
  (doseq [[scenario expected] [[:initial-error "AgentObservationUnavailable"]
                               [:initial-malformed "AgentObservationUnavailable"]
                               [:pane-without-agent "OldPanePresent"]
                               [:pane-returned "OldPanePresent"]
                               [:pane-malformed "PaneObservationUnavailable"]
                               [:reply-malformed "AgentReplyMalformed"]]]
    (in-registry
     (fn [root]
       (let [before (store/route-for root "00f95a")
             pending (store/pending-for root "00f95a")
             history (store/metaflow-identities root)
             pane-calls (atom 0)
             original (ex-info "original observation failure" {:diagnostic :retained})]
         (migrate-fixture root (constantly [moved-agent]) (fn [& _] moved-agent) (constantly "1")
           (fn [prompts]
             (with-redefs [runtime/live-agents (fn [] (case scenario
                                                        :initial-error (throw original)
                                                        :initial-malformed nil
                                                        [moved-agent]))
                           runtime/target-agent* (fn [& _] (if (= scenario :reply-malformed)
                                                            moved-agent {:agent moved-agent}))
                           runtime/herdr! (fn [& args]
                                            (is (= ["--session" "s" "pane" "list"] args))
                                            (let [n (swap! pane-calls inc)]
                                              (cond
                                                (= scenario :pane-malformed) {}
                                                (or (= scenario :pane-without-agent)
                                                    (and (= scenario :pane-returned) (= 2 n)))
                                                {:panes [{:pane_id "p" :terminal_id "t" :agent nil}]}
                                                :else {:panes []})))]
               (let [error (try (register-moved) nil (catch Exception e e))]
                 (is (some? error))
                 (when error
                   (is (= (str "MigrationRefused.{ «Field Core Secondary» " expected " }")
                          (.getMessage error)))
                   (when (= scenario :initial-error)
                     (is (some #(identical? original %)
                               (take-while some? (iterate #(.getCause %) error))))))))
             (when (= scenario :pane-returned)
               (is (= 2 @pane-calls) "vacancy rechecked immediately before save"))
             (is (= before (store/route-for root "00f95a")))
             (is (= pending (store/pending-for root "00f95a")))
             (is (= history (store/metaflow-identities root)))
             (is (zero? @prompts)))))))))
