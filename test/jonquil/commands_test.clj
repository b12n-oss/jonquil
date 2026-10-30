(ns jonquil.commands-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.commands :as c]
            [jonquil.edit :as edit]
            [jonquil.parinfer-edit :as pe]))

(defn- snap
  ([text caret] {:text text :caret caret :sel-start caret :sel-end caret})
  ([text s e] {:text text :caret e :sel-start s :sel-end e}))

(deftest run-selection-text-test
  (testing "a selection wins"
    (is (= "(+ 1 2)" (c/run-selection-text (snap "x (+ 1 2) y" 2 9)))))
  (testing "no selection sends the caret's line"
    (is (= "(inc 1)" (c/run-selection-text (snap "(def a 1)\n(inc 1)\n(dec 2)" 12))))
    (is (= "(def a 1)" (c/run-selection-text (snap "(def a 1)\n(inc 1)" 0))))
    (is (= "(inc 1)" (c/run-selection-text (snap "(def a 1)\n(inc 1)" 10))))
    (is (= "(inc 1)" (c/run-selection-text (snap "(def a 1)\n(inc 1)" 17)))))
  (testing "a blank line sends nothing"
    (is (nil? (c/run-selection-text (snap "(a)\n\n(b)" 4))))
    (is (nil? (c/run-selection-text (snap "" 0))))))

(deftest format-ops-test
  (let [text "(defn f [x]\n(inc x))"]
    (is (= "(defn f [x]\n  (inc x))"
           (edit/apply-edits text (:edits (c/format-all-op (snap text 0))))))
    (is (nil? (c/format-all-op (snap "(defn f [x]\n  (inc x))" 0))) "already formatted"))
  (testing "selection formats only the lines it spans"
    (let [text "(defn a []\n1)\n(defn b []\n2)"
          out (edit/apply-edits text (:edits (c/format-selection-op (snap text 14 26))))]
      (is (= "(defn a []\n1)\n(defn b []\n  2)" out))))
  (testing "no selection formats the caret's line"
    (let [text "(defn a []\n1)"]
      (is (= "(defn a []\n  1)"
             (edit/apply-edits text (:edits (c/format-selection-op (snap text 12)))))))))

(deftest carry-caret-test
  (let [old "(defn f [x]\n(let [y 1]\n(inc x)))"
        new "(defn f [x]\n  (let [y 1]\n    (inc x)))"]
    (testing "the caret keeps its place in the code, not its old offset"
      (is (= (str/index-of new "inc") (c/carry-caret old new (str/index-of old "inc"))) "before inc")
      (is (= (+ 2 (str/index-of new "inc")) (c/carry-caret old new (+ 2 (str/index-of old "inc")))) "inside inc")
      (is (= (count new) (c/carry-caret old new (count old))) "at the end"))
    (is (= 0 (c/carry-caret old new 0)))
    (testing "in the indentation it stays on its own line"
      (let [line3 (inc (str/last-index-of new "\n"))]
        (is (= (+ 4 line3) (c/carry-caret old new (inc (str/last-index-of old "\n"))))
            "right before (inc at column 0 stays right before it, now indented")
        (is (= 2 (c/carry-caret "a\n\nb" "a\n\nb" 2)) "a blank line keeps its place")
        (is (= 4 (c/carry-caret "a\n   b" "a\n  b" 5)) "column 3 of a 3-space indent lands at the end of the new 2-space one")
        (is (= 3 (c/carry-caret "a\n   b" "a\n  b" 3)) "column 1 stays at column 1")))
    (is (= 3 (c/carry-caret "abc" "abc" 3)) "unchanged text, unchanged caret")
    (testing "between two forms on a line it keeps its side of the space"
      (let [o "(defn f [x]\n(let [y 1] (inc x)))"
            n "(defn f [x]\n  (let [y 1] (inc x)))"]
        (is (= (str/index-of n "(inc") (c/carry-caret o n (str/index-of o "(inc"))) "before (inc")
        (is (= (dec (str/index-of n "(inc")) (c/carry-caret o n (dec (str/index-of o "(inc")))) "before the space")))))

