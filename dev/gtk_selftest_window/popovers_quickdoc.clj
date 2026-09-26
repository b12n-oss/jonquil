(ns gtk-selftest-window.popovers-quickdoc
  "The caret-anchored popover base and its window-key routing, quick doc,
  and jump to definition."
  (:require [clojure.string :as str]
            [gtk-selftest-window.support :as support]
            [jonquil.docs :as docs]
            [jonquil.eval :as eval]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.help-popover :as help-popover]
            [jonquil.gtk.popover :as popover]
            [jonquil.session :as session]))

;; --- P4.T3: the caret-anchored popover base, its theme colours, and the
;; window's key order -------------------------------------------------------

(defn row-popover-at-caret
  "Probe 2: a test popover (autohide off, a plain label child), shown at
  caret offset 5 in \"(defn f [x] x)\". jonquil.gtk.popover's own fix
  (report: GtkTextView's dispose hangs on a directly-parented popover, see
  popover.clj's ns docstring) makes the popover's real GtkWidget parent
  the view's scrolled window, not the view -- so the expected x is
  g/caret-window-rect's x translated (g/compute-point) into THAT parent's
  coordinate space, the same computation show-at-caret! itself makes, read
  back independently against the popover's own pointing-to rectangle
  (g/popover-pointing-to, gtk_popover_get_pointing_to)."
  [ui next]
  (let [ed (:editor ui) view (:view ed)
        pp (popover/make-popover view {:autohide? false :position :bottom})]
    (editor/set-text! ed "(defn f [x] x)")
    (support/place-cursor! (:buf ed) 5)
    (popover/set-child! pp (g/gtk-label-new "candidate"))
    (popover/show-at-caret! pp)
    (let [[cx cy] (g/caret-window-rect view)
          [expected-x] (g/compute-point view (g/gtk-widget-get-parent (:popover pp)) cx cy)
          [actual-x] (g/popover-pointing-to (:popover pp))
          visible (g/gtk-widget-get-visible (:popover pp))]
      (support/check :popover-at-caret
             (and (popover/open? pp) (= 1 visible) (= (Math/round (double expected-x)) actual-x))
             {:open (popover/open? pp) :visible visible
              :expected-x expected-x :actual-x actual-x})
      (popover/hide! pp)
      (g/gtk-widget-unparent (:popover pp))
      (next))))

;; GDK_KEY_a (gdkkeysyms.h) -- an ordinary letter, for popover-keys-stay-
;; with-editor's synthetic handle-key! call.
(def ^:private KEY-A 0x061)
(defn row-popover-keys-stay-with-editor
  "Probe 1: with autohide FALSE, the view's own capture-phase key
  controller keeps every key regardless of whether a popover is showing --
  nothing in jonquil.gtk.popover filters keys, so editor/handle-key! with
  an ordinary letter behaves exactly as it would with no popover open (0,
  unhandled -- GTK's own default handling inserts it), and the popover the
  row opened stays open, since nothing here closes it."
  [ui next]
  (let [ed (:editor ui) view (:view ed)
        pp (popover/make-popover view {:autohide? false :position :bottom})]
    (popover/set-child! pp (g/gtk-label-new "candidate"))
    (popover/show-at-caret! pp)
    (let [handled (editor/handle-key! ed KEY-A 0)]
      (support/check :popover-keys-stay-with-editor
             (and (= 0 handled) (popover/open? pp))
             {:handled handled :open (popover/open? pp)})
      (popover/hide! pp)
      (g/gtk-widget-unparent (:popover pp))
      (next))))

(defn row-window-shift-f6-reaches-editor
  "P4.T3 key-order change #1 (probe 4): the window's F6 clause now also
  requires (not shift-held?), so Shift+F6 no longer matches it and falls
  through unconsumed -- returns 0, and focus (read via gtk_root_get_focus,
  the same pointer-identity check do-toggle-focus! itself uses) is
  unchanged. Grabs editor focus first so the row starts from a known
  state regardless of what an earlier row left focused."
  [ui next]
  (let [win (:window ui) ed (:editor ui)]
    (editor/focus! ed)
    (let [before (g/gtk-root-get-focus win)
          handled ((:handle-window-key! ui) nil support/KEY-F6 0 g/SHIFT-MASK nil)
          after (g/gtk-root-get-focus win)]
      (support/check :window-shift-f6-reaches-editor
             (and (= 0 handled) (= before after))
             {:handled handled :before before :after after})
      (next))))

(defn row-window-f6-still-toggles
  "Plain F6 (no modifier) must still toggle focus after the Shift guard was
  added -- console.clj's view first, editor's back, same pattern
  row-toggle-focus already uses for the :toggle-focus!* hook, but driven
  through the WINDOW's own key dispatch (:handle-window-key!) instead,
  since that's the exact path a real F6 keystroke takes."
  [ui next]
  (let [win (:window ui) ed (:editor ui) cv (:console ui)]
    (editor/focus! ed)
    (let [h1 ((:handle-window-key! ui) nil support/KEY-F6 0 0 nil)
          after-first (g/gtk-root-get-focus win)
          h2 ((:handle-window-key! ui) nil support/KEY-F6 0 0 nil)
          after-second (g/gtk-root-get-focus win)]
      (support/check :window-f6-still-toggles
             (and (= 1 h1) (= after-first (:view cv)) (= 1 h2) (= after-second (:view ed)))
             {:h1 h1 :h2 h2 :after-first after-first :after-second after-second})
      (next))))

(defn row-window-escape-closes-popover
  "P4.T3 key-order change #2 (probe 4): a new clause ahead of the
  evaluating? Stop clause closes any open P4 popover on Escape and returns
  1 without touching the program. Starts a real (loop [] (recur)), opens a
  test popover registered on the window's own registry
  (:register-popover!*, the same join completion/quick-doc/rename will use
  from P4.T4 on), sends window Escape: the popover must close and the
  program must still be running. A second Escape (nothing left open) must
  then fall through to the evaluating? clause and Stop it."
  [ui next]
  (let [ed (:editor ui) view (:view ed)
        pp (popover/make-popover view {:autohide? false :position :bottom})]
    (editor/set-text! ed "(loop [] (recur))")
    ((:run!* ui))
    (support/poll-until 2000 20
                (fn [] (eval/evaluating? (:ctx (:session ui))))
                (fn [started?]
                  (popover/set-child! pp (g/gtk-label-new "candidate"))
                  (popover/show-at-caret! pp)
                  ((:register-popover!* ui) pp)
                  (let [mark (support/out-end-offset ui)
                        h1 ((:handle-window-key! ui) nil support/KEY-ESCAPE 0 0 nil)
                        closed-open? (not (popover/open? pp))
                        still-running-1? (eval/evaluating? (:ctx (:session ui)))
                        h2 ((:handle-window-key! ui) nil support/KEY-ESCAPE 0 0 nil)]
                    (support/poll-until 3000 20
                                (fn [] (str/includes? (support/since ui mark) "; interrupted"))
                                (fn [stopped?]
                                  (support/check :window-escape-closes-popover
                                         (and started? (= 1 h1) closed-open? still-running-1?
                                              (= 1 h2) stopped?)
                                         {:started started? :h1 h1 :closed-open closed-open?
                                          :still-running-1 still-running-1? :h2 h2 :stopped stopped?})
                                  ;; Belt-and-braces: hide! is idempotent, so
                                  ;; this leaves clean state for later rows
                                  ;; even if close-popovers-fn didn't fire.
                                  ;;
                                  ;; P4.T4 fix round 1: does NOT unparent pp
                                  ;; itself, unlike every other throwaway-
                                  ;; popover row in this file. pp is
                                  ;; REGISTERED (:register-popover!* above),
                                  ;; and unparenting a floating-ref GtkWidget
                                  ;; drops its last reference and finalizes
                                  ;; it right there (measured: this row used
                                  ;; to leave a dangling pointer in the
                                  ;; window's own registry for the rest of
                                  ;; the run, and window.clj's own new
                                  ;; unparent-registered-popovers! -- run
                                  ;; once, for real, at close-request-fn's
                                  ;; real-quit branch -- then called
                                  ;; gtk_widget_get_parent on that freed
                                  ;; pointer: \"Gtk-CRITICAL:
                                  ;; gtk_widget_get_parent: assertion
                                  ;; GTK_IS_WIDGET (widget) failed\"). Left
                                  ;; parented (hidden) here, it stays a
                                  ;; harmless, invisible, do-nothing widget
                                  ;; for the rest of the run and is cleaned
                                  ;; up exactly once, safely, by that same
                                  ;; function at real window teardown.
                                  (popover/hide! pp)
                                  (next))))))))

(defn row-popover-dark-colours
  "Probe 7's measured selector, read back from the shared provider's OWN
  serialisation (:code-css*, gtk_css_provider_to_string) -- like row-
  theme-css, GTK normalises the theme's hex colours to rgb(r,g,b): dark's
  :background #000000 -> rgb(0,0,0), :selection #3e4451 -> rgb(62,68,81).
  Fix wave item 5 (M8): extended to assert the rename entry's own rule
  (popover.jonquil-popover entry) is present too -- same rgb(0,0,0)/
  rgb(62,68,81) pair, since entry uses :background/:selection same as the
  rest of the popover, so no new colour value is needed to prove it's
  there."
  [ui next]
  (let [ed (:editor ui) view (:view ed)
        pp (popover/make-popover view {:autohide? false :position :bottom})]
    ((:set-theme!* ui) :dark)
    (popover/set-child! pp (g/gtk-label-new "candidate"))
    (popover/show-at-caret! pp)
    (let [css ((:code-css* ui))]
      (support/check :popover-dark-colours
             (and (str/includes? css "popover.jonquil-popover")
                  (str/includes? css "popover.jonquil-popover entry")
                  (str/includes? css "rgb(0,0,0)")
                  (str/includes? css "rgb(62,68,81)"))
             {:css css})
      (popover/hide! pp)
      (g/gtk-widget-unparent (:popover pp))
      ((:set-theme!* ui) :light)
      (next))))

;; --- P4.T4: quick doc and jump to definition -------------------------------

;; gtk-keys.md's shared table, this file's own private copies (measured
;; against gdkkeysyms.h, same values editor.clj's own KEY-Q/KEY-B/KEY-F1/
;; KEY-F12 use).
(def ^:private KEY-B 0x062)
(def ^:private KEY-F1 0xffbe)
(def ^:private KEY-F12 0xffc9)

(defn row-quick-doc-core
  "editor/handle-key! directly with Ctrl+Q (control-only?, no synthesized
  keystroke): the popover opens at the caret and its label -- read back
  via help-popover/quick-doc-text -- carries clojure.core/map's own
  arglists."
  [ui next]
  (let [ed (:editor ui) hp (:help-popover ui)]
    (editor/set-text! ed "(map inc [1])")
    (support/place-cursor! (:buf ed) 2)
    (let [handled (editor/handle-key! ed support/KEY-Q g/CONTROL-MASK)
          text (help-popover/quick-doc-text hp)]
      (support/check :quick-doc-core
             (and (= 1 handled) (popover/open? hp)
                  (str/includes? text "clojure.core/map")
                  (str/includes? text "([f] [f coll]"))
             {:handled handled :open (popover/open? hp) :text text})
      (popover/hide! hp)
      (next))))

(defn row-quick-doc-run-defined
  "Runs a real Run defining greet with a docstring, waits for it to
  actually land (run-and-wait!'s own I1 gating), then Shift+F1 on a fresh
  (greet 1) shows that docstring."
  [ui next]
  (support/run-and-wait! ui "(defn greet \"Says hi.\" [name] name)" 3000
                 (fn [t] (str/includes? t "=> #'user/greet"))
                 (fn [defined? _]
                   (let [ed (:editor ui) hp (:help-popover ui)]
                     (editor/set-text! ed "(greet 1)")
                     (support/place-cursor! (:buf ed) 2)
                     (let [handled (editor/handle-key! ed KEY-F1 g/SHIFT-MASK)
                           text (help-popover/quick-doc-text hp)]
                       (support/check :quick-doc-run-defined
                              (and defined? (= 1 handled) (str/includes? text "Says hi."))
                              {:defined defined? :handled handled :text text})
                       (popover/hide! hp)
                       (next))))))

(defn row-quick-doc-after-reset
  "Continues from the same defined greet, then forces Reset-on-Run mode
  (session/set-mode!, since an earlier row in this file's own sequence
  left the session in :accumulate -- see row-reset-vs-accumulate) and
  Runs a program that does NOT define greet: reset-namespaces! removes
  the whole `user` ns, including greet, before evaluating it. A quick doc
  on the SAME (greet 1) text afterward must not throw (docs/symbol-doc's
  own `user`-missing fallback, P4.T2) and must read as not found -- and,
  since docs/symbol-doc memoises on [ns sym text] and this reuses the
  EXACT SAME key row-quick-doc-run-defined just cached with a real doc,
  this also depends on window.clj's clear-doc-cache! running after the
  second Run (P4.T2 review's Important)."
  [ui next]
  (support/run-and-wait! ui "(defn greet \"Says hi.\" [name] name)" 3000
                 (fn [t] (str/includes? t "=> #'user/greet"))
                 (fn [defined1? _]
                   (session/set-mode! (:session ui) :reset)
                   (support/run-and-wait! ui "(+ 1 1)" 3000
                                  (fn [t] (str/includes? t "=> 2"))
                                  (fn [reran? _]
                                    (let [ed (:editor ui) hp (:help-popover ui)]
                                      (editor/set-text! ed "(greet 1)")
                                      (support/place-cursor! (:buf ed) 2)
                                      (let [{:keys [handled threw]}
                                            (try {:handled (editor/handle-key! ed support/KEY-Q g/CONTROL-MASK)}
                                                 (catch Throwable e {:threw (ex-message e)}))
                                            text (help-popover/quick-doc-text hp)]
                                        (support/check :quick-doc-after-reset
                                               (and defined1? reran? (nil? threw) (= 1 handled)
                                                    (str/includes? text "No documentation found"))
                                               {:defined1 defined1? :reran reran?
                                                :handled handled :threw threw :text text})
                                        (popover/hide! hp)
                                        (next))))))))

(defn row-quick-doc-closes
  "gtk_text_buffer_place_cursor's own cursor-position notify (synchronous,
  no on-gui! drain, per row-line-col) closes the popover the moment the
  caret moves -- no poll needed."
  [ui next]
  (let [ed (:editor ui) hp (:help-popover ui)]
    (editor/set-text! ed "(map inc [1])")
    (support/place-cursor! (:buf ed) 2)
    (editor/handle-key! ed support/KEY-Q g/CONTROL-MASK)
    (let [opened? (popover/open? hp)]
      (support/place-cursor! (:buf ed) 3)
      (support/check :quick-doc-closes
             (and opened? (not (popover/open? hp)))
             {:opened opened? :still-open (popover/open? hp)})
      (next))))

(defn row-quick-doc-closes-on-focus-loss
  "P4.T4 fix round 1 (Minor): calls every fn registered on ed's own
  :on-focus-fns directly -- the SAME ones GtkEventControllerFocus's own
  \"leave\" signal calls (editor.clj's make-editor) -- rather than trying
  to move real OS/window focus, which the self-test window can't reliably
  do (P4.T3's own has_focus finding: this window is never the OS's key
  window, so has_focus stays false throughout and a real focus transfer
  can't be observed here the way gtk_root_get_focus's own focus-WITHIN
  tracking can). Fails first with window.clj's hide-on-focus-lost closure
  temporarily removed (see the report)."
  [ui next]
  (let [ed (:editor ui) hp (:help-popover ui)]
    (editor/set-text! ed "(map inc [1])")
    (support/place-cursor! (:buf ed) 2)
    (editor/handle-key! ed support/KEY-Q g/CONTROL-MASK)
    (let [opened? (popover/open? hp)]
      (doseq [f @(:on-focus-fns ed)] (f))
      (support/check :quick-doc-closes-on-focus-loss
             (and opened? (not (popover/open? hp)))
             {:opened opened? :still-open (popover/open? hp)})
      (next))))

(defn row-quick-doc-cmd-does-nothing
  "0x71 with META-MASK only: control-only? rejects it (the P4 rule --
  CONTROL-MASK checked, META-MASK rejected), so the chord falls through
  unhandled and no popover opens."
  [ui next]
  (let [ed (:editor ui) hp (:help-popover ui)]
    (editor/set-text! ed "(map inc [1])")
    (support/place-cursor! (:buf ed) 2)
    (let [handled (editor/handle-key! ed support/KEY-Q g/META-MASK)]
      (support/check :quick-doc-cmd-does-nothing
             (and (= 0 handled) (not (popover/open? hp)))
             {:handled handled :open (popover/open? hp)})
      (next))))

(defn row-quick-doc-cmd-shift-f1-does-nothing
  "0xffbe (F1) with SHIFT and META together: shift-only? rejects it (fix
  round 1 -- it used to check shift-held? alone, so this chord opened
  quick doc same as plain Shift+F1, measured live before the fix), so
  the chord falls through unhandled and no popover opens."
  [ui next]
  (let [ed (:editor ui) hp (:help-popover ui)]
    (editor/set-text! ed "(map inc [1])")
    (support/place-cursor! (:buf ed) 2)
    (let [handled (editor/handle-key! ed KEY-F1 (bit-or g/SHIFT-MASK g/META-MASK))]
      (support/check :quick-doc-cmd-shift-f1-does-nothing
             (and (= 0 handled) (not (popover/open? hp)))
             {:handled handled :open (popover/open? hp)})
      (next))))

(defn row-quick-doc-after-define
  "T2 review's Important, widened (context clue #3): a lookup before
  anything defines the name must not keep serving :not-found once a REPL
  entry defines it. Looks up quickdocdefme -- a name unique to this row --
  before anything defines it (help/doc-text's own \"No documentation
  found\"), defines it through the SAME path the console's Return uses
  (an interactive insert + handle-return!, never a synthesized key), then
  looks it up again with the IDENTICAL buffer text: the memo key
  ([ns sym text]) is the same both times, so this fails if window.clj's
  clear-doc-cache! call after a completed REPL eval is removed -- verified
  live (see the report) by removing that call, confirming this row (and
  quick-doc-after-reset above, which depends on the SAME call site for its
  own Run) both fail, then restoring it."
  [ui next]
  (let [ed (:editor ui) hp (:help-popover ui) cv (:console ui)
        code "(quickdocdefme 1)"]
    (editor/set-text! ed code)
    (support/place-cursor! (:buf ed) 2)
    (let [h1 (editor/handle-key! ed support/KEY-Q g/CONTROL-MASK)
          before (help-popover/quick-doc-text hp)]
      (popover/hide! hp)
      (let [mark (support/out-end-offset ui)]
        (g/gtk-text-buffer-insert-interactive-at-cursor
         (:buf cv) "(defn quickdocdefme \"Freshly defined.\" [n] n)" -1 1)
        (cview/handle-return! cv false)
        (support/poll-until 3000 20
                    (fn [] (and (support/idle? ui) (str/includes? (support/since ui mark) "=> #'user/quickdocdefme")))
                    (fn [defined?]
                      (editor/set-text! ed code)
                      (support/place-cursor! (:buf ed) 2)
                      (let [h2 (editor/handle-key! ed support/KEY-Q g/CONTROL-MASK)
                            after (help-popover/quick-doc-text hp)]
                        (support/check :quick-doc-after-define
                               (and (= 1 h1) (str/includes? before "No documentation found")
                                    defined? (= 1 h2) (str/includes? after "Freshly defined."))
                               {:h1 h1 :before before :defined defined? :h2 h2 :after after})
                        (popover/hide! hp)
                        (next))))))))

(defn row-jump-top-level
  "\"(defn greet [n] n)\\n(greet 1)\", caret 22 (inside the SECOND greet,
  the usage) -- help/jump-target takes the symbol's START (P4.T1's
  jump-target docstring), F12 (no-modifiers?) lands the caret on the
  DEFN's own greet, offset 6."
  [ui next]
  (let [ed (:editor ui)]
    (editor/set-text! ed "(defn greet [n] n)\n(greet 1)")
    (support/place-cursor! (:buf ed) 22)
    (let [handled (editor/handle-key! ed KEY-F12 0)
          caret (g/cursor-offset (:buf ed))]
      (support/check :jump-top-level
             (and (= 1 handled) (= 6 caret))
             {:handled handled :caret caret})
      (next))))

