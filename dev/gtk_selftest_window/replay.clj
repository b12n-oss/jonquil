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
            [jonquil.parts :as parts]
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
                                    (str/ends-with? line "Open last session's replay from the palette to review it.")
                                    (not (str/includes? line "Recorded in")))
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
  "Run the forms changed records the edited form it sent, as a form whose
  source file is the tab's path."
  [ui]
  (go-row :replay-records-run-changed support/check
    (let [original (on-gtk (root ui))
          [d a t]
          (on-gtk (tabs/occupy-first! ui)
                  (let [[d a] (project!)]
                    ((:simulate-folder-result! ui) {:path d})
                    ((:open-file! ui) a)
                    (let [t (some (fn [t] (when (= a @(:path t)) t)) (tab-list ui))]
                      ((:select-tab! ui) (:id t))
                      (editor/set-text! (:ed t) "(def hits (atom 0))\n(defn cc [] 1)")
                      ((:run!* ui))
                      [d a t])))
          ran? (await-until 5000 20 #(and (support/idle? ui) (some (fn [e] (= a (:path e))) (:current (record ui)))))
          _ (on-gtk (editor/set-text! (:ed t) "(def hits (atom 0))\n(defn cc [] 10)"))
          _ (await-until 3000 20 #(seq @(:ranges (:gutter (:changed (:ed t))))))
          _ (on-gtk (let [p (:palette ui)]
                      ((:open-palette!* ui))
                      (palette/set-query! p "run the forms changed")
                      (palette/run-selected! p)
                      (when (popover/open? p) (popover/hide! p))))
          find-e (fn [] (some (fn [e] (when (= "(defn cc [] 10)" (:text e)) e)) (:current (on-disk ui d))))
          ok? (await-until 5000 20 #(and (support/idle? ui) (find-e)))
          e (find-e)]
      (on-gtk (try
                (support/check :replay-records-run-changed
                               (and ran? ok? (= :form (:kind e)) (= a (:source-file e)))
                               {:ran ran? :ok ok? :entry e :path a})
                (finally (restore! ui original [d])))))))

(defn row-replay-unreadable-other-root
  "A slow eval begun at A, finished after a re-root to B, finds A's record
  file unreadable: the file stays as it was and the console says so."
  [ui]
  (go-row :replay-unreadable-other-root support/check
    (let [original (on-gtk (root ui))
          garbage "{:version 1 :previous [oops"
          [da db]
          (on-gtk (tabs/occupy-first! ui)
                  (let [[da] (project!)
                        [db] (project!)]
                    (io/make-parents (record-file ui da))
                    (spit (record-file ui da) garbage)
                    ((:simulate-folder-result! ui) {:path da})
                    [da db]))
          mark (on-gtk (support/out-end-offset ui))
          _ (enter! ui "(Thread/sleep 600)")
          busy? (await-until 2000 20 #(not (support/idle? ui)))
          _ (on-gtk ((:simulate-folder-result! ui) {:path db}))
          said? (await-until 6000 20 #(and (support/idle? ui)
                                           (str/includes? (support/since ui mark) "; last session: Couldn't read")))]
      (on-gtk (try
                (support/check :replay-unreadable-other-root
                               (and busy? said? (= garbage (slurp (record-file ui da))))
                               {:busy busy? :said said? :file (slurp (record-file ui da))
                                :console (support/since ui mark)})
                (finally (restore! ui original [da db])))))))

(defn- replay-tab [ui]
  (let [id ((:replay-tab-id* ui))]
    (some (fn [t] (when (= id (:id t)) t)) (tab-list ui))))

(defn- seeded!
  "A fresh project whose record file holds rec, the window re-rooted to it:
  [dir mark], mark the console offset before the re-root."
  [ui rec]
  (let [[d] (project!)
        mark (support/out-end-offset ui)]
    (write-record! ui d rec)
    ((:simulate-folder-result! ui) {:path d})
    [d mark]))

(defn row-replay-open-tab
  "Open last session's replay: an Untitled tab, text from the script, saved."
  [ui]
  (go-row :replay-open-tab support/check
    (let [original (on-gtk (root ui))
          n0 (on-gtk (count (tab-list ui)))
          [d] (on-gtk (tabs/occupy-first! ui)
                      (seeded! ui {:version 1 :previous [(entry "(def r 1)")] :current [] :history []}))
          _ (on-gtk ((:replay-open!* ui)))
          t (on-gtk (replay-tab ui))]
      (on-gtk (try
                (let [text (when t (editor/editor-text (:ed t)))]
                  (support/check :replay-open-tab
                                 (and t (= (inc n0) (count (tab-list ui))) (nil? @(:path t))
                                      (str/starts-with? text ";; Last session in ")
                                      (str/includes? text "(def r 1)")
                                      (not (editor/dirty? (:ed t)))
                                      (= (:id t) (:id ((:active-tab* ui)))))
                                 {:tab (some? t) :text text}))
                (finally (restore! ui original [d])))))))

(defn row-replay-run-no-reset
  "Running the replay adds to the session: a console definition made before
  it survives, and a successful Run empties :previous."
  [ui]
  (go-row :replay-run-no-reset support/check
    (let [original (on-gtk (root ui))
          [d] (on-gtk (tabs/occupy-first! ui)
                      (seeded! ui {:version 1 :previous [(entry "(def r 1)")] :current [] :history []}))
          _ (enter! ui "(def typed 7)")
          _ (await-until 5000 20 #(and (support/idle? ui) (some #{"(def typed 7)"} (history ui))))
          _ (on-gtk ((:replay-open!* ui)) ((:run!* ui)))
          emptied? (await-until 5000 20 #(and (support/idle? ui) (empty? (:previous (record ui)))))
          m (enter! ui "typed")
          shown? (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m) "=> 7")))
          prev (:previous (record ui))]
      (on-gtk (try
                (support/check :replay-run-no-reset
                               (and emptied? shown? (empty? prev))
                               {:emptied emptied? :shown shown? :previous prev :console (support/since ui m)})
                (finally (restore! ui original [d])))))))

(defn row-replay-throw-keeps-record
  "A replay Run that throws leaves :previous as it was."
  [ui]
  (go-row :replay-throw-keeps-record support/check
    (let [original (on-gtk (root ui))
          e (entry "(def boom (throw (ex-info \"x\" {})))")
          [d] (on-gtk (tabs/occupy-first! ui)
                      (seeded! ui {:version 1 :previous [e] :current [] :history []}))
          mark (on-gtk (support/out-end-offset ui))
          _ (on-gtk ((:replay-open!* ui)) ((:run!* ui)))
          failed? (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui mark) "ExceptionInfo")))
          settled? (await-until 800 20 #(seq (:current (record ui))))
          prev (:previous (record ui))]
      (on-gtk (try
                (support/check :replay-throw-keeps-record
                               (and failed? (not settled?) (= [(:text e)] (mapv :text prev)))
                               {:failed failed? :settled settled? :previous prev
                                :console (support/since ui mark)})
                (finally (restore! ui original [d])))))))

