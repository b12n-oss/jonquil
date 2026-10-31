(ns jonquil.command-registry
  "Commands as data: an id, one doc line, a scope
  and a (fn [ctx args]) that does the work and answers an outcome
  (:handled, :to-gtk or :next, as jonquil.keymap/dispatch reads it). ctx
  is the map the key handler builds (the window's run-fn, save-fn and the
  rest; the editor's thunks), so a command is a plain function a test can
  call with a fake ctx. No GTK here.

  The window's and the editor's chords are registered here; the context
  menus read the same table (editor-menu, console-menu, menu-items), the
  command palette lists it (run-command-in), and plugins add their own."
  (:require [jonquil.keymap :as km]))

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
   :tab/select {:doc "Go to tab n (9 is the last tab)." :scope :window :palette? false
                :run (fn [ctx args] ((:digit-tab-fn ctx) (:n args)) :handled)}
   :tab/new {:doc "Open a new tab." :scope :window :run (call :new-tab-fn)}
   :tab/back {:doc "Go back to the tab used before." :scope :window :run (call :tab-back-fn)}
   :view/cycle-theme {:doc "Switch to the next theme." :scope :window :run (call :theme-cycle-fn)}
   :inspect/form {:doc "Inspect the value of the form at the caret." :scope :window
                  :run (call :inspect-form-fn)}
   :inspect/last {:doc "Inspect the last result." :scope :window :run (call :inspect-last-fn)}
   :run/time-form {:doc "Time the form at the caret (about a second)." :scope :window
                   :run (call :time-form-fn)}
   :debug/show-frames {:doc "Show the last error's frames and locals." :scope :window
                       :run (call :show-frames-fn)}
   :debug/locals-mode {:doc "Keep every local for the debugger (debug mode, then reload your code)."
                       :scope :window :run (call :locals-mode-fn)}
   :tab/next {:doc "Go to the next tab." :scope :window :run (call :step-tab-fn 1)}
   :tab/previous {:doc "Go to the previous tab." :scope :window :run (call :step-tab-fn -1)}
   ;; find and focus
   :find/next {:doc "Go to the next find match." :scope :window :run (call :find-next!)}
   :find/previous {:doc "Go to the previous find match." :scope :window :run (call :find-prev!)}
   ;; describing keys: palette-only, no chords
   :help/describe-key {:doc "Describe the next key you press." :scope :window
                       :run (call :describe-key-fn)}
   :help/key-bindings {:doc "List every key binding." :scope :window :run (call :key-bindings-fn)}
   :help/recent-keys {:doc "Show the keys you pressed last." :scope :window :run (call :recent-keys-fn)}
   ;; the locations list: three ways to fill it, F8 and Shift+F8 to step
   :locations/uses {:doc "List the uses of the symbol at the caret." :scope :window
                    :run (call :locations-uses-fn)}
   :locations/definitions {:doc "List the definitions of the symbol at the caret." :scope :window
                           :run (call :locations-definitions-fn)}
   :locations/matches {:doc "List every match of the find query, or of the selection." :scope :window
                       :run (call :locations-matches-fn)}
   :locations/next {:doc "Go to the next place in the list." :scope :window
                    :run (call :locations-step-fn 1)}
   :locations/previous {:doc "Go to the previous place in the list." :scope :window
                        :run (call :locations-step-fn -1)}
   :focus/toggle {:doc "Move focus between the editor and the console." :scope :window
                  :run (call :toggle-focus-fn)}
   :source/open-here {:doc "Open the source panel's file in the window." :scope :window :palette? false
                      :run (fn [ctx _args] ((:open! (:source-keys ctx))) :handled)}
   ;; The Escape chain, each command answering an outcome. The
   ;; find bar's own answer is final: its 0 (an IME composing) is GTK's.
   :find/bar-key {:doc "Hand Escape to the focused find bar." :scope :window :palette? false :passes? true
                  :run (fn [ctx _args] (if (= 1 ((:find-bar-key! ctx))) :handled :to-gtk))}
   :popover/close-all {:doc "Close any open popover." :scope :window :palette? false :passes? true
                       :run (fn [ctx _args] (if ((:close-popovers-fn ctx)) :handled :next))}
   ;; Parinfer (step 2 Part B): one ctx fn, the mode as its argument.
   :parinfer/smart {:doc "Parinfer: Smart mode." :scope :window :run (call :parinfer-fn :smart)}
   :parinfer/indent {:doc "Parinfer: Indent mode." :scope :window :run (call :parinfer-fn :indent)}
   :parinfer/paren {:doc "Parinfer: Paren mode." :scope :window :run (call :parinfer-fn :paren)}
   :parinfer/off {:doc "Parinfer: off." :scope :window :run (call :parinfer-fn :off)}
   :parinfer/next-mode {:doc "Parinfer: next mode (Smart, Indent, Paren, off)." :scope :window
                        :run (call :parinfer-fn :next)}
   :inspector/close {:doc "Close the inspector panel." :scope :window :run (call :close-inspector-fn)}
   :source/close {:doc "Close the source panel." :scope :window :palette? false
                  :run (fn [ctx _args] ((:close! (:source-keys ctx))) :handled)}
   :tree/close-menu {:doc "Close the file tree's menu." :scope :window :palette? false
                     :run (fn [ctx _args] ((:close-menu! (:tree-keys ctx))) :handled)}
   :tree/leave {:doc "Leave the file tree for the editor." :scope :window :palette? false
                :run (fn [ctx _args] ((:leave! (:tree-keys ctx))) :handled)}
   :run/stop {:doc "Stop the running evaluation." :scope :window :run (call :stop-fn)}
   ;; The source panel's Cmd/Ctrl+[ back chord, and anywhere
   ;; else the jump back into the tab a jump left (open-in-tab!), passing
   ;; the key on when there's nothing to go back from
   :source/back {:doc "Step the source panel's history back." :scope :window :palette? false
                 :run (fn [ctx _args] ((:back! (:source-keys ctx))) :handled)}
   :source/jump-back {:doc "Go back from the last jump into a tab." :scope :window :palette? false
                      :passes? true
                      :run (fn [ctx _args]
                             (if (some-> (:source-keys ctx) :jump-back! (#(%))) :handled :next))}
   ;; :passes? true marks a command that can answer :next or
   ;; :to-gtk, so the key may reach the editor after it; the plugin
   ;; validator reads it to tell an unreachable plugin binding from one the
   ;; window only sometimes takes.
   ;; The palette. :palette? false marks a command that only works inside the
   ;; key chain guarding it (a focus, an Escape step, a tab number), so the
   ;; palette leaves it out
   :palette/open {:doc "Show the command palette." :scope :window :palette? false
                  :run (call :open-palette-fn)}
   ;; reload the plugin files that changed, appeared or went
   ;; away; no chord, the palette reaches it
   :plugins/reload {:doc "Reload the plugin files that changed." :scope :window
                    :run (call :reload-plugins-fn)}
   ;; open the review of the agents' pending proposals
   :plugins/review {:doc "Review the plugin proposals agents made." :scope :window
                    :run (call :review-proposals-fn)}
   ;; the plugin list, to enable, disable or open one
   :plugins/list {:doc "Show the plugins: enable, disable or open one." :scope :window
                  :run (call :plugins-list-fn)}})

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
   :evaluating (fn [ctx] ((:evaluating? ctx)))
   ;; a text entry popover has focus: the rename box or the command palette
   :entry-focused (fn [ctx] ((:entry-focused? ctx)))})

(defn- edit [k]
  (fn [ctx _args] (if (= 1 ((get ctx k))) :handled :to-gtk)))

(defn- act [k]
  (fn [ctx _args] ((get ctx k)) :handled))

(def editor-commands
  "The editor's chords, plus the context menu's unbound
  Indent and Unindent. ctx is the per-key map
  jonquil.gtk.editor/handle-key! builds, each entry a thunk that reads the
  editor's state when it runs. An edit answers what the old clause did: its
  1 is :handled and its 0 (nothing changed, or nothing to redo) is GTK's.
  The rest are :handled once the chord matches or the item is picked,
  whatever their fn finds, and with no fn registered, so GTK never
  inserts the key."
  {:completion/open {:doc "Open completion, or commit its only candidate." :scope :editor
                     :run (act :complete!)}
   :edit/toggle-comment {:doc "Comment or uncomment the lines." :scope :editor :run (edit :toggle-comment!)}
   :edit/format-all {:doc "Format the buffer." :scope :editor :run (edit :format-all!)}
   :edit/format-selection {:doc "Format the selection." :scope :editor :run (edit :format-selection!)}
   :edit/redo {:doc "Redo." :scope :editor :run (edit :redo!)}
   :help/quick-doc {:doc "Show the docs for the symbol at the caret." :scope :editor :run (act :quick-doc!)}
   :help/jump {:doc "Jump to the definition of the symbol at the caret." :scope :editor :run (act :jump!)}
   :edit/rename {:doc "Rename the symbol at the caret." :scope :editor :run (act :rename!)}
   :run/form {:doc "Run the form at the caret." :scope :editor :run (act :run-form!)}
   ;; The context menu's, with no binding, since Tab is a typing
   ;; stage
   :edit/indent {:doc "Indent the lines." :scope :editor :run (act :indent!)}
   :edit/unindent {:doc "Unindent the lines." :scope :editor :run (act :unindent!)}})

(def editor-conditions
  "Named conditions an editor binding's :when can ask for, over the same
  per-key ctx."
  {:writable (fn [ctx] ((:writable? ctx)))
   :preedit-active (fn [ctx] ((:preedit-active? ctx)))})

(def console-commands
  "The console's context-menu commands. The console's keys stay
  in jonquil.gtk.console-view, outside the window and editor key chains."
  ;; :palette? false: its popup takes keys only while the console has focus,
  ;; and a palette run hands focus back to the editor
  {:console/complete {:doc "Complete the symbol at the console's caret." :scope :console
                      :palette? false :run (act :complete!)}})

(def commands
  "Every registered command, the three scopes merged; ids never collide
  (command_registry_test pins it)."
  (merge window-commands editor-commands console-commands))

(def editor-menu
  "The editor's context menu, in its order: each item's label,
  the action name it's installed under (\"p4.<action>\") and the command
  it runs."
  [{:label "Run Form" :action "run-form" :command :run/form}
   {:label "Jump to Definition" :action "jump" :command :help/jump}
   {:label "Rename Symbol" :action "rename" :command :edit/rename}
   {:label "Quick Documentation" :action "quick-doc" :command :help/quick-doc}
   {:label "Inspect Last Result" :action "inspect-last" :command :inspect/last}
   {:label "Inspect Form" :action "inspect-form" :command :inspect/form}
   {:label "Complete" :action "complete" :command :completion/open}
   {:label "Toggle Comment" :action "toggle-comment" :command :edit/toggle-comment}
   {:label "Format All" :action "format-all" :command :edit/format-all}
   {:label "Format Selection" :action "format-selection" :command :edit/format-selection}
   {:label "Indent" :action "indent" :command :edit/indent}
   {:label "Unindent" :action "unindent" :command :edit/unindent}
   {:label "Find" :action "find" :command :find/open}
   {:label "Replace" :action "replace" :command :find/open-replace}])

(def console-menu
  "The console's context menu."
  [{:label "Complete" :action "complete" :command :console/complete}
   {:label "Clear Last Output" :action "clear-last-output" :command :console/clear-last-output}])

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

(defn menu-items
  "menu's items as jonquil.gtk.window/install-context-menu! takes them,
  [[label action thunk] ...] in menu order, each thunk running its command
  from commands with ctx. A command's outcome is ignored, as a menu item
  has no key to hand on."
  [menu ctx]
  (mapv (fn [{:keys [label action command]}]
          [label action #(run-command! commands {:command command} ctx)])
        menu))

(defn run-command-in
  "Runs the command id from table (commands, or that and the live plugins'
  commands) with the ctx for its own :scope, out of ctxs {:window c
  :editor c :console c}: the ctx that scope's key handler builds, so the
  palette runs a command as its key would. Answers the outcome; nil when
  id isn't in table."
  [table id ctxs]
  (when-let [{:keys [scope]} (get table id)]
    (run-command! table {:command id} (get ctxs scope))))

(def ^:private KEY-ESCAPE (km/key-value "Escape"))

(defn window-answer
  "on-window-key's whole answer for a lowered keyval and a modifier state,
  1 (used up) or 0 (GTK's): first the entry stage, then the keymap.

  The entry stage is the one window rule that isn't a chord. While the
  rename popover's entry or the command palette's has focus every key but
  Escape is the entry's, so Run, close and switch can't fire mid-typing;
  Escape goes on to the keymap, whose popover binding closes the entry. It covers a class of keys, so it
  stays a fixed stage ahead of the keymap, like the
  editor's preedit yield, and asks the named condition :entry-focused.

  Then jonquil.keymap/dispatch over bindings with holds? and run!: :handled
  is 1, :to-gtk and :unmatched are 0."
  [bindings keyval state {:keys [holds?] :as opts}]
  (cond
    (and (not= keyval KEY-ESCAPE) (holds? :entry-focused)) 0
    (= :handled (km/dispatch bindings keyval state opts)) 1
    :else 0))

(defn editor-answer
  "jonquil.gtk.editor/handle-key!'s whole answer for a lowered keyval and a
  modifier state, 1 (used up) or 0 (GTK's), in this order:
  preedit yield, completion popup, editor keymap, typing stages.

  Three of those stages stay code, not bindings, because each covers a
  class of keys. While a preedit is composing every key is the input
  method's, so the named condition :preedit-active answers 0 before
  anything else is asked. An open completion popup gets first look at its
  own keys through the popup-key! thunk; its 1 is final, and its 0 or nil
  goes on. The typing-key! thunk (smart enter, auto-pairs, Backspace, Tab
  and indent) answers only once no binding matched.

  Between them, jonquil.keymap/dispatch over bindings with holds?, run!
  and physical: :handled is 1 and :to-gtk is 0."
  [bindings keyval state {:keys [holds? popup-key! typing-key!] :as opts}]
  (cond
    (holds? :preedit-active) 0
    (= 1 (popup-key!)) 1
    :else (case (km/dispatch bindings keyval state opts)
            :handled 1
            :to-gtk 0
            (typing-key!))))
