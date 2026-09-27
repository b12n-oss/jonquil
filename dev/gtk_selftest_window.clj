(ns gtk-selftest-window
  "P2.T7 self-test: opens the REAL Jonquil window via jonquil.main/start!
  and runs the front-end self-test rows against its ui. Every wait polls a
  condition against a deadline, never a fixed sleep. Rows call ui's exposed
  functions (run!*, stop!*, open-path!, save-to!) directly, or drive the
  console's own submit path (handle-return! over an interactive insert),
  exactly as the buttons/keys and the user's typing do; no keystroke is
  ever synthesized.

  The driver is a go block (dev/gtk_async.clj): run-row resets the editor
  and console, waits for the engine to go idle, then runs one row and parks
  until the row's channel delivers, so rows run strictly in order. A row
  that doesn't finish within PER-ROW-MS is recorded as a :row-timeout FAIL
  and the run goes on. Callback-style rows (fn [ui next]) run through
  gtk-async/from-callback (`shim` below) until their area is converted.

  The rows themselves live in the gtk-selftest-window.* namespaces under
  dev/gtk_selftest_window/, one per feature area (support, run-repl, files,
  find-replace, close-prerun, window-keys, themes, popovers-quickdoc,
  completion, rename-menus, dialects). This file keeps only row-fns (the
  ordered list every row runs in -- the last row really closes the window,
  so order matters), the driver, and -main."
  (:require [clojure.core.async :as a :refer [<! go timeout]]
            [gtk-async :as ga :refer [on-gtk await-until go-row]]
            [gtk-selftest-window.close-prerun :as close-prerun]
            [gtk-selftest-window.completion :as completion]
            [gtk-selftest-window.console-layout :as console-layout]
            [gtk-selftest-window.dialects :as dialects]
            [gtk-selftest-window.files :as files]
            [gtk-selftest-window.find-replace :as find-replace]
            [gtk-selftest-window.popovers-quickdoc :as popovers-quickdoc]
            [gtk-selftest-window.rename-menus :as rename-menus]
            [gtk-selftest-window.run-repl :as run-repl]
            [gtk-selftest-window.support :as support]
            [gtk-selftest-window.themes :as themes]
            [gtk-selftest-window.window-keys :as window-keys]
            [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :as cb]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.main :as main]))

(def ^:private shim
  "A callback-style row (fn [ui next] ...) as a go-style row (fn [ui] ch)."
  (partial ga/from-callback support/check))

