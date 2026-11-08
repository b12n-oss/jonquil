(ns jonquil.gtk.parts.processes
  "LM4's Processes panel as a window part, the first to contribute a
  right-hand panel (the commands and the label are jonquil.parts.processes/spec).
  Moved out of jonquil.gtk.window: the pane, its handlers and the 1 s refresh
  timer, whose callable is built once at install."
  (:require [jolt.ffi :as ffi]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.callbacks :refer [callable on-gui!]]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.processes-pane :as processes-pane]))

(defn- show-fibers!
  "Fills the pane from a :list answer r. An error clears the rows."
  [{:keys [procp]} r]
  (if (:error r)
    ;; no stale rows to act on while the runtime's away
    (do (processes-pane/show! procp [])
        (processes-pane/report! procp (str "; " (:error r))))
    (processes-pane/show! procp (:fibers r))))

(defn- apply-list!
  "On the GUI thread: opens the panel when show?, updates it while it shows."
  [{:keys [right showing-panel show-right-panel! procp] :as ctx} show? r]
  (when show? (show-right-panel! right :processes))
  (when (= :processes (showing-panel right))
    (show-fibers! ctx r))
  (when show? (g/gtk-widget-grab-focus (:list procp))))

(defn- watch-then-list!
  "Watches, then lists, then hops to the GUI thread with the answer."
  [ctx e show?]
  (ev/fibers-op!
   e :watch {}
   (fn [_]
     (ev/fibers-op!
      e :list {}
      (fn [r]
        (on-gui! (fn [] (apply-list! ctx show? r))))))))

(defn- no-jolt!
  "The runtime has no fibers: say so in the status bar or the panel footer."
  [{:keys [right showing-panel set-status! procp]} show?]
  (case (processes-pane/no-jolt-goes-to show?)
    :status (set-status! "; Processes need Jolt.")
    :footer (when (= :processes (showing-panel right))
              (processes-pane/show! procp [])
              (processes-pane/report! procp "; Processes need Jolt."))))

(defn- refresh!
  "LM4: the Processes panel's list, from where the code runs. Watching
  (wrapping jolt.fibers/spawn) starts on the first refresh and is
  idempotent, so a restarted child picks it up again. show? opens
  the panel; the timer's refreshes only update it while it shows. The
  evaluator is read from ev* on every call."
  [{:keys [ev*] :as ctx} show?]
  (let [e @ev*]
    (if (satisfies? ev/Fibers e)
      (watch-then-list! ctx e show?)
      (no-jolt! ctx show?))))

(defn- signal-done!
  "On the GUI thread: reports an error from the signal, else refreshes."
  [{:keys [procp] :as ctx} r]
  (if (:error r)
    (processes-pane/report! procp (str "; " (:error r)))
    (refresh! ctx false)))

(defn- signal!
  "Interrupts, kills or forgets (k) the fiber id, then refreshes the list."
  [{:keys [ev* procp] :as ctx} k id]
  (let [e @ev*]
    (if-not (satisfies? ev/Fibers e)
      (processes-pane/report! procp "; Processes need Jolt.")
      (ev/fibers-op! e k (if id {:id id} {})
                     (fn [r]
                       (on-gui! (fn [] (signal-done! ctx r))))))))

(defn- install
  "The Processes part for one window: builds the pane, wires its handlers
  and arms the refresh timer, reading the evaluator, the right-hand panels
  and the active tab from wctx."
  [{:keys [right hide-right-panel! showing-panel active-tab] :as wctx}]
  (let [procp (processes-pane/make-pane)
        ctx (assoc wctx :procp procp)
        show-fn (fn [] (refresh! ctx true))]
    (processes-pane/set-handlers!
     procp {:on-interrupt (fn [id] (signal! ctx :interrupt id))
            :on-kill (fn [id] (signal! ctx :kill id))
            :on-forget (fn [] (signal! ctx :forget nil))
            :on-close (fn [] (hide-right-panel! right :processes) (editor/focus! (:ed (active-tab))))})
    ;; while the Processes panel shows, its list follows the fibers each second
    (g/g-timeout-add 1000 (callable (fn [_data]
                                      (when (= :processes (showing-panel right)) (refresh! ctx false))
                                      1)
                                    [:pointer] :int :on-throw 1)
                     ffi/null)
    {:fns {:processes-show-fn show-fn}
     :ui {:processes-pane procp}
     :panels {:processes {:root (:root procp)
                          :focus! (fn [] (g/gtk-widget-grab-focus (:list procp)))
                          :open! show-fn}}}))

(def part
  "The Processes part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :processes}))
