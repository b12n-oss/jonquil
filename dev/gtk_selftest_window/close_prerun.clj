(ns gtk-selftest-window.close-prerun
  "The bracket check before Run (unclosed opener, stray closer, the
  REPL/Run hint boundary), Line/Col, and the close-dirty/alert prompts."
  (:require [clojure.string :as str]
            [gtk-selftest-window.support :as support]
            [jonquil.eval :as eval]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]))

(defn row-run-unbalanced [ui next]
  ;; A fixture starting "(ok)" would throw an unresolved-symbol error at
  ;; EVAL time, before load-string's read/eval loop ever reaches the
  ;; unclosed third form, so the result would be a plain :error, never
  ;; "EOF while reading", and the unclosed-bracket message would never
  ;; fire. (+ 1 1) evaluates cleanly, so the loop advances to read the
  ;; still-unclosed third form and genuinely hits EOF -- same line-3
  ;; shape, the intended code path.
  (let [mark (support/out-end-offset ui)]
    (editor/set-text! (:editor ui) "(+ 1 1)\n\n(defn f [x]")
    ((:run!* ui))
    (support/poll-until 3000 20
                (fn [] (str/includes? (support/since ui mark) "The ( on line 3 is never closed."))
                (fn [ok]
                  (support/check :run-unbalanced ok {:console (support/since ui mark)})
                  (next)))))

