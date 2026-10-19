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
     (with-redefs [runtime/send! (fn [& _] (throw (ex-info "Uncertain.{ 00f95a attempt-0123456789ab } do not retry" {})))]
       (try (public/send! target "body" false nil 10)
            (is false "must refuse uncertain delivery")
            (catch Exception error
              (is (str/includes? (.getMessage error) "Uncertain.{ «Field Core Secondary» }"))
              (is (str/includes? (.getMessage error) "Do not retry"))
              (is (not (str/includes? (.getMessage error) "00f95a")))))))))

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
