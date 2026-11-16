(ns gtk-selftest-window.contained
  "Every handler the find bar, the toolbar and the file dialogs connect
  contains its own throw: the row forces the code behind one real handler
  to throw (with-redefs on the var it calls), fires the handler the way
  GTK does, and checks the error was reported where the window reports
  that kind of error, and that the process is still here to check it. The
  dirty-check timer row checks that a throwing tick doesn't stop dirty
  tracking.

  Every row is go-style (dev/gtk_async.clj): (fn [ui] ch), one check each."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [jolt.ffi :as ffi]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.find-bar :as find-bar]
            [jonquil.gtk.window.files :as win-files]
            [jonquil.gtk.window.layout :as win-layout]
            [jonquil.header :as header]
            [jonquil.gtk.window.run :as win-run]))

(defn- boom [msg] (fn [& _] (throw (ex-info msg {}))))

(defn- status [ui] (g/label-text (:status-label ui)))

(defn- status-row
  "One row: in a single GTK step, (fire! msg) makes the code behind a
  handler throw msg and fires the handler; then it waits for the status
  label to show \"; <where>: <msg>\". opts: {:row-id :where :msg :fire!}."
  [ui {:keys [row-id where msg fire!]}]
  (go-row row-id support/check
    (on-gtk (fire! msg))
    (let [want (str "; " where ": " msg)
          ok (await-until 1000 20 #(str/includes? (status ui) want))]
      (on-gtk (support/check row-id ok {:status (status ui) :want want})))))

(defn row-header-default-buttons
  "With no :header in the settings file the header bar is the usual one:
  the plan is jonquil.header/default with nothing skipped, and Files, Run,
  Stop, Open and Save each sit in the header bar."
  [ui]
  (go-row :header-default-buttons support/check
    (on-gtk (let [plan (win-layout/header-plan ((:settings-path* ui)))
                  widgets (assoc (:toolbar ui) :files (:files-btn ui))
                  parented (into {} (for [[k w] widgets]
                                      [k (not (ffi/null? (g/gtk-widget-get-parent w)))]))
                  got {:start (mapv :command (:start plan)) :end (mapv :command (:end plan))
                       :skipped (:skipped plan) :parented parented}]
              (support/check :header-default-buttons
                             (and (= (:start header/default) (:start got))
                                  (= (:end header/default) (:end got))
                                  (empty? (:skipped got))
                                  (every? true? (vals parented)))
                             got)))))

(defn row-places-usual
  "With no :layout in the settings file every part is in its usual place:
  the tree's root in the left-hand place, Interactions at the bottom and
  the inspector on the right."
  [ui]
  (go-row :places-usual support/check
    (on-gtk (let [nb (g/gtk-notebook-get-type)
                  in (fn [w] (g/gtk-widget-get-ancestor w nb))
                  {:keys [left right bottom]} (:places ui)
                  got {:place-of (select-keys ((:place-of* ui)) [:file-tree :repl :terminal :inspector :trace])
                       :tree-left? (= left (in (:root (:file-tree ui))))
                       :repl-bottom? (= bottom (in (:scroll (:console ui))))
                       :inspector-right? (= right (in (:root (:inspector ui))))}]
              (support/check :places-usual
                             (and (= {:file-tree :left :repl :bottom :terminal :bottom :inspector :right :trace :right}
                                     (:place-of got))
                                  (:tree-left? got) (:repl-bottom? got) (:inspector-right? got))
                             got)))))

(defn row-move-to
  "Move to takes Interactions to the right-hand place at once and writes
  :layout, and moving it back puts both back. The settings file is
  restored after, so later rows see the window they expect."
  [ui]
  (go-row :move-to support/check
    (on-gtk (let [path ((:settings-path* ui))
                  f (java.io.File. ^String path)
                  before (when (.exists f) (slurp f))
                  nb (g/gtk-notebook-get-type)
                  in (fn [w] (g/gtk-widget-get-ancestor w nb))
                  {:keys [right bottom]} (:places ui)
                  scroll (:scroll (:console ui))
                  look (fn [] {:right? (= right (in scroll)) :bottom? (= bottom (in scroll))
                               :place (:repl ((:place-of* ui)))
                               :layout (:layout (edn/read-string (slurp f)))})
                  _ ((:move-part!* ui) :repl :right)
                  moved (look)
                  _ ((:move-part!* ui) :repl :bottom)
                  back (look)
                  got {:moved moved :back back}]
              (if before (spit f before) (.delete f))
              (support/check :move-to
                             (and (:right? moved) (= :right (:place moved))
                                  (= [:repl] (get-in moved [:layout :right]))
                                  (:bottom? back) (= :bottom (:place back))
                                  (= [:repl] (get-in back [:layout :bottom]))
                                  (= [] (get-in back [:layout :right])))
                             got)))))

(defn row-tab-menu
  "Interactions' tab menu pops up with Move to Bottom greyed (it is at
  the bottom), and its Move to Right takes Interactions to the right. The
  REPL goes back and the settings file is restored after."
  [ui]
  (go-row :tab-menu support/check
    (on-gtk (let [path ((:settings-path* ui))
                  f (java.io.File. ^String path)
                  before (when (.exists f) (slurp f))
                  nb (g/gtk-notebook-get-type)
                  scroll (:scroll (:console ui))
                  {:keys [popover buttons]} ((:tab-menu!* ui) :repl)
                  shown? (pos? (g/gtk-widget-get-visible popover))
                  sensitive (into {} (for [[p b] buttons] [p (pos? (g/gtk-widget-get-sensitive b))]))
                  _ (g/g-signal-emit-by-name (:right buttons) "clicked")
                  moved? (= (:right (:places ui)) (g/gtk-widget-get-ancestor scroll nb))
                  closed? (zero? (g/gtk-widget-get-visible popover))
                  _ ((:move-part!* ui) :repl :bottom)
                  back? (= (:bottom (:places ui)) (g/gtk-widget-get-ancestor scroll nb))
                  got {:shown? shown? :sensitive sensitive :moved? moved? :closed? closed? :back? back?}]
              (if before (spit f before) (.delete f))
              (support/check :tab-menu
                             (and shown? (= {:left true :right true :bottom false} sensitive)
                                  moved? closed? back?)
                             got)))))

(defn row-find-change-throw-contained [ui]
  (status-row ui {:row-id :find-change-throw-contained :where "find" :msg "forced find-change failure"
                  :fire! (fn [msg]
                           (let [q (:query (:find-bar ui))]
                             (with-redefs [find-bar/recompute-matches! (boom msg)]
                               (g/gtk-editable-set-text q "contained"))
                             (g/gtk-editable-set-text q "")))}))

(defn row-find-replace-throw-contained [ui]
  (status-row ui {:row-id :find-replace-throw-contained :where "replace" :msg "forced replace failure"
                  :fire! (fn [msg]
                           (with-redefs [find-bar/replace-one! (boom msg)]
                             (g/g-signal-emit-by-name (:replace-btn (:find-bar ui)) "clicked")))}))

(defn row-find-replace-all-throw-contained [ui]
  (status-row ui {:row-id :find-replace-all-throw-contained :where "replace all" :msg "forced replace-all failure"
                  :fire! (fn [msg]
                           (with-redefs [find-bar/replace-all! (boom msg)]
                             (g/g-signal-emit-by-name (:replace-all-btn (:find-bar ui)) "clicked")))}))

(defn row-run-button-throw-contained [ui]
  (status-row ui {:row-id :run-button-throw-contained :where "run" :msg "forced run failure"
                  :fire! (fn [msg]
                           (with-redefs [win-run/do-run! (boom msg)]
                             (g/g-signal-emit-by-name (:run (:toolbar ui)) "clicked")))}))

(defn row-stop-button-throw-contained [ui]
  (status-row ui {:row-id :stop-button-throw-contained :where "stop" :msg "forced stop failure"
                  :fire! (fn [msg]
                           (with-redefs [win-run/do-stop! (boom msg)]
                             (g/g-signal-emit-by-name (:stop (:toolbar ui)) "clicked")))}))

(defn row-open-button-throw-contained [ui]
  (status-row ui {:row-id :open-button-throw-contained :where "open" :msg "forced open failure"
                  :fire! (fn [msg]
                           (with-redefs [win-files/do-open! (boom msg)]
                             (g/g-signal-emit-by-name (:open (:toolbar ui)) "clicked")))}))

(defn row-save-button-throw-contained [ui]
  (status-row ui {:row-id :save-button-throw-contained :where "save" :msg "forced save failure"
                  :fire! (fn [msg]
                           (with-redefs [win-files/do-save! (boom msg)]
                             (g/g-signal-emit-by-name (:save (:toolbar ui)) "clicked")))}))

(defn row-reset-toggle-throw-contained
  "Toggles Reset on Run twice while set-mode! throws, so the tick ends where
  it started, then once more with set-mode! restored so the evaluator's own
  mode matches the tick again."
  [ui]
  (status-row ui {:row-id :reset-toggle-throw-contained :where "reset on run" :msg "forced reset-toggle failure"
                  :fire! (fn [msg]
                           (let [chk (:reset-check ui)
                                 was (g/gtk-check-button-get-active chk)]
                             (with-redefs [ev/set-mode! (boom msg)]
                               (g/gtk-check-button-set-active chk (if (zero? was) 1 0)))
                             (g/gtk-check-button-set-active chk was)))}))

(defn- console-row
  "status-row's counterpart for the dialog results, which report on the
  console: waits for \"; <where>: <msg>\" after the console's out-end mark."
  [ui {:keys [row-id where msg fire!]}]
  (go-row row-id support/check
    (let [mark (on-gtk (let [m (support/out-end-offset ui)] (fire! msg) m))
          want (str "; " where ": " msg)
          ok (await-until 1000 20 #(str/includes? (support/since ui mark) want))]
      (on-gtk (support/check row-id ok {:console (support/since ui mark) :want want})))))

(defn row-open-result-throw-contained [ui]
  (console-row ui {:row-id :open-result-throw-contained :where "open" :msg "forced open-result failure"
                   :fire! (fn [msg]
                            (with-redefs [win-files/open-path! (boom msg)]
                              ((:simulate-open-result! ui) {:path "/nonexistent/contained.clj"})))}))

(defn row-save-result-throw-contained [ui]
  (console-row ui {:row-id :save-result-throw-contained :where "save" :msg "forced save-result failure"
                   :fire! (fn [msg]
                            (with-redefs [win-files/save-to! (boom msg)]
                              ((:simulate-save-result! ui) {:path "/nonexistent/contained.clj"})))}))

(defn row-dirty-timer-survives-throw
  "The editor's 20 ms dirty-check tick throws once (dirty? throws on its
  first call), then a later edit must still flip the editor to dirty:
  the timer kept running (on-throw 1) rather than being removed."
  [ui]
  (go-row :dirty-timer-survives-throw support/check
    (let [ed (:editor ui)
          real-dirty? editor/dirty?
          thrown (atom 0)
          _ (on-gtk (editor/set-text! ed "(+ 1 2)")
                    (editor/mark-saved! ed))
          clean? (await-until 1000 20 #(not @(:last-dirty? ed)))
          _ (with-redefs [editor/dirty? (fn [e]
                                          (if (= 1 (swap! thrown inc))
                                            (throw (ex-info "forced dirty-check failure" {}))
                                            (real-dirty? e)))]
              (on-gtk (g/gtk-text-buffer-insert-interactive-at-cursor (:buf ed) " " -1 1))
              (await-until 1000 20 #(pos? @thrown)))
          _ (on-gtk (g/gtk-text-buffer-insert-interactive-at-cursor (:buf ed) "x" -1 1))
          tracked? (await-until 1000 20 #(deref (:last-dirty? ed)))]
      (on-gtk (editor/set-text! ed "")
              (editor/mark-saved! ed)
              (support/check :dirty-timer-survives-throw (and clean? (pos? @thrown) tracked?)
                             {:clean-before clean? :thrown @thrown :tracked tracked?})))))