(deftest format-ops-carry-the-caret-test
  (let [text "(defn f [x]\n(let [y 1]\n(inc x)))"
        caret (str/index-of text "inc")
        out (c/format-all-op (snap text caret))]
    (is (= (str/index-of (edit/apply-edits text (:edits out)) "inc") (:caret out))))
  (let [text "(defn a []\n1)\n(defn b []\n2)"
        caret (- (count text) 2)
        out (c/format-selection-op (snap text caret))]
    (is (= "2" (subs (edit/apply-edits text (:edits out)) (:caret out) (inc (:caret out)))) "still just before 2")))

(deftest pre-run-problem-test
  (is (nil? (c/pre-run-problem "(defn f [x] (inc x))")))
  (is (= "The ( on line 3 is never closed."
         (c/pre-run-problem "(ok)\n\n(defn f [x]")))
  (testing "an early error form no longer hides the unclosed one"
    (is (= "The ( on line 2 is never closed."
           (c/pre-run-problem "(undefined-fn)\n(defn g [y]"))))
  (testing "a stray closer"
    (is (= "The ) on line 2 has no matching opener."
           (c/pre-run-problem "(+ 1 2)\n(inc 3))"))))
  (testing "brackets in strings and comments don't count"
    (is (nil? (c/pre-run-problem "(println \"(\") ; )")))))

(deftest find-navigation-test
  (let [ms [[0 3] [8 11] [16 19]]]
    (is (= [8 11] (c/next-match ms 4)))
    (is (= [0 3] (c/next-match ms 17)) "wraps to the first")
    (is (= [8 11] (c/prev-match ms 16)))
    (is (= [16 19] (c/prev-match ms 0)) "wraps to the last")
    (is (nil? (c/next-match [] 0)))))

(deftest replace-test
  (let [text "foo bar foo baz foo"
        ms [[0 3] [8 11] [16 19]]]
    (testing "replace-current replaces the selected match and selects the next, shifted"
      (let [r (c/replace-current (snap text 0 3) ms "quux")]
        (is (= "quux bar foo baz foo" (edit/apply-edits text (:edits r))))
        (is (= [9 12] (:sel r)))))
    (testing "a selection that isn't a match changes nothing"
      (is (nil? (c/replace-current (snap text 1 3) ms "x"))))
    (testing "replace-all"
      (let [r (c/replace-all ms "X")]
        (is (= "X bar X baz X" (edit/apply-edits text (:edits r))))
        (is (= 3 (:count r)))))
    (is (nil? (c/replace-all [] "X")))))

(deftest zoom-test
  (is (= 14 (c/zoom 13 :in)))
  (is (= 12 (c/zoom 13 :out)))
  (is (= 32 (c/zoom 32 :in)) "clamps at the top")
  (is (= 9 (c/zoom 9 :out)) "clamps at the bottom")
  (is (= 13 (c/zoom 24 :reset))))

;; Run Form's choice of form.
(defn- form-at [text needle & [delta]]
  (:text (c/run-form {:text text :caret (+ (str/index-of text needle) (or delta 0))})))

(deftest run-form-top-level-test
  (let [t "(ns a.b)\n\n(defn f [x]\n  (inc x))\n\n(f 1)\n"]
    (is (= "(defn f [x]\n  (inc x))" (form-at t "inc")) "a nested caret runs its top-level form")
    (is (= "(defn f [x]\n  (inc x))" (form-at t "(defn")) "at the opening bracket")
    (is (= "(defn f [x]\n  (inc x))" (form-at t "(inc x))" 8)) "right after the closing bracket")
    (is (= "(f 1)" (form-at t "(f 1)" 3)))
    (is (= {:text "(f 1)" :start 34 :end 39} (c/run-form {:text t :caret 36})) "the range comes back too")))

