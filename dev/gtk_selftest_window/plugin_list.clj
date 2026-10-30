(ns gtk-selftest-window.plugin-list
  "Rows for the plugin list opened by Plugins… over files written
  into the selftest's own plugins folder (beside its temp settings file,
  never the user's), with Enable, Disable, Open and Review Proposals driven
  through the ui functions the buttons call. Each row deletes its files
  and histories, puts the settings file back, restores the live registry,
  closes the list and any tab it opened, and reloads."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.review :as review]
            [gtk-selftest-window.support :as support]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.plugin-list-pane :as plugin-list-pane]
            [jonquil.plugin-history :as history]
            [jonquil.plugin-list :as plugin-list]
            [jonquil.plugin-registry :as plugin-registry]
            [jonquil.settings :as settings]))

(defn- delete-tree! [f]
  (doseq [x (reverse (file-seq (io/file f)))] (io/delete-file x true)))

(defn- close-extra-tabs!
  "Marks every tab but the first saved and closes it, then selects the
  first (as the tabs rows do). GTK-thread only."
  [ui]
  (doseq [t (rest ((:tabs* ui)))]
    (editor/mark-saved! (:ed t))
    ((:close-tab! ui) (:id t)))
  ((:select-tab! ui) (:id (first ((:tabs* ui))))))

(defn with-list
  "Writes settings-text into the settings file (when given) and files
  ({name text}) into the plugins folder, loads them, runs (f), then puts
  everything back: the files, each id's history, the settings file's old
  text, the live registry, the list's visibility and the tabs."
  [ui {:keys [files ids settings-text]} f]
  (let [sp ((:settings-path* ui))
        dir (io/file ((:plugins-dir* ui)))
        old-settings (when (.isFile (io/file sp)) (slurp sp))
        before @plugin-registry/live]
    (.mkdirs dir)
    (try
      (when settings-text (spit sp settings-text))
      (doseq [[n text] files] (spit (io/file dir n) text))
      ((:load-plugins!* ui))
      (f)
      (finally
        (doseq [[n _] files] (io/delete-file (io/file dir n) true))
        (doseq [id ids] (delete-tree! (history/history-dir (str dir) id)))
        (if old-settings (spit sp old-settings) (io/delete-file (io/file sp) true))
        (reset! plugin-registry/live before)
        (on-gtk (when-let [c (:on-close @(:handlers (:plugin-list-pane ui)))] (c))
                (close-extra-tabs! ui))
        ((:reload-plugins!* ui))))))

(defn- shown [ui] ((:plugin-list-shown* ui)))

(defn- expected-rows
  "The lines jonquil.plugin-list builds now, for comparing with the panel."
  [ui]
  (mapv :line (plugin-list/rows {:plugins-dir ((:plugins-dir* ui)) :registry @plugin-registry/live
                                 :disabled (:disabled (:plugins (settings/load-settings ((:settings-path* ui)))))})))

