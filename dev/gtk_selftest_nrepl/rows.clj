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
            [jonquil.nrepl.evaluator :as nrepl]))

(def fixture-core
  "(ns demo.core)\n\n(defn greet\n  \"Says hi.\"\n  [n]\n  (println \"hi\" n)\n  :hi)\n")

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
    {:dir (.getCanonicalPath d) :file (.getCanonicalPath f)}))

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
                                  (str/includes? text "jolt-lang/jolt#1153")
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
                    (and ok? (= 'demo.core (ev/prompt-ns (evaluator ui))))
                    {:ok? ok? :ns (ev/prompt-ns (evaluator ui)) :text (support/since ui mark)})
     (next))))

(defn row-nrepl-repl-follows-run
  "A REPL entry right after the Run above stays in demo.core."
  [ui next]
  (submit-and-wait!
   ui "(greet 2)" 5000
   (fn [t] (and (str/includes? t "hi 2") (str/includes? t "=> :hi")))
   (fn [ok? mark]
     (support/check :nrepl-repl-follows-run ok? {:text (support/since ui mark)})
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
                                          (str/includes? text2 "=> 1"))
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
