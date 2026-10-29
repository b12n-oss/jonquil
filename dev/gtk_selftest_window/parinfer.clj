(ns gtk-selftest-window.parinfer
  "The per-project Parinfer override: a project's .jonquil.edn :parinfer
  decides its tabs' mode either way, the label says (project), and the
  global commands leave a pinned tab alone. The edits go through one
  begin/end-user-action, the same shape GTK's own commit makes. Each row
  closes its tab and deletes its temp project in a finally, and marks the
  tab clean first (close-clean!), so a failed check doesn't leave the
  unsaved-changes alert blocking the rows after it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [gtk-async :as gtk-async :refer [go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]))

(defn- temp-project!
  "A project holding deps.edn, .jonquil.edn and an empty src/demo/p.clj
  (the dialects rows' temp-project!, trimmed). [canonical-file-path
  canonical-dir-path]."
  [config]
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str "jonquil-selftest-parinfer-" (System/currentTimeMillis) "-" (random-uuid)))
        f (io/file d "src/demo/p.clj")]
    (.mkdirs (.getParentFile f))
    (spit (io/file d "deps.edn") "{:paths [\"src\"]}")
    (spit (io/file d ".jonquil.edn") config)
    (spit f "")
    [(.getCanonicalPath f) (.getCanonicalPath d)]))

(defn- delete-tree!
  "Deletes dir and everything under it, deepest first; dir is this row's
  own unique temp project."
  [dir]
  (let [f (io/file dir)]
    (when (.exists f)
      (doseq [child (reverse (file-seq f))]
        (.delete child)))))

(defn- type-str!
  "One user action inserting s at ed's caret, as GTK's commit does."
  [ed s]
  (let [buf (:buf ed)]
    (g/gtk-text-buffer-begin-user-action buf)
    (g/gtk-text-buffer-insert-interactive-at-cursor buf s -1 1)
    (g/gtk-text-buffer-end-user-action buf)))

(defn- close-clean!
  "Marks t's editor clean (set-text! records the text as saved) and closes
  it, so a row's typed edit never leaves the unsaved-changes alert open
  for the rows after it."
  [ui t]
  (editor/set-text! (:ed t) "")
  ((:close-tab! ui) (:id t)))

(defn- label-state [ui]
  {:visible (pos? (g/gtk-widget-get-visible (:parinfer-label ui)))
   :text (g/label-text (:parinfer-label ui))})

(defn row-parinfer-project-on
  "Review Focus 1 and 4: a project saying {:parinfer :smart} turns the
  mode on for its tab while the global stays off, from the first edit."
  [ui]
  (go-row :parinfer-project-on support/check
    (let [[file dir] (temp-project! "{:parinfer :smart}")]
      (on-gtk (let [_ ((:open-file! ui) file)
                    t ((:active-tab* ui))]
                (try
                  (let [ed (:ed t)
                        _ (type-str! ed "(")
                        text (editor/editor-text ed)
                        label (label-state ui)]
                    (support/check :parinfer-project-on
                                   (and (= "()" text)
                                        (:visible label)
                                        (= "Parinfer: Smart (project)" (:text label)))
                                   {:text text :label label}))
                  (finally
                    (close-clean! ui t)
                    (delete-tree! dir))))))))

(defn row-parinfer-project-pins-off
  "Review Focus 1: {:parinfer :off} pins the tab off under a global
  :smart, the label says so, and a global cycle names the pin."
  [ui]
  (go-row :parinfer-project-pins-off support/check
    (let [[file dir] (temp-project! "{:parinfer :off}")
          state (on-gtk (let [_ ((:open-file! ui) file)
                              t ((:active-tab* ui))
                              ed (:ed t)]
                          (try ((:parinfer!* ui) :smart)
                               (type-str! ed "(")
                               (catch :default _ nil))
                          {:t t
                           :detail {:tab-id (:id t)
                                    :text (editor/editor-text ed)
                                    :label (label-state ui)}}))
          ;; An async Ready from an earlier row can land over the status,
          ;; so poll with an idempotent re-set: choosing :indent again
          ;; rewrites the same mode and the same status line.
          status-ok? (gtk-async/await-until 3000 100
                                            #(let [st (g/label-text (:status-label ui))]
                                               (or (str/includes? st "this project says off")
                                                   (do ((:parinfer!* ui) :indent) false))))]
      (on-gtk (try
                (support/check :parinfer-project-pins-off
                               (let [{:keys [text label]} (:detail state)]
                                 (and (= "(" text)
                                      (:visible label)
                                      (= "Parinfer: off (project)" (:text label))
                                      status-ok?))
                               (assoc (:detail state)
                                      :status-ok status-ok?
                                      :status (g/label-text (:status-label ui))))
                (finally
                  (try ((:parinfer!* ui) :off) (catch :default _ nil))
                  (close-clean! ui (:t state))
                  (delete-tree! dir)))))))

(defn row-parinfer-label-follows-tab
  "Review Focus 2: switching between a project tab and a plain tab flips
  the label between Smart (project) and hidden (the global is off)."
  [ui]
  (go-row :parinfer-label-follows-tab support/check
    (let [[file dir] (temp-project! "{:parinfer :smart}")]
      (on-gtk (let [_ ((:open-file! ui) file)
                    project-tab ((:active-tab* ui))
                    plain-tab* (atom nil)]
                (try
                  (let [on-project (label-state ui)
                        _ ((:new-tab!* ui))
                        _ (reset! plain-tab* ((:active-tab* ui)))
                        on-plain (label-state ui)
                        _ ((:select-tab! ui) (:id project-tab))
                        back (label-state ui)]
                    (support/check :parinfer-label-follows-tab
                                   (and (:visible on-project)
                                        (= "Parinfer: Smart (project)" (:text on-project))
                                        (not (:visible on-plain))
                                        (:visible back)
                                        (= "Parinfer: Smart (project)" (:text back)))
                                   {:on-project on-project :on-plain on-plain :back back}))
                  (finally
                    (when-let [pt @plain-tab*] (close-clean! ui pt))
                    (close-clean! ui project-tab)
                    (delete-tree! dir))))))))

(defn row-parinfer-label-after-open-into-untitled
  "Review I1: Open on a project file fills an untouched Untitled tab in
  place (no tab switch), and the label must still pick up the project's
  override."
  [ui]
  (go-row :parinfer-label-after-open-into-untitled support/check
    (let [[file dir] (temp-project! "{:parinfer :smart}")]
      (on-gtk (let [_ ((:new-tab!* ui))
                    t ((:active-tab* ui))]
                (try
                  ((:open-file! ui) file)
                  (let [same-tab? (= (:id t) (:id ((:active-tab* ui))))
                        label (label-state ui)]
                    (support/check :parinfer-label-after-open-into-untitled
                                   (and same-tab?
                                        (:visible label)
                                        (= "Parinfer: Smart (project)" (:text label)))
                                   {:same-tab same-tab? :label label
                                    :path @(:path t)}))
                  (finally
                    (close-clean! ui t)
                    (delete-tree! dir))))))))
