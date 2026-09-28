(ns messenger-clj.core-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [malli.core]
            [messenger-clj.core :as hm]
            [messenger-clj.typed-store :as store]
            [babashka.fs :as fs]))

(def route {:session "s" :name "Mind Sol 00f95a" :pane_id "p" :terminal_id "t"
            :agent "codex" :native_thread "00000000-0000-0000-0000-000000000000"})

(defn pass-reservation [_ f] (f))
(defn prompted [pane-route]
  {:type "agent_prompted"
   :agent (assoc (select-keys pane-route [:name :pane_id :terminal_id :agent])
                 :agent_status "idle")})
(defn persist-route! [root-path value]
  (store/put-route! root-path "00f95a" value))
(defn fake-transport [live-agent target-agent processes prompts]
  (reify hm/HerdrTransport
    (live-agents* [_] [live-agent])
    (target-agent* [_ _]
      {:agent (if (contains? target-agent :agent_session)
                target-agent
                (assoc target-agent :agent_session
                       {:source (str "herdr:" (:agent target-agent)) :kind "id"
                        :agent (:agent target-agent) :value (:native_thread route)}))})
    (process-info* [_ _] {:process_info {:foreground_processes processes}})
    (pane* [_ route] {:pane (select-keys route [:pane_id :terminal_id :agent])})
    (move-pane* [_ route _ _] {:move_result {:previous_pane_id (:pane_id route) :previous_workspace_id "w1"
                                             :pane (assoc (select-keys route [:terminal_id :agent]) :pane_id "moved" :workspace_id "w2")}})
    (send-keys* [_ _ _] {:ok true})
    (prompt!* [_ _ _ _] (swap! prompts inc) {:ok true})))

(defn move-transport [route mode moves]
  (reify hm/HerdrTransport
    (live-agents* [_]
      (let [pane @moves]
        (if (or (and (= mode :ambiguous) (not= pane "p"))
                (and (= mode :compensate) (= pane "m")))
          []
          [(assoc route :pane_id pane)])))
    (target-agent* [_ route] {:agent route})
    ;; Codex remote panes do not include the stored UUID in argv; PID plus the
    ;; verified terminal and harness identity is Python's accepted evidence.
    (process-info* [_ _] {:process_info {:foreground_processes [{:pid 123 :argv ["codex" "remote"]}]}})
    (pane* [_ requested] {:pane {:pane_id (:pane_id requested) :terminal_id (:terminal_id route) :agent (:agent route)
                                 :workspace_id (if (= "p" (:pane_id requested)) "w1" (if (= "m" (:pane_id requested)) "w2" "w1"))}})
    (move-pane* [_ requested workspace _]
      (let [next-pane (if (= "p" (:pane_id requested)) "m" "r")
            previous-workspace (if (= "p" (:pane_id requested)) "w1" "w2")]
        (reset! moves next-pane)
        {:move_result {:previous_pane_id (:pane_id requested) :previous_workspace_id previous-workspace
                       :pane {:pane_id next-pane :terminal_id (:terminal_id route) :agent (:agent route) :workspace_id workspace}}}))
    (send-keys* [_ _ _] {:ok true})
    (prompt!* [_ _ _ _] {:ok true})))

(deftest identifiers-and-title-fallback-are-strict
  (is (false? (malli.core/validate hm/FlowId "../../etc/x")))
  (is (thrown? Exception (hm/flow-id! "../../etc/x")))
  (is (thrown? Exception (hm/flow-id! "a b!")))
  (is (= {:session "test" :pane_id "w1:p2"} (hm/parse-pane "test:w1:p2")))
  (with-redefs [hm/live-agents (constantly [(assoc route :name "Mind Sol 00f95a")])]
    (is (= "p" (:pane_id (first (hm/resolve-send-route "00f95a" nil nil))))))
  (with-redefs [hm/live-agents (constantly [(assoc route :name "A 00f95a") (assoc route :name "B 00f95a")])]
    (is (thrown? Exception (hm/resolve-send-route "00f95a" nil nil)))))

(deftest machine-relay-is-an-unchanged-edn-round-trip
  (let [line (hm/relay "00f95a" "e51411" "receipt")
        value (hm/read-pane-message line)]
    (is (= "#msg [\"00f95a\" \"receipt\"]" line))
    (is (= ["00f95a" "receipt"] value))
    (is (= value (read-string line)))))

(deftest large-multiline-utf8-and-pasted-content-are-sent-whole
  (let [body (str "first\n" (apply str (repeat 24000 "λ🙂"))
                  "\n<pasted_content id=\"abc\">verbatim</pasted_content>")
        line (hm/message-envelope "sender" {:variant :msg :body body})]
    (is (> (alength (.getBytes body java.nio.charset.StandardCharsets/UTF_8)) (* 128 1024)))
    (is (= ["sender" body] (hm/read-pane-message line)))
    (is (str/includes? line "pasted_content"))))

(deftest psyche-edn-round-trip-keeps-context-and-whole-verbatim-exactly
  (let [context "why this matters\nwith detail"
        verbatim " living's λ words\nexactly  "
        request {:variant :psyche :context context :body verbatim}
        line (hm/message-envelope "e51411" request)]
    (is (str/starts-with? line "#psyche [\"e51411\""))
    (is (= ["e51411" context verbatim] (hm/read-psyche-message line)))
    (is (= ["e51411" context verbatim] (read-string line)))
    (is (thrown? Exception
                 (hm/read-psyche-message "#psyche [\"sender\" \"context\" \"1/1\" \"x\"]")))))

