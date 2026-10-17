(ns gtk-selftest-tree
  "File-tree plan P2.T3: opens the REAL Jonquil window launched on a
  directory (jonquil.main/start! with :dir, as `jonquil ~/dev/project`
  does) and checks the tree is rooted there and showing, the window runs
  that directory's project, and the one tab is Untitled when the window
  first shows. A group of its own (plan ruling 2): the window group starts
  with no argument and every one of its rows relies on that.

  sessions.edn remembers two of the fixture's files and one that's gone,
  so the launch also reopens tabs, after the window is up.

  Mirrors gtk-selftest-cljd's driver; the last row fires the app's quit
  action."
  (:require [clojure.core.async :as a :refer [<! go timeout]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [gtk-async :as ga :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :as cb]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.file-tree-pane :as pane]
            [jonquil.last-session :as last-session]
            [jonquil.main :as main]
            [temp-dirs]))

(defonce ^:private fixture* (atom nil))
;; The open tabs' paths and the tree's rows when on-ready ran, right after
;; the window was shown. Later the reopened core.clj expands src/demo.
(defonce ^:private at-show* (atom nil))
;; sessions.edn as written before the launch.
(defonce ^:private sessions-text* (atom nil))

(defn- fixture!
  "A temp project: deps.edn, src/demo/core.clj, README.md, a .gitignore of
  *.log and an x.log. Its canonical path."
  []
  (let [d (temp-dirs/temp-dir! "jonquil-selftest-tree-")]
    (doseq [[rel text] [["deps.edn" "{}\n"] ["src/demo/core.clj" "(ns demo.core)\n"] ["README.md" "# demo\n"]
                        [".gitignore" "*.log\n"] ["x.log" "noise\n"]]]
      (let [f (io/file d rel)] (io/make-parents f) (spit f text)))
    (reset! fixture* (.getCanonicalPath (io/file d)))))

(defn- write-sessions!
  "sessions.edn beside settings-path: the fixture's core.clj, README.md and
  a gone.clj that doesn't exist, with core.clj active."
  [settings-path]
  (let [d @fixture*
        core (str d "/src/demo/core.clj")
        text (pr-str {:version 1
                      :sessions [{:root d :tabs [core (str d "/README.md") (str d "/gone.clj")] :active core}]})]
    (spit (last-session/sessions-path settings-path) text)
    (reset! sessions-text* text)))

(defn- row-tree-dir-launch-root
  "The tree is showing, its header names the fixture folder, its rows are
  src, deps.edn, README.md (.gitignore and x.log hidden), and its one tab
  was Untitled, all as the window was shown: the remembered tabs open
  after that."
  [ui]
  (go-row :tree-dir-launch-root support/check
    (on-gtk (let [p (:file-tree ui)
                  v {:visible ((:file-tree-visible?* ui))
                     :header (g/label-text (:header-label p))
                     :at-show @at-show*}]
              (support/check :tree-dir-launch-root
                             (and (:visible v) (= (.getName (io/file @fixture*)) (:header v))
                                  (= ["src" "deps.edn" "README.md"] (:names (:at-show v)))
                                  (= [nil] (:tabs (:at-show v))))
                             v)))))

(defn- row-tree-dir-launch-reopen
  "The remembered tabs open in order, core.clj is selected, gone.clj is
  named on the console, and the reopen wrote nothing to sessions.edn. Then
  live saving is on: selecting README.md writes it as the active tab."
  [ui]
  (go-row :tree-dir-launch-reopen support/check
    (let [d @fixture*
          want [(str d "/src/demo/core.clj") (str d "/README.md")]
          paths (fn [] (vec (map (fn [t] @(:path t)) ((:tabs* ui)))))
          ;; The line for gone.clj comes last, after the active tab is selected.
          named? (fn [] (str/includes? (support/console-text ui) "; Couldn't reopen gone.clj from last time."))
          done? (await-until 5000 20 (fn [] (and (= want (paths)) (named?))))
          v (on-gtk {:paths (paths)
                     :active @(:path ((:active-tab* ui)))
                     :sessions-same? (= @sessions-text* (slurp (last-session/sessions-path ((:settings-path* ui)))))})
          saved-active (fn [] (:active (last-session/entry-for
                                        (:sessions (last-session/load-sessions (last-session/sessions-path ((:settings-path* ui)))))
                                        d)))
          _ (on-gtk ((:select-tab! ui) (:id (second ((:tabs* ui))))))
          live? (await-until 2000 20 (fn [] (= (second want) (saved-active))))]
      (support/check :tree-dir-launch-reopen
                     (and done? (= (first want) (:active v)) (:sessions-same? v) live?)
                     (assoc v :live? live?)))))

(defn- row-tree-dir-launch-project
  "The window runs the fixture's project."
  [ui]
  (go-row :tree-dir-launch-project support/check
    (on-gtk (let [dir (:project-dir ((:selection* ui)))]
              (support/check :tree-dir-launch-project (= @fixture* dir) {:project-dir dir :fixture @fixture*})))))

(defn- row-tree-dir-launch-no-save
  "Showing the tree for a directory launch is the starting default, not a
  choice: nothing is written to the settings file (final review finding 6)."
  [ui]
  (go-row :tree-dir-launch-no-save support/check
    (on-gtk (let [p ((:settings-path* ui))
                  exists (.exists (io/file p))
                  text (when exists (slurp p))]
              (support/check :tree-dir-launch-no-save (or (not exists) (not (re-find #":file-tree" text)))
                             {:exists exists :text text})))))

(defn- row-tree-dir-launch-close
  "The deliberately last row: the app's quit action, with nothing unsaved."
  [ui]
  (go-row :tree-dir-launch-close support/check
    (on-gtk ((:activate-quit-action!* ui)))))

(def ^:private row-fns
  [row-tree-dir-launch-root
   row-tree-dir-launch-reopen
   row-tree-dir-launch-project
   row-tree-dir-launch-no-save
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
        settings-path (support/fresh-settings-path)
        driver-done (a/promise-chan)]
    (write-sessions! settings-path)
    (main/start!
     {:dir dir
      :cwd dir
      :settings-path settings-path
      :on-ready (fn [ui]
                  (reset! at-show* {:tabs (mapv (fn [t] @(:path t)) ((:tabs* ui)))
                                    :names (mapv :name (pane/visible-rows (:file-tree ui)))})
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