(def ^:private row-fns
  [run-repl/row-run-output
   run-repl/row-run-while-running
   run-repl/row-stdin-roundtrip
   run-repl/row-stop-while-reading
   close-prerun/row-run-unbalanced
   run-repl/row-reset-vs-accumulate
   run-repl/row-repl-after-run
   run-repl/row-fast-repl-twice
   run-repl/row-busy-hint-once
   run-repl/row-console-history-down-empty
   (shim window-keys/row-escape-yields-when-idle)
   (shim window-keys/row-command-chord-capslock)
   close-prerun/row-repl-eof-no-editor-hint
   (shim files/row-open-refuses-dirty)
   (shim files/row-open-invalid-utf8)
   (shim files/row-save-atomic)
   (shim files/row-save-follows-symlink)
   (shim files/row-save-executable-keeps-mode)
   (shim files/row-save-failure-leaves-original-intact)
   (shim files/row-dialog-dismissed-silent)
   (shim files/row-save-and-open)
   run-repl/row-run-selection-line
   run-repl/row-run-selection-sel
   run-repl/row-run-selection-blank
   (shim window-keys/row-toggle-focus)
   run-repl/row-clear-console
   (shim console-layout/row-console-wraps-long-lines)
   (shim window-keys/row-zoom-in-out)
   find-replace/row-find-highlights
   find-replace/row-find-next-wraps
   find-replace/row-find-prev
   find-replace/row-find-no-match
   find-replace/row-replace-one
   find-replace/row-replace-all
   find-replace/row-find-escape
   find-replace/row-find-hangul
   find-replace/row-find-survives-repaint
   find-replace/row-find-preedit-yields
   find-replace/row-find-prefill-selects
   close-prerun/row-line-col
   close-prerun/row-close-clean
   close-prerun/row-close-dirty-cancel
   close-prerun/row-close-dirty-discard
   close-prerun/row-close-dirty-save
   close-prerun/row-pre-run-hidden-by-error
   close-prerun/row-pre-run-stray-closer
   close-prerun/row-close-alert-pending-guard
   close-prerun/row-close-alert-routing
   close-prerun/row-run-while-running-keeps-warning
   find-replace/row-window-escape-closes-bar-while-running
   find-replace/row-window-escape-yields-preedit-while-running
   find-replace/row-window-escape-stops-when-editor-focused
   find-replace/row-find-typing-keeps-caret
   find-replace/row-find-return-in-replacement
   find-replace/row-window-find-keys-editor-focused
   close-prerun/row-line-col-typing
   close-prerun/row-close-dismissed
   close-prerun/row-close-save-failed
   close-prerun/row-close-alert-build-throws
   close-prerun/row-quit-action-prompts
   (shim themes/row-theme-retints-tags)
   (shim themes/row-theme-css)
   (shim themes/row-theme-prefer-dark)
   (shim themes/row-theme-keeps-text-and-undo)
   (shim themes/row-theme-keeps-find-highlight)
   (shim themes/row-theme-and-zoom-share-css)
   (shim themes/row-theme-applies-to-new-text)
   (shim themes/row-theme-cycle)
   (shim themes/row-theme-chord)
   (shim themes/row-theme-chord-needs-shift)
   (shim themes/row-settings-isolated)
   (shim themes/row-settings-unreadable-warns)
   (shim themes/row-theme-save-failure-reported)
   (shim themes/row-theme-switch-while-running)
   (shim popovers-quickdoc/row-popover-at-caret)
   (shim popovers-quickdoc/row-popover-keys-stay-with-editor)
   (shim popovers-quickdoc/row-window-shift-f6-reaches-editor)
   (shim popovers-quickdoc/row-window-f6-still-toggles)
   (shim popovers-quickdoc/row-window-escape-closes-popover)
   (shim popovers-quickdoc/row-popover-dark-colours)
   (shim popovers-quickdoc/row-quick-doc-core)
   (shim popovers-quickdoc/row-quick-doc-run-defined)
   (shim popovers-quickdoc/row-quick-doc-after-reset)
   (shim popovers-quickdoc/row-quick-doc-closes)
   (shim popovers-quickdoc/row-quick-doc-closes-on-focus-loss)
   (shim popovers-quickdoc/row-quick-doc-cmd-does-nothing)
   (shim popovers-quickdoc/row-quick-doc-cmd-shift-f1-does-nothing)
   (shim popovers-quickdoc/row-quick-doc-after-define)
   (shim popovers-quickdoc/row-jump-top-level)
   (shim popovers-quickdoc/row-jump-local-binding)
   (shim popovers-quickdoc/row-jump-external)
   (shim popovers-quickdoc/row-jump-none)
   (shim popovers-quickdoc/row-jump-shadowed-param)
   completion/row-completion-opens
   completion/row-completion-single-inserts
   completion/row-completion-no-prefix
   completion/row-completion-navigate-commit
   completion/row-completion-tab-commits
   completion/row-completion-keys-reach-editor
   completion/row-completion-closes-on-space
   completion/row-completion-escape-while-running
   completion/row-completion-cmd-space-nothing
   completion/row-completion-preedit-yields
   completion/row-completion-closes-on-focus-loss
   completion/row-completion-closes-other-popover
   completion/row-completion-commit-throw-consumes-key
   completion/row-plain-return-apply-op-throw-contained
   completion/row-repl-completion-opens
   completion/row-repl-completion-precedence
   completion/row-repl-history-after-close
   completion/row-repl-completion-history-symbols
   completion/row-repl-completion-outside-input
   completion/row-repl-completion-cmd-space-nothing
   completion/row-repl-completion-preedit-yields
   completion/row-repl-completion-closes-on-focus-loss
   completion/row-repl-completion-keys-reach-console
   completion/row-repl-completion-closes-on-space
   (shim rename-menus/row-rename-applies)
   (shim rename-menus/row-rename-skips-comments-strings)
   (shim rename-menus/row-rename-one-undo)
   (shim rename-menus/row-rename-invalid)
   (shim rename-menus/row-rename-keys)
   (shim rename-menus/row-rename-cmd-does-nothing)
   (shim rename-menus/row-rename-cmd-shift-f6-does-nothing)
   (shim rename-menus/row-rename-closed-signal-syncs)
   (shim rename-menus/row-menu-actions)
   (shim rename-menus/row-menu-actions-console-complete)
   (shim rename-menus/row-menu-action-throw-is-contained)
   (shim dialects/row-dialect-label-default)
   (shim dialects/row-evaluator-is-the-session)
   (shim dialects/row-project-config-malformed-warns)
   (shim dialects/row-project-config-unknown-runtime-warns)
   (shim dialects/row-project-open-keeps-evaluator)
   (shim window-keys/row-cursor-listener-throw-others-still-run)
   (shim window-keys/row-window-key-throw-contained)
   (shim window-keys/row-command-chord-close)])

