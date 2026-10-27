(ns gtk-selftest-window.review
  "Plugins P9b's rows: the review panel opened by Review Plugin Proposals
  over proposals recorded straight into the selftest's own plugins folder
  (beside its temp settings file, never the user's), the status bar's
  pending count, Next, a review against a live version that moved, and
  Accept and Reject driven through the handlers the panel's buttons call.
  Each row deletes its files and histories, restores the live registry and
  closes the panel."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [gtk-async :refer [go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.review-pane :as review-pane]
            [jonquil.plugin-history :as history]
            [jonquil.plugin-registry :as plugin-registry]))

(def ^:private KEY-J 0x06a)
(def ^:private ctrl-alt (bit-or g/CONTROL-MASK g/ALT-MASK))

(defn- source
  "A plugin file for id with one :edit command bound to Cmd/Ctrl+Alt+J, its
  doc being doc."
  ([id] (source id "Wrap the form at the caret in (when ...)."))
  ([id doc]
   (str "(ns jonquil.plugins." id "\n  (:require [jonquil.plugin :as p]))\n\n"
        "(p/defplugin\n"
        "  {:id :" id "\n"
        "   :doc \"" doc "\"\n"
        "   :effects #{}\n"
        "   :commands [{:id :" id "/wrap :doc \"Wrap it.\"\n"
        "               :scope :editor :edit (fn [s] (p/wrap-form s \"(when \" \")\"))}]\n"
        "   :bindings [{:key \"j\" :mods #{:cmd :alt} :command :" id "/wrap}]})\n")))

(defn- propose!
  "Records a pending proposal for id (unchecked unless :check is given)."
  [ui id text & {:as more}]
  (history/record-proposal! (merge {:plugins-dir ((:plugins-dir* ui)) :id id :text text
                                    :reason (str "an agent's " id) :status :pending
                                    :check {:unchecked? true}}
                                   more)))

(defn- delete-tree! [f]
  (doseq [x (reverse (file-seq (io/file f)))] (io/delete-file x true)))

(defn- close-panel! [ui]
  (on-gtk (when-let [f (:on-close @(:handlers (:review-pane ui)))] (f))))

(defn with-proposals
  "Writes files ({name text}) into the plugins folder and loads them when
  there are any, runs (f), then deletes the files, each id's <id>.clj (an
  Accept writes it) and the histories of ids,
  restores the live registry, closes the panel and reloads, so the pending
  count is back to what it was."
  [ui {:keys [files ids]} f]
  (let [dir (io/file ((:plugins-dir* ui)))
        before @plugin-registry/live]
    (.mkdirs dir)
    (try
      (doseq [[n text] files] (spit (io/file dir n) text))
      (when (seq files) ((:load-plugins!* ui)))
      (f)
      (finally
        (doseq [[n _] files] (io/delete-file (io/file dir n) true))
        (doseq [id ids]
          (io/delete-file (io/file dir (str id ".clj")) true)
          (delete-tree! (history/history-dir (str dir) id)))
        (reset! plugin-registry/live before)
        (close-panel! ui)
        ((:reload-plugins!* ui))))))

(defn- panel [ui]
  (let [pane (:review-pane ui)]
    (on-gtk {:visible (pos? (g/gtk-widget-get-visible (:root pane)))
             :summary (review-pane/summary-text pane)
             :diff (review-pane/diff-text pane)
             :footer (review-pane/footer-text pane)
             :count (g/label-text (:pending-label ui))
             :count-visible (pos? (g/gtk-widget-get-visible (:pending-label ui)))})))

(defn row-review-opens-pending
  "An unchecked proposal for a new plugin: Review Plugin Proposals shows it,
  its first line says it's unchecked, the diff is the whole file, and the
  status bar counts it."
  [ui]
  (go-row :review-opens-pending support/check
    (with-proposals ui {:ids ["p9b-new"]}
      (fn []
        (propose! ui "p9b-new" (source "p9b-new"))
        ((:review-proposals!* ui))
        (let [{:keys [visible summary diff count count-visible] :as p} (panel ui)]
          (support/check :review-opens-pending
                         (and visible
                              (str/starts-with? summary "Unchecked: ")
                              (str/includes? summary "p9b-new v1, proposed by an agent: an agent's p9b-new")
                              (str/starts-with? diff "+ (ns jonquil.plugins.p9b-new")
                              (= "1 plugin proposal" count) count-visible)
                         p))))))

(defn row-review-next-cycles
  "Two pending proposals: the summary says which of two this is, and Next
  shows the other."
  [ui]
  (go-row :review-next-cycles support/check
    (with-proposals ui {:ids ["p9b-a" "p9b-b"]}
      (fn []
        (propose! ui "p9b-a" (source "p9b-a"))
        (propose! ui "p9b-b" (source "p9b-b"))
        ((:review-proposals!* ui))
        (let [first-shown ((:review-shown* ui))
              {:keys [summary count]} (panel ui)
              _ (on-gtk ((:on-next @(:handlers (:review-pane ui)))))
              then ((:review-shown* ui))]
          (support/check :review-next-cycles
                         (and (str/ends-with? summary "Proposal 1 of 2.")
                              (= "p9b-a" (:id first-shown))
                              (= {:id "p9b-b" :index 1} (select-keys then [:id :index]))
                              (= "2 plugin proposals" count))
                         {:first first-shown :then then :summary summary :count count}))))))

