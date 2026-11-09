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
  ;; throw names the plugin
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
  ;; a turn that timed out must not apply later, or a plugin
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
  (is (= "1" (:value (reg/await-eval! {:fire (fake-fire {:status :ok :printed "1"}) :interrupt! (fn []) :timeout-ms nil})))
      "a nil :timeout-ms (no opts from the plugin) is the default")
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

(deftest await-eval-review-fixes-test
  ;; a dialect's :notice result (no runtime, app asleep) is its
  ;; text, not an NPE from error-text
  (is (= {:value nil :out "" :err "" :error "; No runtime is connected."}
         (reg/await-eval! {:fire (fake-fire {:status :notice :text "; No runtime is connected."}) :interrupt! (fn [])})))
  ;; Jonquil's own lines (a restart, auto-require) reach
  ;; :on-notice, the code's :out/:err don't
  (let [notices (atom [])
        r (reg/await-eval! {:fire (fake-fire {:status :ok :printed "1"}
                                             :outs [[:out "o"] [:system "; Stop restarted the Jolt runtime\n"] [:error "; bad\n"]])
                            :interrupt! (fn [])
                            :on-notice (fn [k t] (swap! notices conj [k t]))})]
    (is (= "o" (:out r)))
    (is (= [[:system "; Stop restarted the Jolt runtime\n"] [:error "; bad\n"]] @notices)))
  ;; a :timeout-ms that isn't a positive number throws before anything starts
  (let [fired (atom 0)]
    (is (= ":timeout-ms must be a positive number of milliseconds"
           (ex-message (await-thrown {:fire (fn [_] (swap! fired inc) nil) :interrupt! (fn []) :timeout-ms "500"}))))
    (is (zero? @fired))))

;; --- a plugin's MCP tools -------------------------------------

(defn- tool-plugin
  "A registry holding plugin :fmt declaring effects, with tool rows."
  [effects & rows]
  (with {:id :fmt :doc "d" :effects effects :mcp-tools (vec rows)}))

(def ^:private echo-row
  {:name "echo" :doc "Echo it." :args {"text" {:type "string" :doc "What to echo." :required? true}}
   :run (fn [_ args] (get args "text"))})

