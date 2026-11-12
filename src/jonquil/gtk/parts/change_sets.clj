(ns jonquil.gtk.parts.change-sets
  "LM3's change sets as a window part (the commands and the label are
  jonquil.parts.change-sets/spec): Make a Change Set gathers the active
  tab's changed forms, Change Sets lists the project's kept sets in the
  palette, and the panel shows one as diffs with Save, Apply to REPL, Revert
  in Tabs and Delete. An agent's jonquil_change_set_propose lands as a kept
  set through the :change-set-propose!* ui hook; nothing here applies a set
  on its own."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jonquil.change-sets :as cs]
            [jonquil.clj :as clj]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.callbacks :refer [on-gui!]]
            [jonquil.gtk.change-sets-pane :as pane]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.palette :as palette]
            [jonquil.nrepl.attach]
            [jonquil.nrepl.jank]
            [jonquil.nrepl.shadow]
            [jonquil.session]
            [jonquil.source :as source]))

(defn- sets-dir
  "The project's change sets folder: the window's folder, or one no-folder
  place, never the active tab's own folder (as the worlds part keeps its
  history)."
  [{:keys [settings-path* tree-pane]}]
  (cs/sets-dir @settings-path* (:root @(:state tree-pane))))

(defn- say!
  "A line on the console."
  [{:keys [cv]} s]
  (cview/append! cv :system (str "; " s "\n")))

(defn- plural [n one many] (str n " " (if (= 1 n) one many)))

(defn- tab-for-path
  [{:keys [tabs]} path]
  (some (fn [t] (when (and @(:path t) (source/same-path? @(:path t) path)) t)) @tabs))

;; --- showing a set -------------------------------------------------------------

(defn- show-set!
  "Shows set s in the panel and opens it. draft? for one not kept yet; file
  is the name of its file in the sets folder when it is kept. An agent's
  set offers Apply and Delete only: its :text was never in a tab, so
  Revert has nothing to put back."
  [{:keys [panep shown* right show-right-panel!] :as ctx} s draft? file]
  (reset! shown* {:set s :draft? draft? :file file})
  (pane/show! panep {:summary (cs/summary-text s draft?) :text (cs/set-text s) :draft? draft?
                     :proposed? (some? (:by s))
                     :default-name (when draft? (:name s))})
  (show-right-panel! right :change-sets)
  (g/gtk-widget-grab-focus (if draft? (:name-entry panep) (:close-btn panep)))
  ctx)

(defn- show-broken!
  "Shows a set whose file can't be used: why it can't, and Delete."
  [{:keys [panep shown* right show-right-panel!]} {:keys [name path broken]}]
  (reset! shown* {:broken name :file name :path path})
  (pane/show! panep {:summary (str "Change set \"" name "\" can't be used.") :text broken :draft? false})
  (doseq [k [:apply-btn :revert-btn]] (g/gtk-widget-set-visible (get panep k) 0))
  (show-right-panel! right :change-sets))

