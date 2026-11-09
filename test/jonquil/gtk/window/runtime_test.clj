(ns jonquil.gtk.window.runtime-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.dialect :as dialect]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.window.runtime :as win-runtime]
            [jonquil.nrepl.connection :as connection]))

(defn- attached
  "An attach evaluator whose connection is up? (and with limits, when given)."
  [up? limits]
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify connection/Attached
    (connected? [_] up?)
    ev/Evaluator
    (limitations [_] limits)))

(deftest select-for-says-an-unreadable-project-test
  (with-redefs [dialect/find-project (fn [& _] (throw (ex-info "boom" {})))]
    (is (= (update (dialect/select dialect/profiles nil nil nil) :warnings conj "couldn't read the project: boom")
           (#'win-runtime/select-for "/p/x.clj" "/nonexistent/settings.edn")))))

(deftest home-relative-test
  (let [home (System/getProperty "user.home")]
    (is (= "~/p/q" (#'win-runtime/home-relative (str home "/p/q"))))
    (is (= home (#'win-runtime/home-relative home)))
    (is (= "/elsewhere/q" (#'win-runtime/home-relative "/elsewhere/q")))))

(deftest dialect-text-says-the-connection-test
  (let [sel {:profile {:name "Demo" :runtime-names {:attach "Demo app"}} :runtime :attach}]
    (is (= "Demo app (attached)" (#'win-runtime/dialect-text sel (attached true nil))))
    (is (= "Demo app (not running)" (#'win-runtime/dialect-text sel (attached false nil))))
    (is (= "Demo app (not running)" (#'win-runtime/dialect-text sel ::not-attached)))))

(deftest show-selection-says-each-warning-test
  (let [got (atom []) sel {:profile {:name "Demo" :evaluator :in-process} :runtime :in-process :warnings ["a" "b"]}]
    (with-redefs [g/gtk-label-set-text (fn [l t] (swap! got conj [:label l t]))
                  cview/append! (fn [cv k t] (swap! got conj [:append cv k t]))]
      (#'win-runtime/show-selection! ::cv ::label sel))
    (is (= [[:label ::label (dialect/label sel)]
            [:append ::cv :system "; project: a\n"] [:append ::cv :system "; project: b\n"]]
           @got))))

(deftest apply-reset-toggle-follows-the-decision-test
  (let [got (atom []) state (atom {:saved true})]
    (with-redefs [dialect/reset-toggle (fn [s sel active?] (swap! got conj [:decide s sel active?])
                                         {:state {:saved false} :active? false :sensitive? true :tooltip "t"})
                  g/gtk-check-button-get-active (fn [_] 1)
                  g/gtk-check-button-set-active (fn [b v] (swap! got conj [:active b v]))
                  g/gtk-widget-set-sensitive (fn [b v] (swap! got conj [:sensitive b v]))
                  g/gtk-widget-set-tooltip-text (fn [b t] (swap! got conj [:tooltip b t]))]
      (#'win-runtime/apply-reset-toggle! ::chk state ::sel))
    (is (= [[:decide {:saved true} ::sel true] [:active ::chk 0] [:sensitive ::chk 1] [:tooltip ::chk "t"]] @got))
    (is (= {:saved false} @state))))

(deftest start-and-report-says-an-attach-runtimes-limits-test
  (let [got (atom []) e (attached false ["no x" "no y"])]
    (with-redefs [cview/append! (fn [_ k t] (swap! got conj [k t]))]
      (#'win-runtime/start-and-report! ::cv ::label e {:profile {:experimental true :experimental-message "; Experimental."}})
      (#'win-runtime/start-and-report! ::cv ::label e {:profile {}})
      (is (nil? (#'win-runtime/start-and-report! ::cv ::label ::in-process {:profile {}}))))
    (is (= [[:system "; Experimental.\n"] [:system "; Limit: no x\n"] [:system "; Limit: no y\n"]
            [:system "; Limit: no x\n"] [:system "; Limit: no y\n"]]
           @got))))