(defn- call-tool [r served args & [opts]]
  ((:call (some #(when (= served (:name %)) %) (reg/mcp-tools r (or opts {})))) args))

(deftest mcp-tools-defs-test
  (let [ts (reg/mcp-tools (tool-plugin #{} echo-row {:name "now" :doc "The time." :run (fn [_ _] "t")}))]
    (is (= ["plugin_fmt_echo" "plugin_fmt_now"] (mapv :name ts)))
    (is (= [:fmt :fmt] (mapv :plugin ts)))
    (is (= {"name" "plugin_fmt_echo" "description" "Echo it."
            "inputSchema" {"type" "object"
                           "properties" {"text" {"type" "string" "description" "What to echo."}}
                           "required" ["text"]}}
           (:def (first ts))))
    (is (= {"type" "object" "properties" {} "required" []} (get-in (second ts) [:def "inputSchema"])))
    (is (= [] (reg/mcp-tools (reg/empty-registry))))))

(deftest mcp-tools-call-test
  (let [r (tool-plugin #{} echo-row)]
    (is (= {:text "hi" :error? false} (call-tool r "plugin_fmt_echo" {"text" "hi"})))
    (is (= {:text "hi" :error? false} (call-tool r "plugin_fmt_echo" {"text" "hi" "extra" 1}))
        "an argument not in :args is passed through untouched")
    (is (= {:text "Missing required argument: text" :error? true} (call-tool r "plugin_fmt_echo" {})))
    (is (= {:text "Argument text must be a string." :error? true} (call-tool r "plugin_fmt_echo" {"text" 3}))))
  (let [r (tool-plugin #{} (assoc echo-row :run (fn [_ _] {:text "x" :error? true})))]
    (is (= {:text "x" :error? true} (call-tool r "plugin_fmt_echo" {"text" "a"}))))
  (let [r (tool-plugin #{} (assoc echo-row :args {"n" {:type "integer" :doc "n"} "b" {:type "boolean" :doc "b"}}
                                  :run (fn [_ a] (pr-str [(get a "n") (get a "b")]))))]
    (is (= "[2 true]" (:text (call-tool r "plugin_fmt_echo" {"n" 2 "b" true}))))
    (is (= {:text "Argument n must be an integer." :error? true} (call-tool r "plugin_fmt_echo" {"n" 2.5})))
    (is (= {:text "Argument b must be a boolean." :error? true} (call-tool r "plugin_fmt_echo" {"b" "yes"})))))

(deftest mcp-tools-bad-answers-test
  (is (= {:text "plugin fmt: plugin_fmt_echo answered 42, not text" :error? true}
         (call-tool (tool-plugin #{} (assoc echo-row :run (fn [_ _] 42))) "plugin_fmt_echo" {"text" "a"})))
  (is (= {:text "plugin fmt: boom" :error? true}
         (call-tool (tool-plugin #{} (assoc echo-row :run (fn [_ _] (throw (ex-info "boom" {})))))
                    "plugin_fmt_echo" {"text" "a"}))))

(deftest mcp-tools-deadline-test
  (let [gate (promise)
        r (tool-plugin #{} (assoc echo-row :run (fn [_ _] @gate "late")))
        opts {:deadline-ms 100}]
    (is (= {:text "plugin fmt: plugin_fmt_echo didn't answer in 0 s; it may still be running" :error? true}
           (call-tool r "plugin_fmt_echo" {"text" "a"} opts)))
    (is (= {:text "plugin fmt: plugin_fmt_echo is still running" :error? true}
           (call-tool r "plugin_fmt_echo" {"text" "a"} opts)))
    (deliver gate true)
    (let [answer (loop [i 0]
                   (let [a (call-tool r "plugin_fmt_echo" {"text" "a"} opts)]
                     (if (and (str/ends-with? (:text a) "is still running") (< i 100))
                       (do (Thread/sleep 20) (recur (inc i)))
                       a)))]
      (is (= {:text "late" :error? false} answer)))))

(deftest mcp-tools-effects-test
  (let [calls (atom [])
        before @reg/effects]
    (try
      (reset! reg/effects (assoc (recording-effects calls) :active-tab-turn (constantly 9)))
      (let [r (tool-plugin #{:console :buffer}
                           (assoc echo-row :run (fn [ctx a] (p/print! ctx (get a "text")) (str (p/buffer-text ctx)))))]
        (is (= {:text "t" :error? false} (call-tool r "plugin_fmt_echo" {"text" "hi"})))
        (is (= [[:print! "hi"] [:buffer-text 9]] @calls) "the tab is what :active-tab-turn answers"))
      (let [r (tool-plugin #{} (assoc echo-row :run (fn [ctx _] (p/replace-text! ctx "gone") "done")))
            res (call-tool r "plugin_fmt_echo" {"text" "a"})]
        (is (:error? res))
        (is (str/starts-with? (:text res) "plugin fmt: this plugin didn't declare :buffer"))
        (is (not-any? #(= :replace-text! (first %)) @calls)))
      (finally (reset! reg/effects before)))))

(deftest mcp-tools-lazy-answer-is-under-the-deadline-test
  ;; realizing a lazy answer runs plugin code too, so it
  ;; happens under the deadline, never on the serve thread
  (let [r (tool-plugin #{} (assoc echo-row :name "lazy" :run (fn [_ _] (lazy-seq (Thread/sleep 3000) [1]))))
        t0 (System/currentTimeMillis)
        a (call-tool r "plugin_fmt_lazy" {"text" "a"} {:deadline-ms 200})
        ms (- (System/currentTimeMillis) t0)]
    (is (< ms 1500) (str ms " ms"))
    (is (= {:text "plugin fmt: plugin_fmt_lazy didn't answer in 0 s; it may still be running" :error? true} a))))

(deftest mcp-tools-without-buffer-dont-wait-on-the-window-test
  ;; a tool that doesn't declare :buffer never takes a GTK
  ;; turn, so a busy window can't fail it
  (let [before @reg/effects
        turns (atom 0)]
    (try
      (reset! reg/effects {:active-tab-turn (fn [] (swap! turns inc) (throw (ex-info "The window did not respond." {})))})
      (is (= {:text "hi" :error? false} (call-tool (tool-plugin #{} echo-row) "plugin_fmt_echo" {"text" "hi"})))
      (is (zero? @turns))
      (finally (reset! reg/effects before)))))

(deftest mcp-tools-window-failure-frees-the-tool-test
  ;; a :buffer tool whose tab turn throws never started, so the tool is
  ;; free for the next call and the error is the throwable's message
  (let [before @reg/effects
        r (tool-plugin #{:buffer} echo-row)]
    (try
      (reset! reg/effects {:active-tab-turn (fn [] (throw (ex-info "The window did not respond." {})))})
      (is (= {:text "plugin fmt: The window did not respond." :error? true}
              (call-tool r "plugin_fmt_echo" {"text" "a"})))
      (is (= {:text "plugin fmt: The window did not respond." :error? true}
              (call-tool r "plugin_fmt_echo" {"text" "a"})) "not stuck as still running")
      (finally (reset! reg/effects before)))))

;; --- watch-tools! --------------------------------------------

(deftest watch-tools-fires-on-tool-change
  (let [a (atom (reg/empty-registry))
        calls (atom 0)
        unwatch (reg/watch-tools! a #(swap! calls inc))]
    (reset! a (tool-plugin #{} echo-row))
    (is (= 1 @calls))
    (reset! a (tool-plugin #{} (assoc echo-row :doc "Echo it back.")))
    (is (= 2 @calls))
    (reset! a (reg/empty-registry))
    (is (= 3 @calls))
    (unwatch)))

(deftest watch-tools-ignores-other-changes
  (let [a (atom (tool-plugin #{} echo-row))
        calls (atom 0)
        unwatch (reg/watch-tools! a #(swap! calls inc))]
    (swap! a reg/install :wrap {:definition (plugin "wrap" identity) :source "" :file "" :layer :user})
    (reset! a (tool-plugin #{} echo-row))
    (is (= 0 @calls))
    (unwatch)))

(deftest unwatch-stops-calls
  (let [a (atom (reg/empty-registry))
        calls (atom 0)
        unwatch (reg/watch-tools! a #(swap! calls inc))]
    (unwatch)
    (reset! a (tool-plugin #{} echo-row))
    (is (= 0 @calls))))

(deftest throwing-notify-leaves-the-swap
  (let [a (atom (reg/empty-registry))
        r (tool-plugin #{} echo-row)
        unwatch (reg/watch-tools! a (fn [] (throw (ex-info "boom" {}))))]
    (reset! a r)
    (is (= r @a))
    (unwatch)))
