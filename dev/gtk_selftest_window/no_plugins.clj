(ns gtk-selftest-window.no-plugins
  "Plugins F1's rows: in a window running without plugins (as --no-plugins
  starts one, set here through :plugins-on!*), Reload Plugins, Disable,
  Revert and Accept refuse with one line and change nothing; and a
  plugin whose file is gone after it went live says \"file deleted\" in
  the list. Each row turns plugins back on before its cleanup runs."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [gtk-async :refer [go-row]]
            [gtk-selftest-window.plugin-list :as plugin-list-rows]
            [gtk-selftest-window.review :as review]
            [gtk-selftest-window.support :as support]
            [jonquil.plugin-history :as history]
            [jonquil.plugin-registry :as plugin-registry]
            [jonquil.settings :as settings]))

(def ^:private off "plugins are off in this window (--no-plugins), so ")

(defn- without-plugins
  "Runs (f) with the window's plugins off, turning them back on after."
  [ui f]
  ((:plugins-on!* ui) false)
  (try (f) (finally ((:plugins-on!* ui) true))))

(defn- files [& ids] {:files (mapv (fn [id] [(str id ".clj") (review/source id)]) ids) :ids (vec ids)})

(defn- live? [id] (contains? (:plugins @plugin-registry/live) (keyword id)))

(defn- file [ui id] (io/file ((:plugins-dir* ui)) (str id ".clj")))

(defn row-no-plugins-reload-refuses
  "A new plugin file and Reload Plugins: refused, nothing loads."
  [ui]
  (go-row :no-plugins-reload-refuses support/check
    (plugin-list-rows/with-list ui (assoc (files "np-a") :ids ["np-a" "np-b"])
      (fn []
        (without-plugins ui
          (fn []
            (spit (file ui "np-b") (review/source "np-b"))
            (try
              (let [lines ((:reload-plugins!* ui))]
                (support/check :no-plugins-reload-refuses
                               (and (= [(str off "nothing was reloaded")] lines) (not (live? "np-b")) (live? "np-a")
                                    (str/includes? (support/console-text ui) (str "; " off "nothing was reloaded")))
                               {:lines lines}))
              (finally (io/delete-file (file ui "np-b") true)))))))))

(defn row-no-plugins-disable-refuses
  "Disable in the list: refused, the settings file and the key unchanged."
  [ui]
  (go-row :no-plugins-disable-refuses support/check
    (plugin-list-rows/with-list ui (files "np-c")
      (fn []
        ((:plugins-list!* ui))
        (without-plugins ui
          (fn []
            (let [sp ((:settings-path* ui))
                  before (when (.isFile (io/file sp)) (slurp sp))
                  lines ((:plugin-disable!* ui) "np-c")
                  after (when (.isFile (io/file sp)) (slurp sp))]
              (support/check :no-plugins-disable-refuses
                             (and (= [(str off "plugin np-c wasn't disabled")] lines) (= before after) (live? "np-c")
                                  (not (contains? (get-in (settings/load-settings sp) [:plugins :disabled]) :np-c)))
                             {:lines lines}))))))))

(defn row-no-plugins-revert-refuses
  "Revert to v1 from the history: refused, the file still v2's."
  [ui]
  (go-row :no-plugins-revert-refuses support/check
    (plugin-list-rows/with-list ui (files "np-d")
      (fn []
        (let [v2 (str/replace (review/source "np-d") "{:key \"j\"" "{:key \"k\"")]
          (spit (file ui "np-d") v2)
          ((:reload-plugins!* ui))
          ((:plugins-list!* ui))
          ((:plugin-history!* ui) "np-d")
          (try
            (without-plugins ui
              (fn []
                (let [lines ((:plugin-revert!* ui) 1 "")]
                  (support/check :no-plugins-revert-refuses
                                 (and (= [(str off "plugin np-d wasn't reverted")] lines) (= v2 (slurp (file ui "np-d")))
                                      (= 2 (count (:entries (history/read-entries (history/history-dir ((:plugins-dir* ui)) "np-d"))))))
                                 {:lines lines}))))
            (finally ((:plugin-history-close!* ui)))))))))

(defn row-no-plugins-accept-refuses
  "Accept on an agent's proposal: refused, the proposal stays pending."
  [ui]
  (go-row :no-plugins-accept-refuses support/check
    (review/with-proposals ui {:ids ["np-e"]}
      (fn []
        (history/record-proposal! {:plugins-dir ((:plugins-dir* ui)) :id "np-e" :text (review/source "np-e")
                                   :reason "an agent's np-e" :status :pending :check {:unchecked? true}})
        ((:review-proposals!* ui))
        (without-plugins ui
          (fn []
            (let [lines ((:review-accept!* ui))
                  status (history/version-status (:entries (history/read-entries (history/history-dir ((:plugins-dir* ui)) "np-e"))) 1)]
              (support/check :no-plugins-accept-refuses
                             (and (= (str off "plugin np-e: v1 wasn't accepted") (last lines)) (= :pending status)
                                  (not (live? "np-e")) (not (.exists (file ui "np-e"))))
                             {:lines lines :status status}))))))))

(defn row-plugin-list-shows-file-deleted
  "A loaded plugin whose file is deleted and reloaded away: its row says
  file deleted."
  [ui]
  (go-row :plugin-list-shows-file-deleted support/check
    (plugin-list-rows/with-list ui (files "np-f")
      (fn []
        (io/delete-file (file ui "np-f"))
        ((:reload-plugins!* ui))
        ((:plugins-list!* ui))
        (let [row (first (filter #(str/starts-with? % "np-f: ") (:rows ((:plugin-list-shown* ui)))))]
          (support/check :plugin-list-shows-file-deleted
                         (= "np-f: live v1, not loaded, file deleted, effects unknown, no pending proposal." row)
                         {:row row}))))))