(deftest run-form-comment-block-test
  (let [t "(defn where [] 1)\n\n(comment\n  (where)\n  (go! 44)\n  ;; => the reveal\n  (let [w (where)]\n    (:title w))\n  nil)\n"]
    (is (= "(where)" (form-at t "(where)\n" 3)) "the form inside the block, never the block")
    (is (= "(go! 44)" (form-at t "44")))
    (is (= "(let [w (where)]\n    (:title w))" (form-at t ":title")) "a multi-line form, from a nested caret")
    (is (= "(let [w (where)]\n    (:title w))" (form-at t "(:title w))" 11)) "right after it")
    (is (= "nil" (form-at t "nil)")) "an atom in the block")
    (is (nil? (form-at t "comment" 2)) "the comment symbol itself: nothing")
    (is (nil? (form-at t "  (go!" 1)) "whitespace between the block's forms: nothing")
    (is (nil? (form-at t ";; =>" 4)) "a line comment inside the block: nothing")
    (is (= "(defn where [] 1)" (form-at t "where []")))))

(deftest run-form-edge-cases-test
  (testing "the caret on whitespace between top-level forms"
    (is (nil? (form-at "(a)\n\n\n(b)" "\n\n" 1))))
  (testing "strings, comments and characters holding brackets don't count"
    (let [t "(str \"(not a form\" \\) \")\")\n; (nor this\n(b)"]
      (is (= "(str \"(not a form\" \\) \")\")" (form-at t "not a form")))
      (is (nil? (form-at t "nor")))
      (is (= "(b)" (form-at t "(b)" 1)))))
  (testing "reader prefixes belong to the form"
    (is (= "#{1 2}" (form-at "#{1 2}" "1")))
    (is (= "'(a b)" (form-at "x\n'(a b)" "a b")))
    (is (= "@state" (form-at "@state" "state")))
    (is (= "#?(:cljd 1 :clj 2)" (form-at "#?(:cljd 1 :clj 2)" ":clj "))))
  (testing "a top-level atom"
    (is (= ":k" (form-at "(a)\n:k\n" ":k" 1))))
  (testing "an unclosed form has nothing to run; the forms before it still do"
    (is (nil? (form-at "(a)\n(b (c)" "(c)" 1)))
    (is (= "(a)" (form-at "(a)\n(b (c)" "(a)" 1))))
  (testing "a stray closer is ignored"
    (is (= "(b)" (form-at "(a))\n(b)" "(b)" 1))))
  (testing "a comment block that isn't a plain list runs whole"
    (is (= "'(comment (x))" (form-at "'(comment (x))" "(x)" 1))))
  (testing "blank text or no forms: nil"
    (is (nil? (c/run-form {:text "" :caret 0})))
    (is (nil? (c/run-form {:text "  ; only a comment" :caret 3}))))
  (testing "a comment block nested inside another form runs the enclosing form, not drilled"
    (is (= "(do (comment (a)))" (form-at "(do (comment (a)))" "(a)" 1))))
  (testing "CRLF line endings don't shift offsets"
    (let [t "(ns a.b)\r\n\r\n(defn f [x]\r\n  (inc x))\r\n"]
      (is (= "(defn f [x]\r\n  (inc x))" (form-at t "inc"))))))

;; #_ discards its form, ^meta stays attached.
(deftest run-form-discard-test
  (is (nil? (c/run-form {:text "#_(a b)" :caret 3})) "a #_-discarded form isn't runnable")
  (is (nil? (c/run-form {:text "#_(a b)" :caret 0})) "nor is the #_ marker itself")
  (let [t "#_ #_ x y z"]
    (is (nil? (form-at t "x" 0)) "the first of a stacked #_ #_ is discarded")
    (is (nil? (form-at t "y" 0)) "so is the second")
    (is (= "z" (form-at t "z" 0)) "the form after both discards still runs")))

(deftest run-form-metadata-prefix-test
  (is (= "^:private (defn f [] 1)" (form-at "^:private (defn f [] 1)" "defn")) "a ^:private prefix is part of the form")
  (is (= "^{:doc \"x\"} (def a 1)" (form-at "^{:doc \"x\"} (def a 1)" "def a")) "so is a ^{...} map")
  (is (= "^:a ^:b (def x 1)" (form-at "^:a ^:b (def x 1)" "def x")) "stacked metadata prefixes all attach")
  (let [t "^:private (defn f [] 1)"
        {:keys [text start end]} (c/run-form {:text t :caret (str/index-of t "defn")})]
    (is (= text (subs t start end)) "the range still matches the text")))

