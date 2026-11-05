(ns jonquil.gtk.messages-pane-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.gtk.messages-pane :as mp]
            [jonquil.messages :as m]))

(def ^:private at-ten (constantly "10:46:40"))

(deftest row-text-shape
  (is (= "10:46:40  agent  Claude Code evaluated code"
         (mp/row-text {:kind :agent :summary "Claude Code evaluated code" :at 1} {} at-ten)))
  (is (= "10:46:40  status  Ready ×3"
         (mp/row-text {:kind :status :summary "Ready" :repeat 3 :at 1} {} at-ten))))

(deftest row-text-resolves-tab
  (let [e {:kind :agent :summary "Claude Code replaced the text of tab 3" :summary-tab 3 :at 1}]
    (is (= "10:46:40  agent  Claude Code replaced the text of core.clj"
           (mp/row-text e {3 "core.clj"} at-ten)))
    (is (= "10:46:40  agent  Claude Code replaced the text of a closed tab"
           (mp/row-text e {} at-ten)))))

(deftest row-text-is-literal
  (is (= "10:46:40  agent  <b>x</b> & y"
         (mp/row-text {:kind :agent :summary "<b>x</b> & y" :at 1} {} at-ten))))

(deftest error-row-mark
  (is (= "! 10:46:40  agent  Agent saved tab 1, which failed: no"
         (mp/row-text {:kind :agent :summary "Agent saved tab 1, which failed: no" :error? true :at 1} {} at-ten))))

(deftest expanded-row-shows-detail
  (is (= "10:46:40  agent  Agent evaluated code\n(+ 1 2)\n\n3"
         (mp/row-text {:kind :agent :summary "Agent evaluated code" :detail "(+ 1 2)\n\n3" :at 1} {} at-ten
                      {:expanded? true}))))

(deftest footer-dropped
  (let [l (reduce (fn [l i] (m/add l {:kind :agent :summary (str i)} i)) (m/empty-log) (range 1003))]
    (is (= "1000 entries, 3 dropped" (mp/footer-text l m/SHOWN-KINDS))))
  (is (= "1 entry" (mp/footer-text (m/add (m/empty-log) {:kind :agent :summary "a"} 1) m/SHOWN-KINDS))))

(deftest footer-empty
  (is (= "No messages yet. Agent actions, connections and restarts show up here."
         (mp/footer-text (m/empty-log) m/SHOWN-KINDS)))
  (is (= "No messages yet. Agent actions, connections and restarts show up here."
         (mp/footer-text (m/add (m/empty-log) {:kind :status :summary "Ready"} 1) m/SHOWN-KINDS))
      "only hidden kinds"))

(deftest clock-text-is-hh-mm-ss
  (is (re-matches #"\d\d:\d\d:\d\d" (mp/clock-text 1762300000000))))
