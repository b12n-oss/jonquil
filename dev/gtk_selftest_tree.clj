(ns gtk-selftest-tree
  "File-tree plan P2.T3: opens the REAL Jonquil window launched on a
  directory (jonquil.main/start! with :dir, as `jonquil ~/dev/project`
  does) and checks the tree is rooted there and showing, the window runs
  that directory's project, and the one tab is Untitled. A group of its
  own (plan ruling 2): the window group starts with no argument and every
  one of its rows relies on that.

  Mirrors gtk-selftest-cljd's driver; the last row fires the app's quit
  action."
  (:require [clojure.core.async :as a :refer [<! go timeout]]
            [clojure.java.io :as io]
            [gtk-async :as ga :refer [go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :as cb]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.file-tree-pane :as pane]
            [jonquil.main :as main]
            [temp-dirs]))

(defonce ^:private fixture* (atom nil))

(defn- fixture!
  "A temp project: deps.edn, src/demo/core.clj, README.md, a .gitignore of
  *.log and an x.log. Its canonical path."
  []
  (let [d (temp-dirs/temp-dir! "jonquil-selftest-tree-")]
    (doseq [[rel text] [["deps.edn" "{}\n"] ["src/demo/core.clj" "(ns demo.core)\n"] ["README.md" "# demo\n"]
                        [".gitignore" "*.log\n"] ["x.log" "noise\n"]]]
      (let [f (io/file d rel)] (io/make-parents f) (spit f text)))
    (reset! fixture* (.getCanonicalPath (io/file d)))))

(defn- row-tree-dir-launch-root
  "The tree is showing, its header names the fixture folder, its rows are
  src, deps.edn, README.md (.gitignore and x.log hidden), and the one tab
  is Untitled."
  [ui]
  (go-row :tree-dir-launch-root support/check
    (on-gtk (let [p (:file-tree ui)
                  ts ((:tabs* ui))
                  v {:visible ((:file-tree-visible?* ui))
                     :header (g/label-text (:header-label p))
                     :names (mapv :name (pane/visible-rows p))
                     :tabs (count ts)
                     :tab-path @(:path (first ts))}]
              (support/check :tree-dir-launch-root
                             (and (:visible v) (= (.getName (io/file @fixture*)) (:header v))
                                  (= ["src" "deps.edn" "README.md"] (:names v))
                                  (= 1 (:tabs v)) (nil? (:tab-path v)))
                             v)))))

(defn- row-tree-dir-launch-project
  "The window runs the fixture's project."
  [ui]
  (go-row :tree-dir-launch-project support/check
    (on-gtk (let [dir (:project-dir ((:selection* ui)))]
              (support/check :tree-dir-launch-project (= @fixture* dir) {:project-dir dir :fixture @fixture*})))))

(defn- row-tree-dir-launch-close
  "The deliberately last row: the app's quit action, with nothing unsaved."
  [ui]
  (go-row :tree-dir-launch-close support/check
    (on-gtk ((:activate-quit-action!* ui)))))

(def ^:private row-fns
  [row-tree-dir-launch-root
   row-tree-dir-launch-project
   row-tree-dir-launch-close])

(def ^:private PER-ROW-MS 30000)

(defonce ^:private finished? (atom false))
(defonce ^:private window-destroyed? (atom false))
(def ^:private CLOSE-FALLBACK-DEADLINE-MS 2000)

(defn- print-summary! []
  (let [passed (count (filter second @support/results)) total (count @support/results)]
    (reset! finished? true)
    (println (str "SUMMARY " passed " of " total " passed"))))

(defn- finish
  "After the last row: forces the close only when the quit action didn't
  take, as the other groups do."
  [ui]
  (go (let [t0 (System/currentTimeMillis)]
        (loop []
          (when (and (not @window-destroyed?)
                     (< (- (System/currentTimeMillis) t0) CLOSE-FALLBACK-DEADLINE-MS))
            (<! (timeout 50))
            (recur))))
      (when-not @window-destroyed?
        (support/check :tree-close-forced false {:reason "window still open after the quit action"})
        (ga/close-window-bounded!
         (:window ui) window-destroyed? CLOSE-FALLBACK-DEADLINE-MS
         (fn []
           (support/check :tree-close-stuck false {:reason "window still open after the forced close too"})
           (print-summary!)
           (temp-dirs/delete-made!)
           (System/exit 1))))))

(defn -main [& _]
  (let [dir (fixture!)
        driver-done (a/promise-chan)]
    (main/start!
     {:dir dir
      :cwd dir
      :settings-path (support/fresh-settings-path)
      :on-ready (fn [ui]
                  (g/g-signal-connect-data (:window ui) "destroy"
                                           (cb/callable (fn [_win _data] (reset! window-destroyed? true) nil)
                                                        [:pointer :pointer] :void)
                                           ffi/null ffi/null g/CONNECT-DEFAULT)
                  (go (<! (timeout 300))
                      (<! (ga/run-rows (map (fn [row] (fn [] (row ui))) row-fns) PER-ROW-MS support/check))
                      (<! (finish ui))
                      (a/put! driver-done :done)))})
    (a/alts!! [driver-done (timeout 5000)])
    (print-summary!))
  (when-not @finished?
    (binding [*out* *err*]
      (println "[gtk-selftest-tree] run ended before the last row; no SUMMARY, exiting 1")))
  (temp-dirs/delete-made!)
  (System/exit (if (and @finished? (seq @support/results) (every? second @support/results)) 0 1)))
