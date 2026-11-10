(ns jonquil.gtk.window.help
  "The window's help: the popover registry that Escape closes, quick doc and
  full docs, jump to definition and the source panel with its way back,
  rename, and completion in the editor and the console. build and
  build-source-keys make activate!'s popover, source-ctx, completion and
  source panel key bindings. Internal to the window, as
  jonquil.gtk.window.status is. Moved out of jonquil.gtk.window
  unchanged; the lock caller rule in jonquil.gtk.window's docstring applies
  here."
  (:require [clojure.string :as str]
            [jonquil.edit :as edit]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.callbacks :refer [on-gui!]]
            [jonquil.gtk.completion :as completion]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.help-popover :as help-popover]
            [jonquil.gtk.popover :as popover]
            [jonquil.gtk.source-pane :as source-pane]
            [jonquil.gtk.window.right-slot :as win-right-slot]
            [jonquil.gtk.window.run :as win-run]
            [jonquil.gtk.window.status :as win-status]
            [jonquil.help :as help]
            [jonquil.source :as source]))

;; --- popovers: a registry so Escape can close whichever popover is open ----

(defn- register-popover!
  "Adds p (a jonquil.gtk.popover map) to popovers, this window's own
  registry of every popover it has created. Completion, quick doc and
  rename each register their own popover once, at construction -- created
  once, shown/hidden repeatedly, the same lifecycle :close-popovers!*
  in jonquil.gtk.window's ui map assumes."
  [popovers p]
  (swap! popovers conj p)
  nil)

