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

(defn row-messages-disk-change
  "An open file rewritten on disk by someone else (an agent's own edit
  tool) logs one Files row and counts unread; the tab's text stays as it
  was, and the same text written again logs nothing more."
  [ui]
  (go-row :messages-disk-change support/check
    (let [f (java.io.File. ^String (temp-dirs/temp-dir! "jonquil-selftest-msg-disk-") "x.clj")
          path (.getPath f)
          _ (spit f "(def x 1)\n")
          id (on-gtk (fresh! ui) ((:open-file! ui) path) (:id ((:active-tab* ui))))]
      (try
        ;; the poll's tick, run here so the row needn't wait on its timer:
        ;; once to see the file, once after each write
        (on-gtk ((:disk-tick!* ui)))
        (spit f "(def x 2)\n;; written by an agent\n")
        (on-gtk ((:disk-tick!* ui)))
        (let [logged? (await-until 3000 20 #(= "● 1 message" (unread ui)))
              _ (spit f "(def x 2)\n;; written by an agent\n")
              _ (on-gtk ((:disk-tick!* ui)) ((:disk-tick!* ui)))
              state (on-gtk {:entries (mapv #(select-keys % [:kind :summary :target]) (:entries @(:messages* ui)))
                             :text (editor/editor-text (:ed (some #(when (= id (:id %)) %) ((:tabs* ui)))))})]
          (on-gtk (support/check :messages-disk-change
                                 (and logged?
                                      (= [{:kind :file :summary "x.clj changed on disk" :target {:tab-id id}}]
                                         (filterv #(= :file (:kind %)) (:entries state)))
                                      (= "(def x 1)\n" (:text state)))
                                 (assoc state :logged logged?))))
        (finally (on-gtk (drop-tab! ui id)) (close-panel! ui))))))
