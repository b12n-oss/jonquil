(ns gtk-selftest-args
  "Opens the REAL Jonquil window the way a terminal launch does:
  jonquil.main/start! with :argv from jonquil.main/gtk-argv for a folder
  (sub/) and two files (src/a.clj and b.clj of a project with a deps.edn), so
  GApplication emits \"open\" (and not \"activate\"). Checks the two files
  are the only tabs, the folder roots the tree and shows it without saving
  the tree's visibility (a tree opened by a folder argument is not a user choice), the last file is the active tab, and the
  process's prgname is the launcher's net.b12n.jonquil on Linux. A group of its own:
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
(defonce ^:private settings-path* (atom nil))
(def ^:private PRGNAME "net.b12n.jonquil")
;; Measured on macOS before the Linux-only g_set_prgname: GApplication's
;; argv[0] launch leaves it \"jonquil\".
(def ^:private MAC-PRGNAME "jonquil")

(defn- fixture!
  "A temp project: deps.edn, src/a.clj and b.clj. Its canonical paths."
  []
  (let [d (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-selftest-args-")))]
    (doseq [[rel text] [["deps.edn" "{}\n"] ["src/a.clj" "(def a 1)\n"] ["b.clj" "(def b 2)\n"]
                        ["sub/c.clj" "(def c 3)\n"]]]
      (let [f (io/file d rel)] (io/make-parents f) (spit f text)))
    (reset! fixture* {:dir d :sub (str d "/sub") :a (str d "/src/a.clj") :b (str d "/b.clj")})))

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

(defn- row-args-folder-roots-tree
  "The folder argument roots the tree and the tree shows (a :dir launch
  starts it open)."
  [ui]
  (go-row :args-folder-roots-tree support/check
    (on-gtk (let [root (:root @(:state (:file-tree ui)))
                  shown? ((:file-tree-visible?* ui))]
              (support/check :args-folder-roots-tree (and (= (:sub @fixture*) root) shown?)
                             {:root root :want (:sub @fixture*) :shown? shown?})))))

(defn- row-args-folder-launch-saves-no-tree-visibility
  "Showing the tree for a folder launch is the
  starting state, not a person's choice, so the settings file holds no
  :file-tree."
  [_ui]
  (go-row :args-folder-launch-saves-no-tree-visibility support/check
    (on-gtk (let [f (io/file @settings-path*)
                  text (when (.exists f) (slurp f))]
              (support/check :args-folder-launch-saves-no-tree-visibility
                             (not (and text (re-find #":file-tree" text)))
                             {:settings text})))))

(defn- row-args-prgname
  "Linux: the prgname is the launcher's StartupWMClass, so X11's WM_CLASS
  pairs the window with its entry. macOS: left as GApplication set it."
  [_ui]
  (go-row :args-prgname support/check
    (on-gtk (let [p (g/g-get-prgname)
                  got (when-not (ffi/null? p) (ffi/ptr->string p))
                  linux? (= "Linux" (System/getProperty "os.name"))
                  want (if linux? PRGNAME MAC-PRGNAME)]
              (support/check :args-prgname (= want got) {:got got :want want})))))

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
   row-args-folder-roots-tree
   row-args-folder-launch-saves-no-tree-visibility
   row-args-active-tab-is-last-file
   row-args-prgname
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
      :argv (main/gtk-argv [(:sub dir) (:a dir) (:b dir)] (:dir dir))
      :settings-path (reset! settings-path* (support/fresh-settings-path))
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
