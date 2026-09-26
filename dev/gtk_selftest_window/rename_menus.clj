(ns gtk-selftest-window.rename-menus
  "Rename and the P4 context menus (editor and REPL)."
  (:require [clojure.string :as str]
            [gtk-selftest-window.support :as support]
            [jonquil.gtk.completion :as completion]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.help-popover :as help-popover]
            [jonquil.gtk.popover :as popover]))

;; --- P4.T7: rename and the P4 context menus --------------------------------

(def ^:private rename-fixture
  "\"(defn greet [n] n)\\n(greet 1) ; greet\\n\\\"greet\\\"\\n:greet greeting\"
  -- two CODE occurrences of greet (the defn and the call), one inside a
  comment, one inside a string, plus a keyword (:greet) and a longer symbol
  (greeting) that share the substring but are never themselves greet (I4:
  a rename that did a naive substring replace, or one that also touched the
  keyword, would still pass the OLD fixture, which had neither). Shared by
  every rename row below, each of which re-applies it fresh (run-row! only
  clears text/input, not this)."
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

;; P4.T7: this file's own private copy (measured against gdkkeysyms.h,
;; same value jonquil.gtk.editor's own KEY-F2 uses); KEY-F6 above already
;; covers Shift+F6.
(def ^:private KEY-F2 0xffbf)

(defn row-rename-applies
  "Caret inside the call's own greet, :rename-to!* \"hello\": edit/
  rename's own code-occurrence rule (never a comment, string, regex or
  keyword) renames exactly the defn and the call, one apply-op! (one undo
  step, checked separately by row-rename-one-undo), status the brief's
  own wording with the measured plural (2 occurrences, unchanged by the
  fixture's own :greet/greeting additions -- neither is a code occurrence
  of greet)."
  [ui next]
  (let [ed (:editor ui) status (:status-label ui)]
    (setup-rename! ed)
    (let [result ((:rename-to!* ui) "hello")]
      (support/poll-until 1000 20
                  (fn [] (= "Renamed 2 occurrences of 'greet' to 'hello'." (g/label-text status)))
                  (fn [status-ok?]
                    (support/check :rename-applies
                           (and (nil? result)
                                (= rename-fixture-renamed (editor/editor-text ed))
                                status-ok?)
                           {:result result :text (editor/editor-text ed)
                            :status (g/label-text status)})
                    (next))))))

(defn row-rename-skips-comments-strings
  "Same fixture and rename as row-rename-applies, focused on the reviewer
  clause's own concern (I4): the WHOLE resulting text matches rename-
  fixture-renamed byte for byte, not just \"still contains ; greet and
  \\\"greet\\\"\" (true even when rename does nothing at all) -- the comment,
  the string, the keyword :greet and the symbol greeting all read back
  unchanged, and only the defn/call are hello now."
  [ui next]
  (let [ed (:editor ui)]
    (setup-rename! ed)
    ((:rename-to!* ui) "hello")
    (let [text (editor/editor-text ed)]
      (support/check :rename-skips-comments-strings (= rename-fixture-renamed text) {:text text})
      (next))))

(defn row-rename-one-undo
  "The SAME rename as row-rename-applies, checked BEFORE undoing (I4: the
  old version of this row undid first and compared against the fixture,
  which also holds when rename applied nothing at all -- an untouched
  buffer undoes to itself), then ONE gtk_text_buffer_undo: apply-op! wraps
  every edit in one begin/end_user_action (its own contract), so one undo
  must restore the fixture verbatim."
  [ui next]
  (let [ed (:editor ui)]
    (setup-rename! ed)
    ((:rename-to!* ui) "hello")
    (let [renamed-text (editor/editor-text ed)]
      (g/gtk-text-buffer-undo (:buf ed))
      (let [text (editor/editor-text ed)]
        (support/check :rename-one-undo
               (and (= rename-fixture-renamed renamed-text) (= rename-fixture text))
               {:renamed-text renamed-text :text text})
        (next)))))

(defn row-rename-invalid
  "Same fixture, :rename-to!* \"a b\" (not a valid Clojure symbol,
  edit/rename's own rejection -- test/jonquil/help_test.clj's own
  rename-problem-test measures this exact sentence): returns the problem
  sentence, applies nothing."
  [ui next]
  (let [ed (:editor ui)]
    (setup-rename! ed)
    (let [result ((:rename-to!* ui) "a b")]
      (support/check :rename-invalid
             (and (= "Can't rename 'greet' to 'a b'." result) (= rename-fixture (editor/editor-text ed)))
             {:result result :text (editor/editor-text ed)})
      (next))))

(defn row-rename-keys
  "\"(map inc [1])\", caret 2 (row-quick-doc-core's own fixture/caret --
  \"map\" is the target either way): Shift+F6 opens the rename popover
  prefilled with \"map\", selected; closing it (popover/hide!, never
  Escape -- rename is autohide ON/modal, so this window can't post a real
  Escape into its grab) and F2 opens it again, same prefill."
  [ui next]
  (let [ed (:editor ui) rp (:rename-popover ui)]
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
        (popover/hide! rp)
        (next)))))

