(ns gtk-selftest-cljs-node.rows
  "Rows for the ClojureScript-on-node window: a real window on a throwaway
  project whose src/demo/core.cljs selects the :cljs-node profile (no
  shadow-cljs.edn), with JONQUIL_CLOJURE naming dev/fake-cljs-clojure. The
  window's evaluator starts that fake as a real child process, so the rows
  can check its pid. Nothing here needs a clojure or node install."
  (:require [clojure.core.async :refer [<! timeout]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jolt.process :as proc]
            [jonquil.cljs-node :as cljs-node]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.nrepl.cljs-node :as nrepl-cljs-node]
            [temp-dirs]))

(def fixture-core-text
  (str "(ns demo.core)\n"
       "\n"
       "(defn greet [w] (str \"hello \" w))\n"
       "\n"
       "(+ 1 2)\n"))

(def ^:private NOT-RUNNING "ClojureScript (node) (not running)")
(def ^:private RUNNING "ClojureScript (node) (running)")

;; The fixture project, stashed so rows and after-close-checks! (which runs
;; after main/start! returns) reach it. :closing holds the pid
;; row-cljs-node-close-kills-child recorded right before it quit.
(defonce ^:private fixture* (atom nil))

(defn fixture! []
  (let [d (io/file (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-selftest-cljs-node-"))))
        f (io/file d "src/demo/core.cljs")]
    (.mkdirs (.getParentFile f))
    (spit (io/file d "deps.edn") "{:deps {org.clojure/clojurescript {:mvn/version \"1.12.42\"}}}\n")
    (spit f fixture-core-text)
    (reset! fixture* {:dir (.getCanonicalPath d) :file (.getCanonicalPath f)})))

(defn evaluator [ui] ((:evaluator* ui)))

(defn idle? [ui] (not (ev/evaluating? (evaluator ui))))

(defn- label [ui] (g/label-text (:dialect-label ui)))

(defn- pid-of [ui] (nrepl-cljs-node/child-pid (evaluator ui)))

(defn- pid-gone?
  "True when no process has pid. Runs a subprocess, so call it from a row
  body, never from an await-until predicate (those run on the GTK thread)."
  [pid]
  (not (zero? (:exit (proc/sh "kill" "-0" (str pid))))))

(defn- await-gone
  "Parks until pid is gone or deadline-ms passes."
  [pid deadline-ms]
  (let [t0 (System/currentTimeMillis)]
    (loop []
      (cond (pid-gone? pid) true
            (>= (- (System/currentTimeMillis) t0) deadline-ms) false
            :else (do (<! (timeout 50)) (recur))))))

(defn- run-and-await
  "Sets the editor to code, Runs, and parks until the evaluator is idle and
  (expect-fn text-since-start) holds. [ok mark]."
  [ui code deadline-ms expect-fn]
  (let [mark (on-gtk (let [m (support/out-end-offset ui)]
                       (editor/set-text! (:editor ui) code)
                       ((:run!* ui))
                       m))
        ok (await-until deadline-ms 20 #(and (idle? ui) (expect-fn (support/since ui mark))))]
    [ok mark]))

(defn row-cljs-node-starts-not-running
  "The window opened src/demo/core.cljs outside any shadow-cljs project:
  the label says ClojureScript (node) isn't running yet."
  [ui]
  (go-row :cljs-node-starts-not-running support/check
    (on-gtk (support/check :cljs-node-starts-not-running
                           (= NOT-RUNNING (label ui))
                           {:label (label ui) :text (support/console-text ui)}))))

(defn row-cljs-node-run-prints-value
  "Run evaluates the buffer in the fake: the console shows 3, the label
  turns to running."
  [ui]
  (go-row :cljs-node-run-prints-value support/check
    (let [[ok? mark] (run-and-await ui fixture-core-text 30000 #(str/includes? % "3"))
          running? (await-until 2000 20 #(= RUNNING (label ui)))]
      (on-gtk (support/check :cljs-node-run-prints-value
                             (and ok? running? (pid-of ui))
                             {:ok ok? :running running? :label (label ui)
                              :text (support/since ui mark)})))))

(defn row-cljs-node-run-error-line
  "A form that throws gives a one-line error carrying its message."
  [ui]
  (go-row :cljs-node-run-error-line support/check
    (let [[ok? mark] (run-and-await ui "(throw (ex-info \"boom-cljs\" {}))" 30000
                                    #(str/includes? % "boom-cljs"))]
      (on-gtk (support/check :cljs-node-run-error-line ok? {:text (support/since ui mark)})))))

(defn row-cljs-node-stop-restarts
  "A form containing slow takes 30 s in the fake, so Stop kills the child:
  the console says ClojureScript restarted, the old pid is gone, and the
  next Run starts a new child and answers."
  [ui]
  (go-row :cljs-node-stop-restarts support/check
    (let [old (pid-of ui)
          mark (on-gtk (let [m (support/out-end-offset ui)]
                         (editor/set-text! (:editor ui) "(str \"slow\")")
                         ((:run!* ui))
                         m))
          started? (await-until 3000 20 #(not (idle? ui)))
          _ (on-gtk ((:stop!* ui)))
          stopped? (await-until 5000 20 #(and (idle? ui)
                                              (str/includes? (support/since ui mark) cljs-node/stopped-message)))
          gone? (and old (await-gone old 3000))
          [answered? mark2] (run-and-await ui "(+ 20 22)" 30000 #(str/includes? % "42"))
          new (pid-of ui)]
      (on-gtk (support/check :cljs-node-stop-restarts
                             (and old started? stopped? gone? answered? new (not= old new))
                             {:old old :new new :started started? :stopped stopped? :gone gone?
                              :answered answered? :text (str (support/since ui mark) (support/since ui mark2))})))))

(defn row-cljs-node-close-kills-child
  "The deliberately LAST row: records the child's pid, then fires the app's
  quit action. after-close-checks! asserts it is gone once the GTK loop has
  ended."
  [ui]
  (go-row :cljs-node-close-kills-child support/check
    (on-gtk (swap! fixture* assoc :closing {:pid (pid-of ui)})
            (editor/set-text! (:editor ui) "")
            ((:activate-quit-action!* ui)))))

(defn after-close-checks!
  "Called by -main once main/start! returns: the child process is gone.
  Then deletes the project."
  []
  (let [{:keys [dir closing]} @fixture*
        pid (:pid closing)
        gone? (boolean (and pid (loop [n 0]
                                  (cond (pid-gone? pid) true
                                        (>= n 50) false
                                        :else (do (Thread/sleep 100) (recur (inc n)))))))]
    (support/check :cljs-node-close-recorded (boolean pid) {:closing closing})
    (support/check :cljs-node-close-pid-gone gone? {:pid pid})
    (doseq [x (reverse (file-seq (io/file dir)))] (.delete ^java.io.File x))))
