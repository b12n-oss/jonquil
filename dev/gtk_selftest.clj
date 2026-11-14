(ns gtk-selftest
  "Self-test: opens a real GtkApplicationWindow around the
  editor and the console under test, runs the rows as go blocks (never a
  fixed sleep -- each wait polls a condition against a deadline via
  gtk-async/await-until), prints PASS/FAIL per row and a SUMMARY line,
  quits, and exits 0 or 1. Rows call the same functions the key handlers
  call; no keystroke is ever synthesized.

  The driver is gtk-async/run-rows: rows run strictly in order, and a row
  that doesn't finish within PER-ROW-MS is recorded as a :row-timeout FAIL
  while the run goes on.

  The rows live in the gtk-selftest.* namespaces under dev/gtk_selftest/,
  one per feature group, with their shared helpers in gtk-selftest.support.
  This file keeps the ordered row lists (row-fns for the editor, then
  console-row-fns for the console), the driver and -main."
  (:require [selftest-keys]
            [clojure.core.async :refer [<! go timeout]]
            [gtk-async :as ga :refer [on-gtk]]
            [gtk-selftest.console :as console-rows]
            [gtk-selftest.edit-keys :as edit-keys]
            [gtk-selftest.editor-rows :as editor-rows]
            [gtk-selftest.incremental :as incremental]
            [gtk-selftest.parinfer :as parinfer-rows]
            [gtk-selftest.parinfer-cache :as parinfer-cache]
            [gtk-selftest.parinfer-format :as parinfer-format]
            [gtk-selftest.read-only-paint :as read-only-paint]
            [gtk-selftest.support :refer [check results true-fire-count stub-on-submit!
                                          stub-history-prev stub-history-next]]
            [jolt.ffi :as ffi]
            [jonquil.commands :as commands]
            [jonquil.gtk.callbacks :as cb]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.theme :as theme]))

;; Set only where SUMMARY prints. An early g_application_quit (a row that
;; really quits) ends g_application_run with the remaining rows never run
;; and no SUMMARY, while every result recorded so far can still be a PASS;
;; -main would exit 0 in that case. The window group's own finished? is the same.
(defonce ^:private finished? (atom false))

(def ^:private row-fns
  [editor-rows/row-editor-colours
   editor-rows/row-editor-undo-text-only
   editor-rows/row-editor-smart-enter
   editor-rows/row-editor-dirty
   editor-rows/row-editor-emoji-offsets
   editor-rows/row-editor-preedit-guard
   parinfer-rows/row-editor-cursor-fn-throw-named
   editor-rows/row-editor-tick-skips-unchanged
   editor-rows/row-on-gui-concurrent
   edit-keys/row-edit-auto-pair
   edit-keys/row-edit-wrap-selection
   edit-keys/row-edit-type-over
   edit-keys/row-edit-backspace-pair
   edit-keys/row-edit-backspace-plain
   edit-keys/row-edit-tab-selection
   edit-keys/row-edit-shift-tab
   edit-keys/row-edit-shift-tab-unindented
   edit-keys/row-edit-toggle-comment
   edit-keys/row-edit-toggle-comment-forward
   edit-keys/row-edit-structural-keys
   edit-keys/row-edit-format-all
   edit-keys/row-edit-format-keeps-caret-in-view
   edit-keys/row-edit-format-selection
   edit-keys/row-edit-format-selection-macos-cmd-option-f
   edit-keys/row-edit-format-selection-macos-cmd-option-l
   edit-keys/row-edit-redo-y
   edit-keys/row-edit-preedit-yields
   read-only-paint/row-read-only-refuses-edits
   read-only-paint/row-paint-async-keeps-gtk-responsive
   read-only-paint/row-paint-async-superseded
   read-only-paint/row-read-only-off-supersedes-paint
   read-only-paint/row-paint-async-stops-on-destroy
   read-only-paint/row-editor-dirty-roundtrip
   incremental/row-incremental-matches-full
   incremental/row-incremental-supersede
   incremental/row-incremental-open-string
   incremental/row-paint-async-drops-after-edit
   parinfer-rows/row-parinfer-open-paren
   parinfer-rows/row-parinfer-dedent-undo-redo
   parinfer-rows/row-reload-text-undoable
   parinfer-rows/row-reload-text-caret-clamps
   parinfer-rows/row-reload-text-parinfer-held-off
   parinfer-rows/row-parinfer-mcp-and-reload-unchanged
   parinfer-rows/row-parinfer-unstable-form-only
   parinfer-rows/row-parinfer-off-no-change
   parinfer-rows/row-parinfer-too-long-form-says-so
   parinfer-rows/row-parinfer-paren-mode-keeps-pairs
   parinfer-rows/row-parinfer-quote-backspace-keeps-pair
   parinfer-rows/row-parinfer-op-keeps-caret
   parinfer-rows/row-parinfer-op-keeps-selection
   parinfer-rows/row-parinfer-engine-failure-named-once
   parinfer-rows/row-parinfer-throw-clears-the-prime
   parinfer-cache/row-parinfer-cache-steady-typing
   parinfer-cache/row-parinfer-cache-invalidated-by-undo
   parinfer-cache/row-parinfer-cache-reprimed-by-set-text
   parinfer-cache/row-parinfer-cache-invalidated-by-plain-op
   parinfer-cache/row-parinfer-selection-replace-bounds
   parinfer-cache/row-parinfer-caret-move-keeps-first-edit-reindent
   parinfer-cache/row-parinfer-fix-moves-the-edit-offset
   parinfer-cache/row-parinfer-noop-action-marks-nothing
   parinfer-cache/row-parinfer-splice-equals-buffer
   parinfer-cache/row-parinfer-miss-still-splices-the-end-read
   parinfer-cache/row-parinfer-reload-primes-the-cache
   parinfer-cache/row-parinfer-reload-double-flip
   parinfer-cache/row-parinfer-plain-op-updates-a-valid-cache
   parinfer-cache/row-parinfer-plain-op-leaves-an-invalid-cache
   parinfer-cache/row-parinfer-nul-reload-clears-the-prime
   parinfer-cache/row-parinfer-nul-plain-op-clears-the-cache
   parinfer-cache/row-parinfer-redo-splices-the-end-read
   parinfer-cache/row-parinfer-undo-recovery-reads-nothing
   parinfer-cache/row-parinfer-undo-redo-interleaving
   parinfer-cache/row-parinfer-undo-past-a-reload
   parinfer-cache/row-parinfer-undo-keeps-first-edit-reindent
   parinfer-format/row-parinfer-format-stabilizes-clean-text
   parinfer-format/row-parinfer-format-lands-untouched
   parinfer-format/row-parinfer-format-too-long-quietly
   parinfer-format/row-parinfer-format-off-byte-identical
   parinfer-format/row-parinfer-format-selection-scoped
   parinfer-format/row-parinfer-format-second-is-noop
   parinfer-format/row-parinfer-format-next-keystroke-hits
   parinfer-format/row-parinfer-format-style-pin-paren-stable
   parinfer-format/row-parinfer-format-style-pin-plain
   parinfer-format/row-apply-op-refuses-nul])