(defn- suggested-name
  "A name for a new set made from path's file that no kept set has."
  [ctx path]
  (let [taken (set (map :name (cs/list-sets (sets-dir ctx))))
        base (or (cs/safe-name (str/replace (.getName (io/file path)) #"\.[^.]*$" "")) "changes")
        base (str base "-changes")]
    (or (first (remove taken (cons base (map #(str base "-" %) (iterate inc 2))))) base)))

;; --- making ---------------------------------------------------------------------

(defn- make-set!
  "Make a Change Set: the active tab's changed forms, shown as a draft that
  waits for a name."
  [{:keys [active-tab set-status!] :as ctx}]
  (let [t (active-tab)
        ed (:ed t)
        path @(:path t)
        ran (some-> (:changed ed) :ran deref)]
    (cond
      (nil? path) (set-status! "; Save this tab first: a change set names its file.")
      (nil? ran) (set-status! "; Run the tab once first.")
      :else
      (let [text (editor/editor-text ed)
            es (cs/from-changed text (some-> (:changed ed) :last-run deref) path ran)]
        (if (empty? es)
          (set-status! "; Nothing has changed since the last run.")
          (show-set! ctx (cs/make-set (suggested-name ctx path) es nil) true nil))))))

(defn- save-set!
  "Save Set: keeps the draft under name."
  [{:keys [shown* panep set-status!] :as ctx} typed]
  (let [{:keys [set draft?]} @shown*
        n (cs/safe-name typed)]
    (cond
      (not draft?) (pane/report! panep "; There is nothing to save here.")
      (nil? n) (pane/report! panep "; Give the set a name with some letters or digits in it.")
      :else
      (let [dir (sets-dir ctx)
            replaced? (.exists (io/file (cs/set-path dir n)))
            s (assoc set :name n)]
        (if-let [err (cs/write-set! dir s)]
          (pane/report! panep (str "; " err))
          (do (show-set! ctx s false n)
              (pane/report! panep (str "Saved" (when replaced? ", replacing the set of that name") "."))
              (set-status! (str "; Saved the change set \"" n "\" (" (plural (count (:entries s)) "form" "forms") ")."))))))))

;; --- listing and opening --------------------------------------------------------

(defn- list-entry
  [{:keys [name set broken]}]
  {:id [:change-set/open name]
   :doc (if broken
          (str "Change Set: " name " (broken)")
          (str "Change Set: " name ", " (plural (count (:entries set)) "form" "forms")
               (when-let [by (cs/clean-client (:by set))] (str ", from client \"" by "\""))))
   :chords [] :source "change sets"})

(defn- list-sets!
  "Change Sets: the project's kept sets in the palette."
  [{:keys [active-tab set-status!] :as ctx}]
  (let [ls (cs/list-sets (sets-dir ctx))]
    (if (empty? ls)
      (set-status! "; No change sets yet: Make a Change Set gathers the forms you edited.")
      (palette/open! (:palette (active-tab)) (mapv list-entry ls)))))

(defn- open-set!
  "Shows the kept set called name."
  [{:keys [set-status!] :as ctx} name]
  (let [dir (sets-dir ctx)
        path (cs/set-path dir name)
        r (cs/read-set path)]
    (cond
      (not (.exists (io/file path))) (set-status! (str "; No change set called \"" name "\" any more."))
      (:set r) (show-set! ctx (:set r) false name)
      :else (show-broken! ctx {:name name :path path :broken (:broken r)}))))

(defn- open-panel!
  "Focus the Change Sets panel when it isn't showing: the set it showed last,
  else the list."
  [{:keys [shown*] :as ctx}]
  (if-let [s (:set @shown*)]
    (show-set! ctx s (:draft? @shown*) (:file @shown*))
    (list-sets! ctx)))

;; --- Apply to REPL ----------------------------------------------------------------

(defn- entry-source-text
  "The text the namespace of path's entries is read from: the open tab's
  text, else the file's, else nil."
  [ctx path]
  (or (some-> (tab-for-path ctx path) :ed editor/editor-text)
      (try (slurp path) (catch :default _ nil))))

(defn eval-opts
  "{:opts m} for evaluating a form of the file whose text is text in that
  file's namespace on evaluator e, with the prompt's own namespace left
  as it is, or {:stop msg} when that can't be done.
  - The in-process session and the Jolt child take a :quiet eval, which
    runs in :ns-source's namespace and leaves the prompt alone; in process
    a namespace that isn't loaded stops the apply. The child falls back to
    its session's namespace for one that isn't loaded.
  - The attach, shadow and jank evaluators switch to :ns-source's namespace
    themselves.
  - Any other runtime ignores :ns-source, so a file with an ns form stops
    the apply rather than land its forms in the wrong namespace."
  [e text]
  (let [n (clj/buffer-ns text)
        quiet {:quiet (str "jq-change-set-" (System/nanoTime)) :ns-source text}]
    (cond
      (instance? jonquil.session.InProcess e)
      (if (and n (not (find-ns (symbol n))))
        {:stop (str n " isn't loaded: Run its file first")}
        {:opts quiet})

      (satisfies? ev/QuietEval e) {:opts quiet}

      (or (instance? jonquil.nrepl.attach.Attach e)
          (instance? jonquil.nrepl.shadow.Shadow e)
          (instance? jonquil.nrepl.jank.Jank e))
      {:opts {:ns-source text}}

      (nil? n) {:opts {:ns-source text}}

      :else {:stop (str "this runtime can't evaluate in a file's namespace (" n "), so the forms were not sent")})))

(declare apply-one!)

(defn- entry-tab
  "The tab entry e's form goes from: its file's open tab, else the active
  one."
  [{:keys [active-tab] :as ctx} e]
  (or (tab-for-path ctx (:path e)) (active-tab)))

(defn- entry-blocked
  "Why entry e can't be sent from tab t now, or nil: its tab belongs to
  another project, or something is already running."
  [{:keys [cv ev* sel* refuse-foreign!] :as ctx} e t]
  (cond
    (and (tab-for-path ctx (:path e)) (refuse-foreign! {:tab t :cv cv :sel* sel*}))
    "its tab belongs to another project"
    (ev/evaluating? @ev*) "something is already running"))

(defn- answer-outcome
  "What an evaluation's answer m means for the apply: :ok to go on, a
  reason to stop (busy, or an end other than :ok), or nil while it isn't
  over."
  [m]
  (cond
    (:busy m) "the evaluator was busy"
    (:done m) (let [status (:status (:result (:done m)))]
                (if (= :ok status) :ok (str "it ended " (if status (name status) "badly"))))))

(defn- entry-ended!
  "After the i-th entry's evaluation: goes on to the next on :ok, else
  stops the apply with outcome's reason."
  [ctx {:keys [s entries i stop!]} outcome]
  (if (= :ok outcome)
    (apply-one! ctx s entries (inc i))
    (stop! outcome)))

(defn- on-entry-answer
  "The mcp-eval! callback for the i-th entry: its outcome (answer-outcome)
  on the GTK thread."
  [ctx step]
  (fn [m]
    (when-let [outcome (answer-outcome m)]
      (on-gui! (fn [] (entry-ended! ctx step outcome))))))

(defn- send-entry!
  "Sends entry e's form from tab t in its file's namespace, or stops the
  apply when eval-opts says it can't be."
  [{:keys [cv ev* mcp-eval! status-label] :as ctx} {:keys [e t label stop!] :as step}]
  (let [{:keys [opts stop]} (eval-opts @ev* (entry-source-text ctx (:path e)))]
    (if stop
      (stop! stop)
      (do (say! ctx (str "Applying " label))
          (mcp-eval! {:tab t :cv cv :ev* ev* :status-label status-label}
                     (:text e)
                     (assoc opts :owner (:id t))
                     (on-entry-answer ctx step))))))

(defn- apply-one!
  "Sends entries' i-th form to the evaluator as Run Selection sends code, in
  the namespace of its file, then goes on to the next when it ended :ok.
  A throw is said on the console, and stops the rest."
  [ctx s entries i]
  (if (>= i (count entries))
    (say! ctx (str "Applied the change set \"" (:name s) "\": " (plural (count entries) "form" "forms") "."))
    (let [e (nth entries i)
          t (entry-tab ctx e)
          label (str (inc i) " of " (count entries) ": " (:name e))
          stop! (fn [why] (say! ctx (str "Stopped at " label ", " why ". The forms after it were not sent.")))]
      (if-let [why (entry-blocked ctx e t)]
        (stop! why)
        (send-entry! ctx {:s s :entries entries :i i :e e :t t :label label :stop! stop!})))))

(defn not-loaded-message [n] (str n " isn't loaded: Run its file first"))

(defn check-namespaces!
  "Asks evaluator e, with one quiet eval, which of names (strings) exist,
  and calls cb once with {:missing [name ...]} or {:error msg}. For the Jolt
  child, which falls back to its session's namespace for one that doesn't
  exist and so can't be left to find out per form. cb runs on the eval
  worker, not the GTK thread."
  [e names cb]
  (if (empty? names)
    (cb {:missing []})
    (let [code (str "(mapv (fn [n] (some? (find-ns n))) '" (pr-str (mapv symbol names)) ")")
          warning (ev/eval! e code {:quiet (str "jq-change-set-ns-" (System/nanoTime))
                                    :on-output (fn [_ _] nil)
                                    :on-complete
                                    (fn [r]
                                      (let [answer (when (= :ok (:status r))
                                                     (try (edn/read-string (:printed r)) (catch :default _ nil)))]
                                        (cb (if (and (vector? answer) (= (count names) (count answer)))
                                              {:missing (vec (keep identity (map (fn [n ok] (when-not ok n)) names answer)))}
                                              {:error "couldn't ask the runtime which namespaces are loaded"}))))})]
      (when warning (cb {:error (str warning)})))))

(defn- child-namespaces
  "The namespaces the set's files name, when the evaluator is the Jolt
  child (a quiet-capable runtime other than the in-process session, which
  checks each form itself); else nil."
  [{:keys [ev*] :as ctx} entries]
  (let [e @ev*]
    (when (and (satisfies? ev/QuietEval e) (not (instance? jonquil.session.InProcess e)))
      (vec (distinct (keep #(clj/buffer-ns (entry-source-text ctx (:path %))) entries))))))

(defn- apply-set!
  "Apply to REPL: every form of the shown set, in order, in the namespace of
  its file; each result lands in the console. The Jolt child is first asked
  which of the files' namespaces exist, and nothing is sent when one
  doesn't."
  [{:keys [shown* panep ev*] :as ctx}]
  (let [{:keys [set draft?]} @shown*
        go (fn []
             (pane/report! panep (str "Applying " (plural (count (:entries set)) "form" "forms") ": see the console."))
             (say! ctx (str "Applying the change set \"" (:name set) "\" to the REPL"))
             (apply-one! ctx set (:entries set) 0))]
    (cond
      (or (nil? set) draft?) (pane/report! panep "; Save the set first.")
      (ev/evaluating? @ev*) (pane/report! panep "; Something is already running: Stop it first.")
      :else
      (if-let [names (seq (child-namespaces ctx (:entries set)))]
        (check-namespaces!
         @ev* (vec names)
         (fn [{:keys [missing error]}]
           (on-gui! (fn []
                      (cond
                        error (do (pane/report! panep (str "; " error))
                                  (say! ctx (str "Nothing was sent: " error)))
                        (seq missing) (do (pane/report! panep (str "; " (not-loaded-message (first missing))))
                                          (say! ctx (str (not-loaded-message (first missing)) ". The forms were not sent.")))
                        :else (go))))))
        (go)))))

;; --- Revert in Tabs -----------------------------------------------------------------

(defn- tab-to-revert
  "The tab showing path, opened first when it isn't open; nil when it can't
  be opened."
  [{:keys [open-file!] :as ctx} path]
  (or (tab-for-path ctx path)
      (when-not (:error (open-file! path)) (tab-for-path ctx path))))

(defn- revert-path!
  "Puts the last-run text of entries (all one file's) back in that file's
  tab as one undo step. {:reverted n :skipped [{:name :reason}]}."
  [ctx path entries]
  (if-let [t (tab-to-revert ctx path)]
    (let [ed (:ed t)
          {:keys [edits skipped]} (cs/revert-edits (editor/editor-text ed) entries)]
      (cond
        (empty? edits) {:reverted 0 :skipped skipped}
        (editor/apply-op-plain! ed {:edits edits :caret (ffirst edits)}) {:reverted (count edits) :skipped skipped}
        :else {:reverted 0
               :skipped (into skipped (map (fn [[s]] {:name (str "a form at offset " s) :reason "this tab is read-only"})) edits)}))
    {:reverted 0 :skipped (mapv (fn [e] {:name (:name e) :reason (str "couldn't open " path)}) entries)}))

(defn- revert-set!
  "Revert in Tabs: each form's last-run text back in its tab, one undo step
  per tab; what it skipped is named on the console."
  [{:keys [shown* panep set-status!] :as ctx}]
  (let [{:keys [set draft?]} @shown*]
    (cond
      (or (nil? set) draft?) (pane/report! panep "; Save the set first.")
      (:by set) (pane/report! panep "; An agent's set can be applied or deleted, not reverted: its forms were never in your tabs.")
      :else
      (let [results (mapv (fn [[path es]] (revert-path! ctx path es))
                          (group-by :path (:entries set)))
            reverted (reduce + (map :reverted results))
            skipped (mapcat :skipped results)
            line (str "Reverted " (plural reverted "form" "forms") " of the change set \"" (:name set) "\""
                      (when (seq skipped) (str ", skipped " (count skipped))) ".")]
        (doseq [{:keys [name reason]} skipped] (say! ctx (str "Revert skipped " name ": " reason)))
        (pane/report! panep line)
        (set-status! (str "; " line))))))

;; --- Delete, Close ---------------------------------------------------------------------

(defn- delete-set!
  [{:keys [shown* panep right hide-right-panel! set-status!] :as ctx}]
  (let [{:keys [draft? file]} @shown*
        n file]
    (cond
      (or (nil? n) draft?) (pane/report! panep "; Only a kept set can be deleted.")
      :else
      (if-let [err (cs/delete-set! (sets-dir ctx) n)]
        (pane/report! panep (str "; " err))
        (do (reset! shown* nil)
            (hide-right-panel! right :change-sets)
            (set-status! (str "; Deleted the change set \"" n "\".")))))))

(defn- close-panel!
  [{:keys [right hide-right-panel! active-tab]}]
  (hide-right-panel! right :change-sets)
  (editor/focus! (:ed (active-tab))))

;; --- an agent's proposal --------------------------------------------------------------

(defn- project-root
  "The folder a proposal's paths must stay inside: the window's folder, else
  the process's working directory."
  [ctx]
  (or (:root @(:state (:tree-pane ctx))) (System/getProperty "user.dir")))

(defn- propose!
  "jonquil_change_set_propose: saves the agent's forms as a kept set marked
  with the client's name (cleaned: it is the client's own word), or
  refuses. {:error msg} or {:saved name :forms n}. Never applies anything,
  and never replaces a set that exists: the file is made only if its name
  is free. Runs on the MCP serve thread: file work only, the notice goes to
  the GTK thread."
  [ctx {:keys [name forms client]}]
  (let [n (cs/safe-name name)
        root (project-root ctx)
        dir (sets-dir ctx)
        by (or (cs/clean-client client) "an unnamed client")]
    (cond
      (nil? n) {:error "the change set needs a name with some letters or digits in it"}
      :else
      (let [r (cs/proposal-entries root forms)]
        (if (:error r)
          r
          (let [s (cs/make-set n (:entries r) {:by by})]
            (if-let [err (cs/write-set! dir s {:create-new? true})]
              {:error err}
              (do (on-gui! (fn []
                             (say! ctx (str "A client calling itself \"" by "\" proposed the change set \"" n "\" ("
                                            (plural (count (:entries s)) "form" "forms")
                                            "): Change Sets lists it for you to apply or delete."))
                             ((:set-status! ctx) (str "; A client proposed the change set \"" n "\"."))))
                  {:saved n :forms (count (:entries s))}))))))))

;; --- install -----------------------------------------------------------------------------

(defn- install
  "The change sets part for one window: builds the panel, wires its buttons
  and answers the fns the commands and the palette's set entries call, and
  the ui hook the MCP tool saves a proposal with."
  [wctx]
  (let [panep (pane/make-pane)
        ctx (assoc wctx :panep panep :shown* (atom nil))]
    (pane/set-handlers!
     panep {:on-save (fn [typed] (save-set! ctx typed))
            :on-apply (fn [] (apply-set! ctx))
            :on-revert (fn [] (revert-set! ctx))
            :on-delete (fn [] (delete-set! ctx))
            :on-close (fn [] (close-panel! ctx))})
    {:fns {:change-set-make-fn (fn [] (make-set! ctx))
           :change-set-list-fn (fn [] (list-sets! ctx))
           :change-set-open-fn (fn [name] (open-set! ctx name))}
     :ui {:change-sets-pane panep
          :change-set-propose!* (fn [m] (propose! ctx m))
          :change-sets-root* (fn [] (project-root ctx))}
     :panels {:change-sets {:root (:root panep)
                            :focus! (fn [] (g/gtk-widget-grab-focus (:close-btn panep)))
                            :open! (fn [] (open-panel! ctx))}}}))

(def part
  "The change sets part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :change-sets}))
