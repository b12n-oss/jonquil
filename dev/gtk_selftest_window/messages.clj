(ns gtk-selftest-window.messages
  "The Messages panel (LM8): agent tool calls logged through the same
  wrapper the MCP servers use, the unread count, the filters, status lines
  merging, a closed tab's jump, and a burst from many threads."
  (:require [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jolt.ffi :as ffi]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.messages-pane :as messages-pane]
            [jonquil.mcp.window :as window]
            [jonquil.messages :as messages]
            [temp-dirs]))

(defn- tools-for
  "The window's tool table with every call logged to its Messages, as the
  MCP servers serve it; the client is called Selftest."
  [ui]
  (let [dir (temp-dirs/temp-dir! "jonquil-selftest-msg-dir-")]
    (window/with-messages (fn [] (window/make-window-tools {:ui ui :dir dir})) ui (atom {:client "Selftest"}))))

(defn- call [ui tname args] ((:call ((tools-for ui))) tname args))

(defn- panel-key!
  "Cmd/Ctrl+Alt+digit through the window's key handler."
  [ui digit]
  ((:handle-window-key! ui) ffi/null (int digit) 0 (bit-or g/META-MASK g/ALT-MASK) ffi/null))

(defn- shown? [pane] (pos? (g/gtk-widget-get-visible (:root pane))))

(defn- unread [ui]
  (let [l (:unread-label ui)]
    (when (pos? (g/gtk-widget-get-visible l)) (g/label-text l))))

(defn- fresh!
  "An empty log and the panel closed, so a row counts only its own entries.
  GTK-thread only."
  [ui]
  (reset! (:messages* ui) (messages/empty-log))
  (g/gtk-widget-set-visible (:unread-label ui) 0)
  (when-let [f (:on-close @(:handlers (:messages-pane ui)))] (f)))

(defn- close-panel! [ui]
  (on-gtk (when-let [f (:on-close @(:handlers (:messages-pane ui)))] (f))))

(defn- scratch-tab!
  "A new tab, its id. GTK-thread only."
  [ui]
  ((:new-tab!* ui))
  (:id ((:active-tab* ui))))

