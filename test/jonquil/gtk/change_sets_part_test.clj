(ns jonquil.gtk.change-sets-part-test
  "Apply to REPL's steps (jonquil.gtk.parts.change-sets), the branches the
  window row doesn't reach: why an entry can't be sent, and how an
  evaluation's answer goes on or stops."
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.callbacks]
            [jonquil.gtk.parts.change-sets :as part]))

(defn- evaluator
  "An evaluator that only says whether it is busy."
  [busy?]
  (reify ev/Evaluator
    (start! [_] nil)
    (stop! [_] nil)
    (eval! [_ _ _] nil)
    (interrupt! [_] nil)
    (evaluating? [_] busy?)
    (waiting-for-input? [_] false)
    (send-input! [_ _] nil)
    (send-eof! [_] nil)
    (history [_] nil)
    (prompt-ns [_] 'user)
    (set-mode! [_ _] nil)
    (complete [_ _] nil)
    (lookup [_ _ _ _] nil)
    (limitations [_] [])))

(def ^:private entry-blocked @#'part/entry-blocked)
(def ^:private on-entry-answer @#'part/on-entry-answer)

(deftest entry-blocked-test
  (let [tab {:path (atom "/p/a.clj")}
        ctx (fn [busy? foreign?] {:tabs (atom [tab]) :ev* (atom (evaluator busy?)) :cv nil :sel* nil
                                  :refuse-foreign! (fn [_] (when foreign? "refused"))})]
    (is (nil? (entry-blocked (ctx false false) {:path "/p/a.clj"} tab)))
    (is (= "its tab belongs to another project" (entry-blocked (ctx false true) {:path "/p/a.clj"} tab)))
    (is (nil? (entry-blocked (ctx false true) {:path "/p/other.clj"} tab))
        "a file with no open tab goes from the active tab, which isn't refused")
    (is (= "something is already running" (entry-blocked (ctx true false) {:path "/p/a.clj"} tab)))))

(deftest on-entry-answer-test
  (with-redefs [jonquil.gtk.callbacks/on-gui! (fn [f] (f))]
    (let [stops (atom [])
          step {:s {:name "x"} :entries [] :i 5 :stop! (fn [why] (swap! stops conj why))}
          answer (on-entry-answer {} step)]
      (testing "a busy evaluator stops the apply"
        (answer {:busy "busy"})
        (is (= ["the evaluator was busy"] @stops)))
      (testing "an end other than :ok stops it, naming the end"
        (answer {:done {:result {:status :error}}})
        (answer {:done {:result {}}})
        (is (= ["the evaluator was busy" "it ended error" "it ended badly"] @stops))))))