(defn- close-popovers!
  "Closes every OPEN popover in popovers (jonquil.gtk.popover/hide!) except
  skip, when given, and returns whether any OTHER one was open -- the
  window's own :close-popovers!*, which the window keymap's
  :popover/close-all Escape binding calls (with no skip) ahead of the Stop
  binding, so closing a popover during a run must happen here, before
  Escape can also Stop the program. A caller about to show or reposition skip passes it here
  instead, so hiding and reshowing the SAME popover within one frame never
  happens -- repositioning an already open popover
  (jonquil.gtk.popover/show-at-caret!) is the clean path."
  [popovers & [skip]]
  (let [open (remove #(= % skip) (filter popover/open? @popovers))]
    (doseq [p open] (popover/hide! p))
    (boolean (seq open))))

;; --- quick doc and jump -------------------------------------------------

(defn place-caret-and-scroll!
  "Places ed's cursor at offset and scrolls it into view -- the same
  gtk_text_view_scroll_to_iter pattern jonquil.gtk.find-bar's select-
  match! uses, in two with-iters* calls since scroll-to-iter
  needs its own fresh iter."
  [ed offset]
  (let [buf (:buf ed)]
    (g/with-iters* buf offset offset (fn [a _] (g/gtk-text-buffer-place-cursor buf a)))
    (g/with-iters* buf offset offset
      (fn [a _] (g/gtk-text-view-scroll-to-iter (:view ed) a 0.0 0 0.0 0.0)))))

(defn ask-lookup!
  "ev/lookup for sym, for quick doc and jump. A plain evaluator is asked
  directly and (on-doc doc) runs at once. A server evaluator
  (ev/ServerHelp) is asked through ev/ask-help!: an answer within the
  grace wait runs (on-doc doc) at once too; a later one sets the status to
  \"Looking up <sym>...\" and runs (on-doc doc) on the GTK thread when it
  lands, but only while the window still has that evaluator and (still?)
  holds (the caret is on the same symbol in the active tab); else the
  status goes back to Ready, unless something newer has replaced the
  Looking up line. label names the feature in an error line, also used
  when the lookup itself threw (ev/help-error)."
  [{:keys [ev* status-label]} sym text pos still? label on-doc]
  (let [e @ev*
        looking (str "Looking up " sym "...")
        answer! (fn [doc]
                  (if-let [msg (ev/help-error doc)]
                    (win-status/set-status! status-label (str "; " label ": " msg))
                    (on-doc doc)))]
    (if-not (ev/server-help? e)
      (on-doc (ev/lookup e sym text pos))
      (let [[k doc] (ev/ask-help! ev/help-grace-ms (fn [] (ev/lookup e sym text pos))
                                  (fn [doc]
                                    (on-gui!
                                     (fn []
                                       (try
                                         (when (= looking (g/label-text status-label))
                                           (win-status/set-status! status-label "Ready"))
                                         (when (and (identical? e @ev*) (still?))
                                           (answer! doc))
                                         (catch :default ex
                                           (win-status/set-status! status-label (str "; " label ": " (ex-message ex)))))))))]
        (if (= k :now)
          (answer! doc)
          (win-status/set-status! status-label looking))))))

(defn- same-target?
  "Whether snap's help/target is still id ({:symbol :start})."
  [id snap]
  (= id (select-keys (help/target snap) [:symbol :start])))

(defn- live-editor?
  "False once ed has been destroyed (its tab closed): a late answer must
  not read its buffer then."
  [ed]
  (not @(:destroyed? ed)))

(defn do-quick-doc!
  "Ctrl+Q/Shift+F1, the ui's own :quick-doc!*: help/target on ed's
  current snapshot; none sets status \"No symbol at the caret.\"
  Otherwise looks up ev/lookup (the window's evaluator, in the
  running program's own prompt namespace) and shows help/doc-text in the
  quick-doc popover at the caret. Wrapped in try/catch, since no lookup
  here may throw out of a key handler, even though ev/lookup already
  catches its own exceptions -- help/doc-text and syntax/format-
  autocomplete-doc run outside that catch, and this is the editor's own
  key dispatch calling straight through, with nothing else between it
  and a throw.

  close-popovers-fn runs right before show-doc! -- so opening quick doc
  closes completion's popover too, through the SAME registry Escape
  already uses, never a pairwise special case against completion by name.

  A press on the SAME target (last-shown's own
  {:symbol :start}, set below whenever this shows something) while the
  quick-doc popover is STILL open, for an evaluator that satisfies
  ev/FullDoc, asks the server for full documentation instead of
  re-showing the same quick-doc text: the status says Loading, and
  ev/full-doc's on-result -- a worker thread, per its own docstring -- is
  handed to on-gui! whole, so every GTK/editor call inside it runs safely
  on the GTK thread, wrapped in its own try/catch that sets the status on
  a throw. A stale result (help/target on a FRESH snapshot no longer
  matches the target that was loading -- the caret moved on to something
  else while the request was in flight) is dropped: nothing is shown for
  it. Otherwise this closes every OTHER open popover (close-popovers-fn
  full-doc-pop, never full-doc-pop itself, so it's never hidden and
  reshown within the same frame) and shows the text at 60% of the
  window's own height (gtk-widget-get-height) with Run/Copy buttons per
  ClojureDocs example (do-run-example!, do-copy-example!), then clears the status
  back to Ready -- or, a non-:ok result, sets it from help/full-doc-
  status. Every other press, and every evaluator without FullDoc (cljd,
  in-process), takes the ordinary re-show path below unchanged, which
  also records the freshly shown target in last-shown."
  [{:keys [ed ev* status-label help-pop full-doc-pop last-shown close-popovers-fn win active? example-ctx]}]
  (try
    (let [snap (editor/snapshot ed)]
      (if-let [{:keys [symbol start] :as target} (help/target snap)]
        (let [id (select-keys target [:symbol :start])]
          (if (and (popover/open? help-pop) (= id @last-shown) (satisfies? ev/FullDoc @ev*))
            (do (win-status/set-status! status-label (str "Loading full docs for " symbol "..."))
                (ev/full-doc @ev* symbol (:text snap)
                             (fn [result]
                               (on-gui!
                                (fn []
                                  (try
                                    (cond
                                      ;; Another tab became active
                                      ;; while the server answered; a popover popped
                                      ;; up on a hidden page maps anyway and is lost
                                      ;; on the next switch, so show nothing.
                                      (not (active?))
                                      (win-status/set-status! status-label "Ready")

                                      ;; The caret moved to another symbol while the
                                      ;; server answered: drop the text, but don't
                                      ;; leave "Loading full docs..." behind.
                                      (not= id (select-keys (help/target (editor/snapshot ed)) [:symbol :start]))
                                      (win-status/set-status! status-label "Ready")

                                      (= :ok (:status result))
                                      (do (close-popovers-fn full-doc-pop)
                                          (help-popover/show-full-doc!
                                           full-doc-pop (:text result)
                                           (int (* 0.6 (g/gtk-widget-get-height win)))
                                           {:on-run (fn [code] (win-run/do-run-example! example-ctx code))
                                            :on-copy (fn [code] (win-run/do-copy-example! example-ctx code))})
                                          (win-status/set-status! status-label "Ready"))

                                      :else
                                      (win-status/set-status! status-label (help/full-doc-status symbol result)))
                                    (catch :default e
                                      (win-status/set-status! status-label (str "; full doc: " (ex-message e))))))))))
            (ask-lookup! {:ev* ev* :status-label status-label} symbol (:text snap) start
                         (fn [] (and (active?) (live-editor? ed) (same-target? id (editor/snapshot ed))))
                         "quick doc"
                         (fn [doc]
                           (reset! last-shown id)
                           (close-popovers-fn help-pop)
                           (help-popover/show-doc! help-pop (help/doc-text symbol doc))))))
        (win-status/set-status! status-label "No symbol at the caret.")))
    (catch :default e
      (win-status/set-status! status-label (str "; quick doc: " (ex-message e))))))

(defn show-source!
  "Opens loc in the source panel (painted with the window's current
  profile) and shows the panel in the right-hand slot, focusing it. A read
  failure is on the panel's footer; the panel still shows. ctx as
  do-jump!'s."
  [{:keys [sp right sel*]} loc]
  (source-pane/set-profile! sp (:profile @sel*))
  (source-pane/open-location! sp loc)
  (win-right-slot/show-right-panel! right :source)
  (source-pane/focus! sp))

(declare jump-to! source-jump-to!)

(defn open-in-tab!
  "A definition in one of the project's files (loc's :editable?, the rule Open in window uses)
  opens in a tab, or brings forward the tab that has it, with the caret on
  its line, and the panel closes. Where the jump started, the active tab
  and its caret, goes on :jump-back* first, for Cmd/Ctrl+[. When the tab
  doesn't end up showing loc's file (the read failed), the panel shows it
  instead, footer and all. ctx is the source-ctx map."
  [{:keys [open-file! active-tab jump-back* right status-label] :as ctx} symbol loc]
  (let [{from-ed :ed from-path :path from-id :id} (active-tab)
        from {:id from-id :path @from-path :offset (:caret (editor/snapshot from-ed))}
        {err :error} (open-file! (:path loc))
        {:keys [ed path id]} (active-tab)]
    (if (and (not err) @path (source/same-path? @path (:path loc)))
      (let [text (editor/editor-text ed)]
        ;; Only a jump that left its tab can go back: open-file! fills an
        ;; untouched Untitled tab in place, and there's nothing to return
        ;; to there.
        (when-not (= id from-id) (swap! jump-back* source/push from))
        (place-caret-and-scroll! ed (source/line-offset text (:line (source/clamp-line text (:line loc)))))
        (win-right-slot/hide-right-panel! right :source)
        (editor/focus! ed)
        (win-status/set-status! status-label (str "Jumped to " symbol ".")))
      (show-source! ctx loc))))

(defn- jump-back!
  "Cmd/Ctrl+[ in the editor after a jump into a tab: back to the tab and
  caret the newest jump started from, or that file reopened when its tab
  has closed since. Returns false, doing nothing, when no jump is left to
  go back from, so the key passes on as before. ctx is the source-ctx map
  plus :select-tab!."
  [{:keys [jump-back* select-tab! open-file! active-tab tab-open? status-label]}]
  (if (empty? @jump-back*)
    false
    ;; An entry whose tab has closed and whose file can't be reopened (an
    ;; Untitled tab, a deleted file) is skipped for the next one down.
    (loop []
      (if-let [[{:keys [id path offset]} rest-h] (source/back @jump-back*)]
        (do (reset! jump-back* rest-h)
            (let [restored? (cond
                              (tab-open? id) (do (select-tab! id) true)
                              path (do (open-file! path)
                                       (boolean (some-> (active-tab) :path deref (source/same-path? path))))
                              :else false)]
              (if restored?
                (let [{:keys [ed]} (active-tab)]
                  (place-caret-and-scroll! ed (min offset (count (editor/editor-text ed))))
                  (editor/focus! ed)
                  (win-status/set-status! status-label "Back.")
                  true)
                (recur))))
        (do (win-status/set-status! status-label "Nothing to go back to: those tabs are closed.")
            true)))))

(defn do-jump!
  "F12/Ctrl+B, the ui's own :jump!*: help/jump-target on ed's
  snapshot, using ev/lookup as the external fallback. :buffer places the
  caret, status \"Jumped to <sym>.\". An :external target whose lookup
  carries :file goes through jonquil.source/location: a location in the
  window's own file moves the caret to that line (clamped) instead; one
  in a project file opens in its tab (open-in-tab!); anything else opens
  in the source panel. An :external target without
  :file (ClojureDart, ClojureScript, jank, a Jolt var with no recorded
  file) shows its doc in the quick-doc popover, closing every other
  popover first. No definition reads \"No definition found for <sym>.\", no symbol
  \"No symbol at the caret.\" Wrapped in try/catch like do-quick-doc!. ctx
  is {:ed :ev* :status-label :help-pop :close-popovers-fn :sp :right :path
  :sel*}."
  [{:keys [ed status-label] :as ctx}]
  (try
    (let [snap (editor/snapshot ed)]
      (if-let [{:keys [symbol start] :as t} (help/target snap)]
        (let [local (help/jump-target snap nil)]
          (if (= :buffer (:kind local))
            (jump-to! ctx symbol local)
            ;; A late answer is placed against the text as it is then, not
            ;; as it was when F12 was pressed.
            (ask-lookup! ctx symbol (:text snap) start
                         (fn [] (and ((:active? ctx (constantly true))) (live-editor? ed)
                                     (same-target? (select-keys t [:symbol :start]) (editor/snapshot ed))))
                         "jump"
                         (fn [doc] (jump-to! ctx symbol (help/jump-target (editor/snapshot ed) doc))))))
        (win-status/set-status! status-label "No symbol at the caret.")))
    (catch :default e
      (win-status/set-status! status-label (str "; jump: " (ex-message e))))))

(defn jump-to!
  "do-jump!'s target in hand (help/jump-target's answer, nil for none)."
  [{:keys [ed status-label help-pop close-popovers-fn path sel*] :as ctx} symbol target]
  (let [loc (when (= :external (:kind target))
              (source/location (:doc target) {:project-dir (:project-dir @sel*)}))]
    (cond
      (= :buffer (:kind target))
      (do (place-caret-and-scroll! ed (:offset target))
          (win-status/set-status! status-label (str "Jumped to " symbol ".")))

      (and (:path loc) @path (source/same-path? (:path loc) @path))
      (let [text (editor/editor-text ed)]
        (place-caret-and-scroll! ed (source/line-offset text (:line (source/clamp-line text (:line loc)))))
        (win-status/set-status! status-label (str "Jumped to " symbol ".")))

      (and (:editable? loc) (:open-file! ctx))
      (do (close-popovers-fn help-pop)
          (open-in-tab! ctx symbol loc))

      loc
      (do (close-popovers-fn help-pop)
          (show-source! ctx loc))

      (= :external (:kind target))
      (do (close-popovers-fn help-pop)
          (help-popover/show-doc! help-pop (help/doc-text symbol (:doc target))))

      :else
      (win-status/set-status! status-label (str "No definition found for " symbol ".")))))

(defn- do-source-jump!
  "F12/Ctrl+B inside the source panel: the same lookup on the panel
  editor's own snapshot, so the namespace comes from the panel file's ns
  form. A definition in the panel's file moves the panel caret (history
  kept); one in the window's own file moves the window's caret to it and
  focuses the window's editor, as do-jump! does; one elsewhere loads into the panel; none
  says so on the footer. ctx as do-jump!'s."
  [{:keys [sp] :as ctx}]
  (try
    (let [snap (editor/snapshot (:editor sp))]
      (if-let [{:keys [symbol start] :as t} (help/target snap)]
        (let [local (help/jump-target snap nil)]
          (if (= :buffer (:kind local))
            (source-jump-to! ctx symbol local)
            ;; Only while the panel still shows, and placed against its
            ;; text as it is then.
            (ask-lookup! ctx symbol (:text snap) start
                         (fn [] (and (pos? (g/gtk-widget-get-visible (:source-root (:right ctx))))
                                     (same-target? (select-keys t [:symbol :start]) (editor/snapshot (:editor sp)))))
                         "jump"
                         (fn [doc] (source-jump-to! ctx symbol (help/jump-target (editor/snapshot (:editor sp)) doc))))))
        (source-pane/report! sp "No symbol at the caret.")))
    (catch :default e
      (source-pane/report! sp (str "; jump: " (ex-message e))))))

(defn- source-jump-to!
  "do-source-jump!'s target in hand (help/jump-target's answer, nil for
  none)."
  [{:keys [sp sel* ed path status-label] :as ctx} symbol target]
  (let [loc (when (= :external (:kind target))
              (source/location (:doc target) {:project-dir (:project-dir @sel*)}))]
    (cond
      (= :buffer (:kind target)) (source-pane/move-to-offset! sp (:offset target))
      (and (:path loc) path @path (source/same-path? (:path loc) @path))
      (let [text (editor/editor-text ed)]
        (place-caret-and-scroll! ed (source/line-offset text (:line (source/clamp-line text (:line loc)))))
        (editor/focus! ed)
        (win-status/set-status! status-label (str "Jumped to " symbol ".")))
      (and (:editable? loc) (:open-file! ctx)) (open-in-tab! ctx symbol loc)
      loc (show-source! ctx loc)
      :else (source-pane/report! sp (str "No source found for " symbol ".")))))

