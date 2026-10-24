(ns gtk-selftest-window.window-keys
  "Window-level key dispatch: Escape/Stop, the Caps-Lock chord, focus
  toggling, zoom, and the throw-containment + close-chord rows that
  exercise handle-window-key! directly."
  (:require [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jonquil.eval :as eval]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.popover :as popover]
            [jonquil.gtk.window :as window]
            [jonquil.keymap :as km]))

(defn row-escape-yields-when-idle
  "M5: the window-level capture controller used to consume Escape even
  when nothing was evaluating, so Escape could never reach an IME
  composition. Calls the exact fn the \"key-pressed\" signal calls (an
  ordinary function call with a synthetic keyval/state, not a synthesized
  OS keystroke) -- once while idle (must yield: 0) and once while a
  program runs (must Stop it: 1, and the program must actually stop)."
  [ui]
  (go-row :escape-yields-when-idle support/check
    (let [idle-result (on-gtk (let [r ((:handle-window-key! ui) nil support/KEY-ESCAPE 0 0 nil)]
                                (editor/set-text! (:editor ui) "(loop [] (recur))")
                                ((:run!* ui))
                                r))
          started? (await-until 2000 20 #(eval/evaluating? (:ctx (:session ui))))
          [mark running-result] (on-gtk (let [m (support/out-end-offset ui)
                                              r ((:handle-window-key! ui) nil support/KEY-ESCAPE 0 0 nil)]
                                          [m r]))
          stopped? (await-until 3000 20 #(str/includes? (support/since ui mark) "; interrupted"))]
      (support/check :escape-yields-when-idle
                     (and (= 0 idle-result) started? (= 1 running-result) stopped?)
                     {:idle-result idle-result :started started?
                      :running-result running-result :stopped stopped?}))))

(defn row-f5-runs-the-buffer
  "F5 is a keymap binding since plugins PL1 S4, and no other row presses
  it outside a rename (rename-entry-keeps-window-keys checks it's held
  back there). Sends F5 through the exact fn the \"key-pressed\" signal
  calls: it must answer 1 and Run the buffer."
  [ui]
  (go-row :f5-runs-the-buffer support/check
    (let [[mark handled] (on-gtk (editor/set-text! (:editor ui) "(+ 40 2)")
                                 (let [m (support/out-end-offset ui)]
                                   [m ((:handle-window-key! ui) nil 0xffc2 0 0 nil)]))
          ran? (await-until 3000 20 #(str/includes? (support/since ui mark) "42"))]
      (support/check :f5-runs-the-buffer (and (= 1 handled) ran?)
                     {:handled handled :ran ran? :out (support/since ui mark)}))))

;; The uppercase keyval GDK reports for Ctrl+S under Caps Lock, with no
;; Shift bit set in state -- window.clj's own copy is private.
(def ^:private KEY-S-CAPS 0x53)
(defn row-keymap-masks-match-gdk
  "jonquil.keymap copies the GDK modifier bits so it loads without GTK
  (plugins PL1 S1); this pins the copies against jonquil.gtk.ffi's, the
  values the window's key controller actually reports."
  [ui]
  (go-row :keymap-masks-match-gdk support/check
    (on-gtk (let [want {:shift g/SHIFT-MASK :ctrl g/CONTROL-MASK :alt g/ALT-MASK
                        :super g/SUPER-MASK :meta g/META-MASK}]
              (support/check :keymap-masks-match-gdk (= want km/MODIFIER-MASKS)
                             {:keymap km/MODIFIER-MASKS :gdk want :window (some? (:window ui))})))))

(defn row-command-chord-capslock
  "M4: shortcuts compared the raw keyval, so Caps Lock -- which makes GDK
  report the UPPERCASE keyval with no Shift bit set -- broke Ctrl+S (and
  Ctrl+R/O, not separately exercised here since they share the same
  gdk_keyval_to_lower normalisation). Establishes a path via the
  dialog-free :save-to! (no live dialog needed), dirties the editor, then
  drives the exact key-controller fn with the Caps-Lock-shaped keyval:
  it must Save (not Save As, since Shift isn't set), and the file's
  content must actually update."
  [ui]
  (go-row :command-chord-capslock support/check
    (on-gtk (let [ed (:editor ui)
                  tmp (str (System/getProperty "java.io.tmpdir")
                           "/jonquil-selftest-capslock-" (System/currentTimeMillis) ".clj")]
              (editor/set-text! ed "(+ 1 1)")
              ((:save-to! ui) tmp)
              (editor/set-text! ed "(+ 2 2)")
              (let [handled ((:handle-window-key! ui) nil KEY-S-CAPS 0 g/CONTROL-MASK nil)
                    saved-content (slurp tmp)]
                (support/check :command-chord-capslock
                               (and (= 1 handled) (= "(+ 2 2)" saved-content))
                               {:handled handled :saved-content saved-content})
                (.delete (java.io.File. ^String tmp)))))))

(defn row-toggle-focus
  "Grabs editor focus first (a real function call, not a keystroke) so the
  row starts from a known state regardless of what an earlier row left
  focused, then toggles twice: console, then back to the editor."
  [ui]
  (go-row :toggle-focus support/check
    (on-gtk (let [ed (:editor ui) cv (:console ui) win (:window ui)]
              (editor/focus! ed)
              ((:toggle-focus!* ui))
              (let [after-first (g/gtk-root-get-focus win)]
                ((:toggle-focus!* ui))
                (let [after-second (g/gtk-root-get-focus win)]
                  (support/check :toggle-focus
                                 (and (= after-first (:view cv)) (= after-second (:view ed)))
                                 {:after-first after-first :after-second after-second
                                  :editor-view (:view ed) :console-view (:view cv)})))))))

(defn row-zoom-in-out
  "Zooms in, waits for the editor view's natural height to settle and grow
  past the baseline, then resets and waits for it to settle back to
  exactly the original -- each measurement via await-stable-height, never
  a synchronous read on the same tick as the change."
  [ui]
  (go-row :zoom-in-out support/check
    (let [view (:view (:editor ui))
          h0 (support/await-stable-height view 2000)
          _ (on-gtk ((:zoom!* ui) :in))
          h1 (support/await-stable-height view 2000)
          _ (on-gtk ((:zoom!* ui) :reset))
          h2 (support/await-stable-height view 2000)]
      (support/check :zoom-in-out (and (> h1 h0) (= h2 h0)) {:h0 h0 :h1 h1 :h2 h2}))))

(defn row-cursor-listener-throw-others-still-run
  "Registers two on-cursor-moved! listeners: the first throws once, the
  second flips a flag. Moving the caret proves editor.clj's own
  notify::cursor-position callback runs each listener in its own try --
  the second listener's flag still flips even though the first one threw
  right before it. Each listener stays registered for the rest of the
  run; the first one only ever throws this once (guarded by its own
  atom), so later rows are unaffected."
  [ui]
  (go-row :cursor-listener-throw-others-still-run support/check
    (on-gtk (let [ed (:editor ui)
                  threw-once? (atom false)
                  ran-second? (atom false)]
              (editor/set-text! ed "(+ 1 2)")
              (editor/on-cursor-moved!
               ed (fn [_lc]
                    (when-not @threw-once?
                      (reset! threw-once? true)
                      (throw (ex-info "forced cursor-listener failure" {})))))
              (editor/on-cursor-moved! ed (fn [_lc] (reset! ran-second? true)))
              (support/place-cursor! (:buf ed) 3)
              (support/check :cursor-listener-throw-others-still-run
                             (and @threw-once? @ran-second?)
                             {:threw-once @threw-once? :ran-second @ran-second?})))))

(defn row-window-key-throw-contained
  "Forces jonquil.gtk.window/do-cycle-theme! to throw for one Cmd+Shift+M,
  through on-window-key's own theme-cycle-fn call to it (with-redefs on
  that var reaches the call even though it's unqualified and intra-
  namespace, the same override row-completion-commit-throw-consumes-key
  checks). Calls the exact fn the \"key-pressed\" signal calls
  (:handle-window-key!), the same chord row-theme-chord already drives
  (0x06d + META-MASK/CONTROL-MASK + SHIFT-MASK). Without on-window-key's
  own try/catch, this exact with-redefs crashes the whole self-test
  process before this row can even record a result (no SUMMARY, non-zero
  exit). With the guard: the process survives, the key still counts as
  consumed (1), and the status bar shows the error."
  [ui]
  (go-row :window-key-throw-contained support/check
    (let [handled (on-gtk (with-redefs [window/do-cycle-theme! (fn [& _] (throw (ex-info "forced window-key failure" {})))]
                            ((:handle-window-key! ui) nil 0x06d 0 (bit-or g/CONTROL-MASK g/SHIFT-MASK) nil)))
          status-ok? (await-until 1000 20 #(str/includes? (g/label-text (:status-label ui)) "forced window-key failure"))]
      (on-gtk (support/check :window-key-throw-contained
                             (and (= 1 handled) status-ok?)
                             {:handled handled :status (g/label-text (:status-label ui))})))))

;; The shared key table (gdkkeysyms.h) -- window.clj's own copy is private.
(def ^:private KEY-W 0x77)
(defn row-command-chord-close
  "Closes WHILE a program is running, not idle, since the eval worker's
  own on-gui!/cview/append! calls could otherwise race gtk_window_close's
  widget teardown: this row starts (loop [] (recur)), waits for it to
  actually be evaluating, and only then sends the chord. A command chord
  (Control here) plus W closes the window, which already quits the
  app -- gtk_window_close fires the SAME close-request path a real
  close-button click or OS window-close would. Calls the exact fn the
  key-pressed signal calls (window.clj's :handle-window-key!), never a
  synthesized OS keystroke, same pattern as row-escape-yields-when-idle
  and row-command-chord-capslock above.

  This is deliberately the LAST row: gtk_window_close synchronously fires
  close-request, which calls g_application_quit, so the running
  g_application main loop stops once it returns to it and nothing after
  this row runs -- the CI-facing evidence that closing mid-run didn't hang
  or crash is this whole process exiting cleanly afterward, which
  dev/gtk_selftest_window.clj's own finish (its destroy-signal poll, and
  gtk-async/close-window-bounded!'s own forced-close fallback) now
  guarantees happens either way, pass or fail.

  Also opens quick doc (Ctrl+Q on \"loop\") before starting the run, so
  THIS row's own real close -- the only one in this file that reaches
  gtk_window_close's actual teardown -- also covers a program running
  AND the quick-doc popover still open when the close chord lands (an
  ordinary Ctrl+Q then Cmd+Q sequence for a user). :popover-was-open is
  read right at the close chord itself, alongside started?/handled --
  neither of those two pre-existing assertions is weakened; this only
  adds a third. Nothing here can catch the GTK-CRITICAL a regression
  would print on its own (it's a log line, not a thrown exception) --
  that's still the gates' own stderr grep -- but this makes sure the
  exact call sequence actually happens inside a real bb gtk:selftest
  run."
  [ui]
  (go-row :command-chord-close support/check
    (on-gtk (editor/set-text! (:editor ui) "(loop [] (recur))")
            (support/place-cursor! (:buf (:editor ui)) 2)
            ((:quick-doc!* ui))
            ((:run!* ui)))
    ;; The window's live evaluator, not the starting :session: an earlier
    ;; tabs row switches the window to ClojureDart and back, which leaves a
    ;; new in-process evaluator (tabs plan P2.T4).
    (let [started? (await-until 2000 20 #(ev/evaluating? ((:evaluator* ui))))]
      ;; The close chord ends the GTK main loop, so the check is recorded in
      ;; the same on-gtk step: no on-gtk may follow this one.
      (on-gtk (let [popover-was-open? (popover/open? (:help-popover ui))
                    handled ((:handle-window-key! ui) nil KEY-W 0 g/CONTROL-MASK nil)]
                (support/check :command-chord-close
                               (and started? (= 1 handled) popover-was-open?)
                               {:started started? :handled handled :popover-was-open popover-was-open?}))))))
