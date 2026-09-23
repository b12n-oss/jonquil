(ns jonquil.console-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.console :as console]))

(deftest input-action-test
  (testing "shift always inserts a newline"
    (is (= :newline (console/input-action {:input "(+ 1 2)" :shift? true}))))
  (testing "stdin wins while the program waits"
    (is (= :send-stdin (console/input-action {:input "" :waiting? true :evaluating? true}))))
  (testing "busy while running and not waiting"
    (is (= :busy (console/input-action {:input "(+ 1 2)" :evaluating? true}))))
  (testing "blank input does nothing"
    (is (= :noop (console/input-action {:input "  \n"}))))
  (testing "unbalanced input continues on a new line"
    (is (= :newline (console/input-action {:input "(defn f [x]"}))))
  (testing "a string holding a paren is balanced"
    (is (= :eval (console/input-action {:input "(println \"(\")"}))))
  (testing "complete input evaluates, emoji included"
    (is (= :eval (console/input-action {:input "(str \"😀\" 1)"})))))

(deftest trim-length-test
  (is (= 0 (console/trim-length "abc" 10)))
  (is (= 0 (console/trim-length "" 0)))
  (testing "cuts after the newline inside the part that must go"
    (is (= 4 (console/trim-length "aaa\nbbb\nccc" 9))))
  (testing "no newline: cut exactly"
    (is (= 3 (console/trim-length "abcdefgh" 5))))
  (testing "result fits the cap"
    (let [t (apply str (repeat 1000 "line\n"))
          k (console/trim-length t 100)]
      (is (<= (- (count t) k) 100))
      (is (= \newline (nth t (dec k)))))))

(deftest result-line-test
  (is (= [:result "=> 3\n"] (console/result-line {:status :ok :printed "3"})))
  (is (= [:system "; interrupted\n"] (console/result-line {:status :interrupted})))
  (let [[kind text] (console/result-line {:status :error :error (ex-info "boom" {})})]
    (is (= :error kind))
    (is (re-find #"boom" text))))

(deftest unclosed-bracket-message-test
  (is (nil? (console/unclosed-bracket-message "(+ 1 2)")))
  (is (nil? (console/unclosed-bracket-message "")))
  (is (= "The ( on line 3 is never closed."
         (console/unclosed-bracket-message "(ok)\n\n(defn f [x]\n  (+ x 1)")))
  (is (= "The [ on line 1 is never closed."
         (console/unclosed-bracket-message "😀 [1 2")))
  (testing "a stray closer alone is not an unclosed opener"
    (is (nil? (console/unclosed-bracket-message "(+ 1 2))")))))

(deftest styles-cover-every-kind-test
  (is (= #{:out :err :prompt :result :error :system} (set (keys console/styles)))))
