(ns jonquil.plugin-hooks-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.eval :as eval]
            [jonquil.plugin :as p]
            [jonquil.plugin-hooks :as hooks]
            [jonquil.plugin-registry :as reg]))

(defn- hooked
  "A definition for id declaring effects, with hook-map as its :hooks."
  [id effects hook-map]
  {:id (keyword id) :doc "d" :effects effects :hooks hook-map})

(defn- with [& defs]
  (reduce (fn [r d] (reg/install r (:id d) {:definition d :source "" :file "" :layer :user}))
          (reg/empty-registry) defs))

(defn- with-live
  "Runs (f) with the live registry and effects set to r and fx, restoring
  both after."
  [r fx f]
  (let [old-live @reg/live
        old-fx @reg/effects]
    (reset! reg/live r)
    (reset! reg/effects fx)
    (try (f)
         (finally (reset! reg/live old-live)
                  (reset! reg/effects old-fx)))))

(defn- await-count [a n]
  (loop [i 0]
    (when (and (< (count @a) n) (< i 200)) (Thread/sleep 10) (recur (inc i))))
  @a)

(deftest effect-ctx-test
  (is (= {::p/effects #{:console} ::p/impl {:print! :p :status! :s} ::p/tab 7}
         (reg/effect-ctx {:print! :p :status! :s :buffer-text :b :report! :r} #{:console} 7))))

(deftest hooks-in-id-order-test
  (let [f (fn [_ e] e)
        r (with (hooked "b" #{} {:after-save f})
                (hooked "a" #{:console} {:after-save f})
                (hooked "c" #{} {:on-error f}))]
    (is (= [:a :b] (map :plugin (hooks/hooks r :after-save))))
    (is (= #{:console} (:effects (first (hooks/hooks r :after-save)))))
    (is (= [:c] (map :plugin (hooks/hooks r :on-error))))
    (is (= [] (hooks/hooks r :after-run)))))

(defn- chain [r point event reports]
  (hooks/run-chain {:point point :hooks (hooks/hooks r point)
                    :ctx-for (fn [effects] (reg/effect-ctx {:print! (fn [s] (swap! reports conj [:print s]))}
                                                           effects 7))
                    :event event :report! #(swap! reports conj %)}))

(deftest run-chain-threads-the-event-test
  (let [r (with (hooked "a" #{} {:after-save (fn [_ e] (assoc e :x 1))})
                (hooked "b" #{} {:after-save (fn [_ e] (assoc e :y (inc (:x e))))})
                (hooked "c" #{} {:after-save (fn [_ _] nil)}))]
    (is (= {:path "/f" :x 1 :y 2} (chain r :after-save {:path "/f"} (atom []))))))

(deftest run-chain-throw-reported-test
  (let [reports (atom [])
        r (with (hooked "a" #{} {:after-save (fn [_ _] (throw (ex-info "boom" {})))})
                (hooked "b" #{} {:after-save (fn [_ e] (assoc e :seen true))}))]
    (is (= {:path "/f" :seen true} (chain r :after-save {:path "/f"} reports)))
    (is (= ["plugin a: :after-save hook: boom"] @reports))))

(deftest run-chain-ctx-holds-own-effects-test
  (let [reports (atom [])
        say (fn [ctx e] (p/print! ctx "hi") e)
        r (with (hooked "a" #{} {:after-save say})
                (hooked "b" #{:console} {:after-save say}))]
    (chain r :after-save {:path "/f"} reports)
    (is (= ["plugin a: :after-save hook: this plugin didn't declare :console" [:print "hi"]]
           @reports))))

(deftest error-location-test
  (is (= {:line 3 :column 5} (hooks/error-location "Error [x]: y\nLocation: line 3, col 5")))
  (is (= {:line 7} (hooks/error-location "Error [x]: y\nLocation: line 7")))
  (is (= {} (hooks/error-location "Error: no place")))
  (is (= {} (hooks/error-location nil))))

(deftest run-event-test
  (is (= {:kind :run :status :ok :value "3" :error nil} (hooks/run-event :run {:status :ok :printed "3"})))
  (is (= {:kind :form :status :error :value nil :error "Error: x"}
         (hooks/run-event :form {:status :error :error-text "Error: x"})))
  (is (= {:kind :selection :status :interrupted :value nil :error nil}
         (hooks/run-event :selection {:status :interrupted})))
  (is (= {:kind :run :status :notice :value nil :error "not running"}
         (hooks/run-event :run {:status :notice :text "not running"}))))

(deftest error-event-test
  (is (nil? (hooks/error-event :run {:status :ok :printed "1"})))
  (let [e (ex-info "bad" {:clojure.error/line 3 :clojure.error/column 5})]
    (is (= {:source :repl :text (eval/format-error e) :line 3 :column 5}
           (hooks/error-event :repl {:status :error :error e}))))
  (is (= {:source :form :text "Error: x"} (hooks/error-event :form {:status :error :error-text "Error: x"}))))

(deftest run-hooks-none-installed-test
  (with-live (reg/empty-registry) {}
    #(is (nil? (hooks/run-hooks! {:point :after-save :tab 1 :event {:path "/f"}})))))

(deftest run-hooks-queues-while-busy-test
  ;; Save All saves each tab in one GTK callback, so a second event comes
  ;; while the first chain still waits on the GTK thread: it runs after,
  ;; on the same worker, and nothing is reported
  (let [gate (promise)
        reports (atom [])
        seen (atom [])
        r (with (hooked "a" #{} {:after-save (fn [_ e] (when (empty? @seen) @gate) (swap! seen conj (:path e)) e)}))]
    (with-live r {:report! #(swap! reports conj %)}
      (fn []
        (let [first-run (hooks/run-hooks! {:point :after-save :tab 1 :event {:path "/f"}})]
          (is (future? first-run))
          (is (nil? (hooks/run-hooks! {:point :after-save :tab 1 :event {:path "/g"}})))
          (deliver gate true)
          (deref first-run 2000 nil)
          (is (= ["/f" "/g"] @seen))
          (is (= [] @reports))
          (let [later (hooks/run-hooks! {:point :after-save :tab 1 :event {:path "/h"}})]
            (is (future? later) "a drained point starts afresh")
            (deref later 2000 nil))
          (is (= ["/f" "/g" "/h"] @seen)))))))

(deftest run-hooks-skips-past-the-queue-bound-test
  ;; a hook that never returns holds at most the bound's worth of events
  (let [gate (promise)
        reports (atom [])
        runs (atom 0)
        r (with (hooked "a" #{} {:after-save (fn [_ e] (when (= 1 (swap! runs inc)) @gate) e)}))]
    (with-live r {:report! #(swap! reports conj %)}
      (fn []
        (let [first-run (hooks/run-hooks! {:point :after-save :tab 1 :event {:path "/0"}})]
          (dotimes [i 17] (hooks/run-hooks! {:point :after-save :tab 1 :event {:path (str "/" (inc i))}}))
          (is (= ["the :after-save hooks are still busy with 16 earlier events, so this one was skipped"]
                 @reports))
          (deliver gate true)
          (deref first-run 2000 nil)
          (is (= 17 @runs)))))))

(deftest run-hooks-ctx-test
  (let [printed (atom [])
        r (with (hooked "a" #{:console} {:after-save (fn [ctx e] (p/print! ctx (str (::p/tab ctx) " " (:path e))) e)}))]
    (with-live r {:print! #(swap! printed conj %)}
      (fn []
        (deref (hooks/run-hooks! {:point :after-save :tab 7 :event {:path "/f"}}) 2000 nil)
        (is (= ["7 /f"] @printed))))))

(deftest after-eval-fires-the-right-points-test
  (let [calls (atom [])
        r (with (hooked "a" #{} {:on-error (fn [_ e] (swap! calls conj [:on-error (:source e)]) e)
                                 :after-run (fn [_ e] (swap! calls conj [:after-run (:kind e)]) e)}))
        go (fn [source status]
             (reset! calls [])
             (hooks/after-eval! {:source source :tab 1 :result {:status status :error-text "E" :printed "1"}}))]
    (with-live r {}
      (fn []
        (go :run :error)
        (is (= #{[:on-error :run] [:after-run :run]} (set (await-count calls 2))))
        (go :repl :error)
        (is (= [[:on-error :repl]] (await-count calls 1)))
        (go :form :ok)
        (is (= [[:after-run :form]] (await-count calls 1)))
        (go :repl :ok)
        (Thread/sleep 100)
        (is (= [] @calls))))))

(deftest after-eval-reports-a-throw-test
  ;; an :error result with neither :error nor :error-text: building the
  ;; :on-error event throws; that's reported, never thrown at the eval's
  ;; completion; each point is tried on its own
  (let [reports (atom [])
        r (with (hooked "a" #{} {:on-error (fn [_ e] e) :after-run (fn [_ e] e)}))]
    (with-live r {:report! #(swap! reports conj %)}
      (fn []
        (is (nil? (hooks/after-eval! {:source :run :tab 1 :result {:status :error}})))
        (is (= 2 (count @reports)))
        (is (re-find #"^the :on-error hooks didn't start: " (first @reports)))
        (is (re-find #"^the :after-run hooks didn't start: " (second @reports)))))))

;; --- :before-run (P6b) -------------------------------------------------------

(deftest refused-ctx-test
  (let [ctx {::p/effects #{:console} ::p/impl {:print! identity} ::p/refused "no effects here"}]
    (is (thrown-with-msg? Exception #"^no effects here$" (p/print! ctx "x")))))

(defn- await-before-free
  "Waits (up to 2 s) until no :before-run chain is running, so a stuck one
  a test released doesn't leak into the next test."
  []
  (loop [i 0]
    (when (and @@#'hooks/before-busy (< i 200)) (Thread/sleep 10) (recur (inc i)))))

(defn- before [kind text path]
  (hooks/before-run! {:kind kind :text text :path path :tab 1}))

(deftest before-run-none-installed-test
  (with-live (reg/empty-registry) {}
    #(is (nil? (before :run "(+ 1 2)" nil)))))

(deftest before-run-goes-ahead-test
  (let [seen (atom nil)
        r (with (hooked "a" #{} {:before-run (fn [_ e] (assoc e :seen true))})
                (hooked "b" #{} {:before-run (fn [_ e] (reset! seen e) e)}))]
    (with-live r {}
      (fn []
        (is (nil? (before :run "(+ 1 2)" "/f.clj")))
        (is (= {:kind :run :text "(+ 1 2)" :path "/f.clj" :seen true} @seen))))))

(deftest before-run-abort-first-wins-test
  (let [called (atom false)
        r (with (hooked "a" #{} {:before-run (fn [_ _] {:abort "not today"})})
                (hooked "b" #{} {:before-run (fn [_ e] (reset! called true) e)}))]
    (with-live r {}
      (fn []
        (is (= "plugin a stopped this Run: not today" (before :form "(x)" nil)))
        (is (false? @called))))))

(deftest before-run-bad-abort-test
  (let [reports (atom [])
        r (with (hooked "a" #{} {:before-run (fn [_ _] {:abort :nope})})
                (hooked "b" #{} {:before-run (fn [_ _] {:abort ""})}))]
    (with-live r {:report! #(swap! reports conj %)}
      (fn []
        (is (nil? (before :run "(x)" nil)))
        (is (= ["plugin a: :before-run's :abort must be a sentence, so the Run went ahead"
                "plugin b: :before-run's :abort must be a sentence, so the Run went ahead"]
               @reports))))))

(deftest before-run-throw-reported-test
  (let [reports (atom [])
        r (with (hooked "a" #{} {:before-run (fn [_ _] (throw (ex-info "boom" {})))})
                (hooked "b" #{} {:before-run (fn [_ _] {:abort "stop"})}))]
    (with-live r {:report! #(swap! reports conj %)}
      (fn []
        (is (= "plugin b stopped this Run: stop" (before :run "(x)" nil)))
        (is (= ["plugin a: :before-run hook: boom"] @reports))))))

(deftest before-run-effects-refused-test
  (let [reports (atom [])
        printed (atom [])
        r (with (hooked "a" #{:console} {:before-run (fn [ctx e] (p/print! ctx "hi") e)}))]
    (with-live r {:print! #(swap! printed conj %) :report! #(swap! reports conj %)}
      (fn []
        (is (nil? (before :run "(x)" nil)))
        (is (= [] @printed))
        (is (= ["plugin a: :before-run hook: :before-run hooks can't use effects, since the Run waits for them"]
               @reports))))))

(deftest before-run-overrun-test
  (let [gate (promise)
        reports (atom [])
        r (with (hooked "a" #{} {:before-run (fn [_ e] @gate e)}))]
    (with-live r {:report! #(swap! reports conj %)}
      (fn []
        (try
          (let [t0 (System/currentTimeMillis)]
            (is (nil? (before :run "(x)" nil)))
            (is (< (- (System/currentTimeMillis) t0) 1000)))
          ;; the plugin is named once its hook has started, which under
          ;; load may be after the budget ran out
          (is (= 1 (count @reports)))
          (is (re-matches #"the :before-run hooks took longer than 100 ms( \(plugin a was running\))?, so the Run went ahead"
                          (first @reports)))
          (finally (deliver gate true) (await-before-free)))))))

(deftest before-run-skips-while-overrun-test
  (let [gate (promise)
        reports (atom [])
        calls (atom 0)
        r (with (hooked "a" #{} {:before-run (fn [_ e] (when (= 1 (swap! calls inc)) @gate) e)}))]
    (with-live r {:report! #(swap! reports conj %)}
      (fn []
        (await-before-free)
        (try
          (is (nil? (before :run "(x)" nil)))
          ;; under load the chain's future may start after the budget ran out
          (loop [i 0] (when (and (zero? @calls) (< i 200)) (Thread/sleep 10) (recur (inc i))))
          (is (nil? (before :run "(x)" nil)))
          (is (= 1 @calls))
          (is (= "the :before-run hooks are still running from an earlier Run, so this Run went ahead without them"
                 (second @reports)))
          (deliver gate true)
          ;; the stuck chain returns and frees the hooks: a later Run
          ;; (each one skipped until then) runs the hook again
          (loop [i 0]
            (before :run "(x)" nil)
            (when (and (< @calls 2) (< i 200)) (Thread/sleep 10) (recur (inc i))))
          (is (= 2 @calls) "a later Run runs the hook again")
          (finally (deliver gate true) (await-before-free)))))))

(deftest before-run-odd-return-reported-test
  ;; a sorted map with string keys throws on (contains? m :abort): that's
  ;; reported as the plugin's, never thrown onto the GTK thread
  (let [reports (atom [])
        r (with (hooked "a" #{} {:before-run (fn [_ _] (sorted-map "x" 1))})
                (hooked "b" #{} {:before-run (fn [_ _] {:abort "stop"})}))]
    (with-live r {:report! #(swap! reports conj %)}
      (fn []
        (is (= "plugin b stopped this Run: stop" (before :run "(x)" nil)))
        (is (= 1 (count @reports)))
        (is (re-find #"^plugin a: :before-run hook: " (str (first @reports))))))))

(deftest before-run-chain-failure-reported-test
  ;; anything that fails the chain itself (here report! throwing the
  ;; first time) is reported and the Run goes ahead
  (let [reports (atom [])
        r (with (hooked "a" #{} {:before-run (fn [_ _] (throw (ex-info "boom" {})))}))]
    (with-live r {:report! (fn [line]
                             (swap! reports conj line)
                             (when (= 1 (count @reports)) (throw (ex-info "report failed" {}))))}
      (fn []
        (is (nil? (before :run "(x)" nil)))
        (is (= 2 (count @reports)))
        (is (re-find #"^the :before-run hooks failed: .*report failed.*, so the Run went ahead$"
                     (str (second @reports))))))))
