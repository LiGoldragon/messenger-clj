(ns messenger-clj.core
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter PushbackReader StringReader]
           [java.net UnixDomainSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.nio.channels Channels SocketChannel])
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [babashka.process :refer [shell]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [messenger-clj.typed-store :as store]
            [malli.core :as m]))

(def skill-note "Documented by the authored messaging skills in Curriculum. Update those sources with any change to this tool.")
(def failure-reasons #{:NotRegistered :NeedsBinding :InTransition :RouteHold :IdentityChanged :PaneMissing :Blocked :ProcessMismatch :Stalled :Uncertain :RelayOverflow :Submitting :RepairCandidate :RepairRequired :RouteRepaired :InvalidBinding :sent})
(def delivery-grades #{:Transported :Presented :Fallback-Presented :Repaired :Held :Uncertain})
(def FlowId [:and [:string {:min 1 :max 96}] [:re #"^[A-Za-z0-9][A-Za-z0-9_-]*$"]])
(def NativeThread [:and [:string {:min 16 :max 96}] [:re #"^[A-Za-z0-9][A-Za-z0-9-]+$"]])
(def MessageBody [:string {:min 1}])
(def MessageVariant [:enum :msg :psyche :psyches])
(def RouteBinding [:map {:closed true} [:session :string] [:name :string] [:pane_id :string] [:terminal_id :string] [:agent :string] [:native_thread {:optional true} NativeThread] [:route_hold {:optional true} :string] [:transition {:optional true} :boolean] [:state {:optional true} :string]])
(def DeliveryAttempt [:map {:closed true} [:id :string] [:at :string] [:flow FlowId] [:reason :keyword] [:grade {:optional true} :keyword] [:variant {:optional true} MessageVariant] [:context {:optional true} [:maybe MessageBody]] [:body {:optional true} MessageBody] [:part_index {:optional true} pos-int?] [:part_count {:optional true} pos-int?] [:submitted {:optional true} :string] [:binding {:optional true} RouteBinding]])
(def PendingIntent [:map {:closed true} [:attempt DeliveryAttempt] [:message MessageBody] [:variant {:optional true} MessageVariant] [:context {:optional true} [:maybe MessageBody]] [:part_index {:optional true} pos-int?] [:part_count {:optional true} pos-int?] [:state [:= "held"]]])
(def RouteIdentity [:map {:closed true} [:session :string] [:name :string] [:pane_id :string] [:terminal_id :string] [:agent :string]])
(def RetirementEvidence [:map {:closed true} [:path :string] [:sha256 [:re #"^[0-9a-f]{64}$"]]])
(def RetirementMarker [:map {:closed true} [:version [:= 1]] [:state [:= "retired"]] [:flow FlowId] [:record RouteIdentity] [:native_thread NativeThread] [:evidence RetirementEvidence] [:retired_by :string] [:retired_at :string]])
(def Reservation [:map {:closed true} [:id :int] [:flow FlowId] [:root :string]])
(def PaneMessage [:tuple FlowId MessageBody])
(def PsycheMessage [:tuple FlowId MessageBody MessageBody])
(def PsychesMessage [:vector {:min 1} PsycheMessage])
(def PsychesInput [:vector {:min 1} [:tuple MessageBody MessageBody]])
(def MessageRequest [:map {:closed true} [:variant MessageVariant] [:body MessageBody] [:context {:optional true} [:maybe MessageBody]]])
(doseq [schema [FlowId NativeThread MessageBody MessageVariant RouteBinding DeliveryAttempt PendingIntent RetirementMarker Reservation PaneMessage PsycheMessage PsychesMessage PsychesInput MessageRequest]] (m/validator schema))

(defn fail [s] (throw (ex-info s {:hm/failure true})))
(defn valid! [schema value label]
  (if (m/validate schema value)
    value
    (throw (ex-info (str "Invalid " label ": " (pr-str (m/explain schema value)))
                    {:hm/failure true :hm/invalid true}))))
(declare ->DatalevinLedger record-attempt! record-pending! route-records nonempty-strings! record-sent! held!)
(defn flow-id! [value]
  (when-not (and (string? value) (re-matches #"[A-Za-z0-9][A-Za-z0-9_-]{0,95}" value))
    (fail "Flow ID must contain only letters, digits, underscores, or hyphens"))
  (valid! FlowId value "FlowId"))
(defn native-thread! [value]
  (when-not (and (string? value) (re-matches #"[A-Za-z0-9][A-Za-z0-9-]{15,95}" value)) (fail "Invalid NativeThread"))
  (valid! NativeThread value "NativeThread"))
(defn registration-native-thread! [agent explicit-native existing-native]
  (let [session (:agent_session agent)]
    (if (nil? session)
      (let [native (or explicit-native
                       (fail "Herdr agent has no official agent_session; --native-thread is required"))]
        (native-thread! native)
        (when (and existing-native (not= existing-native native))
          (fail "Explicit native thread differs from the existing registration"))
        native)
      (let [{session-agent :agent kind :kind source :source value :value} session
            agent-kind (:agent agent)]
        (when-not (and (map? session)
                       (= #{:agent :kind :source :value} (set (keys session)))
                       (string? agent-kind)
                       (= agent-kind session-agent)
                       (= "id" kind)
                       (= (str "herdr:" agent-kind) source))
          (fail "Herdr agent_session is malformed or does not match the agent"))
        (native-thread! value)
        (when (and explicit-native (not= explicit-native value))
          (fail "Explicit native thread differs from Herdr agent_session"))
        (when (and existing-native (not= existing-native value))
          (fail "Herdr agent_session differs from the existing registration"))
        value))))
(defn route-binding! [value]
  (try
    (valid! RouteBinding (store/route! value) "RouteBinding")
    (catch Exception error
      (throw (ex-info (.getMessage error)
                      (assoc (ex-data error) :hm/failure true :hm/invalid true)
                      error)))))
(defn delivery-attempt! [value]
  (when-not (and (contains? failure-reasons (:reason value)) (contains? delivery-grades (:grade value)))
    (fail "Invalid DeliveryAttempt grade or reason"))
  (valid! DeliveryAttempt value "DeliveryAttempt"))
(defprotocol Registry (load-route [this flow]) (save-route! [this flow route]))
(defprotocol HerdrTransport
  (live-agents* [this])
  (target-agent* [this route])
  (process-info* [this route])
  (pane* [this route])
  (move-pane* [this route workspace label])
  (send-keys* [this route key])
  (prompt!* [this route envelope wait?]))
(defprotocol Ledger (record-attempt! [this attempt]) (record-pending! [this attempt body]))
(defprotocol Clock (current-time [this]))
(defrecord SystemClock [] Clock (current-time [_] (.toString (java.time.Instant/now))))
(defrecord DatalevinRegistry [state-root]
  Registry
  (load-route [_ flow] (store/route-for state-root (flow-id! flow)))
  (save-route! [_ flow route] (store/put-route! state-root (flow-id! flow) (route-binding! route))))
(def ^:dynamic *root* nil)
(def ^:dynamic *flow-id* nil)
(def ^:dynamic *ledger* nil)
(def ^:dynamic *registry* nil)
(def ^:dynamic *clock* nil)
(def ^:dynamic *transport* nil)
(def ^:dynamic *shell* shell)
(def ^:dynamic *reservation-wait-ms* 15000)
(def ^:dynamic *reservation-retry-ms* 50)
;; A test seam around the Orchestrate boundary.  Production always uses
;; `with-reservation` below; tests supply a short-lived in-memory lease.
(def ^:dynamic *with-reservation* nil)
(defn root []
  ;; The deployment migrates the typed database here while holding both state
  ;; roots and replacing every launcher in the same activation.
  (fs/absolutize (or *root* (System/getenv "HM_REGISTRY")
                     (str (fs/path (System/getProperty "user.home") ".local/state/messenger-clj")))))
(defn registry [] (or *registry* (->DatalevinRegistry (root))))
(defn now [] (current-time (or *clock* (->SystemClock))))
(defn quote-datom [s] (str "«" (str/replace (str s) #"[\\»]" {\\ "\\\\" \» "\\»"}) "»"))
(defn read-msg [value]
  ;; `data_readers.clj` binds #msg to this function for Clojure readers.  The
  ;; tagged value is deliberately just the two pane-visible fields.
  (valid! PaneMessage value "#msg"))
(defn read-psyche [value]
  (valid! PsycheMessage value "#psyche"))
(defn read-psyches [value]
  (valid! PsychesMessage value "#psyches"))
(defn- read-complete [readers line]
  (with-open [reader (PushbackReader. (StringReader. line))]
    (let [eof (Object.)
          value (edn/read {:readers readers :eof eof} reader)]
      (when (or (identical? eof value)
                (not (identical? eof (edn/read {:readers readers :eof eof} reader))))
        (fail "Expected exactly one complete EDN form"))
      value)))
(defn read-pane-message [line]
  (let [tagged ::tagged
        value (read-complete {'msg #(hash-map tagged (read-msg %))} line)]
    (or (get value tagged) (fail "Expected one complete #msg form"))))
(defn read-psyche-message [line]
  (let [tagged ::tagged
        value (read-complete {'psyche #(hash-map tagged (read-psyche %))} line)]
    (or (get value tagged) (fail "Expected one complete #psyche form"))))
(defn read-psyches-message [line]
  (let [tagged ::tagged
        value (read-complete {'psyches #(hash-map tagged (read-psyches %))} line)]
    (or (get value tagged) (fail "Expected one complete #psyches form"))))
(defn read-psyches-input [text]
  (valid! PsychesInput (read-complete {} text) "plural psyche input"))
(defn request! [request]
  (let [request (valid! MessageRequest request "MessageRequest")]
    (when (and (= :psyche (:variant request)) (not (contains? request :context)))
      (fail "Psyche messages require context"))
    (when (and (= :msg (:variant request)) (contains? request :context))
      (fail "Machine messages do not carry psyche context"))
    (when (and (= :psyches (:variant request)) (contains? request :context))
      (fail "Plural psyche input carries one context inside each record"))
    (when (= :psyches (:variant request))
      (read-psyches-input (:body request)))
    request))
(defn message-envelope [sender request]
  (flow-id! sender)
  (let [{:keys [variant context body]} (request! request)
        [tag value reader] (case variant
                             :msg ["#msg" (read-msg [sender body]) read-pane-message]
                             :psyche ["#psyche" (read-psyche [sender context body]) read-psyche-message]
                             :psyches ["#psyches"
                                       (read-psyches (mapv (fn [[record-context verbatim]]
                                                             [sender record-context verbatim])
                                                           (read-psyches-input body)))
                                       read-psyches-message])
        envelope (str tag " " (pr-str value))]
    (when-not (= value (reader envelope))
      (fail (str tag " EDN round trip failed; message held")))
    envelope))

(defn psyche-envelope [sender context verbatim]
  (message-envelope sender {:variant :psyche :context context :body verbatim}))
(defn psyche-requests [sender context verbatim]
  (flow-id! sender)
  [(request! {:variant :psyche :context context :body verbatim})])
(defn psyches-envelope [sender records]
  (message-envelope sender {:variant :psyches :body (pr-str (valid! PsychesInput records "plural psyche input"))}))
(defn relay-line [sender _recipient body] (message-envelope sender {:variant :msg :body body}))
(defn relay [sender recipient body] (relay-line sender recipient body))
(defn framed-text [sender _recipient body] (message-envelope sender {:variant :msg :body body}))
(defn nested-relay? [body]
  (or (try (read-pane-message body) true (catch Exception _ false))
      (try (read-psyche-message body) true (catch Exception _ false))
      (try (read-psyches-message body) true (catch Exception _ false))))
(defn read-route [flow]
  (try
    (if-let [route (load-route (registry) flow)]
      (route-binding! route)
      (fail (str "No valid registration for " flow)))
    (catch Exception e (fail (str "No valid registration for " flow ": " (.getMessage e))))))
(defn herdr! [& args]
  (let [{:keys [exit out err]} (apply shell {:out :string :err :string :continue true :timeout 15000} "herdr" args)]
    (when-not (zero? exit) (fail (or (not-empty (str/trim err)) (str "herdr failed: " exit))))
    (try (let [reply (json/parse-string out true)]
           (when-not (map? reply) (fail "Herdr returned malformed JSON object; do not blindly retry a send"))
           (when (:error reply) (fail (str "Herdr: " (:error reply))))
           (let [result (or (:result reply) reply)]
             (when-not (map? result) (fail "Herdr returned malformed result object; do not blindly retry a send"))
             result))
         (catch Exception _ (fail "Herdr returned invalid JSON; do not blindly retry a send")))))
(declare shell-live-agents)
(def presented-wait-args ["--wait" "--until" "working" "--until" "idle" "--until" "done"
                          "--until" "blocked" "--timeout" "10000"])
(def herdr-max-initial-request-bytes (* 1024 1024))
(defn herdr-socket-path [session]
  (let [matches (filter #(= session (:name %)) (:sessions (herdr! "session" "list" "--json")))]
    (when-not (= 1 (count matches))
      (fail "Herdr session lookup did not return one exact session; do not blindly retry a send"))
    (let [socket-path (:socket_path (first matches))]
      (when-not (and (string? socket-path) (not (str/blank? socket-path)))
        (fail "Herdr session has no socket_path; do not blindly retry a send"))
      socket-path)))
(defn herdr-prompt-request [route envelope wait-presented]
  {:id "messenger-clj:agent:prompt"
   :method "agent.prompt"
   :params (cond-> {:target (:pane_id route) :text envelope}
             wait-presented (assoc :wait {:until ["working" "idle" "done" "blocked"]
                                           :timeout_ms 10000}))})
(defn herdr-request-line [request]
  (json/generate-string request))
(defn herdr-request-byte-count [request]
  (alength (.getBytes ^String (herdr-request-line request) StandardCharsets/UTF_8)))
(defn ensure-herdr-request-fits! [request]
  (let [bytes (herdr-request-byte-count request)]
    (when (> bytes herdr-max-initial-request-bytes)
      (fail (str "RelayOverflow: Herdr API request is " bytes
                 " bytes; maximum is " herdr-max-initial-request-bytes " bytes")))
    request))
(defn herdr-socket-request! [session request]
  (let [request (ensure-herdr-request-fits! request)
        socket-path (herdr-socket-path session)]
    (try
      (with-open [channel (SocketChannel/open (UnixDomainSocketAddress/of socket-path))
                  writer (OutputStreamWriter. (Channels/newOutputStream channel) StandardCharsets/UTF_8)
                  reader (BufferedReader. (InputStreamReader. (Channels/newInputStream channel) StandardCharsets/UTF_8))]
        (.write writer ^String (herdr-request-line request))
        (.write writer "\n")
        (.flush writer)
        (let [line (.readLine reader)]
          (when (str/blank? line)
            (fail "Herdr socket returned an empty response; do not blindly retry a send"))
          (let [reply (json/parse-string line true)]
            (when (:error reply) (fail (str "Herdr: " (:error reply))))
            (let [result (or (:result reply) reply)]
              (when-not (map? result)
                (fail "Herdr socket returned malformed result; do not blindly retry a send"))
              result))))
      (catch clojure.lang.ExceptionInfo error (throw error))
      (catch Exception error
        (fail (str "Herdr socket request failed or is uncertain: " (.getMessage error)))))))
(defn direct-prompt! [route envelope wait-presented]
  (herdr-socket-request! (:session route) (herdr-prompt-request route envelope wait-presented)))
(defn presented! [route reply]
  ;; A waited Herdr prompt returns agent_prompted after observing one of the
  ;; requested lifecycle states.  Bind that observation to the exact pane.
  (when-not (and (= "agent_prompted" (:type reply))
                 (= (:pane_id route) (get-in reply [:agent :pane_id])))
    (fail "Presentation was not observed; do not retry blindly"))
  reply)
(defrecord ShellHerdr []
  HerdrTransport
  (live-agents* [_] (shell-live-agents))
  (target-agent* [_ route] (herdr! "--session" (:session route) "agent" "get" (:pane_id route)))
  (process-info* [_ route] (herdr! "--session" (:session route) "pane" "process-info" "--pane" (:pane_id route)))
  (pane* [_ route] (herdr! "--session" (:session route) "pane" "get" (:pane_id route)))
  (move-pane* [_ route workspace label] (herdr! "--session" (:session route) "pane" "move" (:pane_id route) "--new-tab" "--workspace" workspace "--label" label "--no-focus"))
  (send-keys* [_ route key] (herdr! "--session" (:session route) "agent" "send-keys" (:pane_id route) key))
  (prompt!* [_ route envelope wait?] (direct-prompt! route envelope wait?)))
(defn transport [] (or *transport* (->ShellHerdr)))
(defn sha256 [file]
  (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest (doto (java.security.MessageDigest/getInstance "SHA-256") (.update (fs/read-all-bytes file)))))))
(defn retirement! [flow]
  (when-let [stored (store/retirement-for (root) flow)]
    (try
      (let [value (valid! RetirementMarker stored "RetirementMarker")
            evidence (:evidence value)]
        (when-not (and (= flow (:flow value)) (fs/absolute? (:path evidence))
                       (fs/regular-file? (:path evidence))
                       (= (:sha256 evidence) (sha256 (:path evidence))))
          (fail "bad marker"))
        value)
      (catch Exception _ (fail (str "Retirement marker for " flow " is unavailable or malformed"))))))
(defn assert-not-retired! [flow]
  (when-let [marker (retirement! flow)]
    (fail (str "Retired: " flow " by " (get-in marker [:evidence :path])))))
(defn assert-native-not-retired! [native-thread flow]
  ;; Another Flow's marker blocks only by its stored native thread.  Its
  ;; evidence file belongs to that Flow's own checks; a missing or moved
  ;; witness of an unrelated retirement must not refuse this Flow.
  (doseq [marker (store/retirements (root))
          :let [other (:flow marker)]]
    (when (and (not= flow other) (= native-thread (:native_thread marker)))
      (fail (str "Native thread " native-thread " is retired as Flow " other "; use a fresh native session")))))
(defn claude-session-matches? [process native]
  (and (int? (:pid process))
       (try
         (let [file (fs/path (System/getProperty "user.home") ".claude" "sessions" (str (:pid process) ".json"))
               value (json/parse-string (slurp (str file)) true)]
           (= native (:sessionId value)))
         (catch Exception _ false))))
(defn process-matches! [route]
  ;; Native identity comes from Herdr's typed agent_session.  Native-less
  ;; fallback routes retain their existing exact pane/terminal checks.
  (when (:native_thread route)
    (let [reply (target-agent* (transport) route)
          agent (assoc (or (:agent reply) reply) :session (:session route))]
      (registration-native-thread! agent (:native_thread route) nil))))
(defn content-text [content]
  (cond
    (string? content) content
    (sequential? content) (str/join "" (keep #(when (map? %) (:text %)) content))
    :else ""))
(defn verify-target! [route]
  (let [reply (target-agent* (transport) route)
        agent (or (:agent reply) reply)]
    (when-not (and (= (:session route) (or (:session agent) (:session route)))
                   (= (:pane_id route) (:pane_id agent))
                   (= (:terminal_id route) (:terminal_id agent))
                   (= (:agent route) (:agent agent)))
      (fail "IdentityChanged"))
    (nonempty-strings! "Live Herdr agent has no current name or agent kind"
                       [(:name agent) (:agent agent)])
    (when (= "blocked" (:agent_status agent)) (fail "Blocked"))
    (when-not (contains? #{"idle" "working" "done"} (:agent_status agent)) (fail "Uncertain"))
    (process-matches! route)
    agent))
(defn shell-live-agents []
  (mapcat (fn [session]
            (map #(assoc % :session (:name session))
                 (:agents (herdr! "--session" (:name session) "agent" "list"))))
          (filter :running (:sessions (herdr! "session" "list" "--json")))))
(defn live-agents [] (live-agents* (transport)))
(defn parse-pane [value]
  (let [[session pane] (str/split (or value "") #":" 2)]
    (when (or (str/blank? session) (str/blank? pane)) (fail "--pane requires <session>:<pane>"))
    {:session session :pane_id pane}))
(defn fallback-binding [agent native-thread]
  (route-binding!
   (cond-> (assoc (select-keys agent [:session :name :pane_id :terminal_id :agent])
                  :state "Fallback")
     native-thread (assoc :native_thread native-thread))))
(def stable-route-keys [:session :pane_id :terminal_id])
(defn same-stable-route? [left right]
  (= (select-keys left stable-route-keys)
     (select-keys right stable-route-keys)))
(defn refresh-route-snapshot [route agent]
  (route-binding! (assoc route :name (:name agent) :agent (:agent agent))))
(defn exact-live-agent [route]
  (let [hits (filter #(same-stable-route? route %) (live-agents))]
    (when-not (= 1 (count hits))
      (fail (if (empty? hits)
              "Held: stored route has no exact live Herdr identity"
              "Held: stored route has duplicate live Herdr identities")))
    (first hits)))
(defn fallback-route [flow stored pane]
  (cond
    pane (let [{:keys [session pane_id]} (parse-pane pane)
               hits (filter #(and (= session (:session %)) (= pane_id (:pane_id %))) (live-agents))]
           (when-not (= 1 (count hits)) (fail "Held: --pane does not name exactly one live Herdr agent"))
           (fallback-binding (assoc (first hits) :session session) (:native_thread stored)))
    stored (refresh-route-snapshot stored (exact-live-agent stored))
    :else (let [suffix (re-pattern (str "\\b" (java.util.regex.Pattern/quote flow) "$"))
                hits (filter #(re-find suffix (or (:name %) "")) (live-agents))]
            (when-not (= 1 (count hits)) (fail "Held: Flow title has no unique live Herdr agent"))
            (fallback-binding (first hits) nil))))
(defn resolve-send-route [flow stored pane]
  (cond
    pane [(fallback-route flow stored pane) true]
    stored [(refresh-route-snapshot stored (exact-live-agent stored)) false]
    :else [(fallback-route flow nil nil) true]))
(defn in-transition? [route] (or (:transition route) (= "transition" (:state route))))
(defn needs-binding? [route] (= "NeedsBinding" (:state route)))
(defn attempt-fields [request submitted]
  (cond-> (select-keys (request! request) [:variant :context :body])
    submitted (assoc :submitted submitted)))
(defn prompt-request! [route envelope wait-presented]
  (ensure-herdr-request-fits! (herdr-prompt-request route envelope wait-presented)))
(defn append-attempt! [flow reason grade route request submitted]
  (let [attempt (cond-> {:id (str (java.util.UUID/randomUUID)) :at (now) :flow flow :reason reason :grade grade}
                  request (merge (attempt-fields request submitted))
                  route (assoc :binding route))]
    (delivery-attempt! attempt)
    (record-attempt! (or *ledger* (->DatalevinLedger (root))) attempt)
    ;; The production ledger must be queryable before a prompt may rely on its
    ;; pre-prompt attempt.  Injected test ledgers own their own persistence.
    (when-not *ledger*
      (when-not (= attempt (store/attempt-by-id (root) (:id attempt)))
        (fail "Attempt ledger index did not confirm persistence")))
    attempt))
(def exact-agent-keys [:session :name :pane_id :terminal_id :agent])
(defn checked-repair-candidate [listed]
  (try
    (let [reply (target-agent* (transport) listed)
          target (assoc (or (:agent reply) reply) :session (:session listed))]
      (when (= (select-keys listed exact-agent-keys)
               (select-keys target exact-agent-keys))
        (let [native (registration-native-thread! target nil nil)]
          (valid! RouteBinding
                  (assoc (select-keys target exact-agent-keys) :native_thread native)
                  "RouteBinding"))))
    (catch Exception _ nil)))
(defn discover-repair-candidates [flow stored pane]
  (let [agents (live-agents)
        suffix (re-pattern (str "(?:^|\\s)" (java.util.regex.Pattern/quote flow) "$"))
        eligible (cond
                   pane (let [{:keys [session pane_id]} (parse-pane pane)]
                          (filter #(and (= session (:session %)) (= pane_id (:pane_id %))) agents))
                   stored (filter #(or (same-stable-route? stored %)
                                       (re-find suffix (or (:name %) ""))) agents)
                   :else (filter #(re-find suffix (or (:name %) "")) agents))]
    (->> eligible
         (keep checked-repair-candidate)
         (distinct)
         vec)))
(defn hold-repair-required! [flow stored pane request]
  (let [candidates (discover-repair-candidates flow stored pane)]
    (doseq [candidate candidates]
      (append-attempt! flow :RepairCandidate :Repaired candidate nil nil))
    (try
      (held! flow :RepairRequired request (when (m/validate RouteBinding stored) stored))
      (catch clojure.lang.ExceptionInfo error
        (let [pending-id (:hm/attempt-id (ex-data error))
              evidence (mapv #(select-keys % exact-agent-keys) candidates)]
          (throw (ex-info (str "Held.{ " flow " RepairRequired " pending-id " } candidates=" (pr-str evidence))
                          (ex-data error) error)))))))
(defn held-reason [error]
  (let [message (.getMessage error)
        invalid? (:hm/invalid (ex-data error))
        candidate (keyword (or message ""))]
    (cond invalid? :InvalidBinding
          (contains? failure-reasons candidate) candidate
          :else :PaneMissing)))
(defn record-uncertain! [flow route request submitted]
  ;; The pre-prompt record is already durable.  Keep the original uncertainty
  ;; if storage is unavailable while recording this post-submit observation.
  (try (append-attempt! flow :Uncertain :Uncertain route request submitted) (catch Exception _ nil)))
(defn contention? [reply]
  (boolean (re-find #"LockRejected\.(?:DuplicateName|PathConflict|PathOverlap)|(?:DuplicateName|PathConflict|PathOverlap)"
                    (str (:out reply) "\n" (:err reply)))))
(defn reserve! [flow]
  (let [owner (flow-id! (or *flow-id* (System/getenv "FLOW_ID") flow))
        operation (str "MessengerCljDelivery-"
                       (str/replace (str (java.util.UUID/randomUUID)) "-" ""))
        query (str "Lock.{ " operation " " owner " [ " (quote-datom (root))
                   " ] «Register or submit through Herdr» }")
        deadline (+ (System/nanoTime) (* 1000000 (long *reservation-wait-ms*)))]
    (loop []
      (let [reply (*shell* {:out :string :err :string :continue true :timeout 15000}
                           "orchestrate" query)
            match (re-find #"Locked\.\{\s+(\d+)\b" (:out reply))]
        (cond
          (and (zero? (:exit reply)) match)
          (valid! Reservation {:id (parse-long (second match)) :flow flow :root (str (root))}
                  "Reservation")

          (and (contention? reply) (< (System/nanoTime) deadline))
          (do (Thread/sleep (long *reservation-retry-ms*)) (recur))

          (contention? reply)
          (fail (str "Reservation timed out after " *reservation-wait-ms* "ms: "
                     (str/trim (or (not-empty (:out reply)) (:err reply) ""))))

          :else
          (fail (str "Reservation refused: "
                     (str/trim (or (not-empty (:out reply)) (:err reply) "")))))))))
(defn release! [reservation]
  (let [reply (*shell* {:out :string :err :string :continue true :timeout 15000} "orchestrate" (str "Release." (:id reservation)))]
    (when-not (and (zero? (:exit reply)) (str/starts-with? (:out reply) "Released."))
      (fail (str "Reservation release failed: " (str/trim (or (not-empty (:out reply)) (:err reply) "")))))))
(defn with-reservation [flow f]
  (if *with-reservation*
    (*with-reservation* flow f)
    (let [reservation (reserve! flow)]
      (try (f) (finally (release! reservation))))))
(defrecord DatalevinLedger [state-root]
  Ledger
  (record-attempt! [_ attempt]
    (store/put-attempt! state-root attempt)
    attempt)
  (record-pending! [_ attempt request]
    (let [{:keys [variant context body]} (request! request)]
      (store/put-pending! state-root
                          (valid! PendingIntent
                                  (cond-> {:attempt attempt :message body :variant variant :state "held"}
                                    (contains? request :context) (assoc :context context))
                                  "PendingIntent")))
    attempt))
(defn held! [flow reason request route]
  (let [route (when (and route (m/validate RouteBinding route)) route)
        {:keys [variant context body]} (request! request)
        repair-envelope (when (= reason :RepairRequired)
                          (message-envelope (or *flow-id* (System/getenv "FLOW_ID")
                                                (fail "Set FLOW_ID before holding a repair"))
                                            request))
        attempt (append-attempt! flow reason :Held route request repair-envelope)
        pending (valid! PendingIntent
                        (cond-> {:attempt attempt :message body :variant variant :state "held"}
                          (contains? request :context) (assoc :context context))
                        "PendingIntent")]
    (record-pending! (or *ledger* (->DatalevinLedger (root))) attempt request)
    (when-not *ledger*
      (when-not (= pending (store/pending-by-id (root) (:id attempt)))
        (fail "Pending ledger index did not confirm persistence")))
    (throw (ex-info (str "Held.{ " flow " " (name reason) " attempt-" (subs (:id attempt) 0 12) " }")
                    {:hm/failure true :hm/held true :hm/attempt-id (:id attempt)}))))
(defn register! [flow name session native-thread]
  (flow-id! flow)
  (with-reservation flow
    (fn []
      (assert-not-retired! flow)
      (let [existing (load-route (registry) flow)]
        (when (:route_hold existing) (fail "Registration is held for route repair"))
        (let [agents (map #(assoc % :session (or session (:session %)))
                          (if session (:agents (herdr! "--session" session "agent" "list")) (live-agents)))
              found (if existing
                      (filter #(same-stable-route? existing %) agents)
                      (filter #(= name (:name %)) agents))]
          (when-not (= 1 (count found))
            (fail (if existing
                    (str "Expected one exact live Herdr identity for the existing registration; found " (count found))
                    (str "Expected one live agent named " name "; found " (count found) ". Use --session."))))
          (let [listed (assoc (first found) :session (or session (:session (first found))))
                target-reply (target-agent* (transport) listed)
                target (assoc (or (:agent target-reply) target-reply) :session (:session listed))
                _ (when-not (same-stable-route? listed target)
                    (fail "Herdr agent identity changed during registration"))
                a target
                session (:session a)
                native-thread (registration-native-thread! a native-thread (:native_thread existing))
                _ (assert-native-not-retired! native-thread flow)
                _ (nonempty-strings! "Herdr registration has no agent kind" [(:agent a)])
                route (valid! RouteBinding
                                    (assoc (select-keys a [:session :name :pane_id :terminal_id :agent])
                                           :native_thread native-thread)
                                    "RouteBinding")]
            (when (and existing (not (same-stable-route? existing route)))
              (fail "Flow is already registered to a different live route identity"))
            (when (some #(and (not= flow (key %)) (same-stable-route? route (val %)))
                        (route-records))
              (fail "Live route identity is already registered to another Flow"))
            (save-route! (registry) flow route)
            (str "Registered " flow ": " (:name route) " (" session ")")))))))
(defn repair! [flow pending-id session pane-id terminal-id name agent-kind]
  (flow-id! flow)
  (nonempty-strings! "Repair requires pending ID and every exact candidate identity field"
                     [pending-id session pane-id terminal-id name agent-kind])
  (with-reservation flow
    (fn []
      (assert-not-retired! flow)
      (let [pending (store/pending-by-id (root) pending-id)
            existing (load-route (registry) flow)]
        (when-not (and pending (= flow (get-in pending [:attempt :flow]))
                       (= :RepairRequired (get-in pending [:attempt :reason])))
          (fail "Repair pending intent is absent, already submitted, or belongs to another Flow"))
        (when (:route_hold existing) (fail "RouteHold"))
        (when (in-transition? existing) (fail "InTransition"))
        (let [expected {:session session :pane_id pane-id :terminal_id terminal-id
                        :name name :agent agent-kind}
              hits (filter #(= expected (select-keys % exact-agent-keys)) (live-agents))]
          (when-not (= 1 (count hits))
            (fail (if (empty? hits) "Repair candidate is no longer live and exact"
                      "Repair candidate is ambiguous")))
          (let [route (or (checked-repair-candidate (first hits))
                          (fail "Repair candidate identity or official agent_session changed"))
                _ (assert-native-not-retired! (:native_thread route) flow)
                _ (when (some #(and (not= flow (key %)) (same-stable-route? route (val %)))
                              (route-records))
                    (fail "Live route identity is already registered to another Flow"))
                request (request! (cond-> {:variant (or (:variant pending) :msg)
                                           :body (:message pending)}
                                    (= :psyche (:variant pending)) (assoc :context (:context pending))))
                envelope (get-in pending [:attempt :submitted])
                parsed (when (string? envelope)
                         (case (:variant request)
                           :psyche (let [[_ context body] (read-psyche-message envelope)]
                                      {:variant :psyche :context context :body body})
                           :psyches {:variant :psyches
                                     :body (pr-str (mapv (fn [[_ context body]] [context body])
                                                         (read-psyches-message envelope)))}
                           :msg (let [[_ body] (read-pane-message envelope)]
                                  {:variant :msg :body body})))
                _ (when-not (= (select-keys request [:variant :context :body]) parsed)
                    (fail "Repair pending envelope is missing or differs from the held message"))
                _ (prompt-request! route envelope false)
                repair-attempt (delivery-attempt!
                                {:id (str (java.util.UUID/randomUUID)) :at (now) :flow flow
                                 :reason :RouteRepaired :grade :Repaired :binding route})
                _ (store/put-route-and-attempt! (root) flow route repair-attempt)
                submission (delivery-attempt!
                            (merge {:id (str (java.util.UUID/randomUUID)) :at (now) :flow flow
                                    :reason :Submitting :grade :Uncertain :binding route}
                                   (attempt-fields request envelope)))
                _ (store/take-pending-with-attempt! (root) pending-id submission)]
            (try
              (let [reply (prompt!* (transport) route envelope false)]
                (verify-target! route)
                (record-sent! flow :Transported route submission
                              (or (:agent reply) reply) request envelope))
              (catch Exception error
                (record-uncertain! flow route request envelope)
                (fail (str "Uncertain.{ " flow " attempt-" (subs (:id submission) 0 12)
                           " } repair delivery failed or is uncertain; do not retry: " (.getMessage error)))))))))))
(defn nonempty-strings! [label fields]
  (when-not (every? #(and (string? %) (not (str/blank? %))) fields)
    (fail label)))
(defn deregister! [flow session pane-id terminal-id name]
  (flow-id! flow)
  (nonempty-strings! "Deregister requires every exact stale route identity field" [session pane-id terminal-id name])
  (with-reservation flow
    (fn []
      (let [actual (read-route flow)]
        (when-not (= {:session session :pane_id pane-id :terminal_id terminal-id}
                     (select-keys actual stable-route-keys))
          (fail "Registration differs from the explicitly revalidated stale route"))
        (store/remove-route! (root) flow)
        (str "Deregistered stale " flow ": " name " (" session "/" pane-id "/" terminal-id ")")))))
(defn retirement-evidence! [evidence-path evidence-sha256]
  (let [path (fs/absolutize evidence-path)]
    (when-not (and (fs/absolute? path) (fs/regular-file? path) (string? evidence-sha256) (re-matches #"[0-9a-f]{64}" evidence-sha256))
      (fail "Retirement evidence requires an existing absolute file and SHA-256 digest"))
    (let [actual (sha256 path)]
      (when-not (= evidence-sha256 actual) (fail "Retirement evidence SHA-256 does not match; nothing changed"))
      {:path (str path) :sha256 actual})))
(defn import-retirement!
  "Record a retirement from separately retained exact evidence.  The flow's
  route may already be absent; a present route must match exactly."
  [flow session pane-id terminal-id name agent native-thread evidence-path evidence-sha256]
  (flow-id! flow)
  (nonempty-strings! "Retirement requires every exact route identity field" [session pane-id terminal-id name agent])
  (native-thread! native-thread)
  (let [evidence (retirement-evidence! evidence-path evidence-sha256)
        expected {:session session :pane_id pane-id :terminal_id terminal-id :name name :agent agent}]
    (with-reservation flow
      (fn []
        (if-let [existing (retirement! flow)]
          (if (and (= (dissoc expected :name) (dissoc (:record existing) :name))
                   (= native-thread (:native_thread existing)))
            (str "Already retired " flow ": marker retained")
            (fail (str "Flow " flow " already has a different retirement marker")))
          (do
            (when-let [route (store/stored-route-for (root) flow)]
              (when-not (= (dissoc expected :name)
                           (select-keys route [:session :pane_id :terminal_id :agent]))
                (fail "Registration differs from the explicitly revalidated retirement route"))
              (when-not (= native-thread (:native_thread route))
                (fail "Registration differs from the explicitly revalidated retirement native thread")))
            (store/put-retirement!
             (root)
             (valid! RetirementMarker
                     {:version 1 :state "retired" :flow flow :record expected
                      :native_thread native-thread :evidence evidence
                      :retired_by (or *flow-id* (System/getenv "FLOW_ID") "")
                      :retired_at (now)}
                     "RetirementMarker"))
            (store/remove-route! (root) flow)
            (str "Retired " flow ": delivery is blocked before Herdr routing")))))))
(def retire-refusals #{:UnknownFlow :AlreadyRetired :PaneNotFound :PaneAmbiguous :IdentityChanged :NativeMismatch :NoNativeIdentity :RouteHold})
(defn retire-refused [flow reason detail]
  (when-not (contains? retire-refusals reason) (fail (str "Unknown retirement refusal " reason)))
  (throw (ex-info (str "RetireRefused.{ " flow " " (name reason) " } " detail)
                  {:hm/failure true :hm/retire-refusal reason})))
(defn retirement-evidence-path [flow at]
  (fs/path (root) "retirement-evidence"
           (str flow "-" (str/replace at #"[^0-9A-Za-z]" "") ".json")))
(defn write-retirement-evidence! [flow evidence]
  ;; Evidence is written before the marker.  A failure here changes nothing.
  (let [path (fs/absolutize (retirement-evidence-path flow (:retired_at evidence)))]
    (when (fs/exists? path) (fail (str "Retirement evidence already exists at " path "; nothing changed")))
    (fs/create-dirs (fs/parent path))
    (spit (str path) (str (json/generate-string evidence {:pretty true}) "\n"))
    {:path (str path) :sha256 (sha256 path)}))
(defn retire!
  "Retire FLOW from what the typed registry and live Herdr already know.
  Refuses, typed, an unknown flow, an already retired flow, a route under
  repair hold, and a registered pane that Herdr no longer shows exactly."
  [flow]
  (flow-id! flow)
  (with-reservation flow
    (fn []
      (when-let [existing (store/retirement-for (root) flow)]
        (retire-refused flow :AlreadyRetired (str "by " (get-in existing [:evidence :path]))))
      (let [route (or (store/stored-route-for (root) flow)
                      (retire-refused flow :UnknownFlow "the messenger has no registration for it"))
            where (str (:session route) "/" (:pane_id route) "/" (:terminal_id route))
            _ (when (:route_hold route)
                (retire-refused flow :RouteHold (str (:route_hold route) " at " where)))
            hits (filter #(same-stable-route? route %) (live-agents))
            _ (case (count hits)
                0 (retire-refused flow :PaneNotFound (str "Herdr shows no live agent at " where))
                1 nil
                (retire-refused flow :PaneAmbiguous (str "Herdr shows " (count hits) " live agents at " where)))
            listed (first hits)
            reply (target-agent* (transport) listed)
            live (assoc (or (:agent reply) reply) :session (:session listed))
            _ (when-not (and (same-stable-route? route live) (= (:agent route) (:agent live)))
                (retire-refused flow :IdentityChanged
                                (str "live " (pr-str (select-keys live exact-agent-keys))
                                     " differs from registered " (pr-str (select-keys route exact-agent-keys)))))
            _ (when-not (or (:agent_session live) (:native_thread route))
                (retire-refused flow :NoNativeIdentity "neither the registration nor Herdr names a native thread"))
            native (try (registration-native-thread! live (:native_thread route) nil)
                        (catch clojure.lang.ExceptionInfo error
                          (retire-refused flow :NativeMismatch (.getMessage error))))
            record (valid! RouteIdentity (select-keys (assoc route :name (or (:name live) (:name route)))
                                                      exact-agent-keys)
                           "RouteIdentity")
            retired-by (or *flow-id* (System/getenv "FLOW_ID") "")
            at (now)
            evidence (write-retirement-evidence!
                      flow {:version 1 :flow flow :retired_by retired-by :retired_at at
                            :native_thread native :registered_route route :live_agent live})]
        (store/put-retirement!
         (root)
         (valid! RetirementMarker
                 {:version 1 :state "retired" :flow flow :record record
                  :native_thread native :evidence evidence
                  :retired_by retired-by :retired_at at}
                 "RetirementMarker"))
        (store/remove-route! (root) flow)
        (str "Retired " flow ": delivery is blocked before Herdr routing\n"
             "evidence " (:path evidence) "\n"
             "sha256 " (:sha256 evidence))))))
(defn rebind! [flow old-name new-name session pane-id terminal-id agent native-thread]
  (flow-id! flow)
  (nonempty-strings! "Rebind requires every exact old route identity field" [old-name session pane-id terminal-id agent])
  (when (or (not (string? new-name)) (str/blank? new-name) (= old-name new-name))
    (fail "Rebind requires a distinct nonempty new agent name"))
  (native-thread! native-thread)
  (with-reservation flow
    (fn []
      (assert-not-retired! flow)
      (let [actual (read-route flow)
            expected {:session session :name old-name :pane_id pane-id :terminal_id terminal-id :agent agent}]
        (when (:route_hold actual) (fail "Registration is held for route repair"))
        (when-not (= (dissoc expected :name)
                     (select-keys actual [:session :pane_id :terminal_id :agent]))
          (fail "Registration differs from the explicitly revalidated old binding"))
        (when-not (= native-thread (:native_thread actual))
          (fail "Registration differs from the explicitly revalidated native thread"))
        (let [live (verify-target! actual)]
          (when-not (= new-name (:name live))
            (fail "Rebind new name differs from the current live Herdr name"))
          (when (some #(and (not= flow (key %)) (same-stable-route? actual (val %)))
                      (route-records))
            (fail "Live route identity is already registered to another Flow"))
          (let [replacement (refresh-route-snapshot actual live)]
            (save-route! (registry) flow replacement)
            (str "Rebound " flow ": " old-name " -> " new-name " (" session "/" pane-id "/" terminal-id ")")))))))
(defn move-route! [flow record pane-id hold?]
  (let [replacement (cond-> (assoc record :pane_id pane-id)
                      hold? (assoc :route_hold "pane_move_in_progress")
                      (not hold?) (dissoc :route_hold))]
    (save-route! (registry) flow replacement)
    replacement))
(defn pane-value [reply] (or (:pane reply) reply))
(defn move-result-value [reply] (or (:move_result reply) reply))
(defn verify-move-target! [expected pane process-pid native-thread]
  (let [route (assoc expected :pane_id (:pane_id pane))
        live-pane (pane-value (pane* (transport) route))
        process-reply (process-info* (transport) route)
        info (or (:process_info process-reply) process-reply)
        processes (:foreground_processes info)
        matches (filter #(and (same-stable-route? route %)
                              (= (:agent expected) (:agent %)))
                        (live-agents))]
    (when-not (and (= (:terminal_id expected) (:terminal_id pane))
                   (= (:terminal_id expected) (:terminal_id live-pane))
                   (= (:agent expected) (:agent live-pane)))
      (fail "Moved pane terminal or harness identity changed"))
    (when-not (some #(= process-pid (:pid %)) processes)
      (fail "Moved pane foreground process identity changed"))
    (when-not (or (= "codex" (:agent expected))
                  (some #(str/includes? (str/join " " (map str (or (:argv %) []))) native-thread) processes))
      (fail "Moved pane native session identity changed"))
    (when-not (= 1 (count matches))
      (fail "Moved pane has no unique matching Herdr agent"))
    route))
(defn move! [flow session pane-id terminal-id name agent native-thread process-pid workspace]
  (flow-id! flow)
  (nonempty-strings! "Move requires the complete old route" [session pane-id terminal-id name agent])
  (native-thread! native-thread)
  (when-not (and (integer? process-pid) (pos? process-pid))
    (fail "Move requires a witnessed positive foreground process PID"))
  (when-not (and (string? workspace) (re-matches #"w[A-Za-z0-9]+" workspace))
    (fail "Move requires an exact Herdr workspace ID"))
  (with-reservation flow
    (fn []
      (assert-not-retired! flow)
      (let [record (read-route flow)
            expected {:session session :name name :pane_id pane-id :terminal_id terminal-id :agent agent}]
        (when (:route_hold record) (fail "Registration is held for route repair; move refused"))
        (when-not (and (= (dissoc expected :name)
                          (select-keys record [:session :pane_id :terminal_id :agent]))
                       (= native-thread (:native_thread record)))
          (fail "Move old route or native thread differs from registration"))
        (let [source (pane-value (pane* (transport) record))
              old-workspace (:workspace_id source)]
          (verify-move-target! expected source process-pid native-thread)
          (when (some #(and (not= flow (key %)) (= terminal-id (get-in % [1 :terminal_id]))) (route-records))
            (fail "Terminal is registered to another Flow"))
          ;; The persisted hold is the boundary before an irreversible pane mutation.
          (move-route! flow record pane-id true)
          (let [moved (atom nil)]
            (try
              (let [result (move-result-value (move-pane* (transport) record workspace (or (:label source) name)))
                    pane (:pane result)]
                (reset! moved pane)
                (when-not (and (= pane-id (:previous_pane_id result))
                               (= old-workspace (:previous_workspace_id result))
                               (= workspace (:workspace_id pane)))
                  (fail "Herdr move result differs from requested route"))
                (let [verified (verify-move-target! expected pane process-pid native-thread)]
                  (move-route! flow record (:pane_id verified) false)
                  (str "Moved " flow ": " old-workspace "/" pane-id " -> " workspace "/" (:pane_id verified) " (" terminal-id ")")))
              (catch Exception error
                (if-not @moved
                  (fail (str "Move failed or is uncertain; inspect exact terminal before routing: " (.getMessage error)))
                  (let [rollback-error (try
                                         (let [reverse-result (move-result-value (move-pane* (transport) (assoc record :pane_id (:pane_id @moved)) old-workspace (or (:label source) name)))
                                               reverse (:pane reverse-result)
                                               verified (verify-move-target! expected reverse process-pid native-thread)]
                                           (move-route! flow record (:pane_id verified) false)
                                           nil)
                                         (catch Exception rollback-error rollback-error))]
                    (if rollback-error
                      (fail (str "Move and compensation failed; delivery held for manual route repair: " (.getMessage rollback-error)))
                      (fail (str "Move failed; terminal was returned to original workspace with new pane ID: " (.getMessage error))))))))))))))
(def abrupt-keys {"codex" {:interrupt ["esc"] :submit []}
                  "claude" {:interrupt ["esc" "esc"] :submit ["enter"]}})
(defn validate-request! [request]
  (let [{:keys [context body] :as request} (request! request)
        values (if (= :psyches (:variant request))
                 (mapcat identity (read-psyches-input body))
                 (cond-> [body] (some? context) (conj context)))]
    (when (some #(or (str/blank? %) (re-find #"[\p{Cc}&&[^\n\t]]" %)) values)
      (fail "Message fields must be nonempty and contain no terminal control characters"))
    (when (some nested-relay? values)
      (fail "Nested complete #msg or #psyche or #psyches form is not a message field"))
    request))
(defn send-abrupt-request! [flow request wait-presented]
  (flow-id! flow)
  (let [request (validate-request! request)]
  (let [sender (or *flow-id* (System/getenv "FLOW_ID") (fail "Set FLOW_ID to your own flow ID before sending"))]
    (with-reservation flow
      (fn []
        (assert-not-retired! flow)
          (let [route (read-route flow)]
          (when (needs-binding? route) (held! flow :NeedsBinding request route))
          (when (in-transition? route) (held! flow :InTransition request route))
          (when (:route_hold route) (held! flow :RouteHold request route))
          (let [live (try (verify-target! route) (catch Exception error (held! flow (held-reason error) request route)))
                route (refresh-route-snapshot route live)
                keys (get abrupt-keys (:agent route))]
            (when-not keys (fail (str "Hard-abrupt is not supported for " (:agent route) "; nothing sent")))
            (let [envelope (message-envelope sender request)
                  _ (try (prompt-request! route envelope wait-presented)
                         (catch Exception error
                           (if (str/starts-with? (.getMessage error) "RelayOverflow:")
                             (held! flow :RelayOverflow request route)
                             (throw error))))
                  submission (append-attempt! flow :Submitting :Uncertain route request envelope)]
              (try
                (doseq [key (:interrupt keys)] (send-keys* (transport) route key))
                (let [reply (prompt!* (transport) route envelope wait-presented)]
                  (when wait-presented (presented! route reply)))
                (doseq [key (:submit keys)] (send-keys* (transport) route key))
              ;; A successful prompt does not prove the terminal stayed bound.
              ;; Recheck before reporting any delivery grade.
                (verify-target! route)
                (try
                  (append-attempt! flow :sent (if wait-presented :Presented :Transported) route request envelope)
                  (str (if wait-presented "Presented" "Transported") ".{ " flow " " (or (:agent_status live) "unknown") " }")
                  (catch Exception error
                    (record-uncertain! flow route request envelope)
                    (throw (ex-info (str "Uncertain.{ " flow " attempt-" (subs (:id submission) 0 12) " } prompt was delivered but ledger confirmation failed; do not retry: " (.getMessage error))
                                    {:hm/failure true :hm/post-ledger true}))))
                (catch Exception error
                  (if (:hm/post-ledger (ex-data error))
                    (throw error)
                    (do (record-uncertain! flow route request envelope)
                        (fail (str "Uncertain.{ " flow " attempt-" (subs (:id submission) 0 12) " } Escape was sent; prompt failed or is uncertain: " (.getMessage error)))))))))))))))
(defn send-abrupt! [flow body wait-presented]
  (send-abrupt-request! flow {:variant :msg :body body} wait-presented))
(defn send-abrupt-psyche! [flow context verbatim wait-presented]
  (send-abrupt-request! flow {:variant :psyche :context context :body verbatim} wait-presented))
(defn record-sent! [flow grade route submission live request envelope]
  (try
    (append-attempt! flow :sent grade route request envelope)
    (str (name grade) ".{ " flow " " (or (:agent_status live) "unknown") " }")
    (catch Exception error
      (record-uncertain! flow route request envelope)
      (throw (ex-info (str "Uncertain.{ " flow " attempt-" (subs (:id submission) 0 12)
                           " } prompt was delivered but ledger confirmation failed; do not retry: " (.getMessage error))
                      {:hm/failure true :hm/post-ledger true})))))
(defn send-request!
  ([flow request wait-presented pane] (send-request! flow request wait-presented pane 10))
  ([flow request wait-presented pane hold-seconds]
   (flow-id! flow)
   (let [request (validate-request! request)]
   (let [sender (or *flow-id* (System/getenv "FLOW_ID") (fail "Set FLOW_ID to your own flow ID before sending"))]
     (with-reservation flow
       (fn []
         (assert-not-retired! flow)
         (let [raw-stored (try (load-route (registry) flow) (catch Exception _ nil))
               stored (try (when raw-stored (route-binding! raw-stored)) (catch Exception _ nil))]
           (when (in-transition? raw-stored) (held! flow :InTransition request stored))
           (when (:route_hold raw-stored) (held! flow :RouteHold request stored))
           (when (or (nil? stored) (needs-binding? stored))
             (hold-repair-required! flow raw-stored pane request))
           (let [route (try (refresh-route-snapshot stored (exact-live-agent stored))
                            (catch Exception _ (hold-repair-required! flow raw-stored pane request)))
                 live (try (verify-target! route)
                           (catch Exception error
                             (let [reason (held-reason error)]
                               (if (contains? #{:Blocked :Uncertain} reason)
                                 (held! flow reason request route)
                                 (hold-repair-required! flow raw-stored pane request)))))
                 route (refresh-route-snapshot route live)
                 envelope (message-envelope sender request)
                 _ (try (prompt-request! route envelope wait-presented)
                        (catch Exception error
                          (if (str/starts-with? (.getMessage error) "RelayOverflow:")
                            (held! flow :RelayOverflow request route)
                            (throw error))))
                 submission (try (append-attempt! flow :Submitting :Uncertain route request envelope)
                                 (catch Exception error
                                   (if (:hm/invalid (ex-data error))
                                     (held! flow :InvalidBinding request route)
                                     (throw error))))
                 grade (if wait-presented :Presented :Transported)]
             (try
               (let [waited? wait-presented
                     reply (prompt!* (transport) route envelope waited?)]
                 (when waited? (presented! route reply)))
               (verify-target! route)
               (record-sent! flow grade route submission live request envelope)
               (catch Exception error
                 (if (:hm/post-ledger (ex-data error))
                   (throw error)
                   (do (record-uncertain! flow route request envelope)
                       (fail (str "Uncertain.{ " flow " attempt-" (subs (:id submission) 0 12)
                                  " } prompt failed or is uncertain: " (.getMessage error)))))))))))))))
(defn send!
  ([flow body wait-presented pane] (send-request! flow {:variant :msg :body body} wait-presented pane))
  ([flow body wait-presented pane hold-seconds]
   (send-request! flow {:variant :msg :body body} wait-presented pane hold-seconds)))
(defn send-psyche!
  ([flow context verbatim wait-presented pane]
   (send-psyche! flow context verbatim wait-presented pane 10))
  ([flow context verbatim wait-presented pane hold-seconds]
   (send-request! flow {:variant :psyche :context context :body verbatim}
                  wait-presented pane hold-seconds)))
(defn send-psyches!
  ([flow input wait-presented pane]
   (send-psyches! flow input wait-presented pane 10))
  ([flow input wait-presented pane hold-seconds]
   (send-request! flow {:variant :psyches :body input}
                  wait-presented pane hold-seconds)))
(defn route-records []
  (into {} (remove (fn [[flow _]] (store/retirement-for (root) flow))
                   (store/routes (root)))))
(defn heartbeat-state! []
  (json/generate-string
   {:version 1
    :routes (mapv (fn [[flow route]] {:flow flow :route route})
                  (sort-by key (route-records)))
    :retirements (store/retirements (root))}))
(defn route-matches-agent? [route agent]
  (and (same-stable-route? route agent)
       (= (:agent route) (:agent agent))))
(defn listing! []
  (let [records (route-records)
        agents (live-agents)
        live-rows (for [agent agents
                        :let [flows (->> records
                                         (keep (fn [[flow route]] (when (route-matches-agent? route agent) flow)))
                                         sort)]]
                    (str (if (seq flows) (str/join "," flows) "-") "\t"
                         (or (:name agent) "-") "\t" (:session agent) "\t"
                         (or (:agent_status agent) "unknown")))
        matched (set (mapcat (fn [agent]
                               (keep (fn [[flow route]] (when (route-matches-agent? route agent) flow)) records))
                             agents))
        stale-rows (for [[flow route] (sort-by key (remove (fn [[flow _]] (contains? matched flow)) records))]
                     (str flow "\t" (:name route) "\t" (:session route) "\tSTALE"))]
    (str/join "\n" (concat ["FLOW\tAGENT\tSESSION\tSTATE"] live-rows stale-rows))))
