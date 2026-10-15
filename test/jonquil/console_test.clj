(ns jonquil.console-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.console :as console]
            [jonquil.theme :as theme]))

(deftest input-action-leftover-test
  (testing "the exact leftover text is held for a second Return"
    (is (= :leftover (console/input-action {:input "y" :leftover "y"})))
    (is (= :leftover (console/input-action {:input "(foo" :leftover "(foo"}))))
  (testing "edited text is ordinary input"
    (is (= :eval (console/input-action {:input "y2" :leftover "y"})))
    (is (= :eval (console/input-action {:input "y" :leftover nil}))))
  (testing "stdin, busy and shift still win"
    (is (= :send-stdin (console/input-action {:input "y" :leftover "y" :waiting? true})))
    (is (= :busy (console/input-action {:input "y" :leftover "y" :evaluating? true})))
    (is (= :newline (console/input-action {:input "y" :leftover "y" :shift? true})))))

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
  (is (= [:system "; Loaded a.b (2 forms)\n"] (console/result-line {:status :notice :text "; Loaded a.b (2 forms)"})))
  (let [[kind text] (console/result-line {:status :error :error (ex-info "boom" {})})]
    (is (= :error kind))
    (is (re-find #"boom" text))))

(deftest error-text-test
  (testing "an out-of-process runtime's text is shown as sent"
    (is (= [:error "Error: Divide by zero\n"]
           (console/result-line {:status :error :error-text "Error: Divide by zero"})))
    (is (= "Error: EOF while reading" (console/error-text {:error-text "Error: EOF while reading"}))))
  (testing "an in-process Throwable still goes through format-error"
    (is (re-find #"^Error \[.*\]: boom$" (console/error-text {:error (ex-info "boom" {})})))))

(deftest unclosed-bracket-message-test
  (is (nil? (console/unclosed-bracket-message "(+ 1 2)")))
  (is (nil? (console/unclosed-bracket-message "")))
  (is (= "The ( on line 3 is never closed."
         (console/unclosed-bracket-message "(ok)\n\n(defn f [x]\n  (+ x 1)")))
  (is (= "The [ on line 1 is never closed."
         (console/unclosed-bracket-message "😀 [1 2")))
  (testing "a stray closer alone is not an unclosed opener"
    (is (nil? (console/unclosed-bracket-message "(+ 1 2))")))))

(deftest clear-last-range-test
  (testing "no active last-entry mark: nothing to clear"
    (is (nil? (console/clear-last-range nil 42))))
  (testing "the entry's already empty (a previous clear caught it up to out-end)"
    (is (nil? (console/clear-last-range 10 10))))
  (testing "a mark somehow past out-end (defensive, shouldn't happen live)"
    (is (nil? (console/clear-last-range 12 10))))
  (testing "a real span to delete"
    (is (= [3 10] (console/clear-last-range 3 10)))))

(deftest styles-cover-every-kind-test
  (is (= #{:out :err :prompt :result :error :system} (set (keys console/styles))))
  (is (= [:out :err :prompt :result :error :system] (vec (keys console/styles)))
      "declaration order is the GTK tag priority order"))

(deftest style-takes-colours-from-the-theme-test
  (let [light (theme/theme :light) navy (theme/theme :navy)]
    (is (= {:fg "#24292f"} (console/style light :out)))
    (is (= {:fg "#6639ba" :bold true} (console/style light :prompt)))
    (is (= {:fg "#f07178"} (console/style navy :err)))
    (is (= {:fg "#6f7390" :italic true} (console/style navy :system)))
    (is (nil? (console/style light :bogus)))))
