(ns gtk-selftest-window.replay
  "Recording the session and offering it back (LM7), in the real window:
  the window's :replay-record* against the record file beside the harness's
  temp settings.edn. Each row re-roots to a fresh temp project and leaves
  the tree where it was, :remember-session? back on, its record file gone
  and only the first tab, selected.

  Every row is go-style (dev/gtk_async.clj): (fn [ui] ch), one check each."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [gtk-selftest-window.tabs :as tabs]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.palette :as palette]
            [jonquil.gtk.popover :as popover]
            [jonquil.replay :as replay]
            [jonquil.settings :as settings]
            [temp-dirs]))

(defn- root [ui] (:root @(:state (:file-tree ui))))
(defn- tab-list [ui] ((:tabs* ui)))
(defn- record-file [ui r] (replay/record-path ((:settings-path* ui)) r))
(defn- history [ui] (:items @(ev/history ((:evaluator* ui)))))

(defn- project!
  "A fresh canonical temp folder holding a.clj: [dir a]."
  []
  (let [d (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-selftest-replay-")))
        a (str d "/a.clj")]
    (spit a "(def replay-a 1)\n")
    [d a]))

(defn- remember! [ui on?]
  (settings/save-settings! ((:settings-path* ui)) {:remember-session? on?}))

(defn- restore!
  "The tree back at original, :remember-session? on, the extra tabs closed,
  and the record files of dirs gone. GTK-thread only."
  [ui original dirs]
  (remember! ui true)
  ((:simulate-folder-result! ui) {:path original})
  (doseq [t (rest (tab-list ui))]
    (editor/mark-saved! (:ed t))
    ((:close-tab! ui) (:id t)))
  ((:select-tab! ui) (:id (first (tab-list ui))))
  (doseq [d dirs] (io/delete-file (record-file ui d) true)))

(defn- enter!
  "Types code at the console prompt and presses Return; the out offset
  before it."
  [ui code]
  (on-gtk (let [cv (:console ui)
                m (support/out-end-offset ui)]
            (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) code -1 1)
            (cview/handle-return! cv false)
            m)))

(defn- record [ui] (:record ((:replay-record* ui))))

(defn- on-disk [ui r] (:record (replay/load-record (record-file ui r))))