(defn row-repl-eof-no-editor-hint
  "M11: the EOF-unclosed-bracket hint reads the DEFINITIONS editor's text,
  so it must fire only for Runs, never for a REPL error. Sets the
  Definitions text to something WITH an unclosed bracket -- if the guard
  were missing, on-complete would misapply this Run-only hint and name
  THIS opener even though it's the REPL entry's own nested load-string
  that actually failed. (load-string \"(+ 1\") is balanced at the REPL's
  own top level (console/input-action lets it through to :eval), so the
  reader only hits EOF one level down, inside the nested load-string
  call -- verified against this Jolt to throw exactly \"EOF while
  reading\" at the top of the exception, same message text as a Run's own
  unbalanced Definitions."
  [ui next]
  (let [ed (:editor ui)
        cv (:console ui)
        mark (support/out-end-offset ui)]
    (editor/set-text! ed "(defn f [x]")
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(load-string \"(+ 1\")" -1 1)
    (cview/handle-return! cv false)
    (support/poll-until 3000 20
                (fn [] (and (support/idle? ui) (str/includes? (support/since ui mark) "EOF while reading")))
                (fn [ok]
                  (support/check :repl-eof-no-editor-hint
                         (and ok (not (str/includes? (support/since ui mark) "is never closed.")))
                         {:console (support/since ui mark)})
                  (next)))))

;; --- P3.T5: Line/Col, ask before closing, bracket check before Run --------

(defn row-line-col
  "place_cursor is one of probe 6's own two measured triggers for mark-set;
  the label update itself is synchronous (the buffer's \"mark-set\" signal
  fires inline from place-cursor!'s own call, not via an on-gui! drain), so
  this reads the label right back, no poll needed. \"ab\\ncd\" offset 4 is
  the 'd', 0-based [line 1, col 1] -> 1-based \"Ln 2, Col 2\"."
  [ui next]
  (let [ed (:editor ui)]
    (editor/set-text! ed "ab\ncd")
    (support/place-cursor! (:buf ed) 4)
    (let [label (g/label-text (:line-col-label ui))]
      (support/check :line-col (= "Ln 2, Col 2" label) {:label label})
      (next))))

(defn row-close-clean
  "Clean editor: close-request-fn's own else branch (would quit) fires
  immediately, no dialog. Driven with suppress-close? set (window.clj's
  close-request-fn docstring): the window is never really destroyed here,
  so later rows keep a live window -- row-command-chord-close (still the
  suite's sole deliberately-last row) is what actually exercises a real
  close ending the process. closing? becoming true is this row's stand-in
  for \"the window closes\"."
  [ui next]
  (let [ed (:editor ui)]
    (editor/set-text! ed "(+ 1 1)")
    (reset! (:suppress-close? ui) true)
    ((:request-close!* ui))
    (let [closing @(:closing? ui)
          dirty-after (editor/dirty? ed)]
      (reset! (:suppress-close? ui) false)
      (reset! (:closing? ui) false)
      (support/check :close-clean (and (not dirty-after) closing)
             {:dirty-after dirty-after :closing closing})
      (next))))

(defn row-close-dirty-cancel
  "Dirty editor: request-close!* shows the real GtkAlertDialog (probe 3 --
  there is no programmatic way to pick a labelled button on it) and
  suppresses the close (returns 1) regardless of suppress-close?, since
  close-request-fn's dirty branch doesn't consult it at all. close-choice!
  is then driven with a synthetic {:index 2} (probe 3's own pattern) to
  verify the decision logic without a human: index 2 (Cancel) must do
  nothing, leaving the window open, the text unchanged, and still dirty.

  request-close!* leaves a real, abandoned alert pending (close-choice!
  is driven synthetically here, never through the real dialog's own
  completion), so alert-pending? is reset by hand afterward -- otherwise
  the next dirty close-request row would find one still \"pending\" and
  never open its own alert at all (row-close-alert-pending-guard's own
  guard, applied to a row that isn't testing it)."
  [ui next]
  (let [ed (:editor ui)]
    (editor/set-text! ed "(+ 1 1)")
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf ed) "z" -1 1)
    (let [dirty-before (editor/dirty? ed)
          text-before (editor/editor-text ed)]
      ((:request-close!* ui))
      ;; suppress-close? so a Cancel that wrongly confirmed the close
      ;; shows up as closing? here instead of ending the run.
      (reset! (:suppress-close? ui) true)
      ((:close-choice! ui) {:index 2})
      (let [dirty-after (editor/dirty? ed)
            text-after (editor/editor-text ed)
            closing @(:closing? ui)]
        (reset! (:suppress-close? ui) false)
        (reset! (:closing? ui) false)
        (reset! (:alert-pending? ui) false)
        (support/check :close-dirty-cancel
               (and dirty-before (= text-before text-after) dirty-after (not closing))
               {:dirty-before dirty-before :dirty-after dirty-after
                :text-before text-before :text-after text-after :closing closing})
        (next)))))

(defn row-close-dirty-discard
  "As close-dirty-cancel, but {:index 1} (Don't Save): close-choice! must
  confirm and finish-close! (gtk_window_close again, re-entering close-
  request-fn). suppress-close? intercepts the real close there (see
  window.clj's close-request-fn docstring) -- closing? becoming true,
  asserted through this test hook rather than an actual close, is the
  row's evidence that the confirm-and-close decision fired.

  Also resets alert-pending? -- see close-dirty-cancel's own note on
  why."
  [ui next]
  (let [ed (:editor ui)]
    (editor/set-text! ed "(+ 1 1)")
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf ed) "z" -1 1)
    (let [dirty-before (editor/dirty? ed)]
      ((:request-close!* ui))
      (reset! (:suppress-close? ui) true)
      ((:close-choice! ui) {:index 1})
      (let [closing @(:closing? ui)]
        (reset! (:suppress-close? ui) false)
        (reset! (:closing? ui) false)
        (reset! (:alert-pending? ui) false)
        (support/check :close-dirty-discard (and dirty-before closing)
               {:dirty-before dirty-before :closing closing})
        (next)))))

(defn row-close-dirty-save
  "A remembered path (via the real :save-to! path, so close-choice!'s
  index-0 branch takes the has-a-path arm, not the file-dialog one) then
  dirtied again. {:index 0} must save the CURRENT (dirtied) text to that
  path and, since the save clears dirty?, confirm and close (suppressed,
  same as close-dirty-discard).

  Also resets alert-pending? -- see close-dirty-cancel's own note on
  why."
  [ui next]
  (let [ed (:editor ui)
        tmp (str (System/getProperty "java.io.tmpdir")
                 "/jonquil-selftest-close-save-" (System/currentTimeMillis) ".clj")]
    (spit tmp "(+ 0 0)")
    (editor/set-text! ed "(+ 5 5)")
    ((:save-to! ui) tmp)
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf ed) "z" -1 1)
    (let [dirty-before (editor/dirty? ed)]
      ((:request-close!* ui))
      (reset! (:suppress-close? ui) true)
      ((:close-choice! ui) {:index 0})
      (let [closing @(:closing? ui)
            saved-content (slurp tmp)
            dirty-after (editor/dirty? ed)]
        (reset! (:suppress-close? ui) false)
        (reset! (:closing? ui) false)
        (reset! (:alert-pending? ui) false)
        (support/check :close-dirty-save
               (and dirty-before closing (not dirty-after) (= "(+ 5 5)z" saved-content))
               {:dirty-before dirty-before :closing closing :dirty-after dirty-after
                :saved-content saved-content})
        (.delete (java.io.File. ^String tmp))
        (next)))))