(defn- close-source-panel!
  "Hides the source panel (its state kept) and focuses the editor;
  hide-right-panel! also cancels any re-scroll settling still in flight
  for it."
  [right ed]
  (win-right-slot/hide-right-panel! right :source)
  (editor/focus! ed))

(defn- do-source-open!
  "Open in window: an editable panel file opens in a tab through open-file!
  (a new tab, or the tab already showing it), the caret goes to the panel
  caret's line, and the panel closes -- but only once that tab really
  shows loc's own path (source/same-path?). When it doesn't, the read
  failed: the panel stays open and says so on its footer. A read-only
  location only says why on the footer. Nothing is replaced, so there's
  no unsaved-changes gate. ctx is {:cv :sp :right :open-file!
  :active-tab}."
  [{:keys [sp right open-file! active-tab]}]
  (when-let [{:keys [loc caret]} (source-pane/current sp)]
    (if-not (:editable? loc)
      (source-pane/report! sp (source-pane/read-only-reason loc))
      (let [line (source/line-at (editor/editor-text (:editor sp)) caret)
            {err :error} (open-file! (:path loc))
            {:keys [ed path]} (active-tab)]
        (if (source/same-path? @path (:path loc))
          (do (place-caret-and-scroll! ed (source/line-offset (editor/editor-text ed) line))
              (close-source-panel! right ed))
          (source-pane/report! sp (or err "Couldn't open that file.")))))))

