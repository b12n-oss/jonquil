(ns jonquil.gtk.window.context-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.gtk.find-bar :as find-bar]
            [jonquil.gtk.window.context :as win-context]
            [jonquil.gtk.window.files :as win-files]
            [jonquil.gtk.window.inspector :as win-inspector]
            [jonquil.gtk.window.keys :as win-keys]
            [jonquil.gtk.window.tabs :as win-tabs]))

(deftest build-key-fns-makes-the-command-fns-test
  (let [log (atom []) l (fn [x] (swap! log conj x))
        tab (atom {:ed ::ed :path ::path :fb ::fb})
        ctx* (atom ::ctx)
        in {:cv ::cv :win ::win :active-tab (fn [] @tab) :ev* ::ev* :sel* ::sel* :status-label ::sl
            :right ::right :pane ::pane :inspector-state ::state :inspecting? ::inspecting? :inspect-queued ::queued
            :inspector-last-args ::last-args :on-view ::on-view :tab-ctx* ctx* :x 1}
        w (win-context/build-key-fns in)]
    (is (= (into (set (keys in))
                 #{:open-fn :save-fn :save-as-fn :inspect-last-ctx :inspect-last-fn :inspect-form-fn
                   :close-inspector-fn :toggle-focus-fn :clear-console-fn :clear-last-fn :open-find-fn
                   :open-replace-fn})
           (set (keys w))))
    (is (= {:ev* ::ev* :right ::right :pane ::pane :inspector-state ::state :inspecting? ::inspecting?
            :inspector-last-args ::last-args :on-view ::on-view :status-label ::sl :start [:start {}]}
           (:inspect-last-ctx w)))
    (with-redefs [win-files/do-open! (fn [cv win open!] (l [:open cv win]) (open! "/p.clj"))
                  win-tabs/open-file! (fn [c p] (l [:open-file c p]))
                  win-files/do-save! (fn [ed cv win path] (l [:save ed cv win path]))
                  win-files/do-save-as! (fn [ed cv win path] (l [:save-as ed cv win path]))
                  win-inspector/inspect-last! (fn [c] (l [:inspect-last (identical? c (:inspect-last-ctx w))]))
                  win-inspector/inspect-form! (fn [m] (l [:inspect-form (dissoc m :inspect-last-fn)
                                                          (identical? (:inspect-last-fn m) (:inspect-last-fn w))]))
                  win-inspector/close-inspector-panel! (fn [r ed] (l [:close-inspector r ed]))
                  win-keys/do-toggle-focus! (fn [win ed cv] (l [:toggle-focus win ed cv]))
                  win-keys/do-clear-console! (fn [cv] (l [:clear-console cv]))
                  win-keys/do-clear-last-output! (fn [cv] (l [:clear-last cv]))
                  find-bar/open! (fn [fb mode] (l [:find fb mode]))]
      ((:open-fn w))
      (reset! ctx* ::ctx2)
      (reset! tab {:ed ::ed2 :path ::path2 :fb ::fb2})
      ((:open-fn w))
      ((:save-fn w))
      ((:save-as-fn w))
      ((:inspect-last-fn w))
      ((:inspect-form-fn w))
      ((:close-inspector-fn w))
      ((:toggle-focus-fn w))
      ((:clear-console-fn w))
      ((:clear-last-fn w))
      ((:open-find-fn w))
      ((:open-replace-fn w)))
    (is (= [[:open ::cv ::win] [:open-file ::ctx "/p.clj"]
            [:open ::cv ::win] [:open-file ::ctx2 "/p.clj"]
            [:save ::ed2 ::cv ::win ::path2] [:save-as ::ed2 ::cv ::win ::path2]
            [:inspect-last true]
            [:inspect-form {:tab {:ed ::ed2 :path ::path2 :fb ::fb2} :cv ::cv :ev* ::ev* :sel* ::sel* :status-label ::sl
                            :inspecting? ::inspecting? :inspect-queued ::queued}
             true]
            [:close-inspector ::right ::ed2] [:toggle-focus ::win ::ed2 ::cv] [:clear-console ::cv] [:clear-last ::cv]
            [:find ::fb2 :find] [:find ::fb2 :replace]]
           @log))))