(defn- offer-line [ui mark]
  (last (filter #(str/starts-with? % "; Last session") (str/split-lines (support/since ui mark)))))

(defn- entry [text]
  {:kind :form :text text :ns "user" :source-file nil :at (- (System/currentTimeMillis) 3600000) :dialect "Jolt"})

(defn- write-record! [ui r rec]
  (replay/save-record! (record-file ui r) rec))

(defn row-replay-records-console
  "A console entry is recorded as one form in user, in memory and on disk."
  [ui]
  (go-row :replay-records-console support/check
    (let [original (on-gtk (root ui))
          d (on-gtk (tabs/occupy-first! ui)
                    (let [[d] (project!)] ((:simulate-folder-result! ui) {:path d}) d))]
      (enter! ui "(def a 1)")
      (let [ok? (await-until 5000 20 #(and (support/idle? ui)
                                           (= 1 (count (:current (record ui))))
                                           (= (record ui) (on-disk ui d))))
            cur (:current (record ui))]
        (on-gtk (try
                  (support/check :replay-records-console
                                 (and ok? (= ["(def a 1)"] (mapv :text cur)) (= ["user"] (mapv :ns cur))
                                      (= :form (:kind (first cur))))
                                 {:ok ok? :current cur :disk (on-disk ui d)})
                  (finally (restore! ui original [d]))))))))

(defn row-replay-records-saved-run
  "A Run of a saved file's tab records a file entry for its path."
  [ui]
  (go-row :replay-records-saved-run support/check
    (let [original (on-gtk (root ui))
          [d a] (on-gtk (tabs/occupy-first! ui)
                        (let [[d a] (project!)]
                          ((:simulate-folder-result! ui) {:path d})
                          ((:open-file! ui) a)
                          ((:select-tab! ui) (:id (some (fn [t] (when (= a @(:path t)) t)) (tab-list ui))))
                          ((:run!* ui))
                          [d a]))
          ok? (await-until 5000 20 #(and (support/idle? ui) (seq (:current (record ui)))))
          cur (:current (record ui))]
      (on-gtk (try
                (support/check :replay-records-saved-run
                               (and ok? (= [{:kind :file :path a}] (mapv #(select-keys % [:kind :path]) cur)))
                               {:ok ok? :current cur})
                (finally (restore! ui original [d])))))))

(defn row-replay-skips-errors
  "An entry that threw records nothing, but its text is in the history."
  [ui]
  (go-row :replay-skips-errors support/check
    (let [original (on-gtk (root ui))
          d (on-gtk (tabs/occupy-first! ui)
                    (let [[d] (project!)] ((:simulate-folder-result! ui) {:path d}) d))
          m (enter! ui "(/ 1 0)")
          ok? (await-until 5000 20 #(and (support/idle? ui)
                                         (str/includes? (support/since ui m) "Divide")
                                         (some #{"(/ 1 0)"} (:history (record ui)))))]
      (on-gtk (try
                (support/check :replay-skips-errors
                               (and ok? (empty? (:current (record ui)))
                                    (some #{"(/ 1 0)"} (history ui)))
                               {:ok ok? :record (record ui) :history (history ui)
                                :console (support/since ui m)})
                (finally (restore! ui original [d])))))))

(defn row-replay-offer-at-open
  "Re-rooting to a folder whose record has a non-empty :current moves it to
  :previous and prints the offer."
  [ui]
  (go-row :replay-offer-at-open support/check
    (let [original (on-gtk (root ui))
          [d mark]
          (on-gtk (tabs/occupy-first! ui)
                  (let [[d] (project!)]
                    (write-record! ui d {:version 1 :previous [] :current [(entry "(def z 9)")] :history []})
                    [d (support/out-end-offset ui)]))
          _ (on-gtk ((:simulate-folder-result! ui) {:path d}))
          ok? (await-until 5000 20 #(offer-line ui mark))
          line (offer-line ui mark)
          rec (record ui)]
      (on-gtk (try
                (support/check :replay-offer-at-open
                               (and ok? (= ["(def z 9)"] (mapv :text (:previous rec))) (empty? (:current rec))
                                    (str/starts-with? line "; Last session (Jolt, ")
                                    (str/ends-with? line "Open last session's replay from the palette to review it."))
                               {:ok ok? :line line :record rec})
                (finally (restore! ui original [d])))))))

(defn row-replay-history-restored
  "Re-rooting loads the record's history into the evaluator's Up/Down history."
  [ui]
  (go-row :replay-history-restored support/check
    (let [original (on-gtk (root ui))
          d (on-gtk (tabs/occupy-first! ui)
                    (let [[d] (project!)]
                      (write-record! ui d {:version 1 :previous [] :current [] :history ["(+ 40 2)"]})
                      ((:simulate-folder-result! ui) {:path d})
                      d))
          ok? (await-until 5000 20 #(= ["(+ 40 2)"] (history ui)))
          h @(ev/history ((:evaluator* ui)))]
      (on-gtk (try
                (support/check :replay-history-restored
                               (and ok? (= {:items ["(+ 40 2)"] :index 1 :draft ""} h))
                               {:ok ok? :history h})
                (finally (restore! ui original [d])))))))

(defn row-replay-off
  "With :remember-session? false a console eval writes no file and a re-root
  prints no offer and leaves the record file alone."
  [ui]
  (go-row :replay-off support/check
    (let [original (on-gtk (root ui))
          [d1 d2 rec2]
          (on-gtk (tabs/occupy-first! ui)
                  (let [[d1] (project!)
                        [d2] (project!)
                        rec2 {:version 1 :previous [] :current [(entry "(def y 1)")] :history ["(+ 1 1)"]}]
                    (remember! ui false)
                    (write-record! ui d2 rec2)
                    ((:simulate-folder-result! ui) {:path d1})
                    [d1 d2 rec2]))
          m (enter! ui "(def off1 1)")
          done? (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m) "off1")))
          wrote? (await-until 300 20 #(.exists (io/file (record-file ui d1))))
          m2 (on-gtk (support/out-end-offset ui))
          _ (on-gtk ((:simulate-folder-result! ui) {:path d2}))
          offered? (await-until 300 20 #(offer-line ui m2))]
      (on-gtk (try
                (support/check :replay-off
                               (and done? (not wrote?) (not offered?)
                                    (= rec2 (:record (replay/load-record (record-file ui d2)))))
                               {:done done? :wrote wrote? :offered offered?
                                :disk2 (:record (replay/load-record (record-file ui d2)))})
                (finally (restore! ui original [d1 d2])))))))

(defn row-replay-files-under-start-root
  "An evaluation is filed under the root it started in: a slow entry begun
  at A and finished after a re-root to B lands in A's file, not B's."
  [ui]
  (go-row :replay-files-under-start-root support/check
    (let [original (on-gtk (root ui))
          [da db] (on-gtk (tabs/occupy-first! ui)
                          (let [[da] (project!)
                                [db] (project!)]
                            ((:simulate-folder-result! ui) {:path da})
                            [da db]))
          _ (enter! ui "(Thread/sleep 600)")
          busy? (await-until 2000 20 #(not (support/idle? ui)))
          _ (on-gtk ((:simulate-folder-result! ui) {:path db}))
          done? (await-until 6000 20 #(and (support/idle? ui)
                                           (= ["(Thread/sleep 600)"] (mapv :text (:current (on-disk ui da))))))
          b-got? (await-until 600 20 #(or (seq (:current (record ui))) (seq (:current (on-disk ui db)))))]
      (on-gtk (try
                (support/check :replay-files-under-start-root
                               (and busy? done? (not b-got?) (= db (root ui)))
                               {:busy busy? :done done? :b-got b-got? :a (on-disk ui da)
                                :b (record ui) :b-disk (on-disk ui db)})
                (finally (restore! ui original [da db])))))))

(defn row-replay-records-run-changed
  "Run the forms changed records the edited form it sent."
  [ui]
  (go-row :replay-records-run-changed support/check
    (let [original (on-gtk (root ui))
          d (on-gtk (tabs/occupy-first! ui)
                    ((:select-tab! ui) (:id (first (tab-list ui))))
                    (let [[d] (project!)] ((:simulate-folder-result! ui) {:path d}) d))
          [ran? _] (support/run-and-await ui "(def hits (atom 0))\n(defn cc [] 1)" 5000 #(str/includes? % "#'user/cc"))
          _ (on-gtk (editor/set-text! (:editor ui) "(def hits (atom 0))\n(defn cc [] 10)"))
          _ (await-until 3000 20 #(seq @(:ranges (:gutter (:changed (:editor ui))))))
          _ (on-gtk (let [p (:palette ui)]
                      ((:open-palette!* ui))
                      (palette/set-query! p "run the forms changed")
                      (palette/run-selected! p)
                      (when (popover/open? p) (popover/hide! p))))
          ok? (await-until 5000 20 #(and (support/idle? ui)
                                         (some (fn [e] (= "(defn cc [] 10)" (:text e))) (:current (on-disk ui d)))))]
      (on-gtk (try
                (support/check :replay-records-run-changed
                               (and ran? ok?)
                               {:ran ran? :ok ok? :current (:current (record ui))})
                (finally (restore! ui original [d])))))))
