(ns jonquil.gtk.window.runtime
  "The window's dialect and evaluator: choosing the selection for a path
  (select-for), showing it in the status bar, an attach evaluator's
  connection label and the console's prompt, Reset on Run per runtime,
  starting an out-of-process evaluator and saying so (start-and-report!),
  and switching to another one (apply-selection!). Internal to the window,
  as jonquil.gtk.window.status is. Moved out of jonquil.gtk.window
  unchanged; the lock caller rule in its docstring applies here."
  (:require [clojure.string :as str]
            [jonquil.dialect :as dialect]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.callbacks :refer [on-gui!]]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.window.right-slot :as win-right-slot]
            [jonquil.gtk.window.status :as win-status]
            [jonquil.line-counts :as line-counts]
            [jonquil.nrepl.connection :as connection]
            [jonquil.nrepl.evaluator :as nrepl]
            [jonquil.runtime-registry :as registry]
            [jonquil.session]
            [jonquil.settings :as settings]))

;; --- dialect and evaluator ---------------------------------------------------

(defn select-for
  "jonquil.dialect/select for path (nil for an unsaved window), with the
  settings file's :dialect as the fallback. Never throws: a failure while
  reading the project becomes a warning on the fallback selection."
  [path settings-path]
  (try
    (dialect/select dialect/profiles
                    (dialect/find-project dialect/profiles path)
                    path
                    (:dialect (settings/load-settings settings-path)))
    (catch :default e
      (update (dialect/select dialect/profiles nil nil nil) :warnings conj
              (str "couldn't read the project: " (ex-message e))))))

(defn show-selection!
  "Puts sel's label in the status bar and each warning on the console.
  GTK thread only (the label); cview/append! is safe from any thread."
  [cv dialect-label sel]
  (g/gtk-label-set-text dialect-label (dialect/label sel))
  (doseq [w (:warnings sel)]
    (cview/append! cv :system (str "; project: " w "\n"))))

;; --- the ClojureDart attach evaluator's window state -------------------------

(defn dialect-text
  "The status bar's dialect label for selection sel run by evaluator e: an
  attach evaluator's label says whether it holds a live connection."
  [sel e]
  (dialect/label sel (when (satisfies? connection/Attached e)
                       (if (connection/connected? e) :attached :not-running))))

