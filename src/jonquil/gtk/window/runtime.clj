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

(defn watch-connection!
  "For an attach evaluator e, keeps the dialect label and the console's
  prompt in step with its connection (a new connection is a fresh session,
  back in its default namespace). The listener runs on whatever thread
  connected or lost the socket, so it hands both to the GTK thread, and it
  does nothing once e is no longer the window's evaluator (a later switch
  made another)."
  [e ev* sel* dialect-label cv status-label]
  (when (satisfies? connection/Attached e)
    (connection/on-connection! e (fn [connected?]
                                   (when (identical? e @ev*)
                                     (win-status/log-at! status-label
                                                         {:kind :connection :error? (not connected?)
                                                          :summary (str (dialect/label @sel*)
                                                                        (if connected? " connected" " lost its connection"))}))
                                   (on-gui! (fn []
                                              (when (identical? e @ev*)
                                                (g/gtk-label-set-text dialect-label (dialect-text @sel* e))
                                                (refresh-prompt! cv ev*))))))))

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
;; bb check with "Unable to resolve symbol".
(defn start-and-report!
  "Starts an out-of-process evaluator on a future and says so on the
  console: pid, port and project, then each limitation, or why it couldn't
  start. The in-process engine has nothing to start or report."
  [cv status-label e sel]
  (cond
    (instance? jonquil.nrepl.evaluator.NRepl e)
    (do (win-status/set-status! status-label (str "Starting " (dialect/label sel) "…"))
        (future
          (try
            (if-let [problem (ev/start! e)]
              (do (win-status/log-at! status-label {:kind :runtime :error? true
                                                    :summary (str (dialect/label sel) " couldn't start: " problem)})
                  (cview/append! cv :error (str "; " problem "\n; Run will try again.\n")))
              (do (win-status/log-at! status-label {:kind :runtime
                                                    :summary (str (dialect/label sel) " is running (pid " (nrepl/child-pid e) ")")})
                  (cview/append! cv :system
                                 (str "; " (dialect/label sel) " is running (pid " (nrepl/child-pid e)
                                      ", port " (nrepl/child-port e) ") in " (home-relative (:project-dir sel)) ".\n"))
                  (doseq [l (ev/limitations e)]
                    (cview/append! cv :system (str "; Limit: " l "\n")))))
            (catch :default ex
              (win-status/log-at! status-label {:kind :runtime :error? true
                                                :summary (str (dialect/label sel) " couldn't start: " (ex-message ex))})
              (cview/append! cv :error (str "; start: " (ex-message ex) "\n")))
            (finally
              (when-not (ev/evaluating? e) (win-status/set-status! status-label "Ready"))))))

    ;; An attach evaluator connects on the first Run, so there's nothing to
    ;; start; say it's experimental and what it can't do. satisfies?
    ;; connection/Attached, not an instance? on any one dialect's record, so
    ;; a :clj window prints its own limits too -- the experimental line
    ;; stays gated on the profile's own flag, which cljd and jank set.
    (satisfies? connection/Attached e)
    (do (when (:experimental (:profile sel))
          (cview/append! cv :system (str (:experimental-message (:profile sel)) "\n")))
        (when (satisfies? ev/StartNote e)
          (cview/append! cv :system (str "; " (ev/start-note e) "\n")))
        (doseq [l (ev/limitations e)]
          (cview/append! cv :system (str "; Limit: " l "\n"))))))

;; apply-selection! sits here, after start-and-report! (above), which it
;; calls: Jolt resolves an unqualified symbol at compile time, not lazily,
;; so it can't sit any earlier in the file, before start-and-report! exists.
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

  interrupt! and stop! on the old evaluator are guarded separately, so a
  throw from one still runs the other: each step's own throw is reported
  on the console rather than skipping the step after it and orphaning the
  old child.

  An attach evaluator's connection drives the label
  (watch-connection!), Reset on Run follows the runtime
  (apply-reset-toggle!), and the label is set last, from the evaluator the
  window ends up with, so opening another file of the same app keeps
  reading \"attached\"."
  [{:keys [cv status-label dialect-label sel* ev* mode-fn reset-chk reset-state right inspector-state active-ed]} sel]
  (let [old-sel @sel*]
    (reset! sel* sel)
    (show-selection! cv dialect-label sel)
    (apply-reset-toggle! reset-chk reset-state sel)
    (when (not= (dialect/selection-key old-sel) (dialect/selection-key sel))
      (let [old @ev*
            new (doto (ev/make sel) registry/register!)]
        (ev/set-mode! new (mode-fn))
        (watch-connection! new ev* sel* dialect-label cv status-label)
        ;; LM7: the prompt history follows the window, not the evaluator
        (let [items (:items @(ev/history old))]
          (reset! (ev/history new) {:items items :index (count items) :draft ""}))
        (reset! ev* new)
        ;; LM4: counting is the whole process's mode, so leaving the
        ;; in-process Jolt for another runtime mustn't leave Jonquil counting
        ;; what it compiles (another in-process session shares the mode)
        (when-not (instance? jonquil.session.InProcess new)
          (line-counts/off-if-counting!))
        ;; Focus inside a panel this hides would go nowhere, so it moves to
        ;; the active tab's editor, as closing the inspector does.
        (let [focus-in-panel? (or (win-right-slot/focus-within? (:inspector-root right))
                                  (win-right-slot/focus-within? (:source-root right)))]
          (win-right-slot/hide-right-panel! right :inspector)
          (win-right-slot/hide-right-panel! right :source)
          (when (and focus-in-panel? active-ed)
            (when-let [ed (active-ed)] (editor/focus! ed))))
        (reset! inspector-state nil)
        ;; their views belong to the old runtime, so a panel switch can't
        ;; bring them back
        (when-let [r (:recent-panels right)] (swap! r (fn [v] (vec (remove #{:inspector :source} v)))))
        (cview/append! cv :system (str "; Switched to " (dialect/label sel)
                                       ". The previous session is gone.\n"))
        (future
          (try (when (ev/evaluating? old) (ev/interrupt! old))
               (catch :default ex
                 (cview/append! cv :error (str "; switch: " (ex-message ex) "\n"))))
          (try (ev/stop! old)
               (catch :default ex
                 (cview/append! cv :error (str "; switch: " (ex-message ex) "\n")))
               (finally (registry/unregister! old))))
        (start-and-report! cv status-label new sel)))
    (g/gtk-label-set-text dialect-label (dialect-text sel @ev*))
    (refresh-prompt! cv ev*)))
