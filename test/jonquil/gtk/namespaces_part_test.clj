(ns jonquil.gtk.namespaces-part-test
  "The Namespaces panel's part (jonquil.gtk.parts.namespaces) with the
  pane, the query and the window's jump and inspect fns stubbed: what each
  answer does, stepping in and out, refreshing after an eval, and where
  Return and Shift+Return go."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.gtk.callbacks]
            [jonquil.gtk.namespaces-pane :as pane]
            [jonquil.gtk.parts.namespaces :as part]))

(def ^:private refresh! @#'part/refresh!)
(def ^:private activate! @#'part/activate!)
(def ^:private step-back! @#'part/step-back!)
(def ^:private after-eval! @#'part/after-eval!)

(def ^:private top-value
  {:namespaces [{:name "demo.core" :file "demo/core.clj" :vars 2}
                {:name "clojure.core" :file "clojure/core.clj" :vars 640}]
   :dropped 0})

(def ^:private vars-value
  {:found? true :dropped 0
   :vars [{:name "greet" :arglists ["[n]"] :doc "Hi." :file "demo/core.clj" :line 3 :bound? true}
          {:name "w" :arglists ["[& b]"] :macro? true :bound? true}]})

(defn- ok [v] {:done {:result {:status :ok :printed (pr-str (pr-str v))}}})

(defn- ctx
  "A part ctx over log. answers is an atom of notify maps the query hands
  out in order (none left: the query never answers); every pane, status,
  jump and inspect call is logged."
  [log answers & {:keys [dialect showing? busy?] :or {dialect :jolt showing? true busy? false}}]
  {:sel* (atom {:profile {:id dialect :name "Babashka"} :project-dir "/p"})
   :state* (atom (part/initial-state))
   :query! (fn [code notify]
             (swap! log conj [:query (if (str/includes? code "all-ns") :namespaces :vars)])
             (when-let [m (first @answers)] (swap! answers rest) (notify m)))
   :show! (fn [view] (swap! log conj [:show (mapv :name (:rows view)) (:crumb view) (:footer view) (:selected view)]))
   :set-filter! (fn [s] (swap! log conj [:filter s]))
   :focus-list! (fn [] nil)
   :showing? (fn [] showing?)
   :evaluating? (fn [] busy?)
   :schedule! (fn [_ms f] (f))
   :own? (fn [] (fn [f] (str/starts-with? f "demo/")))
   :set-status! (fn [s] (swap! log conj [:status s]))
   :inspect-text! (fn [t] (swap! log conj [:inspect t]))
   :open-var! (fn [m] (swap! log conj [:open m]))})

(defmacro ^:private with-gui [& body]
  `(with-redefs [jonquil.gtk.callbacks/on-gui! (fn [f#] (f#))] ~@body))

(defn- shows [log] (filter #(= :show (first %)) @log))

(deftest unsupported-dialect-footer
  (with-gui
    (let [log (atom []) c (ctx log (atom []) :dialect :bb)]
      (refresh! c)
      (is (= [[:show [] nil "; Namespaces works with Jolt and JVM Clojure. This project is Babashka." nil]] @log)))))

(deftest opens-with-namespaces
  (with-gui
    (let [log (atom []) c (ctx log (atom [(ok top-value)]))]
      (refresh! c)
      (is (= [:query :namespaces] (first @log)))
      (is (= [:show ["demo.core" nil] nil "1 own namespace, 1 from libraries" 0] (last @log))))))

(deftest step-in-and-back
  (with-gui
    (let [log (atom []) c (ctx log (atom [(ok top-value) (ok vars-value) (ok top-value)]))]
      (refresh! c)
      (activate! c {:kind :ns :key "demo.core" :name "demo.core"} false)
      (is (some #{[:filter ""]} @log))
      (is (= [:show ["greet" "w"] "All namespaces › demo.core · 2 vars"
              "demo.core/greet [n]\nHi. · demo/core.clj:3 · Return jumps, Shift+Return inspects" 0]
             (last (shows log))))
      (step-back! c)
      (is (= [:show ["demo.core" nil] nil "1 own namespace, 1 from libraries" 0] (last (shows log))))
      (is (= :top (:level @(:state* c)))))))

(deftest back-from-a-library-opens-the-group
  (with-gui
    (let [log (atom []) c (ctx log (atom [(ok top-value) (ok vars-value) (ok top-value)]))]
      (refresh! c)
      (activate! c {:kind :ns :key "clojure.core" :name "clojure.core"} false)
      (step-back! c)
      (is (= [:show ["demo.core" nil "clojure.core"] nil "1 own namespace, 1 from libraries" 2] (last (shows log)))
          "the folded Libraries group opens so the namespace can be selected"))))

(deftest group-row-toggles
  (with-gui
    (let [log (atom []) c (ctx log (atom [(ok top-value)]))]
      (refresh! c)
      (activate! c {:kind :group} false)
      (is (= ["demo.core" nil "clojure.core"] (second (last (shows log))))))))

(deftest vanished-namespace-steps-back
  (with-gui
    (let [log (atom [])
          after {:namespaces [{:name "clojure.core" :file "clojure/core.clj" :vars 640}] :dropped 0}
          c (ctx log (atom [(ok top-value) (ok {:found? false :vars [] :dropped 0}) (ok after)]))]
      (refresh! c)
      (activate! c {:kind :ns :key "demo.core" :name "demo.core"} false)
      (is (= :top (:level @(:state* c))))
      (is (= [:query :namespaces] (last (filter #(= :query (first %)) @log))) "the top level is asked again")
      (is (= [:show [nil] nil "demo.core is no longer loaded." nil] (last (shows log)))
          "the gone namespace isn't listed, and the sentence outlives the fresh answer")
      (activate! c {:kind :group} false)
      (is (= "0 own namespaces, 1 from libraries" (nth (last (shows log)) 3)) "the next action clears it"))))

(deftest after-eval-schedules-once
  (with-gui
    (let [log (atom []) fs (atom [])
          c (assoc (ctx log (atom [(ok top-value)])) :schedule! (fn [_ms f] (swap! fs conj f)))]
      (after-eval! c)
      (after-eval! c)
      (is (= 1 (count @fs)) "a burst of evals schedules one refresh")
      ((first @fs))
      (after-eval! c)
      (is (= 2 (count @fs)) "after it runs, the next eval schedules again"))
    (let [fs (atom [])
          c (assoc (ctx (atom []) (atom []) :showing? false) :schedule! (fn [_ms f] (swap! fs conj f)))]
      (after-eval! c)
      (is (= [] @fs) "hidden: nothing is scheduled at all"))))

(deftest stale-vars-answer-dropped
  (with-gui
    (let [log (atom []) notify* (atom nil)
          c (assoc (ctx log (atom [(ok top-value)]))
                   :query! (fn [code notify]
                             (if (str/includes? code "all-ns") (notify (ok top-value)) (reset! notify* notify))))]
      (refresh! c)
      (activate! c {:kind :ns :key "demo.core" :name "demo.core"} false)
      (step-back! c)
      (let [n (count (shows log))]
        (@notify* (ok vars-value))
        (is (= n (count (shows log))) "the late vars answer shows nothing")
        (is (= :top (:level @(:state* c))))))))

(deftest busy-keeps-rows
  (with-gui
    (let [log (atom []) c (ctx log (atom [(ok top-value) {:busy "; Busy."}]))]
      (refresh! c)
      (refresh! c)
      (is (= [:show ["demo.core" nil] nil "; Namespaces: the runtime is busy. Try again when it's done." 0]
             (last (shows log)))))))

(deftest after-eval-only-while-showing
  (with-gui
    (testing "hidden: no query"
      (let [log (atom []) c (ctx log (atom [(ok top-value)]) :showing? false)]
        (after-eval! c)
        (is (= [] @log))))
    (testing "busy again when the delayed refresh runs: no query"
      (let [log (atom []) c (ctx log (atom [(ok top-value)]) :busy? true)]
        (after-eval! c)
        (is (= [] @log))))
    (testing "showing and idle: refreshes"
      (let [log (atom []) c (ctx log (atom [(ok top-value)]))]
        (after-eval! c)
        (is (= [:query :namespaces] (first @log)))))))

(deftest return-on-var-jumps
  (with-gui
    (let [log (atom []) c (ctx log (atom [(ok top-value) (ok vars-value)]))]
      (refresh! c)
      (activate! c {:kind :ns :key "demo.core" :name "demo.core"} false)
      (activate! c {:kind :var :name "greet" :file "demo/core.clj" :line 3} false)
      (is (= [:open {:ns "demo.core" :name "greet" :file "demo/core.clj" :line 3}] (last @log)))
      (activate! c {:kind :var :name "x" :file nil :line nil} false)
      (is (= [:open {:ns "demo.core" :name "x" :file nil :line nil}] (last @log))))))

(deftest shift-return-inspects
  (with-gui
    (let [log (atom []) c (ctx log (atom [(ok top-value) (ok vars-value)]))]
      (refresh! c)
      (activate! c {:kind :ns :key "demo.core" :name "demo.core"} false)
      (activate! c {:kind :var :name "visit!" :bound? true} true)
      (is (= [:inspect "(deref (var demo.core/visit!))"] (last @log)))
      (activate! c {:kind :var :name "w" :macro? true :bound? true} true)
      (is (= [:status "demo.core/w is a macro, with no value to inspect."] (last @log)))
      (testing "a more row does nothing"
        (activate! c {:kind :more} true)
        (is (= [:status "demo.core/w is a macro, with no value to inspect."] (last @log)))))))

(deftest panel-open-shows-the-panel
  ;; the right slot's panel key calls :open! for a part's panel
  (with-gui
    (let [log (atom [])
          wctx {:cv {:after-eval* (atom {})} :right ::right :ev* (atom nil)
                :sel* (atom {:profile {:id :bb :name "Babashka"} :project-dir "/p"})
                :show-right-panel! (fn [r k] (swap! log conj [:show-panel r k]))
                :hide-right-panel! (fn [& _]) :showing-panel (fn [_] nil) :active-tab (fn [] nil)
                :set-status! (fn [_]) :mcp-query! (fn [& _]) :inspect-text! (fn [_])}]
      (with-redefs [pane/make-pane (fn [] {:root ::root})
                    pane/set-handlers! (fn [& _])
                    pane/show! (fn [& _])
                    pane/focus-list! (fn [_])
                    pane/set-filter-text! (fn [& _])]
        (let [c ((deref #'part/install) wctx)]
          ((get-in c [:panels :namespaces :open!]))
          (is (= [[:show-panel ::right :namespaces]] @log))
          (is (fn? (:namespaces @(:after-eval* (:cv wctx))))))))))
