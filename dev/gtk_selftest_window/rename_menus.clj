(ns gtk-selftest-window.rename-menus
  "Rename and the context menus (editor and REPL)."
  (:require [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jolt.ffi :as ffi]
            [jonquil.gtk.completion :as completion]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.find-bar :as find-bar]
            [jonquil.gtk.help-popover :as help-popover]
            [jonquil.gtk.popover :as popover]))

;; --- rename and the context menus --------------------------------

(def ^:private rename-fixture
  "\"(defn greet [n] n)\\n(greet 1) ; greet\\n\\\"greet\\\"\\n:greet greeting\"
  -- two CODE occurrences of greet (the defn and the call), one inside a
  comment, one inside a string, plus a keyword (:greet) and a longer symbol
  (greeting) that share the substring but are never themselves greet (a rename that did a
  naive substring replace, or one that also touched the keyword, would
  still pass a fixture without them). Shared by
  every rename row below, each of which re-applies it fresh (the driver's
  own run-row only clears text/input, not this)."
  "(defn greet [n] n)\n(greet 1) ; greet\n\"greet\"\n:greet greeting")

(def ^:private rename-fixture-renamed
  "rename-fixture with edit/rename's own two code occurrences of greet
  applied as hello (measured: (edit/rename fixture caret) => {:edits [[6 11
  hello] [20 25 hello]] ...}) -- the comment, the string, the keyword and
  greeting are untouched. Shared by row-rename-applies/-one-undo so the
  expected renamed text is defined once."
  "(defn hello [n] n)\n(hello 1) ; greet\n\"greet\"\n:greet greeting")

(defn- setup-rename! [ed]
  (editor/set-text! ed rename-fixture)
  (support/place-cursor! (:buf ed) (inc (str/index-of rename-fixture "(greet 1)"))))

;; This file's own private copy (measured against gdkkeysyms.h,
;; the key the editor keymap names "F2"); KEY-F6 above already
;; covers Shift+F6.
(def ^:private KEY-F2 0xffbf)

(defn row-rename-applies
  "Caret inside the call's own greet, :rename-to!* \"hello\": edit/
  rename's own code-occurrence rule (never a comment, string, regex or
  keyword) renames exactly the defn and the call, one apply-op! (one undo
  step, checked separately by row-rename-one-undo), status reads the
  measured plural (2 occurrences, unchanged by the fixture's own
  :greet/greeting additions -- neither is a code occurrence of greet)."
  [ui]
  (go-row :rename-applies support/check
    (let [ed (:editor ui) status (:status-label ui)
          result (on-gtk (setup-rename! ed)
                         ((:rename-to!* ui) "hello"))
          status-ok? (await-until 1000 20 #(= "Renamed 2 occurrences of 'greet' to 'hello'."
                                              (g/label-text status)))]
      (on-gtk (support/check :rename-applies
                             (and (nil? result)
                                  (= rename-fixture-renamed (editor/editor-text ed))
                                  status-ok?)
                             {:result result :text (editor/editor-text ed)
                              :status (g/label-text status)})))))

(defn row-rename-skips-comments-strings
  "Same fixture and rename as row-rename-applies: the WHOLE
  resulting text matches rename-fixture-renamed byte for byte, not just
  \"still contains ; greet and \\\"greet\\\"\" (true even when rename does
  nothing at all) -- the comment,
  the string, the keyword :greet and the symbol greeting all read back
  unchanged, and only the defn/call are hello now."
  [ui]
  (go-row :rename-skips-comments-strings support/check
    (on-gtk (let [ed (:editor ui)]
              (setup-rename! ed)
              ((:rename-to!* ui) "hello")
              (let [text (editor/editor-text ed)]
                (support/check :rename-skips-comments-strings (= rename-fixture-renamed text) {:text text}))))))