(deftest parinfer-step-test
  (testing "a mode choice sets it, :next walks off -> Smart -> Indent -> Paren -> off"
    (is (= :paren (c/parinfer-step :off :paren)))
    (is (= :off (c/parinfer-step :smart :off)))
    (is (= [:smart :indent :paren :off]
           (rest (take 5 (iterate #(c/parinfer-step % :next) :off)))))))

(deftest parinfer-label-text-test
  (is (nil? (c/parinfer-label-text :off)) "off hides the label")
  (is (= "Parinfer: Smart" (c/parinfer-label-text :smart)))
  (is (= "Parinfer: Indent" (c/parinfer-label-text :indent)))
  (is (= "Parinfer: Paren" (c/parinfer-label-text :paren))))

(deftest parinfer-effective-test
  (is (= :smart (c/parinfer-effective nil :smart)) "no opinion: the global")
  (is (= :indent (c/parinfer-effective :indent :smart)))
  (is (= :off (c/parinfer-effective :off :smart)) "a project pins off"))

(deftest parinfer-mode-word-test
  (is (= "off" (c/parinfer-mode-word :off)))
  (is (= "Smart" (c/parinfer-mode-word :smart)))
  (is (= "Indent" (c/parinfer-mode-word :indent))))

(deftest parinfer-pinned-note-test
  (is (= " (this project says off)" (c/parinfer-pinned-note :smart :off)))
  (is (= " (this project says Paren)" (c/parinfer-pinned-note :off :paren)))
  (is (nil? (c/parinfer-pinned-note :smart :smart))
      "a global landing on the pin says nothing")
  (is (nil? (c/parinfer-pinned-note :smart nil)) "no override, no note"))

(deftest parinfer-status-text-test
  (is (= "Parinfer: Smart" (c/parinfer-status-text :smart false :smart)))
  (is (= "Parinfer: Smart (project)" (c/parinfer-status-text :smart true :off)))
  (is (nil? (c/parinfer-status-text :off false :off)) "globally off: the label hides")
  (is (= "Parinfer: off (project)" (c/parinfer-status-text :off true :smart))
      "pinned off under a non-off global says why it's quiet")
  (is (nil? (c/parinfer-status-text :off true :off))
      "pinned off under an off global differs from nothing, so it hides"))

;; --- the Paren-stable format pass ----------------

(deftest format-all-op-paren-stable-test
  (let [text "(def a\n  [{:x 1}\n    {:y 2}])"]
    (testing "with a :stabilize function the result takes Paren mode's indentation"
      (is (= "(def a\n  [{:x 1}\n   {:y 2}])"
             (edit/apply-edits text (:edits (c/format-all-op (snap text 0)
                                                             {:stabilize pe/paren-stable}))))))
    (testing "without :stabilize, formatter-clean text is a no-op as today"
      (is (nil? (c/format-all-op (snap text 0)))))
    (testing "a second Paren-stable format is a no-op"
      (is (nil? (c/format-all-op (snap "(def a\n  [{:x 1}\n   {:y 2}])" 0)
                                 {:stabilize pe/paren-stable}))))))

(deftest format-selection-op-paren-stable-test
  (let [a "(def a\n  [{:x 1}\n    {:y 2}])"
        b "(def b\n  [{:x 3}\n    {:y 4}])"
        text (str a "\n\n" b)]
    (testing "only the selection's forms are stabilized"
      (is (= (str "(def a\n  [{:x 1}\n   {:y 2}])" "\n\n" b)
             (edit/apply-edits text (:edits (c/format-selection-op
                                             (snap text 2 10)
                                             {:stabilize pe/paren-stable}))))))
    (testing "without :stabilize, a formatter-clean selection is a no-op as today"
      (is (nil? (c/format-selection-op (snap text 2 10)))))))