(defn row-review-based-on-live-moved
  "A proposal based on v1 while the file was edited by hand to v3: the
  review says so and diffs against live, so the hand edit's line shows as
  removed."
  [ui]
  (go-row :review-based-on-live-moved support/check
    (with-proposals ui {:files {"p9b-live.clj" (source "p9b-live")} :ids ["p9b-live"]}
      (fn []
        (propose! ui "p9b-live" (source "p9b-live" "Proposed.") :based-on 1)
        (spit (io/file ((:plugins-dir* ui)) "p9b-live.clj") (source "p9b-live" "Edited live."))
        ((:reload-plugins!* ui))
        ((:review-proposals!* ui))
        (let [{:keys [summary diff] :as p} (panel ui)]
          (support/check :review-based-on-live-moved
                         (and (str/includes? summary "Based on v1, live is v3: the diff is against live.")
                              (some #(and (str/starts-with? % "- ") (str/includes? % "Edited live."))
                                    (str/split-lines diff)))
                         p))))))

(defn row-review-none-to-review
  "With nothing pending, Review Plugin Proposals says so and shows no
  panel and no count."
  [ui]
  (go-row :review-none-to-review support/check
    (with-proposals ui {}
      (fn []
        ((:review-proposals!* ui))
        (let [{:keys [visible count-visible] :as p} (panel ui)
              status (on-gtk (g/label-text (:status-label ui)))]
          (support/check :review-none-to-review
                         (and (not visible) (not count-visible) (= "No plugin proposals to review." status))
                         (assoc p :status status)))))))

(defn- entries [ui id]
  (:entries (history/read-entries (history/history-dir ((:plugins-dir* ui)) id))))

(defn row-review-accept-goes-live
  "Accept on an unchecked proposal for a new plugin: it's checked in
  process, written, recorded as accepted and live, so its key wraps; the
  panel and the count go away."
  [ui]
  (go-row :review-accept-goes-live support/check
    (with-proposals ui {:ids ["p9b-acc"]}
      (fn []
        (propose! ui "p9b-acc" (source "p9b-acc"))
        ((:review-proposals!* ui))
        (let [lines ((:review-accept!* ui))
              live? (some? (get-in @plugin-registry/live [:plugins :p9b-acc]))
              text (on-gtk (let [ed (:editor ui)]
                             (editor/set-text! ed "(a b)")
                             (support/place-cursor! (:buf ed) 1)
                             (editor/handle-key! ed KEY-J ctrl-alt)
                             (editor/editor-text ed)))
              file? (.exists (io/file ((:plugins-dir* ui)) "p9b-acc.clj"))
              status (history/version-status (entries ui "p9b-acc") 1)
              {:keys [visible count-visible]} (panel ui)]
          (support/check :review-accept-goes-live
                         (and (= "plugin p9b-acc: v1 accepted" (last lines)) live? (= "(when (a b))" text)
                              file? (= :accepted status) (not visible) (not count-visible))
                         {:lines lines :live? live? :text text :file? file? :status status
                          :visible visible :count-visible count-visible}))))))

(defn row-review-reject-keeps-note
  "Reject with a note: the status is :rejected with the note, and nothing
  goes live."
  [ui]
  (go-row :review-reject-keeps-note support/check
    (with-proposals ui {:ids ["p9b-rej"]}
      (fn []
        (propose! ui "p9b-rej" (source "p9b-rej"))
        ((:review-proposals!* ui))
        (on-gtk (review-pane/set-note! (:review-pane ui) "not now"))
        (let [lines ((:review-reject!* ui))
              e (dissoc (last (entries ui "p9b-rej")) :at)
              live? (some? (get-in @plugin-registry/live [:plugins :p9b-rej]))]
          (support/check :review-reject-keeps-note
                         (and (= "plugin p9b-rej: v1 rejected" (last lines))
                              (= {:n 1 :status :rejected :note "not now"} e) (not live?))
                         {:lines lines :entry e :live? live?}))))))

(defn row-review-refused-stays-pending
  "A checked proposal whose check reported something other than what loads:
  Accept refuses, the panel stays on it and says why, and it stays
  pending."
  [ui]
  (go-row :review-refused-stays-pending support/check
    (with-proposals ui {:ids ["p9b-ref"]}
      (fn []
        (propose! ui "p9b-ref" (source "p9b-ref")
                  :check {:plugin {:id :p9b-ref} :warnings [] :timings {} :unchecked? false})
        ((:review-proposals!* ui))
        (let [lines ((:review-accept!* ui))
              shown ((:review-shown* ui))
              status (history/version-status (entries ui "p9b-ref") 1)
              live? (some? (get-in @plugin-registry/live [:plugins :p9b-ref]))
              {:keys [visible footer count] :as p} (panel ui)]
          (support/check :review-refused-stays-pending
                         (and visible (= {:id "p9b-ref" :n 1} (select-keys shown [:id :n]))
                              (str/includes? footer "isn't what its check reported")
                              (= :pending status) (not live?) (= "1 plugin proposal" count))
                         (assoc p :lines lines :shown shown :status status :live? live?)))))))

(defn row-review-superseded-while-shown
  "v1 is shown when v2 supersedes it behind the panel's back: Accept
  refuses v1, the panel moves to v2, and nothing goes live."
  [ui]
  (go-row :review-superseded-while-shown support/check
    (with-proposals ui {:ids ["p9b-sup"]}
      (fn []
        (propose! ui "p9b-sup" (source "p9b-sup"))
        ((:review-proposals!* ui))
        (propose! ui "p9b-sup" (source "p9b-sup" "The second."))
        (let [lines ((:review-accept!* ui))
              shown ((:review-shown* ui))
              live? (some? (get-in @plugin-registry/live [:plugins :p9b-sup]))]
          (support/check :review-superseded-while-shown
                         (and (some #{"plugin p9b-sup has no pending v1"} lines)
                              (= {:id "p9b-sup" :n 2} (select-keys shown [:id :n])) (not live?))
                         {:lines lines :shown shown :live? live?}))))))
