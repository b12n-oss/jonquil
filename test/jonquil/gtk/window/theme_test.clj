(ns jonquil.gtk.window.theme-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.window.theme :as win-theme]
            [jonquil.gtk.window.status :as win-status]
            [jonquil.settings :as settings]
            [jonquil.theme :as theme]))

(defn- not-asked [] (throw (ex-info "asked the system" {})))

(deftest poll-tick-decisions-test
  (let [applied (atom [])]
    (with-redefs [win-theme/apply-window-theme! (fn [ctx k] (swap! applied conj k) (reset! (:theme-name ctx) k) nil)]
      (is (= 0 (#'win-theme/poll-tick! {:theme-choice (atom :dark) :closing? (atom false)
                                        :system-dark-fn* (atom not-asked) :theme-name (atom :dark)})))
      (is (= 1 (#'win-theme/poll-tick! {:theme-choice (atom :auto) :closing? (atom true)
                                        :system-dark-fn* (atom not-asked) :theme-name (atom :light)})))
      (is (= 1 (#'win-theme/poll-tick! {:theme-choice (atom :auto) :closing? (atom false)
                                        :system-dark-fn* (atom (fn [] nil)) :theme-name (atom :light)})))
      (is (= 1 (#'win-theme/poll-tick! {:theme-choice (atom :auto) :closing? (atom false)
                                        :system-dark-fn* (atom (fn [] true)) :theme-name (atom :dark)})))
      (is (= [] @applied))
      (let [tn (atom :light)]
        (is (= 1 (#'win-theme/poll-tick! {:theme-choice (atom :auto) :closing? (atom false)
                                          :system-dark-fn* (atom (fn [] true)) :theme-name tn})))
        (is (= [:dark] @applied))
        (is (= :dark @tn))))))

(deftest do-zoom-reloads-under-the-current-theme-test
  (let [loaded (atom []) zoom-px (atom 13) theme-name (atom :dark)]
    (with-redefs [win-theme/reload-code-css! (fn [p t px] (swap! loaded conj [p t px]))]
      (#'win-theme/do-zoom! ::provider zoom-px theme-name :in)
      (is (= 14 @zoom-px))
      (#'win-theme/do-zoom! ::provider zoom-px theme-name :reset)
      (is (= 13 @zoom-px)))
    (is (= [[::provider (theme/theme :dark) 14] [::provider (theme/theme :dark) 13]] @loaded))))

(deftest do-cycle-theme-saves-and-says-test
  (let [got (atom [])
        ctx (fn [choice busy?] {:cv ::cv :theme-choice (atom choice) :settings-path (atom "/s.edn")
                                :status-label ::label :evaluating? (constantly busy?)})]
    (with-redefs [win-theme/apply-choice! (fn [_ k] (swap! got conj [:choice k]) (theme/resolve-choice k true))
                  settings/save-settings! (fn [p m] (swap! got conj [:save p m]) (when (= :light (:theme m)) "disk full"))
                  cview/append! (fn [cv kind text] (swap! got conj [:append cv kind text]))
                  win-status/set-status! (fn [l t] (swap! got conj [:status l t]))]
      (#'win-theme/do-cycle-theme! (ctx :dark false))
      (#'win-theme/do-cycle-theme! (ctx :navy false))
      (#'win-theme/do-cycle-theme! (ctx :auto true)))
    (is (= [[:choice :solarized] [:save "/s.edn" {:theme :solarized}] [:status ::label "Theme: solarized"]
            [:choice :auto] [:save "/s.edn" {:theme :auto}] [:status ::label "Theme: auto (dark)"]
            [:choice :light] [:save "/s.edn" {:theme :light}] [:append ::cv :system "; settings: disk full\n"]]
           @got))))
