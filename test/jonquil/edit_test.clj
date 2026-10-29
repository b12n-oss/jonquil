(ns jonquil.edit-test
  "jonquil.edit's ops as pure snapshot-in, text-and-caret-out cases, derived
  from DrClojure.ui (41c56b8) handlers and test/DrClojure/core_test.clj
  deftests. See UPSTREAM.md and the jonquil.edit ns docstring for the
  contract."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.edit :as edit]
            [jonquil.syntax :as syntax]))

(defn- snap
  "A snapshot with no selection: caret only."
  [text caret]
  {:text text :caret caret :sel-start caret :sel-end caret})

(defn- snap-sel
  "A snapshot with a real selection from a to b (caret at b)."
  [text a b]
  {:text text :caret b :sel-start a :sel-end b})

(deftest apply-edits-test
  (testing "single edit"
    (is (= "(bar)" (edit/apply-edits "(foo)" [[1 4 "bar"]]))))
  (testing "multiple ascending edits applied back to front"
    (is (= "[X, Y]" (edit/apply-edits "[a, b]" [[1 2 "X"] [4 5 "Y"]]))))
  (testing "no edits is a no-op"
    (is (= "(foo)" (edit/apply-edits "(foo)" [])))))

(deftest smart-enter-between-pair-off-test
  (testing "with :between-pair? false (Parinfer on), (|) gets a plain
           smart-indent newline instead of the three-line open"
    (let [snap {:text "()" :caret 1 :sel-start 1 :sel-end 1}]
      (is (= {:edits [[1 1 "\n  \n"]] :caret 4 :sel nil}
             (edit/smart-enter snap)) "the pair branch as it is today")
      (is (= {:edits [[1 1 "\n  "]] :caret 4 :sel nil}
             (edit/smart-enter snap {:between-pair? false}))
          "one line, indented inside the open paren, closer left in place"))))

(deftest smart-enter-test
  (testing "indents from the unclosed form, no selection"
    (let [text "(defn f [x]"
          r (edit/smart-enter (snap text 11))]
      (is (= "(defn f [x]\n  " (edit/apply-edits text (:edits r))))
      (is (= 14 (:caret r)))
      (is (nil? (:sel r)))))

  (testing "auto-dedents when the previous line closed forms"
    (let [text "  (foo))"
          r (edit/smart-enter (snap text 8))]
      (is (= "  (foo))\n" (edit/apply-edits text (:edits r))))
      (is (= 9 (:caret r)))))

  (testing "emoji before caret"
    (let [text "😀(a)"
          r (edit/smart-enter (snap text 3))]
      (is (= "😀(a\n  )" (edit/apply-edits text (:edits r))))
      (is (= 6 (:caret r)))))

  (testing "replaces a selection with a newline"
    (let [text "(let [a 1] XY)"
          r (edit/smart-enter (snap-sel text 11 13))]
      (is (= "(let [a 1] \n  )" (edit/apply-edits text (:edits r))))
      (is (= 14 (:caret r)))))

  (testing "empty text at caret 0: derived from upstream handle-smart-enter!
  with no selection, no prev/next char, and compute-smart-indent returning
  the empty string for blank text -- matches the prescribed code's own
  result, so there's no discrepancy to flag"
    (let [r (edit/smart-enter (snap "" 0))]
      (is (= "\n" (edit/apply-edits "" (:edits r))))
      (is (= 1 (:caret r)))))

  (testing "between an empty pair: upstream smart-enter-test's own
  \"()\" caret 1 case, asserting \"(\\n  \\n)\" and caret 4"
    (let [r (edit/smart-enter (snap "()" 1))]
      (is (= "(\n  \n)" (edit/apply-edits "()" (:edits r))))
      (is (= 4 (:caret r))))))

(deftest indent-test
  ;; Upstream ui.clj indent-selection! (41c56b8); core_test.clj
  ;; indent-unindent-test.
  (testing "no selection: inserts two spaces at the caret"
    (let [text "😀abc"
          r (edit/indent (snap text 1))]
      (is (= "😀  abc" (edit/apply-edits text (:edits r))))
      (is (= 3 (:caret r)))
      (is (nil? (:sel r)))))

  (testing "multi-line block indent, whole text selected, emoji on the
  first line"
    (let [text "😀line 1\nline 2"
          r (edit/indent (snap-sel text 0 (count text)))]
      (is (= "  😀line 1\n  line 2" (edit/apply-edits text (:edits r))))
      (is (= 18 (:caret r)))
      (is (= [2 18] (:sel r))))))

