(ns jonquil.cljd-test
  "Reply shapes here are copied from the C0 spike's probe output against the
  nrepl-poc fork (2026-09-28), with :id and :session dropped as the probes
  printed them."
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.cljd :as cljd]))

(def drive-ns-form
  "(ns demo.deck.drive\n  \"Drive the deck.\"\n  (:require\n    [\"dart:io\" :as io]\n    [cljd.string :as str]))")

(deftest ns-form-test
  (is (= {:name "demo.deck.drive" :text drive-ns-form}
         (cljd/ns-form (str ";; header (ns not.this)\n" drive-ns-form "\n\n(defn where [] 1)\n"))))
  (testing "metadata before the name is skipped"
    (is (= "a.b" (:name (cljd/ns-form "(ns ^:no-doc a.b)")))))
  (testing "only a top-level ns form counts"
    (is (nil? (cljd/ns-form "(comment (ns a.b))")))
    (is (nil? (cljd/ns-form "\"(ns a.b)\" ; (ns c.d)")))
    (is (= "e.f" (:name (cljd/ns-form "(comment (ns a.b))\n(ns e.f)")))))
  (testing "none, blank, or never closed: nil"
    (is (nil? (cljd/ns-form "(defn f [] 1)")))
    (is (nil? (cljd/ns-form "")))
    (is (nil? (cljd/ns-form nil)))
    (is (nil? (cljd/ns-form "(ns a.b\n  (:require [x.y :as z])")))))

(deftest target-ns-test
  (is (= "demo.deck.drive" (:name (cljd/target-ns drive-ns-form))))
  (is (= {:name "cljd.user" :text "(ns cljd.user)"} (cljd/target-ns "(where)"))))

(deftest parse-port-test
  (is (= 61151 (cljd/parse-port "61151\n")) "the fork writes the port and a newline")
  (is (= 61151 (cljd/parse-port "  61151  ")))
  (doseq [bad ["" "\n" "abc" "0" "-1" "70000" "61151 61152" nil]]
    (is (nil? (cljd/parse-port bad)) (pr-str bad))))

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
                                         {:ns "cljd.user"} {:status ["done" "eval-error"]}] false)))))

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
      "Stop's detach closes the socket, and that reads as a Stop"))
