(ns jonquil.doc-examples-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jonquil.commands :as commands]
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
  (is (= {:edits [[11 11 "\n\n(inc 1)\n"]] :caret 20}
         (de/insert-op {:text "(greet \"x\")\n(other)" :caret 2} "(inc 1)"))))

(deftest insert-between-forms-goes-after-caret-line
  (is (= {:edits [[8 8 "\n\n(inc 1)\n"]] :caret 17}
         (de/insert-op {:text "(a)\n;; c\n(b)" :caret 5} "(inc 1)"))))

(defn- applied
  "text after insert-op's edit lands, and the caret."
  [text caret code]
  (let [{:keys [edits] :as op} (de/insert-op {:text text :caret caret} code)
        [[s e ins]] edits]
    [(str (subs text 0 s) ins (subs text e)) (:caret op)]))

(deftest insert-goes-after-the-line-of-the-forms-end
  ;; a trailing comment stays with its form
  (is (= ["(a) ; note\n\n(inc 1)\n\n(b)" 19] (applied "(a) ; note\n(b)" 2 "(inc 1)")))
  ;; a second form on the same line stays with the first
  (is (= ["(a) (b)\n\n(inc 1)" 16] (applied "(a) (b)" 2 "(inc 1)"))))

(deftest insert-separates-from-what-follows
  (is (= ["(a)\n\n(inc 1)\n\n(b)" 12] (applied "(a)\n(b)" 1 "(inc 1)")))
  ;; a blank line already follows: no second one
  (is (= ["(a)\n\n(inc 1)\n\n(b)" 12] (applied "(a)\n\n(b)" 1 "(inc 1)")))
  ;; nothing follows but a newline
  (is (= ["(a)\n\n(inc 1)\n" 12] (applied "(a)\n" 1 "(inc 1)"))))

(deftest insert-into-empty-buffer-has-no-leading-blank-lines
  (is (= ["(inc 1)" 7] (applied "" 0 "(inc 1)")))
  (is (= ["(inc 1)\n\n(b)" 7] (applied "\n(b)" 0 "(inc 1)"))))

(deftest insert-on-a-blank-line-between-forms
  ;; one blank line before and one after, not two and none
  (is (= ["(a)\n\n(inc 1)\n\n(b)" 12] (applied "(a)\n\n(b)" 4 "(inc 1)")))
  ;; blank line after a blank line: goes straight in
  (is (= ["(a)\n\n(inc 1)\n\n(b)" 12] (applied "(a)\n\n\n(b)" 5 "(inc 1)"))))

(deftest insert-on-the-second-line-when-the-first-is-blank
  (is (= ["\n(inc 1)\n\n(b)" 8] (applied "\n\n(b)" 1 "(inc 1)"))))

(deftest insert-inside-a-comment-block
  ;; the closing paren was directly after the last form: it stays directly after the code
  (is (= ["(comment\n  (a)\n\n  (inc 1))" 25] (applied "(comment\n  (a))" 12 "(inc 1)")))
  (is (= ["(comment (a)\n\n  (inc 1))" 23] (applied "(comment (a))" 10 "(inc 1)")))
  ;; the paren was on its own line: it stays on the line after the code
  (is (= ["(comment\n  (a)\n\n  (inc 1)\n)" 25] (applied "(comment\n  (a)\n)" 12 "(inc 1)")))
  ;; only the first line is indented; later lines stay as written
  (is (= ["(comment\n  (a)\n\n  (do\n  1))" 26] (applied "(comment\n  (a))" 12 "(do\n  1)")))
  (doseq [[t c] [["(comment\n  (a))" 12] ["(comment (a))" 10] ["(comment\n  (a)\n)" 12]]]
    (let [[res _] (applied t c "(inc 1)")]
      (is (= 1 (count (commands/top-level-forms res))))
      (is (= (count res) (:end (first (commands/top-level-forms res))))))))
