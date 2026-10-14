(ns gtk-selftest-nrepl.rows
  "Rows for the nREPL window. Every shell call runs on its own thread
  (a/thread) and is bounded, and every signal goes to a pid confirmed as
  this process's own child."
  (:require [clojure.core.async :as a :refer [<! timeout]]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [gtk-async :refer [on-gtk await-until go-row]]
            [gtk-selftest-window.support :as support]
            [gtk-selftest-window.tabs :as tabs]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.help-popover :as help-popover]
            [jonquil.nrepl.evaluator :as nrepl]
            [jonquil.session]
            [temp-dirs]))

(def fixture-core
  "(ns demo.core)\n\n(defn greet\n  \"Says hi.\"\n  [n]\n  (println \"hi\" n)\n  :hi)\n")

;; D2.T5: the fixture!-built project's own {:dir :file}, stashed here so a
;; row (row-nrepl-open-switches-evaluator, reopening it after switching
;; away) and after-close-checks! (reading its .nrepl-port back, after
;; main/start! has returned and -main's own local is out of scope) can
;; both reach it without threading it through every row-fn's [ui]
;; signature.
(defonce ^:private fixture* (atom nil))

;; D2.T5: the pid row-nrepl-close-kills-child records right before quitting,
;; read back by after-close-checks! once the GTK loop is gone.
(defonce ^:private last-child-pid (atom nil))

(defn fixture!
  "A throwaway project: deps.edn, .jonquil.edn {:runtime :nrepl}, a
  .nrepl-port that belongs to \"someone else\" (4242), and
  src/demo/core.clj. {:dir :file}, both canonical.

  I2: the dir name is unique (currentTimeMillis plus a random-uuid, not
  nanoTime, which counts from process start and can collide across
  processes). A collision was already harmless here, since this fn
  itself spits fresh deps.edn/.jonquil.edn/.nrepl-port/source content
  into the dir every time it runs, never trusting anything already
  there -- unlike dialects.clj's own open-then-run, which skips writing
  .jonquil.edn when :config is nil and so could inherit a stale one
  (the real bug). Given the same unique name anyway (the brief), but
  cleanup stays out of scope: after-close-checks! reads this dir's own
  .nrepl-port back after main/start! returns, so deleting it here isn't
  trivially safe: the driver deletes it (temp-dirs/delete-made!) only
  once those checks have run."
  []
  (let [d (io/file (temp-dirs/temp-dir! "jonquil-selftest-nrepl-"))
        f (io/file d "src/demo/core.clj")]
    (.mkdirs (.getParentFile f))
    (spit (io/file d "deps.edn") "{:paths [\"src\"]}")
    (spit (io/file d ".jonquil.edn") "{:runtime :nrepl}")
    (spit (io/file d ".nrepl-port") "4242\n")
    (spit f fixture-core)
    (let [result {:dir (.getCanonicalPath d) :file (.getCanonicalPath f)}]
      (reset! fixture* result)
      result)))

(defn- loose-fixture!
  "A file with no project markers above it and no .jonquil.edn -- so
  dialect/select falls through to the extension alone, which picks the
  Jolt profile's own default runtime, :in-process.

  I2: unique dir name, same reasoning as fixture!'s own docstring above."
  []
  (let [d (io/file (temp-dirs/temp-dir! "jonquil-selftest-loose-"))
        f (io/file d "scratch.clj")]
    (.mkdirs d)
    (spit f "(+ 1 2)\n")
    (.getCanonicalPath f)))

(defn evaluator [ui] ((:evaluator* ui)))

(defn idle? [ui] (not (ev/evaluating? (evaluator ui))))

(defn run-and-await
  "support/run-and-await's contract, idle read through the evaluator."
  [ui code deadline-ms expect-fn]
  (let [mark (on-gtk (let [m (support/out-end-offset ui)]
                       (editor/set-text! (:editor ui) code)
                       ((:run!* ui))
                       m))
        ok (await-until deadline-ms 20 #(and (idle? ui) (expect-fn (support/since ui mark))))]
    [ok mark]))

(defn submit-and-await
  "Types text at the prompt and submits it the way run-repl/row-repl-after-run
  does (insert-interactive, then handle-return!), then parks like
  run-and-await. [ok mark]."
  [ui text deadline-ms expect-fn]
  (let [mark (on-gtk (let [cv (:console ui)
                           m (support/out-end-offset ui)]
                       (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) text -1 1)
                       (cview/handle-return! cv false)
                       m))
        ok (await-until deadline-ms 20 #(and (idle? ui) (expect-fn (support/since ui mark))))]
    [ok mark]))

(defn- sh-within
  "(apply shell/sh args) on its own thread (a/thread); its result map, or
  nil after 5000 ms. Parks, so call it from a go block."
  [args]
  (let [ch (a/thread (apply shell/sh args))
        [r port] (a/alts! [ch (timeout 5000)])]
    (when (= port ch) r)))

(defn own-child-pid
  "The window's child pid once pgrep -P confirms it's this process's own
  child; nil otherwise. Rows signal nothing else."
  [ui]
  (let [pid (nrepl/child-pid (evaluator ui))
        self (.pid (java.lang.ProcessHandle/current))
        kids (set (keep parse-long (str/split-lines (str (:out (sh-within ["pgrep" "-P" (str self)]))))))]
    (when (and pid (contains? kids pid)) pid)))

(defn signal!
  "kill -<sig> pid, for a pid own-child-pid returned."
  [pid sig]
  (sh-within ["kill" sig (str pid)])
  nil)

(defn alive?
  "Whether pid is alive, through ps."
  [pid]
  (str/includes? (str (:out (sh-within ["ps" "-p" (str pid) "-o" "pid="]))) (str pid)))

;; --- D2.T4 rows ------------------------------------------------------------

(defn row-nrepl-starts-and-reports
  "Waits (up to 20000 ms, a restart can be in flight at open) for the
  startup report. Reads the console from its very start (since 0), not
  from a mark captured at row entry: start-and-report! runs on a future as
  soon as the window opens, well before the driver's own first row runs,
  so a mark taken here would already sit after the report."
  [ui]
  (go-row :nrepl-starts-and-reports support/check
    (let [started? (await-until 20000 20 #(str/includes? (support/since ui 0) "; Jolt (nREPL) is running (pid "))
          pid (own-child-pid ui)
          [text label reported-pid] (on-gtk (let [t (support/since ui 0)
                                                  l (g/label-text (:dialect-label ui))]
                                              [t l (some-> (re-find #"pid (\d+)" t) second parse-long)]))
          ready? (await-until 2000 20 #(= "Ready" (g/label-text (:status-label ui))))]
      (on-gtk (support/check :nrepl-starts-and-reports
                             (and started? (= "Jolt (nREPL)" label)
                                  (some? pid) (= pid reported-pid)
                                  (str/includes? text "; Limit: No stdin")
                                  (str/includes? text "jolt-lang/nrepl middleware")
                                  (str/includes? text "no file or line")
                                  ready?)
                             {:started started? :label label :pid pid
                              :reported-pid reported-pid :ready? ready?})))))

(defn row-nrepl-run-output
  "Run fixture-core plus (greet 1): the child prints hi 1 and the load
  answers => :hi, and the prompt namespace follows load-file into
  demo.core, exactly as an in-process Run does."
  [ui]
  (go-row :nrepl-run-output support/check
    (let [[ok? mark] (run-and-await
                      ui (str fixture-core "(greet 1)") 5000
                      #(and (str/includes? % "hi 1") (str/includes? % "=> :hi")))]
      (on-gtk (support/check :nrepl-run-output
                             (and ok? (= 'demo.core (ev/prompt-ns (evaluator ui)))
                                  (instance? jonquil.nrepl.evaluator.NRepl (evaluator ui)))
                             {:ok? ok? :ns (ev/prompt-ns (evaluator ui)) :text (support/since ui mark)})))))

(defn row-nrepl-repl-follows-run
  "A REPL entry right after the Run above stays in demo.core."
  [ui]
  (go-row :nrepl-repl-follows-run support/check
    (let [[ok? mark] (submit-and-await
                      ui "(greet 2)" 5000
                      #(and (str/includes? % "hi 2") (str/includes? % "=> :hi")))]
      (on-gtk (support/check :nrepl-repl-follows-run
                             (and ok? (instance? jonquil.nrepl.evaluator.NRepl (evaluator ui)))
                             {:text (support/since ui mark)})))))

(defn row-nrepl-error-is-text
  "An out-of-process error is text (D2.T1), never a Throwable: result-line
  reads it through console/error-text without throwing."
  [ui]
  (go-row :nrepl-error-is-text support/check
    (let [[ok? mark] (run-and-await ui "(/ 1 0)" 5000 #(str/includes? % "Error: Divide by zero"))
          ready? (await-until 2000 20 #(= "Ready" (g/label-text (:status-label ui))))]
      (on-gtk (support/check :nrepl-error-is-text (and ok? ready?)
                             {:text (support/since ui mark) :ready? ready?})))))

(defn row-nrepl-reset-clears-child
  "Reset on Run (Review focus 4): two Runs in :reset mode leave nothing of
  user/kept behind for a submit to see; the same two Runs in :accumulate
  mode do."
  [ui]
  (go-row :nrepl-reset-clears-child support/check
    (let [[ok1 _] (run-and-await ui "(def kept 1)" 5000 (constantly true))
          [ok2 _] (run-and-await ui "(+ 1 1)" 5000 (constantly true))
          [ok3 mark1] (submit-and-await ui "(resolve 'user/kept)" 5000 (constantly true))
          text1 (on-gtk (support/since ui mark1))
          _ (on-gtk (ev/set-mode! (evaluator ui) :accumulate))
          [ok4 _] (run-and-await ui "(def kept 1)" 5000 (constantly true))
          [ok5 _] (run-and-await ui "(+ 1 1)" 5000 (constantly true))
          [ok6 mark2] (submit-and-await ui "user/kept" 5000 (constantly true))
          text2 (on-gtk (support/since ui mark2))
          _ (on-gtk (ev/set-mode! (evaluator ui) :reset))]
      (on-gtk (support/check :nrepl-reset-clears-child
                             (and ok1 ok2 ok3 ok4 ok5 ok6
                                  (str/includes? text1 "=> nil")
                                  (str/includes? text2 "=> 1")
                                  (instance? jonquil.nrepl.evaluator.NRepl (evaluator ui)))
                             {:text1 text1 :text2 text2})))))

(defn row-nrepl-stdin-notice
  "No input-forwarding middleware exists (D2 overview, measured): the
  console gets the static stdin notice instead of ever waiting for input."
  [ui]
  (go-row :nrepl-stdin-notice support/check
    (let [[ok? mark] (run-and-await ui "(read-line)" 5000 #(str/includes? % "=> nil"))]
      (on-gtk (let [text (support/since ui mark)]
                (support/check :nrepl-stdin-notice
                               (and ok?
                                    (str/includes? text nrepl/stdin-notice)
                                    (not (ev/waiting-for-input? (evaluator ui))))
                               {:text text}))))))

(defn row-nrepl-completion-from-child
  "After Running fixture-core, \"(gre\" with the caret at 4 completes to
  \"(greet\" -- no clojure.core name starts with gre (measured) and the
  buffer defines none, so only the child's own completions op can have
  supplied the single candidate that committed."
  [ui]
  (go-row :nrepl-completion-from-child support/check
    (let [_ (run-and-await ui fixture-core 5000 (constantly true))]
      (on-gtk (let [ed (:editor ui)]
                (editor/set-text! ed "(gre")
                (support/place-cursor! (:buf ed) 4)
                ((:complete!* ui))
                (support/check :nrepl-completion-from-child
                               (= "(greet" (editor/editor-text ed))
                               {:text (editor/editor-text ed)}))))))

(defn row-nrepl-quick-doc-from-child
  "(greet 1), caret 2, quick doc: the popover's text can only have come
  from the child's own lookup op, since demo.core/greet is defined there
  and nowhere in this buffer or clojure.core."
  [ui]
  (go-row :nrepl-quick-doc-from-child support/check
    (on-gtk (let [ed (:editor ui)]
              (editor/set-text! ed "(greet 1)")
              (support/place-cursor! (:buf ed) 2)
              ((:quick-doc!* ui))
              (let [text (help-popover/quick-doc-text (:help-popover ui))]
                (support/check :nrepl-quick-doc-from-child
                               (and (str/includes? text "demo.core/greet") (str/includes? text "Says hi."))
                               {:text text}))))))

;; --- D2.T5 rows: switching runtimes on open, and the window's failure
;; rows (Stop against an unresponsive child, a child that dies, quit with
;; a live child) ---------------------------------------------------------

(defn row-nrepl-stop-interrupts
  "A plain interrupt (Review focus 2's easy case): the child answers, so
  Stop ends the evaluation without ever restarting it -- the same pid
  before and after, and `before`'s binding (set at the top of the same
  Run) survives, unlike a restart, which would lose it.

  Stop must land after the loop is running. Pressing it once the file was
  read but before `(def before 1)` ran left `before` unbound (Linux spike
  finding 4), so the row waits for proof the loop started before Stop.
  The server only sends a program's printed output when the evaluation
  ends, so a `println` can't be that proof: the buffer writes a marker
  file instead, which the row polls."
  [ui]
  (go-row :nrepl-stop-interrupts support/check
    (let [marker (io/file (temp-dirs/temp-dir! "jonquil-selftest-nrepl-") "looping")
          mark (on-gtk (let [m (support/out-end-offset ui)]
                         (editor/set-text! (:editor ui)
                                           (str "(def before 1)\n(spit " (pr-str (.getPath marker)) " \"looping\")\n(loop [] (recur))"))
                         ((:run!* ui))
                         m))
          started? (await-until 2000 20 #(not (idle? ui)))
          looping? (await-until 5000 20 #(.exists marker))
          before-pid (own-child-pid ui)
          _ (on-gtk ((:stop!* ui)))
          interrupted? (await-until 5000 20 #(and (idle? ui) (str/includes? (support/since ui mark) "; interrupted")))
          after-pid (own-child-pid ui)
          [ok? mark2] (submit-and-await ui "before" 5000 #(str/includes? % "=> 1"))]
      (on-gtk (support/check :nrepl-stop-interrupts
                             (and started? looping? interrupted? ok?
                                  (some? before-pid) (= before-pid after-pid))
                             {:started started? :looping looping? :interrupted interrupted?
                              :before-pid before-pid :after-pid after-pid
                              :text (support/since ui mark2)})))))

(defn row-nrepl-stop-unresponsive-restarts
  "Review focus 2's hard case: a SIGSTOPped child never answers interrupt,
  so Stop falls back to killing and restarting it -- a different pid, the
  console naming both the interrupt and the restart, and the runtime
  working again afterward.

  The child is frozen only once the loop is running, proven by a marker
  file as in row-nrepl-stop-interrupts. Freezing it as soon as the window
  turned busy sometimes caught it before the request went out, and the
  wait for its bookkeeping reply alone (request-timeout-ms) could use up
  most of this row's budget."
  [ui]
  (go-row :nrepl-stop-unresponsive-restarts support/check
    (let [marker (io/file (temp-dirs/temp-dir! "jonquil-selftest-nrepl-") "looping")
          mark (on-gtk (let [m (support/out-end-offset ui)]
                         (editor/set-text! (:editor ui)
                                           (str "(spit " (pr-str (.getPath marker)) " \"looping\")\n(loop [] (recur))"))
                         ((:run!* ui))
                         m))
          started? (await-until 2000 20 #(not (idle? ui)))
          looping? (await-until 5000 20 #(.exists marker))
          before-pid (own-child-pid ui)
          _ (signal! before-pid "-STOP")
          _ (on-gtk ((:stop!* ui)))
          msgs? (await-until 15000 20 #(let [t (support/since ui mark)]
                                         (and (str/includes? t "; interrupted")
                                              (str/includes? t "Stop restarted the Jolt runtime"))))
          ;; D2's original re-check loop, inlined: retries own-child-pid
          ;; every 200 ms up to 5000 ms, since restart-for-stop! prints its
          ;; own console line before it actually spawns the replacement
          ;; child.
          after-pid (let [t0 (System/currentTimeMillis)]
                      (loop []
                        (or (own-child-pid ui)
                            (when-not (>= (- (System/currentTimeMillis) t0) 5000)
                              (<! (timeout 200))
                              (recur)))))
          old-alive? (alive? before-pid)
          [ok? mark2] (run-and-await ui "(+ 1 2)" 5000 #(str/includes? % "=> 3"))]
      (on-gtk (support/check :nrepl-stop-unresponsive-restarts
                             (and started? looping? msgs? ok?
                                  (some? before-pid) (some? after-pid)
                                  (not= before-pid after-pid)
                                  (false? old-alive?)
                                  (= "Ready" (g/label-text (:status-label ui))))
                             {:started started? :looping looping? :msgs? msgs?
                              :before-pid before-pid :after-pid after-pid
                              :old-alive? old-alive? :text (support/since ui mark2)})))))

(defn row-nrepl-child-dies-mid-eval
  "Review focus 3: a child that dies outright (SIGKILL, no Stop pressed)
  still ends the evaluation with a sentence instead of hanging, the
  status returns to Ready on its own, and the very next Run notices the
  runtime is gone and starts a fresh one before evaluating."
  [ui]
  (go-row :nrepl-child-dies-mid-eval support/check
    (let [mark (on-gtk (let [m (support/out-end-offset ui)]
                         (editor/set-text! (:editor ui) "(Thread/sleep 30000)")
                         ((:run!* ui))
                         m))
          started? (await-until 2000 20 #(not (idle? ui)))
          pid (own-child-pid ui)
          _ (signal! pid "-9")
          died? (await-until 5000 20 #(and (idle? ui)
                                           (= "Ready" (g/label-text (:status-label ui)))
                                           (str/includes? (support/since ui mark)
                                                          "The Jolt runtime exited before this evaluation finished.")))
          [ok2? mark2] (run-and-await
                        ui "(+ 1 2)" 15000
                        #(and (str/includes? % "; The Jolt runtime had exited, so a new one was started.")
                              (str/includes? % "=> 3")))]
      (on-gtk (support/check :nrepl-child-dies-mid-eval
                             (and started? died? ok2?)
                             {:started started? :died? died?
                              :text (support/since ui mark2)})))))

(defn row-nrepl-open-switches-evaluator
  "Opening a loose file (no markers, no .jonquil.edn) swaps the window off
  the nREPL evaluator onto the in-process one and kills the old child;
  opening the nREPL project's own fixture file again swaps back and
  starts a fresh one."
  [ui]
  (go-row :nrepl-open-switches-evaluator support/check
    (let [old-pid (own-child-pid ui)
          mark (on-gtk (let [m (support/out-end-offset ui)]
                         ((:open-path! ui) (loose-fixture!))
                         m))
          switched1? (await-until 5000 20 #(str/includes? (support/since ui mark)
                                                          "; Switched to Jolt. The previous session is gone."))
          [label1 inproc1?] (on-gtk [(g/label-text (:dialect-label ui))
                                     (instance? jonquil.session.InProcess (evaluator ui))])
          ;; D2's original re-check loop, inlined: retries alive? every
          ;; 200 ms up to 5000 ms until old-pid is gone.
          dead1? (let [t0 (System/currentTimeMillis)]
                   (loop []
                     (cond (not (alive? old-pid)) true
                           (>= (- (System/currentTimeMillis) t0) 5000) false
                           :else (do (<! (timeout 200)) (recur)))))
          mark2 (on-gtk (let [m (support/out-end-offset ui)]
                          ((:open-path! ui) (:file @fixture*))
                          m))
          started2? (await-until 20000 20 #(str/includes? (support/since ui mark2) "; Jolt (nREPL) is running (pid "))
          label2 (on-gtk (g/label-text (:dialect-label ui)))
          [ok3? mark3] (run-and-await ui "(+ 1 2)" 5000 #(str/includes? % "=> 3"))]
      (on-gtk (support/check :nrepl-open-switches-evaluator
                             (and switched1? (= "Jolt" label1) inproc1?
                                  (some? old-pid) dead1?
                                  started2? (= "Jolt (nREPL)" label2) ok3?)
                             {:switched1? switched1? :label1 label1 :inproc1? inproc1?
                              :old-pid old-pid :dead1? dead1? :started2? started2?
                              :label2 label2 :text (support/since ui mark3)})))))

(defn- close-extra-tabs!
  "Marks every tab but the first saved and closes it, then selects the
  first. GTK-thread only."
  [ui]
  (doseq [t (rest ((:tabs* ui)))]
    (editor/mark-saved! (:ed t))
    ((:close-tab! ui) (:id t)))
  ((:select-tab! ui) (:id (first ((:tabs* ui))))))

(defn row-nrepl-two-tabs-owner-reset
  "Tabs plan P2.T4 over nREPL: tab 1 and a new tab each define a
  namespace; Running tab 1 again (a reset) leaves tab 2's tabs.two."
  [ui]
  (go-row :nrepl-two-tabs-owner-reset support/check
    (let [[t1 t2] (on-gtk [(first ((:tabs* ui))) ((:new-tab!* ui))])
          [ok1 _] (<! (tabs/run-tab ui t1 "(ns tabs.one) (def v 1)" 20000 #(str/includes? % "=> ")))
          [ok2 _] (<! (tabs/run-tab ui t2 "(ns tabs.two) (def w 2)" 20000 #(str/includes? % "=> ")))
          [ok3 m3] (<! (tabs/run-tab ui t1 "(ns tabs.one) (def v 1) tabs.two/w" 20000 #(str/includes? % "=> 2")))]
      (on-gtk (try
                (support/check :nrepl-two-tabs-owner-reset (and ok1 ok2 ok3)
                               {:ok1 ok1 :ok2 ok2 :ok3 ok3 :third (support/since ui m3)})
                (finally (close-extra-tabs! ui)))))))

(defn row-nrepl-close-kills-child
  "The deliberately LAST row: records the live child's own pid, leaves the
  editor clean (set-text! never marks it dirty, so the real close below
  takes close-request-fn's immediate real-quit branch, no alert), and
  fires the exact action a Quit AppleEvent activates (window.clj's own
  quit-action handler) -- ev/stop! then runs on the GTK thread inside
  close-request-fn, same as the window self-test's own row-command-chord-
  close. after-close-checks! (below), called once the GTK loop is
  actually gone, is where this row's own pass/fail is recorded."
  [ui]
  (go-row :nrepl-close-kills-child support/check
    (let [pid (own-child-pid ui)]
      ;; The close chord ends the GTK main loop, so nothing may follow
      ;; this on-gtk: no check here, no next step (Review focus 3).
      (on-gtk (reset! last-child-pid pid)
              (editor/set-text! (:editor ui) "")
              ((:activate-quit-action!* ui))))))

(defn after-close-checks!
  "Review focus 1's post-loop evidence: called by -main once main/start!
  returns, when the GTK loop is gone and a blocking clojure.java.shell/sh
  is fine (unlike sh-within above, which parks on a/thread and needs a
  running go block). Three checks, all against row-nrepl-close-kills-
  child's own close:
  - the child pid it recorded no longer answers ps (kill! reached it);
  - the fixture project's .nrepl-port is back to the \"someone else\"
    value fixture! wrote (4242\\n) -- child/kill!'s own restore;
  - this process itself has no leftover children (pgrep -P).

  Deviation from the brief (reported; see the report): the brief's own
  first bullet reads \"ps -p <pid> prints nothing\", but measured (a
  definitely-dead pid, still in range): bare `ps -p <pid>` always prints
  its header line (\"PID TTY TIME CMD\") whether or not the pid is alive,
  so that check can never fail even when the process really is gone --
  it isn't the process's own aliveness. `ps -p <pid> -o pid=` (no header,
  same flag alive? above already uses) prints nothing exactly when
  the pid is dead, measured against a pid this session spawned and
  waited on; used here instead."
  []
  (let [pid @last-child-pid
        self (.pid (java.lang.ProcessHandle/current))
        ps (when pid (shell/sh "ps" "-p" (str pid) "-o" "pid="))
        dir (:dir @fixture*)
        port-file (io/file dir ".nrepl-port")
        port-text (when (.exists port-file) (slurp port-file))
        kids (shell/sh "pgrep" "-P" (str self))]
    (support/check :nrepl-close-kills-child
                   (and (some? pid) (str/blank? (str (:out ps))))
                   {:pid pid :ps-out (:out ps)})
    (support/check :nrepl-close-restores-port-file
                   (= "4242\n" port-text)
                   {:port-text port-text})
    (support/check :nrepl-close-no-children
                   (str/blank? (str (:out kids)))
                   {:pgrep-out (:out kids)})))