;; --- rename ---------------------------------------------------------------

(defn do-rename!
  "Shift+F6/F2, the ui's own :rename!*: help/target on ed's current
  snapshot; none sets status \"Put the caret on a symbol first.\"
  (rename-problem's OWN sentence for this case reads the same, but this
  path never opens the popover at all, so there is no problem label to
  show it in). Otherwise closes every other open
  popover first (close-popovers-fn, same reasoning as do-quick-doc!/
  do-jump! above) and shows the rename popover prefilled with the target
  symbol. Same try/catch reasoning as do-quick-doc!/do-jump!: help/target
  and help-popover/show-rename! run with nothing else between this and a
  throw."
  [ed rename-pop status-label close-popovers-fn]
  (try
    (if-let [{:keys [symbol]} (help/target (editor/snapshot ed))]
      (do (close-popovers-fn)
          (help-popover/show-rename! rename-pop symbol))
      (win-status/set-status! status-label "Put the caret on a symbol first."))
    (catch :default e
      (win-status/set-status! status-label (str "; rename: " (ex-message e))))))

(defn- occurrence-count-phrase
  "\"n occurrence\" or \"n occurrences\" -- the plural rule for the rename
  status line."
  [n]
  (str n " occurrence" (when (not= 1 n) "s")))

(defn do-rename-to!
  "The rename popover's Enter, and the ui's own :rename-to!*: new-name is
  the entry's current text. help/rename-problem on ed's current snapshot
  -- non-nil shows that sentence in the popover's own problem label and
  returns it, applying nothing; nil applies edit/rename's result through
  editor/apply-op! (one user action, one undo step per apply-op!'s own
  contract), closes the popover, sets the rename status wording and
  returns nil. old is read once (help/target) and reused for both the
  status line and (inside rename-problem) the problem sentence, mirroring
  do-quick-doc!/do-jump!'s own snapshot-once shape. Wrapped in try/catch,
  same reasoning as every other popover action here -- this is the entry's own
  \"activate\" signal handler, so nothing else stands between a throw
  here and GTK's own signal emission."
  [ed rename-pop new-name status-label]
  (try
    (let [snap (editor/snapshot ed)
          old (:symbol (help/target snap))
          problem (help/rename-problem snap new-name)]
      (if problem
        (do (help-popover/show-rename-problem! rename-pop problem) problem)
        (let [result (edit/rename snap new-name)]
          (editor/apply-op! ed result)
          (popover/hide! rename-pop)
          (win-status/set-status! status-label
                                  (str "Renamed " (occurrence-count-phrase (count (:edits result)))
                                       " of '" old "' to '" new-name "'."))
          nil)))
    (catch :default e
      (let [msg (str "; rename: " (ex-message e))]
        (win-status/set-status! status-label msg)
        msg))))

