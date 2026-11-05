(ns gtk-selftest-window.plugin-history
  "Rows for a plugin's history opened from the plugin list over
  files written into the selftest's own plugins folder (never the
  user's), with Revert and Back driven through the ui functions the
  buttons call. Each row cleans up through plugin-list's with-list, and
  closes the history."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [gtk-async :refer [go-row on-gtk]]
            [gtk-selftest-window.plugin-list :as plugin-list-rows]
            [gtk-selftest-window.review :as review]
            [gtk-selftest-window.support :as support]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.plugin-history-pane :as plugin-history-pane]
            [jonquil.plugin-history :as history]
            [jonquil.plugin-registry :as plugin-registry]))

(defn- with-history
  "plugin-list's with-list, closing the history panel afterwards."
  [ui opts f]
  (plugin-list-rows/with-list ui opts
    (fn [] (try (f) (finally ((:plugin-history-close!* ui)))))))

(defn- v2 [id] (str/replace (review/source id) "{:key \"j\"" "{:key \"k\""))

(defn- write! [ui id text] (spit (io/file ((:plugins-dir* ui)) (str id ".clj")) text))

(defn- v1-then-v2!
  "Writes and loads id's v2 over the v1 with-history loaded."
  [ui id]
  (write! ui id (v2 id))
  ((:reload-plugins!* ui)))

(defn- open! [ui id]
  ((:plugins-list!* ui))
  ((:plugin-history!* ui) id)
  ((:plugin-history-shown* ui)))

(defn- shown [ui] ((:plugin-history-shown* ui)))

(defn- bound-to? [id k]
  (some #(and (= (keyword id "wrap") (:command %)) (= k (:key %)))
        (plugin-registry/editor-bindings @plugin-registry/live)))

(defn- files [id] {:files [[(str id ".clj") (review/source id)]] :ids [id]})

(defn row-plugin-history-shows-each-version
  "v1 and v2 accepted: the title says live v2, the rows are v2 then v1,
  and v2 is selected."
  [ui]
  (go-row :plugin-history-shows-each-version support/check
    (with-history ui (files "ph-a")
      (fn []
        (v1-then-v2! ui "ph-a")
        (let [{:keys [visible title rows selected] :as s} (open! ui "ph-a")]
          (support/check :plugin-history-shows-each-version
                         (and visible (= "History of ph-a: live v2" title) (= 2 (count rows))
                              (str/starts-with? (first rows) "v2 accepted (user, ")
                              (str/starts-with? (second rows) "v1 accepted (user, ")
                              (= 2 selected))
                         s))))))

(defn row-plugin-history-revert-brings-back-v1
  "v1 bound to j, v2 to k: Revert v1 with a reason writes v1 back, binds j
  again and adds a v3 row; a second press says v1's text is already live."
  [ui]
  (go-row :plugin-history-revert-brings-back-v1 support/check
    (with-history ui (files "ph-b")
      (fn []
        (v1-then-v2! ui "ph-b")
        (open! ui "ph-b")
        (let [was-k (bound-to? "ph-b" "k")
              lines ((:plugin-revert!* ui) 1 "too eager")
              s (shown ui)
              again ((:plugin-revert!* ui) 1 "")
              s2 (shown ui)]
          (support/check :plugin-history-revert-brings-back-v1
                         (and was-k (bound-to? "ph-b" "j")
                              (= (review/source "ph-b") (slurp (io/file ((:plugins-dir* ui)) "ph-b.clj")))
                              (= "plugin ph-b reverted to v1 (now v3)" (last lines))
                              (str/ends-with? (:footer s) "plugin ph-b reverted to v1 (now v3)")
                              (str/starts-with? (first (:rows s)) "v3 accepted (revert, ")
                              (str/ends-with? (first (:rows s)) "): revert to v1: too eager")
                              (= "plugin ph-b: v1 is already live, so it wasn't reverted" (last again))
                              (= 3 (count (:rows s2))))
                         {:lines lines :again again :s s :s2 s2}))))))

