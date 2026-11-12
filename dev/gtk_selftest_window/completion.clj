(ns gtk-selftest-window.completion
  "Completion with a doc preview, in both the editor and the REPL input."
  (:require [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jonquil.docs :as docs]
            [jonquil.eval :as eval]
            [jonquil.gtk.completion :as completion]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.popover :as popover]))

;; This file's own private copies (measured against gdkkeysyms.h,
;; same values jonquil.gtk.completion's own KEY-UP/KEY-DOWN/KEY-TAB use;
;; KEY-ESCAPE/KEY-RETURN above already cover Escape/Enter for this file).
(def ^:private KEY-SPACE 0x020)
(def ^:private KEY-UP 0xff52)
(def ^:private KEY-DOWN 0xff54)
(def ^:private KEY-TAB 0xff09)

;; This file's own private copies (measured against gdkkeysyms.h) for
;; the keys the completion popup must never swallow --
;; row-completion-keys-reach-editor/-closes-on-space and their console
;; counterparts below.
(def ^:private KEY-P 0x070)
(def ^:private KEY-BACKSPACE 0xff08)
(def ^:private KEY-LEFT 0xff51)

;; --- completion with a doc preview ----------------------------------

(defn row-completion-opens
  "\"(ma\", caret 3, :complete!*: the popover opens and its rows equal
  (mapv :symbol (docs/candidates ns {:prefix \"ma\" :text \"(ma\" :pos 3})) in order (measured
  live: the first is macroexpand), and the preview contains
  clojure.core/<first row>."
  [ui]
  (go-row :completion-opens support/check
    (on-gtk (let [ed (:editor ui) c (:completion ui) ns-sym (:prompt-ns @(:state (:session ui)))]
              (editor/set-text! ed "(ma")
              (support/place-cursor! (:buf ed) 3)
              ((:complete!* ui))
              (let [st (completion/state c)
                    expected (mapv :symbol (docs/candidates ns-sym {:prefix "ma" :text "(ma" :pos 3}))
                    rows (mapv :symbol (:candidates st))
                    preview (g/label-text (:doc-label c))]
                (support/check :completion-opens
                               (and (some? st) (seq rows) (= expected rows)
                                    (str/includes? preview (str "clojure.core/" (first rows))))
                               {:expected expected :rows rows :preview preview})
                (completion/close! c))))))

(defn row-completion-single-inserts
  "\"(mapca\", caret 6, Ctrl+Space: docs/candidates for \"mapca\" is exactly
  [mapcat] (measured live), so it commits at once -- text \"(mapcat\", no
  popover."
  [ui]
  (go-row :completion-single-inserts support/check
    (on-gtk (let [ed (:editor ui) c (:completion ui)]
              (editor/set-text! ed "(mapca")
              (support/place-cursor! (:buf ed) 6)
              (let [handled (editor/handle-key! ed KEY-SPACE g/CONTROL-MASK)]
                (support/check :completion-single-inserts
                               (and (= 1 handled) (nil? (completion/state c)) (= "(mapcat" (editor/editor-text ed)))
                               {:handled handled :state (completion/state c) :text (editor/editor-text ed)}))))))

(defn row-completion-no-prefix
  "\"( \", caret 2, Ctrl+Space: help/prefix-at is nil (the char before the
  caret is a space, not a symbol char) -- returns 1 so GTK never inserts a
  literal space, nothing changes, no popover."
  [ui]
  (go-row :completion-no-prefix support/check
    (on-gtk (let [ed (:editor ui) c (:completion ui)]
              (editor/set-text! ed "( ")
              (support/place-cursor! (:buf ed) 2)
              (let [handled (editor/handle-key! ed KEY-SPACE g/CONTROL-MASK)]
                (support/check :completion-no-prefix
                               (and (= 1 handled) (= "( " (editor/editor-text ed)) (nil? (completion/state c)))
                               {:handled handled :text (editor/editor-text ed) :state (completion/state c)}))))))

(defn row-completion-navigate-commit
  "Open on \"(ma\", Down, Enter: the selection moves to index 1, Enter
  commits the SECOND candidate (apply-op!, one undo step) -- text is
  \"(\" + that candidate's own symbol, and one gtk_text_buffer_undo gives
  back \"(ma\"."
  [ui]
  (go-row :completion-navigate-commit support/check
    (on-gtk (let [ed (:editor ui) c (:completion ui)]
              (editor/set-text! ed "(ma")
              (support/place-cursor! (:buf ed) 3)
              (editor/handle-key! ed KEY-SPACE g/CONTROL-MASK)
              (let [st0 (completion/state c)
                    second-sym (:symbol (nth (:candidates st0) 1))
                    h1 (editor/handle-key! ed KEY-DOWN 0)
                    st1 (completion/state c)
                    h2 (editor/handle-key! ed support/KEY-RETURN 0)
                    text (editor/editor-text ed)]
                (g/gtk-text-buffer-undo (:buf ed))
                (let [text-after-undo (editor/editor-text ed)]
                  (support/check :completion-navigate-commit
                                 (and (some? st0) (>= (count (:candidates st0)) 2)
                                      (= 1 h1) (some? st1) (= 1 (:index st1))
                                      (= 1 h2) (nil? (completion/state c))
                                      (= (str "(" second-sym) text)
                                      (= "(ma" text-after-undo))
                                 {:candidate-count (count (:candidates st0)) :second-sym second-sym
                                  :h1 h1 :index1 (:index st1) :h2 h2 :text text :text-after-undo text-after-undo})))))))

(defn row-completion-arrow-keeps-rows
  "Open on \"(ma\", Down twice: the GtkListBoxRow at index 0 is the same
  pointer as before (arrows select, they don't rebuild the list), the
  selected index is 2 and the doc label names candidate 2. Rows 0 and 1
  must compare unequal, and row 0 is held with g_object_ref while the keys
  run: without the ref a rebuild can hand the new row 0 the freed row's
  address, and the pointer `=` check passes against the old code."
  [ui]
  (go-row :completion-arrow-keeps-rows support/check
    (on-gtk (let [ed (:editor ui) c (:completion ui) lb (:list-box c)]
              (editor/set-text! ed "(ma")
              (support/place-cursor! (:buf ed) 3)
              (editor/handle-key! ed KEY-SPACE g/CONTROL-MASK)
              (let [st0 (completion/state c)
                    row0 (g/gtk-list-box-get-row-at-index lb 0)
                    row1 (g/gtk-list-box-get-row-at-index lb 1)
                    _ (g/g-object-ref row0)
                    h1 (editor/handle-key! ed KEY-DOWN 0)
                    h2 (editor/handle-key! ed KEY-DOWN 0)
                    st2 (completion/state c)
                    third-sym (some-> st0 :candidates (nth 2 nil) :symbol)
                    row0-after (g/gtk-list-box-get-row-at-index lb 0)
                    selected (g/gtk-list-box-get-selected-row lb)
                    row2 (g/gtk-list-box-get-row-at-index lb 2)
                    preview (g/label-text (:doc-label c))]
                (g/g-object-unref row0)
                (support/check :completion-arrow-keeps-rows
                               (and (some? st0) (>= (count (:candidates st0)) 3)
                                    (not= row0 row1)
                                    (= 1 h1) (= 1 h2) (= 2 (:index st2))
                                    (= row0 row0-after) (= selected row2)
                                    (str/includes? preview (str "clojure.core/" third-sym)))
                               {:candidate-count (count (:candidates st0)) :h1 h1 :h2 h2
                                :index (:index st2) :row-distinct (not= row0 row1)
                                :row0-kept (= row0 row0-after) :third-sym third-sym :preview preview})
                (completion/close! c))))))

(defn row-completion-tab-commits
  "Open, Tab: commits the selected (first) candidate the same way Enter
  does, and closes the popover."
  [ui]
  (go-row :completion-tab-commits support/check
    (on-gtk (let [ed (:editor ui) c (:completion ui)]
              (editor/set-text! ed "(ma")
              (support/place-cursor! (:buf ed) 3)
              (editor/handle-key! ed KEY-SPACE g/CONTROL-MASK)
              (let [opened? (some? (completion/state c))
                    h (editor/handle-key! ed KEY-TAB 0)]
                (support/check :completion-tab-commits
                               (and opened? (= 1 h) (nil? (completion/state c)) (not= "(ma" (editor/editor-text ed)))
                               {:opened opened? :h h :text (editor/editor-text ed)}))))))

(defn row-completion-keys-reach-editor
  "Open on \"(ma\": p, Backspace and Left must never be swallowed by the
  popup (inserting \"p\" with insert_interactive_at_cursor alone would
  bypass editor/handle-key! entirely, and pass even if
  completion/handle-key! returned 1 for every one of these). completion/handle-key! itself is checked directly
  first and must return 0 for all three (none of Up/Down/PageUp/PageDown/
  Return/KP-Enter/Tab), then editor/handle-key! -- the view's real dispatch
  -- is called for real and must agree: 0 for every one of them, since
  editor.clj has no clause for a bare letter or for Left, and
  jonquil.edit/backspace-pair returns nil whenever the caret sits at the
  buffer's end (see backspace-interactive!'s own docstring), so its own
  KEY-BACKSPACE clause does nothing there either. A bare handle-key! call
  never triggers GTK's own default handling underneath a 0 return (there is
  no real key event driving it), so p's insert and Backspace's delete are
  still reproduced with insert/delete-interactive, the same stand-ins the
  rest of this file uses for GTK's default -- gated on the checks
  above. The popover stays open and refines through
  both (still-valid?'s own prefix-start check holds for \"map\" then back to
  \"ma\"). Left has no observable buffer effect to check beyond its own 0
  return -- a bare call doesn't move the caret either."
  [ui]
  (go-row :completion-keys-reach-editor support/check
    (on-gtk (let [ed (:editor ui) c (:completion ui) ns-sym (:prompt-ns @(:state (:session ui)))]
              (editor/set-text! ed "(ma")
              (support/place-cursor! (:buf ed) 3)
              (editor/handle-key! ed KEY-SPACE g/CONTROL-MASK)
              (let [opened? (some? (completion/state c))
                    p-swallowed (completion/handle-key! c KEY-P 0)
                    p-handled (editor/handle-key! ed KEY-P 0)]
                (g/gtk-text-buffer-insert-interactive-at-cursor (:buf ed) "p" -1 1)
                (let [text-p (editor/editor-text ed)
                      st-p (completion/state c)
                      expected-p (mapv :symbol (docs/candidates ns-sym {:prefix "map" :text "(map" :pos 4}))
                      rows-p (mapv :symbol (:candidates st-p))
                      bs-swallowed (completion/handle-key! c KEY-BACKSPACE 0)
                      bs-handled (editor/handle-key! ed KEY-BACKSPACE 0)]
                  (support/backspace-interactive! (:buf ed) (g/cursor-offset (:buf ed)))
                  (let [text-bs (editor/editor-text ed)
                        st-bs (completion/state c)
                        expected-bs (mapv :symbol (docs/candidates ns-sym {:prefix "ma" :text "(ma" :pos 3}))
                        rows-bs (mapv :symbol (:candidates st-bs))
                        left-swallowed (completion/handle-key! c KEY-LEFT 0)
                        left-handled (editor/handle-key! ed KEY-LEFT 0)]
                    (support/check :completion-keys-reach-editor
                                   (and opened?
                                        (= 0 p-swallowed) (= 0 p-handled)
                                        (= "(map" text-p) (some? st-p) (= expected-p rows-p)
                                        (= 0 bs-swallowed) (= 0 bs-handled)
                                        (= "(ma" text-bs) (some? st-bs) (= expected-bs rows-bs)
                                        (= 0 left-swallowed) (= 0 left-handled))
                                   {:opened opened? :p-swallowed p-swallowed :p-handled p-handled
                                    :text-p text-p :expected-p expected-p :rows-p rows-p
                                    :bs-swallowed bs-swallowed :bs-handled bs-handled
                                    :text-bs text-bs :expected-bs expected-bs :rows-bs rows-bs
                                    :left-swallowed left-swallowed :left-handled left-handled})
                    (completion/close! c))))))))

(defn row-completion-closes-on-space
  "Open, then completion/handle-key! and editor/handle-key! must both
  return 0 for a plain space (the same check row-completion-keys-
  reach-editor makes for p/Backspace/Left -- the editor keymap's
  Ctrl+Space binding needs Control, so a bare space, mask 0, misses it). Only then insert a
  space with insert_interactive_at_cursor: help/prefix-at is nil right
  after the space, so refine! closes the popover -- no poll, same
  synchronous reasoning as completion-keys-reach-editor."
  [ui]
  (go-row :completion-closes-on-space support/check
    (on-gtk (let [ed (:editor ui) c (:completion ui)]
              (editor/set-text! ed "(ma")
              (support/place-cursor! (:buf ed) 3)
              (editor/handle-key! ed KEY-SPACE g/CONTROL-MASK)
              (let [opened? (some? (completion/state c))
                    swallowed (completion/handle-key! c KEY-SPACE 0)
                    handled (editor/handle-key! ed KEY-SPACE 0)]
                (g/gtk-text-buffer-insert-interactive-at-cursor (:buf ed) " " -1 1)
                (support/check :completion-closes-on-space
                               (and opened? (= 0 swallowed) (= 0 handled) (nil? (completion/state c)))
                               {:opened opened? :swallowed swallowed :handled handled
                                :still-open (some? (completion/state c))}))))))

(defn row-completion-escape-while-running
  "Runs a real (loop [] (recur)), opens completion on \"l\" (many
  candidates), sends window Escape -- the SAME registry-based clause
  row-window-escape-closes-popover already proves for a test popover, now
  exercised through the real completion popover: the popup closes and the
  program keeps running; a second Escape (nothing left open) then falls
  through to the evaluating? clause and Stops it, same as that row."
  [ui]
  (go-row :completion-escape-while-running support/check
    (let [ed (:editor ui) c (:completion ui)
          _ (on-gtk (editor/set-text! ed "(loop [] (recur))")
                    (support/place-cursor! (:buf ed) 2)
                    ((:run!* ui)))
          started? (await-until 2000 20 #(eval/evaluating? (:ctx (:session ui))))
          {:keys [opened? mark h1 closed? still-running-1? h2]}
          (on-gtk (editor/handle-key! ed KEY-SPACE g/CONTROL-MASK)
                  (let [opened? (some? (completion/state c))
                        mark (support/out-end-offset ui)
                        h1 ((:handle-window-key! ui) nil support/KEY-ESCAPE 0 0 nil)
                        closed? (nil? (completion/state c))
                        still-running-1? (eval/evaluating? (:ctx (:session ui)))
                        h2 ((:handle-window-key! ui) nil support/KEY-ESCAPE 0 0 nil)]
                    {:opened? opened? :mark mark :h1 h1 :closed? closed?
                     :still-running-1? still-running-1? :h2 h2}))
          stopped? (await-until 3000 20 #(str/includes? (support/since ui mark) "; interrupted"))]
      (support/check :completion-escape-while-running
                     (and started? opened? (= 1 h1) closed? still-running-1?
                          (= 1 h2) stopped?)
                     {:started started? :opened opened? :h1 h1 :closed closed?
                      :still-running-1 still-running-1? :h2 h2 :stopped stopped?})
      (on-gtk (completion/close! c)))))

(defn row-completion-cmd-space-nothing
  "0x20 with META only: the Ctrl+Space binding rejects it (CONTROL-MASK checked,
  META-MASK rejected), so the chord falls through unhandled
  and no popover opens."
  [ui]
  (go-row :completion-cmd-space-nothing support/check
    (on-gtk (let [ed (:editor ui) c (:completion ui)]
              (editor/set-text! ed "(ma")
              (support/place-cursor! (:buf ed) 3)
              (let [handled (editor/handle-key! ed KEY-SPACE g/META-MASK)]
                (support/check :completion-cmd-space-nothing
                               (and (= 0 handled) (nil? (completion/state c)))
                               {:handled handled :state (completion/state c)}))))))

(defn row-completion-preedit-yields
  "With ed's own preedit-active? flag set (the same flag editor.clj's
  handle-key! checks first for every key), Ctrl+Space returns 0 and opens
  nothing."
  [ui]
  (go-row :completion-preedit-yields support/check
    (on-gtk (let [ed (:editor ui) c (:completion ui)]
              (editor/set-text! ed "(ma")
              (support/place-cursor! (:buf ed) 3)
              (reset! (:preedit-active? ed) true)
              (let [handled (editor/handle-key! ed KEY-SPACE g/CONTROL-MASK)]
                (reset! (:preedit-active? ed) false)
                (support/check :completion-preedit-yields
                               (and (= 0 handled) (nil? (completion/state c)))
                               {:handled handled}))))))

(defn row-completion-closes-on-focus-loss
  "Calls every fn registered on ed's own :on-focus-fns directly -- the
  SAME ones GtkEventControllerFocus's \"leave\" signal calls --
  mirroring row-quick-doc-closes-on-focus-loss, since this self-test
  window is never the OS's key window and can't move real focus
  (gtk_widget_has_focus depends on the OS window being active)."
  [ui]
  (go-row :completion-closes-on-focus-loss support/check
    (on-gtk (let [ed (:editor ui) c (:completion ui)]
              (editor/set-text! ed "(ma")
              (support/place-cursor! (:buf ed) 3)
              ((:complete!* ui))
              (let [opened? (some? (completion/state c))]
                (doseq [f @(:on-focus-fns ed)] (f))
                (support/check :completion-closes-on-focus-loss
                               (and opened? (nil? (completion/state c)))
                               {:opened opened? :still-open (some? (completion/state c))}))))))

(defn row-completion-closes-other-popover
  "Opening completion while quick doc is showing closes quick doc, and
  opening quick doc while completion is showing closes completion --
  both through close-popovers-fn (the shared popover registry), never a pairwise
  special case naming the other popover."
  [ui]
  (go-row :completion-closes-other-popover support/check
    (on-gtk (let [ed (:editor ui) c (:completion ui) hp (:help-popover ui)]
              (editor/set-text! ed "(map inc [1])")
              (support/place-cursor! (:buf ed) 2)
              (editor/handle-key! ed support/KEY-Q g/CONTROL-MASK)
              (let [quick-doc-open-1 (popover/open? hp)]
                ((:complete!* ui))
                (let [completion-open-1 (some? (completion/state c))
                      quick-doc-closed-by-completion (not (popover/open? hp))]
                  (completion/close! c)
                  (editor/handle-key! ed support/KEY-Q g/CONTROL-MASK)
                  (let [quick-doc-open-2 (popover/open? hp)]
                    ((:complete!* ui))
                    (let [completion-open-2 (some? (completion/state c))
                          quick-doc-closed-by-completion-2 (not (popover/open? hp))]
                      (support/check :completion-closes-other-popover
                                     (and quick-doc-open-1 completion-open-1 quick-doc-closed-by-completion
                                          quick-doc-open-2 completion-open-2 quick-doc-closed-by-completion-2)
                                     {:quick-doc-open-1 quick-doc-open-1 :completion-open-1 completion-open-1
                                      :quick-doc-closed-by-completion quick-doc-closed-by-completion
                                      :quick-doc-open-2 quick-doc-open-2 :completion-open-2 completion-open-2
                                      :quick-doc-closed-by-completion-2 quick-doc-closed-by-completion-2})
                      (popover/hide! hp)
                      (completion/close! c)))))))))

(defn row-completion-commit-throw-consumes-key
  "Forces jonquil.gtk.editor/apply-op! to throw for the duration of one
  Enter, through commit!'s own call to it -- with-redefs on that var
  DOES reach the call completion.clj makes through its own `editor`
  alias (without completion/handle-key!'s own catch, this exact
  with-redefs, run through editor/handle-key!, crashes the whole selftest
  process: jonquil.gtk.window.help's :handle-key! wrapper catches the
  first throw and
  returns 0, so editor.clj's own handle-key! falls through to
  plain-return? -> handle-return!, whose OWN apply-op! call has no
  try/catch and hits the SAME redefined throw uncaught). With its own
  catch, completion/handle-key! itself catches it and still returns 1:
  the buffer keeps its pre-commit text (no newline, no second edit), the
  popover is closed, and the status bar shows the error."
  [ui]
  (go-row :completion-commit-throw-consumes-key support/check
    (let [ed (:editor ui) c (:completion ui) status (:status-label ui)
          {:keys [opened? handled text closed?]}
          (on-gtk (editor/set-text! ed "(ma")
                  (support/place-cursor! (:buf ed) 3)
                  (editor/handle-key! ed KEY-SPACE g/CONTROL-MASK)
                  (let [opened? (some? (completion/state c))
                        ;; with-redefs and the call it covers stay in one
                        ;; on-gtk, so the redef is live only for that call.
                        handled (with-redefs [editor/apply-op! (fn [& _] (throw (ex-info "forced commit failure" {})))]
                                  (editor/handle-key! ed support/KEY-RETURN 0))
                        text (editor/editor-text ed)
                        closed? (nil? (completion/state c))]
                    {:opened? opened? :handled handled :text text :closed? closed?}))
          status-ok? (await-until 1000 20 #(str/includes? (g/label-text status) "forced commit failure"))]
      (on-gtk (support/check :completion-commit-throw-consumes-key
                             (and opened? (= 1 handled) (= "(ma" text) closed? status-ok?)
                             {:opened opened? :handled handled :text text :closed closed?
                              :status (g/label-text status)})))))

(defn row-plain-return-apply-op-throw-contained
  "Forces jonquil.gtk.editor/apply-op! to throw for the duration of one
  plain Return, through handle-return!'s own call to it -- the same
  with-redefs override row-completion-commit-throw-consumes-key uses above.
  Calls editor/dispatch-key!, the exact fn on-key calls (never handle-key!
  directly, which has no try/catch of its own): the process survives, the
  key is consumed (1), the buffer keeps its pre-Return text, and the
  status bar shows the error."
  [ui]
  (go-row :plain-return-apply-op-throw-contained support/check
    (let [ed (:editor ui) status (:status-label ui)
          [handled text]
          (on-gtk (editor/set-text! ed "(+ 1 2)")
                  (support/place-cursor! (:buf ed) 3)
                  ;; with-redefs and the call it covers stay in one on-gtk,
                  ;; so the redef is live only for that call.
                  (let [handled (with-redefs [editor/apply-op! (fn [& _] (throw (ex-info "forced return failure" {})))]
                                  (editor/dispatch-key! ed support/KEY-RETURN 0))
                        text (editor/editor-text ed)]
                    [handled text]))
          status-ok? (await-until 1000 20 #(str/includes? (g/label-text status) "forced return failure"))]
      (on-gtk (support/check :plain-return-apply-op-throw-contained
                             (and (= 1 handled) (= "(+ 1 2)" text) status-ok?)
                             {:handled handled :text text :status (g/label-text status)})))))

;; --- completion in the REPL input -----------------------------------

(defn row-repl-completion-opens
  "\"(ma\" in the console input, Ctrl+Space: the popover opens above the
  input and its rows equal (mapv :symbol (docs/candidates ns {:prefix \"ma\" :text \"(ma\"
  :pos 3 :extra-context history})) in order -- history being the exact REPL-history join do-
  repl-complete! computes, captured here at the same moment so this stays
  correct no matter how much earlier rows already left in the session's
  history (measured live: none of it starts with \"ma\", so the rows
  match what row-completion-opens measures for the editor -- first is
  macroexpand -- regardless)."
  [ui]
  (go-row :repl-completion-opens support/check
    (on-gtk (let [cv (:console ui) c (:repl-completion ui)
                  ns-sym (:prompt-ns @(:state (:session ui)))
                  history (str/join "\n" (:items @(:history (:ctx (:session ui)))))]
              (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(ma" -1 1)
              ((:repl-complete!* ui))
              (let [st (completion/state c)
                    expected (mapv :symbol (docs/candidates ns-sym {:prefix "ma" :text "(ma" :pos 3 :extra-context history}))
                    rows (mapv :symbol (:candidates st))
                    preview (g/label-text (:doc-label c))]
                (support/check :repl-completion-opens
                               (and (some? st) (popover/open? c) (seq rows) (= expected rows)
                                    (str/includes? preview (str "clojure.core/" (first rows))))
                               {:expected expected :rows rows :preview preview})
                (completion/close! c))))))

(defn row-repl-completion-precedence
  "With history containing \"(inc 1)\", open on \"(ma\", Down, Enter, all
  through cview/handle-key! -- the SAME precedence-ordered dispatch the
  real key controller uses, never the raw actions, so a regression that
  let Down browse history or Enter evaluate would show up here exactly as
  it would for a real keystroke. Enter must commit the SECOND candidate
  (apply-input-op!, one undo step), never evaluate \"(ma\" (unbalanced,
  so console/input-action would otherwise insert a newline, not evaluate
  it -- checked anyway, since nothing here is actually evaluated) and
  never browse history (the history atom itself must come out exactly as
  history-add left it)."
  [ui]
  (go-row :repl-completion-precedence support/check
    (on-gtk (let [cv (:console ui) c (:repl-completion ui)
                  ctx (:ctx (:session ui))
                  mark (support/out-end-offset ui)]
              (swap! (:history ctx) eval/history-add "(inc 1)")
              (let [hist-before @(:history ctx)]
                (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(ma" -1 1)
                (let [h0 (cview/handle-key! cv KEY-SPACE g/CONTROL-MASK)
                      st0 (completion/state c)
                      second-sym (:symbol (nth (:candidates st0) 1))
                      h1 (cview/handle-key! cv KEY-DOWN 0)
                      st1 (completion/state c)
                      h2 (cview/handle-key! cv support/KEY-RETURN 0)
                      input (cview/input-text cv)
                      hist-after @(:history ctx)]
                  (support/check :repl-completion-precedence
                         ;; mark = out-end-offset ui unchanged proves nothing was
                         ;; evaluated: commit-input! (the ONLY thing that moves
                         ;; out-end) only ever runs from a real :eval/:send-stdin,
                         ;; never from completion's own commit (since's own "plus
                         ;; the live prompt/input tail" caveat means `(since ui
                         ;; mark)` is never actually "" -- it always includes that
                         ;; tail -- so out-end-offset is the precise check here).
                                 (and (= 1 h0) (some? st0) (>= (count (:candidates st0)) 2)
                                      (= 1 h1) (some? st1) (= 1 (:index st1))
                                      (= 1 h2) (nil? (completion/state c))
                                      (= (str "(" second-sym) input)
                                      (= hist-before hist-after)
                                      (= mark (support/out-end-offset ui)))
                                 {:h0 h0 :second-sym second-sym :h1 h1 :index1 (:index st1) :h2 h2
                                  :input input :hist-before hist-before :hist-after hist-after
                                  :out-end-before mark :out-end-after (support/out-end-offset ui)
                                  :console (support/since ui mark)})))))))

(defn row-repl-history-after-close
  "Open on \"(ma\", Escape (via (:close-popovers!* ui), the SAME registry
  path a real Escape always uses, never completion/close! directly), then
  Up: the popup is really closed (state nil, not left stale-open) and Up
  browses history normally again -- the input becomes the last history
  entry, not a completion navigation."
  [ui]
  (go-row :repl-history-after-close support/check
    (on-gtk (let [cv (:console ui) c (:repl-completion ui)
                  ctx (:ctx (:session ui))]
              (swap! (:history ctx) eval/history-add "(inc 1)")
              (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(ma" -1 1)
              (cview/handle-key! cv KEY-SPACE g/CONTROL-MASK)
              (let [opened? (some? (completion/state c))
                    escaped ((:close-popovers!* ui))
                    closed? (nil? (completion/state c))
                    h (cview/handle-key! cv KEY-UP 0)
                    input (cview/input-text cv)]
                (support/check :repl-history-after-close
                               (and opened? escaped closed? (= 1 h) (= "(inc 1)" input))
                               {:opened opened? :escaped escaped :closed closed? :h h :input input}))))))

(defn row-repl-completion-history-symbols
  "The ORIGINAL version of this row submitted \"(def zork 1)\" as a real
  REPL entry, which interns zork in `user` -- syntax/get-autocomplete-
  candidates has its own ns-publics source, entirely independent of
  :extra-context, so that row passed even with extra-context wired to a
  constant nil (measured: (docs/candidates 'user {:prefix \"zo\" :text \"(zo\" :pos 3}) =>
  [\"zork\"]). It proved candidates come from the running namespace, not
  that history feeds docs/candidates at all.

  This version puts the symbol ONLY in history text, never evaluated:
  eval/history-add (the exact fn repl-history-context's own :items read,
  and submit-repl!'s own write path) records \"(let [wobbuquux 42]
  wobbuquux)\" straight on the session's history atom, the same shape/API
  repl-history-context reads. wobbuquux is never bound anywhere real, so
  ns-publics can't find it either -- only jonquil.syntax's own extra-
  context tokenizing can (measured: with that string as extra-context,
  docs/candidates for \"wobb\"/\"(wobb\"/5 is exactly [wobbuquux]; with nil
  extra-context it's [] and open! does nothing at all, not even a single-
  candidate commit). \"(wobb\" + Ctrl+Space (through cview/handle-key!,
  the real dispatch) then commits it at once (help/open-state's own
  :single? branch, one candidate, no popover) to \"(wobbuquux\" -- a
  result :extra-context alone can produce.

  Captures the history atom's value before adding the fabricated entry
  and resets it back verbatim afterward (not just popping the one item --
  simpler, and correct regardless of what :index/:draft eval/history-add
  itself touched), so later rows (repl-history-after-close in particular)
  see the same history they would have without this row ever running."
  [ui]
  (go-row :repl-completion-history-symbols support/check
    (on-gtk (let [cv (:console ui) c (:repl-completion ui)
                  ctx (:ctx (:session ui))
                  hist-before @(:history ctx)]
              (swap! (:history ctx) eval/history-add "(let [wobbuquux 42] wobbuquux)")
              (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(wobb" -1 1)
              (let [handled (cview/handle-key! cv KEY-SPACE g/CONTROL-MASK)
                    input (cview/input-text cv)
                    st (completion/state c)]
                (reset! (:history ctx) hist-before)
                (support/check :repl-completion-history-symbols
                               (and (= 1 handled) (nil? st) (= "(wobbuquux" input))
                               {:handled handled :input input :state st}))))))

(defn row-repl-completion-outside-input
  "Caret placed at offset 0 -- always before input-start (input-snapshot's
  own docstring: input-start starts right after the initial
  \"user=> \" prompt, and only ever moves further right as output/prompt accumulate)
  -- Ctrl+Space through the SAME cview/handle-key! dispatch: returns 0 and
  opens nothing, input and output both untouched. Restores focus (cview/
  focus!, back to the end of the input) before returning, the same way
  every other row here leaves the console ready for the next one -- a
  caret left outside the input would silently fail every later row's own
  Ctrl+Space too, for the wrong reason."
  [ui]
  (go-row :repl-completion-outside-input support/check
    (on-gtk (let [cv (:console ui) c (:repl-completion ui)
                  mark (support/out-end-offset ui)
                  input-before (cview/input-text cv)]
              (support/place-cursor! (:buf cv) 0)
              (let [handled (cview/handle-key! cv KEY-SPACE g/CONTROL-MASK)]
                (support/check :repl-completion-outside-input
                               (and (= 0 handled) (nil? (completion/state c))
                                    (= input-before (cview/input-text cv)) (= mark (support/out-end-offset ui)))
                               {:handled handled :state (completion/state c)
                                :input-unchanged (= input-before (cview/input-text cv))})
                (cview/focus! cv))))))

(defn row-repl-completion-cmd-space-nothing
  "0x20 with META only, caret in the input: control-only? rejects it
  (CONTROL-MASK checked, META-MASK rejected, the same rule the editor's
  own completion chord follows), so the chord falls through unhandled and
  no popover opens."
  [ui]
  (go-row :repl-completion-cmd-space-nothing support/check
    (on-gtk (let [cv (:console ui) c (:repl-completion ui)]
              (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(ma" -1 1)
              (let [handled (cview/handle-key! cv KEY-SPACE g/META-MASK)]
                (support/check :repl-completion-cmd-space-nothing
                               (and (= 0 handled) (nil? (completion/state c)))
                               {:handled handled :state (completion/state c)}))))))

(defn row-repl-completion-preedit-yields
  "With cv's own preedit-active? flag set (the same flag console-
  view/handle-key!'s Ctrl+Space clause checks), Ctrl+Space returns 0 and
  opens nothing."
  [ui]
  (go-row :repl-completion-preedit-yields support/check
    (on-gtk (let [cv (:console ui) c (:repl-completion ui)]
              (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(ma" -1 1)
              (reset! (:preedit-active? cv) true)
              (let [handled (cview/handle-key! cv KEY-SPACE g/CONTROL-MASK)]
                (reset! (:preedit-active? cv) false)
                (support/check :repl-completion-preedit-yields
                               (and (= 0 handled) (nil? (completion/state c)))
                               {:handled handled}))))))

(defn row-repl-completion-closes-on-focus-loss
  "Calls every fn registered on cv's own :on-focus-fns directly -- the
  SAME ones GtkEventControllerFocus's \"leave\" signal calls --
  mirroring row-completion-closes-on-focus-loss, since this self-test
  window is never the OS's key window and can't move real focus
  (gtk_widget_has_focus depends on the OS window being active)."
  [ui]
  (go-row :repl-completion-closes-on-focus-loss support/check
    (on-gtk (let [cv (:console ui) c (:repl-completion ui)]
              (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(ma" -1 1)
              ((:repl-complete!* ui))
              (let [opened? (some? (completion/state c))]
                (doseq [f @(:on-focus-fns cv)] (f))
                (support/check :repl-completion-closes-on-focus-loss
                               (and opened? (nil? (completion/state c)))
                               {:opened opened? :still-open (some? (completion/state c))}))))))

(defn row-repl-completion-keys-reach-console
  "Same reasoning as row-completion-keys-reach-editor, through the
  console's own cview/handle-key! (the REPL popup must not swallow
  keys the input needs either). Open on \"(ma\" in the input: completion/
  handle-key! itself returns 0 for p, Backspace and Left, and cview/
  handle-key! -- the console's real dispatch -- agrees: console-view.clj
  has no clause of its own for any of the three (unlike editor.clj's
  KEY-BACKSPACE clause, which also does nothing here once the caret sits
  at the buffer's end, per backspace-interactive!'s own docstring), so a
  bare call never triggers GTK's own default handling underneath a 0
  return -- p's insert and Backspace's delete are reproduced the same
  insert/delete-interactive way row-completion-keys-reach-editor's own
  stand-ins do, now gated on the checks above. Left has no observable
  buffer effect to check beyond its own 0 return."
  [ui]
  (go-row :repl-completion-keys-reach-console support/check
    (on-gtk (let [cv (:console ui) c (:repl-completion ui)
                  ns-sym (:prompt-ns @(:state (:session ui)))
                  history (str/join "\n" (:items @(:history (:ctx (:session ui)))))]
              (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(ma" -1 1)
              (cview/handle-key! cv KEY-SPACE g/CONTROL-MASK)
              (let [opened? (some? (completion/state c))
                    p-swallowed (completion/handle-key! c KEY-P 0)
                    p-handled (cview/handle-key! cv KEY-P 0)]
                (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "p" -1 1)
                (let [input-p (cview/input-text cv)
                      st-p (completion/state c)
                      expected-p (mapv :symbol (docs/candidates ns-sym {:prefix "map" :text "(map" :pos 4 :extra-context history}))
                      rows-p (mapv :symbol (:candidates st-p))
                      bs-swallowed (completion/handle-key! c KEY-BACKSPACE 0)
                      bs-handled (cview/handle-key! cv KEY-BACKSPACE 0)]
                  (support/backspace-interactive! (:buf cv) (g/cursor-offset (:buf cv)))
                  (let [input-bs (cview/input-text cv)
                        st-bs (completion/state c)
                        expected-bs (mapv :symbol (docs/candidates ns-sym {:prefix "ma" :text "(ma" :pos 3 :extra-context history}))
                        rows-bs (mapv :symbol (:candidates st-bs))
                        left-swallowed (completion/handle-key! c KEY-LEFT 0)
                        left-handled (cview/handle-key! cv KEY-LEFT 0)]
                    (support/check :repl-completion-keys-reach-console
                                   (and opened?
                                        (= 0 p-swallowed) (= 0 p-handled)
                                        (= "(map" input-p) (some? st-p) (= expected-p rows-p)
                                        (= 0 bs-swallowed) (= 0 bs-handled)
                                        (= "(ma" input-bs) (some? st-bs) (= expected-bs rows-bs)
                                        (= 0 left-swallowed) (= 0 left-handled))
                                   {:opened opened? :p-swallowed p-swallowed :p-handled p-handled
                                    :input-p input-p :expected-p expected-p :rows-p rows-p
                                    :bs-swallowed bs-swallowed :bs-handled bs-handled
                                    :input-bs input-bs :expected-bs expected-bs :rows-bs rows-bs
                                    :left-swallowed left-swallowed :left-handled left-handled})
                    (completion/close! c))))))))

(defn row-repl-completion-closes-on-space
  "Same reasoning as row-completion-closes-on-space, through cview/
  handle-key!: completion/handle-key! and cview/handle-key! both
  return 0 for a plain space (control-only? in console-view.clj's own
  Ctrl+Space clause rejects mask 0). Only then insert a space with
  insert_interactive_at_cursor: help/prefix-at is nil right after it, so
  refine! closes the popover."
  [ui]
  (go-row :repl-completion-closes-on-space support/check
    (on-gtk (let [cv (:console ui) c (:repl-completion ui)]
              (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(ma" -1 1)
              (cview/handle-key! cv KEY-SPACE g/CONTROL-MASK)
              (let [opened? (some? (completion/state c))
                    swallowed (completion/handle-key! c KEY-SPACE 0)
                    handled (cview/handle-key! cv KEY-SPACE 0)]
                (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) " " -1 1)
                (support/check :repl-completion-closes-on-space
                               (and opened? (= 0 swallowed) (= 0 handled) (nil? (completion/state c)))
                               {:opened opened? :swallowed swallowed :handled handled
                                :still-open (some? (completion/state c))}))))))

(defn row-completion-sized-to-rows-and-labelled
  "Two names the buffer defines: the list is as tall as
  its two rows rather than eight, and each row reads \"buffer\", not
  \"user\", before any Run. A long list (\"ma\") still gets the full
  height. Heights are the list's scrolled window, read once showing."
  [ui]
  (go-row :completion-sized-to-rows-and-labelled support/check
    (let [c (:completion ui)
          scroll-h (fn [] (g/gtk-widget-get-height (g/gtk-widget-get-parent (g/gtk-widget-get-parent (:list-box c)))))
          row-texts (fn [] (let [lb (:list-box c)]
                             (vec (for [i (range (count (:candidates (completion/state c))))]
                                    (g/label-text (g/gtk-list-box-row-get-child (g/gtk-list-box-get-row-at-index lb i)))))))
          ;; ready? says when the height is this list's: the scrolled window
          ;; keeps its last height while closed, so the second open read
          ;; the first list's 46 px before GTK resized it (2 runs of 4).
          ;; The first open had the same trap the other way: an earlier
          ;; row's long list left it at the full height, and pos? took that
          ;; 160 px. So it waits for a height under the full one.
          full-h @#'completion/LIST-HEIGHT
          open-on (fn [text caret ready?]
                    (on-gtk (let [ed (:editor ui)]
                              (editor/set-text! ed text)
                              (support/place-cursor! (:buf ed) caret)
                              ((:complete!* ui))))
                    (await-until 2000 20 #(ready? (scroll-h)))
                    (let [r (on-gtk {:h (scroll-h) :rows (row-texts)})]
                      (on-gtk (completion/close! c))
                      r))
          text "(defn zzq-a [] 1)\n(defn zzq-b [] 2)\n(zzq"
          few (open-on text (count text) #(< 0 % full-h))
          many (open-on "(ma" 3 #(< (* 2 (:h few)) %))]
      (on-gtk (support/check :completion-sized-to-rows-and-labelled
                             (and (= ["zzq-a  buffer" "zzq-b  buffer"] (:rows few))
                                  (< (* 2 (:h few)) (:h many)))
                             {:few few :many-h (:h many)})))))

(defn row-repl-completion-hides-when-input-scrolls-away
  "The console's completion popup is caret-anchored too: with enough
  output above the prompt, scrolling the Interactions pane to its top
  moves the input line out of view, and the popup hides instead of
  floating over old output (the editor's own rows pin the follow
  geometry; the console shares the same follow-caret! wiring)."
  [ui]
  (go-row :repl-completion-hides-when-input-scrolls-away support/check
    (let [cv (:console ui) c (:repl-completion ui)
          vadj (on-gtk (cview/append! cv :out (apply str (repeat 80 ";; filler\n")))
                       (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(ma" -1 1)
                       ((:repl-complete!* ui))
                       (g/gtk-scrollable-get-vadjustment (:view cv)))
          opened? (await-until 2000 20 #(popover/open? c))
          ;; append! drains on a later GTK tick and the console then
          ;; auto-scrolls to the bottom: wait that out, or the scroll to
          ;; the top below would find the buffer still short
          grew? (await-until 3000 20 #(> (g/gtk-adjustment-get-value vadj) 50.0))
          _ (on-gtk (g/gtk-adjustment-set-value vadj 0.0))
          open-after (on-gtk (popover/open? c))]
      (on-gtk (support/check :repl-completion-hides-when-input-scrolls-away
                             (and opened? grew? (false? open-after))
                             {:opened opened? :grew grew? :open-after open-after})
              (when (popover/open? c) (completion/close! c))
              (cview/clear-input! (:console ui))))))