(deftest unindent-test
  ;; Upstream ui.clj unindent-selection! (41c56b8); core_test.clj
  ;; indent-unindent-test.
  (testing "single-line, no selection: removes leading spaces, emoji after
  them"
    (let [text "  😀(foo)"
          r (edit/unindent (snap text 0))]
      (is (= "😀(foo)" (edit/apply-edits text (:edits r))))
      (is (= 0 (:caret r)))
      (is (nil? (:sel r)))))

  (testing "single-line, no selection: removes a leading tab"
    (let [text "\t(println \"hello\")"
          r (edit/unindent (snap text 0))]
      (is (= "(println \"hello\")" (edit/apply-edits text (:edits r))))
      (is (= 0 (:caret r)))))

  (testing "single-line, no leading whitespace: does not apply"
    (is (nil? (edit/unindent (snap "(println \"hello\")" 0)))))

  (testing "multi-line block unindent, whole text selected"
    (let [text "  line 1\n  line 2"
          r (edit/unindent (snap-sel text 0 (count text)))]
      (is (= "line 1\nline 2" (edit/apply-edits text (:edits r))))
      (is (= 13 (:caret r)))
      (is (= [0 13] (:sel r))))))

(deftest toggle-comment-test
  ;; Upstream ui.clj toggle-comment! (41c56b8); core_test.clj
  ;; toggle-comment-test.
  (testing "single line, no selection, emoji before the caret: comment then
  uncomment round-trips the caret"
    (let [text "😀(foo)"
          commented (edit/toggle-comment (snap text 1))]
      (is (= "; 😀(foo)" (edit/apply-edits text (:edits commented))))
      (is (= 3 (:caret commented)))
      (is (nil? (:sel commented)))
      (let [commented-text (edit/apply-edits text (:edits commented))
            uncommented (edit/toggle-comment (snap commented-text (:caret commented)))]
        (is (= text (edit/apply-edits commented-text (:edits uncommented))))
        (is (= 1 (:caret uncommented))))))

  (testing "multi-line toggle comment, whole text selected"
    (let [text "(defn foo []\n  (+ 1 2))\n"
          r (edit/toggle-comment (snap-sel text 0 (count text)))]
      (is (= "; (defn foo []\n;   (+ 1 2))\n" (edit/apply-edits text (:edits r))))
      (let [commented-text (edit/apply-edits text (:edits r))
            back (edit/toggle-comment (snap-sel commented-text (first (:sel r)) (second (:sel r))))]
        (is (= text (edit/apply-edits commented-text (:edits back)))))))

  (testing "preserves empty lines when commenting a block"
    (let [text "(def a 1)\n\n(def b 2)"
          r (edit/toggle-comment (snap-sel text 0 (count text)))]
      (is (= "; (def a 1)\n\n; (def b 2)" (edit/apply-edits text (:edits r))))))

  (testing "blank text spans no commentable line: does not apply"
    (is (nil? (edit/toggle-comment (snap "" 0))))))

(deftest insert-open-test
  ;; Upstream ui.clj setup-auto-brackets! keyTyped, open-bracket/quote
  ;; branch (41c56b8); core_test.clj auto-brackets-test.
  (testing "opens a fresh empty pair with the caret inside"
    (let [r (edit/insert-open (snap "" 0) \()]
      (is (= "()" (edit/apply-edits "" (:edits r))))
      (is (= 1 (:caret r)))
      (is (nil? (:sel r)))))

  (testing "wraps a selection, emoji before it"
    (let [text "😀hello"
          r (edit/insert-open (snap-sel text 1 6) \[)]
      (is (= "😀[hello]" (edit/apply-edits text (:edits r))))
      (is (= 8 (:caret r)))
      (is (= [1 8] (:sel r)))))

  (testing "typing a quote next to an existing quote steps over instead of
  nesting"
    (let [text "\"\""
          r (edit/insert-open (snap text 1) \")]
      (is (= text (edit/apply-edits text (:edits r))))
      (is (= 2 (:caret r)))))

  (testing "an unsupported character does not apply"
    (is (nil? (edit/insert-open (snap "abc" 1) \a)))))

(deftest insert-close-test
  ;; Upstream ui.clj setup-auto-brackets! keyTyped, close-delimiter branch
  ;; (41c56b8); core_test.clj auto-brackets-test.
  (testing "typing a closer that's already next steps over it"
    (let [text "()"
          r (edit/insert-close (snap text 1) \))]
      (is (= text (edit/apply-edits text (:edits r))))
      (is (= 2 (:caret r)))
      (is (nil? (:sel r)))))

  (testing "stepping over, emoji before the caret"
    (let [text "😀()"
          r (edit/insert-close (snap text 2) \))]
      (is (= text (edit/apply-edits text (:edits r))))
      (is (= 3 (:caret r)))))

  (testing "the next char isn't the same closer: does not apply"
    (is (nil? (edit/insert-close (snap "(a" 1) \)))))

  (testing "an active selection: does not apply"
    (is (nil? (edit/insert-close (snap-sel "())" 1 2) \))))))

