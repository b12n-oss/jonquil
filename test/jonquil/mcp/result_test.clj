(ns jonquil.mcp.result-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.mcp.result :as result]))

(deftest max-output-default-and-bounds
  (is (= 100000 (result/max-output-of {})))
  (is (= 100000 (result/max-output-of {"max_output_chars" -1})))
  (is (= 100000 (result/max-output-of {"max_output_chars" "many"})))
  (is (= 50 (result/max-output-of {"max_output_chars" 50})))
  (is (= 1000000 (result/max-output-of {"max_output_chars" 5000000}))))

(defn- collect [cap chunks]
  (reduce (fn [st [kind s]] (result/add-output st kind s cap)) result/empty-output chunks))

(deftest output-under-the-cap-is-whole
  (is (= {:output "ab\ncd" :err "cd" :dropped 0}
         (result/output-of (collect 10 [[:out "ab\n"] [:err "cd"]]) 10))))

(deftest output-over-the-cap-keeps-the-tail
  (is (= {:output "56789" :err "" :dropped 5}
         (result/output-of (collect 5 [[:out "01234"] [:out "56789"]]) 5)))
  ;; the cut can land inside a chunk
  (is (= {:output "3456" :err "" :dropped 3}
         (result/output-of (collect 4 [[:out "0123456"]]) 4))))

(deftest a-flood-stays-bounded
  (let [st (collect 10 (repeat 1000 [:out "abcdefg"]))]
    (is (<= (:size st) 20))
    (is (= 7000 (+ (:dropped st) (:size st))))
    (let [{:keys [output dropped]} (result/output-of st 10)]
      (is (= 10 (count output)))
      (is (= 6990 dropped))
      (is (= (subs (apply str (repeat 1000 "abcdefg")) 6990) output)))))

(deftest clip-keeps-the-tail
  (is (= {:output "abc" :dropped 0} (result/clip "abc" 3)))
  (is (= {:output "cde" :dropped 2} (result/clip "abcde" 3)))
  (is (= {:output "" :dropped 0} (result/clip nil 3))))

(deftest dropped-line-only-when-something-went
  (is (= "" (result/dropped-line 0)))
  (is (= "; 12 earlier characters of output dropped\n" (result/dropped-line 12))))

(deftest error-fields-from-format-error-text
  (is (= {"message" "Divide by zero" "type" "java.lang.ArithmeticException"}
         (result/error-fields "Error [java.lang.ArithmeticException]: Divide by zero\n")))
  (is (= {"message" "Unable to resolve symbol: x in this context"
          "type" "clojure.lang.Compiler$CompilerException" "line" 3 "column" 7}
         (result/error-fields (str "Error [clojure.lang.Compiler$CompilerException]: "
                                   "Unable to resolve symbol: x in this context\nLocation: line 3, col 7"))))
  (is (= {"message" "bad" "type" "T" "line" 2}
         (result/error-fields "Error [T]: bad\nLocation: line 2"))))

(deftest error-fields-of-other-text-is-the-message
  (is (= {"message" "the server went away"} (result/error-fields "the server went away\n")))
  (is (= {"message" ""} (result/error-fields nil))))

(deftest eval-data-shapes
  (is (= {"status" "ok" "output" "hi\n" "value" "3" "ms" 4 "ns" "user"}
         (result/eval-data {:status :ok :value "3" :output "hi\n" :err "" :dropped 0 :ms 4 :ns 'user})))
  (is (= {"status" "error" "output" "" "err" "e" "dropped_chars" 9
          "error" {"message" "bad" "type" "T"}}
         (result/eval-data {:status :error :output "" :err "e" :dropped 9 :error-text "Error [T]: bad"})))
  (is (= {"status" "timeout" "output" "p"} (result/eval-data {:status :timeout :output "p"}))))