(defn row-jump-local-binding
  "\"(defn f [x] (let [y x] y))\", caret 23 (the LAST y, the usage inside
  the body) -- Ctrl+B (control-only?) lands on the BINDING y inside
  [y x], offset 18 (measured with the pure spike's help/jump-target, per
  the brief)."
  [ui next]
  (let [ed (:editor ui)]
    (editor/set-text! ed "(defn f [x] (let [y x] y))")
    (support/place-cursor! (:buf ed) 23)
    (let [handled (editor/handle-key! ed KEY-B g/CONTROL-MASK)
          caret (g/cursor-offset (:buf ed))]
      (support/check :jump-local-binding
             (and (= 1 handled) (= 18 caret))
             {:handled handled :caret caret})
      (next))))

(defn row-jump-external
  "\"(map inc [1])\", caret 2, F12: find-definition finds nothing in the
  buffer, so jump-target falls to the :external case and the SAME quick-
  doc popover shows clojure.core/map's own doc."
  [ui next]
  (let [ed (:editor ui) hp (:help-popover ui)]
    (editor/set-text! ed "(map inc [1])")
    (support/place-cursor! (:buf ed) 2)
    (let [handled (editor/handle-key! ed KEY-F12 0)
          text (help-popover/quick-doc-text hp)]
      (support/check :jump-external
             (and (= 1 handled) (popover/open? hp) (str/includes? text "clojure.core/map"))
             {:handled handled :open (popover/open? hp) :text text})
      (popover/hide! hp)
      (next))))