;; --- completion ---------------------------------------------------------

(defn do-complete!
  "Ctrl+Space, the ui's own :complete!*: completion/open! on ed's current
  snapshot. Wrapped in try/catch, the same reasoning as do-quick-doc!/
  do-jump! above -- completion/open! runs help/prefix-at, docs/candidates,
  help/open-state and, for a single candidate, editor/apply-op! straight
  through, with nothing else between it and a throw."
  [ed completion status-label]
  (try
    (completion/open! completion (editor/snapshot ed))
    (catch :default e
      (win-status/set-status! status-label (str "; complete: " (ex-message e))))))

;; --- REPL completion ----------------------------------------------------

(defn- repl-history-context
  "The evaluator's REPL history entries (jonquil.eval/create-history's own
  :items), joined by newlines -- the extra-context for docs/
  candidates' 5-arity, read straight off (ev/history @ev*), the SAME atom
  history-prev-fn/history-next-fn (in jonquil.gtk.window.run) read and
  write, never a parallel copy of it."
  [ev*]
  (str/join "\n" (:items @(ev/history @ev*))))

(defn do-repl-complete!
  "Ctrl+Space in the console, the ui's own console-facing :complete!*:
  cview/input-snapshot on cv, extended with repl-history-context as
  :extra-context, then completion/open! on repl-completion -- nil from
  input-snapshot (caret before input-start) does nothing, same gate the
  console's own key dispatch already applies before ever calling this.
  Same try/catch reasoning as do-complete! above."
  [cv ev* repl-completion status-label]
  (try
    (when-let [snap (cview/input-snapshot cv)]
      (completion/open! repl-completion (assoc snap :extra-context (repl-history-context ev*))))
    (catch :default e
      (win-status/set-status! status-label (str "; complete: " (ex-message e))))))

