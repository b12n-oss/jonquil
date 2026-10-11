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