(deftest backspace-pair-test
  ;; Upstream ui.clj setup-auto-brackets! keyPressed VK_BACK_SPACE (41c56b8);
  ;; core_test.clj auto-brackets-test.
  (testing "caret between an empty pair deletes both characters"
    (let [text "[]"
          r (edit/backspace-pair (snap text 1))]
      (is (= "" (edit/apply-edits text (:edits r))))
      (is (= 0 (:caret r)))
      (is (nil? (:sel r)))))

  (testing "an empty pair after an emoji"
    (let [text "😀[]"
          r (edit/backspace-pair (snap text 2))]
      (is (= "😀" (edit/apply-edits text (:edits r))))
      (is (= 1 (:caret r)))))

  (testing "not an empty pair: does not apply"
    (is (nil? (edit/backspace-pair (snap "[a]" 2))))))

(deftest matching-bracket-test
  ;; Upstream ui.clj find-matching-bracket (41c56b8); core_test.clj
  ;; bracket-matching-test.
  (testing "matching parentheses"
    (let [code "(+ (* 2 3) 4)"]
      (is (= [0 12] (edit/matching-bracket (snap code 0))))
      (is (= [12 0] (edit/matching-bracket (snap code 12))))
      (is (= [3 9] (edit/matching-bracket (snap code 3))))
      (is (= [9 3] (edit/matching-bracket (snap code 9))))))

  (testing "matching brackets and braces"
    (let [code "[1 { :a 2 } 3]"]
      (is (= [0 13] (edit/matching-bracket (snap code 0))))
      (is (= [13 0] (edit/matching-bracket (snap code 13))))
      (is (= [3 10] (edit/matching-bracket (snap code 3))))
      (is (= [10 3] (edit/matching-bracket (snap code 10))))))

  (testing "brackets inside comments and strings are ignored"
    (let [code "(let [s \"(foo)\"] ;; )\n (+ 1 2))"]
      (is (= [0 30] (edit/matching-bracket (snap code 0))))
      (is (= [30 0] (edit/matching-bracket (snap code 30))))
      (is (nil? (edit/matching-bracket (snap code 9))))
      (is (nil? (edit/matching-bracket (snap code 13))))
      (is (nil? (edit/matching-bracket (snap code 20))))))

  (testing "unmatched bracket returns nil"
    (is (nil? (edit/matching-bracket (snap "(+ 1 2" 0)))))

  (testing "emoji before the brackets"
    (let [code "😀(a)"]
      (is (= [1 3] (edit/matching-bracket (snap code 1))))
      (is (= [3 1] (edit/matching-bracket (snap code 3)))))))

