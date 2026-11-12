(ns gtk-selftest-window.change-sets
  "Change sets (LM3) over the real window: the changed forms of a run tab
  gathered into a named set, kept, applied to the REPL, reverted in their
  tab as one undo step and deleted; and an agent's proposal, saved through
  the MCP tool and opened from the palette."
  (:require [selftest-keys]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jonquil.change-sets :as cs]
            [jonquil.gtk.change-sets-pane :as pane]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.palette :as palette]
            [jonquil.mcp.window :as mcp-window]
            [temp-dirs]))

(defn- status [ui] (g/label-text (:status-label ui)))

(defn- run-active-palette!
  "Opens the active tab's palette, filters it to query and runs the first
  entry. GTK-thread only."
  [ui query]
  ((:open-palette!* ui))
  (let [p (:palette ((:active-tab* ui)))]
    (palette/set-query! p query)
    (palette/run-selected! p)))

(defn- kept-files
  "The .edn files under the selftest's change-sets folder, as {name path}."
  [ui]
  (let [root (io/file (.getParent (io/file ((:settings-path* ui)))) "change-sets")]
    (into {} (comp (filter #(and (.isFile ^java.io.File %) (str/ends-with? (.getName ^java.io.File %) ".edn")))
                   (map (fn [^java.io.File f] [(str/replace (.getName f) #"\.edn$" "") (.getPath f)])))
          (file-seq root))))

(defn- cleanup! [ui ed]
  (doseq [[_ p] (kept-files ui)] (io/delete-file p true))
  (let [p (:change-sets-pane ui)]
    (when-let [f (:on-close @(:handlers p))] (f)))
  (doseq [t ((:tabs* ui))] (editor/mark-saved! (:ed t)))
  (doseq [t (rest ((:tabs* ui)))] ((:close-tab! ui) (:id t)))
  ((:select-tab! ui) (:id (first ((:tabs* ui)))))
  ed)

(defn row-change-set-make-apply-revert
  "Make a Change Set after a Run and two edits shows the draft as diffs,
  Save keeps it under the typed name, Apply to REPL sends both forms, Revert
  in Tabs puts the changed one back (the new one has no earlier text, so it
  is skipped and named) as one undo step, and Delete removes the file."
  [ui]
  (go-row :change-set-make-apply-revert support/check
    (let [f (io/file (temp-dirs/temp-dir! "jonquil-selftest-cset-") "cs.clj")
          ran "(ns cs)\n(def a 1)\n(def b 2)\n"
          edited "(ns cs)\n(def a 10)\n(def b 2)\n(def c 3)\n"
          p (:change-sets-pane ui)
          _ (spit f ran)
          _ (on-gtk ((:open-file! ui) (.getPath f)))
          ed (on-gtk (:ed ((:active-tab* ui))))
          _ (on-gtk (run-active-palette! ui "make a change set"))
          not-run? (await-until 3000 20 #(= "; Run the tab once first." (status ui)))
          _ (on-gtk ((:run!* ui)))
          recorded? (await-until 8000 20 #(= ran @(:last-run (:changed ed))))
          _ (on-gtk (editor/apply-op-plain! ed {:edits [[0 (count ran) edited]] :caret 0}))
          _ (on-gtk (run-active-palette! ui "make a change set from the forms"))
          drafted? (await-until 3000 20 #(str/includes? (pane/summary-text p) "(not kept yet): 2 forms in 1 file"))
          draft-text (on-gtk (pane/diff-text p))
          draft-name (on-gtk (pane/name-text p))
          _ (on-gtk (pane/set-name! p "cs idea") (pane/click! p :save))
          saved? (await-until 3000 20 #(contains? (kept-files ui) "cs-idea"))
          kept (get (kept-files ui) "cs-idea")
          read (when kept (cs/read-set kept))
          mark (on-gtk (support/out-end-offset ui))
          _ (on-gtk (pane/click! p :apply))
          applied? (await-until 8000 20 #(and (support/idle? ui)
                                              (str/includes? (support/since ui mark) "Applied the change set \"cs-idea\": 2 forms.")))
          console (on-gtk (support/since ui mark))
          _ (on-gtk (pane/click! p :revert))
          reverted? (await-until 3000 20 #(= "(ns cs)\n(def a 1)\n(def b 2)\n(def c 3)\n" (editor/editor-text ed)))
          footer (on-gtk (pane/footer-text p))
          console2 (on-gtk (support/since ui mark))
          _ (on-gtk (g/gtk-text-buffer-undo (:buf ed)))
          undone (on-gtk (editor/editor-text ed))
          _ (on-gtk (pane/click! p :delete))
          deleted? (await-until 3000 20 #(not (contains? (kept-files ui) "cs-idea")))]
      (on-gtk (support/check :change-set-make-apply-revert
                             (and not-run? recorded? drafted? saved? applied? reverted? deleted?
                                  (str/includes? draft-text "- (def a 1)\n+ (def a 10)")
                                  (str/includes? draft-text "cs.clj  def c (new)\n+ (def c 3)")
                                  (= "cs-changes" draft-name)
                                  (= ["def a" "def c"] (map :name (:entries (:set read))))
                                  (str/includes? console "Applying 1 of 2: def a")
                                  (str/includes? console "Applying 2 of 2: def c")
                                  (= [10 3] [(some-> (resolve 'cs/a) deref) (some-> (resolve 'cs/c) deref)])
                                  (nil? (resolve 'user/c))
                                  (str/includes? footer "Reverted 1 form of the change set \"cs-idea\", skipped 1.")
                                  (str/includes? console2 "Revert skipped def c: it is new since the last Run")
                                  (= edited undone))
                             {:not-run not-run? :recorded recorded? :drafted drafted? :draft draft-text :name draft-name
                              :saved saved? :read read :applied applied? :console console :reverted reverted?
                              :footer footer :undone undone :deleted deleted? :status (status ui)})
              (cleanup! ui ed)))))

(defn row-change-set-agent-proposal
  "jonquil_change_set_propose saves a set marked with the client's name and
  applies nothing; a taken name and an unbalanced form are refused; the
  palette's Change Sets entry opens the set, as a diff against the file."
  [ui]
  (go-row :change-set-agent-proposal support/check
    (let [;; the project's folder: a proposal's path has to be inside it
          f (io/file ((:change-sets-root* ui)) (str "cset-selftest-" (System/nanoTime) ".clj"))
          outside (io/file (temp-dirs/temp-dir! "jonquil-selftest-cset-agent-") "out.clj")
          _ (spit outside "(def o 1)\n")
          _ (spit f "(ns ag)\n(def a 1)\n")
          refused-outside (try ((:call (mcp-window/make-window-tools {:ui ui :dir (.getParent f)}))
                                "jonquil_change_set_propose"
                                {"name" "agent out" "forms" [{"path" (.getPath outside) "text" "(def o 2)"}]})
                               (catch :default e {:threw (ex-message e)}))
          tools (mcp-window/make-window-tools {:ui ui :dir (.getParent f) :client-fn (constantly "Claude Code")})
          call (fn [args] ((:call tools) "jonquil_change_set_propose" args))
          p (:change-sets-pane ui)
          forms [{"path" (.getPath f) "text" "(def a 99)"}]
          r (call {"name" "agent idea" "forms" forms})
          again (call {"name" "agent idea" "forms" forms})
          bad (call {"name" "agent bad" "forms" [{"path" (.getPath f) "text" "(def a ("}]})
          kept (get (kept-files ui) "agent-idea")
          s (:set (when kept (cs/read-set kept)))
          pal (on-gtk (:palette ((:active-tab* ui))))
          _ (on-gtk (run-active-palette! ui "change sets"))
          listed (on-gtk (palette/shown-docs pal))
          s1 (on-gtk (status ui))
          _ (on-gtk (palette/set-query! pal "agent-idea")
                    (palette/run-selected! pal))
          s2 (on-gtk (status ui))
          opened? (await-until 3000 20 #(str/includes? (pane/summary-text p) "calling itself \"Claude Code\""))
          text (on-gtk (pane/diff-text p))
          revert-visible (on-gtk (g/gtk-widget-get-visible (:revert-btn p)))
          said? (await-until 3000 20 #(str/includes? (status ui) "A client proposed the change set"))]
      (on-gtk (support/check :change-set-agent-proposal
                             (and (not (:error? r)) (:error? again) (:error? bad) opened?
                                  (:error? refused-outside)
                                  (str/includes? (str (:text refused-outside)) "outside the project")
                                  (nil? (get (kept-files ui) "agent-out"))
                                  (= "Claude Code" (:by s))
                                  (= [(.getCanonicalPath f)] (map :path (:entries s)))
                                  (str/ends-with? text "- (def a 1)\n+ (def a 99)")
                                  (nil? (get (kept-files ui) "agent-bad"))
                                  (zero? revert-visible))
                             {:r r :again again :bad bad :set s :opened opened? :text text :said said? :listed listed :s1 s1 :s2 s2 :status (status ui)})
              (io/delete-file f true)
              (cleanup! ui nil)))))