(deftest long-multiline-utf8-psyche-is-one-whole-envelope-with-no-numbering
  (let [context "word-separated Unicode"
        verbatim (str "  λ🙂 one\n\ttwo  " (str/join " " (repeat 25000 "世界")) "  ")
        requests (hm/psyche-requests "sender" context verbatim)
        envelope (hm/message-envelope "sender" (first requests))]
    (is (= 1 (count requests)))
    (is (> (alength (.getBytes verbatim java.nio.charset.StandardCharsets/UTF_8)) (* 128 1024)))
    (is (= ["sender" context verbatim] (hm/read-psyche-message envelope)))
    (is (= (seq (.getBytes verbatim java.nio.charset.StandardCharsets/UTF_8))
           (seq (.getBytes (nth (hm/read-psyche-message envelope) 2)
                           java.nio.charset.StandardCharsets/UTF_8))))
    (is (not (re-find #"[0-9]+/[0-9]+" envelope)))
    (is (not (contains? (first requests) :part_index)))
    (is (not (contains? (first requests) :part_count)))))

(deftest unavailable-herdr-holds-one-whole-psyche-envelope-durably
  (let [root-path (str (fs/create-temp-dir {:prefix "hm-psyche-held-"}))
        verbatim (str "first\n" (apply str (repeat 900 "秘密")) "\nlast")]
    (binding [hm/*root* root-path hm/*flow-id* "sender"
              hm/*with-reservation* pass-reservation]
      (with-redefs [hm/live-agents (constantly [])]
        (let [diagnostic (try (hm/send-psyche! "00f95a" "context" verbatim false nil 0)
                              (catch Exception error (.getMessage error)))
              pending (first (store/pending-for root-path "00f95a"))]
          (is (re-find #"Held\.\{ 00f95a RepairRequired" diagnostic))
          (is (= verbatim (:message pending)))
          (is (= "context" (:context pending)))
          (is (= :psyche (:variant pending)))
          (is (not (contains? pending :part_index)))
          (is (not (contains? pending :part_count))))))))

(deftest psyche-herdr-failure-is-one-whole-uncertain-attempt
  (let [root-path (str (fs/create-temp-dir {:prefix "hm-psyche-partial-"}))
        calls (atom [])
        live (assoc route :interactive_ready true :agent_status "working")
        process [{:argv ["codex" "--thread" (:native_thread route)]}]
        transport (reify hm/HerdrTransport
                    (live-agents* [_] [live])
                    (target-agent* [_ _] {:agent live})
                    (process-info* [_ _] {:process_info {:foreground_processes process}})
                    (pane* [_ _] {:pane route})
                    (move-pane* [_ _ _ _] {:move_result {}})
                    (send-keys* [_ _ _] {:ok true})
                    (prompt!* [_ _ envelope _]
                      (swap! calls conj envelope)
                      (hm/fail "simulated Herdr failure")))
        verbatim (str/join " " (repeat 1500 "λword"))]
    (binding [hm/*root* root-path hm/*flow-id* "sender"
              hm/*with-reservation* pass-reservation hm/*transport* transport]
      (persist-route! root-path route)
      (let [diagnostic (try (hm/send-psyche! "00f95a" "context" verbatim false nil 0)
                            (catch Exception error (.getMessage error)))
            attempts (filter #(= :psyche (:variant %))
                             (store/attempts-for root-path "00f95a"))]
        (is (re-find #"Uncertain\.\{ 00f95a" diagnostic))
        (is (= 1 (count @calls)))
        (is (= ["sender" "context" verbatim]
               (hm/read-psyche-message (first @calls))))
        (is (= #{:Submitting :Uncertain} (set (map :reason attempts))))
        (is (every? #(= (first @calls) (:submitted %)) attempts))
        (is (every? #(not (contains? % :part_index)) attempts))
        (is (every? #(not (contains? % :part_count)) attempts))))))

(deftest nested-pane-message-is-rejected-before-send
  (let [secret "DIAGNOSTIC_SECRET_7391"
        diagnostic (try (hm/send! "00f95a" (str "#msg [\"sender\" \"" secret "\"]") false nil)
                        (catch Exception error (.getMessage error)))]
    (is (re-find #"Nested complete #msg or #psyche" diagnostic))
    (is (not (str/includes? diagnostic secret))))
  (is (re-find #"Nested complete #msg or #psyche"
               (binding [hm/*flow-id* "sender"]
                 (try (hm/send-psyche! "00f95a" "context" "#psyche [\"sender\" \"context\" \"v\"]" false nil)
                      (catch Exception error (.getMessage error)))))))

(deftest closed-core-records-and-deep-relays-are-rejected
  (is (false? (malli.core/validate hm/RouteBinding (assoc route :unexpected true))))
  (is (hm/nested-relay? "#msg [\"sender\" \"body\"]"))
  (is (hm/nested-relay? "#psyche [\"sender\" \"context\" \"words\"]"))
  (is (false? (hm/nested-relay? "prose mentioning #msg is allowed")))
  (is (false? (hm/nested-relay? "Machine.Relay.{ relayed }")))
  (is (false? (hm/nested-relay? "#msg [\"sender\" \"body\"] trailing")))
  (is (thrown? Exception (hm/read-pane-message "[\"sender\" \"body\"]"))))

(deftest abrupt-send-is-durable-gated-and-agent-specific
  (let [root-path (str (fs/create-temp-dir {:prefix "hm-abrupt-"}))
        process [{:argv ["codex" "--thread" (:native_thread route)]}]
        run (fn [record reply target]
              (let [events (atom [])
                    transport (reify hm/HerdrTransport
                                (live-agents* [_] [(assoc record :interactive_ready true :agent_status "working")])
                                (target-agent* [_ _] {:agent (assoc target :interactive_ready true :agent_status "working")})
                                (process-info* [_ _] {:process_info {:foreground_processes process}})
                                (pane* [_ _] {:pane {}})
                                (move-pane* [_ _ _ _] {:move_result {}})
                                (send-keys* [_ _ key] (swap! events conj [:key key]) {:ok true})
                                (prompt!* [_ _ _ wait?] (swap! events conj [:prompt wait?]) reply))]
                (binding [hm/*root* root-path hm/*flow-id* "sender" hm/*with-reservation* pass-reservation hm/*transport* transport]
                  (persist-route! root-path record)
                  [(try (hm/send-abrupt! "00f95a" "receipt" true)
                        (catch Exception error (.getMessage error))) @events])))]
    (let [[result events] (run route (prompted route) route)]
      (is (= "Presented.{ 00f95a working }" result))
      (is (= [[:key "esc"] [:prompt true]] events)))
    (let [[result events] (run (assoc route :agent "claude")
                               (prompted (assoc route :agent "claude"))
                               (assoc route :agent "claude"))]
      (is (= "Presented.{ 00f95a working }" result))
      (is (= [[:key "esc"] [:key "esc"] [:prompt true] [:key "enter"]] events)))
    (let [[result events] (run route {:ok true} route)]
      (is (re-find #"Uncertain\.\{ 00f95a attempt-.*Escape was sent" result))
      (is (= [[:key "esc"] [:prompt true]] events)))
    (let [[result events] (run route (prompted route) (assoc route :terminal_id "other"))]
      (is (re-find #"Held\.\{ 00f95a" result))
      (is (empty? events)))))

(deftest datalevin-pod-stores-and-queries-typed-attempts
  (let [root (str (fs/create-temp-dir {:prefix "hm-datalevin-"}))
        attempt {:id "attempt-1" :at "2026-09-25T00:00:00Z" :flow "00f95a"
                 :reason :NotRegistered :grade :Held}]
    (store/put-attempt! root attempt)
    (is (= [attempt] (store/attempts-for root "00f95a")))))

(deftest concurrent-sends-wait-for-one-path-lock-with-unique-operation-names
  (let [root-path (str (fs/create-temp-dir {:prefix "messenger-clj-concurrent-"}))
        active (atom false)
        next-id (atom 100)
        lock-names (atom [])
        conflict-seen (promise)
        first-prompt (promise)
        prompts (atom [])
        live-route (assoc route :interactive_ready true :agent_status "working")
        transport (reify hm/HerdrTransport
                    (live-agents* [_] [live-route])
                    (target-agent* [_ _] {:agent live-route})
                    (process-info* [_ _]
                      {:process_info {:foreground_processes
                                      [{:argv ["codex" "--thread" (:native_thread route)]}]}})
                    (pane* [_ _] {:pane route})
                    (move-pane* [_ _ _ _] {:move_result {}})
                    (send-keys* [_ _ _] {:ok true})
                    (prompt!* [_ _ envelope _]
                      (swap! prompts conj envelope)
                      (when (= 1 (count @prompts))
                        (deliver first-prompt true)
                        (deref conflict-seen 2000 false))
                      {:ok true}))
        fake-shell (fn [_ executable query]
                     (is (= "orchestrate" executable))
                     (if (str/starts-with? query "Lock.")
                       (let [operation (second (re-find #"Lock\.\{\s+(\S+)" query))]
                         (swap! lock-names conj operation)
                         (if (compare-and-set! active false true)
                           {:exit 0 :out (str "Locked.{ " (swap! next-id inc)
                                              " " operation " sender [ /tmp ] test }\n") :err ""}
                           (do (deliver conflict-seen true)
                               {:exit 1 :out "LockRejected.PathOverlap.{ busy }\n" :err ""})))
                       (do (reset! active false)
                           {:exit 0 :out "Released.{ 1 }\n" :err ""})))
        send-one (fn [body]
                   (binding [hm/*root* root-path hm/*flow-id* "sender"
                             hm/*transport* transport hm/*reservation-wait-ms* 2000
                             hm/*reservation-retry-ms* 1]
                     (hm/send! "00f95a" body false nil 0)))]
    (persist-route! root-path route)
    (binding [hm/*shell* fake-shell]
      (let [first-send (future (send-one "first"))
            _ (is (true? (deref first-prompt 2000 false)))
            second-send (future (send-one "second"))]
        (is (= "Transported.{ 00f95a working }" (deref first-send 3000 ::timeout)))
        (is (= "Transported.{ 00f95a working }" (deref second-send 3000 ::timeout)))))
    (is (= #{"#msg [\"sender\" \"first\"]" "#msg [\"sender\" \"second\"]"}
           (set @prompts)))
    (is (= 2 (count @prompts)))
    (is (= 2 (count (set @lock-names))))
    (is (every? #(str/starts-with? % "MessengerCljDelivery-") @lock-names))
    (let [counts (frequencies (map (juxt :body :reason)
                                   (store/attempts-for root-path "00f95a")))]
      (is (= 1 (get counts ["first" :Submitting])))
      (is (= 1 (get counts ["first" :sent])))
      (is (= 1 (get counts ["second" :Submitting])))
      (is (= 1 (get counts ["second" :sent]))))))

(deftest reservation-timeout-prompts-and-writes-nothing
  (let [root-path (str (fs/create-temp-dir {:prefix "messenger-clj-timeout-"}))
        prompted (atom false)
        transport (fake-transport route route
                                  [{:argv ["codex" "--thread" (:native_thread route)]}]
                                  prompted)]
    (persist-route! root-path route)
    (binding [hm/*root* root-path hm/*flow-id* "sender" hm/*transport* transport
              hm/*reservation-wait-ms* 0 hm/*reservation-retry-ms* 0]
      (binding [hm/*shell* (fn [& _]
                            {:exit 1 :out "LockRejected.PathOverlap.{ busy }\n" :err ""})]
        (is (re-find #"Reservation timed out after 0ms"
                     (try (hm/send! "00f95a" "held" false nil 0)
                          (catch Exception error (.getMessage error)))))))
    (is (false? @prompted))
    (is (empty? (store/attempts-for root-path "00f95a")))))

(deftest held-unregistered-writes-linked-pending-without-prompt
  (let [root (str (fs/create-temp-dir {:prefix "hm-held-"}))
        prompted (atom false)]
    (binding [hm/*root* root hm/*flow-id* "sender" hm/*with-reservation* pass-reservation]
      (with-redefs [hm/live-agents (constantly [])
                    hm/verify-target! (fn [_] route)
                    hm/direct-prompt! (fn [& _] (reset! prompted true))]
        (let [message (try (hm/send! "00f95a" "held body" false nil)
                           (catch Exception error (.getMessage error)))]
          (is (re-find #"Held\.\{ 00f95a RepairRequired" message))
          (is (false? @prompted))
          (is (= 1 (count (store/pending-for root "00f95a"))))
          (is (= :RepairRequired
                 (get-in (first (store/pending-for root "00f95a"))
                         [:attempt :reason]))))))))

(deftest needs-binding-route-is-held-before-herdr
  (let [root-path (str (fs/create-temp-dir {:prefix "hm-needs-binding-"}))
        prompted (atom false)]
    (binding [hm/*root* root-path hm/*flow-id* "sender"
              hm/*with-reservation* pass-reservation]
      (persist-route! root-path (dissoc route :native_thread))
      (with-redefs [hm/live-agents (constantly [route])
                    hm/direct-prompt! (fn [& _] (reset! prompted true))]
        (let [message (try (hm/send! "00f95a" "held body" false nil 0)
                           (catch Exception error (.getMessage error)))]
          (is (re-find #"Held\.\{ 00f95a RepairRequired" message))
          (is (false? @prompted))
          (is (= :RepairRequired
                 (get-in (first (store/pending-for root-path "00f95a"))
                         [:attempt :reason]))))))))

(deftest fallback-order-and-grades-are-explicit
  (with-redefs [hm/live-agents (constantly [route])]
    (let [[resolved fallback?] (hm/resolve-send-route "00f95a" route nil)]
      (is (= route (dissoc resolved :state)))
      (is (false? fallback?))))
  (with-redefs [hm/live-agents (constantly [(assoc route :pane_id "stale")])]
    (is (thrown? Exception (hm/resolve-send-route "00f95a" route nil))))
  (with-redefs [hm/live-agents (constantly [(assoc route :session "override" :pane_id "chosen")])]
    (is (= "chosen" (:pane_id (first (hm/resolve-send-route "00f95a" nil "override:chosen")))))))

(deftest plain-wait-presented-needs-the-supported-observation
  (let [root-path (str (fs/create-temp-dir {:prefix "hm-presented-"}))
        invoke (fn [reply]
                 (let [calls (atom [])]
                   (binding [hm/*root* root-path hm/*flow-id* "sender" hm/*with-reservation* pass-reservation]
                     (persist-route! root-path route)
                     (with-redefs [hm/live-agents (constantly [route])
                                   hm/verify-target! (fn [_] route)
                                   hm/direct-prompt! (fn [& args] (swap! calls conj args) reply)]
                       [(try (hm/send! "00f95a" "plain wait" true nil)
                             (catch Exception error (.getMessage error)))
                        @calls]))))]
    (let [[result calls] (invoke (prompted route))]
      (is (= "Presented.{ 00f95a unknown }" result))
      (is (= 1 (count calls)))
      (is (true? (nth (first calls) 2))))
    (let [[result calls] (invoke {:ok true})]
      (is (re-find #"Uncertain\.\{ 00f95a" result))
      (is (= 1 (count calls)))
      (is (true? (nth (first calls) 2))))
    (let [[result calls] (invoke {:presented true})]
      (is (re-find #"Uncertain\.\{ 00f95a" result))
      (is (= 1 (count calls))))
    (let [[result calls] (invoke (prompted (assoc route :pane_id "other")))]
      (is (re-find #"Uncertain\.\{ 00f95a" result))
      (is (= 1 (count calls))))))

(defn herdr-agent [overrides]
  (let [agent (merge {:agent "claude" :agent_status "idle" :cwd "/home/li/primary"
                      :focused false :foreground_cwd "/home/li/primary" :interactive_ready true
                      :name "Mind Sol 00f95a" :pane_id "moved" :revision 7 :state_change_seq 42
                      :tab_id "w1:t3" :terminal_id "t" :workspace_id "w1"}
                     overrides)]
    (if (contains? overrides :agent_session)
      agent
      (assoc agent :agent_session {:source (str "herdr:" (:agent agent)) :kind "id"
                                   :agent (:agent agent) :value (:native_thread route)}))))

(defn fallback-transport [session agents argv prompts & {:keys [reply target]}]
  (reify hm/HerdrTransport
    (live-agents* [_] (map #(assoc % :session session) (vals agents)))
    (target-agent* [_ requested]
      {:agent (or target (get agents (:pane_id requested)))})
    (process-info* [_ _]
      {:process_info {:foreground_processes [{:pid 7 :argv argv}]}})
    (pane* [_ _] (throw (ex-info "unexpected pane" {})))
    (move-pane* [_ _ _ _] (throw (ex-info "unexpected move" {})))
    (send-keys* [_ _ _] (throw (ex-info "unexpected keys" {})))
    (prompt!* [_ requested envelope wait?]
      (swap! prompts conj {:pane (:pane_id requested) :envelope envelope :wait wait?})
      (or reply (prompted (get agents (:pane_id requested)))))))

(defn g1-send [root-path transport & args]
  (binding [hm/*root* root-path hm/*flow-id* "sender"
            hm/*with-reservation* pass-reservation hm/*transport* transport]
    (try (apply hm/send! args) (catch Exception error (.getMessage error)))))

(deftest g1-stored-name-is-refreshed-on-the-same-live-identity
  (let [root-path (str (fs/create-temp-dir {:prefix "hm-g1-stored-"}))
        prompts (atom [])
        native (:native_thread route)
        renamed "Psyche Opus 88475f"
        transport (fallback-transport "s" {"p" (herdr-agent {:pane_id "p" :name renamed :agent "codex"})}
                                      ["codex" "--thread" native] prompts)]
    (persist-route! root-path route)
    (is (= "Transported.{ 00f95a idle }"
           (g1-send root-path transport "00f95a" "after a rename" false nil 0)))
    (is (= 1 (count @prompts)))
    (is (= {:pane "p" :wait false}
           (select-keys (first @prompts) [:pane :wait])))
    (is (= "#msg [\"sender\" \"after a rename\"]" (:envelope (first @prompts))))
    (let [sent (first (filter #(= :sent (:reason %))
                              (store/attempts-for root-path "00f95a")))]
      (is (= :Transported (:grade sent)))
      (is (= {:session "s" :name renamed :pane_id "p"
              :terminal_id "t" :agent "codex" :native_thread native
              :state "Bound"}
             (:binding sent))))))

(deftest g1-reused-pane-and-duplicate-stable-identities-fail-before-prompt
  (let [root-path (str (fs/create-temp-dir {:prefix "hm-g1-reused-"}))
        native (:native_thread route)
        invoke (fn [agents]
                 (let [prompts (atom [])
                       transport (fallback-transport "s" agents ["codex" "--thread" native] prompts)]
                   (persist-route! root-path route)
                   [(g1-send root-path transport "00f95a" "must stay held" false nil 0) @prompts]))]
    (let [[result prompts] (invoke {"p" (herdr-agent {:pane_id "p" :terminal_id "replacement" :agent "codex"})})]
      (is (re-find #"Held\.\{ 00f95a RepairRequired" result))
      (is (empty? prompts)))
    (let [[result prompts] (invoke {"one" (herdr-agent {:pane_id "p" :agent "codex"})
                                    "two" (herdr-agent {:pane_id "p" :agent "codex"})})]
      (is (re-find #"Held\.\{ 00f95a RepairRequired" result))
      (is (empty? prompts)))))

(deftest missing-row-is-held-for-judgment-then-repaired-and-delivered-once
  (let [root-path (str (fs/create-temp-dir {:prefix "hm-send-repair-missing-"}))
        prompts (atom [])
        live (herdr-agent {:pane_id "w17:p1" :name "Psyche Opus 00f95a"
                           :terminal_id "term-live" :agent "claude"})
        transport (fallback-transport "messaging-build" {"w17:p1" live}
                                      ["claude" "--dangerously-skip-permissions"] prompts)]
    (let [held (g1-send root-path transport "00f95a" "repair then send" false nil 0)
          pending (first (store/pending-for root-path "00f95a"))
          pending-id (get-in pending [:attempt :id])]
      (is (re-find #"Held\.\{ 00f95a RepairRequired" held))
      (is (empty? @prompts))
      (is (= 1 (count (filter #(= :RepairCandidate (:reason %))
                              (store/attempts-for root-path "00f95a")))))
      (binding [hm/*root* root-path hm/*flow-id* "sender"
                hm/*with-reservation* pass-reservation hm/*transport* transport]
        (is (= "Transported.{ 00f95a idle }"
               (hm/repair! "00f95a" pending-id "messaging-build" "w17:p1"
                           "term-live" "Psyche Opus 00f95a" "claude")))
        (is (thrown? Exception
                     (hm/repair! "00f95a" pending-id "messaging-build" "w17:p1"
                                 "term-live" "Psyche Opus 00f95a" "claude")))))
    (is (= [{:pane "w17:p1" :envelope "#msg [\"sender\" \"repair then send\"]" :wait false}]
           @prompts))
    (let [stored (store/route-for root-path "00f95a")
          attempts (store/attempts-for root-path "00f95a")]
      (is (= "term-live" (:terminal_id stored)))
      (is (= (:native_thread route) (:native_thread stored)))
      (is (= 1 (count (filter #(= :RouteRepaired (:reason %)) attempts))))
      (is (= 1 (count (filter #(= :sent (:reason %)) attempts)))))))

(deftest malformed-native-thread-is-held-and-never-inferred-from-argv
  (let [root-path (str (fs/create-temp-dir {:prefix "hm-send-repair-malformed-"}))
        prompts (atom [])
        malformed (assoc route :native_thread "--dangerously-skip-permissions")
        live (herdr-agent {:pane_id "p" :name (:name route) :agent "codex"})
        transport (fallback-transport "s" {"p" live}
                                      ["codex" "--dangerously-skip-permissions"] prompts)]
    (persist-route! root-path malformed)
    (is (re-find #"Held\.\{ 00f95a RepairRequired"
                 (g1-send root-path transport "00f95a" "first" false nil 0)))
    (is (empty? @prompts))
    (is (= "--dangerously-skip-permissions"
           (:native_thread (store/route-for root-path "00f95a"))))))

(deftest g1-invalid-fallback-is-held-durably-before-prompt
  (let [root-path (str (fs/create-temp-dir {:prefix "hm-g1-invalid-"}))
        prompts (atom [])
        live (herdr-agent {:pane_id "w9:p1" :name nil})
        transport (fallback-transport "d" {"w9:p1" live} ["zsh"] prompts)
        result (g1-send root-path transport "00f95a" "secret body" false "d:w9:p1" 0)]
    (is (re-find #"^Held\.\{ 00f95a RepairRequired" result))
    (is (not (str/includes? result "secret body")))
    (is (empty? @prompts))
    (let [pending (store/pending-for root-path "00f95a")]
      (is (= 1 (count pending)))
      (is (= "secret body" (:message (first pending))))
      (is (= :RepairRequired (get-in (first pending) [:attempt :reason]))))))

(deftest g1-presented-wait-names-all-observable-states
  (let [calls (atom [])]
    (with-redefs [hm/herdr-socket-request! (fn [session request]
                                             (swap! calls conj [session request])
                                             {:type "agent_prompted"})]
      (hm/direct-prompt! route "#msg [\"sender\" \"x\"]" true)
      (hm/direct-prompt! route "#msg [\"sender\" \"y\"]" false))
    (is (= ["s" {:id "messenger-clj:agent:prompt" :method "agent.prompt"
                  :params {:target "p" :text "#msg [\"sender\" \"x\"]"
                           :wait {:until ["working" "idle" "done" "blocked"] :timeout_ms 10000}}}]
           (first @calls)))
    (is (= ["s" {:id "messenger-clj:agent:prompt" :method "agent.prompt"
                  :params {:target "p" :text "#msg [\"sender\" \"y\"]"}}]
           (second @calls)))))

(deftest herdr-socket-preflight-enforces-the-real-json-line-limit
  (let [base-request (hm/herdr-prompt-request route "" false)
        base-bytes (hm/herdr-request-byte-count base-request)
        exact-text (apply str (repeat (- hm/herdr-max-initial-request-bytes base-bytes) "x"))
        exact-request (hm/herdr-prompt-request route exact-text false)
        overflow-request (hm/herdr-prompt-request route (str exact-text "x") false)]
    (is (= hm/herdr-max-initial-request-bytes (hm/herdr-request-byte-count exact-request)))
    (is (= exact-request (hm/ensure-herdr-request-fits! exact-request)))
    (is (= (inc hm/herdr-max-initial-request-bytes) (hm/herdr-request-byte-count overflow-request)))
    (is (re-find #"RelayOverflow: Herdr API request is 1048577 bytes; maximum is 1048576 bytes"
                 (try (hm/ensure-herdr-request-fits! overflow-request) nil
                      (catch Exception error (.getMessage error)))))))

(deftest ledger-failure-prevents-the-live-send-prompt
  (let [root-path (str (fs/create-temp-dir {:prefix "hm-ledger-"}))
        prompts (atom 0)
        failing (reify hm/Ledger
                  (record-attempt! [_ _] (hm/fail "ledger unavailable"))
                  (record-pending! [_ _ _] nil))]
    (binding [hm/*root* root-path hm/*flow-id* "sender" hm/*ledger* failing hm/*with-reservation* pass-reservation]
      (persist-route! root-path route)
      (with-redefs [hm/live-agents (constantly [route])
                    hm/verify-target! (fn [_] route)
                    hm/direct-prompt! (fn [& _] (swap! prompts inc))]
        (is (re-find #"ledger unavailable"
                     (try (hm/send! "00f95a" "body" false nil)
                          (catch Exception error (.getMessage error)))))
        (is (zero? @prompts))))))

(deftest route-gates-precede-the-prompt
  (let [root-path (str (fs/create-temp-dir {:prefix "hm-route-gates-"}))
        prompts (atom 0)
        good-agent (assoc route :interactive_ready true :agent_status "working")
        good-process [{:argv ["codex" "--thread" (:native_thread route)]}]
        send-result (fn [target-agent processes]
                      (binding [hm/*root* root-path hm/*flow-id* "sender"
                                hm/*with-reservation* pass-reservation
                                hm/*transport* (fake-transport good-agent target-agent processes prompts)]
                        (persist-route! root-path route)
                        (try (hm/send! "00f95a" "body" false nil)
                             (catch Exception error (.getMessage error)))))]
    (is (re-find #"RepairRequired" (send-result (assoc good-agent :agent "other") good-process)))
    (is (re-find #"NotReady" (send-result (assoc good-agent :interactive_ready false) good-process)))
    (is (re-find #"RepairRequired"
                 (send-result (assoc good-agent :agent_session
                                     {:source "herdr:claude" :kind "id" :agent "codex"
                                      :value (:native_thread route)})
                              [{:argv ["codex" "--dangerously-skip-permissions"]}])))
    (binding [hm/*root* root-path hm/*flow-id* "sender" hm/*with-reservation* pass-reservation]
      (with-redefs [store/retirement-for (fn [_ flow] (when (= flow "00f95a") {:malformed true}))]
        (is (re-find #"Retirement marker" (try (hm/send! "00f95a" "body" false nil)
                                               (catch Exception error (.getMessage error)))))))
    (binding [hm/*root* root-path hm/*flow-id* "sender"
              hm/*with-reservation* (fn [_ _] (hm/fail "Reservation refused"))
              hm/*transport* (fake-transport good-agent good-agent good-process prompts)]
      (is (re-find #"Reservation refused" (try (hm/send! "00f95a" "body" false nil)
                                               (catch Exception error (.getMessage error))))))
    (is (zero? @prompts))
    (is (= "Transported.{ 00f95a working }" (send-result good-agent good-process)))
    (is (= 1 @prompts))))

(deftest held-routes-large-bodies-and-post-prompt-ledger-failure-are-honest
  (let [root-path (str (fs/create-temp-dir {:prefix "hm-p0-"}))
        prompts (atom 0)
        large-body (str "line one\n" (apply str (repeat 12000 "λ")))
        good-agent (assoc route :interactive_ready true :agent_status "working")
        process [{:argv ["codex" "--thread" (:native_thread route)]}]
        transport (fake-transport good-agent good-agent process prompts)]
    (binding [hm/*root* root-path hm/*flow-id* "sender" hm/*with-reservation* pass-reservation hm/*transport* transport]
      (persist-route! root-path (assoc route :route_hold "pane_move_in_progress"))
      (is (re-find #"RouteHold" (try (hm/send! "00f95a" "body" false nil) (catch Exception error (.getMessage error)))))
      (is (re-find #"RouteHold" (try (hm/send-abrupt! "00f95a" "body" false) (catch Exception error (.getMessage error)))))
      (is (zero? @prompts))
      (let [pending (first (store/pending-for root-path "00f95a"))]
        (is (= :msg (:variant pending)))
        (is (= "body" (:message pending))))
      (persist-route! root-path route)
      (is (= "Transported.{ 00f95a working }" (hm/send! "00f95a" large-body false nil)))
      (is (= #{large-body}
             (set (keep :body (filter #(contains? #{:Submitting :sent} (:reason %))
                                      (store/attempts-for root-path "00f95a"))))))
      (is (every? #(= :msg (:variant %))
                  (filter #(contains? #{:Submitting :sent} (:reason %))
                          (store/attempts-for root-path "00f95a"))))
      (is (every? #(= (hm/message-envelope "sender" {:variant :msg :body large-body}) (:submitted %))
                  (filter #(contains? #{:Submitting :sent} (:reason %))
                          (store/attempts-for root-path "00f95a"))))
      (let [writes (atom 0)
            ledger (reify hm/Ledger
                     (record-attempt! [_ _] (if (= 2 (swap! writes inc)) (hm/fail "sent ledger unavailable") :ok))
                     (record-pending! [_ _ _] :ok))]
        (binding [hm/*ledger* ledger]
          (is (re-find #"prompt was delivered but ledger confirmation failed; do not retry"
                       (try (hm/send! "00f95a" "body" false nil) (catch Exception error (.getMessage error)))))
          (is (= 2 @prompts)))))))

(deftest real-herdr-limit-is-held-durably-before-any-prompt
  (let [root-path (str (fs/create-temp-dir {:prefix "hm-overflow-"}))
        prompts (atom 0)
        body (apply str (repeat hm/herdr-max-initial-request-bytes "x"))
        good-agent (assoc route :interactive_ready true :agent_status "working")
        transport (fake-transport good-agent good-agent
                                  [{:argv ["codex" "--thread" (:native_thread route)]}]
                                  prompts)]
    (binding [hm/*root* root-path hm/*flow-id* "sender" hm/*with-reservation* pass-reservation
              hm/*transport* transport]
      (persist-route! root-path route)
      (is (re-find #"Held\.\{ 00f95a RelayOverflow"
                   (try (hm/send! "00f95a" body false nil)
                        (catch Exception error (.getMessage error)))))
      (is (zero? @prompts))
      (let [pending (first (store/pending-for root-path "00f95a"))]
        (is (= body (:message pending)))
        (is (= :RelayOverflow (get-in pending [:attempt :reason])))
        (is (not-any? #(= :Submitting (:reason %))
                      (store/attempts-for root-path "00f95a")))))))

(deftest listing-joins-live-agents-from-typed-routes
  (let [root-path (str (fs/create-temp-dir {:prefix "hm-list-"}))
        second-agent {:session "s" :name "Other 123" :pane_id "x" :terminal_id "u" :agent "codex" :agent_status "idle"}]
    (binding [hm/*root* root-path]
      (persist-route! root-path route)
      (with-redefs [hm/live-agents (constantly [(assoc route :agent_status "working") second-agent])]
        (is (= (str "FLOW\tAGENT\tSESSION\tSTATE\n"
                    "00f95a\tMind Sol 00f95a\ts\tworking\n"
                    "-\tOther 123\ts\tidle")
               (hm/listing!)))
        (let [queried (atom [])]
          (with-redefs [store/routes (fn [_] (swap! queried conj :routes) {"00f95a" route})]
            (hm/listing!)
            (is (= [:routes] @queried)))))
      (with-redefs [hm/live-agents (constantly [])]
        (is (= (str "FLOW\tAGENT\tSESSION\tSTATE\n"
                    "00f95a\tMind Sol 00f95a\ts\tSTALE")
               (hm/listing!)))))))

(deftest readiness-probe-requires-exact-native-turns
  (let [marker "HM_READY_12345678"
        transcript (fs/create-temp-file {:prefix "hm-rollout-" :suffix ".jsonl"})
        prompts (atom 0)
        agent (assoc route :interactive_ready false)
        transport (reify hm/HerdrTransport
                    (live-agents* [_] [agent])
                    (target-agent* [_ _] {:agent agent})
                    (process-info* [_ _] {:process_info {:foreground_processes []}})
                    (pane* [_ _] {:pane {}})
                    (move-pane* [_ _ _ _] {:move_result {}})
                    (send-keys* [_ _ _] {:ok true})
                    (prompt!* [_ _ _ _] (swap! prompts inc) {:ok true}))
        user {:type "event_msg" :payload {:thread_id (:native_thread route)
                                          :item {:type "UserMessage" :content [{:text (str "Reply " marker)}]}}}
        reply {:type "event_msg" :payload {:thread_id (:native_thread route)
                                           :item {:type "AgentMessage" :content [{:text marker}]}}}]
    (spit (str transcript) (str (json/generate-string user) "\n" (json/generate-string reply) "\n"))
    (binding [hm/*transport* transport hm/*readiness-attempts* 1]
      (is (= {:thread_id (:native_thread route) :rollout (str (fs/absolutize transcript)) :marker marker}
             (hm/readiness-probe! agent marker (:native_thread route) transcript)))
      (is (= 1 @prompts))
      (is (thrown? Exception (hm/readiness-probe! agent "not-a-marker" (:native_thread route) transcript)))
      (spit (str transcript) (str (json/generate-string {:type "user" :sessionId (:native_thread route) :message {:content marker}}) "\n"
                                  (json/generate-string {:type "assistant" :sessionId (:native_thread route) :message {:content marker}}) "\n"))
      (is (= "claude-transcript" (:evidence_kind (hm/readiness-probe! agent marker (:native_thread route) transcript))))
      (spit (str transcript) (str (json/generate-string (assoc-in reply [:payload :thread_id] "different")) "\n"))
      (is (thrown? Exception (hm/readiness-probe! agent marker (:native_thread route) transcript))))))

(deftest register-persists-proof-for-a-not-ready-agent
  (let [root-path (str (fs/create-temp-dir {:prefix "hm-register-proof-"}))
        marker "HM_READY_87654321"
        transcript (fs/create-temp-file {:prefix "hm-register-rollout-" :suffix ".jsonl"})
        agent (assoc route :interactive_ready false)
        rows [{:type "event_msg" :payload {:thread_id (:native_thread route)
                                           :item {:type "UserMessage" :content [{:text marker}]}}}
              {:type "event_msg" :payload {:thread_id (:native_thread route)
                                           :item {:type "AgentMessage" :content [{:text marker}]}}}]]
    (spit (str transcript) (str/join "\n" (map json/generate-string rows)))
    (binding [hm/*root* root-path hm/*readiness-attempts* 1]
      (with-redefs [hm/herdr! (fn [& args]
                                (cond
                                  (some #{"list"} args) {:agents [agent]}
                                  (some #{"process-info"} args) {:process_info {:foreground_processes []}}
                                  :else {:agent agent}))
                    hm/direct-prompt! (fn [& _] {:ok true})]
        (is (thrown? Exception (hm/register! "00f95a" (:name route) "s" (:native_thread route) nil nil)))
        (is (= "Registered 00f95a: Mind Sol 00f95a (s)"
               (hm/register! "00f95a" (:name route) "s" (:native_thread route) marker transcript)))
        (is (= marker (get-in (hm/read-route "00f95a") [:readiness_proof :marker])))))))

(deftest register-uses-only-the-official-herdr-agent-session
  (let [root-path (str (fs/create-temp-dir {:prefix "hm-register-session-"}))
        official (:native_thread route)
        process [{:argv ["claude" "--dangerously-skip-permissions" "--model" "opus"]}]
        agent (assoc route :agent "claude" :interactive_ready true
                     :agent_session {:source "herdr:claude" :kind "id"
                                     :agent "claude" :value official})]
    (binding [hm/*root* root-path hm/*with-reservation* pass-reservation
              hm/*transport* (fake-transport agent agent process (atom 0))]
      (with-redefs [hm/herdr! (fn [& args]
                                (if (some #{"list"} args)
                                  {:agents [agent]}
                                  (throw (ex-info "process-info must not be used for registration" {}))))]
        (is (= "Registered 00f95a: Mind Sol 00f95a (s)"
               (hm/register! "00f95a" (:name route) "s" nil nil nil)))
        (is (= official (:native_thread (hm/read-route "00f95a"))))))))

(deftest register-rejects-untrusted-or-conflicting-native-session-without-persistence
  (doseq [[label agent explicit]
          [["absent" (assoc route :interactive_ready true :agent_session nil) nil]
           ["malformed-source"
            (assoc route :interactive_ready true
                   :agent_session {:source "herdr:claude" :kind "id"
                                   :agent "codex" :value (:native_thread route)})
            nil]
           ["malformed-value"
            (assoc route :interactive_ready true
                   :agent_session {:source "herdr:codex" :kind "id"
                                   :agent "codex" :value "--dangerously-skip-permissions"})
            nil]
           ["explicit-mismatch"
            (assoc route :interactive_ready true
                   :agent_session {:source "herdr:codex" :kind "id"
                                   :agent "codex" :value (:native_thread route)})
            "11111111-1111-1111-1111-111111111111"]]]
    (let [root-path (str (fs/create-temp-dir {:prefix (str "hm-register-" label "-")}))]
      (binding [hm/*root* root-path hm/*with-reservation* pass-reservation
                hm/*transport* (fake-transport agent agent
                                               [{:argv ["codex" "--dangerously-skip-permissions"]}]
                                               (atom 0))]
        (with-redefs [hm/herdr! (fn [& args]
                                  (if (some #{"list"} args)
                                    {:agents [agent]}
                                    (throw (ex-info "unexpected Herdr call" {}))))]
          (is (thrown? Exception
                       (hm/register! "00f95a" (:name route) "s" explicit nil nil))
              label)
          (is (nil? (store/route-for root-path "00f95a")) label))))))

(deftest register-refreshes-a-renamed-existing-live-identity
  (let [root-path (str (fs/create-temp-dir {:prefix "hm-register-rename-"}))
        renamed (assoc route :name "Psyche Opus 88475f"
                       :interactive_ready true :agent_status "idle")
        process [{:argv ["codex" "--thread" (:native_thread route)]}]]
    (binding [hm/*root* root-path hm/*with-reservation* pass-reservation
              hm/*transport* (fake-transport renamed renamed process (atom 0))]
      (persist-route! root-path route)
      (with-redefs [hm/herdr! (fn [& args]
                                (if (some #{"list"} args)
                                  {:agents [renamed]}
                                  {:process_info {:foreground_processes process}}))]
        (is (= "Registered 00f95a: Psyche Opus 88475f (s)"
               (hm/register! "00f95a" (:name route) "s" (:native_thread route) nil nil)))
        (is (= "Psyche Opus 88475f" (:name (hm/read-route "00f95a"))))))))

(deftest deregister-and-rebind-keep-an-exact-live-binding
  (let [root-path (str (fs/create-temp-dir {:prefix "hm-lifecycle-"}))
        process [{:argv ["codex" "--thread" (:native_thread route)]}]
        rebound (assoc route :name "Mind Sol renamed" :interactive_ready true :agent_status "working")]
    (binding [hm/*root* root-path hm/*with-reservation* pass-reservation]
      (persist-route! root-path route)
      (is (= "Deregistered stale 00f95a: Mind Sol 00f95a (s/p/t)"
             (hm/deregister! "00f95a" "s" "p" "t" "Mind Sol 00f95a")))
      (is (nil? (store/route-for root-path "00f95a")))
      (persist-route! root-path route)
      (binding [hm/*transport* (fake-transport rebound rebound process (atom 0))]
        (is (= "Rebound 00f95a: Mind Sol 00f95a -> Mind Sol renamed (s/p/t)"
               (hm/rebind! "00f95a" "Mind Sol 00f95a" "Mind Sol renamed" "s" "p" "t" "codex" (:native_thread route))))
        (is (= "Mind Sol renamed" (:name (hm/read-route "00f95a"))))
        (is (thrown? Exception
                     (hm/rebind! "00f95a" "Mind Sol renamed" "again" "s" "p" "t" "codex" "different-native-thread")))))))

(deftest guarded-move-persists-hold-before-mutation-and-compensates
  (let [root-path (str (fs/create-temp-dir {:prefix "hm-move-"}))
        invoke (fn [mode]
                 (let [moves (atom "p")]
                   (binding [hm/*root* root-path hm/*with-reservation* pass-reservation
                             hm/*transport* (move-transport route mode moves)]
                     (persist-route! root-path route)
                     [(try (hm/move! "00f95a" "s" "p" "t" "Mind Sol 00f95a" "codex" (:native_thread route) 123 "w2")
                           (catch Exception error (.getMessage error)))
                      (hm/read-route "00f95a")])))]
    (let [[result record] (invoke :success)]
      (is (= "Moved 00f95a: w1/p -> w2/m (t)" result))
      (is (= "m" (:pane_id record)))
      (is (nil? (:route_hold record))))
    (let [[result record] (invoke :compensate)]
      (is (re-find #"returned to original workspace" result))
      (is (= "r" (:pane_id record)))
      (is (nil? (:route_hold record))))
    (let [[result record] (invoke :ambiguous)]
      (is (re-find #"delivery held for manual route repair" result))
      (is (= "pane_move_in_progress" (:route_hold record))))
    (let [moves (atom "p")]
      (binding [hm/*root* root-path hm/*with-reservation* pass-reservation
                hm/*transport* (move-transport route :success moves)]
        (persist-route! root-path (assoc route :route_hold "pane_move_in_progress"))
        (is (thrown? Exception (hm/move! "00f95a" "s" "p" "t" "Mind Sol 00f95a" "codex" (:native_thread route) 123 "w2")))
        (is (= "p" @moves))))))

(deftest retirement-is-evidence-bound-idempotent-and-blocks-reuse
  (let [root-path (str (fs/create-temp-dir {:prefix "hm-retire-"}))
        evidence (fs/create-temp-file {:prefix "hm-evidence-"})]
    (spit (str evidence) "witness")
    (binding [hm/*root* root-path hm/*with-reservation* pass-reservation]
      (persist-route! root-path route)
      (let [digest (hm/sha256 evidence)]
        (is (= "Retired 00f95a: delivery is blocked before Herdr routing"
               (hm/retire! "00f95a" "s" "p" "t" "Mind Sol 00f95a" "codex" (:native_thread route) evidence digest false)))
        (is (= "Already retired 00f95a: marker retained"
               (hm/retire! "00f95a" "s" "p" "t" "Mind Sol 00f95a" "codex" (:native_thread route) evidence digest false)))
        (is (thrown? Exception (hm/assert-not-retired! "00f95a")))
        (is (thrown? Exception (hm/assert-native-not-retired! (:native_thread route) "other-flow")))
        (is (thrown? Exception (hm/retire! "00f95a" "s" "other" "t" "Mind Sol 00f95a" "codex" (:native_thread route) evidence digest false)))
        (with-redefs [store/retirement-for (fn [_ flow] (when (= flow "broken") {:bad true}))]
          (is (thrown? Exception (hm/assert-not-retired! "broken"))))
        (is (= "Retired imported: delivery is blocked before Herdr routing"
               (hm/retire! "imported" "s" "p" "t" "Mind Sol 00f95a" "codex" (:native_thread route) evidence digest true)))))))

(deftest another-flows-missing-retirement-evidence-does-not-refuse-this-flow
  (let [root-path (str (fs/create-temp-dir {:prefix "hm-retire-cross-"}))
        evidence (fs/create-temp-file {:prefix "hm-evidence-"})]
    (spit (str evidence) "witness")
    (binding [hm/*root* root-path hm/*with-reservation* pass-reservation]
      (persist-route! root-path route)
      (hm/retire! "00f95a" "s" "p" "t" "Mind Sol 00f95a" "codex" (:native_thread route) evidence (hm/sha256 evidence) false)
      (fs/delete evidence)
      ;; The unrelated Flow's own marker still refuses it, as malformed.
      (is (thrown-with-msg? Exception #"Retirement marker for 00f95a is unavailable or malformed"
                            (hm/assert-not-retired! "00f95a")))
      ;; Another Flow on a fresh native thread is not refused by it.
      (is (nil? (hm/assert-native-not-retired! "11111111-1111-1111-1111-111111111111" "6f51ad")))
      ;; The retired native thread itself stays blocked for every other Flow.
      (is (thrown-with-msg? Exception #"is retired as Flow 00f95a"
                            (hm/assert-native-not-retired! (:native_thread route) "6f51ad"))))))
