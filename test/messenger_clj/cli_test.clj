(ns messenger-clj.cli-test
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.nio.channels Channels ServerSocketChannel])
  (:require [babashka.fs :as fs]
            [babashka.process :refer [shell]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [messenger-clj.core :as hm]
            [messenger-clj.legacy-import-test :as legacy-test]
            [messenger-clj.typed-store :as store]))

(def native-thread "00000000-0000-0000-0000-000000000000")

(defn seed-cli-sender! [environment wrapper]
  (when (contains? #{"hm-send" "hm-send-abrupt"} wrapper)
    (store/put-route! (get environment "HM_REGISTRY") "sender"
                      {:session "s" :name "sender" :pane_id "sender-pane" :terminal_id "sender-terminal"
                       :agent "codex" :native_thread native-thread
                       :aspect "Mind" :topic "Tests" :layer "Primary"})))
(defn invoke [environment wrapper & arguments]
  (seed-cli-sender! environment wrapper)
  (apply shell {:out :string :err :string :continue true :extra-env environment}
         (str (fs/absolutize (fs/path "bin" wrapper))) arguments))
(defn invoke-input [environment input wrapper & arguments]
  (seed-cli-sender! environment wrapper)
  (apply shell {:in input :out :string :err :string :continue true :extra-env environment}
         (str (fs/absolutize (fs/path "bin" wrapper))) arguments))

(defn fake-prompt-server [config-home]
  (let [socket-dir (fs/path config-home "herdr" "sessions" "s")
        socket-path (fs/path socket-dir "herdr.sock")
        requests (atom [])
        server (ServerSocketChannel/open StandardProtocolFamily/UNIX)]
    (fs/create-dirs socket-dir)
    (.bind server (UnixDomainSocketAddress/of (str socket-path)))
    (let [worker (future
                   (try
                     (while (.isOpen server)
                       (with-open [channel (.accept server)
                                   reader (BufferedReader. (InputStreamReader. (Channels/newInputStream channel) StandardCharsets/UTF_8))
                                   writer (OutputStreamWriter. (Channels/newOutputStream channel) StandardCharsets/UTF_8)]
                         (let [request (json/parse-string (.readLine reader) true)
                               text (get-in request [:params :text])
                               response (cond
                                          (str/includes? text "wait-submitted") {:id (:id request) :result {:ok true}}
                                          (str/includes? text "wait-timeout") {:id (:id request) :error {:code "agent_prompt_stalled"}}
                                          :else {:id (:id request)
                                                 :result {:type "agent_prompted"
                                                          :agent {:name "Mind Sol 00f95a" :pane_id "p"
                                                                  :terminal_id "t" :agent "codex"
                                                                  :agent_status (if (:wait (:params request)) "idle" "working")}}})]
                           (swap! requests conj request)
                           (.write writer (json/generate-string response))
                           (.write writer "\n")
                           (.flush writer))))
                     (catch Exception error
                       (when (.isOpen server) (throw error)))))]
      {:requests requests :server server :worker worker
       :close! (fn [] (.close server) (deref worker 5000 nil))})))

(defn fake-herdr-environment []
  (let [root (str (fs/create-temp-dir {:prefix "hm-cli-presented-"}))
        tools (fs/create-temp-dir {:prefix "hm-cli-presented-tools-"})
        prompt-log (str (fs/path root "prompts.edn"))
        config-home (str (fs/path root "config"))
        prompt-server (fake-prompt-server config-home)
        herdr (fs/path tools "herdr")
        orchestrate (fs/path tools "orchestrate")
        flow (fs/path tools "flow")]
    (fs/copy "test/fake-herdr" herdr)
    (spit (str flow) "#!/usr/bin/env sh\nprintf 'Current.Unknown\\n'\n")
    (.setExecutable (java.io.File. (str flow)) true)
    (spit (str orchestrate)
          "#!/usr/bin/env bash\ncase \"$1\" in Lock.*) echo 'Locked.{ 1 Test sender [ /tmp ] test }';; Release.*) echo 'Released.{ 1 Test sender [ /tmp ] test }';; esac\n")
    (.setExecutable (java.io.File. (str herdr)) true)
    (.setExecutable (java.io.File. (str orchestrate)) true)
    {:root root :tools tools :prompt-log prompt-log :prompt-server prompt-server
     :environment {"PATH" (str tools ":" (System/getenv "PATH"))
                   "HM_REGISTRY" root "HM_PRIMARY_ROOT" root "FLOW_ID" "sender" "HM_REGISTRATION_FLOW_ID" "00f95a"
                   "XDG_CONFIG_HOME" config-home
                   "FAKE_HERDR_PROMPT_LOG" prompt-log}}))

(deftest wait-presented-cli-uses-one-fake-herdr-prompt-and-durable-grades
  (doseq [[wait-result expected-grade expected-exit]
          [["presented" :Presented 0]
           ["submitted" :Uncertain 1]
           ["timeout" :Uncertain 1]]]
    (let [{:keys [root tools prompt-server environment]} (fake-herdr-environment)
          env (assoc environment "FAKE_HERDR_WAIT" wait-result)]
      (try
        (is (zero? (:exit (invoke env "hm-register" "Field Core Secondary" "Mind Sol 00f95a"
                                  "--session" "s" "--native-thread" native-thread))))
        (let [sent (invoke env "hm-send" "Field Core Secondary" (str "wait-" wait-result) "--wait-presented")
              attempts (store/attempts-for root "00f95a")]
          (is (= expected-exit (:exit sent)) (str wait-result ": " (:err sent)))
          (is (str/includes? (str (:out sent) (:err sent)) (name expected-grade)))
          (is (= 1 (count @(:requests prompt-server))))
          (is (= (str "#msg [\"Mind Tests Primary\" \"wait-" wait-result "\"]")
                 (get-in (first @(:requests prompt-server)) [:params :text])))
          (is (= 1 (count (filter #(= :Submitting (:reason %)) attempts))))
          (is (some #(and (= expected-grade (:grade %))
                          (= (if (zero? expected-exit) :sent :Uncertain) (:reason %)))
                    attempts)))
        (finally
          ((:close! prompt-server))
          (fs/delete-tree root)
          (fs/delete-tree tools))))))

(deftest register-cli-does-not-persist-a-failed-native-session
  (doseq [agent-session ["absent" "malformed"]]
    (let [{:keys [root tools prompt-server environment]} (fake-herdr-environment)
          result (invoke (assoc environment "FAKE_HERDR_AGENT_SESSION" agent-session)
                         "hm-register" "Field Core Secondary" "Mind Sol 00f95a" "--session" "s")]
      (try
        (is (not (zero? (:exit result))) agent-session)
        (is (nil? (store/route-for root "00f95a")) agent-session)
        (finally
          ((:close! prompt-server))
          (fs/delete-tree root)
          (fs/delete-tree tools))))))

(deftest public-json-import-wrapper-is-dry-run-by-default-and-requires-apply
  (let [{:keys [source]} (legacy-test/fixture!)
        target (str (fs/path (fs/create-temp-dir {:prefix "hm-cli-import-target-"}) "target"))
        receipt (str (fs/path (fs/create-temp-dir {:prefix "hm-cli-import-receipt-"}) "receipt.edn"))
        dry-run (invoke {} "messenger-clj" "import-json" source "--target" target "--receipt" receipt)]
    (is (zero? (:exit dry-run)) (:err dry-run))
    (is (= :dry-run (:mode (edn/read-string (str/trim (:out dry-run))))))
    (is (not (fs/exists? (store/database-path target))))
    (let [applied (invoke {} "messenger-clj" "import-json" source "--target" target "--receipt" receipt "--apply")]
      (is (zero? (:exit applied)) (:err applied))
      (is (= :applied (:mode (edn/read-string (str/trim (:out applied))))))
      (is (= "NeedsBinding" (:state (store/route-for target "beta")))))))

(deftest public-wrappers-use-one-isolated-typed-database
  (let [root (str (fs/create-temp-dir {:prefix "hm-cli-store-"}))
        tools (fs/create-temp-dir {:prefix "hm-cli-tools-"})
        state (str (fs/path root "fake-herdr-pane"))
        config-home (str (fs/create-temp-dir {:prefix "hm-cli-config-"}))
        prompt-server (fake-prompt-server config-home)
        herdr (fs/path tools "herdr")
        orchestrate (fs/path tools "orchestrate")
        environment {"PATH" (str tools ":" (System/getenv "PATH"))
                     "HM_REGISTRY" root
                     "HM_PRIMARY_ROOT" root
                     "FLOW_ID" "sender" "HM_REGISTRATION_FLOW_ID" "00f95a"
                     "XDG_CONFIG_HOME" config-home
                     "FAKE_HERDR_STATE" state
                     "FAKE_HERDR_PROMPT_LOG" (str (fs/path root "unused-prompts.edn"))}]
    (try
      (fs/copy "test/fake-herdr" herdr)
      (spit (str orchestrate)
            "#!/usr/bin/env bash\ncase \"$1\" in Lock.*) echo 'Locked.{ 1 Test sender [ /tmp ] test }';; Release.*) echo 'Released.{ 1 Test sender [ /tmp ] test }';; esac\n")
      (.setExecutable (java.io.File. (str herdr)) true)
      (.setExecutable (java.io.File. (str orchestrate)) true)

      (let [registered (invoke environment "hm-register" "Field Core Secondary" "Mind Sol 00f95a"
                               "--session" "s" "--native-thread" native-thread
                               "--aspect" "Field" "--topic" "Core" "--layer" "Secondary")]
        (is (zero? (:exit registered)) (:err registered))
        (is (str/includes? (:out registered) "Registered.{ «Field Core Secondary» }")))
      (let [listed (invoke environment "hm-list")]
        (is (zero? (:exit listed)) (:err listed))
        (is (str/includes? (:out listed) "Field Core Secondary\ts\tworking")))
      (let [snapshot (invoke environment "hm-heartbeat-state")
            value (json/parse-string (:out snapshot) true)]
        (is (zero? (:exit snapshot)) (:err snapshot))
        (is (= 2 (:version value)))
        (is (= [{:metaflow "Field Core Secondary" :state "Bound" :session "s"
                 :harness "codex" :held false :transition false}]
               (:routes value)))
        (is (= [] (:retirements value))))
      (let [sent (invoke environment "hm-send" "Field Core Secondary" "isolated-success")]
        (is (zero? (:exit sent)) (:err sent))
        (is (str/includes? (:out sent) "Transported.{ «Field Core Secondary» working }"))
        (is (some #(= :Submitting (:reason %)) (store/attempts-for root "00f95a"))))
      (let [short-body "one\ntwo\nλ"
            long-body (str "line 1 \\\"quoted\\\" \\\\ path\n" (apply str (repeat 36000 "λ🙂"))
                           "\n<pasted_content id=\"abc\">whole</pasted_content>")
            context "context first\nwith UTF-8: 世界"
            verbatim (str "  verbatim starts \\\"quoted\\\" \\\\ path\n" (str/join " " (repeat 50000 "ψ")) "\nverbatim ends  ")
            plural-records [["transcription context" "Keep the original closure text exactly."]
                            ["explicit correction"
                             (str "Correction: Clojure was intended; closure remains in the earlier record.\n"
                                  "Quoted: \\\"Clojure\\\"; path: \\\\source\\file\n"
                                  (apply str (repeat 30000 "δ🙂")))]]
            plural-input (pr-str plural-records)
            short-send (invoke-input environment short-body "hm-send" "Field Core Secondary" "--stdin")
            long-send (invoke-input environment long-body "hm-send" "Field Core Secondary" "--stdin")
            psyche-send (invoke-input environment verbatim "hm-send" "Field Core Secondary" "--psyche" context "--stdin")
            psyches-send (invoke-input environment plural-input "hm-send" "Field Core Secondary" "--psyches" "--stdin")
            envelopes (mapv #(get-in % [:params :text]) @(:requests prompt-server))
            values (mapv read-string envelopes)
            attempts (store/attempts-for root "00f95a")]
        (is (zero? (:exit short-send)) (:err short-send))
        (is (zero? (:exit long-send)) (:err long-send))
        (is (zero? (:exit psyche-send)) (:err psyche-send))
        (is (zero? (:exit psyches-send)) (:err psyches-send))
        (is (> (alength (.getBytes long-body StandardCharsets/UTF_8)) (* 128 1024)))
        (is (> (alength (.getBytes verbatim StandardCharsets/UTF_8)) (* 128 1024)))
        (is (> (alength (.getBytes (nth envelopes 4) StandardCharsets/UTF_8)) (* 128 1024)))
        (is (= 5 (count envelopes)))
        (is (= ["Mind Tests Primary" short-body] (second values)))
        (is (= ["Mind Tests Primary" long-body] (nth values 2)))
        (is (= ["Mind Tests Primary" context verbatim] (nth values 3)))
        (is (= context (second (nth values 3))))
        (is (= (mapv (fn [[record-context record-verbatim]]
                       ["Mind Tests Primary" record-context record-verbatim]) plural-records)
               (nth values 4)))
        (is (not-any? #(str/includes? % "Message too long for a pane") envelopes))
        (is (= #{short-body long-body}
               (set (keep :body (filter #(and (contains? #{:Submitting :sent} (:reason %))
                                              (= :msg (:variant %))
                                              (not= "isolated-success" (:body %)))
                                        attempts)))))
        (let [psyche-attempts (filter #(= :psyche (:variant %)) attempts)]
          (is (= 2 (count psyche-attempts)))
          (is (= #{:Submitting :sent} (set (map :reason psyche-attempts))))
          (is (every? #(= verbatim (:body %)) psyche-attempts))
          (is (every? #(= context (:context %)) psyche-attempts))
          (is (every? #(not (contains? % :part_index)) psyche-attempts))
          (is (every? #(not (contains? % :part_count)) psyche-attempts))
          (is (every? #(= (nth envelopes 3) (:submitted %)) psyche-attempts)))
        (let [plural-attempts (filter #(= :psyches (:variant %)) attempts)]
          (is (= 2 (count plural-attempts)))
          (is (= #{:Submitting :sent} (set (map :reason plural-attempts))))
          (is (every? #(= plural-input (:body %)) plural-attempts))
          (is (every? #(= (nth envelopes 4) (:submitted %)) plural-attempts))))
      (let [moved (invoke environment "hm-move" "Field Core Secondary" "w2"
                          "--session" "s" "--pane-id" "p" "--terminal-id" "t"
                          "--name" "Mind Sol 00f95a" "--agent" "codex"
                          "--native-thread" native-thread "--process-pid" "123")]
        (is (zero? (:exit moved)) (:err moved))
        (is (= "m" (:pane_id (store/route-for root "00f95a")))))
      (let [deregistered (invoke environment "hm-deregister" "Field Core Secondary"
                                 "--session" "s" "--pane-id" "m" "--terminal-id" "t"
                                 "--name" "Mind Sol 00f95a")]
        (is (zero? (:exit deregistered)) (:err deregistered)))
      (let [held (invoke (assoc environment "FAKE_HERDR_AGENT_LIST" "empty")
                         "hm-send" "Field Core Secondary" "isolated-held" "--hold-seconds" "0")]
        (is (= 1 (:exit held)))
        (is (str/includes? (:err held) "TargetNotRegistered: Field Core Secondary"))
        (is (empty? (store/pending-for root "00f95a")) "No named route exists to receive or hold this send"))

      (let [registered (invoke environment "hm-register" "Field Core Secondary" "Mind Sol 00f95a"
                               "--session" "s" "--native-thread" native-thread)]
        (is (zero? (:exit registered)) (:err registered)))
      (let [extra (invoke environment "hm-retire" "Field Core Secondary" "--session" "s")]
        (is (= 2 (:exit extra)))
        (is (str/includes? (:err extra) "retire takes only the metaflow name")))
      (let [unknown (invoke environment "hm-retire" "Mind Missing Primary")]
        (is (= 1 (:exit unknown)))
        (is (str/includes? (:err unknown) "TargetNotRegistered: Mind Missing Primary")))
      (let [gone (invoke (assoc environment "FAKE_HERDR_AGENT_LIST" "empty") "hm-retire" "Field Core Secondary")]
        (is (= 1 (:exit gone)))
        (is (str/includes? (:err gone) "RetireRefused.{ «Field Core Secondary» PaneNotFound }"))
        (is (some? (store/route-for root "00f95a"))))
      (let [retired (invoke environment "hm-retire" "Field Core Secondary")
            marker (store/retirement-for root "00f95a")
            path (get-in marker [:evidence :path])]
        (is (zero? (:exit retired)) (:err retired))
        (is (str/includes? (:out retired) "Retired.{ «Field Core Secondary» }"))
        (is (str/starts-with? path (str (fs/path root "retirement-evidence"))))
        (is (= (hm/sha256 path) (get-in marker [:evidence :sha256])))
        (is (= {:session "s" :name "Mind Sol 00f95a" :pane_id "m" :terminal_id "t" :agent "codex"} (:record marker)))
        (is (= native-thread (:native_thread marker)))
        (is (= "sender" (:retired_by marker)))
        (let [again (invoke environment "hm-retire" "Field Core Secondary")]
          (is (= 1 (:exit again)))
          (is (str/includes? (:err again) "RetireRefused.{ «Field Core Secondary» AlreadyRetired }")))
        (is (nil? (store/route-for root "00f95a")))
        (let [snapshot (invoke environment "hm-heartbeat-state")
              value (json/parse-string (:out snapshot) true)]
          (is (zero? (:exit snapshot)) (:err snapshot))
          (is (= ["Mind Tests Primary"] (mapv :metaflow (:routes value))))
          (is (= ["Field Core Secondary"] (mapv :metaflow (:retirements value)))))
        (is (empty? (fs/glob root "**/*.edn"))
            "operational commands create no EDN authority files"))
      (finally
        ((:close! prompt-server))
        (fs/delete-tree root)
        (fs/delete-tree config-home)
        (fs/delete-tree tools)))))

(deftest public-wrapper-entrypoints-refuse-id-targets-before-native-or-store-effects
  (let [{:keys [root tools prompt-server environment]} (fake-herdr-environment)]
    (try
      (doseq [[wrapper args]
              [["hm-send" ["00f95a" "body"]]
               ["hm-send-abrupt" ["00f95a" "body"]]
               ["hm-register" ["00f95a" "agent" "--session" "s"]]
               ["hm-repair" ["00f95a" "--pending-id" "attempt" "--session" "s" "--pane-id" "p" "--terminal-id" "t" "--name" "agent" "--agent" "codex"]]
               ["hm-deregister" ["00f95a" "--session" "s" "--pane-id" "p" "--terminal-id" "t" "--name" "agent"]]
               ["hm-rebind" ["00f95a" "new" "--old-name" "old" "--session" "s" "--pane-id" "p" "--terminal-id" "t" "--agent" "codex" "--native-thread" native-thread]]
               ["hm-move" ["00f95a" "w2" "--session" "s" "--pane-id" "p" "--terminal-id" "t" "--name" "agent" "--agent" "codex" "--native-thread" native-thread "--process-pid" "123"]]
               ["hm-retire" ["00f95a"]]
               ["messenger-clj" ["import-retirement" "00f95a" "--session" "s" "--pane-id" "p" "--terminal-id" "t" "--name" "agent" "--agent" "codex" "--native-thread" native-thread "--evidence" "/not-opened" "--evidence-sha256" "unused"]]]]
        (let [result (apply invoke environment wrapper args)]
          (is (= 1 (:exit result)) (str wrapper ": " (:err result)))
          (is (str/includes? (:err result) "MetaflowRequired") wrapper)
          (is (not (str/includes? (:err result) "00f95a")) wrapper)))
      (is (empty? @(:requests prompt-server)))
      (is (nil? (store/route-for root "00f95a")))
      (finally ((:close! prompt-server)) (fs/delete-tree root) (fs/delete-tree tools)))))