(defn row-rename-cmd-does-nothing
  "0xffbf (F2) with META only: no-modifiers? rejects it (the P4 rule),
  same reasoning as every other P4 chord's own cmd-does-nothing row, so
  the chord falls through unhandled and no popover opens."
  [ui next]
  (let [ed (:editor ui) rp (:rename-popover ui)]
    (editor/set-text! ed "(map inc [1])")
    (support/place-cursor! (:buf ed) 2)
    (let [handled (editor/handle-key! ed KEY-F2 g/META-MASK)]
      (support/check :rename-cmd-does-nothing
             (and (= 0 handled) (not (popover/open? rp)))
             {:handled handled :open (popover/open? rp)})
      (next))))

(defn row-rename-cmd-shift-f6-does-nothing
  "0xffc3 (F6) with SHIFT and META together: shift-only? rejects it (fix
  round 1 -- it used to check shift-held? alone, so this chord opened
  rename same as plain Shift+F6, measured live before the fix), so the
  chord falls through unhandled and no popover opens."
  [ui next]
  (let [ed (:editor ui) rp (:rename-popover ui)]
    (editor/set-text! ed "(map inc [1])")
    (support/place-cursor! (:buf ed) 2)
    (let [handled (editor/handle-key! ed support/KEY-F6 (bit-or g/SHIFT-MASK g/META-MASK))]
      (support/check :rename-cmd-shift-f6-does-nothing
             (and (= 0 handled) (not (popover/open? rp)))
             {:handled handled :open (popover/open? rp)})
      (next))))

(defn row-rename-closed-signal-syncs
  "Context bullet #1: gtk_popover_popdown called DIRECTLY (never
  jonquil.gtk.popover/hide!) still leaves :open? false -- jonquil.gtk.
  popover's own \"closed\" signal handler (make-popover's autohide
  branch) is what does that, not this row's own hide! (never called
  here). Polls rather than reading once, since a GTK close animation
  could defer the signal past this call returning."
  [ui next]
  (let [ed (:editor ui) rp (:rename-popover ui)]
    (editor/set-text! ed "(map inc [1])")
    (support/place-cursor! (:buf ed) 2)
    (editor/handle-key! ed support/KEY-F6 g/SHIFT-MASK)
    (let [opened? (popover/open? rp)]
      (g/gtk-popover-popdown (:popover rp))
      (support/poll-until 1000 20
                  (fn [] (not (popover/open? rp)))
                  (fn [closed?]
                    (support/check :rename-closed-signal-syncs
                           (and opened? closed?)
                           {:opened opened? :closed closed?})
                    (next))))))

(defn row-menu-actions
  "The brief's own table row: :activate-menu-action!* for jump, quick-
  doc, toggle-comment, each against the fixture/caret its own existing
  row (row-jump-top-level/row-quick-doc-core/dev/gtk_selftest.clj's
  row-edit-toggle-comment) already established, but fired through the
  editor's own P4 GSimpleActionGroup (g_action_group_activate_action, per
  install-context-menu!'s own docstring) instead of the key."
  [ui next]
  (let [ed (:editor ui) hp (:help-popover ui) activate! (:activate-menu-action!* ui)]
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
                  :quick-doc-text quick-doc-text :commented-text commented-text})
          (next))))))

(defn row-menu-actions-console-complete
  "Extra row (not in the brief's own table): activates the REPL's own
  p4.complete (:repl-activate-menu-action!*, the console's own P4 action
  group) and checks it has the SAME effect Ctrl+Space does -- the
  completion popover opens over \"(ma\"'s own candidates."
  [ui next]
  (let [cv (:console ui) c (:repl-completion ui)]
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(ma" -1 1)
    ((:repl-activate-menu-action!* ui) "complete")
    (let [opened? (some? (completion/state c))]
      (support/check :menu-actions-console-complete opened? {:opened opened?})
      (completion/close! c)
      (next))))

(defn row-menu-action-throw-is-contained
  "Fix round 1 (Important): forces jonquil.gtk.editor/do-toggle-comment!
  to throw for one activation (with-redefs, the seam row-completion-
  commit-throw-consumes-key already proved reaches through a closure the
  same shape), then activates p4.toggle-comment through :activate-menu-
  action!*. Without install-context-menu!'s own try/catch, the SAME
  throw crosses a GSimpleAction \"activate\" callback -- a Jolt foreign-
  callable -- and kills this whole self-test process (measured live with
  the fix reverted: exit 1, no SUMMARY). With the fix: the process
  survives, the status bar shows the error, and the buffer is untouched
  (do-toggle-comment! throws before any edit is ever applied)."
  [ui next]
  (let [ed (:editor ui) status (:status-label ui) activate! (:activate-menu-action!* ui)]
    (editor/set-text! ed "(+ 1 2)")
    (support/place-cursor! (:buf ed) 0)
    (with-redefs [editor/do-toggle-comment! (fn [& _] (throw (ex-info "forced menu-action failure" {})))]
      (activate! "toggle-comment"))
    (let [text (editor/editor-text ed)]
      (support/poll-until 1000 20
                  (fn [] (str/includes? (g/label-text status) "forced menu-action failure"))
                  (fn [status-ok?]
                    (support/check :menu-action-throw-is-contained
                           (and status-ok? (= "(+ 1 2)" text))
                           {:status (g/label-text status) :text text})
                    (next))))))