(defn row-pre-run-hidden-by-error
  "(undefined-fn) would throw an unresolved-symbol error at EVAL time if
  Run ever reached it -- the bracket check must run FIRST and skip
  evaluation entirely, so only the line-2 unclosed-opener message shows,
  never the eval error the first form would otherwise produce."
  [ui next]
  (let [mark (support/out-end-offset ui)]
    (editor/set-text! (:editor ui) "(undefined-fn)\n(defn g [y]")
    ((:run!* ui))
    (support/poll-until 3000 20
                (fn [] (str/includes? (support/since ui mark) "The ( on line 2 is never closed."))
                (fn [ok]
                  (let [t (support/since ui mark)]
                    (support/check :pre-run-hidden-by-error
                           (and ok (not (str/includes? t "Unable to resolve symbol")))
                           {:console t})
                    (next))))))

(defn row-pre-run-stray-closer
  "An extra closer with no opener at all (as opposed to an unclosed
  opener, row-run-unbalanced's own case)."
  [ui next]
  (let [mark (support/out-end-offset ui)]
    (editor/set-text! (:editor ui) "(+ 1 2))")
    ((:run!* ui))
    (support/poll-until 3000 20
                (fn [] (str/includes? (support/since ui mark) "The ) on line 1 has no matching opener."))
                (fn [ok]
                  (support/check :pre-run-stray-closer ok {:console (support/since ui mark)})
                  (next)))))

;; --- P3.T5: close-alert dialog identity, and the run-while-running warning

(defn row-close-alert-pending-guard
  "The guard half: a second close request while the first alert is still
  pending must not build a SECOND real GtkAlertDialog and silently hand
  it the shared 'current dialog' state, orphaning the first
  (row-close-alert-routing, below, covers the other half -- per-dialog
  completion routing). close-request-fn refuses to open a second alert
  while one is pending. Two dirty :request-close!*
  calls in a row must build only ONE real dialog (alert-dialog-count);
  answering that one pending alert with a synthetic Don't Save must still
  confirm the close via :closing?."
  [ui next]
  (let [ed (:editor ui)
        count-before @(:alert-dialog-count ui)]
    (editor/set-text! ed "(+ 1 1)")
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf ed) "z" -1 1)
    ((:request-close!* ui))
    ((:request-close!* ui))
    (let [dialogs-created (- @(:alert-dialog-count ui) count-before)]
      (reset! (:suppress-close? ui) true)
      ((:close-choice! ui) {:index 1})
      (let [closing @(:closing? ui)]
        (reset! (:suppress-close? ui) false)
        (reset! (:closing? ui) false)
        (reset! (:alert-pending? ui) false)
        (support/check :close-alert-pending-guard
               (and (= 1 dialogs-created) closing)
               {:dialogs-created dialogs-created :closing closing})
        (next)))))

