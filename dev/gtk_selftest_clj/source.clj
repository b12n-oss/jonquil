(ns gtk-selftest-clj.source
  "The source panel in a real JVM Clojure window against the fake server:
  F12 on a definition outside the buffer opens it on the right, read-only
  for a jar or a file outside the project, editable through Open in window
  for a project file; F12 inside follows, Cmd/Ctrl+[ goes back, Escape
  closes; the inspector shares the slot.

  Every row is go-style (dev/gtk_async.clj): (fn [ui] ch), one check each.
  Each row starts by closing the panel and setting the editor text it needs."
  (:require [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-clj.rows :as rows]
            [gtk-selftest-window.support :as support]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.source-pane :as source-pane]
            [jonquil.source :as source]))

(def ^:private KEY-ESCAPE 0xff1b)
(def ^:private KEY-O 0x06f)
(def ^:private KEY-BRACKETLEFT 0x05b)

(defn- sp [ui] (:source-pane ui))
(defn- visible? [w] (pos? (g/gtk-widget-get-visible w)))
(defn- panel-line [ui] (inc (first (g/cursor-line-col (:buf (:editor (sp ui)))))))
(defn- header [ui] (g/label-text (:header (sp ui))))
(defn- footer [ui] (g/label-text (:footer (sp ui))))

(defn- jump-on!
  "Closes the panel, sets the editor to text with the caret inside sym, and
  runs the window's own F12 fn. GTK-thread only."
  [ui text sym]
  ((:source-close!* ui))
  (let [ed (:editor ui)]
    (editor/set-text! ed text)
    (support/place-cursor! (:buf ed) (+ 1 (str/index-of text sym)))
    ((:jump!* ui))))

(defn- panel-caret-on!
  "Puts the panel's caret inside the first sym after from in its text."
  [ui sym from]
  (let [ed (:editor (sp ui))
        text (editor/editor-text ed)]
    (support/place-cursor! (:buf ed) (+ 1 (str/index-of text sym (str/index-of text from))))))

(defn- window-key! [ui keyval state] ((:handle-window-key! ui) nil keyval 0 state nil))

