(ns jonquil.command-registry
  "Commands as data (plugins design, Part 1): an id, one doc line, a scope
  and a (fn [ctx args]) that does the work and answers an outcome
  (:handled, :to-gtk or :next, as jonquil.keymap/dispatch reads it). ctx is the map the window's key
  handler already receives (run-fn, save-fn and the rest), so a command is
  a plain function a test can call with a fake ctx. No GTK here.

  Slice S1 registers the window's plain chords; later slices add the rest
  of the window's and the editor's, menus and the context menu read the
  same table, and Part 2's plugins add their own.")

(defn- call [k & args]
  (fn [ctx _args] (apply (get ctx k) args) :handled))

(def window-commands
  {:run/run {:doc "Run the buffer." :scope :window :run (call :run-fn)}
   :file/open {:doc "Open a file." :scope :window :run (call :open-fn)}
   :file/save-as {:doc "Save the buffer under a new name." :scope :window :run (call :save-as-fn)}
   :file/save {:doc "Save the buffer." :scope :window :run (call :save-fn)}
   :tab/close {:doc "Close the tab (the window, on the last one)." :scope :window :run (call :close-fn)}
   :tree/toggle {:doc "Show or hide the file tree." :scope :window :run (call :toggle-tree-fn)}
   :run/selection {:doc "Run the selection, or the form at the caret." :scope :window
                   :run (call :run-selection-fn)}
   :console/clear-last-output {:doc "Clear the last entry's output." :scope :window
                               :run (call :clear-last-fn)}
   :console/clear {:doc "Clear the console." :scope :window :run (call :clear-console-fn)}
   :find/open {:doc "Find in the buffer." :scope :window :run (call :open-find-fn)}
   :find/open-replace {:doc "Find and replace in the buffer." :scope :window :run (call :open-replace-fn)}
   :view/zoom-in {:doc "Make the text larger." :scope :window :run (call :zoom-fn :in)}
   :view/zoom-out {:doc "Make the text smaller." :scope :window :run (call :zoom-fn :out)}
   :view/zoom-reset {:doc "Reset the text size." :scope :window :run (call :zoom-fn :reset)}
   :tab/select {:doc "Go to tab n (9 is the last tab)." :scope :window
                :run (fn [ctx args] ((:digit-tab-fn ctx) (:n args)) :handled)}
   :tab/new {:doc "Open a new tab." :scope :window :run (call :new-tab-fn)}
   :view/cycle-theme {:doc "Switch to the next theme." :scope :window :run (call :theme-cycle-fn)}
   :inspect/form {:doc "Inspect the value of the form at the caret." :scope :window
                  :run (call :inspect-form-fn)}
   :inspect/last {:doc "Inspect the last result." :scope :window :run (call :inspect-last-fn)}
   :tab/next {:doc "Go to the next tab." :scope :window :run (call :step-tab-fn 1)}
   :tab/previous {:doc "Go to the previous tab." :scope :window :run (call :step-tab-fn -1)}
   ;; slice S2: the clauses that sat above S1's block
   :find/next {:doc "Go to the next find match." :scope :window :run (call :find-next!)}
   :find/previous {:doc "Go to the previous find match." :scope :window :run (call :find-prev!)}
   :focus/toggle {:doc "Move focus between the editor and the console." :scope :window
                  :run (call :toggle-focus-fn)}
   :source/open-here {:doc "Open the source panel's file in the window." :scope :window
                      :run (fn [ctx _args] ((:open! (:source-keys ctx))) :handled)}
   ;; slice S3: the Escape chain, each command answering an outcome. The
   ;; find bar's own answer is final: its 0 (an IME composing) is GTK's.
   :find/bar-key {:doc "Hand Escape to the focused find bar." :scope :window
                  :run (fn [ctx _args] (if (= 1 ((:find-bar-key! ctx))) :handled :to-gtk))}
   :popover/close-all {:doc "Close any open popover." :scope :window
                       :run (fn [ctx _args] (if ((:close-popovers-fn ctx)) :handled :next))}
   :inspector/close {:doc "Close the inspector panel." :scope :window :run (call :close-inspector-fn)}
   :source/close {:doc "Close the source panel." :scope :window
                  :run (fn [ctx _args] ((:close! (:source-keys ctx))) :handled)}
   :tree/close-menu {:doc "Close the file tree's menu." :scope :window
                     :run (fn [ctx _args] ((:close-menu! (:tree-keys ctx))) :handled)}
   :tree/leave {:doc "Leave the file tree for the editor." :scope :window
                :run (fn [ctx _args] ((:leave! (:tree-keys ctx))) :handled)}
   :run/stop {:doc "Stop the running evaluation." :scope :window :run (call :stop-fn)}})

(def window-conditions
  "Named conditions a window binding's :when can ask for, each (fn [ctx])
  over the per-key ctx on-window-key builds, which carries the GTK reads as
  fns, so nothing here touches GTK."
  {:find-bar-navigable (fn [ctx] ((:find-bar-navigable? ctx)))
   :source-focused (fn [ctx] ((:focused? (:source-keys ctx))))
   :find-bar-focused (fn [ctx] ((:find-bar-focused? ctx)))
   :inspector-focused (fn [ctx] ((:inspector-focused? ctx)))
   ;; a window with no tree has no tree-keys
   :tree-focused (fn [ctx] (some-> (:tree-keys ctx) :focused? (#(%))))
   :tree-menu-open (fn [ctx] (some-> (:tree-keys ctx) :menu-open? (#(%))))
   :evaluating (fn [ctx] ((:evaluating? ctx)))})

(defn holds-fn
  "The holds? a binding's :when is asked through: true when the named
  condition in conditions holds for ctx; false for an unknown id."
  [conditions ctx]
  (fn [id] (if-let [f (get conditions id)] (boolean (f ctx)) false)))

(defn run-command!
  "Runs the binding's command from registry with ctx and the binding's
  :args, returning what the command returns, an outcome for
  jonquil.keymap/dispatch (:handled, :to-gtk or :next); nil when the id
  isn't registered."
  [registry binding ctx]
  (when-let [{f :run} (get registry (:command binding))]
    (f ctx (or (:args binding) {}))))
