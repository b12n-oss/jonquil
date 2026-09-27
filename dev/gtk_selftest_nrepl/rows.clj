(ns gtk-selftest-nrepl.rows
  "Rows for the nREPL window. Every shell call runs on a future, and every
  signal goes to a pid confirmed as this process's own child."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [gtk-selftest-window.support :as support]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.help-popover :as help-popover]
            [jonquil.nrepl.evaluator :as nrepl]
            [jonquil.session]))

(def fixture-core
  "(ns demo.core)\n\n(defn greet\n  \"Says hi.\"\n  [n]\n  (println \"hi\" n)\n  :hi)\n")

;; D2.T5: the fixture!-built project's own {:dir :file}, stashed here so a
;; row (row-nrepl-open-switches-evaluator, reopening it after switching
;; away) and after-close-checks! (reading its .nrepl-port back, after
;; main/start! has returned and -main's own local is out of scope) can
;; both reach it without threading it through every row-fn's [ui next]
;; signature.
(defonce ^:private fixture* (atom nil))

;; D2.T5: the pid row-nrepl-close-kills-child records right before quitting,
;; read back by after-close-checks! once the GTK loop is gone.
(defonce ^:private last-child-pid (atom nil))

(defn fixture!
  "A throwaway project: deps.edn, .jonquil.edn {:runtime :nrepl}, a
  .nrepl-port that belongs to \"someone else\" (4242), and
  src/demo/core.clj. {:dir :file}, both canonical."
  []
  (let [d (io/file (System/getProperty "java.io.tmpdir") (str "jonquil-selftest-nrepl-" (System/nanoTime)))
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
  Jolt profile's own default runtime, :in-process."
  []
  (let [d (io/file (System/getProperty "java.io.tmpdir") (str "jonquil-selftest-loose-" (System/nanoTime)))
        f (io/file d "scratch.clj")]
    (.mkdirs d)
    (spit f "(+ 1 2)\n")
    (.getCanonicalPath f)))

(defn evaluator [ui] ((:evaluator* ui)))

(defn idle? [ui] (not (ev/evaluating? (evaluator ui))))

(defn run-and-wait!
  "support/run-and-wait!'s contract, idle read through the evaluator."
  [ui code deadline-ms expect-fn done]
  (let [mark (support/out-end-offset ui)]
    (editor/set-text! (:editor ui) code)
    ((:run!* ui))
    (support/poll-until deadline-ms 20
                        (fn [] (and (idle? ui) (expect-fn (support/since ui mark))))
                        (fn [ok] (done ok mark)))))

(defn submit-and-wait!
  "Types text at the prompt and submits it the way run-repl/row-repl-after-run
  does (insert-interactive, then handle-return!)."
  [ui text deadline-ms expect-fn done]
  (let [cv (:console ui)
        mark (support/out-end-offset ui)]
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) text -1 1)
    (cview/handle-return! cv false)
    (support/poll-until deadline-ms 20
                        (fn [] (and (idle? ui) (expect-fn (support/since ui mark))))
                        (fn [ok] (done ok mark)))))

(defn- shell-then
  "Runs (apply shell/sh args) on a future and calls (f result-or-nil) on the
  GTK thread once it's done, or with nil after 5000 ms."
  [args f]
  (let [r (future (apply shell/sh args))]
    (support/poll-until 5000 20 (fn [] (realized? r)) (fn [ok] (f (when ok @r))))))

(defn with-own-child-pid
  "(f pid) with the window's child pid once pgrep -P confirms it's this
  process's own child; (f nil) otherwise. Rows signal nothing else."
  [ui f]
  (let [pid (nrepl/child-pid (evaluator ui))
        self (.pid (java.lang.ProcessHandle/current))]
    (shell-then ["pgrep" "-P" (str self)]
                (fn [r]
                  (let [kids (set (keep parse-long (str/split-lines (str (:out r)))))]
                    (f (when (and pid (contains? kids pid)) pid)))))))

(defn signal-then [pid sig f] (shell-then ["kill" sig (str pid)] (fn [_] (f))))

(defn alive-then
  "(f alive?) for pid, through ps."
  [pid f]
  (shell-then ["ps" "-p" (str pid) "-o" "pid="]
              (fn [r] (f (str/includes? (str (:out r)) (str pid))))))

(defn dead-then
  "(f dead?) for pid: re-checks alive-then every 200 ms (via support/later,
  never a fixed sleep) until it reports false or deadline-ms elapses."
  [pid deadline-ms f]
  (let [t0 (System/currentTimeMillis)]
    (letfn [(step []
              (alive-then pid
                          (fn [alive?]
                            (cond
                              (not alive?) (f true)
                              (>= (- (System/currentTimeMillis) t0) deadline-ms) (f false)
                              :else (support/later 200 step)))))]
      (step))))