(defn row-rename-one-undo
  "The SAME rename as row-rename-applies, checked BEFORE undoing (undoing
  first and comparing against the fixture also holds when rename applied
  nothing at all: an untouched buffer undoes to itself), then ONE gtk_text_buffer_undo: apply-op! wraps
  every edit in one begin/end_user_action (its own contract), so one undo
  must restore the fixture verbatim."
  [ui]
  (go-row :rename-one-undo support/check
    (on-gtk (let [ed (:editor ui)]
              (setup-rename! ed)
              ((:rename-to!* ui) "hello")
              (let [renamed-text (editor/editor-text ed)]
                (g/gtk-text-buffer-undo (:buf ed))
                (let [text (editor/editor-text ed)]
                  (support/check :rename-one-undo
                                 (and (= rename-fixture-renamed renamed-text) (= rename-fixture text))
                                 {:renamed-text renamed-text :text text})))))))

(defn row-rename-invalid
  "Same fixture, :rename-to!* \"a b\" (not a valid Clojure symbol,
  edit/rename's own rejection -- test/jonquil/help_test.clj's own
  rename-problem-test measures this exact sentence): returns the problem
  sentence, applies nothing."
  [ui]
  (go-row :rename-invalid support/check
    (on-gtk (let [ed (:editor ui)]
              (setup-rename! ed)
              (let [result ((:rename-to!* ui) "a b")]
                (support/check :rename-invalid
                               (and (= "Can't rename 'greet' to 'a b'." result) (= rename-fixture (editor/editor-text ed)))
                               {:result result :text (editor/editor-text ed)}))))))

(defn row-rename-keys
  "\"(map inc [1])\", caret 2 (row-quick-doc-core's own fixture/caret --
  \"map\" is the target either way): Shift+F6 opens the rename popover
  prefilled with \"map\", selected; closing it (popover/hide!, never
  Escape -- rename is autohide ON/modal, so this window can't post a real
  Escape into its grab) and F2 opens it again, same prefill."
  [ui]
  (go-row :rename-keys support/check
    (on-gtk (let [ed (:editor ui) rp (:rename-popover ui)]
              (editor/set-text! ed "(map inc [1])")
              (support/place-cursor! (:buf ed) 2)
              (let [h1 (editor/handle-key! ed support/KEY-F6 g/SHIFT-MASK)
                    open1? (popover/open? rp)
                    text1 (help-popover/rename-entry-text rp)]
                (popover/hide! rp)
                (let [h2 (editor/handle-key! ed KEY-F2 0)
                      open2? (popover/open? rp)
                      text2 (help-popover/rename-entry-text rp)]
                  (support/check :rename-keys
                                 (and (= 1 h1) open1? (= "map" text1) (= 1 h2) open2? (= "map" text2))
                                 {:h1 h1 :open1 open1? :text1 text1 :h2 h2 :open2 open2? :text2 text2})
                  (popover/hide! rp)))))))

(defn row-rename-cmd-does-nothing
  "0xffbf (F2) with META only: :exact #{} rejects it,
  same reasoning as every other chord's own cmd-does-nothing row, so
  the chord falls through unhandled and no popover opens."
  [ui]
  (go-row :rename-cmd-does-nothing support/check
    (on-gtk (let [ed (:editor ui) rp (:rename-popover ui)]
              (editor/set-text! ed "(map inc [1])")
              (support/place-cursor! (:buf ed) 2)
              (let [handled (editor/handle-key! ed KEY-F2 g/META-MASK)]
                (support/check :rename-cmd-does-nothing
                               (and (= 0 handled) (not (popover/open? rp)))
                               {:handled handled :open (popover/open? rp)}))))))

(defn row-rename-cmd-shift-f6-does-nothing
  "0xffc3 (F6) with SHIFT and META together: :exact #{:shift} rejects it
  (checking shift-held? alone would treat this chord the same as plain
  Shift+F6), so the chord falls through unhandled and no popover opens."
  [ui]
  (go-row :rename-cmd-shift-f6-does-nothing support/check
    (on-gtk (let [ed (:editor ui) rp (:rename-popover ui)]
              (editor/set-text! ed "(map inc [1])")
              (support/place-cursor! (:buf ed) 2)
              (let [handled (editor/handle-key! ed support/KEY-F6 (bit-or g/SHIFT-MASK g/META-MASK))]
                (support/check :rename-cmd-shift-f6-does-nothing
                               (and (= 0 handled) (not (popover/open? rp)))
                               {:handled handled :open (popover/open? rp)}))))))

