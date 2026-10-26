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

(deftest run-hooks-single-flight-test
  (let [gate (promise)
        reports (atom [])
        runs (atom 0)
        r (with (hooked "a" #{} {:after-save (fn [_ e] (when (= 1 (swap! runs inc)) @gate) e)}))]
    (with-live r {:report! #(swap! reports conj %)}
      (fn []
        (let [first-run (hooks/run-hooks! {:point :after-save :tab 1 :event {:path "/f"}})]
          (is (future? first-run))
          (is (nil? (hooks/run-hooks! {:point :after-save :tab 1 :event {:path "/g"}})))
          (is (= ["the :after-save hooks are still running from the last time, so they were skipped this time"]
                 @reports))
          (deliver gate true)
          (deref first-run 2000 nil)
          (let [third (hooks/run-hooks! {:point :after-save :tab 1 :event {:path "/h"}})]
            (is (future? third))
            (deref third 2000 nil))
          (is (= 2 @runs)))))))

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
