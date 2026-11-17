(ns jonquil.gtk.parts.tests
  "Run Tests as a window part: the active tab's tests, run in the window's
  runtime, their failures in the Locations list (the commands are
  jonquil.parts.tests/spec).

  A run is three evaluations in a row. A quiet one tags the tests the
  buffer's namespace holds, the tab's own Run evaluates the buffer (so
  unsaved edits count and the file isn't written), and a quiet one drops
  the tests still tagged, the ones the buffer no longer defines, and runs
  the rest. The same report shows an agent's run (jonquil.mcp.window).

  Only the tab's own text runs. Another open tab with forms edited since
  they last ran is named on the console before the summary, since the
  tests ran against what the runtime held."
  (:require [clojure.string :as str]
            [jonquil.changed :as changed]
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

(defn- noting
  "Evaluation callbacks for the MCP server's run: what the code prints goes
  on the console and into out, an atom of strings, and on-done hears the
  result."
  [{:keys [cv ev*]} out on-done]
  {:on-output (fn [kind s]
                (cview/append! cv kind s)
                (swap! out conj s))
   :on-wait-input (fn [waiting?] (when waiting? (ev/send-eof! @ev*)))
   :on-complete on-done})

(defn- agent-eval!
  "Evaluates code for the MCP server with no console entry. A busy
  runtime answers (notify {:busy warning}); otherwise on-done hears the
  result."
  [{:keys [ev*] :as ctx} code {:keys [out notify on-done]}]
  (let [e @ev*
        warning (ev/eval! e code (merge (quiet-opts e) (noting ctx out on-done)))]
    (when warning (notify {:busy warning}))))

(defn- agent-tab-run!
  "The MCP server's run of a namespace that tab holds: mark, the tab's
  Run, test, as a person's command, so the tab's unsaved edits count. A
  Run that was refused, found a problem or ended in an error is what
  notify hears, and the tests don't run."
  [{:keys [cv ev* sel* status-label] :as ctx} tab {:keys [ns-name test]} {:keys [out notify]}]
  (let [done (fn [r] (notify {:done {:result r :output (apply str @out)}}))
        test! (fn []
                (agent-eval! ctx (test-run/snippet {:ns ns-name :test test :drop-marked? true})
                             {:out out :notify notify :on-done done}))
        run! (fn []
               (win-run/do-run! {:tab tab :cv cv :ev* ev* :sel* sel* :status-label status-label}
                                (fn [m]
                                  (if (= :ok (get-in m [:done :result :status]))
                                    (do (swap! out conj (get-in m [:done :output]))
                                        (on-gui! test!))
                                    (notify m)))))]
    (agent-eval! ctx (test-run/mark-snippet {:ns ns-name})
                 {:out (atom []) :notify notify :on-done (fn [_] (on-gui! run!))})))

(defn- agent-run!
  "The MCP server's test run of namespace :ns-name (:test names one test).
  When an open tab's text holds that namespace, the tab runs first, as a
  person's command runs it. Otherwise :code, the snippet that reloads the
  namespace from disk, is evaluated. Nothing makes a console entry. notify
  hears exactly one map, {:busy warning}, a refused Run's map or {:done
  {:result r :output s}}, and the return value reads the output so far, as
  win-run/mcp-eval! does. GTK thread."
  [{:keys [tabs] :as ctx} {:keys [ns-name code] :as run} notify]
  (let [out (atom [])
        tab (first (filter #(= ns-name (clj/buffer-ns (editor/editor-text (:ed %)))) @tabs))]
    (if tab
      (agent-tab-run! ctx tab run {:out out :notify notify})
      (agent-eval! ctx code
                   {:out out :notify notify
                    :on-done (fn [r] (notify {:done {:result r :output (apply str @out)}}))}))
    (fn [] (apply str @out))))

(defn- unrun-lines
  "A warning line for each open tab, other than the one with id skip-id,
  that has run and holds forms edited since (the changed-form gutter's
  record). GTK thread."
  [tabs skip-id]
  (keep (fn [t]
          (when-let [ran (and (not= skip-id (:id t)) (some-> (:changed (:ed t)) :ran deref))]
            (let [n (count (changed/changed (editor/editor-text (:ed t)) ran))
                  path @(:path t)]
              (when (pos? n)
                (test-run/unrun-line (if path (.getName (java.io.File. ^String path)) "An untitled tab") n)))))
        @tabs))

(defn report-run!
  "Shows a test run's report (jonquil.test-run/report): its lines on the
  console, after a line naming who, the agent that ran it, when given, and
  the opts' :warnings (unrun-lines'); the failures as the Locations list;
  and, for a pass, that namespace's old failures list cleared. GTK thread."
  [{:keys [cv show-failures! clear-failures!]} report {:keys [who warnings]}]
  (when who
    (cview/append! cv :system (str "; " who " ran the tests in " (:ns report) "\n")))
  (doseq [w warnings]
    (cview/append! cv :system (str "; " w "\n")))
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
  [{:keys [cv set-status! project-root] :as ctx} r {:keys [ns-name test tab-path warnings]}]
  (set-status! "Ready")
  (case (:status r)
    :ok (if-let [v (test-run/read-value (:printed r))]
          (report-run! ctx
                       (test-run/report v {:ns ns-name :test test
                                           :locate (locate-fn tab-path (project-root))})
                       {:warnings warnings})
          (cview/append! cv :error "; Tests: the run returned something unreadable.\n"))
    :error (cview/append! cv :error (str "; Tests: " (str/trim (console/error-text r)) "\n"))
    (cview/append! cv :system "; Tests: stopped.\n")))

(defn- start-run!
  "Mark, Run, test for the tab's namespace ns-name (test names one test).
  Each step starts only when the one before finished; a Run that was
  refused, found a problem or ended in an error has said so itself, and
  the tests don't run."
  [{:keys [cv ev* sel* status-label set-status! tabs] :as ctx} {:keys [tab ns-name test]}]
  (let [run {:ns-name ns-name :test test :tab-path @(:path tab)
             :warnings (vec (unrun-lines tabs (:id tab)))}
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
  :test-run-caret-fn, and three ui entries for the MCP server: :mcp-test!,
  (fn [{:keys [ns-name test code]} notify]), which runs its tests with no
  console entry (agent-run!),
  :test-unrun-lines, (fn []), the warning lines for tabs with unrun edits,
  and :test-report!, (fn [report {:keys [who warnings]}]), which shows its
  run. Reads the Locations part's exports :show-failures! and
  :clear-failures!."
  [{:keys [active-tab tabs cv ev* sel* status-label set-status! tree-pane show-failures! clear-failures!]}]
  (let [project-root (fn []
                       (or (:root @(:state tree-pane))
                           (some-> @(:path (active-tab)) (java.io.File.) .getCanonicalFile .getParent)))
        ctx {:active-tab active-tab :tabs tabs :cv cv :ev* ev* :sel* sel* :status-label status-label
             :set-status! set-status! :project-root project-root
             :show-failures! show-failures! :clear-failures! clear-failures!}]
    {:fns {:test-run-file-fn (fn [] (run-tests! ctx false))
           :test-run-caret-fn (fn [] (run-tests! ctx true))}
     :ui {:mcp-test! (fn [run notify] (agent-run! ctx run notify))
          ;; an agent's run reloads from disk, so every tab's unrun edits count
          :test-unrun-lines (fn [] (vec (unrun-lines tabs nil)))
          :test-report! (fn [report opts] (report-run! ctx report opts))}}))

(def part
  "The tests part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :tests}))