(defn row-replay-survives-new-eval
  "Yesterday's three forms plus a new eval: the next launch's :previous holds
  all four, in order."
  [ui]
  (go-row :replay-survives-new-eval support/check
    (let [original (on-gtk (root ui))
          [d] (on-gtk (tabs/occupy-first! ui)
                      (seeded! ui {:version 1 :previous []
                                   :current [(entry "(def s1 1)") (entry "(def s2 2)") (entry "(def s3 3)")]
                                   :history []}))
          [d2] (on-gtk (project!))
          _ (enter! ui "(+ 1 2)")
          saved? (await-until 5000 20 #(and (support/idle? ui) (= ["(+ 1 2)"] (mapv :text (:current (on-disk ui d))))))
          _ (on-gtk ((:simulate-folder-result! ui) {:path d2})
                    ((:replay-reopen-root!* ui) d)
                    ((:simulate-folder-result! ui) {:path d}))
          prev (mapv :text (:previous (record ui)))]
      (on-gtk (try
                (support/check :replay-survives-new-eval
                               (and saved? (= ["(def s1 1)" "(def s2 2)" "(def s3 3)" "(+ 1 2)"] prev)
                                    (empty? (:current (record ui))))
                               {:saved saved? :previous prev})
                (finally (restore! ui original [d d2])))))))

(defn row-replay-forget
  "Forget empties :previous, in memory and on disk, and leaves :history."
  [ui]
  (go-row :replay-forget support/check
    (let [original (on-gtk (root ui))
          [d mark] (on-gtk (tabs/occupy-first! ui)
                           (seeded! ui {:version 1 :previous [(entry "(def f 1)")] :current []
                                        :history ["(+ 40 2)"]}))
          _ (enter! ui "(def keep 1)")
          _ (await-until 5000 20 #(and (support/idle? ui) (= ["(def keep 1)"] (mapv :text (:current (on-disk ui d))))))
          _ (on-gtk ((:replay-forget!* ui)))
          ok? (await-until 5000 20 #(empty? (:previous (on-disk ui d))))
          rec (record ui)]
      (on-gtk (try
                (support/check :replay-forget
                               (and ok? (empty? (:previous rec)) (= ["(def keep 1)"] (mapv :text (:current rec)))
                                    (= ["(def keep 1)"] (mapv :text (:current (on-disk ui d))))
                                    (some #{"(+ 40 2)"} (:history rec))
                                    (some #{"(+ 40 2)"} (:history (on-disk ui d)))
                                    (str/includes? (support/since ui mark)
                                                   "; Forgot the last session. Your prompt history stays."))
                               {:ok ok? :record rec :disk (on-disk ui d)})
                (finally (restore! ui original [d])))))))

(defn row-replay-none
  "With nothing recorded, Open says so and opens no tab."
  [ui]
  (go-row :replay-none support/check
    (let [original (on-gtk (root ui))
          n0 (on-gtk (count (tab-list ui)))
          [d mark] (on-gtk (tabs/occupy-first! ui)
                           (seeded! ui {:version 1 :previous [] :current [] :history []}))
          _ (on-gtk ((:replay-open!* ui)))]
      (on-gtk (try
                (support/check :replay-none
                               (and (= n0 (count (tab-list ui)))
                                    (nil? ((:replay-tab-id* ui)))
                                    (str/includes? (support/since ui mark)
                                                   "; There's no last session to replay for this folder."))
                               {:tabs (count (tab-list ui)) :n0 n0 :console (support/since ui mark)})
                (finally (restore! ui original [d])))))))

(defn row-replay-open-twice
  "A second Open selects the replay tab it already made: one new tab in
  total, and it is the selected one."
  [ui]
  (go-row :replay-open-twice support/check
    (let [original (on-gtk (root ui))
          n0 (on-gtk (count (tab-list ui)))
          [d] (on-gtk (tabs/occupy-first! ui)
                      (seeded! ui {:version 1 :previous [(entry "(def r 1)")] :current [] :history []}))
          _ (on-gtk ((:replay-open!* ui)))
          id ((:replay-tab-id* ui))
          _ (on-gtk ((:select-tab! ui) (:id (first (tab-list ui)))))
          _ (on-gtk ((:replay-open!* ui)))]
      (on-gtk (try
                (support/check :replay-open-twice
                               (and id (= (inc n0) (count (tab-list ui)))
                                    (= id ((:replay-tab-id* ui)))
                                    (= id (:id ((:active-tab* ui)))))
                               {:n0 n0 :n (count (tab-list ui)) :id id :now ((:replay-tab-id* ui))
                                :active (:id ((:active-tab* ui)))})
                (finally (restore! ui original [d])))))))

(defn row-replay-forget-off
  "With :remember-session? false, Forget leaves the record file as it was."
  [ui]
  (go-row :replay-forget-off support/check
    (let [original (on-gtk (root ui))
          [d mark] (on-gtk (tabs/occupy-first! ui)
                           (seeded! ui {:version 1 :previous [(entry "(def f 1)")] :current [] :history []}))
          before (slurp (record-file ui d))
          _ (on-gtk (remember! ui false) ((:replay-forget!* ui)))
          _ (await-until 500 20 #(not= before (slurp (record-file ui d))))
          after (slurp (record-file ui d))]
      (on-gtk (try
                (support/check :replay-forget-off
                               (and (= before after)
                                    (str/includes? (support/since ui mark) "; There's no last session to forget for this folder."))
                               {:same (= before after) :console (support/since ui mark)})
                (finally (restore! ui original [d])))))))

;; --- final review fixes --------------------------------------------------------

(defn row-replay-reroot-open-new-tab
  "Open at A, re-root to B, Open again: a new tab with B's script, not A's
  tab. A Run of A's old tab (no longer the replay tab) leaves both records'
  :previous alone."
  [ui]
  (go-row :replay-reroot-open-new-tab support/check
    (let [original (on-gtk (root ui))
          [da] (on-gtk (tabs/occupy-first! ui)
                       (seeded! ui {:version 1 :previous [(entry "(def ra 1)")] :current [] :history []}))
          ta (on-gtk ((:replay-open!* ui)) (replay-tab ui))
          [db] (on-gtk (seeded! ui {:version 1 :previous [(entry "(def rb 2)")] :current [] :history []}))
          tb (on-gtk ((:replay-open!* ui)) (replay-tab ui))
          text-b (on-gtk (when tb (editor/editor-text (:ed tb))))
          active-b (on-gtk (:id ((:active-tab* ui))))
          _ (on-gtk ((:select-tab! ui) (:id ta)) ((:run!* ui)))
          ran? (await-until 5000 20 #(and (support/idle? ui) (seq (:current (on-disk ui db)))))
          settled? (await-until 800 20 #(or (empty? (:previous (on-disk ui db)))
                                             (empty? (:previous (on-disk ui da)))))]
      (on-gtk (try
                (support/check :replay-reroot-open-new-tab
                               (and ta tb (not= (:id ta) (:id tb)) (= (:id tb) active-b)
                                    (str/includes? text-b "(def rb 2)") (not (str/includes? text-b "(def ra 1)"))
                                    ran? (not settled?)
                                    (= ["(def rb 2)"] (mapv :text (:previous (on-disk ui db))))
                                    (= ["(def ra 1)"] (mapv :text (:previous (on-disk ui da)))))
                               {:ta (:id ta) :tb (:id tb) :active active-b :text-b text-b :ran ran?
                                :a (on-disk ui da) :b (on-disk ui db)})
                (finally (restore! ui original [da db])))))))

(defn row-replay-run-forgets-own-root
  "Open at A, re-root to B, Run A's replay tab: A's :previous is forgotten,
  B's stays."
  [ui]
  (go-row :replay-run-forgets-own-root support/check
    (let [original (on-gtk (root ui))
          [da] (on-gtk (tabs/occupy-first! ui)
                       (seeded! ui {:version 1 :previous [(entry "(def rc 1)")] :current [] :history []}))
          ta (on-gtk ((:replay-open!* ui)) (replay-tab ui))
          [db] (on-gtk (seeded! ui {:version 1 :previous [(entry "(def rd 2)")] :current [] :history []}))
          _ (on-gtk ((:select-tab! ui) (:id ta)) ((:run!* ui)))
          forgot? (await-until 5000 20 #(and (support/idle? ui) (empty? (:previous (on-disk ui da)))))
          b-lost? (await-until 800 20 #(or (empty? (:previous (record ui))) (empty? (:previous (on-disk ui db)))))]
      (on-gtk (try
                (support/check :replay-run-forgets-own-root
                               (and ta forgot? (not b-lost?)
                                    (= ["(def rd 2)"] (mapv :text (:previous (record ui)))))
                               {:forgot forgot? :b-lost b-lost? :a (on-disk ui da) :b (record ui)})
                (finally (restore! ui original [da db])))))))

(defn row-replay-stale-tab
  "Open, close the replay tab, Open again: a fresh replay tab, selected."
  [ui]
  (go-row :replay-stale-tab support/check
    (let [original (on-gtk (root ui))
          [d] (on-gtk (tabs/occupy-first! ui)
                      (seeded! ui {:version 1 :previous [(entry "(def rs 1)")] :current [] :history []}))
          t1 (on-gtk ((:replay-open!* ui)) (replay-tab ui))
          _ (on-gtk ((:close-tab! ui) (:id t1)))
          gone? (on-gtk (nil? ((:replay-tab-id* ui))))
          t2 (on-gtk ((:replay-open!* ui)) (replay-tab ui))]
      (on-gtk (try
                (support/check :replay-stale-tab
                               (and t1 gone? t2 (not= (:id t1) (:id t2)) (= (:id t2) (:id ((:active-tab* ui))))
                                    (str/includes? (editor/editor-text (:ed t2)) "(def rs 1)"))
                               {:t1 (:id t1) :gone gone? :t2 (:id t2)})
                (finally (restore! ui original [d])))))))

(defn row-replay-end-to-end
  "A record with a file, a definition in that file's ns and a form: re-root,
  Open, Run. app.core/x exists, :previous is empty and the new :current
  compacts to the same files and definitions."
  [ui]
  (go-row :replay-end-to-end support/check
    (let [original (on-gtk (root ui))
          [d] (on-gtk (project!))
          core (str d "/src/app/core.clj")
          at (- (System/currentTimeMillis) 3600000)
          prev [{:kind :file :path core :at at :dialect "Jolt"}
                {:kind :form :text "(def x (f))" :ns "app.core" :source-file nil :at at :dialect "Jolt"}
                {:kind :form :text "(swap! (atom 0) inc)" :ns "user" :source-file nil :at at :dialect "Jolt"}]
          _ (io/make-parents core)
          _ (spit core "(ns app.core)\n(defn f [] 1)\n")
          _ (on-gtk (tabs/occupy-first! ui)
                    (write-record! ui d {:version 1 :previous prev :current [] :history []})
                    ((:simulate-folder-result! ui) {:path d}))
          _ (on-gtk ((:replay-open!* ui)) ((:run!* ui)))
          emptied? (await-until 5000 20 #(and (support/idle? ui) (empty? (:previous (record ui)))
                                              (seq (:current (record ui)))
                                              (empty? (:previous (on-disk ui d)))))
          m (enter! ui "app.core/x")
          shown? (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m) "=> 1")))
          c0 (replay/compact prev)
          c1 (replay/compact (:current (record ui)))
          shape (fn [c] [(:files c) (mapv (juxt :ns :text) (:defs c))])]
      (on-gtk (try
                (support/check :replay-end-to-end
                               (and emptied? shown? (= (shape c0) (shape c1)))
                               {:emptied emptied? :shown shown? :before (shape c0) :after (shape c1)
                                :console (support/since ui m)})
                (finally (restore! ui original [d])))))))

(defn row-replay-offer-dialect-differs
  "A record made in another dialect: the offer line says so."
  [ui]
  (go-row :replay-offer-dialect-differs support/check
    (let [original (on-gtk (root ui))
          [d mark] (on-gtk (tabs/occupy-first! ui)
                           (seeded! ui {:version 1 :previous [(assoc (entry "(def z 9)") :dialect "Clojure (JVM)")]
                                        :current [] :history []}))
          ok? (await-until 5000 20 #(offer-line ui mark))
          line (offer-line ui mark)]
      (on-gtk (try
                (support/check :replay-offer-dialect-differs
                               (and ok? (str/starts-with? line "; Last session (Clojure (JVM), ")
                                    (str/ends-with? line (str "Open last session's replay from the palette to review it."
                                                              " Recorded in Clojure (JVM); this window runs Jolt.")))
                               {:ok ok? :line line})
                (finally (restore! ui original [d])))))))

(defn- fake-evaluator
  "An idle Evaluator, every method implemented, whose prompt is in ns."
  [ns]
  (reify ev/Evaluator
    (start! [_] nil)
    (stop! [_] nil)
    (eval! [_ _ _] nil)
    (interrupt! [_] nil)
    (evaluating? [_] false)
    (waiting-for-input? [_] false)
    (send-input! [_ _] nil)
    (send-eof! [_] nil)
    (history [_] (atom {:items [] :index 0 :draft ""}))
    (prompt-ns [_] ns)
    (set-mode! [_ _] nil)
    (complete [_ _ _ _ _] [])
    (lookup [_ _ _ _] nil)
    (limitations [_] [])))

(defn row-replay-run-ns
  "The ns an Untitled Run is recorded in: user for the in-process session,
  the prompt's ns for any other evaluator."
  [ui]
  (go-row :replay-run-ns support/check
    (on-gtk (let [f (resolve 'jonquil.gtk.window/run-record-ns)
                  other (fake-evaluator 'zz.other)
                  got (when f [(f (:session ui)) (f other)])]
              (support/check :replay-run-ns (= ["user" "zz.other"] got) {:got got})))))

(def ^:private carry-form "(swap! (atom 0) inc)")

(defn- carry-prev []
  (let [at (- (System/currentTimeMillis) 3600000)]
    [{:kind :form :text "(def carried-d 1)" :ns "user" :source-file nil :at at :dialect "Jolt"}
     {:kind :form :text carry-form :ns "user" :source-file nil :at at :dialect "Jolt"}]))

(defn row-replay-run-carries-skipped
  "Open, Run unedited: the other form still behind #_ is in :current (in
  memory and on disk) and :previous is empty."
  [ui]
  (go-row :replay-run-carries-skipped support/check
    (let [original (on-gtk (root ui))
          [d] (on-gtk (tabs/occupy-first! ui)
                      (seeded! ui {:version 1 :previous (carry-prev) :current [] :history []}))
          _ (on-gtk ((:replay-open!* ui)) ((:run!* ui)))
          ok? (await-until 5000 20 #(and (support/idle? ui) (empty? (:previous (record ui)))
                                         (some (fn [e] (= carry-form (:text e))) (:current (record ui)))
                                         (= (record ui) (on-disk ui d))))
          cur (:current (record ui))]
      (on-gtk (try
                (support/check :replay-run-carries-skipped
                               (and ok? (some #(= carry-form (:text %)) cur)
                                    (empty? (:previous (record ui)))
                                    (= (:current (on-disk ui d)) cur))
                               {:ok ok? :record (record ui) :disk (on-disk ui d)})
                (finally (restore! ui original [d])))))))

(defn row-replay-run-drops-deleted-skipped
  "Open, delete the #_ line from the tab text, Run: the form is not in
  :current, and :previous is empty."
  [ui]
  (go-row :replay-run-drops-deleted-skipped support/check
    (let [original (on-gtk (root ui))
          [d] (on-gtk (tabs/occupy-first! ui)
                      (seeded! ui {:version 1 :previous (carry-prev) :current [] :history []}))
          _ (on-gtk ((:replay-open!* ui))
                    (let [t (replay-tab ui)
                          kept (->> (str/split-lines (editor/editor-text (:ed t)))
                                    (remove #(str/includes? % (str "#_" carry-form)))
                                    (str/join "\n"))]
                      (editor/set-text! (:ed t) kept))
                    ((:run!* ui)))
          ok? (await-until 5000 20 #(and (support/idle? ui) (empty? (:previous (record ui)))
                                         (seq (:current (record ui)))
                                         (= (record ui) (on-disk ui d))))
          cur (:current (record ui))]
      (on-gtk (try
                (support/check :replay-run-drops-deleted-skipped
                               (and ok? (not-any? #(= carry-form (:text %)) cur)
                                    (empty? (:previous (record ui))))
                               {:ok ok? :record (record ui) :disk (on-disk ui d)})
                (finally (restore! ui original [d])))))))

(defn row-replay-state-per-window
  "Replay state is per window. The harness opens one window per process, so
  this checks that the window's record is its own root's, and that installing
  the replay part again, as a second window would (its own console slots, no
  tabs yet), starts from an empty record and leaves this window's recorder
  alone."
  [ui]
  (go-row :replay-state-per-window support/check
    (on-gtk (let [part (some-> (resolve 'jonquil.gtk.parts.replay/part) deref)
                  recorder* (:recorder* (:console ui))
                  before @recorder*
                  own ((:replay-record* ui))
                  slots {:recorder* (atom nil) :replay-root* (atom nil) :replay-tab* (atom nil)}
                  other (when part (:ui (part {:cv slots :tab-ctx* (atom nil)})))
                  fresh (when other ((:replay-record* other)))]
              (support/check :replay-state-per-window
                             (and part (= (root ui) (:root own)) (= {} fresh)
                                  (identical? before @recorder*) (some? @(:recorder* slots)))
                             {:part? (some? part) :root (root ui) :own-root (:root own) :fresh fresh
                              :recorder-kept? (identical? before @recorder*)})))))

(defn row-part-commands-reachable
  "Every part spec command's :call key is in the palette's window context
  (the core's keys plus the parts' :fns), so none runs as a silent no-op."
  [ui]
  (go-row :part-commands-reachable support/check
    (on-gtk (let [ks (:palette-ctx-keys ui)
                  cmds (apply merge {} (map :commands parts/specs))
                  missing (parts/missing-calls cmds ks)]
              (support/check :part-commands-reachable
                             (and (set? ks) (seq cmds) (empty? missing))
                             {:ctx-keys? (set? ks) :commands (sort (keys cmds)) :missing missing})))))
