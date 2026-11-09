(ns jonquil.gtk.window.plugin-agent-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [jonquil.gtk.window.plugin-agent :as win-plugin-agent]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(defn- settings-path []
  (str (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-plugin-agent-test-"))) "/settings.edn"))

(defn- with-hooks
  "Runs f with plugin-console, plugin-reviews-changed and
  plugin-history-shown set to console, changed and shown, then puts back
  the values they had."
  [{:keys [console changed shown]} f]
  (let [console* @#'win-plugin-agent/plugin-console changed* @#'win-plugin-agent/plugin-reviews-changed shown* @#'win-plugin-agent/plugin-history-shown
        old [@console* @changed* @shown*]]
    (try
      (reset! console* console) (reset! changed* changed) (reset! shown* shown)
      (f)
      (finally (reset! console* (old 0)) (reset! changed* (old 1)) (reset! shown* (old 2))))))

(deftest plugin-step-hands-the-window-its-lines-test
  (let [printed (atom []) got (promise) done (promise)]
    (with-hooks {:console (fn [l] (swap! printed conj l))
                 :changed (fn [m] (deliver got m) (deliver (:done m) true))
                 :shown nil}
      (fn []
        (let [a (#'win-plugin-agent/plugin-step! {:settings-path (settings-path) :step (fn [] ["one" "two"])
                                                  :open? true :after ::after :done done})]
          (is (true? (deref done 10000 false)))
          (is (true? (await-for 10000 a)))
          (is (= ["one" "two"] @a)))))
    (let [m (deref got 0 nil)]
      (is (= ["one" "two"] @printed))
      (is (= #{:reviews :rows :history :open? :open-list? :open-history? :lines :after :done} (set (keys m))))
      (is (= {:open? true :open-list? nil :open-history? nil :lines ["one" "two"] :after ::after :history nil}
             (select-keys m [:open? :open-list? :open-history? :lines :after :history]))))))

(deftest plugin-step-says-a-failed-review-test
  (let [printed (atom []) done (promise)]
    (with-hooks {:console (fn [l] (swap! printed conj l))
                 :changed (fn [_] (throw (ex-info "boom" {})))
                 :shown nil}
      (fn []
        (let [a (#'win-plugin-agent/plugin-step! {:settings-path (settings-path) :step (fn [] ["one"]) :done done})]
          (is (true? (deref done 10000 false)))
          (is (true? (await-for 10000 a)))
          (is (= ["one"] @a)))))
    (is (= ["one" "reading the plugin proposals failed: boom"] @printed))))

(deftest plugin-step-reads-the-open-history-test
  (let [got (promise) done (promise)]
    (with-hooks {:console (fn [_]) :changed (fn [m] (deliver got m) (deliver (:done m) true)) :shown "demo"}
      (fn []
        (#'win-plugin-agent/plugin-step! {:settings-path (settings-path) :step (fn [] []) :open-history? true :done done})
        (is (true? (deref done 10000 false)))))
    (is (= "demo" (:id (:history (deref got 0 nil)))))
    (is (true? (:open-history? (deref got 0 nil))))))