(defn row-close-alert-routing
  "The routing half: the completion callback must route by its OWN
  dialog identity (src -- GAsyncReadyCallback's own source_object
  argument for a real dialog), never a shared 'current dialog' atom.
  Drives complete-alert! directly with two distinct SYNTHETIC \"dialogs\"
  (plain keywords, never passed to any real GTK call) and a counting
  release stub swapped in via :alert-release! (so nothing GTK-side is
  ever touched), completing them in REVERSE registration order -- the
  shared-atom
  bug's exact failure shape: with :dialog-b registered after :dialog-a,
  completing :dialog-b first coincidentally looks right (it IS the
  'current' one), but completing :dialog-a next still uses the STALE
  'current' state (:dialog-b's, since nothing ever cleared it), so
  :dialog-a's own on-choice never fires and :dialog-b gets released a
  second time. A third, stray re-invocation of :dialog-a (already
  completed) must be a no-op, not another on-choice call or release."
  [ui next]
  (let [calls (atom [])
        released (atom [])
        prior-release @(:alert-release! ui)]
    (reset! (:alert-release! ui) (fn [d] (swap! released conj d)))
    ((:register-alert-choice-for-test! ui) :dialog-a (fn [r] (swap! calls conj [:a r])))
    ((:register-alert-choice-for-test! ui) :dialog-b (fn [r] (swap! calls conj [:b r])))
    ((:complete-alert-for-test! ui) :dialog-b {:index 0})
    ((:complete-alert-for-test! ui) :dialog-a {:index 1})
    ((:complete-alert-for-test! ui) :dialog-a {:index 1}) ; stray re-invocation
    (reset! (:alert-release! ui) prior-release)
    (support/check :close-alert-routing
           (and (= [[:b {:index 0}] [:a {:index 1}]] @calls)
                (= [:dialog-b :dialog-a] @released))
           {:calls @calls :released @released})
    (next)))

(defn row-run-while-running-keeps-warning
  "A Run while one is already running must not run commands/pre-run-
  problem on the SECOND buffer regardless: an unbalanced second buffer
  must not set status to Ready (the FIRST program is still running) or
  append a bracket :error into the middle of its output instead of P2's
  own 'already in progress' warning. Starts (loop []
  (recur)), waits for it to really be evaluating, THEN sets an unbalanced
  buffer and calls run!* again: the console must show the P2 warning and
  no bracket sentence, and the status must still read the running status,
  not Ready."
  [ui next]
  (let [mark (support/out-end-offset ui)]
    (editor/set-text! (:editor ui) "(loop [] (recur))")
    ((:run!* ui))
    (support/poll-until 2000 20
                (fn [] (eval/evaluating? (:ctx (:session ui))))
                (fn [started?]
                  (editor/set-text! (:editor ui) "(+ 1 2))")
                  ((:run!* ui))
                  (support/poll-until 1000 20
                              (fn [] (str/includes? (support/since ui mark) "already in progress"))
                              (fn [warned?]
                                (let [t (support/since ui mark)
                                      status (g/label-text (:status-label ui))]
                                  ((:stop!* ui))
                                  (support/poll-until 3000 20
                                              (fn [] (str/includes? (support/since ui mark) "; interrupted"))
                                              (fn [stopped?]
                                                (support/check :run-while-running-keeps-warning
                                                       (and started? warned? stopped?
                                                            (= "Running…" status)
                                                            (not (str/includes? t "no matching opener")))
                                                       {:started started? :warned warned? :stopped stopped?
                                                        :status status :console t})
                                                (next))))))))))

(defn row-line-col-typing
  "I2: an insert moves the insert mark by gravity without emitting
  mark-set, so Ln/Col must follow the buffer's notify::cursor-position
  instead. \"ab\\ncd\" with the caret at 4 reads \"Ln 2, Col 2\"; typing
  \"x\" there must advance it to \"Ln 2, Col 3\"."
  [ui next]
  (let [ed (:editor ui) buf (:buf ed)]
    (editor/set-text! ed "ab\ncd")
    (support/place-cursor! buf 4)
    (let [before (g/label-text (:line-col-label ui))]
      (g/gtk-text-buffer-insert-interactive-at-cursor buf "x" -1 1)
      (let [after (g/label-text (:line-col-label ui))]
        (support/check :line-col-typing (and (= "Ln 2, Col 2" before) (= "Ln 2, Col 3" after))
               {:before before :after after})
        (next)))))

(defn row-close-dismissed
  "#7: a dismissed alert ({:error ..}, close-choice!'s error branch) leaves
  the window open, the buffer dirty and the text unchanged."
  [ui next]
  (let [ed (:editor ui)]
    (editor/set-text! ed "(+ 1 1)")
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf ed) "z" -1 1)
    (reset! (:suppress-close? ui) true)
    (let [text-before (editor/editor-text ed)]
      ((:close-choice! ui) {:error "dismissed"})
      (let [closing @(:closing? ui)
            dirty-after (editor/dirty? ed)
            text-after (editor/editor-text ed)]
        (reset! (:suppress-close? ui) false)
        (reset! (:closing? ui) false)
        (support/check :close-dismissed
               (and (not closing) dirty-after (= text-before text-after))
               {:closing closing :dirty-after dirty-after
                :text-before text-before :text-after text-after})
        (next)))))

