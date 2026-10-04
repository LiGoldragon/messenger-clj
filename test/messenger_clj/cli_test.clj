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

(defn invoke [environment wrapper & arguments]
  (apply shell {:out :string :err :string :continue true :extra-env environment}
         (str (fs/absolutize (fs/path "bin" wrapper))) arguments))
(defn invoke-input [environment input wrapper & arguments]
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
        orchestrate (fs/path tools "orchestrate")]
    (fs/copy "test/fake-herdr" herdr)
    (spit (str orchestrate)
          "#!/usr/bin/env bash\ncase \"$1\" in Lock.*) echo 'Locked.{ 1 Test sender [ /tmp ] test }';; Release.*) echo 'Released.{ 1 Test sender [ /tmp ] test }';; esac\n")
    (.setExecutable (java.io.File. (str herdr)) true)
    (.setExecutable (java.io.File. (str orchestrate)) true)
    {:root root :tools tools :prompt-log prompt-log :prompt-server prompt-server
     :environment {"PATH" (str tools ":" (System/getenv "PATH"))
                   "HM_REGISTRY" root "HM_PRIMARY_ROOT" root "FLOW_ID" "sender"
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
        (is (zero? (:exit (invoke env "hm-register" "00f95a" "Mind Sol 00f95a"
                                  "--session" "s" "--native-thread" native-thread))))
        (let [sent (invoke env "hm-send" "00f95a" (str "wait-" wait-result) "--wait-presented")
              attempts (store/attempts-for root "00f95a")]
          (is (= expected-exit (:exit sent)) (str wait-result ": " (:err sent)))
          (is (str/includes? (str (:out sent) (:err sent)) (name expected-grade)))
          (is (= 1 (count @(:requests prompt-server))))
          (is (= (str "#msg [\"sender\" \"wait-" wait-result "\"]")
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
                         "hm-register" "00f95a" "Mind Sol 00f95a" "--session" "s")]
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
                     "FLOW_ID" "sender"
                     "XDG_CONFIG_HOME" config-home
                     "FAKE_HERDR_STATE" state
                     "FAKE_HERDR_PROMPT_LOG" (str (fs/path root "unused-prompts.edn"))}]
    (try
      (fs/copy "test/fake-herdr" herdr)
      (spit (str orchestrate)
            "#!/usr/bin/env bash\ncase \"$1\" in Lock.*) echo 'Locked.{ 1 Test sender [ /tmp ] test }';; Release.*) echo 'Released.{ 1 Test sender [ /tmp ] test }';; esac\n")
      (.setExecutable (java.io.File. (str herdr)) true)
      (.setExecutable (java.io.File. (str orchestrate)) true)

      (let [registered (invoke environment "hm-register" "00f95a" "Mind Sol 00f95a"
                               "--session" "s" "--native-thread" native-thread)]
        (is (zero? (:exit registered)) (:err registered))
        (is (str/includes? (:out registered) "Registered 00f95a")))
      (let [listed (invoke environment "hm-list")]
        (is (zero? (:exit listed)) (:err listed))
        (is (str/includes? (:out listed) "00f95a\tMind Sol 00f95a\ts\tworking")))
      (let [snapshot (invoke environment "hm-heartbeat-state")
            value (json/parse-string (:out snapshot) true)]
        (is (zero? (:exit snapshot)) (:err snapshot))
        (is (= 1 (:version value)))
        (is (= [{:flow "00f95a"
                 :route {:session "s" :name "Mind Sol 00f95a" :pane_id "p" :terminal_id "t"
                         :agent "codex" :native_thread native-thread :state "Bound"}}]
               (:routes value)))
        (is (= [] (:retirements value))))
      (let [sent (invoke environment "hm-send" "00f95a" "isolated-success")]
        (is (zero? (:exit sent)) (:err sent))
        (is (str/includes? (:out sent) "Transported.{ 00f95a working }"))
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
            short-send (invoke-input environment short-body "hm-send" "00f95a" "--stdin")
            long-send (invoke-input environment long-body "hm-send" "00f95a" "--stdin")
            psyche-send (invoke-input environment verbatim "hm-send" "00f95a" "--psyche" context "--stdin")
            psyches-send (invoke-input environment plural-input "hm-send" "00f95a" "--psyches" "--stdin")
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
        (is (= ["sender" short-body] (second values)))
        (is (= ["sender" long-body] (nth values 2)))
        (is (= ["sender" context verbatim] (nth values 3)))
        (is (= context (second (nth values 3))))
        (is (= (mapv (fn [[record-context record-verbatim]]
                       ["sender" record-context record-verbatim]) plural-records)
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
      (let [moved (invoke environment "hm-move" "00f95a" "w2"
                          "--session" "s" "--pane-id" "p" "--terminal-id" "t"
                          "--name" "Mind Sol 00f95a" "--agent" "codex"
                          "--native-thread" native-thread "--process-pid" "123")]
        (is (zero? (:exit moved)) (:err moved))
        (is (= "m" (:pane_id (store/route-for root "00f95a")))))
      (let [deregistered (invoke environment "hm-deregister" "00f95a"
                                 "--session" "s" "--pane-id" "m" "--terminal-id" "t"
                                 "--name" "Mind Sol 00f95a")]
        (is (zero? (:exit deregistered)) (:err deregistered)))
      (let [held (invoke (assoc environment "FAKE_HERDR_AGENT_LIST" "empty")
                         "hm-send" "00f95a" "isolated-held" "--hold-seconds" "0")]
        (is (= 1 (:exit held)))
        (is (str/includes? (:err held) "Held.{ 00f95a RepairRequired"))
        (is (= "isolated-held" (:message (first (store/pending-for root "00f95a"))))))

      (let [registered (invoke environment "hm-register" "00f95a" "Mind Sol 00f95a"
                               "--session" "s" "--native-thread" native-thread)]
        (is (zero? (:exit registered)) (:err registered)))
      (let [extra (invoke environment "hm-retire" "00f95a" "--session" "s")]
        (is (= 2 (:exit extra)))
        (is (str/includes? (:err extra) "retire takes only the flow id")))
      (let [unknown (invoke environment "hm-retire" "abcdef")]
        (is (= 1 (:exit unknown)))
        (is (str/includes? (:err unknown) "RetireRefused.{ abcdef UnknownFlow }")))
      (let [gone (invoke (assoc environment "FAKE_HERDR_AGENT_LIST" "empty") "hm-retire" "00f95a")]
        (is (= 1 (:exit gone)))
        (is (str/includes? (:err gone) "RetireRefused.{ 00f95a PaneNotFound }"))
        (is (some? (store/route-for root "00f95a"))))
      (let [retired (invoke environment "hm-retire" "00f95a")
            marker (store/retirement-for root "00f95a")
            path (get-in marker [:evidence :path])]
        (is (zero? (:exit retired)) (:err retired))
        (is (str/includes? (:out retired) (str "evidence " path)))
        (is (str/starts-with? path (str (fs/path root "retirement-evidence"))))
        (is (= (hm/sha256 path) (get-in marker [:evidence :sha256])))
        (is (= {:session "s" :name "Mind Sol 00f95a" :pane_id "m" :terminal_id "t" :agent "codex"} (:record marker)))
        (is (= native-thread (:native_thread marker)))
        (is (= "sender" (:retired_by marker)))
        (let [again (invoke environment "hm-retire" "00f95a")]
          (is (= 1 (:exit again)))
          (is (str/includes? (:err again) "RetireRefused.{ 00f95a AlreadyRetired }")))
        (is (nil? (store/route-for root "00f95a")))
        (let [snapshot (invoke environment "hm-heartbeat-state")
              value (json/parse-string (:out snapshot) true)]
          (is (zero? (:exit snapshot)) (:err snapshot))
          (is (= [] (:routes value)))
          (is (= ["00f95a"] (mapv :flow (:retirements value)))))
        (is (empty? (fs/glob root "**/*.edn"))
            "operational commands create no EDN authority files"))
      (finally
        ((:close! prompt-server))
        (fs/delete-tree root)
        (fs/delete-tree config-home)
        (fs/delete-tree tools)))))