(deftest rename-test
  ;; Upstream ui.clj rename-symbol-dialog! (41c56b8), via
  ;; syntax/find-symbol-occurrences. No named upstream deftest exercises this
  ;; end to end (it's driven by a JOptionPane dialog), so these cases are
  ;; written directly against find-symbol-occurrences' own contract: a
  ;; string or comment mentioning the name must stay untouched.
  (testing "renames every code occurrence, leaving a string and a comment
  containing the name untouched"
    (let [text "(defn foo [x] (+ foo x))\n\"a string with foo inside\"\n; a comment about foo\n"
          caret (str/index-of text "foo")
          r (edit/rename (snap text caret) "bar")]
      (is (= "(defn bar [x] (+ bar x))\n\"a string with foo inside\"\n; a comment about foo\n"
             (edit/apply-edits text (:edits r))))
      (is (= 9 (:caret r)))
      (is (nil? (:sel r)))))

  (testing "renames from a selection, emoji before the symbol"
    (let [text "😀(let [x 1] (+ x x))"
          sx (str/index-of text "x")
          r (edit/rename (snap-sel text sx (inc sx)) "y")]
      (is (= "😀(let [y 1] (+ y y))" (edit/apply-edits text (:edits r))))
      (is (= 8 (:caret r)))))

  (testing "no symbol at the caret: does not apply"
    (is (nil? (edit/rename (snap "   " 1) "bar"))))

  (testing "new name equals the old name: does not apply"
    (is (nil? (edit/rename (snap "(defn foo [] foo)" 8) "foo"))))

  (testing "new name isn't a valid symbol: does not apply"
    (is (nil? (edit/rename (snap "(defn foo [] foo)" 8) "not valid"))))

  (testing "no occurrences of the symbol under the selection text: selecting
  a word that only ever appears inside a comment finds nothing to rename"
    (let [text "; foo bar\n(baz 1)"
          s (str/index-of text "bar")]
      (is (nil? (edit/rename (snap-sel text s (+ s 3)) "quux")))))

  (testing "a whitespace-padded new name is trimmed first, like upstream's
  own (str/trim (str input)) -- renames exactly like the untrimmed name"
    (let [text "(defn foo [x] (+ foo x))\n\"a string with foo inside\"\n; a comment about foo\n"
          caret (str/index-of text "foo")
          untrimmed (edit/rename (snap text caret) "bar")
          padded (edit/rename (snap text caret) " bar ")]
      (is (= (edit/apply-edits text (:edits untrimmed))
             (edit/apply-edits text (:edits padded))))
      (is (= (:caret untrimmed) (:caret padded)))
      (is (= "(defn bar [x] (+ bar x))\n\"a string with foo inside\"\n; a comment about foo\n"
             (edit/apply-edits text (:edits padded)))))))

(deftest smart-enter-keeps-body-indent-before-a-closer-test
  ;; upstream 7056c61: Enter before a trailing closer inside an open form
  (let [text "(defn foo\n  [x])"
        r (edit/smart-enter (snap text 15))]
    (is (= [[15 15 "\n  "]] (:edits r)))
    (is (= 18 (:caret r)))))

(deftest smart-indent-unclosed-prefix-edges-test
  (testing "only a stray closer before the caret still stops the dedent (upstream's rule)"
    (is (= "    " (syntax/compute-smart-indent ")\n    (+ a 1))" 13))))
  (testing "a bracket inside a string doesn't count"
    (is (= "" (syntax/compute-smart-indent "(def s \"(\")\n  x)" 15))))
  (testing "the painter's compute-brackets cache survives an Enter"
    (let [t "(defn foo\n  [x])"
          r (syntax/compute-brackets t)]
      (syntax/compute-smart-indent t 15)
      (is (identical? r (syntax/compute-brackets t))))))

(deftest prefix-unclosed-agrees-with-a-fresh-prefix-scan-test
  ;; Jonquil's delta from upstream 7056c61: the whole-buffer bracket result
  ;; must give the same answer as scanning the text before the caret
  (doseq [t ["(defn foo\n  [x])"
             ")\n    (+ a 1))"
             "(def s \"(\")\n  x)"
             "(a [b {c}] ;; (\n  \"]\" \\( d)"
             "(re-find #\"(\" s)\n  (x))"]
          warm? [false true]]
    ;; cold: the cache holds other text, so it scans; warm: the cache holds
    ;; an equal but distinct string, as the painter's does for a snapshot
    (syntax/compute-brackets (if warm? (apply str t) "x"))
    (doseq [pos (range (inc (count t)))]
      (is (= (boolean (seq (:unmatched (#'syntax/compute-brackets* (subs t 0 pos)))))
             (#'syntax/prefix-unclosed? t pos))
          (pr-str t pos warm?)))))

(deftest prefix-unclosed-cold-path-scans-only-the-prefix-test
  ;; review I1: on a cache miss the scan stops at the caret, as upstream's
  ;; does, rather than reading the whole buffer
  (let [orig @#'syntax/compute-brackets*
        seen (atom [])
        t "(defn foo\n  [x])\n(def a 1)\n(def b 2)\n"]
    (syntax/compute-brackets "x")
    (with-redefs-fn {#'syntax/compute-brackets* (fn [s] (swap! seen conj (count s)) (orig s))}
      #(syntax/compute-smart-indent t 15))
    (is (= [15] @seen))))
