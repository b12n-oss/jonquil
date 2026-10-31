(ns jonquil.debugger-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.debugger :as debugger]))

(def ^:private fake-frames
  [{:fn 'demo.core/inner :file "demo/core.clj" :line 2 :locals {'y 42 'x 21}}
   {:fn 'user/fn--2 :file nil :line 1 :locals {}}])

(defn- fake [_] fake-frames)

(defn- caught [f] (try (f) nil (catch Throwable e e)))

(def ^:private div-zero (caught #(/ 1 0)))

(deftest title-test
  (is (= "ArithmeticException: Divide by zero"
         (:title (debugger/frames-data div-zero {:frames-fn fake}))))
  (is (= "ExceptionInfo: boom {:k 1}"
         (:title (debugger/frames-data (ex-info "boom" {:k 1}) {:frames-fn fake})))))

(deftest frames-and-locals-test
  (let [d (debugger/frames-data div-zero {:frames-fn fake})]
    (is (= ["demo.core/inner" "user/fn--2"] (mapv :fn (:frames d))))
    (is (= [{:name "x" :text "21"} {:name "y" :text "42"}] (:locals (first (:frames d)))))
    (is (= [nil 1] [(:file (second (:frames d))) (:line (second (:frames d)))]))))

(deftest rows-test
  (is (= ["demo.core/inner  core.clj:2" "    x = 21" "    y = 42" "user/fn--2  line 1 (no file)"]
         (mapv :text (debugger/rows (debugger/frames-data div-zero {:frames-fn fake})))))
  (is (= [:frame :local :local :frame]
         (mapv :kind (debugger/rows (debugger/frames-data div-zero {:frames-fn fake})))))
  (is (= {:kind :local :frame 0 :name "y" :text "    y = 42"}
         (nth (debugger/rows (debugger/frames-data div-zero {:frames-fn fake})) 2))))

(deftest no-file-no-line-test
  (is (= "f  (no file)"
         (:text (first (debugger/rows {:title "t" :frames [{:fn "f" :file nil :line nil :locals []}]}))))))

(deftest harness-frames-cut-test
  (testing "the list stops at the evaluator's own frames"
    (is (= ["user/dbg-inner" "user/fn--3"]
           (mapv :fn (:frames (debugger/frames-data
                               div-zero
                               {:frames-fn (constantly [{:fn 'user/dbg-inner :locals {}} {:fn 'user/fn--3 :locals {}}
                                                        {:fn "jonquil.eval/eval-async/fn--7" :locals {}}
                                                        {:fn 'user/after :locals {}}])})))))
    (is (= ["user/x"]
           (mapv :fn (:frames (debugger/frames-data
                               div-zero
                               {:frames-fn (constantly [{:fn 'user/x :locals {}} {:fn 'nrepl.middleware.session/run-eval :locals {}}
                                                        {:fn 'jolt.nrepl/evaluate :locals {}}])}))))))
  (testing "a fault inside the harness itself keeps every frame"
    (is (= ["jonquil.eval/eval-async/fn--7" "user/y"]
           (mapv :fn (:frames (debugger/frames-data
                               div-zero
                               {:frames-fn (constantly [{:fn "jonquil.eval/eval-async/fn--7" :locals {}}
                                                        {:fn 'user/y :locals {}}])})))))))

(deftest resolve-file-test
  (let [d (debugger/frames-data div-zero {:frames-fn fake :resolve-file #(str "/abs/" %)})]
    (is (= "/abs/demo/core.clj" (:file (first (:frames d)))))
    (is (nil? (:file (second (:frames d)))) "no file stays nil")))

(deftest no-frames-api-test
  (let [d (debugger/frames-data div-zero {:frames-fn nil})]
    (is (= "ArithmeticException: Divide by zero" (:title d)))
    (is (= [{:kind :note :text "Frames need a Jolt with jolt.debug (newer than this one)."}]
           (debugger/rows d)))))

(deftest no-frames-test
  (is (= [{:kind :note :text "No frames: the error came from code compiled without them."}]
         (debugger/rows (debugger/frames-data div-zero {:frames-fn (constantly [])}))))
  (is (= [{:kind :note :text "No error yet."}] (debugger/rows {:note "No error yet."}))))

(deftype Unprintable [])
(defmethod print-method Unprintable [_ _] (throw (Exception. "no printing")))

(deftest value-text-test
  (testing "an infinite seq prints bounded"
    (let [t (debugger/value-text (range))]
      (is (< (count t) 120) t)
      (is (str/ends-with? t "...)") t)))
  (testing "a long string is cut to 100 characters"
    (let [t (debugger/value-text (apply str (repeat 500 "a")))]
      (is (= 101 (count t)))
      (is (str/ends-with? t "…"))))
  (testing "a value whose printing throws"
    (is (str/starts-with? (debugger/value-text (Unprintable.)) "<unprintable: "))))

(deftest local-value-test
  (is (= 21 (debugger/local-value div-zero 0 "x" {:frames-fn fake})))
  (is (= ::debugger/missing (debugger/local-value div-zero 0 "z" {:frames-fn fake})))
  (is (= ::debugger/missing (debugger/local-value div-zero 9 "x" {:frames-fn fake}))))

(deftest real-frames-test
  (if-not (debugger/available?)
    (is true "skipped: this Jolt has no jolt.debug")
    (let [instrument! (requiring-resolve 'jolt.profile/instrument!)]
      (instrument! :debug)
      (try
        (let [f (eval (read-string "(fn [q] (let [r (str q)] (/ (count r) 0)))"))
              e (caught #(f :abc))
              d (debugger/frames-data e {:frames-fn (debugger/frames-fn)})]
          (is (some (fn [fr] (some #(= "q" (:name %)) (:locals fr))) (:frames d)) (pr-str d)))
        (finally (instrument! false))))))

(deftest lazy-locals-stay-unrealized-test
  (let [calls (atom 0)
        lazy (map (fn [x] (swap! calls inc) x) [1 2 3])]
    (is (= "<lazy seq, not realized>" (debugger/value-text lazy)))
    (is (= "[1 <lazy seq, not realized>]" (debugger/value-text [1 lazy])))
    (is (= "{:xs <lazy seq, not realized>}" (debugger/value-text {:xs lazy})))
    (is (= 0 @calls) "opening the panel ran none of the program's code")
    (doall lazy)
    (is (= "(1 2 3)" (debugger/value-text lazy)) "a realized one prints")))

(deftest toggle-debug-mode-test
  (if (debugger/available?)
    (try
      (is (= {:on? true} (debugger/toggle-debug-mode!)))
      (is (= {:on? false} (debugger/toggle-debug-mode!)))
      (finally (debugger/set-debug-mode! false)))
    (is (= {:error "Frames need a Jolt with jolt.debug (newer than this one)."} (debugger/toggle-debug-mode!)))))

(deftest out-of-stack-note-test
  (testing "no frames from running out of stack or memory says why, not 'compiled without them'"
    (doseq [e [(java.lang.OutOfMemoryError. "jolt: out of memory") (java.lang.StackOverflowError. "stack limit")]]
      (is (= [{:kind :note :text "No frames: Jolt ran out of stack or memory and kept none. With debug mode on, deep recursion that doesn't use recur ends this way."}]
             (debugger/rows (debugger/frames-data e {:frames-fn (constantly [])})))
          (str (class e)))))
  (testing "a stack overflow that kept its frames lists them"
    (is (= :frame (:kind (first (debugger/rows (debugger/frames-data (java.lang.StackOverflowError. "s")
                                                                     {:frames-fn (constantly [{:fn 'user/f :line 1 :locals {}}])}))))))))