(defn row-jump-none
  "\"(nope 1)\", caret 2, F12: a real symbol with no definition anywhere
  -- jump-target's own nil case -- reads as the status message, polled
  (P4.T4: do-jump! goes through set-status!, which enqueues via on-gui!
  same as every other status update in this file, never synchronous)."
  [ui next]
  (let [ed (:editor ui) status (:status-label ui)]
    (editor/set-text! ed "(nope 1)")
    (support/place-cursor! (:buf ed) 2)
    (let [handled (editor/handle-key! ed KEY-F12 0)]
      (support/poll-until 1000 20
                  (fn [] (= "No definition found for nope." (g/label-text status)))
                  (fn [status-ok?]
                    (support/check :jump-none
                           (and (= 1 handled) status-ok?)
                           {:handled handled :status (g/label-text status)})
                    (next))))))

(defn row-jump-shadowed-param
  "I1: \"(def x 1)\\n(defn f [x] x)\", caret 22 (the body's own x, a usage
  of the PARAMETER, not the top-level def) -- jonquil.help/jump-target's
  scope-aware search finds the enclosing param first, so F12 lands on the
  param at offset 19, never the top-level def's own x at offset 5 (measured
  live against the fix: test/jonquil/help_test.clj's own jump-target-
  scope-test carries the same fixture/offsets, pure)."
  [ui next]
  (let [ed (:editor ui)]
    (editor/set-text! ed "(def x 1)\n(defn f [x] x)")
    (support/place-cursor! (:buf ed) 22)
    (let [handled (editor/handle-key! ed KEY-F12 0)
          caret (g/cursor-offset (:buf ed))]
      (support/check :jump-shadowed-param
             (and (= 1 handled) (= 19 caret))
             {:handled handled :caret caret})
      (next))))