(defn row-clj-source-jar
  "F12 on map in (map inc [1]): the panel opens the fixture jar's
  clojure/core.clj on line 20, read-only, Open in window insensitive.

  The row before this one (row-clj-inspect-lost-idle) leaves
  the JVM attach connection dropped on purpose, and ev/lookup never
  reconnects on its own (jonquil.nrepl.jvm's own lookup docstring: \"never
  connecting on its own\") -- so this row reconnects first with a plain
  REPL entry, the same \"(pick)\"-style reconnect row-clj-source-shares-
  slot already uses, before its own F12."
  [ui]
  (go-row :clj-source-jar support/check
    (let [cv (:console ui)
          mark (on-gtk (let [m (support/out-end-offset ui)]
                         (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(+ 1 2)" -1 1)
                         (cview/handle-return! cv false)
                         m))
          connected? (await-until 5000 20 #(and (rows/idle? ui) (str/includes? (support/since ui mark) "=> 3")))]
      (on-gtk (jump-on! ui "(map inc [1])" "map"))
      (let [shown? (await-until 3000 20 #(and (visible? (:root (sp ui))) (visible? (:right-slot ui))))
            v (on-gtk {:header (header ui) :line (panel-line ui)
                       :open (g/gtk-widget-get-sensitive (:open-btn (sp ui)))
                       :focused (source-pane/focused? (sp ui) (:window ui))})]
        (on-gtk (support/check :clj-source-jar
                               (and connected? shown? (= "core.clj in lib.jar  · read-only" (:header v))
                                    (= 20 (:line v)) (= 0 (:open v)) (:focused v))
                               (assoc v :shown shown? :connected connected?)))))))

(defn row-clj-source-own-file
  "greet's lookup names the window's own file (the fixture's core.clj), so
  F12 on a use of greet in a dirty, one-line buffer stays in the buffer:
  status \"Jumped to greet.\", the panel stays hidden, the buffer's text is
  unchanged.

  set-status! defers through on-gui!/g_idle_add even when
  already called from the GTK thread (jonquil.gtk.callbacks/on-gui!'s own
  docstring), so reading the status label in the SAME on-gtk step that
  triggers the jump would still see the PREVIOUS row's own status, never
  this jump's. await-until the status text first, as every other status
  check in this codebase already does, before reading the panel/buffer
  state in a second on-gtk step."
  [ui]
  (go-row :clj-source-own-file support/check
    (let [text "(greet \"x\")"
          _ (on-gtk (jump-on! ui text "greet"))
          jumped? (await-until 2000 20 #(= "Jumped to greet." (g/label-text (:status-label ui))))
          v (on-gtk {:panel (visible? (:root (sp ui))) :text (editor/editor-text (:editor ui))})]
      (on-gtk (support/check :clj-source-own-file
                             (and jumped? (not (:panel v)) (= text (:text v)))
                             (assoc v :jumped jumped?))))))

(defn row-clj-source-jump-to-own-file
  "F12 inside the panel on a use of greet, whose lookup names the window's
  own core.clj, moves the WINDOW's caret to greet's line 4 and focuses the
  window's editor, rather than loading core.clj into the panel: the
  panel keeps util.clj."
  [ui]
  (go-row :clj-source-jump-to-own-file support/check
    (on-gtk (jump-on! ui "(helper2)" "helper2"))
    (let [opened? (await-until 3000 20 #(= "util.clj  · read-only" (header ui)))
          _ (on-gtk (let [ed (:editor ui)]
                      (editor/set-text! ed rows/fixture-core-text)
                      (support/place-cursor! (:buf ed) 0)
                      (panel-caret-on! ui "greet" "(greet")
                      ((:source-jump!* ui))))
          jumped? (await-until 2000 20 #(= "Jumped to greet." (g/label-text (:status-label ui))))
          v (on-gtk {:window-line (inc (first (g/cursor-line-col (:buf (:editor ui)))))
                     :header (header ui)
                     :window-focused (= (:view (:editor ui)) (g/gtk-root-get-focus (:window ui)))})]
      (on-gtk (support/check :clj-source-jump-to-own-file
                             (and opened? jumped? (= 4 (:window-line v)) (= "util.clj  · read-only" (:header v))
                                  (:window-focused v))
                             (assoc v :opened opened? :jumped jumped?))))))

(defn row-clj-source-follow-and-back
  "F12 on helper2 opens util.clj on line 3. In the panel, F12 on helper (line
  4) moves to line 6 in the same file; F12 on outside-fn (line 7) loads
  other/place.clj on line 2, read-only. Cmd/Ctrl+[ comes back to util.clj on
  line 7, then to line 4."
  [ui]
  (go-row :clj-source-follow-and-back support/check
    (on-gtk (jump-on! ui "(helper2)" "helper2"))
    (let [opened? (await-until 3000 20 #(= "util.clj  · read-only" (header ui)))
          first-line (on-gtk (panel-line ui))
          in-file (on-gtk (panel-caret-on! ui "helper" "(helper))")
                          ((:source-jump!* ui))
                          (panel-line ui))
          out (on-gtk (panel-caret-on! ui "outside-fn" "(outside-fn")
                      ((:source-jump!* ui))
                      [(header ui) (panel-line ui)])
          back1 (on-gtk (window-key! ui KEY-BRACKETLEFT g/CONTROL-MASK)
                        [(header ui) (panel-line ui)])
          back2 (on-gtk (window-key! ui KEY-BRACKETLEFT g/CONTROL-MASK)
                        [(header ui) (panel-line ui)])]
      (on-gtk (support/check :clj-source-follow-and-back
                             (and opened? (= 3 first-line) (= 6 in-file)
                                  (= ["place.clj  · read-only" 2] out)
                                  (= ["util.clj  · read-only" 7] back1) (= ["util.clj  · read-only" 4] back2))
                             {:opened opened? :first first-line :in-file in-file :out out
                              :back1 back1 :back2 back2})))))

(defn row-clj-source-project-file-opens-a-tab
  "F12 on proj-helper, defined in the project's own util.clj (line 6),
  opens that file in a new tab with the caret on line 6 and no panel,
  leaving the first tab's dirty text alone.
  Cmd/Ctrl+[ then goes back to the first tab, caret where F12 was pressed.
  The row closes the new tab so later rows see one tab again."
  [ui]
  (go-row :clj-source-project-file-opens-a-tab support/check
    (let [first-id (on-gtk (:id ((:active-tab* ui))))
          _ (on-gtk (jump-on! ui "(proj-helper)" "proj-helper")
                    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf (:editor ui)) " " -1 1))
          first-text (on-gtk (editor/editor-text (:editor ui)))
          opened? (await-until 3000 20 #(= 2 (count ((:tabs* ui)))))
          v (on-gtk (let [t ((:active-tab* ui))]
                      {:tabs (count ((:tabs* ui)))
                       :text (editor/editor-text (:ed t))
                       :line (inc (first (g/cursor-line-col (:buf (:ed t)))))
                       :panel (visible? (:root (sp ui)))
                       :first-kept (= first-text (editor/editor-text (:editor ui)))}))
          back (on-gtk (window-key! ui KEY-BRACKETLEFT g/CONTROL-MASK)
                       (let [t ((:active-tab* ui))]
                         {:same-tab (= first-id (:id t))
                          :caret (g/cursor-offset (:buf (:ed t)))}))
          _ (on-gtk (doseq [t (rest ((:tabs* ui)))]
                      (editor/mark-saved! (:ed t))
                      ((:close-tab! ui) (:id t))))]
      (on-gtk (support/check :clj-source-project-file-opens-a-tab
                             (and opened? (= 2 (:tabs v)) (= rows/util-text (:text v)) (= 6 (:line v))
                                  (not (:panel v)) (:first-kept v) (:same-tab back)
                                  ;; recorded when the lookup answers, which
                                  ;; can be after the row's own space
                                  (contains? #{2 3} (:caret back)))
                             (assoc v :opened opened? :back back))))))

(defn row-clj-source-back-skips-a-closed-untitled-tab
  "F12 on proj-helper from a new Untitled tab opens util.clj in its own
  tab; closing the Untitled tab leaves Cmd/Ctrl+[ nothing to go back to,
  so it says so instead of claiming \"Back.\", and
  leaves util.clj showing. The row closes the tabs it made."
  [ui]
  (go-row :clj-source-back-skips-a-closed-untitled-tab support/check
    (let [scratch-id (on-gtk ((:source-close!* ui))
                             ((:new-tab!* ui))
                             (let [t ((:active-tab* ui)) text "(proj-helper)"]
                               (editor/set-text! (:ed t) text)
                               (support/place-cursor! (:buf (:ed t)) 2)
                               ((:jump! (:actions t)))
                               (:id t)))
          opened? (await-until 3000 20 #(some-> ((:active-tab* ui)) :path deref (str/ends-with? "util.clj")))
          _ (on-gtk (when-let [t (some #(when (= scratch-id (:id %)) %) ((:tabs* ui)))]
                      (editor/mark-saved! (:ed t))
                      ((:close-tab! ui) scratch-id)))
          _ (on-gtk (window-key! ui KEY-BRACKETLEFT g/CONTROL-MASK))
          said? (await-until 2000 20 #(= "Nothing to go back to: those tabs are closed." (g/label-text (:status-label ui))))
          v (on-gtk {:tab (some-> ((:active-tab* ui)) :path deref (str/split #"/") last)
                     :status (g/label-text (:status-label ui))})
          _ (on-gtk (doseq [t (rest ((:tabs* ui)))]
                      (editor/mark-saved! (:ed t))
                      ((:close-tab! ui) (:id t))))]
      (on-gtk (support/check :clj-source-back-skips-a-closed-untitled-tab
                             (and opened? said? (= "util.clj" (:tab v)))
                             (assoc v :opened opened? :said said?))))))

(defn row-clj-source-outside-read-only
  "F12 on outside-fn opens other/place.clj read-only; Cmd/Ctrl+Shift+O says
  why on the footer and leaves the window's buffer alone."
  [ui]
  (go-row :clj-source-outside-read-only support/check
    (on-gtk (jump-on! ui "(outside-fn)" "outside-fn"))
    (let [opened? (await-until 3000 20 #(= "place.clj  · read-only" (header ui)))
          v (on-gtk (source-pane/focus! (sp ui))
                    (window-key! ui KEY-O (bit-or g/CONTROL-MASK g/SHIFT-MASK))
                    {:footer (footer ui) :text (editor/editor-text (:editor ui))
                     :tip (g/tooltip-text (:open-btn (sp ui)))})]
      (on-gtk (support/check :clj-source-outside-read-only
                             (and opened?
                                  (= "This file is outside the project, so it opens read-only." (:footer v))
                                  (= (:footer v) (:tip v))
                                  (= "(outside-fn)" (:text v)))
                             (assoc v :opened opened?))))))

(defn row-clj-source-escape
  "Escape with the panel focused hides the panel and the slot and puts focus
  back in the window's editor."
  [ui]
  (go-row :clj-source-escape support/check
    (on-gtk (jump-on! ui "(map inc [1])" "map"))
    (let [opened? (await-until 3000 20 #(visible? (:root (sp ui))))
          v (on-gtk (source-pane/focus! (sp ui))
                    (window-key! ui KEY-ESCAPE 0)
                    {:panel (visible? (:root (sp ui))) :slot (visible? (:right-slot ui))
                     :focus-editor (= (:view (:editor ui)) (g/gtk-root-get-focus (:window ui)))})]
      (on-gtk (support/check :clj-source-escape
                             (and opened? (not (:panel v)) (not (:slot v)) (:focus-editor v))
                             (assoc v :opened opened?))))))

(defn row-clj-source-missing-entry
  "F12 on ghost, whose jar entry doesn't exist: the panel shows with the
  footer naming the missing entry."
  [ui]
  (go-row :clj-source-missing-entry support/check
    (on-gtk (jump-on! ui "(ghost)" "ghost"))
    (let [ok (await-until 3000 20 #(and (visible? (:root (sp ui)))
                                        (= "nope/ghost.clj isn't in lib.jar." (footer ui))))]
      (on-gtk (support/check :clj-source-missing-entry ok {:footer (footer ui)})))))

(defn row-clj-source-line-moved
  "F12 on late, whose line (999) is past util.clj's end: the caret goes to
  the last line and the footer says the line moved."
  [ui]
  (go-row :clj-source-line-moved support/check
    (on-gtk (jump-on! ui "(late)" "late"))
    (let [ok (await-until 3000 20 #(= source/moved-note (footer ui)))
          line (on-gtk (panel-line ui))]
      (on-gtk (support/check :clj-source-line-moved
                             (and ok (= (count (str/split-lines (str rows/util-text "x"))) line))
                             {:footer (footer ui) :line line})))))

(defn row-clj-source-read-only
  "In the panel, typing, Return and Backspace change nothing, the buffer has
  no undo step, and the window's own buffer isn't marked dirty."
  [ui]
  (go-row :clj-source-read-only support/check
    (on-gtk (jump-on! ui "(map inc [1])" "map")
            (editor/mark-saved! (:editor ui)))
    (let [opened? (await-until 3000 20 #(visible? (:root (sp ui))))
          v (on-gtk (let [ped (:editor (sp ui))
                          before (editor/editor-text ped)]
                      (g/gtk-text-buffer-insert-interactive-at-cursor (:buf ped) "zz" -1 1)
                      (editor/dispatch-key! ped 0xff0d 0)
                      (editor/dispatch-key! ped 0xff08 0)
                      {:same (= before (editor/editor-text ped))
                       :undo (g/gtk-text-buffer-get-can-undo (:buf ped))
                       :window-dirty (editor/dirty? (:editor ui))}))]
      (on-gtk (support/check :clj-source-read-only
                             (and opened? (:same v) (= 0 (:undo v)) (false? (:window-dirty v)))
                             (assoc v :opened opened?))))))

(defn row-clj-source-shares-slot
  "One slot, last wins: with the inspector showing (Cmd/Ctrl+I after a REPL
  entry), F12 replaces it with the panel; Cmd/Ctrl+I brings the inspector
  back and hides the panel, which keeps its location."
  [ui]
  (go-row :clj-source-shares-slot support/check
    (let [cv (:console ui)
          mark (on-gtk (let [m (support/out-end-offset ui)]
                         (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(pick)" -1 1)
                         (cview/handle-return! cv false)
                         m))
          evaled? (await-until 5000 20 #(and (rows/idle? ui) (str/includes? (support/since ui mark) "=> {:k 42}")))
          _ (on-gtk ((:source-close!* ui)) ((:inspect-last!* ui)))
          ;; Wait for the first view to land: a second Cmd/Ctrl+I while a
          ;; request is in flight is dropped on purpose.
          inspector? (await-until 3000 20 #(and (visible? (:root (:inspector ui)))
                                                (= "*1" (g/label-text (:header (:inspector ui))))))
          _ (on-gtk (jump-on! ui "(map inc [1])" "map"))
          swapped? (await-until 3000 20 #(and (visible? (:root (sp ui))) (not (visible? (:root (:inspector ui))))))
          _ (on-gtk ((:inspect-last!* ui)))
          back? (await-until 3000 20 #(and (visible? (:root (:inspector ui))) (not (visible? (:root (sp ui))))))
          kept (on-gtk (header ui))]
      (on-gtk (support/check :clj-source-shares-slot
                             (and evaled? inspector? swapped? back? (= "core.clj in lib.jar  · read-only" kept))
                             {:evaled evaled? :inspector inspector? :swapped swapped? :back back? :kept kept})))))