(defn refresh-prompt!
  "Makes the console's prompt read the window's evaluator's own namespace
  (`user=> `, `demo.core=> `). Safe from any thread: the rewrite goes
  through on-gui!, and a throw from prompt-ns or set-prompt! lands on the
  console instead of reaching GTK."
  [cv ev*]
  (on-gui! (fn []
             (try (cview/set-prompt! cv (str (or (ev/prompt-ns @ev*) 'user)))
                  (catch :default e
                    (cview/append! cv :error (str "; prompt: " (ex-message e) "\n")))))))

(defn- connection-listener
  "The fn watch-connection! hands to e's on-connection!: logs the change
  while e is still the window's evaluator, and hands the label and prompt
  refresh to the GTK thread. ctx is watch-connection!'s."
  [e {:keys [ev* sel* dialect-label cv status-label]}]
  (fn [connected?]
    (when (identical? e @ev*)
      (win-status/log-at! status-label
                          {:kind :connection :error? (not connected?)
                           :summary (str (dialect/label @sel*)
                                         (if connected? " connected" " lost its connection"))}))
    (on-gui! (fn []
               (when (identical? e @ev*)
                 (g/gtk-label-set-text dialect-label (dialect-text @sel* e))
                 (refresh-prompt! cv ev*))))))

(defn watch-connection!
  "For an attach evaluator e, keeps the dialect label and the console's
  prompt in step with its connection (a new connection is a fresh session,
  back in its default namespace). The listener runs on whatever thread
  connected or lost the socket, so it hands both to the GTK thread, and it
  does nothing once e is no longer the window's evaluator (a later switch
  made another). ctx is {:ev* :sel* :dialect-label :cv :status-label}."
  [e ctx]
  (when (satisfies? connection/Attached e)
    (connection/on-connection! e (connection-listener e ctx))))

(defn apply-reset-toggle!
  "Reset on Run as dialect/reset-toggle decides for sel: unticked and
  insensitive, with the profile's own :reset-tooltip, for an attach
  runtime, and the person's own tick back, with no tooltip, for every
  other runtime. reset-state is the window's atom holding the tick an
  attach runtime took down. Setting the tick fires the box's toggled
  handler, which only calls set-mode! on the current evaluator: a no-op
  on an attach one, and harmless on the evaluator a switch is about to
  replace. GTK thread only."
  [reset-chk reset-state sel]
  (let [{:keys [state active? sensitive? tooltip]}
        (dialect/reset-toggle @reset-state sel (not (zero? (g/gtk-check-button-get-active reset-chk))))]
    (reset! reset-state state)
    (g/gtk-check-button-set-active reset-chk (if active? 1 0))
    (g/gtk-widget-set-sensitive reset-chk (if sensitive? 1 0))
    (g/gtk-widget-set-tooltip-text reset-chk tooltip)))

;; The start report's project path is usually under the user's home
;; directory; a ~-relative form keeps that line short.
(defn- home-relative
  "path with a leading $HOME replaced by ~, or path unchanged when it isn't
  under $HOME (or $HOME can't be read)."
  [path]
  (let [home (System/getProperty "user.home")]
    (if (and home (str/starts-with? path (str home "/")))
      (str "~" (subs path (count home)))
      path)))

;; start-and-report! sits here, before apply-selection! (below), which
;; calls it: Jolt resolves an unqualified symbol at compile time, not
;; lazily, so a forward reference to it from earlier in the file fails
;; jolt check with "Unable to resolve symbol".
(defn- report-start-failure!
  "Logs and prints that sel's runtime couldn't start: summary on the
  status log, text on the console as an :error. ctx is {:cv :status-label
  :sel}."
  [{:keys [cv status-label sel]} summary text]
  (win-status/log-at! status-label {:kind :runtime :error? true
                                    :summary (str (dialect/label sel) " couldn't start: " summary)})
  (cview/append! cv :error text))

(defn- report-started!
  "Logs and prints that the out-of-process evaluator e is running: pid,
  port and project, then each limitation."
  [cv status-label e sel]
  (win-status/log-at! status-label {:kind :runtime
                                    :summary (str (dialect/label sel) " is running (pid " (nrepl/child-pid e) ")")})
  (cview/append! cv :system
                 (str "; " (dialect/label sel) " is running (pid " (nrepl/child-pid e)
                      ", port " (nrepl/child-port e) ") in " (home-relative (:project-dir sel)) ".\n"))
  (doseq [l (ev/limitations e)]
    (cview/append! cv :system (str "; Limit: " l "\n"))))

(defn- start-child!
  "Starts the out-of-process evaluator e and reports how it went, then
  sets the status back to Ready unless it is evaluating. Blocks until the
  start finishes, so start-and-report! runs it on a future."
  [cv status-label e sel]
  (let [ctx {:cv cv :status-label status-label :sel sel}]
    (try
      (if-let [problem (ev/start! e)]
        (report-start-failure! ctx problem (str "; " problem "\n; Run will try again.\n"))
        (report-started! cv status-label e sel))
      (catch :default ex
        (report-start-failure! ctx (ex-message ex) (str "; start: " (ex-message ex) "\n")))
      (finally
        (when-not (ev/evaluating? e) (win-status/set-status! status-label "Ready"))))))

(defn- report-attach-start!
  "An attach evaluator connects on the first Run, so there's nothing to
  start: says it's experimental when the profile's own flag is set, adds
  its start note, and lists what it can't do. satisfies?
  connection/Attached, not an instance? on any one dialect's record, so a
  :clj window prints its own limits too."
  [cv e sel]
  (when (:experimental (:profile sel))
    (cview/append! cv :system (str (:experimental-message (:profile sel)) "\n")))
  (when (satisfies? ev/StartNote e)
    (cview/append! cv :system (str "; " (ev/start-note e) "\n")))
  (doseq [l (ev/limitations e)]
    (cview/append! cv :system (str "; Limit: " l "\n"))))

(defn start-and-report!
  "Starts an out-of-process evaluator on a future and says so on the
  console: pid, port and project, then each limitation, or why it couldn't
  start. An attach evaluator has nothing to start: its experimental note
  and limits are said instead. The in-process engine has nothing to start
  or report."
  [cv status-label e sel]
  (cond
    (instance? jonquil.nrepl.evaluator.NRepl e)
    (do (win-status/set-status! status-label (str "Starting " (dialect/label sel) "…"))
        (future (start-child! cv status-label e sel)))

    (satisfies? connection/Attached e)
    (report-attach-start! cv e sel)))

;; The helpers below and apply-selection! sit here, after start-and-report!
;; (above), which they call: Jolt resolves an unqualified symbol at compile
;; time, not lazily, so they can't sit any earlier in the file.
(defn- drop-panels-for-switch!
  "A runtime switch's panel cleanup: hides the inspector and source panels
  and drops the inspector's state, since their views belong to the old
  runtime and a panel switch can't bring them back. Focus inside a panel
  this hides would go nowhere, so it moves to the active tab's editor, as
  closing the inspector does. ctx is apply-selection!'s."
  [{:keys [right inspector-state active-ed]}]
  (let [focus-in-panel? (or (win-right-slot/focus-within? (:inspector-root right))
                            (win-right-slot/focus-within? (:source-root right)))]
    (win-right-slot/hide-right-panel! right :inspector)
    (win-right-slot/hide-right-panel! right :source)
    (when (and focus-in-panel? active-ed)
      (when-let [ed (active-ed)] (editor/focus! ed))))
  (reset! inspector-state nil)
  (when-let [r (:recent-panels right)] (swap! r (fn [v] (vec (remove #{:inspector :source} v))))))

(defn- retire-evaluator!
  "Interrupts (when evaluating) and stops the old evaluator on a future,
  off the GTK thread, then unregisters it. The two steps are guarded
  separately, so a throw from one still runs the other: each step's own
  throw is reported on the console rather than skipping the step after it
  and orphaning the old child."
  [cv old]
  (future
    (try (when (ev/evaluating? old) (ev/interrupt! old))
         (catch :default ex
           (cview/append! cv :error (str "; switch: " (ex-message ex) "\n"))))
    (try (ev/stop! old)
         (catch :default ex
           (cview/append! cv :error (str "; switch: " (ex-message ex) "\n")))
         (finally (registry/unregister! old)))))

(defn- swap-evaluator!
  "apply-selection!'s evaluator swap for sel: makes and registers the new
  evaluator, carries the prompt history over, drops the panels, retires
  the old evaluator and starts the new one. ctx is apply-selection!'s."
  [{:keys [cv status-label dialect-label sel* ev* mode-fn] :as ctx} sel]
  (let [old @ev*
        new (doto (ev/make sel) registry/register!)]
    (ev/set-mode! new (mode-fn))
    (watch-connection! new {:ev* ev* :sel* sel* :dialect-label dialect-label :cv cv :status-label status-label})
    ;; LM7: the prompt history follows the window, not the evaluator
    (let [items (:items @(ev/history old))]
      (reset! (ev/history new) {:items items :index (count items) :draft ""}))
    (reset! ev* new)
    ;; LM4: counting is the whole process's mode, so leaving the
    ;; in-process Jolt for another runtime mustn't leave Jonquil counting
    ;; what it compiles (another in-process session shares the mode)
    (when-not (instance? jonquil.session.InProcess new)
      (line-counts/off-if-counting!))
    (drop-panels-for-switch! ctx)
    (cview/append! cv :system (str "; Switched to " (dialect/label sel)
                                   ". The previous session is gone.\n"))
    (retire-evaluator! cv old)
    (start-and-report! cv status-label new sel)))

(defn apply-selection!
  "Makes sel the window's selection. When it needs a different evaluator
  (jonquil.dialect/selection-key), the old one is interrupted and stopped
  off the GTK thread and a new one starts, and the inspector panel (if
  showing) hides and drops its own state (the panel's old view would
  otherwise call ev/inspect! on an evaluator that may not even implement
  it). sel-ctx is {:cv :status-label :dialect-label :sel* :ev*
  :mode-fn :reset-chk :reset-state :right :inspector-state}.

  The new evaluator joins jonquil.runtime-registry as soon as it's made
  (so jonquil.main's stop-all! can never miss it, even if the window
  closes before it ever starts); the old one leaves once it's stopped.

  An attach evaluator's connection drives the label
  (watch-connection!), Reset on Run follows the runtime
  (apply-reset-toggle!), and the label is set last, from the evaluator the
  window ends up with, so opening another file of the same app keeps
  reading \"attached\"."
  [{:keys [cv dialect-label sel* ev* reset-chk reset-state] :as ctx} sel]
  (let [old-sel @sel*]
    (reset! sel* sel)
    (show-selection! cv dialect-label sel)
    (apply-reset-toggle! reset-chk reset-state sel)
    (when (not= (dialect/selection-key old-sel) (dialect/selection-key sel))
      (swap-evaluator! ctx sel))
    (g/gtk-label-set-text dialect-label (dialect-text sel @ev*))
    (refresh-prompt! cv ev*)))
