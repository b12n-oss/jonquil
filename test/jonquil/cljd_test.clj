(ns jonquil.cljd-test
  "Reply shapes here are copied from probe output against the
  ClojureDart nREPL fork (2026-09-28), with :id and :session dropped as the
  probes printed them."
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.cljd :as cljd]
            [jonquil.dialect :as dialect]))

(def drive-ns-form
  "(ns demo.deck.drive\n  \"Drive the deck.\"\n  (:require\n    [\"dart:io\" :as io]\n    [cljd.string :as str]))")

(deftest ns-form-test
  (is (= {:name "demo.deck.drive" :text drive-ns-form}
         (cljd/ns-form (str ";; header (ns not.this)\n" drive-ns-form "\n\n(defn where [] 1)\n"))))
  (testing "metadata before the name is skipped"
    (is (= "a.b" (:name (cljd/ns-form "(ns ^:no-doc a.b)")))))
  (testing "^{...} metadata before the name is skipped too, and it can stack"
    (is (= "my.app" (:name (cljd/ns-form "(ns ^{:doc \"x\"} my.app (:require [a :as b]))"))))
    (is (= "my.app" (:name (cljd/ns-form "(ns ^:a ^{:doc \"x\"} my.app)")))))
  (testing "only a top-level ns form counts"
    (is (nil? (cljd/ns-form "(comment (ns a.b))")))
    (is (nil? (cljd/ns-form "\"(ns a.b)\" ; (ns c.d)")))
    (is (= "e.f" (:name (cljd/ns-form "(comment (ns a.b))\n(ns e.f)")))))
  (testing "none, blank, or never closed: nil"
    (is (nil? (cljd/ns-form "(defn f [] 1)")))
    (is (nil? (cljd/ns-form "")))
    (is (nil? (cljd/ns-form nil)))
    (is (nil? (cljd/ns-form "(ns a.b\n  (:require [x.y :as z])"))))
  (testing "a discarded or quoted top-level ns form doesn't count"
    (is (= "real.ns" (:name (cljd/ns-form "#_(ns old.ns)\n(ns real.ns)"))))
    (is (= "real.ns" (:name (cljd/ns-form "`(ns fake.ns)\n(ns real.ns)"))))
    (is (= "real.ns" (:name (cljd/ns-form "'(ns fake.ns)\n(ns real.ns)")))))
  (testing "a header comment ending in a prefix character isn't a prefix"
    (is (= "foo.bar" (:name (cljd/ns-form ";; see 'README'\n(ns foo.bar)\n"))))
    (is (= "foo.bar" (:name (cljd/ns-form ";; count @\n(ns foo.bar)"))))))

(deftest target-ns-test
  (is (= "demo.deck.drive" (:name (cljd/target-ns drive-ns-form))))
  (is (= {:name "cljd.user" :text "(ns cljd.user)"} (cljd/target-ns "(where)")))
  (is (= {:name "cljd.user" :text "(ns cljd.user)"}
         (cljd/target-ns "(ns a.b\n  (:require [x.y :as z])"))
      "an unclosed ns form gives cljd.user"))

(deftest probe-result-test
  (is (= "(+ 1 2)" cljd/probe-code))
  (is (= :awake (cljd/probe-result [{:out "ClojureDart\n"} {:ns "cljd.user"} {:value "3"} {:ns "cljd.user"} {:status ["done"]}]))
      "a fresh session's measured first answer")
  (testing "not awake yet: the printed nil, or no value, with no err"
    (is (= :asleep (cljd/probe-result [{:out "ClojureDart\n"} {:ns "cljd.user"} {:value "nil"} {:ns "cljd.user"} {:status ["done"]}])))
    (is (= :asleep (cljd/probe-result [{:ns "cljd.user"} {:status ["done"]}]))))
  (is (= :lost (cljd/probe-result [{:status ["done" "jonquil-disconnected"]}])))
  (testing "an error is a failure, never retried"
    (is (= :failed (cljd/probe-result [{:err "boom"} {:status ["done" "eval-error"]}])))
    (is (= :failed (cljd/probe-result [{:value "4"} {:status ["done"]}])))))