(defn- has-binding? [id]
  (some #(= (keyword id "wrap") (:command %)) (plugin-registry/editor-bindings @plugin-registry/live)))

(defn- disabled-ids [ui] (get-in (settings/load-settings ((:settings-path* ui))) [:plugins :disabled]))

(defn row-plugin-list-shows-each-plugin
  "Two loaded plugins and one disabled in settings: the list shows the
  three jonquil.plugin-list rows in id order, the first selected."
  [ui]
  (go-row :plugin-list-shows-each-plugin support/check
    (with-list ui {:files [["pl-a.clj" (review/source "pl-a")] ["pl-b.clj" (review/source "pl-b" "Another." "#{}")]
                           ["pl-c.clj" (review/source "pl-c")]]
                   :ids ["pl-a" "pl-b" "pl-c"]
                   :settings-text "{:plugins {:disabled #{:pl-c}}}"}
      (fn []
        ((:plugins-list!* ui))
        (let [{:keys [visible rows selected] :as s} (shown ui)
              ours (filterv #(re-find #"^pl-[abc]: " %) rows)]
          (support/check :plugin-list-shows-each-plugin
                         (and visible (= rows (expected-rows ui)) (= 3 (count ours))
                              (str/includes? (nth ours 2) "disabled")
                              (= selected (first (str/split (first rows) #":"))))
                         s))))))

(defn row-plugin-list-disable-takes-the-key
  "Disable on a plugin bound to Cmd/Ctrl+Alt+J: its binding goes, its row
  says disabled, the settings file holds it, and the console says so."
  [ui]
  (go-row :plugin-list-disable-takes-the-key support/check
    (with-list ui {:files [["pl-d.clj" (review/source "pl-d")]] :ids ["pl-d"]}
      (fn []
        ((:plugins-list!* ui))
        (let [was (has-binding? "pl-d")
              lines ((:plugin-disable!* ui) "pl-d")
              row (first (filter #(str/starts-with? % "pl-d: ") (:rows (shown ui))))]
          (support/check :plugin-list-disable-takes-the-key
                         (and was (not (has-binding? "pl-d")) (str/includes? (str row) "disabled")
                              (contains? (disabled-ids ui) :pl-d)
                              (= "plugin pl-d disabled." (first lines))
                              (= "plugin pl-d disabled." (:footer (shown ui)))
                              (str/includes? (support/console-text ui) "; plugin pl-d disabled."))
                         {:was was :lines lines :row row}))))))

(defn row-plugin-list-enable-brings-it-back
  "Enable on a plugin disabled in settings: it loads and takes its key."
  [ui]
  (go-row :plugin-list-enable-brings-it-back support/check
    (with-list ui {:files [["pl-e.clj" (review/source "pl-e")]] :ids ["pl-e"]
                   :settings-text "{:plugins {:disabled #{:pl-e}}}"}
      (fn []
        ((:plugins-list!* ui))
        (let [was (has-binding? "pl-e")
              lines ((:plugin-enable!* ui) "pl-e")]
          (support/check :plugin-list-enable-brings-it-back
                         (and (not was) (has-binding? "pl-e") (not (contains? (disabled-ids ui) :pl-e))
                              (= "plugin pl-e enabled." (first lines)))
                         {:was was :lines lines}))))))

(defn row-plugin-list-broken-plugin-can-be-disabled
  "A file that doesn't compile is listed as not loaded, and Disable puts
  it in the settings file (by design, a broken plugin can always be turned
  off from the list)."
  [ui]
  (go-row :plugin-list-broken-plugin-can-be-disabled support/check
    (with-list ui {:files [["pl-f.clj" "(ns jonquil.plugins.pl-f) (this does not compile"]] :ids ["pl-f"]}
      (fn []
        ((:plugins-list!* ui))
        (let [row (first (filter #(str/starts-with? % "pl-f: ") (:rows (shown ui))))
              lines ((:plugin-disable!* ui) "pl-f")]
          (support/check :plugin-list-broken-plugin-can-be-disabled
                         (and (str/includes? (str row) "not loaded")
                              (= "plugin pl-f disabled." (first lines))
                              (contains? (disabled-ids ui) :pl-f))
                         {:row row :lines lines}))))))

(defn row-plugin-list-keeps-trusted-projects
  "Disable keeps the rest of :plugins and the other settings."
  [ui]
  (go-row :plugin-list-keeps-trusted-projects support/check
    (with-list ui {:files [["pl-g.clj" (review/source "pl-g")]] :ids ["pl-g"]
                   :settings-text "{:theme :dark :plugins {:trusted-projects {\"/x\" 1}}}"}
      (fn []
        ((:plugins-list!* ui))
        ((:plugin-disable!* ui) "pl-g")
        (let [m (read-string (slurp ((:settings-path* ui))))]
          (support/check :plugin-list-keeps-trusted-projects
                         (= {:theme :dark :plugins {:trusted-projects {"/x" 1} :disabled #{:pl-g}}} m)
                         m))))))

(defn row-plugin-list-open-opens-its-file
  "Open shows the plugin's file in the active tab; a plugin with only a
  history says it has no file and opens nothing."
  [ui]
  (go-row :plugin-list-open-opens-its-file support/check
    (with-list ui {:files [["pl-h.clj" (review/source "pl-h")]] :ids ["pl-h" "pl-i"]}
      (fn []
        (history/record-proposal! {:plugins-dir ((:plugins-dir* ui)) :id "pl-i" :text (review/source "pl-i")
                                   :reason "an agent's pl-i" :status :pending :check {:unchecked? true}})
        ((:plugins-list!* ui))
        (let [footer ((:plugin-open!* ui) "pl-h")
              path (on-gtk @(:path ((:active-tab* ui))))
              n-tabs (on-gtk (count ((:tabs* ui))))
              footer2 ((:plugin-open!* ui) "pl-i")
              n-tabs2 (on-gtk (count ((:tabs* ui))))]
          (support/check :plugin-list-open-opens-its-file
                         (and (= "" footer)
                              (= (.getCanonicalPath (io/file ((:plugins-dir* ui)) "pl-h.clj"))
                                 (some-> path io/file .getCanonicalPath))
                              (str/starts-with? footer2 "plugin pl-i has no file in ")
                              (= n-tabs n-tabs2))
                         {:footer footer :path path :footer2 footer2}))))))

(defn row-plugin-list-review-opens-the-review
  "With a pending proposal, Review Proposals shows the review panel in the
  slot and hides the list."
  [ui]
  (go-row :plugin-list-review-opens-the-review support/check
    (with-list ui {:ids ["pl-j"]}
      (fn []
        (history/record-proposal! {:plugins-dir ((:plugins-dir* ui)) :id "pl-j" :text (review/source "pl-j")
                                   :reason "an agent's pl-j" :status :pending :check {:unchecked? true}})
        ((:plugins-list!* ui))
        (on-gtk ((:on-review @(:handlers (:plugin-list-pane ui)))))
        (let [ok (await-until 5000 50 #(pos? (g/gtk-widget-get-visible (:root (:review-pane ui)))))
              p (review/panel ui)]
          (support/check :plugin-list-review-opens-the-review
                         (and ok (:visible p) (not (:visible (shown ui)))
                              (str/includes? (:summary p) "pl-j v1, proposed by an agent"))
                         p)
          (on-gtk ((:on-close @(:handlers (:review-pane ui))))))))))

(defn row-plugin-list-empty
  "No plugins: one line saying where plugin files go, and Enable says
  nothing is selected. Earlier rows leave histories in the selftest's
  plugins folder, so this row points the window at a fresh settings file
  of its own, and back after."
  [ui]
  (go-row :plugin-list-empty support/check
    (let [old ((:settings-path* ui))]
      ((:settings-path!* ui) (support/fresh-settings-path))
      (try
        ((:plugins-list!* ui))
        (let [before (shown ui)
              _ (on-gtk ((:on-enable @(:handlers (:plugin-list-pane ui)))))
              after (shown ui)]
          (support/check :plugin-list-empty
                         (and (:visible before) (= [] (:rows before))
                              (str/starts-with? (str (:empty before)) "No plugins yet. A plugin is a file in ")
                              (= "No plugin is selected." (:footer after)))
                         {:before before :after after}))
        (finally
          ((:settings-path!* ui) old)
          (on-gtk ((:on-close @(:handlers (:plugin-list-pane ui))))))))))

(defn row-plugin-list-refreshes-after-a-step
  "With the list showing, Reload Plugins after a new file adds its row and
  keeps the selected one."
  [ui]
  (go-row :plugin-list-refreshes-after-a-step support/check
    (with-list ui {:files [["pl-k.clj" (review/source "pl-k")] ["pl-z.clj" (review/source "pl-z")]]
                   :ids ["pl-k" "pl-m" "pl-z"]}
      (fn []
        ((:plugins-list!* ui))
        (on-gtk (plugin-list-pane/select-id! (:plugin-list-pane ui) "pl-z"))
        (spit (io/file ((:plugins-dir* ui)) "pl-m.clj") (review/source "pl-m"))
        (try
          ((:reload-plugins!* ui))
          (let [{:keys [rows selected] :as s} (shown ui)]
            (support/check :plugin-list-refreshes-after-a-step
                           (and (some #(str/starts-with? % "pl-m: live v1, loaded") rows) (= "pl-z" selected))
                           s))
          (finally (io/delete-file (io/file ((:plugins-dir* ui)) "pl-m.clj") true)))))))
