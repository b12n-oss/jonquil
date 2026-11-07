(ns jonquil.doc-examples-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jonquil.doc-examples :as de]))

(defn- doc-with
  "The server's shape: doc, blank line, '== ns/name', blank line, examples."
  [& bodies]
  (str "clojure.core/map\n([f coll])\nReturns a lazy seq.\n\n"
       "== clojure.core/map\n\n"
       (str/join "\n\n" (map-indexed (fn [i b] (str "= example " (inc i) "\n" b)) bodies))))

(deftest split-no-examples
  (is (= {:doc "a/b\n([x])\nDoc." :title nil :examples []}
         (de/split "a/b\n([x])\nDoc."))))

(deftest split-header-without-examples-falls-back
  (is (= [] (:examples (de/split "demo.core/greet\n([who])\nSays hello.\n\n== examples")))))

(deftest split-examples-without-title-line-falls-back
  (is (= [] (:examples (de/split "doc\n\n= example 1\n(inc 1)")))))

(deftest split-two-examples
  (let [r (de/split (doc-with "(map inc [1 2])\n;;=> (2 3)" "(map + [1] [2])"))]
    (is (= "clojure.core/map" (:title r)))
    (is (= [1 2] (map :n (:examples r))))
    (is (= "(map inc [1 2])\n;;=> (2 3)" (:code (first (:examples r)))))
    (is (= "(map inc [1 2])\n;;=> (2 3)" (:body (first (:examples r)))))
    (is (not (str/includes? (:doc r) "== ")))))

(deftest split-normalises-crlf
  (is (= "(inc 1)" (:code (first (:examples (de/split (str/replace (doc-with "(inc 1)") "\n" "\r\n"))))))))

(deftest clean-plain-code-unchanged
  (is (= "(map inc [1 2])\n;;=> (2 3)" (de/clean "(map inc [1 2])\n;;=> (2 3)"))))

(deftest clean-user-transcript
  (is (= ";; function checks\n(def ?9 #(= % 9))\n(?9 9)\n(?9 5)"
         (de/clean "\n;; function checks\nuser=> (def ?9 #(= % 9))\n#'user/?9\nuser=> (?9 9)\ntrue\nuser=> (?9 5)\nfalse"))))

(deftest clean-keeps-code-before-first-prompt
  (is (= "(defn small? [n] (< n 100))\n\n(small? 50)"
         (de/clean "(defn small? [n] (< n 100))\n\nuser=> (small? 50)\ntrue"))))

(deftest clean-other-namespace-prompt
  (is (= "(inc 1)" (de/clean "cljs.user=> (inc 1)\n2"))))

(deftest clean-multiline-form-after-prompt
  (is (= "(let [a 1]\n  (inc a))" (de/clean "user=> (let [a 1]\n  (inc a))\n2"))))

(deftest clean-bare-arrow-result-marker
  (is (= "((juxt :a :b) {:a 1 :b 2})" (de/clean "((juxt :a :b) {:a 1 :b 2})\n=> [1 2]")))
  (is (= "(f)" (de/clean "(f)\n=> {:a 1\n    :b 2}"))))

(deftest clean-output-stops-at-blank-and-comment
  (is (= "(f)\n\n(g)\n;; note\n(h)"
         (de/clean "user=> (f)\n1\n\n(g)\nuser=> ;; note\n(h)"))))

(deftest clean-unclosed-form-keeps-rest
  (is (= "(map inc [1 2" (de/clean "user=> (map inc [1 2"))))

(deftest clean-cider-prompt
  (is (= "(inc 1)" (de/clean "user> (inc 1)\n2")))
  (is (= "x" (de/clean "my.ns> x\n1"))))

(deftest clean-cider-prompt-needs-a-name-and-line-start
  (is (= "(> 1 2)\n(f x>y)\nx>y\n> 1" (de/clean "(> 1 2)\n(f x>y)\nx>y\n> 1"))))

(deftest clean-keeps-same-line-remainder
  (is (= "(f) (g)" (de/clean "user=> (f) (g)\n3"))))

(deftest clean-keeps-same-line-comment
  (is (= "(f) ; why\n\n(h)" (de/clean "user=> (f) ; why\n3\n\n(h)"))))

(deftest clean-to-blank
  (is (= "" (de/clean "user=>\n"))))

(deftest insert-after-enclosing-form
  (is (= {:edits [[11 11 "\n\n(inc 1)"]] :caret 20}
         (de/insert-op {:text "(greet \"x\")\n(other)" :caret 2} "(inc 1)"))))

(deftest insert-between-forms-goes-after-caret-line
  (is (= {:edits [[8 8 "\n\n(inc 1)"]] :caret 17}
         (de/insert-op {:text "(a)\n;; c\n(b)" :caret 5} "(inc 1)"))))