(defn wire-completion!
  "Joins completion to the popover registry and its own view's key/caret/
  focus hooks -- shared by the editor's and the console's completion
  instance, which otherwise wire identically. set-fns! registers
  :complete! and a :handle-key! that reports a throw to the status label
  and returns 1 (completion/handle-key! already caught the key by the
  time it reached this wrapper, so a throw escaping it still means the
  key was owned, never a fall-through); on-moved! is the view's own
  caret-move hook (on-cursor-moved! for the editor, on-input-changed! for
  the console -- the callback ignores whatever args it's called with, so
  one fn works for either) and refines the popup on every call, reporting
  a throw the same way; on-focus-lost! closes the popup."
  [completion complete-fn status-label register-popover-fn set-fns! on-moved! on-focus-lost!]
  (register-popover-fn completion)
  (set-fns!
   {:complete! complete-fn
    :handle-key! (fn [keyval state]
                   (try
                     (completion/handle-key! completion keyval state)
                     (catch :default e
                       (win-status/set-status! status-label (str "; complete: " (ex-message e)))
                       1)))})
  (on-moved! (fn [& _]
               (try
                 (completion/refine! completion)
                 (catch :default e
                   (win-status/set-status! status-label (str "; complete: " (ex-message e)))))))
  (on-focus-lost! (fn [] (completion/close! completion))))