(defn- drop-tab!
  "Closes tab id without a save prompt. GTK-thread only."
  [ui id]
  (when-let [t (some #(when (= id (:id %)) %) ((:tabs* ui)))]
    (editor/mark-saved! (:ed t))
    ((:close-tab! ui) id)))

(defn row-messages-agent-replace-logged
  "An agent's jonquil_replace_text logs one entry, the status bar says
  \"● 1 message\", and Cmd/Ctrl+Alt+7 shows the panel with that row on top
  and clears the count."
  [ui]
  (go-row :messages-agent-replace-logged support/check
    (let [mp (:messages-pane ui)
          id (on-gtk (fresh! ui) (scratch-tab! ui))]
      (try
        (let [r (call ui "jonquil_replace_text" {"text" "(def m 1)" "id" id})
              counted? (await-until 3000 20 #(= "● 1 message" (unread ui)))
              _ (on-gtk (panel-key! ui \7))
              shown (await-until 3000 20 #(shown? mp))
              rows (on-gtk (messages-pane/row-texts mp))
              after (on-gtk (unread ui))]
          (on-gtk (support/check :messages-agent-replace-logged
                                 (and (not (:error? r)) counted? shown
                                      (= 1 (count rows))
                                      (str/includes? (first rows) "agent  Selftest replaced the text of Untitled")
                                      (nil? after))
                                 {:result r :counted counted? :shown shown :rows rows :after after})))
        (finally (on-gtk (drop-tab! ui id)) (close-panel! ui))))))

(defn row-messages-reads-hidden
  "A read-only call (jonquil_tabs) adds no row and no unread count until
  the Reads filter is ticked."
  [ui]
  (go-row :messages-reads-hidden support/check
    (let [mp (:messages-pane ui)]
      (on-gtk (fresh! ui))
      (try
        (call ui "jonquil_tabs" {})
        (on-gtk (panel-key! ui \7))
        (let [before (on-gtk {:rows (messages-pane/row-texts mp)
                              :footer (g/label-text (:footer mp)) :unread (unread ui)})
              _ (on-gtk (messages-pane/set-kind! mp :agent-read true))
              after (on-gtk (messages-pane/row-texts mp))]
          (on-gtk (support/check :messages-reads-hidden
                                 (and (empty? (:rows before)) (nil? (:unread before))
                                      (str/starts-with? (:footer before) "No messages yet.")
                                      (= 1 (count after))
                                      (str/includes? (first after) "read  Selftest read the tabs"))
                                 {:before before :after after})))
        (finally (on-gtk (messages-pane/set-kind! mp :agent-read false)) (close-panel! ui))))))

(defn row-messages-status-collapses
  "Three identical status lines from set-status! (a panel key with nothing
  to show) are one Status row ending ×3."
  [ui]
  (go-row :messages-status-collapses support/check
    (let [mp (:messages-pane ui)
          recent ((:recent-panels* ui)) saved @recent]
      (on-gtk (fresh! ui))
      (try
        (on-gtk (panel-key! ui \7)
                (messages-pane/set-kind! mp :status true)
                (reset! recent [:messages])
                (dotimes [_ 3] (panel-key! ui \1)))
        (let [want "; Nothing inspected yet: inspect a form or a result first. ×3"
              merged? (await-until 3000 20 #(some (fn [r] (str/ends-with? r want)) (messages-pane/row-texts mp)))
              rows (on-gtk (messages-pane/row-texts mp))]
          (on-gtk (support/check :messages-status-collapses
                                 (and merged? (= 1 (count (filter #(str/includes? % "Nothing inspected yet") rows))))
                                 {:merged merged? :rows rows})))
        (finally (reset! recent saved)
                 (on-gtk (messages-pane/set-kind! mp :status false))
                 (close-panel! ui))))))

(defn row-messages-closed-tab-target
  "A row whose tab has since closed says so when activated, and the active
  tab stays as it was."
  [ui]
  (go-row :messages-closed-tab-target support/check
    (let [mp (:messages-pane ui)
          id (on-gtk (fresh! ui) (scratch-tab! ui))]
      (try
        (call ui "jonquil_replace_text" {"text" "(def gone 1)" "id" id})
        (let [before (on-gtk (drop-tab! ui id) (:id ((:active-tab* ui))))
              _ (on-gtk (panel-key! ui \7))
              _ (await-until 3000 20 #(seq (messages-pane/row-texts mp)))
              state (on-gtk (messages-pane/activate! mp 0)
                            {:footer (g/label-text (:footer mp)) :active (:id ((:active-tab* ui)))
                             :row (first (messages-pane/row-texts mp))})]
          (on-gtk (support/check :messages-closed-tab-target
                                 (and (= "That tab is closed." (:footer state)) (= before (:active state))
                                      (str/includes? (:row state) "replaced the text of a closed tab"))
                                 (assoc state :before before))))
        (finally (close-panel! ui))))))

(defn row-messages-burst
  "200 entries logged from 200 threads at once all arrive, in id order,
  and the panel shows the newest on top."
  [ui]
  (go-row :messages-burst support/check
    (let [mp (:messages-pane ui)]
      (on-gtk (fresh! ui))
      (try
        (run! deref (doall (for [i (range 200)] (future ((:log!* ui) {:kind :agent :summary (str "burst " i)})))))
        (let [counted? (await-until 3000 20 #(= "● 200 messages" (unread ui)))
              _ (on-gtk (panel-key! ui \7))
              _ (await-until 3000 20 #(= 200 (count (messages-pane/row-texts mp))))
              state (on-gtk {:ids (map :id (:entries @(:messages* ui)))
                             :top (:id (first @(:entries mp)))
                             :rows (count (messages-pane/row-texts mp))})]
          (on-gtk (support/check :messages-burst
                                 (and counted? (= (range 1 201) (:ids state)) (= 200 (:top state)) (= 200 (:rows state)))
                                 (assoc (dissoc state :ids) :counted counted?))))
        (finally (close-panel! ui))))))

(defn- disk-row!
  "Runs body-fn with a fresh x.clj open in a tab, the poll having seen it
  once; body-fn gets {:f :id :tick! :state} and returns the check value.
  The tab and panel go afterwards."
  [ui row-key initial body-fn]
  (let [f (java.io.File. ^String (temp-dirs/temp-dir! "jonquil-selftest-msg-disk-") "x.clj")
        _ (spit f initial)
        id (on-gtk (fresh! ui) ((:open-file! ui) (.getPath f)) (:id ((:active-tab* ui))))
        tick! (fn [] (on-gtk ((:disk-tick!* ui))))
        ;; a change is followed once its stat has held for a tick
        settle! (fn [] (tick!) (tick!))
        ;; the same text again keeps the length, so move the time on to make
        ;; the poll really read it
        respit! (fn [s] (let [m (.lastModified f)] (spit f s) (.setLastModified f (+ m 5000))))
        state (fn [] (on-gtk (let [ed (:ed (some #(when (= id (:id %)) %) ((:tabs* ui))))]
                               {:files (mapv :summary (filter #(= :file (:kind %)) (:entries @(:messages* ui))))
                                :targets (mapv :target (filter #(= :file (:kind %)) (:entries @(:messages* ui))))
                                ;; the log folds a repeat of its newest row into ×N
                                :repeats (mapv #(or (:repeat %) 1) (filter #(= :file (:kind %)) (:entries @(:messages* ui))))
                                :text (editor/editor-text ed)
                                :dirty? (editor/dirty? ed)})))]
    (try
      ;; the poll's tick, run here so the row needn't wait on its timer
      (tick!)
      (let [[ok? data] (body-fn {:f f :id id :tick! tick! :settle! settle! :respit! respit! :state state :ed (on-gtk (:ed ((:active-tab* ui))))})]
        (on-gtk (support/check row-key ok? data)))
      (finally (on-gtk (drop-tab! ui id)) (close-panel! ui)))))

(defn row-messages-disk-change
  "An open, clean file rewritten on disk by someone else (an agent's own
  edit tool) follows the file, stays clean and logs one Files row that
  counts unread; the same text written again logs nothing more."
  [ui]
  (go-row :messages-disk-change support/check
    (disk-row! ui :messages-disk-change "(def x 1)\n"
               (fn [{:keys [f id settle! respit! state]}]
                 (let [new-text "(def x 2)\n;; written by an agent\n"
                       _ (spit f new-text)
                       _ (settle!)
                       logged? (await-until 3000 20 #(= "● 1 message" (unread ui)))
                       _ (respit! new-text)
                       _ (settle!)
                       st (state)]
                   [(and logged? (= ["x.clj was reloaded from disk"] (:files st)) (= [1] (:repeats st)) (= [{:tab-id id}] (:targets st))
                         (= new-text (:text st)) (not (:dirty? st)))
                    (assoc st :logged logged?)])))))

(defn row-messages-disk-change-dirty
  "A dirty tab whose file is rewritten keeps its edits and stays dirty,
  logging one row; the same text written again logs nothing more."
  [ui]
  (go-row :messages-disk-change-dirty support/check
    (disk-row! ui :messages-disk-change-dirty "(def x 1)\n"
               (fn [{:keys [f settle! respit! state ed]}]
                 (on-gtk (g/with-iters* (:buf ed) 0 0 (fn [a _] (g/gtk-text-buffer-insert (:buf ed) a ";; mine\n" -1))))
                 (spit f "(def x 2)\n")
                 (settle!)
                 (respit! "(def x 2)\n")
                 (settle!)
                 (let [st (state)]
                   [(and (= ";; mine\n(def x 1)\n" (:text st)) (:dirty? st)
                         (= ["x.clj changed on disk, and this tab keeps your unsaved edits"] (:files st))
                         (= [1] (:repeats st)))
                    st])))))

(defn row-messages-disk-change-nul
  "Disk text with a NUL byte never reaches the tab: its text stays and one
  row says why."
  [ui]
  (go-row :messages-disk-change-nul support/check
    (disk-row! ui :messages-disk-change-nul "(def x 1)\n"
               (fn [{:keys [f settle! respit! state]}]
                 (spit f "(def x\u0000 2)\n")
                 (settle!)
        ;; the same NUL text again is said once
                 (respit! "(def x\u0000 2)\n")
                 (settle!)
                 (let [st (state)]
                   [(and (= "(def x 1)\n" (:text st)) (not (:dirty? st))
                         (= ["x.clj changed on disk but holds a NUL byte, so this tab keeps its text"] (:files st))
                         (= [1] (:repeats st)))
                    st])))))

(defn row-messages-disk-deleted-then-back
  "A deleted file is said once and the tab kept; written back with new
  text, the tab follows it."
  [ui]
  (go-row :messages-disk-deleted-then-back support/check
    (disk-row! ui :messages-disk-deleted-then-back "(def x 1)\n"
               (fn [{:keys [^java.io.File f settle! state]}]
                 (.delete f)
                 (settle!) (settle!)
                 (spit f "(def x 3)\n")
                 (settle!)
                 (let [st (state)]
          ;; :entries is in log order, oldest first
                   [(and (= ["x.clj was deleted or moved on disk" "x.clj was reloaded from disk"] (:files st))
                         (= "(def x 3)\n" (:text st)) (not (:dirty? st)))
                    st])))))

(defn row-messages-disk-waits-a-tick
  "A change is followed only once the file's stat has held for one tick:
  a writer that truncates and then writes is never caught halfway, so the
  first tick after a write leaves the tab alone."
  [ui]
  (go-row :messages-disk-waits-a-tick support/check
    (disk-row! ui :messages-disk-waits-a-tick "(def x 1)\n"
               (fn [{:keys [f tick! state]}]
                 (spit f "")
                 (tick!)
                 (let [mid (state)]
                   (spit f "(def x 2)\n")
                   (tick!)
                   (let [still (state)]
                     (tick!)
                     (let [st (state)]
                       [(and (= "(def x 1)\n" (:text mid)) (= [] (:files mid))
                             (= "(def x 1)\n" (:text still))
                             (= "(def x 2)\n" (:text st)) (= ["x.clj was reloaded from disk"] (:files st)))
                        {:mid mid :still still :st st}])))))))

(defn row-messages-disk-follow-failure-said
  "A throw while following a change is one error row naming it, never a
  silent retry."
  [ui]
  (go-row :messages-disk-follow-failure-said support/check
    (disk-row! ui :messages-disk-follow-failure-said "(def x 1)\n"
               (fn [{:keys [f settle! state ed]}]
        ;; reload-text! bumps the paint gen first: a paint that isn't a map throws there
                 (let [paint @(:paint ed)]
                   (try
                     (on-gtk (reset! (:paint ed) :broken))
                     (spit f "(def x 2)\n")
                     (settle!)
                     (settle!)
                     (finally (on-gtk (reset! (:paint ed) paint)))))
                 (let [st (state)
                       errs (on-gtk (mapv :error? (filter #(= :file (:kind %)) (:entries @(:messages* ui)))))]
                   [(and (= 1 (count (:files st)))
                         (str/starts-with? (first (:files st)) "x.clj changed on disk, but following it failed: ")
                         (= [true] errs))
                    (assoc st :errs errs)])))))

(defn- selected-entry-id [mp]
  (let [row (g/gtk-list-box-get-selected-row (:list mp))]
    (when-not (ffi/null? row)
      (:id (get @(:entries mp) (g/gtk-list-box-row-get-index row))))))

(defn row-messages-selection-stays
  "With the panel open and the second row selected, a hidden status line
  and a new agent entry arriving keep that row selected."
  [ui]
  (go-row :messages-selection-stays support/check
    (let [mp (:messages-pane ui)]
      (on-gtk (fresh! ui))
      (try
        (doseq [s ["one" "two" "three"]] ((:log!* ui) {:kind :agent :summary s}))
        (on-gtk (panel-key! ui \7))
        (await-until 3000 20 #(= 3 (count (messages-pane/row-texts mp))))
        (let [picked (on-gtk (g/gtk-list-box-select-row (:list mp) (g/gtk-list-box-get-row-at-index (:list mp) 1))
                             (selected-entry-id mp))
              _ ((:log!* ui) {:kind :status :summary "Ready"})
              _ ((:log!* ui) {:kind :agent :summary "four"})
              arrived? (await-until 3000 20 #(= 4 (count (messages-pane/row-texts mp))))
              after (on-gtk (selected-entry-id mp))]
          (on-gtk (support/check :messages-selection-stays
                                 (and arrived? (= 2 picked) (= picked after))
                                 {:picked picked :after after :arrived arrived?})))
        (finally (close-panel! ui))))))

(defn row-messages-unread-click-opens
  "Clicking the status bar's unread count opens Messages and marks it read."
  [ui]
  (go-row :messages-unread-click-opens support/check
    (let [mp (:messages-pane ui)]
      (on-gtk (fresh! ui))
      (try
        ((:log!* ui) {:kind :agent :summary "unread click"})
        (let [counted? (await-until 3000 20 #(= "● 1 message" (unread ui)))
              _ (on-gtk ((:unread-click!* ui)))
              shown (await-until 3000 20 #(shown? mp))
              panel (on-gtk (when (pos? (g/gtk-widget-get-visible (:right-slot ui))) :messages))
              after (on-gtk (unread ui))]
          (on-gtk (support/check :messages-unread-click-opens
                                 (and counted? shown (= :messages panel) (nil? after))
                                 {:counted counted? :shown shown :panel panel :after after})))
        (finally (close-panel! ui))))))