(def ^:private console-row-fns
  [console-rows/row-console-readonly
   console-rows/row-console-readonly-delete
   console-rows/row-console-output-above-input
   console-rows/row-console-submit
   console-rows/row-console-style-after-submit
   console-rows/row-console-unbalanced-newline
   console-rows/row-console-flood
   console-rows/row-console-unicode
   console-rows/row-console-history-multiline-caret
   console-rows/row-console-set-prompt-keeps-selection
   console-rows/row-console-input-fn-throw-named
   console-rows/row-callable-contains-throw
   console-rows/row-console-preedit-guard
   console-rows/row-console-history])

;; --- driver ------------------------------------------------------------
(def ^:private PER-ROW-MS
  ;; row-console-flood's own deadline is 60000 ms; nothing else waits longer
  ;; than row-on-gui-concurrent's 10000 ms. The whole run takes about 3 s.
  90000)

(defn- run-all
  "Runs every row strictly in order (ga/run-rows), prints SUMMARY, then
  quits the app on the GTK thread."
  [app steps]
  (go (<! (ga/run-rows steps PER-ROW-MS check))
      (let [passed (count (filter second @results)) total (count @results)]
        (reset! finished? true)
        (selftest-keys/print-warning!)
        (println (str "SUMMARY " passed " of " total " passed")))
      (<! (timeout 50))
      (on-gtk (g/g-application-quit app))))

(defn- activate [app _data]
  (let [win (g/gtk-application-window-new app)
        ed (editor/make-editor)
        cv (cview/make-console-view {:on-submit stub-on-submit!
                                     :history-prev stub-history-prev
                                     :history-next stub-history-next})
        paned (g/gtk-paned-new g/ORIENTATION-VERTICAL)]
    (g/gtk-paned-set-start-child paned (:scroll ed))
    (g/gtk-paned-set-end-child paned (:scroll cv))
    (g/gtk-paned-set-position paned 300)
    ;; the navy theme, easier on the eyes; JONQUIL_SELFTEST_THEME picks
    ;; another (an unknown name falls back to navy)
    (let [k (some-> (System/getenv "JONQUIL_SELFTEST_THEME") keyword)
          t (theme/theme (if (some #{k} theme/theme-names) k :navy))
          provider (g/gtk-css-provider-new)]
      (editor/apply-theme! ed t)
      (cview/apply-theme! cv t)
      (g/gtk-widget-add-css-class (:view ed) "jonquil-code")
      (g/gtk-widget-add-css-class (:view cv) "jonquil-code")
      (g/gtk-css-provider-load-from-string provider (theme/code-css t commands/DEFAULT-FONT-PX))
      (g/gtk-style-context-add-provider-for-display
       (g/gdk-display-get-default) provider g/STYLE-PROVIDER-PRIORITY-APPLICATION)
      (g/set-color-scheme! (g/gtk-settings-get-default) (:dark? t)))
    (g/gtk-window-set-title win "Jonquil editor self-test")
    (g/gtk-window-set-default-size win 700 700)
    (g/gtk-window-set-child win paned)
    (editor/on-dirty-changed! ed (fn [dirty] (when dirty (swap! true-fire-count inc))))
    (selftest-keys/watch! win)
    (g/gtk-window-present win)
    ;; recording a demo: JONQUIL_SELFTEST_MAXIMIZE fills the screen
    (when (System/getenv "JONQUIL_SELFTEST_MAXIMIZE") (g/gtk-window-maximize win))
    (editor/focus! ed)
    (go (<! (timeout 300))
        (<! (run-all app (into (mapv (fn [row] (fn [] (row ed))) row-fns)
                               (mapv (fn [row] (fn [] (row cv))) console-row-fns)))))))

(defn -main [& _]
  (let [app (g/gtk-application-new "net.b12n.jonquil.selftest" g/APPLICATION-DEFAULT-FLAGS)]
    (g/g-signal-connect-data app "activate"
                             (cb/callable activate [:pointer :pointer] :void)
                             ffi/null ffi/null g/CONNECT-DEFAULT)
    (g/g-application-run app 0 ffi/null)
    (when-not @finished?
      (binding [*out* *err*]
        (println "[gtk-selftest] run ended before the last row; no SUMMARY, exiting 1")))
    (System/exit (if (and @finished? (seq @results) (every? second @results)) 0 1))))
