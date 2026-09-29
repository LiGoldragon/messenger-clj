(ns messenger-clj.typed-store
  "Typed Datalevin authority for the Clojure messenger. EDN is exposed only as
  an explicit import/export representation."
  (:require [babashka.fs :as fs]
            [babashka.pods :as pods]
            [clojure.edn :as edn]
            [malli.core :as m]))

(def pod-version "0.8.25")
(if-let [executable (System/getenv "MESSENGER_CLJ_DATALEVIN_POD")]
  (pods/load-pod executable)
  (pods/load-pod 'huahaiy/datalevin pod-version))
(require 'pod.huahaiy.datalevin)

(defn- call [symbol & args]
  (apply (or (resolve symbol)
             (throw (ex-info (str "Datalevin pod lacks " symbol) {})))
         args))

(def Route
  [:map {:closed true}
   [:session :string] [:name :string] [:pane_id :string] [:terminal_id :string]
   [:agent :string] [:native_thread {:optional true} :string]
   [:route_hold {:optional true} :string]
   [:transition {:optional true} :boolean]
   [:state :string]])
(def AttemptBinding
  [:map {:closed true}
   [:session {:optional true} :string] [:name {:optional true} :string]
   [:pane_id {:optional true} :string] [:terminal_id {:optional true} :string]
   [:agent {:optional true} :string] [:native_thread {:optional true} :string]
   [:route_hold {:optional true} :string] [:transition {:optional true} :boolean]
   [:state {:optional true} :string]])
(def Attempt
  [:map {:closed true}
   [:id :string] [:flow :string] [:at :string] [:grade {:optional true} :keyword] [:reason :keyword]
   [:variant {:optional true} [:enum :msg :psyche :psyches]]
   [:context {:optional true} [:maybe :string]] [:body {:optional true} :string]
   [:part_index {:optional true} pos-int?] [:part_count {:optional true} pos-int?]
   [:submitted {:optional true} :string] [:binding {:optional true} AttemptBinding]])
(def Pending
  [:map {:closed true}
   [:attempt Attempt] [:message :string] [:variant {:optional true} [:enum :msg :psyche :psyches]]
   [:context {:optional true} [:maybe :string]]
   [:part_index {:optional true} pos-int?] [:part_count {:optional true} pos-int?]
   [:state [:= "held"]]])
(def RouteIdentity
  [:map {:closed true}
   [:session :string] [:name :string] [:pane_id :string] [:terminal_id :string] [:agent :string]])
(def Evidence
  [:map {:closed true} [:path :string] [:sha256 :string]])
(def Retirement
  [:map {:closed true}
   [:version [:= 1]] [:state [:= "retired"]] [:flow :string]
   [:record RouteIdentity] [:native_thread :string] [:evidence Evidence]
   [:retired_by :string] [:retired_at :string]])

(def schema
  {:flow/id {:db/unique :db.unique/identity}
   :route/flow {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one
                :db/unique :db.unique/identity}
   :route/session {} :route/name {} :route/pane {} :route/terminal {}
   :route/agent {} :route/thread {} :route/hold {} :route/transition {} :route/state {}
   :attempt/id {:db/unique :db.unique/identity}
   :attempt/flow {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :attempt/at {} :attempt/grade {} :attempt/reason {} :attempt/variant {}
   :attempt/context {} :attempt/body {} :attempt/part-index {} :attempt/part-count {}
   :attempt/submitted {}
   :attempt.binding/session {} :attempt.binding/name {} :attempt.binding/pane {}
   :attempt.binding/terminal {} :attempt.binding/agent {} :attempt.binding/thread {}
   :attempt.binding/hold {} :attempt.binding/transition {} :attempt.binding/state {}
   :pending/id {:db/unique :db.unique/identity}
   :pending/attempt {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :pending/message {} :pending/variant {} :pending/context {}
   :pending/part-index {} :pending/part-count {} :pending/state {}
   :retirement/flow {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one
                     :db/unique :db.unique/identity}
   :retirement/version {} :retirement/state {} :retirement/session {}
   :retirement/name {} :retirement/pane {} :retirement/terminal {} :retirement/agent {}
   :retirement/thread {} :retirement/evidence-path {} :retirement/evidence-sha256 {}
   :retirement/retired-by {} :retirement/at {}})

(defn valid! [entity-schema value]
  (if (m/validate entity-schema value)
    value
    (throw (ex-info "Invalid typed store entity"
                    {:value value :explanation (m/explain entity-schema value)}))))

(defn route! [route]
  (valid! Route
          (assoc route :state
                 (or (:state route)
                     (if (:native_thread route) "Bound" "NeedsBinding")))))
(defn attempt! [attempt] (valid! Attempt attempt))
(defn pending! [pending] (valid! Pending (update pending :attempt attempt!)))
(defn retirement! [retirement] (valid! Retirement retirement))

(defn database-path [root] (str (fs/path root "typed-datalevin")))
(defn with-db [root f]
  (let [conn (call 'pod.huahaiy.datalevin/get-conn (database-path root) schema)]
    (try (f conn) (finally (call 'pod.huahaiy.datalevin/close conn)))))
(defn transact! [root tx] (with-db root #(call 'pod.huahaiy.datalevin/transact! % tx)))
(defn query [root form & inputs]
  (with-db root
    #(apply call 'pod.huahaiy.datalevin/q
            form (call 'pod.huahaiy.datalevin/db %) inputs)))

(defn checked-rows! [rows width]
  (when-not (and (or (set? rows) (sequential? rows))
                 (every? #(and (vector? %) (= width (count %))) rows))
    (throw (ex-info "Malformed typed Datalevin query row" {:rows rows :width width})))
  rows)
(defn- one! [rows kind identity]
  (let [rows (checked-rows! rows 1)]
    (when (> (count rows) 1)
      (throw (ex-info (str "Typed " kind " is not unique") {:identity identity :rows rows})))
    (ffirst rows)))
(defn- assoc-present [m k v] (if (nil? v) m (assoc m k v)))

(defn- route-attrs [prefix route]
  (let [route (route! route)]
    (-> {}
        (assoc (keyword prefix "session") (:session route) (keyword prefix "name") (:name route)
               (keyword prefix "pane") (:pane_id route) (keyword prefix "terminal") (:terminal_id route)
               (keyword prefix "agent") (:agent route) (keyword prefix "state") (:state route))
        (assoc-present (keyword prefix "thread") (:native_thread route))
        (assoc-present (keyword prefix "hold") (:route_hold route))
        (assoc-present (keyword prefix "transition") (:transition route)))))
(defn- attrs-route! [prefix entity]
  (let [getv #(get entity (keyword prefix %))]
    (route! (cond-> {:session (getv "session") :name (getv "name") :pane_id (getv "pane")
                     :terminal_id (getv "terminal") :agent (getv "agent") :state (getv "state")}
              (getv "thread") (assoc :native_thread (getv "thread"))
              (getv "hold") (assoc :route_hold (getv "hold"))
              (some? (getv "transition")) (assoc :transition (getv "transition"))))))
(defn- binding-attrs [binding]
  (-> {}
      (assoc-present :attempt.binding/session (:session binding))
      (assoc-present :attempt.binding/name (:name binding))
      (assoc-present :attempt.binding/pane (:pane_id binding))
      (assoc-present :attempt.binding/terminal (:terminal_id binding))
      (assoc-present :attempt.binding/agent (:agent binding))
      (assoc-present :attempt.binding/thread (:native_thread binding))
      (assoc-present :attempt.binding/hold (:route_hold binding))
      (assoc-present :attempt.binding/transition (:transition binding))
      (assoc-present :attempt.binding/state (:state binding))))
(defn- attrs-binding! [entity]
  (valid! AttemptBinding
          (cond-> {}
            (:attempt.binding/session entity) (assoc :session (:attempt.binding/session entity))
            (:attempt.binding/name entity) (assoc :name (:attempt.binding/name entity))
            (:attempt.binding/pane entity) (assoc :pane_id (:attempt.binding/pane entity))
            (:attempt.binding/terminal entity) (assoc :terminal_id (:attempt.binding/terminal entity))
            (:attempt.binding/agent entity) (assoc :agent (:attempt.binding/agent entity))
            (:attempt.binding/thread entity) (assoc :native_thread (:attempt.binding/thread entity))
            (:attempt.binding/hold entity) (assoc :route_hold (:attempt.binding/hold entity))
            (some? (:attempt.binding/transition entity)) (assoc :transition (:attempt.binding/transition entity))
            (:attempt.binding/state entity) (assoc :state (:attempt.binding/state entity)))))

(def route-pull
  [:route/session :route/name :route/pane :route/terminal :route/agent :route/thread
   :route/hold :route/transition :route/state
   {:route/flow [:flow/id]}])
(def attempt-pull
  [:attempt/id :attempt/at :attempt/grade :attempt/reason :attempt/variant
   :attempt/context :attempt/body :attempt/part-index :attempt/part-count
   :attempt/submitted
   :attempt.binding/session :attempt.binding/name :attempt.binding/pane
   :attempt.binding/terminal :attempt.binding/agent :attempt.binding/thread
   :attempt.binding/hold :attempt.binding/transition :attempt.binding/state
   {:attempt/flow [:flow/id]}])
(def pending-pull
  [:pending/message :pending/variant :pending/context :pending/part-index
   :pending/part-count :pending/state
   {:pending/attempt attempt-pull}])
(def retirement-pull
  [:retirement/version :retirement/state :retirement/session :retirement/name
   :retirement/pane :retirement/terminal :retirement/agent :retirement/thread
   :retirement/evidence-path :retirement/evidence-sha256
   :retirement/retired-by :retirement/at {:retirement/flow [:flow/id]}])

(defn- route-tx [root flow route]
  (let [route (route! route)
        current (some-> (query root '[:find (pull ?route [*])
                                      :in $ ?flow
                                      :where [?f :flow/id ?flow] [?route :route/flow ?f]]
                               flow)
                        (one! "route" flow))
        entity-id (:db/id current)
        replacement (cond-> (assoc (route-attrs "route" route)
                                   :route/flow [:flow/id flow])
                      entity-id (assoc :db/id entity-id))
        retractions (for [[attribute value] current
                          :when (and (keyword? attribute)
                                     (= "route" (namespace attribute))
                                     (not= attribute :route/flow)
                                     (not (contains? replacement attribute)))]
                      [:db/retract entity-id attribute value])]
    (vec (concat retractions [{:flow/id flow} replacement]))))
(defn put-route! [root flow route]
  (let [route (route! route)]
    (transact! root (route-tx root flow route))
    route))
(defn- pulled-route! [entity]
  (when-not (string? (get-in entity [:route/flow :flow/id]))
    (throw (ex-info "Malformed route flow reference" {:entity entity})))
  (attrs-route! "route" entity))
(defn stored-route-for [root flow]
  (some-> (query root '[:find (pull ?route ?pattern)
                        :in $ ?flow ?pattern
                        :where [?f :flow/id ?flow] [?route :route/flow ?f]]
                 flow route-pull)
          (one! "route" flow) pulled-route!))

(declare retirement-for)
(defn route-for [root flow]
  (when-not (retirement-for root flow) (stored-route-for root flow)))
(defn routes [root]
  (let [rows (checked-rows!
              (query root '[:find ?flow (pull ?route ?pattern)
                            :in $ ?pattern
                            :where [?f :flow/id ?flow] [?route :route/flow ?f]]
                     route-pull)
              2)]
    (into {} (map (fn [[flow entity]] [flow (pulled-route! entity)]) rows))))
(defn remove-route! [root flow]
  (when-let [entity-id (query root '[:find ?route . :in $ ?flow
                                     :where [?f :flow/id ?flow] [?route :route/flow ?f]]
                              flow)]
    (transact! root [[:db/retractEntity entity-id]]))
  nil)

(defn- attempt-tx [attempt]
  (let [attempt (attempt! attempt)]
    [{:flow/id (:flow attempt)}
     (cond-> {:attempt/id (:id attempt) :attempt/flow [:flow/id (:flow attempt)]
              :attempt/at (:at attempt) :attempt/reason (:reason attempt)}
       (:grade attempt) (assoc :attempt/grade (:grade attempt))
       (:variant attempt) (assoc :attempt/variant (:variant attempt))
       (:context attempt) (assoc :attempt/context (:context attempt))
       (:body attempt) (assoc :attempt/body (:body attempt))
       (:part_index attempt) (assoc :attempt/part-index (:part_index attempt))
       (:part_count attempt) (assoc :attempt/part-count (:part_count attempt))
       (:submitted attempt) (assoc :attempt/submitted (:submitted attempt))
       (:binding attempt) (merge (binding-attrs (:binding attempt))))]))
(defn put-attempt! [root attempt]
  (let [attempt (attempt! attempt)] (transact! root (attempt-tx attempt)) attempt))
(defn put-route-and-attempt! [root flow route attempt]
  (let [route (route! route) attempt (attempt! attempt)]
    (transact! root (vec (concat (route-tx root flow route) (attempt-tx attempt))))
    {:route route :attempt attempt}))
(defn- pulled-attempt! [entity]
  (let [flow (get-in entity [:attempt/flow :flow/id])]
    (when-not (string? flow)
      (throw (ex-info "Malformed attempt flow reference" {:entity entity})))
    (attempt!
     (cond-> {:id (:attempt/id entity) :flow flow :at (:attempt/at entity)
              :reason (:attempt/reason entity)}
       (:attempt/grade entity) (assoc :grade (:attempt/grade entity))
       (:attempt/variant entity) (assoc :variant (:attempt/variant entity))
       (:attempt/context entity) (assoc :context (:attempt/context entity))
       (:attempt/body entity) (assoc :body (:attempt/body entity))
       (:attempt/part-index entity) (assoc :part_index (:attempt/part-index entity))
       (:attempt/part-count entity) (assoc :part_count (:attempt/part-count entity))
       (and (= :psyche (:attempt/variant entity))
            (> (or (:attempt/part-index entity) 0) 1)) (assoc :context nil)
       (:attempt/submitted entity) (assoc :submitted (:attempt/submitted entity))
       (some #(contains? entity %)
             [:attempt.binding/session :attempt.binding/name :attempt.binding/pane
              :attempt.binding/terminal :attempt.binding/agent :attempt.binding/thread])
       (assoc :binding (attrs-binding! entity))))))
(defn attempt-by-id [root id]
  (some-> (query root '[:find (pull ?attempt ?pattern)
                        :in $ ?id ?pattern :where [?attempt :attempt/id ?id]]
                 id attempt-pull)
          (one! "attempt" id) pulled-attempt!))
(defn attempts-for [root flow]
  (->> (checked-rows!
        (query root '[:find (pull ?attempt ?pattern)
                      :in $ ?flow ?pattern
                      :where [?f :flow/id ?flow] [?attempt :attempt/flow ?f]]
               flow attempt-pull)
        1)
       (map (comp pulled-attempt! first)) (sort-by :id) vec))

(defn put-pending! [root pending]
  (let [pending (pending! pending) attempt (:attempt pending)]
    (when-not (attempt-by-id root (:id attempt))
      (throw (ex-info "Pending intent requires a persisted attempt" {:attempt (:id attempt)})))
    (transact! root [(cond-> {:pending/id (:id attempt)
                              :pending/attempt [:attempt/id (:id attempt)]
                              :pending/message (:message pending)
                              :pending/state (:state pending)}
                       (:variant pending) (assoc :pending/variant (:variant pending))
                       (:context pending) (assoc :pending/context (:context pending))
                       (:part_index pending) (assoc :pending/part-index (:part_index pending))
                       (:part_count pending) (assoc :pending/part-count (:part_count pending)))])
    pending))
(defn- pulled-pending! [entity]
  (when-not (map? (:pending/attempt entity))
    (throw (ex-info "Malformed pending attempt reference" {:entity entity})))
  (pending! (cond-> {:attempt (pulled-attempt! (:pending/attempt entity))
                     :message (:pending/message entity) :state (:pending/state entity)}
              (:pending/variant entity) (assoc :variant (:pending/variant entity))
              (:pending/context entity) (assoc :context (:pending/context entity))
              (:pending/part-index entity) (assoc :part_index (:pending/part-index entity))
              (:pending/part-count entity) (assoc :part_count (:pending/part-count entity))
              (and (= :psyche (:pending/variant entity))
                   (> (or (:pending/part-index entity) 0) 1)) (assoc :context nil))))
(defn pending-by-id [root id]
  (some-> (query root '[:find (pull ?pending ?pattern)
                        :in $ ?id ?pattern :where [?pending :pending/id ?id]]
                 id pending-pull)
          (one! "pending intent" id) pulled-pending!))
(defn take-pending-with-attempt! [root id attempt]
  (let [attempt (attempt! attempt)
        pending-id (query root '[:find ?pending . :in $ ?id
                                 :where [?pending :pending/id ?id]] id)]
    (when-not pending-id
      (throw (ex-info "Pending intent is absent or already submitted" {:pending/id id})))
    (transact! root (vec (concat [[:db/retractEntity pending-id]] (attempt-tx attempt))))
    attempt))
(defn pending-for [root flow]
  (->> (checked-rows!
        (query root '[:find (pull ?pending ?pattern)
                      :in $ ?flow ?pattern
                      :where [?f :flow/id ?flow] [?attempt :attempt/flow ?f]
                      [?pending :pending/attempt ?attempt]]
               flow pending-pull)
        1)
       (map (comp pulled-pending! first)) (sort-by #(get-in % [:attempt :id])) vec))

(defn put-retirement! [root retirement]
  (let [retirement (retirement! retirement) record (:record retirement)
        evidence (:evidence retirement)]
    (transact! root
               [{:flow/id (:flow retirement)}
                {:retirement/flow [:flow/id (:flow retirement)]
                 :retirement/version (:version retirement) :retirement/state (:state retirement)
                 :retirement/session (:session record) :retirement/name (:name record)
                 :retirement/pane (:pane_id record) :retirement/terminal (:terminal_id record)
                 :retirement/agent (:agent record) :retirement/thread (:native_thread retirement)
                 :retirement/evidence-path (:path evidence)
                 :retirement/evidence-sha256 (:sha256 evidence)
                 :retirement/retired-by (:retired_by retirement)
                 :retirement/at (:retired_at retirement)}])
    retirement))
(defn- pulled-retirement! [entity]
  (let [flow (get-in entity [:retirement/flow :flow/id])]
    (when-not (string? flow)
      (throw (ex-info "Malformed retirement flow reference" {:entity entity})))
    (retirement!
     {:version (:retirement/version entity) :state (:retirement/state entity) :flow flow
      :record {:session (:retirement/session entity) :name (:retirement/name entity)
               :pane_id (:retirement/pane entity) :terminal_id (:retirement/terminal entity)
               :agent (:retirement/agent entity)}
      :native_thread (:retirement/thread entity)
      :evidence {:path (:retirement/evidence-path entity)
                 :sha256 (:retirement/evidence-sha256 entity)}
      :retired_by (:retirement/retired-by entity) :retired_at (:retirement/at entity)})))
(defn retirement-for [root flow]
  (some-> (query root '[:find (pull ?retirement ?pattern)
                        :in $ ?flow ?pattern
                        :where [?f :flow/id ?flow] [?retirement :retirement/flow ?f]]
                 flow retirement-pull)
          (one! "retirement" flow) pulled-retirement!))
(defn retirements [root]
  (->> (checked-rows!
        (query root '[:find (pull ?retirement ?pattern)
                      :in $ ?pattern :where [?retirement :retirement/flow]]
               retirement-pull)
        1)
       (map (comp pulled-retirement! first)) (sort-by :flow) vec))

(defn- boundary-entity! [entity]
  (when-not (map? entity)
    (throw (ex-info "Typed boundary entity must be a map" {:value entity})))
  (case (:entity/type entity)
    :route (do
             (when-not (string? (:entity/flow entity))
               (throw (ex-info "Route export requires its Flow ID" {:value entity})))
             (assoc entity :entity/value (route! (:entity/value entity))))
    :attempt (assoc entity :entity/value (attempt! (:entity/value entity)))
    :pending (assoc entity :entity/value (pending! (:entity/value entity)))
    :retirement (assoc entity :entity/value (retirement! (:entity/value entity)))
    (throw (ex-info "Unknown typed boundary entity" {:value entity}))))
(defn export-edn [entities]
  (when-not (sequential? entities)
    (throw (ex-info "Typed export must be a sequence" {:value entities})))
  (pr-str (mapv boundary-entity! entities)))
(defn import-edn [text]
  (when-not (string? text)
    (throw (ex-info "Typed import must be EDN text" {:value text})))
  (let [entities (edn/read-string text)]
    (when-not (sequential? entities)
      (throw (ex-info "Typed import must contain a sequential EDN value" {:value entities})))
    (mapv boundary-entity! entities)))