(defn row-plugin-history-revert-keeps-the-reason-at-the-click
  "The reason recorded is the one in the entry when Revert was pressed,
  even if the entry changes before the step runs."
  [ui]
  (go-row :plugin-history-revert-keeps-the-reason-at-the-click support/check
    (with-history ui (files "ph-j")
      (fn []
        (v1-then-v2! ui "ph-j")
        (open! ui "ph-j")
        (let [lines ((:plugin-revert!* ui) 1 "kept"
                     #(plugin-history-pane/set-reason! (:plugin-history-pane ui) "changed"))
              row (first (:rows (shown ui)))]
          (support/check :plugin-history-revert-keeps-the-reason-at-the-click
                         (str/ends-with? (str row) "): revert to v1: kept")
                         {:lines lines :row row}))))))

(defn row-plugin-history-revert-of-live-says-already
  "Revert on the live v2 says it's already live and adds no row."
  [ui]
  (go-row :plugin-history-revert-of-live-says-already support/check
    (with-history ui (files "ph-c")
      (fn []
        (v1-then-v2! ui "ph-c")
        (open! ui "ph-c")
        (let [lines ((:plugin-revert!* ui) 2 "")
              s (shown ui)]
          (support/check :plugin-history-revert-of-live-says-already
                         (and (= ["plugin ph-c: v2 is already live, so it wasn't reverted"] lines)
                              (= 2 (count (:rows s))))
                         {:lines lines :s s}))))))

(defn row-plugin-history-revert-over-a-broken-edit
  "v2 live and its file then broken: Revert v1 is refused, saying the file
  differs from the live version, and the file is left as it is."
  [ui]
  (go-row :plugin-history-revert-over-a-broken-edit support/check
    (with-history ui (files "ph-d")
      (fn []
        (v1-then-v2! ui "ph-d")
        (write! ui "ph-d" "(ns jonquil.plugins.ph-d) (this does not compile")
        ((:reload-plugins!* ui))
        (open! ui "ph-d")
        (let [lines ((:plugin-revert!* ui) 1 "")]
          (support/check :plugin-history-revert-over-a-broken-edit
                         (and (str/includes? (str (last lines)) "ph-d.clj differs from the live version")
                              (bound-to? "ph-d" "k")
                              (str/includes? (slurp (io/file ((:plugins-dir* ui)) "ph-d.clj")) "does not compile"))
                         {:lines lines}))))))

(defn row-plugin-history-revert-restores-a-deleted-file
  "v1 loaded, its file deleted and reloaded away: Revert v1 writes the file
  back and loads it."
  [ui]
  (go-row :plugin-history-revert-restores-a-deleted-file support/check
    (with-history ui (files "ph-e")
      (fn []
        (io/delete-file (io/file ((:plugins-dir* ui)) "ph-e.clj"))
        ((:reload-plugins!* ui))
        (let [gone (not (bound-to? "ph-e" "j"))
              _ (open! ui "ph-e")
              lines ((:plugin-revert!* ui) 1 "")]
          (support/check :plugin-history-revert-restores-a-deleted-file
                         (and gone (bound-to? "ph-e" "j")
                              (.isFile (io/file ((:plugins-dir* ui)) "ph-e.clj"))
                              (= "plugin ph-e reverted to v1 (now v2)" (last lines)))
                         {:gone gone :lines lines}))))))

(defn row-plugin-history-pending-isnt-reverted
  "A pending agent v2: Revert on it says it was never live."
  [ui]
  (go-row :plugin-history-pending-isnt-reverted support/check
    (with-history ui (files "ph-f")
      (fn []
        (history/record-proposal! {:plugins-dir ((:plugins-dir* ui)) :id "ph-f" :text (v2 "ph-f")
                                   :reason "an agent's ph-f" :status :pending :check {:unchecked? true}
                                   :based-on 1})
        (let [s (open! ui "ph-f")
              lines ((:plugin-revert!* ui) 2 "")]
          (support/check :plugin-history-pending-isnt-reverted
                         (and (str/starts-with? (first (:rows s)) "v2 pending (agent, ")
                              (= "plugin ph-f: v2 was never live, so it wasn't reverted" (last lines)))
                         {:s s :lines lines}))))))

(defn row-plugin-history-unreadable
  "A corrupt history.edn shows the error in place of the rows, and Revert
  says no version is selected."
  [ui]
  (go-row :plugin-history-unreadable support/check
    (with-history ui (files "ph-g")
      (fn []
        (spit (io/file (history/history-dir ((:plugins-dir* ui)) "ph-g") "history.edn") "{:not")
        (let [s (open! ui "ph-g")
              lines ((:plugin-revert!* ui) nil "")
              s2 (shown ui)]
          (support/check :plugin-history-unreadable
                         (and (str/starts-with? (str (:empty s)) "ph-g's history can't be read: ")
                              (empty? (:rows s)) (= [] lines)
                              (= "No version is selected." (:footer s2)))
                         {:s s :lines lines :s2 s2}))))))

(defn row-plugin-history-back-shows-the-list
  "Back shows the list with the same plugin selected and hides the
  history."
  [ui]
  (go-row :plugin-history-back-shows-the-list support/check
    (with-history ui (files "ph-h")
      (fn []
        (open! ui "ph-h")
        ((:plugin-history-back!* ui))
        (let [h (shown ui)
              l ((:plugin-list-shown* ui))]
          (support/check :plugin-history-back-shows-the-list
                         (and (not (:visible h)) (nil? (:id h)) (:visible l) (= "ph-h" (:selected l)))
                         {:h h :l l}))))))

(defn row-plugin-history-refreshes-after-a-step
  "With the history open on v1, a Reload after an edit adds v2's row and
  keeps v1 selected."
  [ui]
  (go-row :plugin-history-refreshes-after-a-step support/check
    (with-history ui (files "ph-i")
      (fn []
        (open! ui "ph-i")
        (v1-then-v2! ui "ph-i")
        (let [{:keys [rows selected title] :as s} (shown ui)]
          (support/check :plugin-history-refreshes-after-a-step
                         (and (= 2 (count rows)) (str/starts-with? (first rows) "v2 accepted")
                              (= 1 selected) (= "History of ph-i: live v2" title))
                         s))))))

;; --- a tab on the plugin's file follows a Revert or an Accept --

(defn- open-tab!
  "Opens id's file in a tab through the list's Open: the tab."
  [ui id]
  ((:plugins-list!* ui))
  ((:plugin-open!* ui) id)
  (on-gtk (first (filter #(some-> @(:path %) (str/ends-with? (str "/" id ".clj"))) ((:tabs* ui))))))

(defn- tab-state [t] (on-gtk {:text (editor/editor-text (:ed t)) :dirty? (editor/dirty? (:ed t))}))

(defn- count-of [ui s] (count (re-seq (re-pattern (java.util.regex.Pattern/quote s)) (support/console-text ui))))

(def ^:private reloaded "changed on disk, so this tab was reloaded.")
(def ^:private warned "changed on disk. This tab keeps your unsaved edits")

(defn row-plugin-tab-follows-a-revert
  "A clean tab on v2's file: Revert to v1 reloads it, clean, as one undo
  step: Cmd/Ctrl+Z brings v2's text back, dirty."
  [ui]
  (go-row :plugin-tab-follows-a-revert support/check
    (with-history ui (files "pt-a")
      (fn []
        (v1-then-v2! ui "pt-a")
        (let [t (open-tab! ui "pt-a")
              v2 (:text (tab-state t))
              before (count-of ui reloaded)
              _ ((:plugin-history!* ui) "pt-a")
              lines ((:plugin-revert!* ui) 1 "")
              st (tab-state t)
              _ (on-gtk (g/gtk-text-buffer-undo (:buf (:ed t))))
              undone (tab-state t)]
          (support/check :plugin-tab-follows-a-revert
                         (and t (= (review/source "pt-a") (:text st)) (not (:dirty? st))
                              (= (inc before) (count-of ui reloaded))
                              (= v2 (:text undone)) (:dirty? undone))
                         {:lines lines :st st :undone undone}))))))

(defn row-plugin-tab-keeps-edits-on-a-revert
  "A dirty tab on v2's file: Revert keeps the edits, warns once, and a
  later Reload doesn't warn again."
  [ui]
  (go-row :plugin-tab-keeps-edits-on-a-revert support/check
    (with-history ui (files "pt-b")
      (fn []
        (v1-then-v2! ui "pt-b")
        (let [t (open-tab! ui "pt-b")
              _ (on-gtk (support/place-cursor! (:buf (:ed t)) 0)
                        (g/gtk-text-buffer-insert-interactive-at-cursor (:buf (:ed t)) ";; mine\n" -1 1))
              edited (:text (tab-state t))
              before (count-of ui warned)
              _ ((:plugin-history!* ui) "pt-b")
              lines ((:plugin-revert!* ui) 1 "")
              st (tab-state t)
              once (count-of ui warned)
              _ ((:reload-plugins!* ui))
              again (count-of ui warned)]
          (support/check :plugin-tab-keeps-edits-on-a-revert
                         (and t (str/starts-with? edited ";; mine") (= edited (:text st)) (:dirty? st)
                              (= (inc before) once again))
                         {:lines lines :st st :before before :once once :again again}))))))

(defn row-plugin-tab-follows-an-accept
  "A clean tab on v1's file: accepting an agent's v2 reloads it."
  [ui]
  (go-row :plugin-tab-follows-an-accept support/check
    (with-history ui (files "pt-c")
      (fn []
        (history/record-proposal! {:plugins-dir ((:plugins-dir* ui)) :id "pt-c" :text (v2 "pt-c")
                                   :reason "an agent's pt-c" :status :pending :check {:unchecked? true}
                                   :based-on 1})
        (let [t (open-tab! ui "pt-c")
              _ ((:review-proposals!* ui))
              lines ((:review-accept!* ui))
              st (tab-state t)]
          (support/check :plugin-tab-follows-an-accept
                         (and t (= (v2 "pt-c") (:text st)) (not (:dirty? st)))
                         {:lines lines :st st}))))))

(defn row-plugin-tab-untouched-by-a-refused-revert
  "Revert to the live v2 is refused: the tab and the console are unchanged."
  [ui]
  (go-row :plugin-tab-untouched-by-a-refused-revert support/check
    (with-history ui (files "pt-d")
      (fn []
        (v1-then-v2! ui "pt-d")
        (let [t (open-tab! ui "pt-d")
              st0 (tab-state t)
              before [(count-of ui reloaded) (count-of ui warned)]
              _ ((:plugin-history!* ui) "pt-d")
              lines ((:plugin-revert!* ui) 2 "")
              st (tab-state t)]
          (support/check :plugin-tab-untouched-by-a-refused-revert
                         (and t (= st0 st) (= before [(count-of ui reloaded) (count-of ui warned)])
                              (str/includes? (str (last lines)) "already live"))
                         {:lines lines :st st}))))))

(defn row-plugin-tab-at-a-link-target-follows-a-revert
  "<plugins>/pt-e.clj links to a file kept elsewhere (a dotfiles setup), and
  the tab is open at that target: Revert to v1 still reloads it."
  [ui]
  (go-row :plugin-tab-at-a-link-target-follows-a-revert support/check
    (let [elsewhere (str (java.nio.file.Files/createTempDirectory "jonquil-dots-" (make-array java.nio.file.attribute.FileAttribute 0)))
          target (str elsewhere "/pt-e.clj")
          link (io/file ((:plugins-dir* ui)) "pt-e.clj")]
      (spit target (review/source "pt-e"))
      (java.nio.file.Files/createSymbolicLink (.toPath link) (.toPath (io/file target))
                                              (make-array java.nio.file.attribute.FileAttribute 0))
      (try
        (with-history ui {:files [] :ids ["pt-e"]}
          (fn []
            (spit target (v2 "pt-e"))
            ((:reload-plugins!* ui))
            (on-gtk ((:open-paths! ui) [target]))
            (let [t (on-gtk (first (filter #(= target @(:path %)) ((:tabs* ui)))))
                  _ ((:plugins-list!* ui))
                  _ ((:plugin-history!* ui) "pt-e")
                  lines ((:plugin-revert!* ui) 1 "")
                  st (when t (tab-state t))]
              (support/check :plugin-tab-at-a-link-target-follows-a-revert
                             (and t (= (review/source "pt-e") (:text st)) (not (:dirty? st)))
                             {:lines lines :st st}))))
        (finally (io/delete-file link true) (io/delete-file target true) (io/delete-file elsewhere true))))))
