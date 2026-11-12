(ns jonquil.gtk.window.console
  "The window's console (Interactions): the console view with its submit, history, busy and waiting fns and the atoms the restarts, Trace and the replay read, and its own completion popover, which follows the console as it scrolls. build makes the console and build-completion its completion. Internal to the window, as jonquil.gtk.window.status is. Moved out of jonquil.gtk.window's activate! unchanged; the lock caller rule in jonquil.gtk.window's docstring applies here."
  (:require [jolt.ffi :as ffi]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.callbacks :refer [callable]]
            [jonquil.gtk.completion :as completion]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.popover :as popover]
            [jonquil.gtk.window.help :as win-help]
            [jonquil.gtk.window.run :as win-run]
            [jonquil.gtk.window.status :as win-status]))

(defn build
  "activate!'s console bindings, in activate!'s order: *cv, the forward
  atom the submit fn reads, bang* (the ! commands, reset by the
  command-palette part), and cv, the console view with the atoms the
  restarts, Trace and the replay fill, then *cv reset to it. w holds what
  they read: :active-tab :ev* :status-label :evaluating-fn. Returns w with
  :bang* and :cv added."
  [{:keys [active-tab ev* status-label evaluating-fn] :as w}]
  (let [*cv (atom nil)
        ;; LM1's ! commands at the prompt, installed once the palette's
        ;; pieces exist (the command-palette part resets it at install)
        bang* (atom (fn [_text]))
        ;; :failure*, the last failed evaluation, for the restarts
        cv (assoc
            (cview/make-console-view {:on-submit (win-run/make-on-submit {:tab-fn active-tab :*cv *cv :ev* ev* :status-label status-label :bang* bang*})
                                      :history-prev (win-run/history-prev-fn ev*)
                                      :history-next (win-run/history-next-fn ev*)
                                      ;; The console reads busy/waiting state
                                      ;; straight from the engine, never a mirror.
                                      :evaluating? evaluating-fn
                                      :waiting? (fn [] (ev/waiting-for-input? @ev*))})
            :failure* (atom nil)
            ;; a fn the window runs after every evaluation (the Trace panel's refresh)
            :after-eval* (atom nil)
            ;; LM7: the fn (record result) that files a finished evaluation
            ;; in the project's record, and the replay tab as {:id :root},
            ;; the root it was opened for (or nil)
            :recorder* (atom nil)
            ;; a fn returning the tree's root, read as an evaluation starts
            :replay-root* (atom nil)
            :replay-tab* (atom nil))
        _ (reset! *cv cv)]
    (assoc w :bang* bang* :cv cv)))

(defn- follow-scroll!
  "Connects value-changed on the console view's two adjustments so
  scrolling the Interactions pane moves or hides the completion popover.
  Returns nil."
  [cv repl-completion]
  (let [follow (callable (fn [_adj _data]
                           (popover/follow-caret! repl-completion)
                           nil)
                         [:pointer :pointer] :void)]
    (doseq [adj [(g/gtk-scrollable-get-vadjustment (:view cv))
                 (g/gtk-scrollable-get-hadjustment (:view cv))]]
      (g/g-signal-connect-data adj "value-changed" follow ffi/null ffi/null g/CONNECT-DEFAULT))))

(defn build-completion
  "activate!'s console completion bindings, in activate!'s order: the
  console's own completion instance, repl-complete-fn (what Ctrl+Space in
  the console and the console menu call), then the scroll follow on the
  console view's two adjustments. w holds what they read: :cv :ev*
  :status-label, and :completion-ns-fn :server-ev :close-popovers-fn from
  jonquil.gtk.window.help's build. Returns w with :repl-completion and
  :repl-complete-fn added."
  [{:keys [cv ev* status-label completion-ns-fn server-ev close-popovers-fn] :as w}]
  (let [;; A second jonquil.gtk.completion instance, on the console's own
        ;; input region -- :position :top (above the input line, vs. the
        ;; editor's own :bottom), :snapshot-fn/:apply-op! overridden to
        ;; cview/input-snapshot and cview/apply-input-op! so refine!/
        ;; commit! read and write the input region instead of :ed's whole
        ;; buffer (see jonquil.gtk.completion's ns docstring).
        ;; close-others!/on-error are the SAME registry-wide fns the
        ;; editor's instance uses, so opening either instance still closes
        ;; any other open popover.
        repl-completion (completion/make-completion
                         (:view cv)
                         ;; No :ed -- :snapshot-fn/:apply-op! below replace
                         ;; every use the default closures would have made
                         ;; of it, so there's no editor for this instance
                         ;; to name.
                         {:ns-fn completion-ns-fn
                          :candidates-fn (fn [prefix text pos extra] (ev/complete @ev* prefix text pos extra))
                          :doc-fn (fn [sym text pos] (ev/lookup @ev* sym text pos))
                          :server-ev server-ev
                          :close-others! close-popovers-fn
                          :on-error (fn [msg] (win-status/set-status! status-label msg))
                          :position :top
                          :snapshot-fn (fn [] (cview/input-snapshot cv))
                          :apply-op! (fn [result] (cview/apply-input-op! cv result))})
        repl-complete-fn (fn [] (win-help/do-repl-complete! cv ev* repl-completion status-label))
        ;; the console's completion is caret-anchored too: scrolling the
        ;; Interactions pane follows or hides it, as a tab's editor does
        ;; for its popovers (wire-tab!)
        _ (follow-scroll! cv repl-completion)]
    (assoc w :repl-completion repl-completion :repl-complete-fn repl-complete-fn)))