;; --- driver --------------------------------------------------------------

(def ^:private PER-ROW-MS
  ;; A row that FAILs waits out every deadline on its path. The longest path
  ;; is run-repl/row-reset-vs-accumulate's four 3000 ms waits (12000 ms),
  ;; plus run-row's 2000 ms idle wait; the whole run takes about 5 s.
  30000)

(defn- run-row
  "Resets the editor and the console's input, waits (up to 2000 ms) for the
  engine to go idle, then runs row and parks until its channel delivers."
  [ui row]
  (go-row :row-reset support/check
    (on-gtk (editor/set-text! (:editor ui) "")
            (cview/clear-input! (:console ui)))
    (await-until 2000 20 #(support/idle? ui))
    (<! (row ui))))

;; P2.T10 fix round 1, Important #2: removing run-rows!'s old unconditional
;; close made row-command-chord-close the only way out, so a REGRESSION in
;; the close chord (on-window-key stops matching KEY-W, close-fn stops
;; being wired, etc.) would leave the g_application main loop running
;; forever with nothing to stop it -- hanging `bb gtk:selftest` instead of
;; failing it. window-destroyed? is set by a "destroy" signal this file
;; connects on the window itself (-main, below), independent of window.clj's
;; own close-request->quit wiring: a plain Clojure atom flip, safe to read
;; from the driver's go block without ever touching the (possibly
;; already-destroyed) widget pointer directly.
(defonce ^:private window-destroyed? (atom false))

;; P3 fix wave I3: set only where SUMMARY prints, i.e. when the driver
;; reached the end of row-fns. An early g_application_quit (a row that
;; really closes or quits) ends g_application_run with the remaining rows
;; never run and no SUMMARY, and every result recorded so far can still be
;; a PASS -- -main used to exit 0 then, and `bb gtk:selftest` reads only
;; exit codes.
(defonce ^:private finished? (atom false))

(def ^:private WINDOW-CLOSE-FALLBACK-DEADLINE-MS
  ;; Generous: a working chord closes within one main-loop iteration
  ;; (microseconds), so this only ever actually waits out its deadline
  ;; when the chord is broken.
  2000)

(defn- finish
  "After the last row. row-command-chord-close already tried the product's
  own close chord; this reads window-destroyed? rather than assuming it
  worked. If the chord regressed, the window is still open, and forcing
  gtk_window_close keeps the process from hanging (non-zero, via the FAIL
  below). It never uses await-until: once the window is gone the GTK main
  loop has ended, and an on-gui! thunk would never run."
  [ui]
  (go (let [t0 (System/currentTimeMillis)]
        (loop []
          (when (and (not @window-destroyed?)
                     (< (- (System/currentTimeMillis) t0) WINDOW-CLOSE-FALLBACK-DEADLINE-MS))
            (<! (timeout 50))
            (recur))))
      (when-not @window-destroyed?
        (support/check :command-chord-close-forced false
                       {:reason "window still open after the close chord; forced closed to avoid hanging the run"})
        (on-gtk (g/gtk-window-close (:window ui))))
      (let [passed (count (filter second @support/results)) total (count @support/results)]
        (reset! finished? true)
        (println (str "SUMMARY " passed " of " total " passed")))))

(defn -main [& _]
  (let [driver-done (a/promise-chan)]
    (main/start!
     {:settings-path (support/fresh-settings-path)
      :on-ready (fn [ui]
                  (g/g-signal-connect-data (:window ui) "destroy"
                                           (cb/callable (fn [_win _data] (reset! window-destroyed? true) nil)
                                                        [:pointer :pointer] :void)
                                           ffi/null ffi/null g/CONNECT-DEFAULT)
                  (go (<! (timeout 300))
                      (<! (ga/run-rows (map (fn [row] (fn [] (run-row ui row))) row-fns)
                                       PER-ROW-MS support/check))
                      (<! (finish ui))
                      (a/put! driver-done :done)))})
    ;; g_application_run has returned, so this thread may block now. The
    ;; driver prints SUMMARY from its own thread after it sees the destroy
    ;; signal, which can land just after the main loop ends.
    (a/alts!! [driver-done (timeout 5000)])
    (when-not @finished?
      (binding [*out* *err*]
        (println "[gtk-selftest-window] run ended before the last row; no SUMMARY, exiting 1")))
    (System/exit (if (and @finished? (seq @support/results) (every? second @support/results)) 0 1))))
