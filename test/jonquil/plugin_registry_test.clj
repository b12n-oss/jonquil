(ns jonquil.plugin-registry-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jonquil.command-registry :as cmd-registry]
            [jonquil.evaluator :as ev]
            [jonquil.keymap :as km]
            [jonquil.plugin :as p]
            [jonquil.plugin-registry :as reg]))

(defn- plugin
  "A definition for id with one :edit command (edit) bound to Cmd+Alt+J."
  [id edit & {:keys [more-commands more-bindings]}]
  {:id (keyword id) :doc "d" :effects #{:console}
   :commands (into [{:id (keyword id "wrap") :doc "Wrap." :scope :editor :edit edit}] more-commands)
   :bindings (into [{:key "j" :mods #{:cmd :alt} :command (keyword id "wrap")}] more-bindings)})

(defn- with [& defs]
  (reduce (fn [r d] (reg/install r (:id d) {:definition d :source "" :file "" :layer :user}))
          (reg/empty-registry) defs))

(def ^:private ctrl-alt (bit-or (:ctrl km/MODIFIER-MASKS) (:alt km/MODIFIER-MASKS)))

(deftest empty-registry-is-the-shipped-keymap-test
  (let [r (reg/empty-registry)]
    (is (= (:window km/shipped) (reg/window-bindings r)))
    (is (= (:editor km/shipped) (reg/editor-bindings r)))
    (is (= cmd-registry/commands (reg/all-commands r)))
    (is (= {} (reg/commands r)))))

(deftest edit-command-outcomes-test
  (let [r (with (plugin "p3-a" (fn [s] (p/wrap-form s "(when " ")"))))
        applied (atom [])
        ctx (fn [snap] {:snapshot (constantly snap) :apply-op! #(swap! applied conj %)})
        run (fn [snap] (cmd-registry/run-command! (reg/commands r) {:command :p3-a/wrap} (ctx snap)))]
    (is (= :editor (:scope (get (reg/commands r) :p3-a/wrap))))
    (is (= :p3-a (:plugin (get (reg/commands r) :p3-a/wrap))))
    (is (= :handled (run {:text "(a b)" :caret 1 :sel-start 1 :sel-end 1})))
    (is (= 1 (count @applied)))
    (reset! applied [])
    (is (= :to-gtk (run {:text "(a)\n\n(b)" :caret 4 :sel-start 4 :sel-end 4})) "no form: nil op")
    (is (= [] @applied)))
  (let [r (with (plugin "p3-throw" (fn [_] (throw (ex-info "kaboom" {})))))
        e (try (cmd-registry/run-command! (reg/commands r) {:command :p3-throw/wrap}
                                          {:snapshot (constantly {:text "" :caret 0}) :apply-op! identity})
               nil (catch :default e e))]
    (is (some? e))
    (is (str/starts-with? (ex-message e) "plugin p3-throw: "))
    (is (str/includes? (ex-message e) "kaboom"))))

(deftest run-commands-installed-test
  (let [r (with (plugin "p3-run" (fn [_] nil)
                        :more-commands [{:id :p3-run/say :doc "Say." :scope :window :run (fn [_] nil)}]
                        :more-bindings [{:key "j" :mods #{:cmd :alt :shift} :command :p3-run/say}]))]
    (is (= #{:p3-run/wrap :p3-run/say} (set (keys (reg/commands r)))))
    (is (= {:scope :window :plugin :p3-run} (select-keys (get (reg/commands r) :p3-run/say) [:scope :plugin])))
    (is (= 1 (count (reg/bindings r :window))))
    (is (= 1 (count (reg/bindings r :editor))))))

(defn- run-plugin
  "A registry holding one plugin id declaring effects, with a :run command
  :<id>/go whose body is run."
  [id effects run]
  (with {:id (keyword id) :doc "d" :effects effects
         :commands [{:id (keyword id "go") :doc "Go." :scope :window :run run}]}))

(defn- recording-effects
  "An effects map whose functions record into calls; the active tab is 7."
  [calls]
  {:active-tab (constantly 7)
   :print! #(swap! calls conj [:print! %])
   :status! #(swap! calls conj [:status! %])
   :buffer-text (fn [tab] (swap! calls conj [:buffer-text tab]) "t")
   :replace-text! (fn [tab s] (swap! calls conj [:replace-text! tab s]))
   :open! (fn [path] (swap! calls conj [:open! path]) 8)
   :report! #(swap! calls conj [:report! %])})

(defn- go! [r id]
  (cmd-registry/run-command! (reg/commands r) {:command (keyword id "go")} {}))

(defn- await-calls [calls n]
  (loop [i 0]
    (when (and (< (count @calls) n) (< i 200)) (Thread/sleep 10) (recur (inc i))))
  @calls)

(deftest run-command-runs-on-a-worker-test
  (let [calls (atom [])
        gate (promise)
        done (promise)
        r (run-plugin "p5-slow" #{:console} (fn [_] @gate (deliver done true)))]
    (reset! reg/effects (recording-effects calls))
    (let [t0 (System/currentTimeMillis)]
      (is (= :handled (go! r "p5-slow")))
      (is (< (- (System/currentTimeMillis) t0) 1000) "the key answers before run finishes"))
    (is (not (realized? done)))
    (deliver gate true)
    (is (true? (deref done 2000 nil)))))

(defn- go-when-free!
  "go!, retried for up to 2 s while the last run is still finishing."
  [r id]
  (loop [i 0]
    (let [v (try (go! r id) (catch :default e (if (< i 200) ::busy (throw e))))]
      (if (= ::busy v) (do (Thread/sleep 10) (recur (inc i))) v))))

(deftest run-command-single-flight-test
  ;; a second press while the first run is still going starts no copy
  ;; (future-cancel can't stop a busy loop, so each copy would keep a core)
  (let [calls (atom [])
        gate (promise)
        started (atom 0)
        second-run (promise)
        r (run-plugin "p5-once" #{:console} (fn [_] (when (= 2 (swap! started inc)) (deliver second-run true)) @gate))]
    (reset! reg/effects (recording-effects calls))
    (is (= :handled (go! r "p5-once")))
    (let [e (try (go! r "p5-once") nil (catch :default e e))]
      (is (= "plugin p5-once: :p5-once/go is still running" (ex-message e))))
    (deliver gate true)
    (is (= :handled (go-when-free! r "p5-once")) "a finished run frees the command")
    (is (true? (deref second-run 2000 nil)))
    (is (= 2 @started))))

(deftest run-command-ctx-test
  (let [calls (atom [])
        seen (promise)
        r (run-plugin "p5-ctx" #{:console} (fn [ctx] (p/print! ctx "hi") (deliver seen ctx)))]
    (reset! reg/effects (recording-effects calls))
    (go! r "p5-ctx")
    (let [ctx (deref seen 2000 nil)]
      (is (= [[:print! "hi"]] @calls))
      (is (= 7 (::p/tab ctx)) "the tab active when it started")
      (is (= #{:console} (::p/effects ctx)))
      (is (= #{:print! :status!} (set (keys (::p/impl ctx)))) "only the declared effects' functions"))))

(deftest run-command-reports-a-throw-test
  (doseq [[id run msg] [["p5-boom" (fn [_] (throw (ex-info "kaboom" {}))) "plugin p5-boom: kaboom"]
                        ["p5-undeclared" (fn [ctx] (p/buffer-text ctx))
                         "plugin p5-undeclared: this plugin didn't declare :buffer"]]]
    (let [calls (atom [])
          r (run-plugin id #{:console} run)]
      (reset! reg/effects (recording-effects calls))
      (is (= :handled (go! r id)))
      (is (= [[:report! msg]] (await-calls calls 1)) id))))

(deftest binding-order-test
  (doseq [order [["p3-a" "p3-b"] ["p3-b" "p3-a"]]]
    (let [r (apply with (map #(plugin % (fn [s] s)) order))
          b (km/resolve-binding (reg/editor-bindings r) 0x6a ctrl-alt (constantly true))]
      (is (= :p3-a/wrap (:command b)) (pr-str order))))
  (let [r (with (plugin "p3-a" (fn [s] s)))]
    (is (= (inc (count (:editor km/shipped))) (count (reg/editor-bindings r))))
    (is (= :p3-a/wrap (:command (first (reg/editor-bindings r)))) "plugin bindings come first")))

(deftest remove-plugin-test
  (let [r (reg/remove-plugin (with (plugin "p3-a" (fn [s] s))) :p3-a)]
    (is (= {} (reg/commands r)))
    (is (= (:editor km/shipped) (reg/editor-bindings r)))))

(deftest edit-op-checked-on-the-live-buffer-test
  ;; an op that's wrong for the buffer it ran on isn't applied, and the
  ;; throw names the plugin (review I4)
  (let [r (with (plugin "p3-oob" (fn [_] {:edits [[0 999 "x"]] :caret 0})))
        applied (atom [])
        e (try (cmd-registry/run-command! (reg/commands r) {:command :p3-oob/wrap}
                                          {:snapshot (constantly {:text "(a)" :caret 0 :sel-start 0 :sel-end 0})
                                           :apply-op! #(swap! applied conj %)})
               nil (catch :default e e))]
    (is (some? e))
    (is (str/starts-with? (ex-message e) "plugin p3-oob: "))
    (is (= [] @applied))))

(deftest gui-turn-abandons-a-late-thunk-test
  ;; review I1: a turn that timed out must not apply later, or a plugin
  ;; told "the window did not respond" sees its edit land anyway
  (let [queued (atom [])
        gui #(swap! queued conj %)
        ran (atom 0)
        e (try (reg/gui-turn gui #(swap! ran inc) 50) nil (catch :default e e))]
    (is (= "The window did not respond." (some-> e ex-message)))
    (doseq [t @queued] (t))
    (is (zero? @ran) "the late thunk does nothing"))
  (is (= 3 (reg/gui-turn (fn [t] (t)) (constantly 3) 1000)))
  (is (= "that tab was closed"
         (try (reg/gui-turn (fn [t] (t)) #(throw (ex-info "that tab was closed" {})) 1000) nil
              (catch :default e (ex-message e))))))

(defn- fake-fire
  "A fire fn for await-eval! that answers with result, after printing outs
  ([kind text] ...), or with busy when it's a string, or never when result
  is ::never, or asks for input when it's ::input."
  [result & {:keys [outs busy]}]
  (fn [{:keys [on-output on-complete on-wait-input]}]
    (or busy
        (do (future
              (doseq [[k t] outs] (on-output k t))
              (case result
                ::never nil
                ::input (on-wait-input true)
                (on-complete result)))
            nil))))

(defn- await-thrown [opts]
  (try (reg/await-eval! opts) nil (catch :default e e)))

(deftest eval-failures-test
  (let [stops (atom 0)
        stop! #(swap! stops inc)]
    (is (= "the REPL is busy" (ex-message (await-thrown {:fire (fake-fire nil :busy "; busy") :interrupt! stop!})))))
  (let [stops (atom 0)
        stop! #(swap! stops inc)]
    (is (= "the eval took longer than 50 ms and was stopped"
           (ex-message (await-thrown {:fire (fake-fire ::never) :interrupt! stop! :timeout-ms 50}))))
    (is (= 1 @stops)))
  (let [stops (atom 0)
        stop! #(swap! stops inc)]
    (is (= "the eval asked for console input, so it was stopped"
           (ex-message (await-thrown {:fire (fake-fire ::input) :interrupt! stop!}))))
    (is (= 1 @stops)))
  (is (= {:value "42" :out "side\n" :err "warn\n"}
         (reg/await-eval! {:fire (fake-fire {:status :ok :printed "42"} :outs [[:out "side\n"] [:err "warn\n"] [:system "; note\n"]])
                           :interrupt! (fn [])})))
  (is (= {:value nil :out "" :err "" :error "Error: boom"}
         (reg/await-eval! {:fire (fake-fire {:status :error :error-text "Error: boom"}) :interrupt! (fn [])})))
  (is (= {:value nil :out "" :err "" :error "the eval was interrupted"}
         (reg/await-eval! {:fire (fake-fire {:status :interrupted}) :interrupt! (fn [])}))))

(deftest eval-other-dialect-test
  (is (false? (reg/quiet-capable? (reify ev/Evaluator))))
  (is (true? (reg/quiet-capable? (reify ev/Evaluator ev/QuietEval (quiet-eval? [_] true))))))

(deftest run-command-ctx-eval-test
  (let [seen (promise)
        r (run-plugin "p5b-ctx" #{:eval} (fn [ctx] (deliver seen ctx)))]
    (reset! reg/effects (assoc (recording-effects (atom [])) :eval! (fn [& _]) :repl! (fn [& _])))
    (go! r "p5b-ctx")
    (is (= #{:eval! :repl!} (set (keys (::p/impl (deref seen 2000 nil))))))))
