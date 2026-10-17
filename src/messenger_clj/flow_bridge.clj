(ns messenger-clj.flow-bridge
  "Flow owns managed addresses; Message identifies the actual subprocess peer."
  (:require [clojure.string :as str]
            [babashka.process :refer [shell]]
            [messenger-clj.core :as runtime]))

(def ^:dynamic *call*
  (fn [program query]
    (shell {:out :string :err :string :continue true :timeout 15000}
           program query)))

(defn address! [target]
  (let [[aspect topic layer :as parts] (str/split target #" ")]
    (when-not (and (= 3 (count parts))
                   (contains? #{"Psyche" "Mind" "Field"} aspect)
                   (re-matches #"[A-Za-z0-9][A-Za-z0-9_-]*" (or topic ""))
                   (contains? #{"Primary" "Secondary" "Tertiary" "Quaternary"} layer))
      (runtime/fail "MetaflowRequired: expected a typed Aspect Topic Layer identifier"))
    (str "{ " aspect " " (runtime/quote-datom topic) " " layer " }")))

(defn managed? [target]
  (let [query (str "Current." (address! target))
        result (try (*call* "flow" query)
                    (catch Exception _ (runtime/fail "FlowRegistryUnavailable: no legacy fallback")))
        answer (str/trim (or (:out result) ""))]
    (when-not (zero? (or (:exit result) -1))
      (runtime/fail "FlowRegistryUnavailable: no legacy fallback"))
    (cond
      (= answer "Current.Unknown") false
      (contains? #{"Current.Asleep" "Current.Ended"} answer) true
      (re-matches #"Current\.Awake\.(?:[A-Za-z0-9_-]+|«[^«»\\\n]+»)" answer) true
      :else (runtime/fail "FlowRegistryRefused: unexpected or refused Current response; no legacy fallback"))))

(defn request-datom [request]
  (let [{:keys [variant context body]} (runtime/validate-request! request)
        said (fn [[c v]] (str "{ " (runtime/quote-datom c) " " (runtime/quote-datom v) " }"))]
    (case variant
      :msg (str "Order." (runtime/quote-datom body))
      :psyche (str "Psyche." (said [context body]))
      :psyches (str "Psyches.[ " (str/join " " (map said (runtime/read-psyches-input body))) " ]"))))

(defn send! [target request {:keys [abrupt wait-presented pane]}]
  (when (or abrupt wait-presented pane)
    (runtime/fail "ManagedDeliveryOptionUnsupported: pane, interrupt and presentation-wait are not queue operations"))
  (let [query (str "Send.{ Address." (address! target) " " (request-datom request) " }")
        result (try (*call* "message" query)
                    (catch Exception _ (runtime/fail "Uncertain: Message exchange failed; no retry or legacy fallback")))
        answer (str/trim (or (:out result) ""))]
    (when-not (zero? (or (:exit result) -1))
      (runtime/fail "Uncertain: Message exchange failed; no retry or legacy fallback"))
    (cond
      (contains? #{"Delivered" "Queued" "Woken"} answer)
      (str answer ".{ " (runtime/quote-datom target) " }")
      (str/starts-with? answer "Refused.")
      ;; Keep internal process/lock IDs out of the public surface. The refusal
      ;; kind is enough to distinguish sender adoption from queue acceptance.
      (let [kind (second (re-find #"^Refused\.([A-Za-z]+)(?:\.|$)" answer))]
        (runtime/fail (str "MessageRefused.{ " (runtime/quote-datom target) " " (or kind "Unknown") " }")))
      :else (runtime/fail "Uncertain: unrecognized Message response; no retry or legacy fallback"))))
