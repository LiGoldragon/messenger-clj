(ns messenger-clj.public
  "Flow-facing commands. Metaflow names are public; registry keys stay internal."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [messenger-clj.legacy-import :as legacy]
            [cheshire.core :as json]
            [messenger-clj.core :as runtime]
            [messenger-clj.flow-bridge :as bridge]
            [messenger-clj.typed-store :as store]
            [malli.core :as m]))

(def skill-note runtime/skill-note)
(def fail runtime/fail)
;; Only a launcher/bootstrap process supplies this internal registration input.
;; It is never accepted as a public command argument or emitted in a receipt.
(def ^:dynamic *registration-flow-id* nil)
(defn name! [value]
  (when-not (m/validate runtime/Metaflow value)
    (fail "MetaflowRequired: use one quoted Aspect Topic Layer identifier"))
  value)
(defn metadata [value]
  (zipmap [:aspect :topic :layer] (str/split (name! value) #" ")))
(defn route-name! [route]
  (try (runtime/metaflow! route)
       (catch Exception _ (fail "MetaflowMetadataRequired: registration lacks aspect, topic or layer"))))
(defn known-name [route]
  (when (every? #(m/validate runtime/MetaflowPart (get route %)) [:aspect :topic :layer])
    (runtime/metaflow! route)))
(defn matching-routes [target]
  (filter (fn [[_ route]] (= target (known-name route))) (runtime/route-records)))
(defn resolve! [target history?]
  (name! target)
  (let [live (vec (matching-routes target))
        historical (when (and history? (empty? live))
                     (filter (fn [[_ name]] (= target name)) (store/metaflow-identities (runtime/root))))
        matches (if (seq live) live historical)]
    (cond
      (> (count matches) 1) (fail (str "AmbiguousTarget: " target))
      (empty? matches) (fail (str "TargetNotRegistered: " target))
      :else (ffirst matches))))
(defn with-registry [f]
  ;; Resolve and act under the same existing registry-wide reservation. Nested
  ;; legacy operations use that reservation rather than acquiring a second one.
  (runtime/with-reservation (or runtime/*flow-id* (System/getenv "FLOW_ID")
                                *registration-flow-id* (System/getenv "HM_REGISTRATION_FLOW_ID")
                                (fail "RuntimeIdentityRequired: harness identity is unavailable"))
    #(binding [runtime/*with-reservation* (fn [_ operation] (operation))] (f))))
(defn named-error [target error]
  (let [data (ex-data error)
        message (or (.getMessage error) "")
        public? (re-find #"^(MetaflowRequired|MetaflowMetadataRequired|AmbiguousTarget|TargetNotRegistered|RuntimeIdentityRequired|RegistrationIdentityRequired|RegistrationIdentityConflict|MetaflowAlreadyRegistered):" message)
        held-text (re-find #"^Held\.\{ \S+ (\S+) ((?:attempt-)?[a-f0-9-]+) \}" message)
        attempt-id (:hm/attempt-id data)
        held (when (and (true? (:hm/held data))
                        (string? attempt-id) (not (str/blank? attempt-id))
                        held-text
                        (contains? #{attempt-id (str "attempt-" (subs attempt-id 0 (min 12 (count attempt-id))))}
                                   (nth held-text 2)))
               held-text)
        uncertain (str/starts-with? message "Uncertain.{")
        reason (or (second (re-find #"^(SenderNotRegistered|SenderBindingUnavailable|LegacyPendingOrigin|PendingOrder|RelayOverflow|IdentityChanged|RouteHold|InTransition|Retired)(?:[:. ]|$)" message))
                   (cond
                     (str/starts-with? message "Reservation") "ReservationRefused"
                     (str/starts-with? message "Registration differs") "BindingMismatch"
                     (str/starts-with? message "Herdr agent has no official") "NativeSessionRequired"
                     (or (str/starts-with? message "Herdr agent_session")
                         (str/starts-with? message "Explicit native thread")) "NativeSessionMismatch"
                     :else "RuntimeBindingOrStore"))
        rendered (cond
                   public? message
                   (:hm/migration-refusal data) (str "MigrationRefused.{ " (runtime/quote-datom target) " " (name (:hm/migration-refusal data)) " }")
                   (:hm/retire-refusal data) (str "RetireRefused.{ " (runtime/quote-datom target) " " (name (:hm/retire-refusal data)) " }")
                   held (str "Held.{ " (runtime/quote-datom target) " " (second held) " " (nth held 2) " }")
                   uncertain (str "Uncertain.{ " (runtime/quote-datom target) " } Do not retry; inspect the retained runtime attempt.")
                   :else (str "Refused.{ " (runtime/quote-datom target) " " reason " }"))]
    ;; Original cause/data remain available to runtime diagnostics, never as a
    ;; flow-facing identity or as payload rewriting.
    (ex-info rendered (assoc data :hm/failure true :hm/public true) error)))
(defn named-call [target f]
  (name! target)
  (try (with-registry f)
       (catch Exception error (throw (named-error target error)))))
(defn registration-id! [target native-thread]
  (let [provided (or *registration-flow-id* (System/getenv "HM_REGISTRATION_FLOW_ID"))
        matches (vec (matching-routes target))]
    (when (> (count matches) 1) (fail (str "AmbiguousTarget: " target)))
    (when provided (runtime/flow-id! provided))
    (let [current (ffirst matches)
          history (filter (fn [[id name]] (and (= target name)
                                               (not (store/retirement-for (runtime/root) id))))
                          (store/metaflow-identities (runtime/root)))
          id (or current provided (when (= 1 (count history)) (ffirst history))
                 (fail "RegistrationIdentityRequired: launcher must supply the actual claimed runtime identity"))
          existing (runtime/load-route (runtime/registry) id)]
      (when (and current provided (not= current provided))
        (fail (str "MetaflowAlreadyRegistered: " target)))
      (when (and (known-name existing) (not= (known-name existing) target))
        (fail "RegistrationIdentityConflict: runtime identity already has another metaflow name"))
      (when (and native-thread existing (:native_thread existing)
                 (not= native-thread (:native_thread existing)))
        (fail "RegistrationIdentityConflict: native session differs from current registration"))
      id)))
(defn register!
  ([target agent-name session native-thread] (register! target agent-name session native-thread nil))
  ([target agent-name session native-thread supplied-metadata]
   (named-call target
     #(let [declared (metadata target)
            id (registration-id! target native-thread)]
        (when (and supplied-metadata (not= declared (runtime/metadata! supplied-metadata)))
          (fail "RegistrationIdentityConflict: metadata flags differ from the public name"))
        (runtime/register! id agent-name session native-thread declared)
        (str "Registered.{ " (runtime/quote-datom target) " }")))))
(defn control! [target label f]
  (named-call target
    #(let [id (resolve! target true)]
       (f id)
       (str label ".{ " (runtime/quote-datom target) " }"))))
(defn delivery-receipt! [target result]
  (let [[_ grade status] (re-matches #"([A-Za-z-]+)\.\{ \S+ ([^{}\n]*) \}" result)]
    (when-not (and grade status)
      (fail "Uncertain.{ internal } Unexpected delivery receipt; do not retry"))
    (str grade ".{ " (runtime/quote-datom target) " " status " }")))
(defn repair! [target & arguments]
  (named-call target
    #(let [id (resolve! target true)
            route (runtime/load-route (runtime/registry) id)]
        (when-not (= target (route-name! route))
          (fail "RegistrationIdentityConflict: repair requires the current named registration"))
        (delivery-receipt! target (apply runtime/repair! id arguments)))))
(defn deregister! [target & arguments]
  (control! target "Deregistered" #(apply runtime/deregister! % arguments)))
(defn rebind! [target & arguments]
  (control! target "Rebound" #(apply runtime/rebind! % arguments)))
(defn move! [target & arguments]
  (control! target "Moved" #(apply runtime/move! % arguments)))
(defn retire! [target]
  (control! target "Retired" runtime/retire!))
(defn import-retirement! [target & arguments]
  ;; Historical import requires an existing named identity. An unidentified old
  ;; record remains audit evidence; callers cannot invent a name from its ID.
  (control! target "Retired" #(apply runtime/import-retirement! % arguments)))
(defn delivery! [target f]
  (named-call target
    #(do (resolve! target false)
         (delivery-receipt! target (f)))))
(defn routed-delivery! [target request options legacy]
  (name! target)
  (runtime/validate-request! request)
  (if (bridge/managed? target)
    (bridge/send! target request options)
    (delivery! target legacy)))
(defn send! [target body wait-presented pane & extra]
  (routed-delivery! target {:variant :msg :body body}
                    {:wait-presented wait-presented :pane pane}
                    #(apply runtime/send! target body wait-presented pane extra)))
(defn send-psyche! [target context body wait-presented pane & extra]
  (routed-delivery! target {:variant :psyche :context context :body body}
                    {:wait-presented wait-presented :pane pane}
                    #(apply runtime/send-psyche! target context body wait-presented pane extra)))
(defn send-psyches! [target body wait-presented pane & extra]
  (routed-delivery! target {:variant :psyches :body body}
                    {:wait-presented wait-presented :pane pane}
                    #(apply runtime/send-psyches! target body wait-presented pane extra)))
(defn send-abrupt! [target body wait-presented]
  (routed-delivery! target {:variant :msg :body body} {:abrupt true :wait-presented wait-presented}
                    #(runtime/send-abrupt! target body wait-presented)))
(defn send-abrupt-psyche! [target context body wait-presented]
  (routed-delivery! target {:variant :psyche :context context :body body} {:abrupt true :wait-presented wait-presented}
                    #(runtime/send-abrupt-psyche! target context body wait-presented)))
(defn send-abrupt-request! [target request wait-presented]
  (routed-delivery! target request {:abrupt true :wait-presented wait-presented}
                    #(runtime/send-abrupt-request! target request wait-presented)))
(defn registry-read [f]
  (try (f)
       (catch Exception error
         (throw (ex-info (if (str/starts-with? (or (.getMessage error) "") "MetaflowMetadataRequired:")
                           (.getMessage error)
                           "RegistryUnavailable: cannot read the qualified public roster")
                         {:hm/failure true :hm/public true} error)))))
(defn heartbeat-state! []
  (registry-read
    #(let [names (store/metaflow-identities (runtime/root))]
       (json/generate-string
        {:version 2
         :lookup_owner "messenger-clj"
         :routes (mapv (fn [[_ route]]
                         {:metaflow (route-name! route)
                          :state (:state route)
                          :session (:session route)
                          :harness (:agent route)
                          :held (boolean (:route_hold route))
                          :transition (boolean (:transition route))})
                       (sort-by (comp route-name! val) (runtime/route-records)))
         :retirements (mapv (fn [record]
                             (if-let [name (get names (:flow record))]
                               {:metaflow (name! name) :state "retired"}
                               {:state "retired" :metadata_unavailable true}))
                           (store/retirements (runtime/root)))}))))
(defn display-title! [flow route]
  ;; Display only: never truncate a registry key or derive metadata from NAME.
  (when-not (and (string? flow) (re-matches #"[0-9a-f]{6}" flow))
    (fail "DisplayIdentityRequired: registration needs its actual six-character flow ID"))
  (str "{ " (route-name! route) " " flow " }"))
(defn listing! []
  (registry-read
    #(let [agents (runtime/live-agents)
           rows (for [[flow route] (sort-by (comp route-name! val) (runtime/route-records))
                      :let [title (display-title! flow route)
                            matches (filter (partial runtime/route-matches-agent? route) agents)
                            state (case (count matches)
                                    0 "STALE"
                                    1 (or (:agent_status (first matches)) "unknown")
                                    "AMBIGUOUS")]]
                  (str title "\t" (:session route) "\t" state))]
       (str/join "\n" (cons "TITLE\tSESSION\tSTATE" rows)))))

(defn import-json! [source target receipt apply?]
  (try
    (let [result (edn/read-string (legacy/import-json! source target receipt apply?))]
      ;; IDs remain in the immutable import receipt, not in public summaries.
      (pr-str (reduce (fn [summary key] (update summary key count)) result
                      [:retirement-route-overlaps :orphan-flow-refs :empty-retired-by])))
    (catch Exception error
      (throw (ex-info "ImportRefused: inspect the retained import evidence before any retry"
                      {:hm/failure true :hm/public true} error)))))
