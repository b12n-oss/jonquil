(ns jonquil.gtk.window.keys-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.command-registry :as cmd-registry]
            [jonquil.describe :as describe]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.find-bar :as find-bar]
            [jonquil.gtk.inspector-pane :as inspector-pane]
            [jonquil.gtk.window.keys :as win-keys]
            [jonquil.gtk.window.status :as win-status]
            [jonquil.plugin-registry :as plugin-registry]))

(deftest clear-and-toggle-focus-test
  (let [log (atom [])
        focus (atom ::view)]
    (with-redefs [cview/clear-output! (fn [cv] (swap! log conj [:clear cv]))
                  cview/clear-last-output! (fn [cv] (swap! log conj [:clear-last cv]))
                  cview/focus! (fn [cv] (swap! log conj [:cv-focus cv]))
                  editor/focus! (fn [ed] (swap! log conj [:ed-focus ed]))
                  g/gtk-root-get-focus (fn [_] @focus)]
      (#'win-keys/do-clear-console! ::cv)
      (#'win-keys/do-clear-last-output! ::cv)
      (#'win-keys/do-toggle-focus! ::win {:view ::view} ::cv)
      (reset! focus ::other)
      (#'win-keys/do-toggle-focus! ::win {:view ::view} ::cv)
      (is (= [[:clear ::cv] [:clear-last ::cv] [:cv-focus ::cv] [:ed-focus {:view ::view}]] @log)))))

(deftest window-key-ctx-adds-the-reads-test
  (let [log (atom [])
        t (atom {:fb ::fb :rename-pop ::rename :palette ::palette})]
    (with-redefs [find-bar/nav-ready? (fn [fb] (swap! log conj [:nav fb]))
                  find-bar/find-next! (fn [fb] (swap! log conj [:next fb]))
                  find-bar/find-prev! (fn [fb] (swap! log conj [:prev fb]))
                  find-bar/handle-key! (fn [fb kv st] (swap! log conj [:key fb kv st]))
                  win-keys/find-bar-focused? (fn [win fb] (swap! log conj [:fb-focused win fb]))
                  inspector-pane/focused? (fn [pane win] (swap! log conj [:insp pane win]))
                  win-keys/focus-in-popover? (fn [_ pp] (= pp ::palette))]
      (let [k (#'win-keys/window-key-ctx {:win ::win :active-tab (fn [] @t) :pane ::pane :x 1} 65 4)]
        (is (= 1 (:x k)))
        (is (= #{:win :active-tab :pane :x :find-bar-navigable? :find-next! :find-prev!
                 :find-bar-focused? :find-bar-key! :inspector-focused? :entry-focused?}
               (set (keys k))))
        ((:find-bar-navigable? k))
        ((:find-next! k))
        ((:find-prev! k))
        ((:find-bar-focused? k))
        ((:find-bar-key! k))
        ((:inspector-focused? k))
        (is (= [[:nav ::fb] [:next ::fb] [:prev ::fb] [:fb-focused ::win ::fb] [:key ::fb 65 4]
                [:insp ::pane ::win]]
               @log))
        (is (true? ((:entry-focused? k))))
        (swap! t assoc :palette nil)
        (is (not ((:entry-focused? k))))))))

(deftest on-window-key-contains-a-throw-test
  (let [log (atom [])]
    (with-redefs [g/gdk-keyval-to-lower identity
                  win-keys/window-key-ctx (fn [_ _ _] (throw (ex-info "boom" {})))
                  win-status/set-status! (fn [l text] (swap! log conj [l text]))]
      (is (= 1 ((#'win-keys/on-window-key {:status-label ::l}) nil 65 0 0 nil)))
      (is (= [[::l "; key: boom"]] @log)))))

(deftest on-window-key-runs-describes-and-remembers-test
  (let [log (atom [])
        os (System/getProperty "os.name")
        recent (atom [])
        pending (atom false)
        ctx {:status-label ::l :win ::win :active-tab (fn [] {}) :pane ::pane
             :recent-keys recent :describe-pending pending
             :live-keys (fn [] {:k 1})
             :print-lines! (fn [lines] (swap! log conj [:print lines]))}]
    (with-redefs [g/gdk-keyval-to-lower identity
                  g/keyval-name (constantly "F5")
                  g/printable-key? (constantly false)
                  editor/physical-key (constantly ::phys)
                  plugin-registry/window-bindings (constantly [])
                  plugin-registry/commands (constantly {})
                  cmd-registry/run-command! (constantly :handled)
                  cmd-registry/window-answer (fn [_bindings keyval state opts]
                                               (swap! log conj [:answer keyval state (:physical opts)])
                                               ((:run! opts) {:command :x})
                                               1)
                  describe/key-matches (fn [m]
                                         (swap! log conj [:matches (:keyval m) (:state m) (:physical m) (:k m)])
                                         [::m])
                  describe/describe-lines (fn [c m] [:lines c m])]
      (let [f (#'win-keys/on-window-key ctx)]
        (is (= 1 (f nil 65 0 0 nil)))
        (is (= [[:answer 65 0 ::phys]] @log))
        (is (= [{:chord (describe/chord-text "F5" 0 os) :command :x}] @recent))
        (reset! log [])
        (reset! recent [])
        (reset! pending true)
        (is (= 1 (f nil 65 0 0 nil)))
        (is (false? @pending))
        (is (= [[:matches 65 0 ::phys 1]
                [:print [:lines (describe/chord-text "F5" 0 os) [::m]]]]
               @log))
        (is (= [] @recent))))))
