(ns jonquil.gtk.window.quit-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.ffi :as ffi]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.popover :as popover]
            [jonquil.gtk.window.files :as win-files]
            [jonquil.gtk.window.quit :as win-quit]))

(defn- tab [path dirty?] {:ed {:dirty (atom dirty?)} :path (atom path)})

(defn- run-stubbed
  "Runs f with the close path's GTK, editor and file calls recording into
  log. A tab's ed is {:dirty (atom b)}; save-to! of a path in fail-paths
  leaves it dirty; a Save dialog is logged as [:dialog mode win cb]."
  [log fail-paths f]
  (with-redefs [editor/dirty? (fn [ed] @(:dirty ed))
                g/gtk-window-close (fn [win] (swap! log conj [:close win]))
                win-files/save-to! (fn [ed _cv _path p]
                                     (swap! log conj [:save p])
                                     (when-not (contains? fail-paths p) (reset! (:dirty ed) false)))
                win-files/file-dialog! (fn [mode win cb] (swap! log conj [:dialog mode win cb]))
                win-files/dialog-dismissed? (fn [r] (= 2 (:code r)))
                cview/append! (fn [_cv kind text] (swap! log conj [:append kind text]))]
    (f)))

(defn- choice-ctx [log confirmed]
  {:dirty-tabs [] :cv ::cv :win ::win :confirmed-close? confirmed
   :select! (fn [t] (swap! log conj [:select @(:path t)]))})

(deftest close-choice-decisions-test
  (let [log (atom []) confirmed (atom false) ctx (choice-ctx log confirmed)]
    (run-stubbed log #{}
                 (fn []
                   (is (nil? (#'win-quit/close-choice! ctx {:error "dismissed"})))
                   (is (nil? (#'win-quit/close-choice! ctx {:index 2})))
                   (is (nil? (#'win-quit/close-choice! ctx {:index 3})))
                   (is (= [] @log))
                   (is (false? @confirmed))
                   (#'win-quit/close-choice! ctx {:index 1})
                   (is (true? @confirmed))
                   (reset! confirmed false)
                   (#'win-quit/close-choice! ctx {:index 0})))
    (is (true? @confirmed))
    (is (= [[:close ::win] [:close ::win]] @log))))

(deftest save-all-stops-at-the-first-tab-still-dirty-test
  (let [log (atom []) confirmed (atom false) ctx (choice-ctx log confirmed)
        a (tab "/r/a.clj" true) b (tab "/r/b.clj" true) c (tab "/r/c.clj" true)]
    (run-stubbed log #{"/r/b.clj"} (fn [] (#'win-quit/save-all! ctx [a b c])))
    (is (= [[:save "/r/a.clj"] [:save "/r/b.clj"] [:select "/r/b.clj"]] @log))
    (is (false? @confirmed))
    (reset! log [])
    (run-stubbed log #{} (fn [] (#'win-quit/save-all! ctx [b c])))
    (is (= [[:save "/r/b.clj"] [:save "/r/c.clj"] [:close ::win]] @log))
    (is (true? @confirmed))))

(deftest save-all-asks-where-an-untitled-tab-goes-test
  (let [log (atom []) confirmed (atom false) ctx (choice-ctx log confirmed) u (tab nil true)
        answer! (fn [r] (let [[_ _ _ cb] (first (filter #(= :dialog (first %)) @log))] (cb r)))]
    (run-stubbed log #{}
                 (fn []
                   (#'win-quit/save-all! ctx [u])
                   (answer! {:error "dismissed" :code 2})
                   (answer! {:error "full" :code 9})
                   (answer! {:path "/r/u.clj"})))
    (is (= [[:select nil] [:dialog :save ::win] [:append :error "; save: full\n"] [:save "/r/u.clj"] [:close ::win]]
           (mapv (fn [e] (if (= :dialog (first e)) (vec (take 3 e)) e)) @log)))
    (is (true? @confirmed))))

(deftest unparent-hides-an-open-popover-first-test
  (let [log (atom []) pops (atom [{:id 1 :popover ffi/null} {:id 2 :popover ffi/null}])]
    (with-redefs [popover/open? (fn [p] (= 1 (:id p)))
                  popover/hide! (fn [p] (swap! log conj [:hide (:id p)]))
                  g/gtk-widget-get-parent (fn [_] ffi/null)
                  g/gtk-widget-unparent (fn [_] (swap! log conj [:unparent]))]
      (#'win-quit/unparent-registered-popovers! pops))
    (is (= [[:hide 1]] @log))))