(defn own-pid-then
  "(f pid) once with-own-child-pid confirms a non-nil pid, re-checked every
  200 ms up to deadline-ms; (f nil) if it never does. restart-for-stop!
  (jonquil.nrepl.evaluator) prints its own console line BEFORE it actually
  spawns the replacement child (measured: with-own-child-pid right after
  that text appears sometimes still finds no confirmed child), so a row
  reading the pid AFTER a restart needs this instead of a single
  with-own-child-pid call."
  [ui deadline-ms f]
  (let [t0 (System/currentTimeMillis)]
    (letfn [(step []
              (with-own-child-pid
                ui
                (fn [pid]
                  (cond
                    pid (f pid)
                    (>= (- (System/currentTimeMillis) t0) deadline-ms) (f nil)
                    :else (support/later 200 step)))))]
      (step))))

;; --- D2.T4 rows ------------------------------------------------------------

(defn row-nrepl-starts-and-reports
  "Waits (up to 20000 ms, a restart can be in flight at open) for the
  startup report. Reads the console from its very start (since 0), not
  from a mark captured at row entry: start-and-report! runs on a future as
  soon as the window opens, well before the driver's own first row runs,
  so a mark taken here would already sit after the report."
  [ui next]
  (support/poll-until
   20000 20
   (fn [] (str/includes? (support/since ui 0) "; Jolt (nREPL) is running (pid "))
   (fn [started?]
     (with-own-child-pid
       ui
       (fn [pid]
         (let [text (support/since ui 0)
               label (g/label-text (:dialect-label ui))
               reported-pid (some-> (re-find #"pid (\d+)" text) second parse-long)]
           (support/poll-until
            2000 20
            (fn [] (= "Ready" (g/label-text (:status-label ui))))
            (fn [ready?]
              (support/check :nrepl-starts-and-reports
                             (and started? (= "Jolt (nREPL)" label)
                                  (some? pid) (= pid reported-pid)
                                  (str/includes? text "; Limit: No stdin")
                                  (str/includes? text "jolt-lang/nrepl middleware")
                                  (str/includes? text "no file or line")
                                  ready?)
                             {:started started? :label label :pid pid
                              :reported-pid reported-pid :ready? ready?})
              (next)))))))))

(defn row-nrepl-run-output
  "Run fixture-core plus (greet 1): the child prints hi 1 and the load
  answers => :hi, and the prompt namespace follows load-file into
  demo.core, exactly as an in-process Run does."
  [ui next]
  (run-and-wait!
   ui (str fixture-core "(greet 1)") 5000
   (fn [t] (and (str/includes? t "hi 1") (str/includes? t "=> :hi")))
   (fn [ok? mark]
     (support/check :nrepl-run-output
                    (and ok? (= 'demo.core (ev/prompt-ns (evaluator ui)))
                         (instance? jonquil.nrepl.evaluator.NRepl (evaluator ui)))
                    {:ok? ok? :ns (ev/prompt-ns (evaluator ui)) :text (support/since ui mark)})
     (next))))

(defn row-nrepl-repl-follows-run
  "A REPL entry right after the Run above stays in demo.core."
  [ui next]
  (submit-and-wait!
   ui "(greet 2)" 5000
   (fn [t] (and (str/includes? t "hi 2") (str/includes? t "=> :hi")))
   (fn [ok? mark]
     (support/check :nrepl-repl-follows-run
                    (and ok? (instance? jonquil.nrepl.evaluator.NRepl (evaluator ui)))
                    {:text (support/since ui mark)})
     (next))))

(defn row-nrepl-error-is-text
  "An out-of-process error is text (D2.T1), never a Throwable: result-line
  reads it through console/error-text without throwing."
  [ui next]
  (run-and-wait!
   ui "(/ 1 0)" 5000
   (fn [t] (str/includes? t "Error: Divide by zero"))
   (fn [ok? mark]
     (support/poll-until
      2000 20
      (fn [] (= "Ready" (g/label-text (:status-label ui))))
      (fn [ready?]
        (support/check :nrepl-error-is-text (and ok? ready?)
                       {:text (support/since ui mark) :ready? ready?})
        (next))))))

(defn row-nrepl-reset-clears-child
  "Reset on Run (Review focus 4): two Runs in :reset mode leave nothing of
  user/kept behind for a submit to see; the same two Runs in :accumulate
  mode do."
  [ui next]
  (letfn [(run! [code done] (run-and-wait! ui code 5000 (constantly true) (fn [ok? _mark] (done ok?))))
          (submit! [code done]
            (submit-and-wait! ui code 5000 (constantly true)
                              (fn [ok? mark] (done ok? (support/since ui mark)))))]
    (run!
     "(def kept 1)"
     (fn [ok1]
       (run!
        "(+ 1 1)"
        (fn [ok2]
          (submit!
           "(resolve 'user/kept)"
           (fn [ok3 text1]
             (ev/set-mode! (evaluator ui) :accumulate)
             (run!
              "(def kept 1)"
              (fn [ok4]
                (run!
                 "(+ 1 1)"
                 (fn [ok5]
                   (submit!
                    "user/kept"
                    (fn [ok6 text2]
                      (ev/set-mode! (evaluator ui) :reset)
                      (support/check :nrepl-reset-clears-child
                                     (and ok1 ok2 ok3 ok4 ok5 ok6
                                          (str/includes? text1 "=> nil")
                                          (str/includes? text2 "=> 1")
                                          (instance? jonquil.nrepl.evaluator.NRepl (evaluator ui)))
                                     {:text1 text1 :text2 text2})
                      (next)))))))))))))))

(defn row-nrepl-stdin-notice
  "No input-forwarding middleware exists (D2 overview, measured): the
  console gets the static stdin notice instead of ever waiting for input."
  [ui next]
  (run-and-wait!
   ui "(read-line)" 5000
   (fn [t] (str/includes? t "=> nil"))
   (fn [ok? mark]
     (let [text (support/since ui mark)]
       (support/check :nrepl-stdin-notice
                      (and ok?
                           (str/includes? text nrepl/stdin-notice)
                           (not (ev/waiting-for-input? (evaluator ui))))
                      {:text text})
       (next)))))

(defn row-nrepl-completion-from-child
  "After Running fixture-core, \"(gre\" with the caret at 4 completes to
  \"(greet\" -- no clojure.core name starts with gre (measured) and the
  buffer defines none, so only the child's own completions op can have
  supplied the single candidate that committed."
  [ui next]
  (run-and-wait!
   ui fixture-core 5000 (constantly true)
   (fn [_ok? _mark]
     (let [ed (:editor ui)]
       (editor/set-text! ed "(gre")
       (support/place-cursor! (:buf ed) 4)
       ((:complete!* ui))
       (support/check :nrepl-completion-from-child
                      (= "(greet" (editor/editor-text ed))
                      {:text (editor/editor-text ed)})
       (next)))))

(defn row-nrepl-quick-doc-from-child
  "(greet 1), caret 2, quick doc: the popover's text can only have come
  from the child's own lookup op, since demo.core/greet is defined there
  and nowhere in this buffer or clojure.core."
  [ui next]
  (let [ed (:editor ui)]
    (editor/set-text! ed "(greet 1)")
    (support/place-cursor! (:buf ed) 2)
    ((:quick-doc!* ui))
    (let [text (help-popover/quick-doc-text (:help-popover ui))]
      (support/check :nrepl-quick-doc-from-child
                     (and (str/includes? text "demo.core/greet") (str/includes? text "Says hi."))
                     {:text text})
      (next))))

;; --- D2.T5 rows: switching runtimes on open, and the window's failure
;; rows (Stop against an unresponsive child, a child that dies, quit with
;; a live child) ---------------------------------------------------------

(defn row-nrepl-stop-interrupts
  "A plain interrupt (Review focus 2's easy case): the child answers, so
  Stop ends the evaluation without ever restarting it -- the same pid
  before and after, and `before`'s binding (set at the top of the same
  Run) survives, unlike a restart, which would lose it."
  [ui next]
  (let [mark (support/out-end-offset ui)]
    (editor/set-text! (:editor ui) "(def before 1)\n(loop [] (recur))")
    ((:run!* ui))
    (support/poll-until
     2000 20
     (fn [] (ev/evaluating? (evaluator ui)))
     (fn [started?]
       (with-own-child-pid
         ui
         (fn [before-pid]
           ((:stop!* ui))
           (support/poll-until
            5000 20
            (fn [] (and (idle? ui) (str/includes? (support/since ui mark) "; interrupted")))
            (fn [interrupted?]
              (with-own-child-pid
                ui
                (fn [after-pid]
                  (submit-and-wait!
                   ui "before" 5000
                   (fn [t] (str/includes? t "=> 1"))
                   (fn [ok? mark2]
                     (support/check :nrepl-stop-interrupts
                                    (and started? interrupted? ok?
                                         (some? before-pid) (= before-pid after-pid))
                                    {:started started? :interrupted interrupted?
                                     :before-pid before-pid :after-pid after-pid
                                     :text (support/since ui mark2)})
                     (next)))))))))))))

(defn row-nrepl-stop-unresponsive-restarts
  "Review focus 2's hard case: a SIGSTOPped child never answers interrupt,
  so Stop falls back to killing and restarting it -- a different pid, the
  console naming both the interrupt and the restart, and the runtime
  working again afterward."
  [ui next]
  (let [mark (support/out-end-offset ui)]
    (editor/set-text! (:editor ui) "(loop [] (recur))")
    ((:run!* ui))
    (support/poll-until
     2000 20
     (fn [] (ev/evaluating? (evaluator ui)))
     (fn [started?]
       (with-own-child-pid
         ui
         (fn [before-pid]
           (signal-then
            before-pid "-STOP"
            (fn []
              ((:stop!* ui))
              (support/poll-until
               15000 20
               (fn [] (let [t (support/since ui mark)]
                        (and (str/includes? t "; interrupted")
                             (str/includes? t "Stop restarted the Jolt runtime"))))
               (fn [msgs?]
                 (own-pid-then
                  ui 5000
                  (fn [after-pid]
                    (alive-then
                     before-pid
                     (fn [old-alive?]
                       (run-and-wait!
                        ui "(+ 1 2)" 5000
                        (fn [t] (str/includes? t "=> 3"))
                        (fn [ok? mark2]
                          (support/check :nrepl-stop-unresponsive-restarts
                                         (and started? msgs? ok?
                                              (some? before-pid) (some? after-pid)
                                              (not= before-pid after-pid)
                                              (false? old-alive?)
                                              (= "Ready" (g/label-text (:status-label ui))))
                                         {:started started? :msgs? msgs?
                                          :before-pid before-pid :after-pid after-pid
                                          :old-alive? old-alive? :text (support/since ui mark2)})
                          (next)))))))))))))))))

(defn row-nrepl-child-dies-mid-eval
  "Review focus 3: a child that dies outright (SIGKILL, no Stop pressed)
  still ends the evaluation with a sentence instead of hanging, the
  status returns to Ready on its own, and the very next Run notices the
  runtime is gone and starts a fresh one before evaluating."
  [ui next]
  (let [mark (support/out-end-offset ui)]
    (editor/set-text! (:editor ui) "(Thread/sleep 30000)")
    ((:run!* ui))
    (support/poll-until
     2000 20
     (fn [] (ev/evaluating? (evaluator ui)))
     (fn [started?]
       (with-own-child-pid
         ui
         (fn [pid]
           (signal-then
            pid "-9"
            (fn []
              (support/poll-until
               5000 20
               (fn [] (and (idle? ui)
                           (= "Ready" (g/label-text (:status-label ui)))
                           (str/includes? (support/since ui mark)
                                          "The Jolt runtime exited before this evaluation finished.")))
               (fn [died?]
                 (run-and-wait!
                  ui "(+ 1 2)" 15000
                  (fn [t] (and (str/includes? t "; The Jolt runtime had exited, so a new one was started.")
                               (str/includes? t "=> 3")))
                  (fn [ok2? mark2]
                    (support/check :nrepl-child-dies-mid-eval
                                   (and started? died? ok2?)
                                   {:started started? :died? died?
                                    :text (support/since ui mark2)})
                    (next)))))))))))))

(defn row-nrepl-open-switches-evaluator
  "Opening a loose file (no markers, no .jonquil.edn) swaps the window off
  the nREPL evaluator onto the in-process one and kills the old child;
  opening the nREPL project's own fixture file again swaps back and
  starts a fresh one."
  [ui next]
  (with-own-child-pid
    ui
    (fn [old-pid]
      (let [mark (support/out-end-offset ui)]
        ((:open-path! ui) (loose-fixture!))
        (support/poll-until
         5000 20
         (fn [] (str/includes? (support/since ui mark)
                               "; Switched to Jolt. The previous session is gone."))
         (fn [switched1?]
           (let [label1 (g/label-text (:dialect-label ui))
                 inproc1? (instance? jonquil.session.InProcess (evaluator ui))]
             (dead-then
              old-pid 5000
              (fn [dead1?]
                (let [mark2 (support/out-end-offset ui)]
                  ((:open-path! ui) (:file @fixture*))
                  (support/poll-until
                   20000 20
                   (fn [] (str/includes? (support/since ui mark2) "; Jolt (nREPL) is running (pid "))
                   (fn [started2?]
                     (let [label2 (g/label-text (:dialect-label ui))]
                       (run-and-wait!
                        ui "(+ 1 2)" 5000
                        (fn [t] (str/includes? t "=> 3"))
                        (fn [ok3? mark3]
                          (support/check :nrepl-open-switches-evaluator
                                         (and switched1? (= "Jolt" label1) inproc1?
                                              (some? old-pid) dead1?
                                              started2? (= "Jolt (nREPL)" label2) ok3?)
                                         {:switched1? switched1? :label1 label1 :inproc1? inproc1?
                                          :old-pid old-pid :dead1? dead1? :started2? started2?
                                          :label2 label2 :text (support/since ui mark3)})
                          (next))))))))))))))))

(defn row-nrepl-close-kills-child
  "The deliberately LAST row: records the live child's own pid, leaves the
  editor clean (set-text! never marks it dirty, so the real close below
  takes close-request-fn's immediate real-quit branch, no alert), and
  fires the exact action a Quit AppleEvent activates (window.clj's own
  quit-action handler) -- ev/stop! then runs on the GTK thread inside
  close-request-fn, same as the window self-test's own row-command-chord-
  close. after-close-checks! (below), called once the GTK loop is
  actually gone, is where this row's own pass/fail is recorded."
  [ui next]
  (with-own-child-pid
    ui
    (fn [pid]
      (reset! last-child-pid pid)
      (editor/set-text! (:editor ui) "")
      ((:activate-quit-action!* ui))
      (next))))

(defn after-close-checks!
  "Review focus 1's post-loop evidence: called by -main once main/start!
  returns, when the GTK loop is gone and a blocking clojure.java.shell/sh
  is fine (unlike shell-then above, which polls via g_timeout_add and
  needs one running). Three checks, all against row-nrepl-close-kills-
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
  same flag alive-then above already uses) prints nothing exactly when
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
