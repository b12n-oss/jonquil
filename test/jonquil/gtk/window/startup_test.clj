(ns jonquil.gtk.window.startup-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.gtk.window.quit :as win-quit]
            [jonquil.gtk.window.session :as win-session]
            [jonquil.gtk.window.startup :as win-startup]
            [jonquil.gtk.window.tabs :as win-tabs]
            [jonquil.parts :as parts]))

(deftest connect-quit-hands-quit-the-close-map-test
  (let [log (atom []) l (fn [x] (swap! log conj x)) got (atom nil)
        live? (atom true) restoring? (atom false)
        ctx* (atom {:id ::ctx :session-live? live? :session-restoring? restoring?})
        in {:tabs ::tabs :cv ::cv :win ::win :app ::app :confirmed-close? ::confirmed :suppress-close? ::suppress
            :closing? ::closing :popovers ::popovers :ev* ::ev* :part-contribution ::contribution
            :part-error! ::part-error :tab-ctx* ctx* :x 1}]
    (with-redefs [win-quit/connect! (fn [m] (reset! got m))
                  win-tabs/select-tab! (fn [c id] (l [:select (:id c) id]))
                  parts/run-hooks! (fn [c k args err] (l [:hooks c k args err]))
                  win-session/save-session! (fn [c] (l [:save (:id c)]))]
      (win-startup/connect-quit! in)
      (is (= #{:tabs :select! :cv :win :app :confirmed-close? :suppress-close? :closing? :popovers :ev* :save-session!}
             (set (keys @got))))
      (is (= (select-keys in [:tabs :cv :win :app :confirmed-close? :suppress-close? :closing? :popovers :ev*])
             (dissoc @got :select! :save-session!)))
      ((:select! @got) {:id 7})
      ((:save-session! @got))
      (is (and (false? @live?) (false? @restoring?)))
      (reset! restoring? true)
      ((:save-session! @got)))
    (is (= [[:select ::ctx 7] [:hooks ::contribution :on-quit [] ::part-error] [:save ::ctx]
            [:hooks ::contribution :on-quit [] ::part-error]]
           @log))))