(defn row-close-save-failed
  "#7: Save chosen ({:index 0}) with a remembered path whose save fails
  (its directory was removed after the first save) must not close: the
  window stays open, the buffer stays dirty, the text is unchanged, and
  the failure reaches the console."
  [ui next]
  (let [ed (:editor ui)
        dir (java.io.File. (str (System/getProperty "java.io.tmpdir")
                                "/jonquil-selftest-close-fail-" (System/currentTimeMillis)))
        path (str (.getPath dir) "/f.clj")
        mark (support/out-end-offset ui)]
    (.mkdirs dir)
    (editor/set-text! ed "(+ 5 5)")
    ((:save-to! ui) path)
    (.delete (java.io.File. ^String path))
    (.delete dir)
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf ed) "z" -1 1)
    (reset! (:suppress-close? ui) true)
    (let [text-before (editor/editor-text ed)]
      ((:close-choice! ui) {:index 0})
      (let [closing @(:closing? ui)
            dirty-after (editor/dirty? ed)
            text-after (editor/editor-text ed)]
        (reset! (:suppress-close? ui) false)
        (reset! (:closing? ui) false)
        (support/poll-until 1000 20
                    (fn [] (str/includes? (support/since ui mark) "; save:"))
                    (fn [reported?]
                      (support/check :close-save-failed
                             (and (not closing) dirty-after (= text-before text-after) reported?
                                  (not (.exists dir)))
                             {:closing closing :dirty-after dirty-after :text-before text-before
                              :text-after text-after :reported reported? :console (support/since ui mark)})
                      (next)))))))

(defn row-close-alert-build-throws
  "#10: if building the alert throws after alert-pending? is set, the flag
  must reset (or every later close silently does nothing) and the error
  must reach the console. Forces the throw by redefining the alert's
  constructor for this one close request."
  [ui next]
  (let [ed (:editor ui)
        mark (support/out-end-offset ui)]
    (editor/set-text! ed "(+ 1 1)")
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf ed) "z" -1 1)
    (reset! (:suppress-close? ui) true)
    (with-redefs [g/g-object-new-with-properties
                  (fn [& _] (throw (ex-info "forced alert build failure" {})))]
      ((:request-close!* ui)))
    (let [pending @(:alert-pending? ui)
          closing @(:closing? ui)
          dirty-after (editor/dirty? ed)]
      (reset! (:suppress-close? ui) false)
      (reset! (:closing? ui) false)
      (reset! (:alert-pending? ui) false)
      (support/poll-until 1000 20
                  (fn [] (str/includes? (support/since ui mark) "forced alert build failure"))
                  (fn [reported?]
                    (support/check :close-alert-build-throws
                           (and (not pending) (not closing) dirty-after reported?)
                           {:pending pending :closing closing :dirty-after dirty-after
                            :reported reported? :console (support/since ui mark)})
                    (next))))))

(defn row-quit-action-prompts
  "C3: the application's \"quit\" action, activated the way GTK's macOS
  delegate activates it on a Quit AppleEvent (Dock -> Quit, logout),
  must go through the close prompt: dirty opens the alert and does not
  close; clean closes (suppress-close? stands in for the real quit, as in
  close-clean)."
  [ui next]
  (let [ed (:editor ui)
        count-before @(:alert-dialog-count ui)]
    (editor/set-text! ed "(+ 1 1)")
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf ed) "z" -1 1)
    (reset! (:suppress-close? ui) true)
    ((:activate-quit-action!* ui))
    (let [dialogs (- @(:alert-dialog-count ui) count-before)
          pending @(:alert-pending? ui)
          closing-dirty @(:closing? ui)
          text-after (editor/editor-text ed)]
      (reset! (:alert-pending? ui) false)
      (editor/set-text! ed "(+ 1 1)")
      ((:activate-quit-action!* ui))
      (let [closing-clean @(:closing? ui)]
        (reset! (:suppress-close? ui) false)
        (reset! (:closing? ui) false)
        (support/check :quit-action-prompts
               (and (= 1 dialogs) pending (not closing-dirty) (= "(+ 1 1)z" text-after)
                    closing-clean)
               {:dialogs dialogs :pending pending :closing-dirty closing-dirty
                :text-after text-after :closing-clean closing-clean})
        (next)))))