(deftest eval-result-test
  (is (= {:status :ok :printed "{:title \"Demo deck\", :step 2, :slide 21, :of 54}" :ns 'demo.deck.drive}
         (cljd/eval-result [{:value "{:title \"Demo deck\", :step 2, :slide 21, :of 54}"}
                            {:ns "demo.deck.drive"} {:status ["done"]}] false)))
  (is (= {:status :error :error-text "Lost the connection to the app."}
         (cljd/eval-result [{:status ["done" "jonquil-disconnected"]}] false)))
  (is (= :interrupted (:status (cljd/eval-result [{:status ["done" "jonquil-disconnected"]}] true))))
  (is (= "Error: Unhandled exception:"
         (:error-text (cljd/eval-result [{:err "Unhandled exception:\nException: boom\n#0      Eval ()\n"}
                                         {:ns "cljd.user"} {:status ["done" "eval-error"]}] false))))
  (testing "a cljd Stop never interrupts, so an eval-error is always the app's own"
    (is (= :error (:status (cljd/eval-result [{:err "boom"} {:status ["done" "eval-error"]}] true))))))

(def ^:private drive-load
  ;; probe_drive.out's load-file replies, cut to three forms.
  [{:out "ClojureDart\n\nUse `(pick!)` ...\n"} {:ns "cljd.user"} {:ns "demo.deck.drive"}
   {:value "Closure: () => dynamic from Function 'where': static."} {:ns "demo.deck.drive"}
   {:value "nil"} {:ns "demo.deck.drive"}
   {:value "#object[cljd.core.Atom {:val nil}]"} {:ns "demo.deck.drive"}
   {:status ["done"]}])

(def ^:private format-load
  ;; probe_mutating.out's format.cljd load, cut to two values, then the stop.
  [{:ns "demo.deck.format"}
   {:value "Closure: (dynamic) => dynamic from Function 'parse_slide_type': static."} {:ns "demo.deck.format"}
   {:value "Closure: (dynamic) => dynamic from Function 'parse_v2_slide': static."} {:ns "demo.deck.format"}
   {:err "Error:  nil\n"} {:ns "demo.deck.format"} {:status ["done" "eval-error"]}])

(deftest load-result-test
  (is (= {:status :notice :text "; Loaded demo.deck.drive (3 forms)"}
         (cljd/load-result drive-load "demo.deck.drive" false)))
  (is (= "; Loaded x.y (1 form)"
         (:text (cljd/load-result [{:value "nil"} {:status ["done"]}] "x.y" false))))
  (is (= {:status :error :error-text "Load stopped at form 3 of demo.deck.format"}
         (cljd/load-result format-load "demo.deck.format" false)))
  (is (= {:status :error :error-text "Lost the connection to the app."}
         (cljd/load-result [{:value "nil"} {:status ["done" "jonquil-disconnected"]}] "x.y" false)))
  (is (= {:status :interrupted}
         (cljd/load-result [{:value "nil"} {:status ["done" "jonquil-disconnected"]}] "x.y" true))
      "Stop's detach closes the socket, and that reads as a Stop")
  (is (= {:status :error :error-text "Load stopped at form 2 of x.y"}
         (cljd/load-result [{:ns "x.y"} {:value "1"} {:err "boom"} {:status ["done" "eval-error"]}] "x.y" true))
      "a cljd Stop never sends interrupt, so an eval-error in the load's own replies is never caused by Stop"))

(deftest doc-result-test
  (testing "found (fork 685e56de: nREPL's stock info shape, no file or line)"
    (is (= {:status :found :symbol "map" :ns "cljd.core" :name "map" :arglists "([f] [f coll])" :doc "Returns a lazy seq."}
           (cljd/doc-result "map" [{:info {"ns" "cljd.core" "name" "map" "doc" "Returns a lazy seq."
                                           "arglists" "([f] [f coll])" "arglists-str" "([f] [f coll])"}
                                    :status ["done"]}])))
    (is (:macro? (cljd/doc-result "when" [{:info {"ns" "cljd.core" "name" "when" "macro" "true"} :status ["done"]}]))))
  (is (= {:status :no-docs :symbol "nope" :name "nope" :doc "No docs for nope"}
         (cljd/doc-result "nope" [{:status ["done" "no-info"]}])))
  (testing "lookup-error (the older fork 573781a2) and unknown-op: the older-fork sentence"
    (is (= cljd/no-lookup-doc (:doc (cljd/doc-result "map" [{:status ["done" "lookup-error"]}]))))
    (is (= cljd/no-lookup-doc (:doc (cljd/doc-result "map" [{:err "Unknown op: lookup" :status ["done" "unknown-op"]}]))))))

(deftest buffer-aliases-test
  (is (= ["io" "str"] (cljd/buffer-aliases drive-ns-form)))
  (is (= ["f" "m"] (cljd/buffer-aliases "(ns a (:require [cljd.flutter :as f] [\"package:flutter/material.dart\" :as m] [x.y :as-alias f]))")))
  (is (= [] (cljd/buffer-aliases "(defn f [])"))))

(deftest candidates-test
  (let [text (str "(ns demo.app (:require [cljd.flutter :as f] [\"dart:io\" :as io]))\n"
                  "(defn where-now [] 1)\n(defn window-size [] 2)\n(f/widget :padding 8)\n")
        syms (fn [prefix & [extra]] (mapv :symbol (cljd/candidates dialect/cljd prefix text 0 extra)))]
    (testing "the buffer's own names, then ClojureDart's forms"
      (is (= ["where-now" "window-size" "when"] (take 3 (syms "w"))) "buffer names sort first")
      (is (some #{"widget"} (syms "w")))
      (is (= :user (:category (first (cljd/candidates dialect/cljd "where" text 0 nil))))))
    (testing "aliases from the ns form, and the aliased flutter forms"
      (is (= ["f/"] (take 1 (syms "f"))))
      (is (some #{"f/widget" "f/build" "f/$"} (syms "f/")))
      (is (some #{"io/"} (syms "i"))))
    (testing "cljd's own forms are special; Jolt's builtins stay builtins"
      (let [by-sym (into {} (map (juxt :symbol :category)) (cljd/candidates dialect/cljd "" text 0 nil))]
        (is (= :special (by-sym "await")))
        (is (= :special (by-sym "f/widget")))
        (is (= :builtin (by-sym "map")))))
    (testing "the REPL history counts as extra context"
      (is (some #{"from-history"} (syms "from" "(from-history 1)"))))
    (testing "nothing from the in-process Jolt runtime: a clojure.core var no profile set names"
      (is (empty? (syms "ns-publics")))
      (is (empty? (syms "requiring-resolve"))))))

(deftest buffer-ns-refs-test
  (is (= {:aliases {"str" "clojure.string" "cs" "clojure.set"}
          :refers {"union" "clojure.set" "diff" "clojure.set"}}
         (cljd/buffer-ns-refs
          "(ns my.app (:require [clojure.string :as str]\n [clojure.set :as cs :refer [union diff]]))\n(defn f [x] x)")))
  (is (= {:aliases {} :refers {}} (cljd/buffer-ns-refs "(+ 1 2)")))
  (is (= {:aliases {} :refers {}} (cljd/buffer-ns-refs nil)))
  ;; junk never throws: unclosed form, :refer :all, metadata
  (is (map? (cljd/buffer-ns-refs "(ns a (:require [b.c :as")))
  (is (= {} (:refers (cljd/buffer-ns-refs "(ns a (:require [b.c :refer :all]))"))))
  (is (= {"m" "b.c"} (:aliases (cljd/buffer-ns-refs "(ns a (:require ^:keep [b.c :as m]))")))))

(deftest candidate-sort-key-test
  (is (= [0 0 "map" "map"] (cljd/candidate-sort-key "ma" {:symbol "map" :category :user})))
  (is (= [1 3 "mapcat" "mapcat"] (cljd/candidate-sort-key "x" {:symbol "mapcat" :category :core})))
  (is (= [1 4 "a.b/map" "a.b/map"] (cljd/candidate-sort-key "x" {:symbol "a.b/map" :category :remote}))))
