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
  (is (= ["demo.core/inner  core.clj:2" "    x = 21" "    y = 42" "user/fn--2  (typed at the prompt)"]
         (mapv :text (debugger/rows (debugger/frames-data div-zero {:frames-fn fake})))))
  (is (= [:frame :local :local :frame]
         (mapv :kind (debugger/rows (debugger/frames-data div-zero {:frames-fn fake})))))
  (is (= {:kind :local :frame 0 :name "y" :text "    y = 42"}
         (nth (debugger/rows (debugger/frames-data div-zero {:frames-fn fake})) 2))))

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
