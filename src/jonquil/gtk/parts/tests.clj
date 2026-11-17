(ns jonquil.gtk.parts.tests
  "Run Tests as a window part: the active tab's tests, run in the window's
  runtime, their failures in the Locations list (the commands are
  jonquil.parts.tests/spec).

  A run is three evaluations in a row. A quiet one tags the tests the
  buffer's namespace holds, the tab's own Run evaluates the buffer (so
  unsaved edits count and the file isn't written), and a quiet one drops
  the tests still tagged, the ones the buffer no longer defines, and runs
  the rest. The same report shows an agent's run (jonquil.mcp.window)."
  (:require [clojure.string :as str]
            [jonquil.clj :as clj]
            [jonquil.commands :as commands]
            [jonquil.console :as console]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.callbacks :refer [on-gui!]]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.window.run :as win-run]
            [jonquil.test-run :as test-run]))

(defn- quiet-opts
  "Options for an evaluation of the part's own code on evaluator e: quiet
  where e honours it, so the REPL's history and *1 stay as they were."
  [e]
  (cond-> {:run? false :owner :repl}
    (satisfies? ev/QuietEval e) (assoc :quiet (str "jonquil-test-" (random-uuid) ":"))))

(defn- eval-then!
  "Evaluates code in the window's runtime without a console entry and
  calls (then result) on the GTK thread. on-output, when given, hears what
  the code prints. A busy runtime's warning goes on the console and then
  is never called. True when the evaluation started."
  [{:keys [cv ev*]} code {:keys [on-output then]}]
  (let [e @ev*
        warning (ev/eval! e code
                          (assoc (quiet-opts e)
                                 :on-output (or on-output (fn [_ _] nil))
                                 :on-wait-input (fn [waiting?] (when waiting? (ev/send-eof! e)))
                                 :on-complete (fn [r] (on-gui! (fn [] (then r))))))]
    (when warning (cview/append! cv :system warning))
    (nil? warning)))

(defn report-run!
  "Shows a test run's report (jonquil.test-run/report): its lines on the
  console, after a line naming who, the agent that ran it, when given; the
  failures as the Locations list; and, for a pass, that namespace's old
  failures list cleared. GTK thread."
  [{:keys [cv show-failures! clear-failures!]} report who]
  (when who
    (cview/append! cv :system (str "; " who " ran the tests in " (:ns report) "\n")))
  (cview/append! cv :system (str (str/join "\n" (test-run/console-lines report)) "\n"))
  (case (:status report)
    "failed" (show-failures! report)
    "ok" (clear-failures! (:ns report))
    nil))

(defn- locate-fn
  "A fn from a test's source path to its file: the path itself when it is
  absolute, else the one file ending with it among the tab's own and the
  project's."
  [tab-path root]
  (let [files (delay (distinct (concat (when tab-path [tab-path])
                                       (when root (test-run/project-files root)))))]
    (fn [rel]
      (if (.isAbsolute (java.io.File. ^String rel))
        rel
        (test-run/locate @files rel)))))

(defn- finish!
  "The test evaluation's result r as the run's report, or a line saying
  why there is none."
  [{:keys [cv set-status! project-root] :as ctx} r {:keys [ns-name test tab-path]}]
  (set-status! "Ready")
  (case (:status r)
    :ok (if-let [v (test-run/read-value (:printed r))]
          (report-run! ctx
                       (test-run/report v {:ns ns-name :test test
                                           :locate (locate-fn tab-path (project-root))})
                       nil)
          (cview/append! cv :error "; Tests: the run returned something unreadable.\n"))
    :error (cview/append! cv :error (str "; Tests: " (str/trim (console/error-text r)) "\n"))
    (cview/append! cv :system "; Tests: stopped.\n")))

(defn- start-run!
  "Mark, Run, test for the tab's namespace ns-name (test names one test).
  Each step starts only when the one before finished; a Run that was
  refused, found a problem or ended in an error has said so itself, and
  the tests don't run."
  [{:keys [cv ev* sel* status-label set-status!] :as ctx} {:keys [tab ns-name test]}]
  (let [run {:ns-name ns-name :test test :tab-path @(:path tab)}
        test! (fn []
                (set-status! (str "Running the tests in " ns-name "…"))
                (eval-then! ctx (test-run/snippet {:ns ns-name :test test :drop-marked? true})
                            {:on-output (fn [kind s] (cview/append! cv kind s))
                             :then (fn [r] (finish! ctx r run))}))
        run! (fn []
               (win-run/do-run! {:tab tab :cv cv :ev* ev* :sel* sel* :status-label status-label}
                                (fn [m]
                                  (when (= :ok (get-in m [:done :result :status]))
                                    (on-gui! test!)))))]
    (eval-then! ctx (test-run/mark-snippet {:ns ns-name}) {:then (fn [_] (run!))})))

(defn- run-tests!
  "Run Tests in This File, or with caret? Run Test at the Caret: says on
  the status line why it can't, else starts the run."
  [{:keys [active-tab cv ev* sel* set-status!] :as ctx} caret?]
  (let [tab (active-tab)
        snap (editor/snapshot (:ed tab))
        ns-name (clj/buffer-ns (:text snap))
        test (when caret? (some-> (commands/run-form snap) :text test-run/form-name))]
    (cond
      (win-run/refuse-foreign! {:tab tab :cv cv :sel* sel*}) nil
      (not (test-run/supported? (:id (:profile @sel*))))
      (set-status! "; Running tests needs Jolt or JVM Clojure.")
      (nil? ns-name) (set-status! "; No ns form in this tab, so there is no test namespace to run.")
      (and caret? (nil? test)) (set-status! "; No test at the caret.")
      (ev/evaluating? @ev*) (set-status! "; The runtime is busy: stop it or wait, then run the tests.")
      :else (try
              (start-run! ctx {:tab tab :ns-name ns-name :test test})
              (catch :default e
                (set-status! (str "; Tests: " (or (ex-message e) (str e)))))))))

(defn- install
  "The tests part for one window. Answers :test-run-file-fn and
  :test-run-caret-fn, and the ui entry :test-report!, (fn [report who]),
  which shows a run made elsewhere (the MCP server's). Reads the Locations
  part's exports :show-failures! and :clear-failures!."
  [{:keys [active-tab cv ev* sel* status-label set-status! tree-pane show-failures! clear-failures!]}]
  (let [project-root (fn []
                       (or (:root @(:state tree-pane))
                           (some-> @(:path (active-tab)) (java.io.File.) .getCanonicalFile .getParent)))
        ctx {:active-tab active-tab :cv cv :ev* ev* :sel* sel* :status-label status-label
             :set-status! set-status! :project-root project-root
             :show-failures! show-failures! :clear-failures! clear-failures!}]
    {:fns {:test-run-file-fn (fn [] (run-tests! ctx false))
           :test-run-caret-fn (fn [] (run-tests! ctx true))}
     :ui {:test-report! (fn [report who] (report-run! ctx report who))}}))

(def part
  "The tests part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :tests}))
