(ns messenger-clj.legacy-import
  "Offline, one-way conversion of the legacy Python JSON registry into the
  typed Datalevin registry. Source bytes are read once, validated completely,
  and represented by a digest manifest before an optional apply."
  (:import [java.nio.file Files]
           [java.nio.file.attribute PosixFilePermissions])
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [messenger-clj.typed-store :as store]))

(def flow-pattern #"[A-Za-z0-9][A-Za-z0-9_-]{0,95}")
(def attempt-pattern #"[0-9a-f]{32}")
(def thread-pattern #"[A-Za-z0-9-]{16,96}")
(def digest-pattern #"[0-9a-f]{64}")
(def reasons #{"NotRegistered" "NeedsBinding" "InTransition" "RouteHold"
               "IdentityChanged" "PaneMissing" "Blocked"
               "ProcessMismatch" "Stalled" "Uncertain" "RelayOverflow"
               "Submitting" "sent"})
(def grades #{"Transported" "Presented" "Fallback-Presented" "Held" "Uncertain"})

(defn fail! [message path]
  (throw (ex-info (str message (when path (str " in " path)))
                  {:hm/import true :path path})))
(defn exact-map! [value required optional label path]
  (when-not (map? value) (fail! (str label " must be a JSON object") path))
  (let [actual (set (keys value))
        allowed (set/union required optional)]
    (when-let [missing (seq (set/difference required actual))]
      (fail! (str label " is missing fields " (pr-str (sort missing))) path))
    (when-let [unknown (seq (set/difference actual allowed))]
      (fail! (str label " has unknown fields " (pr-str (sort unknown))) path)))
  value)
(defn string! [value label path]
  (when-not (and (string? value) (not (str/blank? value)))
    (fail! (str label " must be a nonempty string") path))
  value)
(defn string-value! [value label path]
  (when-not (string? value) (fail! (str label " must be a string") path))
  value)
(defn pattern! [value pattern label path]
  (string! value label path)
  (when-not (re-matches pattern value) (fail! (str "Malformed " label) path))
  value)
(defn flow! [value path] (pattern! value flow-pattern "Flow ID" path))
(defn attempt-id! [value path] (pattern! value attempt-pattern "attempt ID" path))
(defn thread! [value path] (pattern! value thread-pattern "native thread" path))
(defn timestamp! [value path]
  (string! value "UTC timestamp" path)
  (when-not (str/ends-with? value "Z") (fail! "Timestamp must be UTC" path))
  (try (java.time.Instant/parse value)
       (catch Exception _ (fail! "Malformed UTC timestamp" path)))
  value)
(defn sha256-bytes [bytes]
  (apply str (map #(format "%02x" (bit-and % 0xff))
                  (.digest (doto (java.security.MessageDigest/getInstance "SHA-256")
                             (.update bytes))))))
(defn sha256-file [path] (sha256-bytes (fs/read-all-bytes path)))

(defn json! [bytes path]
  (try
    (json/parse-string (String. bytes java.nio.charset.StandardCharsets/UTF_8) true)
    (catch Exception _ (fail! "Malformed JSON" path))))

(defn route! [flow value path]
  (flow! flow path)
  (exact-map! value #{:session :name :pane_id :terminal_id :agent}
              #{:native_thread :route_hold :transition :state}
              "route" path)
  (let [route {:session (string! (:session value) "route session" path)
               :name (string! (:name value) "route name" path)
               :pane_id (string! (:pane_id value) "route pane" path)
               :terminal_id (string! (:terminal_id value) "route terminal" path)
               :agent (string! (:agent value) "route agent" path)}]
    (store/route!
     (let [result (cond-> route
                    (contains? value :native_thread) (assoc :native_thread (thread! (:native_thread value) path))
                    (contains? value :route_hold) (assoc :route_hold (string! (:route_hold value) "route hold" path))
                    (contains? value :transition) (assoc :transition (if (boolean? (:transition value))
                                                                       (:transition value)
                                                                       (fail! "Route transition must be boolean" path)))
                    (contains? value :state) (assoc :state (string! (:state value) "route state" path)))]
       result))))

(defn binding! [value path]
  (exact-map! value #{} #{:pane_id :terminal_id :native_thread} "attempt binding" path)
  (cond-> {}
    (contains? value :pane_id) (assoc :pane_id (string! (:pane_id value) "binding pane" path))
    (contains? value :terminal_id) (assoc :terminal_id (string! (:terminal_id value) "binding terminal" path))
    (contains? value :native_thread) (assoc :native_thread (thread! (:native_thread value) path))))

(def attempt-fields #{:id :at :flow :reason :grade :binding})
(defn attempt! [value path]
  (exact-map! value attempt-fields #{} "attempt" path)
  (when-not (contains? reasons (:reason value)) (fail! "Unknown attempt reason or case" path))
  (when-not (or (nil? (:grade value)) (contains? grades (:grade value)))
    (fail! "Unknown attempt grade or case" path))
  (when-not (or (nil? (:binding value)) (map? (:binding value)))
    (fail! "Attempt binding must be null or an object" path))
  (store/attempt!
   (cond-> {:id (attempt-id! (:id value) path)
            :at (timestamp! (:at value) path)
            :flow (flow! (:flow value) path)
            :reason (keyword (:reason value))}
     (:grade value) (assoc :grade (keyword (:grade value)))
     (:binding value) (assoc :binding (binding! (:binding value) path)))))

(defn retirement! [flow value path]
  (flow! flow path)
  (exact-map! value #{:version :state :flow :record :native_thread :evidence :retired_by :retired_at}
              #{} "retirement" path)
  (when-not (and (= 1 (:version value)) (= "retired" (:state value)) (= flow (:flow value)))
    (fail! "Retirement header does not match its file" path))
  (exact-map! (:record value) #{:session :name :pane_id :terminal_id :agent} #{} "retirement record" path)
  (exact-map! (:evidence value) #{:path :sha256} #{} "retirement evidence" path)
  (let [evidence-path (string! (get-in value [:evidence :path]) "evidence path" path)
        expected (pattern! (get-in value [:evidence :sha256]) digest-pattern "evidence SHA-256" path)]
    (when-not (fs/absolute? evidence-path) (fail! "Retirement evidence path must be absolute" path))
    (when-not (fs/regular-file? evidence-path) (fail! "Retirement evidence file is unavailable" path))
    (when-not (= expected (sha256-file evidence-path)) (fail! "Retirement evidence SHA-256 mismatch" path))
    (store/retirement!
     {:version 1 :state "retired" :flow flow
      :record (into {} (map (fn [[key label]] [key (string! (get-in value [:record key]) label path)]))
                    [[:session "retirement session"] [:name "retirement name"]
                     [:pane_id "retirement pane"] [:terminal_id "retirement terminal"]
                     [:agent "retirement agent"]])
      :native_thread (thread! (:native_thread value) path)
      :evidence {:path evidence-path :sha256 expected}
      :retired_by (string-value! (:retired_by value) "retired_by" path)
      :retired_at (timestamp! (:retired_at value) path)})))

(defn source-paths [root]
  (let [root (fs/absolutize root)]
    (when-not (fs/directory? root) (fail! "Source registry directory is unavailable" (str root)))
    (->> (concat (fs/glob root "*.json")
                 (let [attempts (fs/path root "attempts.jsonl")]
                   (when (fs/regular-file? attempts) [attempts]))
                 (when (fs/directory? (fs/path root "pending")) (fs/glob root "pending/*.json"))
                 (when (fs/directory? (fs/path root "retired")) (fs/glob root "retired/*.json")))
         (filter fs/regular-file?)
         (sort-by str)
         vec)))
(defn read-snapshot [root]
  (let [root (fs/absolutize root)
        files (mapv (fn [path]
                      (let [bytes (fs/read-all-bytes path)]
                        {:path path :relative (str (fs/relativize root path))
                         :bytes bytes :size (alength bytes) :sha256 (sha256-bytes bytes)}))
                    (source-paths root))
        digest (java.security.MessageDigest/getInstance "SHA-256")]
    (doseq [{:keys [relative bytes]} files]
      (.update digest (.getBytes relative java.nio.charset.StandardCharsets/UTF_8))
      (.update digest (byte-array [0]))
      (.update digest bytes)
      (.update digest (byte-array [0])))
    {:root (str root) :digest (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest digest)))
     :files files}))

(defn duplicate! [kind ids]
  (when-let [id (->> ids frequencies (filter #(> (val %) 1)) ffirst)]
    (fail! (str "Duplicate " kind " " id) nil)))
(defn route-conflicts! [routes]
  (doseq [[label key-fn] [["session/name" #(select-keys (val %) [:session :name])]
                          ["session/pane" #(select-keys (val %) [:session :pane_id])]
                          ["session/terminal" #(select-keys (val %) [:session :terminal_id])]]]
    (when-let [[identity rows] (first (filter #(> (count (val %)) 1) (group-by key-fn routes)))]
      (fail! (str "Conflicting route " label " " (pr-str identity)
                  " for flows " (pr-str (sort (map key rows)))) nil))))
(defn native-conflicts! [routes retirements]
  (let [bindings (concat (keep (fn [[flow route]]
                                 (when-let [thread (:native_thread route)] [flow thread]))
                               routes)
                         (map (fn [[flow retirement]] [flow (:native_thread retirement)]) retirements))]
    (when-let [[thread rows] (first (filter #(> (count (set (map first (val %)))) 1)
                                            (group-by second bindings)))]
      (fail! (str "Conflicting native thread " thread " for flows "
                  (pr-str (sort (set (map first rows))))) nil))))

(defn parse-snapshot [snapshot]
  (let [files (:files snapshot)
        route-files (filter #(re-matches #"[^/]+\.json" (:relative %)) files)
        attempt-file (first (filter #(= "attempts.jsonl" (:relative %)) files))
        pending-files (filter #(str/starts-with? (:relative %) "pending/") files)
        retirement-files (filter #(str/starts-with? (:relative %) "retired/") files)
        routes (into (sorted-map)
                     (map (fn [{:keys [relative bytes]}]
                            (let [flow (subs relative 0 (- (count relative) 5))]
                              [flow (route! flow (json! bytes relative) relative)])))
                     route-files)
        raw-attempts (if attempt-file
                       (->> (str/split-lines (String. (:bytes attempt-file) java.nio.charset.StandardCharsets/UTF_8))
                            (remove str/blank?)
                            (map-indexed (fn [index line]
                                           (attempt! (json! (.getBytes line java.nio.charset.StandardCharsets/UTF_8)
                                                            (str "attempts.jsonl:" (inc index)))
                                                     (str "attempts.jsonl:" (inc index)))))
                            vec)
                       [])
        _ (duplicate! "attempt ID" (map :id raw-attempts))
        attempt-map (into {} (map (juxt :id identity) raw-attempts))
        pending-rows (mapv (fn [{:keys [relative bytes]}]
                             (let [value (json! bytes relative)]
                               (exact-map! value (conj attempt-fields :message :state) #{} "pending intent" relative)
                               (let [embedded (attempt! (select-keys value attempt-fields) relative)
                                     id (:id embedded)
                                     filename (fs/file-name relative)
                                     source (get attempt-map id)]
                                 (when-not (= filename (str id ".json")) (fail! "Pending filename does not match attempt ID" relative))
                                 (when-not source (fail! (str "Dangling pending attempt " id) relative))
                                 (when-not (= source embedded) (fail! (str "Pending attempt " id " conflicts with attempts.jsonl") relative))
                                 (when-not (= "held" (:state value)) (fail! "Pending state must be held" relative))
                                 (let [body (string! (:message value) "pending message" relative)
                                       enriched (store/attempt! (assoc source :body body))]
                                   {:id id :attempt enriched
                                    :pending (store/pending! {:attempt enriched :message body :state "held"})}))))
                           pending-files)
        _ (duplicate! "pending attempt ID" (map :id pending-rows))
        pending-by-id (into {} (map (juxt :id identity) pending-rows))
        attempts (mapv #(or (get-in pending-by-id [(:id %) :attempt]) %) raw-attempts)
        retirements (into (sorted-map)
                          (map (fn [{:keys [relative bytes]}]
                                 (let [filename (fs/file-name relative)
                                       flow (subs filename 0 (- (count filename) 5))]
                                   [flow (retirement! flow (json! bytes relative) relative)])))
                          retirement-files)
        _ (duplicate! "retirement Flow" (keys retirements))
        overlaps (vec (sort (set/intersection (set (keys routes)) (set (keys retirements)))))
        effective-routes (apply dissoc routes overlaps)
        _ (route-conflicts! effective-routes)
        _ (native-conflicts! effective-routes retirements)
        referenced-flows (set (concat (map :flow attempts) (keys routes) (keys retirements)))
        anchored-flows (set (concat (keys effective-routes) (keys retirements)))
        orphan-flows (vec (sort (set/difference referenced-flows anchored-flows)))
        empty-retired-by (vec (sort (keep (fn [[flow retirement]]
                                            (when (empty? (:retired_by retirement)) flow))
                                          retirements)))
        entities (vec (concat (map (fn [[flow route]] {:entity/type :route :entity/flow flow :entity/value route}) effective-routes)
                              (map (fn [attempt] {:entity/type :attempt :entity/value attempt}) attempts)
                              (map (fn [{:keys [pending]}] {:entity/type :pending :entity/value pending}) pending-rows)
                              (map (fn [[_ retirement]] {:entity/type :retirement :entity/value retirement}) retirements)))]
    {:routes routes :effective-routes effective-routes :attempts attempts
     :pending (mapv :pending pending-rows) :retirements retirements
     :overlaps overlaps :orphan-flows orphan-flows :empty-retired-by empty-retired-by
     :entities (store/import-edn (store/export-edn entities))}))

(defn receipt! [value]
  (exact-map! value #{:receipt/version :source/snapshot :import/report :export/entities} #{} "receipt" nil)
  (when-not (= 1 (:receipt/version value)) (fail! "Unsupported receipt version" nil))
  (let [snapshot (:source/snapshot value)
        report (:import/report value)]
    (exact-map! snapshot #{:root :sha256 :files} #{} "source snapshot" nil)
    (when-not (fs/absolute? (:root snapshot)) (fail! "Snapshot root must be absolute" nil))
    (pattern! (:sha256 snapshot) digest-pattern "snapshot SHA-256" nil)
    (when-not (vector? (:files snapshot)) (fail! "Snapshot files must be a vector" nil))
    (doseq [file (:files snapshot)]
      (exact-map! file #{:relative :size :sha256} #{} "snapshot file" nil)
      (when-not (and (string? (:relative file)) (not (str/blank? (:relative file)))
                     (not (fs/absolute? (:relative file)))
                     (not-any? #{".."} (fs/components (:relative file))))
        (fail! "Snapshot relative path is unsafe" nil))
      (when-not (and (integer? (:size file)) (not (neg? (:size file))))
        (fail! "Snapshot file size is invalid" nil))
      (pattern! (:sha256 file) digest-pattern "snapshot file SHA-256" nil))
    (exact-map! report #{:mode :counts :inserted :unchanged :route-removals
                         :retirement-route-overlaps :orphan-flow-refs :empty-retired-by}
                #{} "import report" nil)
    (when-not (contains? #{:dry-run :applied} (:mode report)) (fail! "Receipt mode is invalid" nil))
    (exact-map! (:counts report)
                #{:source-routes :source-bound-routes :source-needs-binding-routes
                  :effective-routes :attempts :attempt-bodies :pending :retirements :flow-ids}
                #{} "receipt counts" nil)
    (doseq [[key amount] (:counts report)]
      (when-not (and (integer? amount) (not (neg? amount)))
        (fail! (str "Receipt count " (name key) " is invalid") nil)))
    (doseq [key [:inserted :unchanged :route-removals]]
      (when-not (and (integer? (get report key)) (not (neg? (get report key))))
        (fail! (str "Receipt " (name key) " is invalid") nil)))
    (doseq [key [:retirement-route-overlaps :orphan-flow-refs :empty-retired-by]]
      (when-not (and (vector? (get report key)) (every? string? (get report key)))
        (fail! (str "Receipt " (name key) " must be a string vector") nil))))
  (let [entities (store/import-edn (pr-str (:export/entities value)))
        counts (get-in value [:import/report :counts])
        entity-counts (frequencies (map :entity/type entities))]
    (when-not (and (= (:effective-routes counts) (get entity-counts :route 0))
                   (= (:attempts counts) (get entity-counts :attempt 0))
                   (= (:pending counts) (get entity-counts :pending 0))
                   (= (:retirements counts) (get entity-counts :retirement 0))
                   (= (:attempt-bodies counts)
                      (count (filter #(and (= :attempt (:entity/type %))
                                           (contains? (:entity/value %) :body)) entities))))
      (fail! "Receipt counts do not match its typed entities" nil))
    (assoc value :export/entities entities)))
(defn read-receipt [text] (receipt! (edn/read-string text)))

(defn snapshot-manifest [snapshot]
  {:root (:root snapshot) :sha256 (:digest snapshot)
   :files (mapv #(select-keys % [:relative :size :sha256]) (:files snapshot))})
(defn counts [parsed]
  {:source-routes (count (:routes parsed))
   :source-bound-routes (count (filter #(= "Bound" (:state %)) (vals (:routes parsed))))
   :source-needs-binding-routes (count (filter #(= "NeedsBinding" (:state %)) (vals (:routes parsed))))
   :effective-routes (count (:effective-routes parsed))
   :attempts (count (:attempts parsed))
   :attempt-bodies (count (filter :body (:attempts parsed)))
   :pending (count (:pending parsed))
   :retirements (count (:retirements parsed))
   :flow-ids (count (set (concat (keys (:routes parsed)) (keys (:retirements parsed))
                                 (map :flow (:attempts parsed)))))})

(defn target-value [target entity]
  (case (:entity/type entity)
    :route (store/stored-route-for target (:entity/flow entity))
    :attempt (store/attempt-by-id target (get-in entity [:entity/value :id]))
    :pending (store/pending-by-id target (get-in entity [:entity/value :attempt :id]))
    :retirement (store/retirement-for target (get-in entity [:entity/value :flow]))))
(defn preflight-target [target parsed]
  (if-not (fs/exists? (store/database-path target))
    {:inserted (count (:entities parsed)) :unchanged 0 :route-removals 0}
    (let [states (mapv (fn [entity]
                         (let [existing (target-value target entity)]
                           (when (and (= :route (:entity/type entity))
                                      (store/retirement-for target (:entity/flow entity)))
                             (fail! (str "Target retirement conflicts with route " (:entity/flow entity)) nil))
                           (when (and existing (not= existing (:entity/value entity)))
                             (fail! (str "Target conflicts with " (name (:entity/type entity)) " identity") nil))
                           (if existing :unchanged :inserted)))
                       (:entities parsed))
          removals (count (keep (fn [flow]
                                  (when-let [existing (store/stored-route-for target flow)]
                                    (when-not (= existing (get-in parsed [:routes flow]))
                                      (fail! (str "Target route conflicts with retirement " flow) nil))
                                    flow))
                                (:overlaps parsed)))
          retained (merge (store/routes target) (:effective-routes parsed))]
      (route-conflicts! retained)
      (native-conflicts! retained
                         (into {} (map (juxt :flow identity)
                                       (concat (store/retirements target)
                                               (vals (:retirements parsed))))))
      {:inserted (count (filter #{:inserted} states))
       :unchanged (count (filter #{:unchanged} states))
       :route-removals removals})))

(defn apply-entities! [target parsed]
  (doseq [attempt (:attempts parsed)] (store/put-attempt! target attempt))
  (doseq [pending (:pending parsed)] (store/put-pending! target pending))
  (doseq [[flow route] (:effective-routes parsed)] (store/put-route! target flow route))
  (doseq [[flow retirement] (:retirements parsed)]
    (store/put-retirement! target retirement)
    (store/remove-route! target flow)))

(defn unchanged-snapshot! [snapshot]
  (let [again (read-snapshot (:root snapshot))]
    (when-not (= (snapshot-manifest snapshot) (snapshot-manifest again))
      (fail! "Source registry changed during import" (:root snapshot)))))
(defn verify-retirement-evidence! [parsed]
  (doseq [[flow retirement] (:retirements parsed)
          :let [{:keys [path sha256]} (:evidence retirement)]]
    (when-not (and (fs/regular-file? path) (= sha256 (sha256-file path)))
      (fail! (str "Retirement evidence changed for " flow) path))))
(defn write-receipt! [path receipt]
  (let [path (fs/absolutize path)
        temporary (fs/path (fs/parent path) (str "." (fs/file-name path) ".tmp"))
        text (str (pr-str (receipt! receipt)) "\n")]
    (fs/create-dirs (fs/parent path))
    (spit (str temporary) text)
    (Files/setPosixFilePermissions (fs/path temporary) (PosixFilePermissions/fromString "rw-------"))
    (fs/move temporary path {:replace-existing true})
    (Files/setPosixFilePermissions (fs/path path) (PosixFilePermissions/fromString "rw-------"))
    (read-receipt (slurp (str path)))
    (str path)))

(defn import-json! [source target receipt-path apply?]
  (let [source (str (fs/absolutize source))
        target (str (fs/absolutize target))]
    (when (= source target) (fail! "Source and target must be different directories" source))
    (when (.startsWith (.normalize (fs/path target)) (.normalize (fs/path source)))
      (fail! "Target must be outside the source registry" target))
    (let [snapshot (read-snapshot source)
          parsed (parse-snapshot snapshot)
          target-report (preflight-target target parsed)
          report (merge {:mode (if apply? :applied :dry-run)
                         :counts (counts parsed)
                         :retirement-route-overlaps (:overlaps parsed)
                         :orphan-flow-refs (:orphan-flows parsed)
                         :empty-retired-by (:empty-retired-by parsed)}
                        target-report)
          receipt {:receipt/version 1 :source/snapshot (snapshot-manifest snapshot)
                   :import/report report :export/entities (:entities parsed)}]
      (when apply?
        (unchanged-snapshot! snapshot)
        (verify-retirement-evidence! parsed)
        (apply-entities! target parsed)
        (let [verification (preflight-target target parsed)]
          (when-not (and (zero? (:inserted verification))
                         (= (count (:entities parsed)) (:unchanged verification))
                         (zero? (:route-removals verification)))
            (fail! "Target read-after-write verification failed" target))))
      (let [receipt-path (write-receipt! receipt-path receipt)
            output {:mode (:mode report) :source-sha256 (:digest snapshot)
                    :target target :receipt receipt-path
                    :counts (:counts report) :inserted (:inserted report)
                    :unchanged (:unchanged report) :route-removals (:route-removals report)
                    :retirement-route-overlaps (:retirement-route-overlaps report)
                    :orphan-flow-refs (:orphan-flow-refs report)
                    :empty-retired-by (:empty-retired-by report)}]
        (pr-str output)))))
