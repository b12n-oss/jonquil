(ns jonquil.gtk.window.alerts-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.gtk.window.alerts :as win-alerts]))

(deftest complete-alert-routes-each-dialog-once-test
  (let [released (atom []) chosen (atom [])
        release* @#'win-alerts/alert-release! pending* @#'win-alerts/alert-pending? old @release* old-pending @pending*]
    (try
      (reset! release* (fn [d] (swap! released conj d)))
      (reset! pending* true)
      (#'win-alerts/register-alert-choice! ::a (fn [r] (swap! chosen conj [::a r])))
      (#'win-alerts/register-alert-choice! ::b (fn [r] (swap! chosen conj [::b r])))
      (#'win-alerts/complete-alert! ::b {:index 1})
      (#'win-alerts/complete-alert! ::b {:index 0})
      (#'win-alerts/complete-alert! ::a {:error "dismissed"})
      (is (= [[::b {:index 1}] [::a {:error "dismissed"}]] @chosen))
      (is (= [::b ::a] @released))
      (is (false? @pending*))
      (finally (reset! release* old) (reset! pending* old-pending)
               (swap! @#'win-alerts/alert-on-choice-by-dialog dissoc ::a ::b)))))

(deftest complete-alert-releases-when-the-choice-throws-test
  (let [released (atom []) release* @#'win-alerts/alert-release! pending* @#'win-alerts/alert-pending? old @release* old-pending @pending*]
    (try
      (reset! release* (fn [d] (swap! released conj d)))
      (reset! pending* true)
      (#'win-alerts/register-alert-choice! ::c (fn [_] (throw (ex-info "boom" {}))))
      (is (thrown? Exception (#'win-alerts/complete-alert! ::c {:index 0})))
      (is (= [::c] @released))
      (is (false? @pending*))
      (finally (reset! release* old) (reset! pending* old-pending)))))