(defn row-rename-ctrl-shift-f6-does-nothing
  "0xffc3 (F6) with SHIFT and CONTROL together: :exact #{:shift} must reject the
  Control side as it already rejects Meta (Ctrl+Shift+F6 must not trigger
  rename), so the chord falls through unhandled."
  [ui]
  (go-row :rename-ctrl-shift-f6-does-nothing support/check
    (on-gtk (let [ed (:editor ui) rp (:rename-popover ui)]
              (editor/set-text! ed "(map inc [1])")
              (support/place-cursor! (:buf ed) 2)
              (let [handled (editor/handle-key! ed support/KEY-F6 (bit-or g/SHIFT-MASK g/CONTROL-MASK))]
                (support/check :rename-ctrl-shift-f6-does-nothing
                               (and (= 0 handled) (not (popover/open? rp)))
                               {:handled handled :open (popover/open? rp)}))))))

(defn row-rename-closed-signal-syncs
  "Context bullet #1: gtk_popover_popdown called DIRECTLY (never
  jonquil.gtk.popover/hide!) still leaves :open? false -- jonquil.gtk.
  popover's own \"closed\" signal handler (make-popover's autohide
  branch) is what does that, not this row's own hide! (never called
  here). Polls rather than reading once, since a GTK close animation
  could defer the signal past this call returning."
  [ui]
  (go-row :rename-closed-signal-syncs support/check
    (let [rp (:rename-popover ui)
          opened? (on-gtk (let [ed (:editor ui)]
                            (editor/set-text! ed "(map inc [1])")
                            (support/place-cursor! (:buf ed) 2)
                            (editor/handle-key! ed support/KEY-F6 g/SHIFT-MASK)
                            (let [opened? (popover/open? rp)]
                              (g/gtk-popover-popdown (:popover rp))
                              opened?)))
          closed? (await-until 1000 20 #(not (popover/open? rp)))]
      (on-gtk (support/check :rename-closed-signal-syncs
                             (and opened? closed?)
                             {:opened opened? :closed closed?})))))

(defn row-rename-escape-refocuses-editor
  "Escape closes the rename popover and the editor has focus again, not
  the editor's scrolled window: GTK moves focus to the popover's parent
  once it unmaps, after its \"closed\" handler has run, so the handler's
  own grab would be undone (found with the command palette)."
  [ui]
  (go-row :rename-escape-refocuses-editor support/check
    (let [ed (:editor ui) rp (:rename-popover ui)
          _ (on-gtk (editor/set-text! ed "(map inc [1])")
                    (support/place-cursor! (:buf ed) 2)
                    (editor/handle-key! ed support/KEY-F6 g/SHIFT-MASK))
          entry? (await-until 2000 20 #(let [f (g/gtk-root-get-focus (:window ui))]
                                         (and (not (ffi/null? f))
                                              (not (zero? (g/gtk-widget-is-ancestor f (:popover rp)))))))
          hesc (on-gtk ((:handle-window-key! ui) ffi/null support/KEY-ESCAPE 0 0 ffi/null))
          closed? (await-until 1000 20 #(not (popover/open? rp)))
          focused? (await-until 2000 20 #(= (:view ed) (g/gtk-root-get-focus (:window ui))))]
      (on-gtk (when (popover/open? rp) (popover/hide! rp))
              (support/check :rename-escape-refocuses-editor
                             (and entry? (= 1 hesc) closed? focused?)
                             {:entry entry? :escape hesc :closed closed? :focused focused?})))))

(def ^:private KEY-T 0x074)
(def ^:private KEY-W 0x077)
(def ^:private KEY-F5 0xffc2)

(defn row-rename-entry-keeps-window-keys
  "With focus in the rename popover's entry, the window's capture-phase key
  handler leaves Cmd/Ctrl+T and Cmd/Ctrl+W to the entry (0, no new or
  closed tab), and F5 doesn't Run; Escape still reaches the popover clause
  and closes it (1). :handle-window-key! is the signal's own fn, called
  directly, after waiting for the entry's focus to land."
  [ui]
  (go-row :rename-entry-keeps-window-keys support/check
    (let [rp (:rename-popover ui)
          tabs0 (on-gtk (let [ed (:editor ui)]
                          (editor/set-text! ed "(map inc [1])")
                          (support/place-cursor! (:buf ed) 2)
                          (editor/handle-key! ed support/KEY-F6 g/SHIFT-MASK)
                          (count ((:tabs* ui)))))
          focused? (await-until 2000 20
                                #(let [f (g/gtk-root-get-focus (:window ui))]
                                   (and (not (ffi/null? f))
                                        (not (zero? (g/gtk-widget-is-ancestor f (:popover rp)))))))
          [ht hw hf5 tabs1 open?] (on-gtk [((:handle-window-key! ui) ffi/null KEY-T 0 g/META-MASK ffi/null)
                                           ((:handle-window-key! ui) ffi/null KEY-W 0 g/META-MASK ffi/null)
                                           ((:handle-window-key! ui) ffi/null KEY-F5 0 0 ffi/null)
                                           (count ((:tabs* ui)))
                                           (popover/open? rp)])
          hesc (on-gtk ((:handle-window-key! ui) ffi/null support/KEY-ESCAPE 0 0 ffi/null))
          closed? (await-until 1000 20 #(not (popover/open? rp)))]
      (on-gtk (when (popover/open? rp) (popover/hide! rp))
              (support/check :rename-entry-keeps-window-keys
                             (and focused? (= 0 ht hw hf5) (= tabs0 tabs1) open? (= 1 hesc) closed?)
                             {:focused focused? :cmd-t ht :cmd-w hw :f5 hf5 :tabs [tabs0 tabs1]
                              :open open? :escape hesc :closed closed?})))))

(defn row-menu-actions
  ":activate-menu-action!* for jump, quick-doc, toggle-comment, each
  against the fixture/caret its own existing row (row-jump-top-level/
  row-quick-doc-core/dev/gtk_selftest.clj's row-edit-toggle-comment)
  already established, but fired through the
  editor's own GSimpleActionGroup (g_action_group_activate_action, per
  install-context-menu!'s own docstring) instead of the key."
  [ui]
  (go-row :menu-actions support/check
    (on-gtk (let [ed (:editor ui) hp (:help-popover ui) activate! (:activate-menu-action!* ui)]
              (editor/set-text! ed "(defn greet [n] n)\n(greet 1)")
              (support/place-cursor! (:buf ed) 22)
              (activate! "jump")
              (let [jump-caret (g/cursor-offset (:buf ed))]
                (editor/set-text! ed "(map inc [1])")
                (support/place-cursor! (:buf ed) 2)
                (activate! "quick-doc")
                (let [quick-doc-open? (popover/open? hp)
                      quick-doc-text (help-popover/quick-doc-text hp)]
                  (popover/hide! hp)
                  (editor/set-text! ed "(+ 1 2)")
                  (support/place-cursor! (:buf ed) 0)
                  (activate! "toggle-comment")
                  (let [commented-text (editor/editor-text ed)]
                    (support/check :menu-actions
                                   (and (= 6 jump-caret)
                                        quick-doc-open? (str/includes? quick-doc-text "clojure.core/map")
                                        (= "; (+ 1 2)" commented-text))
                                   {:jump-caret jump-caret :quick-doc-open quick-doc-open?
                                    :quick-doc-text quick-doc-text :commented-text commented-text}))))))))

(defn row-menu-actions-console-complete
  "Activates the REPL's own p4.complete (:repl-activate-menu-action!*,
  the console's own action group) and checks it has the SAME effect
  Ctrl+Space does -- the completion popover opens over \"(ma\"'s own
  candidates."
  [ui]
  (go-row :menu-actions-console-complete support/check
    (on-gtk (let [cv (:console ui) c (:repl-completion ui)]
              (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(ma" -1 1)
              ((:repl-activate-menu-action!* ui) "complete")
              (let [opened? (some? (completion/state c))]
                (support/check :menu-actions-console-complete opened? {:opened opened?})
                (completion/close! c))))))

(defn row-menu-action-throw-is-contained
  "Forces jonquil.gtk.editor/do-toggle-comment! to throw for one
  activation (with-redefs, the same override row-completion-commit-throw-
  consumes-key reaches through a closure of the same shape), then
  activates p4.toggle-comment through :activate-menu-action!*. Without
  install-context-menu!'s own try/catch, the SAME throw crosses a
  GSimpleAction \"activate\" callback -- a Jolt foreign-callable -- and
  kills this whole self-test process outright (exit 1, no SUMMARY). With
  the guard: the process survives, the status bar shows the error, and
  the buffer is untouched (do-toggle-comment! throws before any edit is
  ever applied)."
  [ui]
  (go-row :menu-action-throw-is-contained support/check
    (let [ed (:editor ui) status (:status-label ui) activate! (:activate-menu-action!* ui)
          text (on-gtk (editor/set-text! ed "(+ 1 2)")
                       (support/place-cursor! (:buf ed) 0)
                       (with-redefs [editor/do-toggle-comment! (fn [& _] (throw (ex-info "forced menu-action failure" {})))]
                         (activate! "toggle-comment"))
                       (editor/editor-text ed))
          status-ok? (await-until 1000 20 #(str/includes? (g/label-text status) "forced menu-action failure"))]
      (on-gtk (support/check :menu-action-throw-is-contained
                             (and status-ok? (= "(+ 1 2)" text))
                             {:status (g/label-text status) :text text})))))

(defn row-menu-items-all-resolve
  "The editor menu's items run commands over
  a menu ctx wire-tab! builds, and an item whose ctx entry is missing
  throws only when picked, which install-context-menu!'s try/catch turns
  into \"; <action>: ...\" in the status bar. So this fires, through
  :activate-menu-action!*, each item with no live row of its own (run-form,
  complete, jump, quick-doc and toggle-comment have theirs) and checks the
  status bar never shows that catch format, closing what each one opens.
  set-status! posts the label update through on-gui!, the queue on-gtk
  uses too, so each status is read in the on-gtk after its fire. inspect-
  last and inspect-form are left out: each starts an eval and an inspector
  that later rows read."
  [ui]
  (go-row :menu-items-all-resolve support/check
    (let [ed (:editor ui) rp (:rename-popover ui) fb (:find-bar ui)
          status (:status-label ui) activate! (:activate-menu-action!* ui)
          _ (on-gtk (editor/set-text! ed "(+ 1 2)")
                    (support/place-cursor! (:buf ed) 1))
          results (loop [actions ["rename" "indent" "unindent" "format-all" "format-selection" "find" "replace"]
                         acc []]
                    (if-let [action (first actions)]
                      (let [extra (on-gtk (g/gtk-label-set-text status "")
                                          (activate! action)
                                          (case action
                                            "rename" (let [open? (popover/open? rp)] (popover/hide! rp) open?)
                                            "indent" (editor/editor-text ed)
                                            ("find" "replace") (find-bar/close! fb)
                                            nil))
                            text (on-gtk (g/label-text status))]
                        (recur (rest actions)
                               (conj acc [action (not (str/includes? text (str "; " action ":"))) text extra])))
                      acc))]
      (on-gtk (support/check :menu-items-all-resolve
                             (and (every? second results)
                                  (true? (nth (results 0) 3))
                                  (not= "(+ 1 2)" (nth (results 1) 3)))
                             {:results results})))))
