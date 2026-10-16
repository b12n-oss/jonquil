(ns gtk-selftest-window.session-tabs
  "Reopening the last session's tabs (session-tabs plan Task 2): the
  window's :save-session!* and :restore-session!*, against sessions.edn
  beside the self-test's own temp settings file. Rows that need a known
  session write sessions.edn themselves. Every row leaves only the first
  tab, selected, the tree rooted where it was, :reopen-tabs? back on, and
  no sessions.edn.

  The first tab can't be closed (ui's per-tab keys are its objects) and an
  earlier row may leave a file in it, so rows compare against the paths
  open before they started (p0) rather than assuming none.

  Every row is go-style (dev/gtk_async.clj): (fn [ui] ch), one check each."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [gtk-selftest-window.tabs :as tabs]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.window :as window]
            [jonquil.last-session :as last-session]
            [jonquil.settings :as settings]
            [temp-dirs]))

(defn- tab-list [ui] ((:tabs* ui)))
(defn- open-paths [ui] (vec (keep (fn [t] @(:path t)) (tab-list ui))))
(defn- active-path [ui] @(:path ((:active-tab* ui))))
(defn- root [ui] (:root @(:state (:file-tree ui))))
(defn- sessions-file [ui] (last-session/sessions-path ((:settings-path* ui))))

