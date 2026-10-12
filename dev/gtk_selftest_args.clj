(ns gtk-selftest-args
  "P1.T4: opens the REAL Jonquil window the way a terminal launch does:
  jonquil.main/start! with :argv from jonquil.main/gtk-argv for two files (src/a.clj and b.clj of a project with a deps.edn), so
  GApplication emits \"open\" (and not \"activate\"). Checks the two files
  are tabs with no Untitled left, the first file's project roots the tree,
  and the last file is the active tab. A group of its own (plan ruling 2):
  g_application_run can't be re-run from a row of a running app.

  Mirrors gtk-selftest-tree's driver; the last row fires the app's quit
  action."
  (:require [clojure.core.async :as a :refer [<! go timeout]]
            [clojure.java.io :as io]
            [gtk-async :as ga :refer [go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :as cb]
            [jonquil.gtk.ffi :as g]
            [jonquil.main :as main]
            [temp-dirs]))

(defonce ^:private fixture* (atom nil))

(defn- fixture!
  "A temp project: deps.edn, src/a.clj and b.clj. Its canonical paths."
  []
  (let [d (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-selftest-args-")))]
    (doseq [[rel text] [["deps.edn" "{}\n"] ["src/a.clj" "(def a 1)\n"] ["b.clj" "(def b 2)\n"]]]
      (let [f (io/file d rel)] (io/make-parents f) (spit f text)))
    (reset! fixture* {:dir d :a (str d "/src/a.clj") :b (str d "/b.clj")})))

(defn- tab-paths [ui] (mapv (fn [t] @(:path t)) ((:tabs* ui))))

(defn- row-args-two-files-open-as-tabs
  "Exactly the two files have tabs: no Untitled tab is left."
  [ui]
  (go-row :args-two-files-open-as-tabs support/check
    (on-gtk (let [{:keys [a b]} @fixture*
                  ps (tab-paths ui)]
              (support/check :args-two-files-open-as-tabs
                             (and (= 2 (count ps)) (= #{a b} (set ps)))
                             {:paths ps :want [a b]})))))

(defn- row-args-tree-rooted-at-project
  "The first file's project (the dir holding deps.edn) roots the tree, as a
  single-file launch always did."
  [ui]
  (go-row :args-tree-rooted-at-project support/check
    (on-gtk (let [root (:root @(:state (:file-tree ui)))]
              (support/check :args-tree-rooted-at-project (= (:dir @fixture*) root)
                             {:root root :want (:dir @fixture*)})))))

(defn- row-args-active-tab-is-last-file
  "The active tab is the last file argument, b.clj."
  [ui]
  (go-row :args-active-tab-is-last-file support/check
    (on-gtk (let [t ((:active-tab* ui))
                  p (when t @(:path t))]
              (support/check :args-active-tab-is-last-file (= (:b @fixture*) p)
                             {:active p :want (:b @fixture*)})))))

(defn- row-args-close
  "The deliberately last row: the app's quit action, with nothing unsaved."
  [ui]
  (go-row :args-close support/check
    (on-gtk ((:activate-quit-action!* ui)))))

(def ^:private row-fns
  [row-args-two-files-open-as-tabs
   row-args-tree-rooted-at-project
   row-args-active-tab-is-last-file
   row-args-close])

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
        (support/check :args-close-forced false {:reason "window still open after the quit action"})
        (ga/close-window-bounded!
         (:window ui) window-destroyed? CLOSE-FALLBACK-DEADLINE-MS
         (fn []
           (support/check :args-close-stuck false {:reason "window still open after the forced close too"})
           (print-summary!)
           (temp-dirs/delete-made!)
           (System/exit 1))))))

(defn -main [& _]
  (let [dir (fixture!)
        driver-done (a/promise-chan)]
    (main/start!
     {:cwd (:dir dir)
      :argv (main/gtk-argv [(:a dir) (:b dir)] (:dir dir))
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
      (println "[gtk-selftest-args] run ended before the last row; no SUMMARY, exiting 1")))
  (temp-dirs/delete-made!)
  (System/exit (if (and @finished? (seq @support/results) (every? second @support/results)) 0 1)))
