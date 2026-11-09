(ns jonquil.gtk.window.status-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.gtk.window.status :as win-status]))

(deftest log-at-routes-by-label-test
  (let [got (atom [])]
    (try
      (#'win-status/register-status-log! ::label (fn [e] (swap! got conj e)))
      (is (nil? (#'win-status/log-at! ::label {:kind :status :summary "a"})))
      (is (nil? (#'win-status/log-at! ::other {:kind :status :summary "b"})))
      (is (= [{:kind :status :summary "a"}] @got))
      (finally (swap! @#'win-status/status-logs dissoc ::label)))))

(deftest contain-click-runs-and-contains-test
  (let [said (atom []) got (atom [])]
    (with-redefs [win-status/set-status! (fn [label text] (swap! said conj [label text]))]
      (is (nil? ((#'win-status/contain-click ::label "run" (fn [b] (swap! got conj b))) ::button nil)))
      (is (nil? ((#'win-status/contain-click ::label "run" (fn [_] (throw (ex-info "boom" {})))) ::button nil))))
    (is (= [::button] @got))
    (is (= [[::label "; run: boom"]] @said))))