(defn- project!
  "A fresh canonical temp folder holding a.clj and b.clj: [dir a b]."
  []
  (let [d (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-selftest-session-")))
        a (str d "/a.clj")
        b (str d "/b.clj")]
    (spit a "(def a 1)\n")
    (spit b "(def b 2)\n")
    [d a b]))

(defn- write-sessions! [ui sessions]
  (spit (sessions-file ui) (pr-str {:version 1 :sessions sessions})))

(defn- close-extra-tabs! [ui]
  (doseq [t (rest (tab-list ui))]
    (editor/mark-saved! (:ed t))
    ((:close-tab! ui) (:id t)))
  ((:select-tab! ui) (:id (first (tab-list ui)))))

(defn- relaunch-tabs!
  "Closes the extra tabs the way a quit and a new launch leave them: live
  saving off first (the quit's last save is already written), so the closes
  don't shorten the saved list. The row turns it back on after its restore."
  [ui]
  ((:session-live!* ui) false)
  (close-extra-tabs! ui))

(defn- restore!
  "Extra tabs closed, first tab selected, tree rooted at original,
  :reopen-tabs? back on, sessions.edn gone. GTK-thread only."
  [ui original]
  ((:session-live!* ui) true)
  ((:simulate-folder-result! ui) {:path original})
  (close-extra-tabs! ui)
  (settings/save-settings! ((:settings-path* ui)) {:reopen-tabs? true})
  (io/delete-file (sessions-file ui) true))

(defn row-session-save-restore
  "Save with a.clj and b.clj open and a.clj selected, close both, restore:
  both come back in order with a.clj selected."
  [ui]
  (go-row :session-save-restore support/check
          (on-gtk (let [original (root ui)]
                    (try
                      (tabs/occupy-first! ui)
                      (let [[d a b] (project!)
                            p0 (open-paths ui)]
                        ((:simulate-folder-result! ui) {:path d})
                        ((:open-file! ui) a)
                        ((:open-file! ui) b)
                        ((:select-tab! ui) (:id (some (fn [t] (when (= a @(:path t)) t)) (tab-list ui))))
                        (let [err ((:save-session!* ui))
                              _ (relaunch-tabs! ui)
                              closed (open-paths ui)
                              _ ((:restore-session!* ui))
                              _ ((:session-live!* ui) true)
                              v {:err err :p0 p0 :closed closed :paths (open-paths ui) :active (active-path ui)}]
                          (support/check :session-save-restore
                                         (and (nil? err) (= p0 closed) (= (conj p0 a b) (:paths v)) (= a (:active v)))
                                         v)))
                      (finally (restore! ui original)))))))

(defn row-session-untitled-not-saved
  "An Untitled tab holding text is never saved: the root's entry lists only
  the tabs with files (p0), and no :active while the Untitled tab is active."
  [ui]
  (go-row :session-untitled-not-saved support/check
          (on-gtk (let [original (root ui)]
                    (try
                      (tabs/occupy-first! ui)
                      (let [[d] (project!)
                            p0 (open-paths ui)]
                        ((:simulate-folder-result! ui) {:path d})
                        ((:new-tab!* ui))
                        (editor/set-text! (:ed ((:active-tab* ui))) "(+ 1 2)")
                        (let [err ((:save-session!* ui))
                              entry (last-session/entry-for
                                     (:sessions (last-session/load-sessions (sessions-file ui))) d)
                              v {:err err :p0 p0 :entry entry :tab-count (count (tab-list ui))}]
                          (support/check :session-untitled-not-saved
                                         (and (nil? err) entry (= (inc (count p0)) (:tab-count v))
                                              (= p0 (:tabs entry)) (nil? (:active entry)))
                                         v)))
                      (finally (restore! ui original)))))))

(defn row-session-missing-file-named
  "A deleted file and a folder in the session are named on the console;
  the file that's still there opens."
  [ui]
  (go-row :session-missing-file-named support/check
          (let [original (on-gtk (root ui))
                [mark p0 a gone sub]
                (on-gtk (tabs/occupy-first! ui)
                        (let [p0 (open-paths ui)
                              [d a] (project!)
                              gone (str d "/gone.clj")
                              sub (str d "/sub")]
                          (.mkdirs (io/file sub))
                          ((:simulate-folder-result! ui) {:path d})
                          (write-sessions! ui [{:root d :tabs [a gone sub] :active a}])
                          (let [m (support/out-end-offset ui)]
                            ((:restore-session!* ui))
                            [m p0 a gone sub])))
                want "; Couldn't reopen 2 files from last time: gone.clj, sub"
                named? (await-until 2000 20 #(str/includes? (support/since ui mark) want))]
            (on-gtk (try
                      (let [ps (open-paths ui)]
                        (support/check :session-missing-file-named
                                       (and named? (= (conj p0 a) ps) (not-any? #{gone sub} ps))
                                       {:p0 p0 :paths ps :console (support/since ui mark)}))
                      (finally (restore! ui original)))))))

(defn row-session-restore-no-duplicates
  "A file already open isn't opened a second time by a restore."
  [ui]
  (go-row :session-restore-no-duplicates support/check
          (on-gtk (let [original (root ui)]
                    (try
                      (tabs/occupy-first! ui)
                      (let [[d a b] (project!)]
                        ((:simulate-folder-result! ui) {:path d})
                        ((:open-file! ui) a)
                        (write-sessions! ui [{:root d :tabs [a b] :active b}])
                        ((:restore-session!* ui))
                        (let [ps (open-paths ui)
                              v {:paths ps :a (count (filter #{a} ps)) :b (count (filter #{b} ps))}]
                          (support/check :session-restore-no-duplicates
                                         (and (= 1 (:a v)) (= 1 (:b v))) v)))
                      (finally (restore! ui original)))))))

(defn row-session-restore-symlinked-root
  "A session saved under the canonical root restores when the tree is
  rooted through a symlink to that folder."
  [ui]
  (go-row :session-restore-symlinked-root support/check
          (on-gtk (let [original (root ui)]
                    (try
                      (tabs/occupy-first! ui)
                      (let [[d a] (project!)
                            p0 (open-paths ui)
                            link (str (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-selftest-session-link-")))
                                      "/proj")]
                        (java.nio.file.Files/createSymbolicLink
                         (.toPath (io/file link)) (.toPath (io/file d))
                         (make-array java.nio.file.attribute.FileAttribute 0))
                        ((:simulate-folder-result! ui) {:path d})
                        ((:open-file! ui) a)
                        (let [err ((:save-session!* ui))
                              _ (relaunch-tabs! ui)
                              _ ((:simulate-folder-result! ui) {:path link})
                              linked-root (root ui)
                              _ ((:restore-session!* ui))
                              _ ((:session-live!* ui) true)
                              v {:err err :link link :root linked-root :p0 p0 :paths (open-paths ui)}]
                          (support/check :session-restore-symlinked-root
                                         (and (nil? err) (= d linked-root) (= (conj p0 a) (:paths v)))
                                         v)))
                      (finally (restore! ui original)))))))

(defn row-session-off
  "With :reopen-tabs? false, saving leaves sessions.edn as it was and
  restoring opens nothing."
  [ui]
  (go-row :session-off support/check
          (on-gtk (let [original (root ui)]
                    (try
                      (tabs/occupy-first! ui)
                      (let [[d a b] (project!)]
                        ((:simulate-folder-result! ui) {:path d})
                        (settings/save-settings! ((:settings-path* ui)) {:reopen-tabs? false})
                        (write-sessions! ui [{:root d :tabs [b] :active b}])
                        ((:open-file! ui) a)
                        (let [before (slurp (sessions-file ui))
                              err ((:save-session!* ui))
                              after (slurp (sessions-file ui))
                              paths0 (open-paths ui)
                              _ ((:restore-session!* ui))
                              v {:err err :same-bytes (= before after) :paths0 paths0 :paths (open-paths ui)}]
                          (support/check :session-off
                                         (and (nil? err) (:same-bytes v) (= paths0 (:paths v))) v)))
                      (finally (restore! ui original)))))))

(defn row-session-bad-file
  "An unreadable sessions.edn is reported on the console and nothing opens."
  [ui]
  (go-row :session-bad-file support/check
          (let [original (on-gtk (root ui))
                [mark paths0]
                (on-gtk (tabs/occupy-first! ui)
                        (let [[d] (project!)]
                          ((:simulate-folder-result! ui) {:path d})
                          (spit (sessions-file ui) "{:sessions [")
                          (let [m (support/out-end-offset ui)
                                p0 (open-paths ui)]
                            ((:restore-session!* ui))
                            [m p0])))
                want "; Couldn't read sessions.edn, so no tabs were reopened."
                named? (await-until 2000 20 #(str/includes? (support/since ui mark) want))]
            (on-gtk (try
                      (let [ps (open-paths ui)]
                        (support/check :session-bad-file
                                       (and named? (= paths0 ps))
                                       {:paths0 paths0 :paths ps :console (support/since ui mark)}))
                      (finally (restore! ui original)))))))

(defn row-session-throwing-file-skipped
  "Opening one saved file throws (read-for-tab rebound for that path): the
  later saved file still opens, the saved active tab is selected, and the
  throwing file is named on the console."
  [ui]
  (go-row :session-throwing-file-skipped support/check
          (let [original (on-gtk (root ui))
                orig-read @#'window/read-for-tab
                [mark p0 a b]
                (on-gtk (tabs/occupy-first! ui)
                        (let [p0 (open-paths ui)
                              [d a b] (project!)
                              boom (str d "/boom.clj")]
                          (spit boom "(def boom 3)\n")
                          ((:simulate-folder-result! ui) {:path d})
                          (write-sessions! ui [{:root d :tabs [boom a b] :active b}])
                          (let [m (support/out-end-offset ui)]
                            (with-redefs [window/read-for-tab
                                          (fn [cv path]
                                            (if (= path boom)
                                              (throw (ex-info "forced open failure" {}))
                                              (orig-read cv path)))]
                              ((:restore-session!* ui)))
                            [m p0 a b])))
                want "; Couldn't reopen boom.clj from last time.\n"
                named? (await-until 2000 20 #(str/includes? (support/since ui mark) want))]
            (on-gtk (try
                      (let [ps (open-paths ui)]
                        (support/check :session-throwing-file-skipped
                                       (and named? (= (conj p0 a b) ps) (= b (active-path ui)))
                                       {:p0 p0 :paths ps :active (active-path ui) :want want
                                        :console (support/since ui mark)}))
                      (finally (restore! ui original)))))))

(defn row-session-saved-live
  "Without a quit or a :save-session!* call, sessions.edn follows the tabs:
  opening two files and selecting the first saves both with it active, and
  closing the second saves the shorter list."
  [ui]
  (go-row :session-saved-live support/check
          (on-gtk (let [original (root ui)]
                    (try
                      (tabs/occupy-first! ui)
                      (let [[d a b] (project!)
                            p0 (open-paths ui)
                            entry (fn [] (last-session/entry-for
                                          (:sessions (last-session/load-sessions (sessions-file ui))) d))
                            tab-for (fn [p] (some (fn [t] (when (= p @(:path t)) t)) (tab-list ui)))]
                        ((:simulate-folder-result! ui) {:path d})
                        ((:open-file! ui) a)
                        ((:open-file! ui) b)
                        ((:select-tab! ui) (:id (tab-for a)))
                        (let [opened (entry)
                              _ ((:close-tab! ui) (:id (tab-for b)))
                              closed (entry)
                              v {:p0 p0 :opened opened :closed closed}]
                          (support/check :session-saved-live
                                         (and (= (conj p0 a b) (:tabs opened)) (= a (:active opened))
                                              (= (conj p0 a) (:tabs closed)) (= a (:active closed)))
                                         v)))
                      (finally (restore! ui original)))))))