(defn build
  "activate!'s popover registry, source-ctx and completion bindings, in activate!'s order: the popovers atom with its close and register fns, jump-back* and source-ctx (the map quick doc, jump and the source panel take for a tab), and the evaluator fns and the opts every tab's completion is built with. w holds what they read: :ev* :status-label :sp :sel* :active? :active-tab, :open-file! (jonquil.gtk.window's open-file!, called with @*tab-ctx), and :tab-ctx* and :right* (activate!'s *tab-ctx and *right, read when source-ctx and its :open-file! run). Returns w with :popovers :close-popovers-fn :register-popover-fn :source-ctx :server-ev :completion-ns-fn and :completion-opts added."
  [{:keys [ev* status-label sp sel* active? active-tab open-file!] *tab-ctx :tab-ctx* *right :right* :as w}]
  (let [;; This window's own registry of popovers (completion, quick doc,
        ;; rename register theirs via register-popover-fn as they're
        ;; created); close-popovers-fn is what the window keymap's Escape
        ;; binding and :close-popovers!* call.
        ;; It takes an optional popover to skip -- a caller about to show
        ;; or reposition ITS OWN popover passes it here, so that one is
        ;; never hidden and reshown within the same frame.
        popovers (atom [])
        close-popovers-fn (fn [& [skip]] (close-popovers! popovers skip))
        register-popover-fn (fn [p] (register-popover! popovers p))
        ;; Where each jump into a tab started, newest last, for Cmd/Ctrl+[
        ;; (open-in-tab!, jump-back!).
        jump-back* (atom [])
        source-ctx (fn [t] {:ed (:ed t) :ev* ev* :status-label status-label :help-pop (:help-pop t)
                            :close-popovers-fn close-popovers-fn :sp sp :right @*right
                            :path (:path t) :sel* sel* :active? (fn [] (active? t))
                            :open-file! (fn [p] (open-file! @*tab-ctx p))
                            :active-tab active-tab :jump-back* jump-back*})
        ;; The window's evaluator when its help asks a server
        ;; (ev/ServerHelp): completion then asks off the GTK thread.
        server-ev (fn [] (let [e @ev*] (when (ev/server-help? e) e)))
        ;; Shared by every tab's completion and the console's: the running
        ;; program's namespace, read fresh at every lookup.
        completion-ns-fn (fn [] (ev/prompt-ns @ev*))
        ;; What each tab's completion is built with (jonquil.gtk.tab adds
        ;; its own :ed); close-others! is the registry-wide close, so
        ;; opening completion closes any other open popover.
        completion-opts {:ns-fn completion-ns-fn
                         :candidates-fn (fn [prefix text pos extra] (ev/complete @ev* prefix text pos extra))
                         :doc-fn (fn [sym text pos] (ev/lookup @ev* sym text pos))
                         :server-ev server-ev
                         :close-others! close-popovers-fn
                         :on-error (fn [msg] (win-status/set-status! status-label msg))}]
    (assoc w :popovers popovers :close-popovers-fn close-popovers-fn :register-popover-fn register-popover-fn
           :source-ctx source-ctx :server-ev server-ev :completion-ns-fn completion-ns-fn :completion-opts completion-opts)))

(defn build-source-keys
  "activate!'s source panel keys, in activate!'s order: the panel's F12, Close, Back and Open in window fns, jump-back-fn (Cmd/Ctrl+[ after a jump), and source-keys, the map the window keys read while the panel has focus. w holds what they read: :source-ctx :active-tab :right :sp :win :tabs, :open-file! :select-tab! and :tab-by-id (jonquil.gtk.window's own fns, as the parts' wctx passes them), and :tab-ctx* (activate!'s *tab-ctx, read when one of them runs). Returns w with :source-jump-fn :source-close-fn :source-back-fn :source-open-fn :jump-back-fn and :source-keys added."
  [{:keys [source-ctx active-tab right sp win tabs open-file! select-tab! tab-by-id] *tab-ctx :tab-ctx* :as w}]
  (let [source-jump-fn (fn [] (do-source-jump! (source-ctx (active-tab))))
        source-close-fn (fn [] (close-source-panel! right (:ed (active-tab))))
        source-back-fn (fn [] (source-pane/back! sp))
        source-open-fn (fn [] (do-source-open! {:sp sp :right right :active-tab active-tab
                                                :open-file! (fn [p] (open-file! @*tab-ctx p))}))
        jump-back-fn (fn [] (jump-back! (assoc (source-ctx (active-tab))
                                               :select-tab! (fn [id] (select-tab! @*tab-ctx id))
                                               :tab-open? (fn [id] (some? (tab-by-id tabs id))))))
        source-keys {:focused? (fn [] (source-pane/focused? sp win)) :close! source-close-fn
                     :back! source-back-fn :open! source-open-fn :jump-back! jump-back-fn}]
    (assoc w :source-jump-fn source-jump-fn :source-close-fn source-close-fn :source-back-fn source-back-fn
           :source-open-fn source-open-fn :jump-back-fn jump-back-fn :source-keys source-keys)))
