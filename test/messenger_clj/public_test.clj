(ns messenger-clj.public-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [messenger-clj.public :as public]
            [messenger-clj.core :as runtime]
            [messenger-clj.typed-store :as store]))

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
       (is (= "METAFLOW\tSESSION\tSTATE\nField Core Secondary\ts\tSTALE" (public/listing!))))
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
