(ns jonquil.upstream.parinfer-test
  "DrClojure's Parinfer tests at 7056c61, ported apart from the namespace
   names, JSON read with clojure.data.json in place of upstream's
   json_helper.clj, and test-format-all-and-selection-robustness, which
   drives Swing. The fixtures are Parinfer's own. See UPSTREAM.md."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [jonquil.parinfer :as parinfer]))

(defn- parse-json [s] (json/read-str s :key-fn keyword))

(defn- run-case [mode-fn test-case]
  (let [in-text (:text test-case)
        options (:options test-case)
        expected (:result test-case)
        actual (mode-fn in-text options)]
    (when (contains? expected :success)
      (is (= (:success expected) (:success actual))
          (str "Success mismatch in test case " (:id test-case))))
    (when (contains? expected :text)
      (is (= (:text expected) (:text actual))
          (str "Text mismatch in test case " (:id test-case))))
    (when (contains? expected :cursorX)
      (is (= (:cursorX expected) (:cursorX actual))
          (str "cursorX mismatch in test case " (:id test-case))))
    (when (contains? expected :cursorLine)
      (is (= (:cursorLine expected) (:cursorLine actual))
          (str "cursorLine mismatch in test case " (:id test-case))))
    (when (contains? expected :error)
      (is (= (get-in expected [:error :name]) (get-in actual [:error :name]))
          (str "Error name mismatch in test case " (:id test-case))))))

(deftest test-smart-mode-basic
  (testing "Basic smart mode inference"
    (let [res (parinfer/smart-mode "(def x 1")]
      (is (:success res))
      (is (= "(def x 1)" (:text res))))

    (let [res (parinfer/smart-mode "(defn foo\n  [x]\n  (+ x 1")]
      (is (:success res))
      (is (= "(defn foo\n  [x]\n  (+ x 1))" (:text res))))

    (let [res (parinfer/smart-mode "(let [a 1\n      b 2]\n  (+ a b")]
      (is (:success res))
      (is (= "(let [a 1\n      b 2]\n  (+ a b))" (:text res))))

    (let [res (parinfer/smart-mode "(let [a 1\n      ])" {:cursorX 6 :cursorLine 1})]
      (is (:success res))
      (is (= "(let [a 1\n      ])" (:text res))))))

(deftest test-indent-mode-basic
  (testing "Basic indent mode inference"
    (let [res (parinfer/indent-mode "(def x 1")]
      (is (:success res))
      (is (= "(def x 1)" (:text res))))

    (let [res (parinfer/indent-mode "(defn foo\n  [x]\n  (+ x 1")]
      (is (:success res))
      (is (= "(defn foo\n  [x]\n  (+ x 1))" (:text res))))))

(deftest test-paren-mode-basic
  (testing "Basic paren mode inference"
    (let [res (parinfer/paren-mode "(defn foo\n[x]\n(+ x 1))")]
      (is (:success res))
      (is (= "(defn foo\n [x]\n (+ x 1))" (:text res))))))

(deftest test-editor-helpers
  (testing "offset->line-col and line-col->offset"
    (let [txt "abc\ndef\n  ghi"]
      (is (= [0 0] (parinfer/offset->line-col txt 0)))
      (is (= [0 2] (parinfer/offset->line-col txt 2)))
      (is (= [1 0] (parinfer/offset->line-col txt 4)))
      (is (= [1 2] (parinfer/offset->line-col txt 6)))
      (is (= [2 2] (parinfer/offset->line-col txt 10)))

      (is (= 0 (parinfer/line-col->offset txt 0 0)))
      (is (= 2 (parinfer/line-col->offset txt 0 2)))
      (is (= 4 (parinfer/line-col->offset txt 1 0)))
      (is (= 6 (parinfer/line-col->offset txt 1 2)))
      (is (= 10 (parinfer/line-col->offset txt 2 2)))))

  (testing "compute-text-change"
    (let [change (parinfer/compute-text-change "(foo)" "(foo bar)")]
      (is (= 0 (:line-no change)))
      (is (= 4 (:x change)))
      (is (= "" (:old-text change)))
      (is (= " bar" (:new-text change))))

    (let [change (parinfer/compute-text-change "(foo\n    bar)" "(foo\n  bar)")]
      (is (= 1 (:line-no change)))
      (is (= 2 (:x change)))
      (is (= "  " (:old-text change)))
      (is (= "" (:new-text change))))))

(defn- find-fixture-file [base-name]
  (some (fn [path]
          (let [f (io/file path)]
            (when (.exists f) f)))
        [(str "test-resources/parinfer/" base-name ".json")
         (str base-name ".json")
         (str base-name ".json.tmp")]))

(deftest test-smart-mode-official-cases
  (let [f (find-fixture-file "smart-mode-cases")]
    (is (some? f) "Fixture smart-mode-cases.json should exist")
    (when f
      (testing "Official Parinfer Smart Mode test cases"
        (let [cases (parse-json (slurp f))]
          (is (pos? (count cases)))
          (doseq [tc cases]
            (run-case parinfer/smart-mode tc)))))))

(deftest test-indent-mode-official-cases
  (let [f (find-fixture-file "indent-mode-cases")]
    (is (some? f) "Fixture indent-mode-cases.json should exist")
    (when f
      (testing "Official Parinfer Indent Mode test cases"
        (let [cases (parse-json (slurp f))]
          (is (pos? (count cases)))
          (doseq [tc cases]
            (run-case parinfer/indent-mode tc)))))))

(deftest test-paren-mode-official-cases
  (let [f (find-fixture-file "paren-mode-cases")]
    (is (some? f) "Fixture paren-mode-cases.json should exist")
    (when f
      (testing "Official Parinfer Paren Mode test cases"
        (let [cases (parse-json (slurp f))]
          (is (pos? (count cases)))
          (doseq [tc cases]
            (run-case parinfer/paren-mode tc)))))))

(deftest parinfer-edge-inputs-test
  (testing "Empty text, a lone comment and CRLF text don't throw"
    (doseq [t ["" "; just a comment" "(foo\r\n  bar)"]
            mode [parinfer/smart-mode parinfer/indent-mode parinfer/paren-mode]]
      (is (true? (:success (mode t))) (pr-str t))))
  (testing "CRLF line endings survive a fix"
    (is (= "(foo\r\n  bar)" (:text (parinfer/smart-mode "(foo\r\n  bar"))))))
