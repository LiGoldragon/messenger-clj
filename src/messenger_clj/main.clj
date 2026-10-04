(ns messenger-clj.main
  (:require [messenger-clj.core :as hm]
            [messenger-clj.legacy-import :as legacy]))
(defn usage [] (str "Usage: messenger-clj <send|send-abrupt|register|repair|deregister|rebind|move|retire|import-retirement|import-json|heartbeat-state|list> ...\n"
                    "  messenger-clj send TARGET BODY [--wait-presented] [--hold-seconds N] [--pane SESSION:PANE]\n"
                    "  messenger-clj send TARGET --stdin [--wait-presented] [--hold-seconds N] [--pane SESSION:PANE]\n"
                    "  messenger-clj send TARGET --psyche CONTEXT (VERBATIM|--stdin) [--wait-presented] [--hold-seconds N] [--pane SESSION:PANE]\n"
                    "  messenger-clj send TARGET --psyches --stdin [--wait-presented] [--hold-seconds N] [--pane SESSION:PANE]\n"
                    "  messenger-clj retire FLOW\n"
                    hm/skill-note))
(defn arg [xs option] (second (drop-while #(not= option %) xs)))
(defn parse-error [message] (throw (ex-info message {:hm/parse true})))
(def value-options #{"--session" "--native-thread" "--old-name" "--pending-id" "--pane-id" "--terminal-id" "--name" "--agent" "--process-pid" "--evidence" "--evidence-sha256" "--hold-seconds" "--pane" "--target" "--receipt"})
(defn expand-equals [xs]
  (mapcat #(if-let [[_ option value] (re-matches #"(--[^=]+)=(.*)" %)] [option value] [%]) xs))
(defn normalize-options [xs]
  (let [xs (vec (expand-equals xs))]
    (loop [remaining xs positional [] options []]
      (if-let [value (first remaining)]
        (cond
          (contains? #{"--wait-presented" "--apply" "--psyche" "--psyches" "--stdin"} value) (recur (next remaining) positional (conj options value))
          (contains? value-options value) (if-let [argument (second remaining)]
                                            (recur (nnext remaining) positional (into options [value argument]))
                                            (parse-error (str "argument " value ": expected one argument")))
          (.startsWith value "--") (recur (next remaining) positional (conj options value))
          :else (recur (next remaining) (conj positional value) options))
        (into positional options)))))
(defn extra-values! [xs allowed]
  (loop [remaining xs]
    (when-let [value (first remaining)]
      (cond
        (contains? #{"--wait-presented" "--apply" "--psyche" "--psyches" "--stdin"} value) (recur (next remaining))
        (contains? value-options value) (recur (nnext remaining))
        (contains? allowed value) (recur (next remaining))
        :else (parse-error (str "unrecognized arguments: " value))))))
(defn unknown-flags! [xs allowed]
  (doseq [value xs :when (and (.startsWith value "--") (not (contains? allowed value)))]
    (parse-error (str "unrecognized arguments: " value))))
(defn input-mode [xs]
  (let [psyche? (boolean (some #{"--psyche"} xs))
        psyches? (boolean (some #{"--psyches"} xs))
        stdin? (boolean (some #{"--stdin"} xs))
        mode (cond psyches? :psyches psyche? :psyche :else :msg)
        positional-count (count (take-while #(not (.startsWith % "--")) xs))
        expected-count (case [mode stdin?]
                         [:msg false] 2 [:msg true] 1
                         [:psyche false] 3 [:psyche true] 2
                         [:psyches true] 1
                         1)]
    (when (and psyche? psyches?) (parse-error "--psyche and --psyches are mutually exclusive"))
    (when (and psyches? (not stdin?)) (parse-error "--psyches requires --stdin"))
    (when-not (= expected-count positional-count)
      (parse-error "message input arguments do not match the selected --stdin/--psyche/--psyches mode"))
    {:mode mode :stdin? stdin?}))
(defn stdin-text! [] (slurp *in*))
(defn -main [& argv]
  (try
    (let [[op & raw-xs] argv
          xs (normalize-options raw-xs)]
      (if (or (= op "--help") (= op "-h") (some #{"--help" "-h"} xs))
        (println (usage))
        (case op
          "send" (let [{:keys [mode stdin?]} (input-mode xs)
                       [flow first-field second-field & tail] xs
                       [context argv-body rest] (case mode
                                                  :psyche [first-field second-field tail]
                                                  :psyches [nil nil (rest xs)]
                                                  :msg [nil first-field (if (nil? second-field) tail (cons second-field tail))])
                       body (if stdin? (stdin-text!) argv-body)]
                   (when-not (and flow body (or (not= mode :psyche) context))
                     (parse-error (case mode
                                    :psyche "the following arguments are required: flow, --psyche, context, verbatim or --stdin"
                                    :psyches "the following arguments are required: flow, --psyches, --stdin"
                                    "the following arguments are required: flow, message or --stdin")))
                   (unknown-flags! rest #{"--psyche" "--psyches" "--stdin" "--wait-presented" "--hold-seconds" "--pane"})
                   (extra-values! rest #{"--psyche" "--psyches" "--stdin" "--wait-presented" "--hold-seconds" "--pane"})
                   (let [hold (try (Double/parseDouble (or (arg rest "--hold-seconds") "10"))
                                   (catch Exception _ (parse-error "argument --hold-seconds: invalid float value")))]
                     (when-not (<= 0 hold 60) (hm/fail "--hold-seconds must be between 0 and 60"))
                     (println (case mode
                                :psyche (hm/send-psyche! flow context body (boolean (some #{"--wait-presented"} rest)) (arg rest "--pane") hold)
                                :psyches (hm/send-psyches! flow body (boolean (some #{"--wait-presented"} rest)) (arg rest "--pane") hold)
                                (hm/send! flow body (boolean (some #{"--wait-presented"} rest)) (arg rest "--pane") hold)))))
          "send-abrupt" (let [{:keys [mode stdin?]} (input-mode xs)
                              [flow first-field second-field & tail] xs
                              [context argv-body rest] (case mode
                                                         :psyche [first-field second-field tail]
                                                         :psyches [nil nil (rest xs)]
                                                         :msg [nil first-field (if (nil? second-field) tail (cons second-field tail))])
                              body (if stdin? (stdin-text!) argv-body)]
                          (when-not (and flow body (or (not= mode :psyche) context))
                            (parse-error "send-abrupt requires flow and the selected message input"))
                          (unknown-flags! rest #{"--psyche" "--psyches" "--stdin" "--wait-presented" "--hold-seconds"})
                          (extra-values! rest #{"--psyche" "--psyches" "--stdin" "--wait-presented" "--hold-seconds"})
                          (let [hold (try (Double/parseDouble (or (arg rest "--hold-seconds") "10"))
                                          (catch Exception _ (parse-error "argument --hold-seconds: invalid float value")))]
                            (when-not (<= 0 hold 60) (hm/fail "--hold-seconds must be between 0 and 60")))
                          (println (case mode
                                     :psyche (hm/send-abrupt-psyche! flow context body (boolean (some #{"--wait-presented"} rest)))
                                     :psyches (hm/send-abrupt-request! flow {:variant :psyches :body body} (boolean (some #{"--wait-presented"} rest)))
                                     (hm/send-abrupt! flow body (boolean (some #{"--wait-presented"} rest))))))
          "register" (let [[flow name & rest] xs session (arg rest "--session") thread (arg rest "--native-thread")]
                       (when-not (and flow name) (parse-error "the following arguments are required: flow, name"))
                       (unknown-flags! rest #{"--session" "--native-thread"})
                       (extra-values! rest #{"--session" "--native-thread"})
                       (println (hm/register! flow name session thread)))
          "repair" (let [[flow & rest] xs]
                     (when-not flow (parse-error "the following arguments are required: flow"))
                     (unknown-flags! rest #{"--pending-id" "--session" "--pane-id" "--terminal-id" "--name" "--agent"})
                     (extra-values! rest #{"--pending-id" "--session" "--pane-id" "--terminal-id" "--name" "--agent"})
                     (let [pending-id (arg rest "--pending-id") session (arg rest "--session")
                           pane-id (arg rest "--pane-id") terminal-id (arg rest "--terminal-id")
                           name (arg rest "--name") agent (arg rest "--agent")]
                       (when-not (every? some? [pending-id session pane-id terminal-id name agent])
                         (parse-error "the following arguments are required: --pending-id, --session, --pane-id, --terminal-id, --name, --agent"))
                       (println (hm/repair! flow pending-id session pane-id terminal-id name agent))))
          "deregister" (let [[flow & rest] xs]
                         (when-not flow (parse-error "the following arguments are required: flow"))
                         (unknown-flags! rest #{"--session" "--pane-id" "--terminal-id" "--name"})
                         (extra-values! rest #{"--session" "--pane-id" "--terminal-id" "--name"})
                         (let [session (arg rest "--session") pane-id (arg rest "--pane-id") terminal-id (arg rest "--terminal-id") name (arg rest "--name")]
                           (when-not (every? some? [session pane-id terminal-id name])
                             (parse-error "the following arguments are required: --session, --pane-id, --terminal-id, --name"))
                           (println (hm/deregister! flow session pane-id terminal-id name))))
          "rebind" (let [[flow new-name & rest] xs]
                     (when-not (and flow new-name) (parse-error "the following arguments are required: flow, new_name"))
                     (unknown-flags! rest #{"--old-name" "--session" "--pane-id" "--terminal-id" "--agent" "--native-thread"})
                     (extra-values! rest #{"--old-name" "--session" "--pane-id" "--terminal-id" "--agent" "--native-thread"})
                     (let [old-name (arg rest "--old-name") session (arg rest "--session") pane-id (arg rest "--pane-id")
                           terminal-id (arg rest "--terminal-id") agent (arg rest "--agent") native-thread (arg rest "--native-thread")]
                       (when-not (every? some? [old-name session pane-id terminal-id agent native-thread])
                         (parse-error "the following arguments are required: --old-name, --session, --pane-id, --terminal-id, --agent, --native-thread"))
                       (println (hm/rebind! flow old-name new-name session pane-id terminal-id agent native-thread))))
          "move" (let [[flow workspace & rest] xs]
                   (when-not (and flow workspace) (parse-error "the following arguments are required: flow, workspace"))
                   (unknown-flags! rest #{"--session" "--pane-id" "--terminal-id" "--name" "--agent" "--native-thread" "--process-pid"})
                   (extra-values! rest #{"--session" "--pane-id" "--terminal-id" "--name" "--agent" "--native-thread" "--process-pid"})
                   (let [session (arg rest "--session") pane-id (arg rest "--pane-id") terminal-id (arg rest "--terminal-id")
                         name (arg rest "--name") agent (arg rest "--agent") native-thread (arg rest "--native-thread") pid-text (arg rest "--process-pid")]
                     (when-not (every? some? [session pane-id terminal-id name agent native-thread pid-text])
                       (parse-error "the following arguments are required: --session, --pane-id, --terminal-id, --name, --agent, --native-thread, --process-pid"))
                     (let [pid (or (try (parse-long pid-text) (catch Exception _ nil))
                                   (parse-error "argument --process-pid: invalid int value"))]
                       (println (hm/move! flow session pane-id terminal-id name agent native-thread pid workspace)))))
          "retire" (let [[flow & rest] xs]
                     (when-not flow (parse-error "the following arguments are required: flow"))
                     (when (seq rest) (parse-error (str "unrecognized arguments: " (first rest) "; retire takes only the flow id")))
                     (println (hm/retire! flow)))
          "import-retirement" (let [[flow & rest] xs]
                                (when-not flow (parse-error "the following arguments are required: flow"))
                                (unknown-flags! rest #{"--session" "--pane-id" "--terminal-id" "--name" "--agent" "--native-thread" "--evidence" "--evidence-sha256"})
                                (extra-values! rest #{"--session" "--pane-id" "--terminal-id" "--name" "--agent" "--native-thread" "--evidence" "--evidence-sha256"})
                                (let [session (arg rest "--session") pane-id (arg rest "--pane-id") terminal-id (arg rest "--terminal-id") name (arg rest "--name")
                                      agent (arg rest "--agent") native-thread (arg rest "--native-thread") evidence (arg rest "--evidence") digest (arg rest "--evidence-sha256")]
                                  (when-not (every? some? [session pane-id terminal-id name agent native-thread evidence digest])
                                    (parse-error "the following arguments are required: --session, --pane-id, --terminal-id, --name, --agent, --native-thread, --evidence, --evidence-sha256"))
                                  (println (hm/import-retirement! flow session pane-id terminal-id name agent native-thread evidence digest))))
          "import-json" (let [[source & rest] xs]
                          (when-not source (parse-error "the following arguments are required: source"))
                          (unknown-flags! rest #{"--target" "--receipt" "--apply"})
                          (extra-values! rest #{"--target" "--receipt" "--apply"})
                          (let [target (arg rest "--target") receipt (arg rest "--receipt")]
                            (when-not (and target receipt)
                              (parse-error "the following arguments are required: --target, --receipt"))
                            (println (legacy/import-json! source target receipt
                                                          (boolean (some #{"--apply"} rest))))))
          "heartbeat-state" (do (when (seq xs) (parse-error "unrecognized arguments"))
                                (println (hm/heartbeat-state!)))
          "list" (do (when (seq xs) (parse-error "unrecognized arguments")) (println (hm/listing!)))
          (parse-error (str "invalid choice: " op)))))
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*]
        (println (if (:hm/parse (ex-data e))
                   (str "usage: " (usage) "messenger-clj: error: " (.getMessage e))
                   (str (when-not (:hm/held (ex-data e)) "messenger-clj: ") (.getMessage e))))
        (System/exit (if (:hm/parse (ex-data e)) 2 1))))
    (catch Exception e
      (binding [*out* *err*]
        (println "messenger-clj:" (.getMessage e))
        (System/exit 1)))))
