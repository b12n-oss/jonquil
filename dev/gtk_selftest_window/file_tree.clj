(ns gtk-selftest-window.file-tree
  "The file tree in the real window (file-tree plan P2.T2): the chord and
  the button, opening from the tree into tabs, the highlight following the
  active tab, a save showing its file, Open Folder through a simulated
  result, the settings it writes, and the theme reaching it. The window
  group starts with no argument, so the tree is rooted at the checkout
  (the cwd) and hidden; every row puts both back in a finally.

  Every row is go-style (dev/gtk_async.clj): (fn [ui] ch), one check each."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [gtk-selftest-window.tabs :as tabs]
            [jolt.ffi :as ffi]
            [jonquil.file-ops :as ops]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.file-tree-pane :as pane]
            [jonquil.settings :as settings]
            [temp-dirs]))

(defn- pane [ui] (:file-tree ui))
(defn- visible? [ui] ((:file-tree-visible?* ui)))
(defn- root [ui] (:root @(:state (pane ui))))

(defn- tree!
  "A temp project: src/a.clj, src/b.clj, README.md; its canonical path."
  []
  (let [r (temp-dirs/temp-dir! "jonquil-selftest-tree-")]
    (doseq [rel ["src/a.clj" "src/b.clj" "README.md"]]
      (let [f (io/file r rel)] (io/make-parents f) (spit f (str "(ns " (str/replace rel #"[/.]" "-") ")\n"))))
    (.getCanonicalPath (io/file r))))

(defn- restore!
  "Tree hidden, rooted back at original, extra tabs closed. GTK-thread only."
  [ui original]
  (when (visible? ui) ((:toggle-file-tree!* ui)))
  ((:simulate-folder-result! ui) {:path original})
  (doseq [t (rest ((:tabs* ui)))]
    (editor/mark-saved! (:ed t))
    ((:close-tab! ui) (:id t)))
  ((:select-tab! ui) (:id (first ((:tabs* ui))))))

(defn- activate-name! [ui name]
  (let [p (pane ui)
        i (first (keep-indexed (fn [i r] (when (= name (:name r)) i)) (pane/visible-rows p)))]
    (pane/activate-index! p i)))

(defn- cmd-shift-e! [ui]
  ((:handle-window-key! ui) ffi/null 0x45 0 (bit-or g/META-MASK g/SHIFT-MASK) ffi/null))

(defn row-tree-toggle-chord
  "Cmd/Ctrl+Shift+E shows the tree and the button follows; again hides it,
  and the notebook gets its width back."
  [ui]
  (go-row :tree-toggle-chord support/check
    (let [original (on-gtk (root ui))
          w0 (on-gtk (g/gtk-widget-get-width (:notebook ui)))
          _ (on-gtk (cmd-shift-e! ui))
          shown? (await-until 2000 20 #(< (g/gtk-widget-get-width (:notebook ui)) w0))
          v1 (on-gtk {:visible (visible? ui) :btn (g/gtk-toggle-button-get-active (:files-btn ui))})
          _ (on-gtk (cmd-shift-e! ui))
          back? (await-until 2000 20 #(= w0 (g/gtk-widget-get-width (:notebook ui))))
          v2 (on-gtk {:visible (visible? ui) :btn (g/gtk-toggle-button-get-active (:files-btn ui))})]
      (on-gtk (try
                (support/check :tree-toggle-chord
                               (and shown? back? (:visible v1) (= 1 (:btn v1)) (not (:visible v2)) (= 0 (:btn v2)))
                               {:v1 v1 :v2 v2 :shown shown? :back back?})
                (finally (restore! ui original)))))))

(defn row-tree-chord-not-run-selection
  "Cmd/Ctrl+Shift+E toggles the tree and runs nothing; plain Cmd/Ctrl+E
  still runs the selection (Review Focus 4)."
  [ui]
  (go-row :tree-chord-not-run-selection support/check
    (let [original (on-gtk (root ui))
          mark (on-gtk (let [m (support/out-end-offset ui)
                             ed (:editor ui)]
                         (editor/set-text! ed "(+ 1 2)")
                         (support/place-cursor! (:buf ed) 1)
                         (cmd-shift-e! ui)
                         m))
          ran-on-shift? (await-until 600 20 #(str/includes? (support/since ui mark) "=> 3"))
          toggled (on-gtk (visible? ui))
          mark2 (on-gtk (let [m (support/out-end-offset ui)]
                          ((:handle-window-key! ui) ffi/null 0x65 0 g/META-MASK ffi/null)
                          m))
          ran-plain? (await-until 3000 20 #(str/includes? (support/since ui mark2) "=> 3"))]
      (on-gtk (try
                (support/check :tree-chord-not-run-selection (and (not ran-on-shift?) toggled ran-plain?)
                               {:ran-on-shift ran-on-shift? :toggled toggled :ran-plain ran-plain?})
                (finally (restore! ui original)))))))

(defn row-tree-button-toggles
  "A click on the Files button shows the tree."
  [ui]
  (go-row :tree-button-toggles support/check
    (on-gtk (let [original (root ui)]
              (try
                (g/g-signal-emit-by-name (:files-btn ui) "clicked")
                (support/check :tree-button-toggles (visible? ui) {:visible (visible? ui)})
                (finally (restore! ui original)))))))

(defn row-tree-open-and-focus
  "Open Folder (simulated) on a temp project; clicking src then a.clj opens
  a tab; clicking a.clj again focuses that tab instead of adding one."
  [ui]
  (go-row :tree-open-and-focus support/check
    (on-gtk (let [original (root ui)]
              (try
                (tabs/occupy-first! ui)
                (let [r (tree!)
                      _ ((:simulate-folder-result! ui) {:path r})
                      _ (activate-name! ui "src")
                      _ (activate-name! ui "a.clj")
                      n1 (count ((:tabs* ui)))
                      id1 (:id ((:active-tab* ui)))
                      _ ((:select-tab! ui) (:id (first ((:tabs* ui)))))
                      _ (activate-name! ui "a.clj")
                      v {:root-ok (= r (root ui)) :n1 n1 :n2 (count ((:tabs* ui)))
                         :same (= id1 (:id ((:active-tab* ui))))
                         :path @(:path ((:active-tab* ui)))}]
                  (support/check :tree-open-and-focus
                                 (and (:root-ok v) (= 2 n1) (= 2 (:n2 v)) (:same v) (= (str r "/src/a.clj") (:path v)))
                                 v))
                (finally (restore! ui original)))))))

(defn row-tree-highlight-follows-tab
  "The highlight follows the active tab: none on the first tab, a.clj's row
  on a.clj's tab."
  [ui]
  (go-row :tree-highlight-follows-tab support/check
    (on-gtk (let [original (root ui)]
              (try
                (tabs/occupy-first! ui)
                (let [r (tree!)
                      _ ((:simulate-folder-result! ui) {:path r})
                      _ (activate-name! ui "src")
                      _ (activate-name! ui "a.clj")
                      t2 ((:active-tab* ui))
                      _ ((:select-tab! ui) (:id (first ((:tabs* ui)))))
                      on-first (pane/selected-path (pane ui))
                      _ ((:select-tab! ui) (:id t2))
                      on-second (pane/selected-path (pane ui))]
                  (support/check :tree-highlight-follows-tab
                                 (and (nil? on-first) (= (str r "/src/a.clj") on-second))
                                 {:on-first on-first :on-second on-second}))
                (finally (restore! ui original)))))))

(defn row-tree-save-shows-file
  "Saving a new tab under the root shows the file in the tree and selects it."
  [ui]
  (go-row :tree-save-shows-file support/check
    (on-gtk (let [original (root ui)]
              (try
                (let [r (tree!)
                      _ ((:simulate-folder-result! ui) {:path r})
                      t ((:new-tab!* ui))
                      _ (g/gtk-text-buffer-insert-interactive-at-cursor (:buf (:ed t)) "(ns src.new)" -1 1)
                      target (str r "/src/new.clj")
                      _ ((:save-to! ui) target)
                      v {:names (mapv :name (pane/visible-rows (pane ui)))
                         :selected (pane/selected-path (pane ui))}]
                  (support/check :tree-save-shows-file
                                 (and (some #{"new.clj"} (:names v)) (= target (:selected v)))
                                 v))
                (finally (restore! ui original)))))))

(defn row-tree-settings-persist
  "Showing the tree writes :file-tree {:visible? true :width w} to the
  settings file and keeps its :theme."
  [ui]
  (go-row :tree-settings-persist support/check
    (on-gtk (let [original (root ui)]
              (try
                ((:toggle-file-tree!* ui))
                (let [s (settings/load-settings ((:settings-path* ui)))
                      ft (:file-tree s)]
                  (support/check :tree-settings-persist
                                 (and (true? (:visible? ft)) (pos-int? (:width ft)) (keyword? (:theme s)))
                                 {:settings s}))
                (finally (restore! ui original)))))))

(defn row-tree-follows-theme
  "The tree's list carries jonquil-code, the class the shared provider
  restyles on a theme change (Review Focus 5)."
  [ui]
  (go-row :tree-follows-theme support/check
    (on-gtk (let [before ((:theme-name* ui))
                  _ ((:set-theme!* ui) :navy)
                  css-navy ((:code-css* ui))
                  _ ((:set-theme!* ui) :light)
                  css-light ((:code-css* ui))
                  v {:class (g/gtk-widget-has-css-class (:list (pane ui)) "jonquil-code")
                     :changed (not= css-navy css-light)}]
              ((:set-theme!* ui) before)
              (support/check :tree-follows-theme (and (= 1 (:class v)) (:changed v)) v)))))

(defn row-tree-escape-returns-focus
  "With the tree focused, Escape gives focus back to the active editor
  (final review finding 4); hiding a focused tree does the same."
  [ui]
  (go-row :tree-escape-returns-focus support/check
    (on-gtk (let [original (root ui)
                  win (:window ui)]
              (try
                ((:toggle-file-tree!* ui))
                (pane/focus! (pane ui))
                (let [in-tree (pane/focused? (pane ui) win)
                      h ((:handle-window-key! ui) ffi/null 0xff1b 0 0 ffi/null)
                      back (= (g/gtk-root-get-focus win) (:view (:ed ((:active-tab* ui)))))
                      _ (pane/focus! (pane ui))
                      _ ((:toggle-file-tree!* ui))
                      back2 (= (g/gtk-root-get-focus win) (:view (:ed ((:active-tab* ui)))))]
                  (support/check :tree-escape-returns-focus (and in-tree (= 1 h) back back2)
                                 {:in-tree in-tree :handled h :back back :back-after-hide back2}))
                (finally (restore! ui original)))))))

;; --- file operations from the tree (file-ops plan T4) ------------------------
;; Each row roots the tree at its own temp project through the simulated Open
;; Folder result, shows the tree, and drives the menu through the ui hooks
;; (:tree-menu!* :tree-press!* :tree-submit!* :tree-menu-state*). The trash
;; rows go through :trash-choice! with a synthetic {:index n}, so no native
;; alert ever opens. Every row puts the root, the tree's visibility and the
;; tabs back in a finally.

(defn- ops-tree!
  "A temp project: a.clj, c.clj, src/x.clj, plus extra (rel -> text); its
  canonical path."
  ([] (ops-tree! {}))
  ([extra]
   (let [r (temp-dirs/temp-dir! "jonquil-selftest-tree-ops-")]
     (doseq [[rel text] (merge {"a.clj" "(ns a)\n" "c.clj" "(ns c)\n" "src/x.clj" "(ns x)\n"} extra)]
       (let [f (io/file r rel)] (io/make-parents f) (spit f text)))
     (.getCanonicalPath (io/file r)))))

(defn- root-ops!
  "Roots the tree at r and shows it. GTK-thread only."
  [ui r]
  (tabs/occupy-first! ui)
  ((:simulate-folder-result! ui) {:path r})
  (when-not (visible? ui) ((:toggle-file-tree!* ui))))

(defn- tree-shown? [ui] (pos? (g/gtk-widget-get-width (:root (pane ui)))))

(defn- active-path [ui] (some-> ((:active-tab* ui)) :path deref))

(defn- tab-on [ui path] (some (fn [t] (when (= path @(:path t)) t)) ((:tabs* ui))))

(defn- name-op!
  "Opens the menu on path (nil for the root), presses label and submits
  text; the menu's state after. GTK-thread only."
  [ui path label text]
  ((:tree-menu!* ui) path)
  ((:tree-press!* ui) label)
  ((:tree-submit!* ui) text)
  ((:tree-menu-state* ui)))

(defn- popdown! [ui] (g/gtk-popover-popdown (:popover (pane ui))))

(defn- remove-trashed!
  "Removes this row's own trashed copy, by its literal (unique) name: from
  ~/.Trash on macOS; from ~/.local/share/Trash/files plus its .trashinfo on
  Linux. Nothing else there is touched, and a symlink is removed as a link."
  [name]
  (let [home (System/getProperty "user.home")]
    (case (System/getProperty "os.name")
      "Mac OS X" (temp-dirs/delete-tree! (io/file home ".Trash" name))
      "Linux" (do (temp-dirs/delete-tree! (io/file home ".local/share/Trash/files" name))
                  (temp-dirs/delete-tree! (io/file home ".local/share/Trash/info" (str name ".trashinfo"))))
      nil)))

(defn- said-last?
  "Whether the console output since mark, up to the prompt line, ends with msg."
  [ui mark msg]
  (let [s (support/since ui mark)
        i (str/last-index-of s "\n")]
    (boolean (and i (str/ends-with? (subs s 0 (inc i)) msg)))))

(defn- unique-name [stem ext] (str "jonquil-tree-trash-" stem "-" (System/nanoTime) ext))

(defn row-tree-new-file-opens-tab
  "New File... n.clj on the root: the file exists, a tab on it is active,
  its row is selected."
  [ui]
  (go-row :tree-new-file-opens-tab support/check
    (let [original (on-gtk (root ui))
          r (on-gtk (let [r (ops-tree!)] (root-ops! ui r) r))
          _ (await-until 2000 20 #(tree-shown? ui))
          target (str r "/n.clj")
          st (on-gtk (name-op! ui nil "New File…" "n.clj"))
          opened (await-until 2000 20 #(= target (active-path ui)))
          v (on-gtk {:state st :opened opened :exists (.isFile (io/file target))
                     :active (active-path ui) :selected (pane/selected-path (pane ui))})]
      (on-gtk (try
                (support/check :tree-new-file-opens-tab
                               (and opened (:exists v) (not (:open? st)) (= target (:selected v)))
                               v)
                (finally (popdown! ui) (restore! ui original)))))))

(defn row-tree-new-folder-selects
  "New Folder... d on the root: the folder exists and its row is selected."
  [ui]
  (go-row :tree-new-folder-selects support/check
    (let [original (on-gtk (root ui))
          r (on-gtk (let [r (ops-tree!)] (root-ops! ui r) r))
          _ (await-until 2000 20 #(tree-shown? ui))
          target (str r "/d")
          st (on-gtk (name-op! ui nil "New Folder…" "d"))
          selected (await-until 2000 20 #(= target (pane/selected-path (pane ui))))
          v (on-gtk {:state st :selected selected :exists (.isDirectory (io/file target))
                     :sel (pane/selected-path (pane ui))})]
      (on-gtk (try
                (support/check :tree-new-folder-selects (and selected (:exists v) (not (:open? st))) v)
                (finally (popdown! ui) (restore! ui original)))))))

(defn row-tree-rename-open-file
  "Rename... a.clj to b.clj while a.clj is open and dirty: the tab follows
  to b.clj's canonical path and label, keeps its text and stays dirty;
  a.clj is gone."
  [ui]
  (go-row :tree-rename-open-file support/check
    (let [original (on-gtk (root ui))
          r (on-gtk (let [r (ops-tree!)] (root-ops! ui r) r))
          _ (await-until 2000 20 #(tree-shown? ui))
          a (str r "/a.clj")
          b (str r "/b.clj")
          text (on-gtk ((:open-file! ui) a)
                       (let [t ((:active-tab* ui))]
                         (g/gtk-text-buffer-insert-interactive-at-cursor (:buf (:ed t)) ";x" -1 1)
                         (editor/editor-text (:ed t))))
          st (on-gtk (name-op! ui a "Rename…" "b.clj"))
          moved (await-until 2000 20 #(tab-on ui b))
          v (on-gtk (let [t (tab-on ui b)]
                      {:state st :moved moved :a-gone (not (.exists (io/file a))) :b (.isFile (io/file b))
                       :label (when t (g/label-text (:label t)))
                       :text-kept (when t (= text (editor/editor-text (:ed t))))
                       :dirty (when t (editor/dirty? (:ed t)))
                       :selected (pane/selected-path (pane ui))}))]
      (on-gtk (try
                (support/check :tree-rename-open-file
                               (and moved (:a-gone v) (:b v) (:text-kept v) (:dirty v)
                                    (some-> (:label v) (str/starts-with? "b.clj"))
                                    (not (:open? st)))
                               v)
                (finally (popdown! ui) (restore! ui original)))))))

(defn row-tree-rename-folder-retargets
  "Review Focus 1: a tab opened on src/x.clj through a non-canonical
  spelling (a symlink to the root) follows a rename of src to lib."
  [ui]
  (go-row :tree-rename-folder-retargets support/check
    (let [original (on-gtk (root ui))
          r (on-gtk (let [r (ops-tree!)] (root-ops! ui r) r))
          _ (await-until 2000 20 #(tree-shown? ui))
          alias (str (temp-dirs/temp-dir! "jonquil-selftest-tree-alias-") "/proj")
          _ (java.nio.file.Files/createSymbolicLink (.toPath (io/file alias)) (.toPath (io/file r))
                                                    (make-array java.nio.file.attribute.FileAttribute 0))
          spelled (str alias "/src/x.clj")
          target (str r "/lib/x.clj")
          opened (on-gtk ((:open-file! ui) spelled) (active-path ui))
          st (on-gtk (name-op! ui (str r "/src") "Rename…" "lib"))
          moved (await-until 2000 20 #(tab-on ui target))
          v (on-gtk {:state st :opened opened :moved moved :paths (mapv (fn [t] @(:path t)) ((:tabs* ui)))
                     :lib (.isFile (io/file target))})]
      (on-gtk (try
                (support/check :tree-rename-folder-retargets
                               (and (= spelled opened) moved (:lib v) (not (:open? st))) v)
                (finally (popdown! ui) (restore! ui original)))))))

(defn row-tree-rename-taken
  "Rename... a.clj to the existing c.clj: the entry shows the problem and
  neither file changes."
  [ui]
  (go-row :tree-rename-taken support/check
    (let [original (on-gtk (root ui))
          r (on-gtk (let [r (ops-tree!)] (root-ops! ui r) r))
          _ (await-until 2000 20 #(tree-shown? ui))
          a (str r "/a.clj")
          c (str r "/c.clj")]
      (on-gtk (try
                (let [st (name-op! ui a "Rename…" "c.clj")
                      v {:state st :a (slurp a) :c (slurp c)}]
                  (support/check :tree-rename-taken
                                 (and (= "c.clj already exists here." (:problem st)) (:open? st)
                                      (= "(ns a)\n" (:a v)) (= "(ns c)\n" (:c v)))
                                 v))
                (finally (popdown! ui) (restore! ui original)))))))

(defn row-tree-trash-cancel
  "The trash alert's Cancel ({:index 1}) leaves the file where it is."
  [ui]
  (go-row :tree-trash-cancel support/check
    (let [original (on-gtk (root ui))
          r (on-gtk (let [r (ops-tree!)] (root-ops! ui r) r))
          a (str r "/a.clj")]
      (on-gtk (try
                ((:trash-choice! ui) a {:index 1})
                ((:trash-choice! ui) a {:error "dismissed"})
                (support/check :tree-trash-cancel (.isFile (io/file a)) {:exists (.isFile (io/file a))})
                (finally (restore! ui original)))))))

(defn row-tree-trash-open-file
  "Move to Trash ({:index 0}) on an open file: gone from the folder, its
  tab still open with its text, and the console says one tab is open. The
  row removes its own trashed copy from ~/.Trash (macOS)."
  [ui]
  (go-row :tree-trash-open-file support/check
    (let [original (on-gtk (root ui))
          n (unique-name "file" ".clj")
          r (on-gtk (let [r (ops-tree! {n "(ns t)\n"})] (root-ops! ui r) r))
          f (str r "/" n)
          mark (on-gtk (support/out-end-offset ui))
          v (on-gtk (try
                      ((:open-file! ui) f)
                      ((:trash-choice! ui) f {:index 0})
                      (let [t (tab-on ui f)]
                        {:gone (not (.exists (io/file f))) :tab (some? t)
                         :text (when t (editor/editor-text (:ed t)))})
                      (finally (remove-trashed! n))))
          row-gone (await-until 2000 20 #(not-any? (fn [row] (= f (:path row))) (pane/visible-rows (pane ui))))
          v (assoc v :row-gone row-gone)
          msg (ops/trash-message n 1 false)
          said (await-until 2000 20 #(said-last? ui mark msg))]
      (on-gtk (try
                (support/check :tree-trash-open-file
                               (and (:gone v) (:tab v) (= "(ns t)\n" (:text v)) (:row-gone v) said)
                               (assoc v :said said :console (support/since ui mark)))
                (finally (restore! ui original)))))))

(defn row-tree-trash-folder-dirty-tab
  "Review Focus 4: trashing a folder holding a dirty open file keeps the
  tab, its text and its dirty state."
  [ui]
  (go-row :tree-trash-folder-dirty-tab support/check
    (let [original (on-gtk (root ui))
          n (unique-name "dir" "")
          r (on-gtk (let [r (ops-tree! {(str n "/x.clj") "(ns y)\n"})] (root-ops! ui r) r))
          d (str r "/" n)
          f (str d "/x.clj")
          mark (on-gtk (support/out-end-offset ui))
          v (on-gtk (try
                      ((:open-file! ui) f)
                      (let [t (tab-on ui f)]
                        (g/gtk-text-buffer-insert-interactive-at-cursor (:buf (:ed t)) ";z" -1 1)
                        (let [text (editor/editor-text (:ed t))]
                          ((:trash-choice! ui) d {:index 0})
                          (let [t2 (tab-on ui f)]
                            {:gone (not (.exists (io/file d))) :tab (some? t2)
                             :text-kept (when t2 (= text (editor/editor-text (:ed t2))))
                             :dirty (when t2 (editor/dirty? (:ed t2)))})))
                      (finally (remove-trashed! n))))
          msg (ops/trash-message n 1 true)
          said (await-until 2000 20 #(said-last? ui mark msg))]
      (on-gtk (try
                (support/check :tree-trash-folder-dirty-tab
                               (and (:gone v) (:tab v) (:text-kept v) (:dirty v) said)
                               (assoc v :said said :console (support/since ui mark)))
                (finally (restore! ui original)))))))

(defn row-tree-new-dotfile-hidden
  "New File... .env: it exists and opens in a tab, and the console says the
  tree hides it."
  [ui]
  (go-row :tree-new-dotfile-hidden support/check
    (let [original (on-gtk (root ui))
          r (on-gtk (let [r (ops-tree!)] (root-ops! ui r) r))
          _ (await-until 2000 20 #(tree-shown? ui))
          target (str r "/.env")
          mark (on-gtk (support/out-end-offset ui))
          st (on-gtk (name-op! ui nil "New File…" ".env"))
          opened (await-until 2000 20 #(= target (active-path ui)))
          msg (ops/hidden-message ".env")
          said (await-until 2000 20 #(str/includes? (support/since ui mark) msg))
          v (on-gtk {:state st :opened opened :said said :exists (.isFile (io/file target))
                     :console (support/since ui mark)})]
      (on-gtk (try
                (support/check :tree-new-dotfile-hidden (and opened said (:exists v)) v)
                (finally (popdown! ui) (restore! ui original)))))))

(defn row-tree-window-keys-reach-tree
  "Ruling 2: with the tree's name entry open, the window's Escape closes it
  (and doesn't hand focus to the editor); with the list focused, F2, Delete,
  Cmd/Ctrl+BackSpace, Shift+F10 and Menu pass through the window (0)."
  [ui]
  (go-row :tree-window-keys-reach-tree support/check
    (let [original (on-gtk (root ui))
          r (on-gtk (let [r (ops-tree!)] (root-ops! ui r) r))
          _ (await-until 2000 20 #(tree-shown? ui))
          key! (fn [kv st] ((:handle-window-key! ui) ffi/null kv 0 st ffi/null))
          esc (on-gtk ((:tree-menu!* ui) (str r "/a.clj"))
                      ((:tree-press!* ui) "Rename…")
                      (let [before ((:tree-menu-state* ui))
                            h (key! 0xff1b 0)]
                        {:before-mode (:mode before) :handled h :after ((:tree-menu-state* ui))}))
          passed (on-gtk (pane/focus! (pane ui))
                         {:focused (pane/focused? (pane ui) (:window ui))
                          :keys (mapv (fn [[kv st]] (key! kv st))
                                      [[0xffbf 0] [0xffff 0] [0xff08 g/META-MASK] [0xff08 g/CONTROL-MASK]
                                       [0xffc7 g/SHIFT-MASK] [0xff67 0]])})
          v {:esc esc :passed passed}]
      (on-gtk (try
                (support/check :tree-window-keys-reach-tree
                               (and (= :name (:before-mode esc)) (= 1 (:handled esc))
                                    (false? (:open? (:after esc)))
                                    (:focused passed) (= [0 0 0 0 0 0] (:keys passed)))
                               v)
                (finally (popdown! ui) (restore! ui original)))))))

(defn row-tree-trash-symlink
  "Fix round 1, Critical 1: the menu on a symlink row records the link as
  listed, and Move to Trash on it trashes the link, leaving its target (a
  file outside the project) where it is."
  [ui]
  (go-row :tree-trash-symlink support/check
    (let [original (on-gtk (root ui))
          n (unique-name "link" ".txt")
          target (str (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-selftest-tree-else-"))) "/notes.txt")
          _ (spit target "kept")
          r (on-gtk (let [r (ops-tree!)]
                      (java.nio.file.Files/createSymbolicLink (.toPath (io/file r n)) (.toPath (io/file target))
                                                              (make-array java.nio.file.attribute.FileAttribute 0))
                      (root-ops! ui r)
                      r))
          _ (await-until 2000 20 #(tree-shown? ui))
          link (str r "/" n)
          link-exists? (fn [] (java.nio.file.Files/exists (.toPath (io/file link))
                                                          (into-array java.nio.file.LinkOption [java.nio.file.LinkOption/NOFOLLOW_LINKS])))
          v (on-gtk (try
                      ((:tree-menu!* ui) link)
                      (let [st ((:tree-menu-state* ui))]
                        (popdown! ui)
                        ((:trash-choice! ui) (:listed st) {:index 0})
                        {:listed (:listed st) :target-path (:target st) :link-gone (not (link-exists?))
                         :target-kept (= "kept" (slurp target))})
                      (finally (remove-trashed! n))))]
      (on-gtk (try
                (support/check :tree-trash-symlink
                               (and (= link (:listed v)) (= target (:target-path v)) (:link-gone v) (:target-kept v))
                               v)
                (finally (popdown! ui) (restore! ui original)))))))
