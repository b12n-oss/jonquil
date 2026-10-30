(ns jonquil.parinfer-edit-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.edit :as edit]
            [jonquil.parinfer :as parinfer]
            [jonquil.parinfer-edit :as pe]))

(defn- run
  "adjust on the edit from [before bc] to [after ac] in mode, applied:
  [text caret], or nil when adjust has nothing to do."
  [mode before bc after ac]
  (when-let [{:keys [edits caret]} (pe/adjust {:mode mode :prev-text before :prev-caret bc
                                               :text after :caret ac})]
    [(edit/apply-edits after edits) caret]))

(deftest region-follows-brackets-test
  (let [t "(a)\n\n(b\n c)\n(d)"]
    (is (= [5 11] (pe/region t 9)) "the form around the caret, whole lines")
    (is (= [4 4] (pe/region t 4)) "a blank line between forms is its own region"))
  (is (= [0 7] (pe/region "(a) (b)\n(c)" 1)) "forms sharing a line come out as one"))

(deftest region-falls-back-to-indentation-test
  (let [t "(a\n  \"doc\nline\"\n(b"]
    (is (= [0 15] (pe/region t 11)) "a docstring line at column 0 doesn't start a region")
    (is (= [16 18] (pe/region t 17)))))

(deftest adjust-closes-a-new-form-test
  (is (= ["(a)\n()\n(b)" 5] (run :smart "(a)\n\n(b)" 4 "(a)\n(\n(b)" 5))))

(deftest adjust-dedent-moves-closer-test
  (testing "deleting a body line's indent moves the closer up, inside that form only"
    (is (= ["(z)\n(a)\nb\n(y)" 8] (run :smart "(z)\n(a\n  b)\n(y)" 9 "(z)\n(a\nb)\n(y)" 7)))
    (is (= ["(z)\n(a)\nb\n(y)" 8] (run :indent "(z)\n(a\n  b)\n(y)" 9 "(z)\n(a\nb)\n(y)" 7)))))

(deftest adjust-reindents-an-unstable-form-first-test
  (let [before "(z)\n(fn [a b]\n     (h a))\n(q [c]\n     d)"
        after "(z)\n(fn [a b]\n     (h ax))\n(q [c]\n     d)"
        [t c :as r] (run :smart before 24 after 24)]
    (testing "the first edit re-indents the edited form and moves no closer"
      (is (= ["(z)\n(fn [a b]\n    (h ax))\n(q [c]\n     d)" 23] r)))
    (testing "the next edit finds it stable and runs Smart, which has nothing to change"
      (is (nil? (run :smart t c (str (subs t 0 c) "y" (subs t c)) (inc c)))))))

(deftest adjust-opens-a-paren-in-an-unstable-form-test
  (testing "the indentation is fixed, then the keystroke runs Smart, so ( closes;
           Paren mode alone fails here, and the next edit would find the form unbalanced"
    (is (= ["(z)\n(fn [a b]\n    (h (a)))" 22]
           (run :smart "(z)\n(fn [a b]\n     (h a))" 22 "(z)\n(fn [a b]\n     (h (a))" 23)))))

(deftest adjust-crlf-test
  (is (= ["(a)\r\n()\r\n(b)" 6] (run :smart "(a)\r\n\r\n(b)" 5 "(a)\r\n(\r\n(b)" 6)))
  (is (= ["(a)\n()\n(b)" 5] (run :smart "(a)\n\n(b)" 4 "(a)\n(\n(b)" 5))))

(deftest adjust-nil-cases-test
  (is (nil? (run :smart "(a)" 1 "(a)" 1)) "no change")
  (is (nil? (run :smart "(a b)" 2 "(a xb)" 4)) "Parinfer has nothing to fix")
  (is (nil? (run :paren "(a)\n" 4 "(a)\n(" 5)) "Paren mode fails on an unclosed form"))

(deftest adjust-paste-spans-forms-test
  (is (= ["(a)\n(c)\n(d)\n(b)" 10] (run :smart "(a)\n\n(b)" 4 "(a)\n(c\n(d\n(b)" 9))))

(deftest adjust-skips-a-form-over-max-lines-test
  (testing "a form longer than :max-lines answers ::pe/too-long, so the
           editor can say why nothing happened"
    (is (= ::pe/too-long (pe/adjust {:mode :smart :max-lines 1
                          :prev-text "(z)\n(a\n  b)\n(y)" :prev-caret 9
                          :text "(z)\n(a\nb)\n(y)" :caret 7})))
    (is (some? (pe/adjust {:mode :smart :max-lines 2
                           :prev-text "(z)\n(a\n  b)\n(y)" :prev-caret 9
                           :text "(z)\n(a\nb)\n(y)" :caret 7})))))

(deftest adjust-tab-in-code-keeps-the-keystroke-column-test
  (testing "Paren mode turns a tab in code into two spaces; the keystroke stays before c"
    (let [before "(fn [a b]\n     (h\ta c))"
          i (.indexOf before "c))")
          after (str (subs before 0 i) "x" (subs before i))
          [t] (run :smart before i after (inc i))]
      (is (str/includes? t "a xc")))))

(deftest adjust-whitespace-tidy-is-not-unstable-test
  (testing "a form Paren mode would only tidy (a space before a closer) still runs Smart on a dedent"
    ;; Smart tidies (foo ) itself, since the caret isn't beside it.
    (is (= "(defn f [x]\n  (foo)\n  (bar)\n  baz)"
           (first (run :smart "(defn f [x]\n  (foo )\n  (bar\n    baz))" 30
                       "(defn f [x]\n  (foo )\n  (bar\n  baz))" 28))))))

(deftest adjust-leaves-the-next-form-alone-test
  (testing "Enter at the end of a form doesn't reach the misindented form below"
    (is (nil? (run :smart "(a)\n(fn [a b]\n     (h a))" 3 "(a)\n\n(fn [a b]\n     (h a))" 4))))
  (testing "deleting a whole line doesn't reach the form after it"
    (is (nil? (run :smart "(x)\n(y)\n(fn [a b]\n     (h a))" 8 "(x)\n(fn [a b]\n     (h a))" 4)))))

(deftest region-column-0-comment-stays-inside-a-form-test
  (testing "in unbalanced text a ;; line at column 0 doesn't cut the form above it"
    (is (= [0 29] (pe/region "(defn f [x]\n;; note\n  (g x))\n\n(h" 9)))
    (is (nil? (run :smart "(defn f [x]\n;; note\n  (g x))\n\n(h" 9
                   "(defn f [yx]\n;; note\n  (g x))\n\n(h" 10)))))

(deftest adjust-indent-joins-the-form-above-test
  (testing "indenting a top-level line pulls it into the form above (review I4)"
    (is (= ["(a\n  b)" 5] (run :smart "(a)\nb" 4 "(a)\n  b" 6)))
    (is (= ["(a\n  b)" 5] (run :indent "(a)\nb" 4 "(a)\n  b" 6))))
  (testing "a blank line between doesn't stop the join"
    (is (= ["(a\n\n  b)" 6] (run :smart "(a)\n\nb" 5 "(a)\n\n  b" 7))))
  (testing "indenting a line already inside a form doesn't reach further up"
    (is (nil? (run :smart "(z)\n(a\n  b)" 7 "(z)\n(a\n    b)" 9)))))

(deftest remap-offset-test
  (testing "an offset crosses a fix by its line and column (the fix moves
           brackets, never the user's lines)"
    (is (= 4 (pe/remap-offset "(a\nb\nc)" "(a)\nb\nc" 3)) "start of b")
    (is (= 7 (pe/remap-offset "(a\nb\nc)" "(a)\nb\nc" 7)) "after c, was before )")
    (is (= 2 (pe/remap-offset "ab\ncd" "ab" 4)) "a vanished line clamps to the end")))

(deftest region-crlf-fallback-ends-on-the-line-test
  (testing "in unbalanced CRLF text the region ends before the line's \r"
    (is (= [0 8] (pe/region "(a\r\n  b)\r\n(c\r\n(d" 1)))))

(deftest region-regex-column-0-stays-inside-test
  (testing "a multi-line regex's column-0 line doesn't split a fallback region"
    (is (= [0 14] (pe/region "(def r #\"a\nb\")\n(h" 2)))))

(deftest adjust-caps-the-after-text-too-test
  (testing "a long paste onto a blank line answers ::pe/too-long (the cap
           counted only the before-text's one line)"
    (let [block (clojure.string/join "\n" (repeat 400 "(x)"))]
      (is (= ::pe/too-long
             (pe/adjust {:mode :smart :prev-text "(a)\n\n(b)" :prev-caret 4
                         :text (str "(a)\n" block "\n(b)") :caret (+ 4 (count block))}))))))

(deftest adjust-reports-an-engine-failure-test
  (testing "an \"unhandled\" engine result (a Jolt shim gap) is named, not nil"
    (with-redefs-fn {#'parinfer/smart-mode
                     (fn [& _] {:success false :error {:name "unhandled" :message "boom"}})}
      #(is (= {:engine-error "boom"}
              (pe/adjust {:mode :smart :prev-text "(a b)" :prev-caret 2
                          :text "(a xb)" :caret 3})))))
  (testing "an \"unhandled\" during the unstable form's re-apply is named too,
           not silently replaced by Paren mode's fix (polish review I1)"
    (with-redefs-fn {#'parinfer/smart-mode
                     (fn [& _] {:success false :error {:name "unhandled" :message "boom"}})}
      #(is (= {:engine-error "boom"}
              (pe/adjust {:mode :smart :prev-text "(a\nb)" :prev-caret 1
                          :text "(xa\nb)" :caret 2})))))
  (testing "Parinfer's own refusal stays nil (normal typing)"
    (is (nil? (run :paren "(a)\n" 4 "(a)\n(" 5)))))

(deftest adjust-bounds-equals-diff-test
  (testing "a :bounds hint computed like diff's own prefix/suffix changes nothing,
           over every adjust case this file already pins"
    (doseq [[mode prev pc text c] [[:smart "(a)\n\n(b)" 4 "(a)\n(\n(b)" 5]
                                   [:smart "(z)\n(a\n  b)\n(y)" 9 "(z)\n(a\nb)\n(y)" 7]
                                   [:indent "(z)\n(a\n  b)\n(y)" 9 "(z)\n(a\nb)\n(y)" 7]
                                   [:smart "(z)\n(fn [a b]\n     (h a))" 22 "(z)\n(fn [a b]\n     (h (a))" 23]
                                   [:smart "(a)\r\n\r\n(b)" 5 "(a)\r\n(\r\n(b)" 6]
                                   [:smart "(a)\n\n(b)" 4 "(a)\n(c\n(d\n(b)" 9]
                                   [:smart "(a)\nb" 4 "(a)\n  b" 6]
                                   [:paren "(a)\n" 4 "(a)\n(" 5]]]
      (let [n (count prev) m (count text)
            p (loop [i 0] (if (and (< i (min n m)) (= (nth prev i) (nth text i))) (recur (inc i)) i))
            p (min p c)
            q (loop [i 0] (if (and (< i (- (min n m) p))
                                   (= (nth prev (- n 1 i)) (nth text (- m 1 i))))
                            (recur (inc i)) i))]
        (is (= (pe/adjust {:mode mode :prev-text prev :prev-caret pc :text text :caret c})
               (pe/adjust {:mode mode :prev-text prev :prev-caret pc :text text :caret c
                           :bounds [p q]}))
            (pr-str [mode prev text [p q]]))))))

(deftest adjust-bounds-overlap-clamps-test
  (testing "overlapping prefix+suffix (a selection retyped as itself plus one
           char) clamps as diff does"
    (is (= (pe/adjust {:mode :smart :prev-text "(aa)" :prev-caret 3 :text "(aaa)" :caret 4})
           (pe/adjust {:mode :smart :prev-text "(aa)" :prev-caret 3 :text "(aaa)" :caret 4
                       :bounds [3 3]})))))

(deftest adjust-prev-consistent-skips-the-reindent-test
  (let [before "(z)\n(fn [a b]\n     (h a))"
        after "(z)\n(fn [a b]\n     (h ax))"]
    (testing "without the flag, the unstable form is re-indented first (today's pin)"
      (is (= ["(z)\n(fn [a b]\n    (h ax))" 24]
             (run :smart before 24 after 25))))
    (testing "with the flag the stability probe is skipped and Smart runs as is;
             on text that IS unstable that means no re-indent on this edit,
             which is the contract: the editor only sets the flag when the
             edit starts in the form its own previous action probed"
      (let [r (pe/adjust {:mode :smart :prev-text before :prev-caret 24
                          :text after :caret 25 :prev-consistent? true})]
        (is (map? r))
        (is (not= ["(z)\n(fn [a b]\n    (h ax))" 24]
                  [(edit/apply-edits after (:edits r)) (:caret r)]))))))

(deftest adjust-bounds-skips-the-text-diff-test
  (testing "with :bounds the prev/text diff never runs (the R/NR one still does)"
    (let [calls (atom 0)
          real @#'pe/diff]
      (with-redefs-fn {#'pe/diff (fn [& a] (swap! calls inc) (apply real a))}
        #(do (pe/adjust {:mode :smart :prev-text "(a)\n\n(b)" :prev-caret 4
                         :text "(a)\n(\n(b)" :caret 5 :bounds [4 4]})
             (is (= 1 @calls) "only the fix's own diff"))))))

(deftest adjust-signal-bounds-shared-affix-test
  (testing "signal-exact bounds (what GTK reports) whose replacement shares
           an affix with the replaced text give diff's answer, not the
           raw span: the engine keys its change lookup on the span's end
           (speedup review I4: select \"a(\" and type \"(\")"
    (let [prev "(f a(b\n      c))" after "(f (b\n      c))"]
      (is (= (pe/adjust {:mode :smart :prev-text prev :prev-caret 5 :text after :caret 4})
             (pe/adjust {:mode :smart :prev-text prev :prev-caret 5 :text after :caret 4
                         :bounds [3 11]})))))
  (testing "a small brute force over selection retypes agrees everywhere"
    (doseq [prev ["(a x(b\n     c)\n   d)" "(f a(b\n      c))" "(aa (bb)\n  cc)"]
            a (range 1 5) w [1 2] rep ["(" "((" "x(" "(b"]]
      (let [b (min (count prev) (+ a w))
            after (str (subs prev 0 a) rep (subs prev b))
            caret (+ a (count rep))
            no-bounds (pe/adjust {:mode :smart :prev-text prev :prev-caret b
                                  :text after :caret caret})
            spanb [a (- (count prev) b)]
            with-bounds (pe/adjust {:mode :smart :prev-text prev :prev-caret b
                                    :text after :caret caret :bounds spanb})]
        (is (= no-bounds with-bounds) (pr-str [prev a b rep]))))))

;; --- paren-stable (the format-interplay arc) --------------------------------

(deftest paren-stable-shifts-a-wrapped-line-test
  (is (= "(def a\n  [{:x 1}\n   {:y 2}])"
         (pe/paren-stable "(def a\n  [{:x 1}\n    {:y 2}])"))
      "the wrapped vector line takes Paren mode's one-space shift"))

(deftest paren-stable-is-a-fixpoint-test
  (let [t "(def a\n  [{:x 1}\n   {:y 2}])"]
    (is (= t (pe/paren-stable t)) "already-stable text comes back as given")))

(deftest paren-stable-leaves-unbalanced-text-test
  (let [t "(def a\n  [{:x 1}\n    {:y 2}]"]
    (is (= t (pe/paren-stable t))
        "Paren mode would move brackets there, so unbalanced text stays whole")))

(deftest paren-stable-covers-a-long-form-test
  (let [big (str "(def big\n  [{:x 1}\n"
                 (apply str (repeat pe/default-max-lines "    {:y 2}\n"))
                 "    {:y 3}])")
        big-stable (str "(def big\n  [{:x 1}\n"
                        (apply str (repeat pe/default-max-lines "   {:y 2}\n"))
                        "   {:y 3}])")
        small "(def a\n  [{:x 1}\n    {:y 2}])"
        out (pe/paren-stable (str big "\n\n" small))]
    (is (str/starts-with? out big-stable)
        "a form over the keystroke limit is stabilized too: the user
        accepted the format's cost, so the guarantee has no carve-out")
    (is (str/ends-with? out "(def a\n  [{:x 1}\n   {:y 2}])")
        "the small sibling is stabilized as well")))

(deftest paren-stable-range-touches-only-its-forms-test
  (let [a "(def a\n  [{:x 1}\n    {:y 2}])"
        b "(def b\n  [{:x 3}\n    {:y 4}])"]
    (is (= (str "(def a\n  [{:x 1}\n   {:y 2}])" "\n\n" b)
           (pe/paren-stable (str a "\n\n" b) 0 3))
        "a range inside form a leaves form b as given")))

(deftest paren-stable-crlf-test
  (is (= "(def a\r\n  [{:x 1}\r\n   {:y 2}])"
         (pe/paren-stable "(def a\r\n  [{:x 1}\r\n    {:y 2}])"))))

(deftest paren-stable-comments-untouched-test
  (is (= ";; a comment\n(def a\n  [{:x 1}\n   {:y 2}])\n;; after"
         (pe/paren-stable ";; a comment\n(def a\n  [{:x 1}\n    {:y 2}])\n;; after"))
      "comment lines around a form stay as given"))

(deftest paren-stable-accepts-a-paren-trail-tidy-test
  (is (= "(def a\n  [{:x 1}\n   {:y 2}])"
         (pe/paren-stable "(def a\n  [{:x 1 }\n    {:y 2}])"))
      "a space tidied out of a paren trail must not reject the reindent
      (review I1: the formatter keeps mid-line spaces, so its output can
      carry one; rejecting the form recreates the first-keystroke fight)"))

(deftest paren-stable-accepts-a-tab-rewrite-test
  (is (= "(def a\n  [{:x  1}\n   {:y 2}])"
         (pe/paren-stable "(def a\n  [{:x\t1}\n    {:y 2}])"))
      "a tab in code becoming two spaces must not reject the reindent
      (review I1: the formatter only touches leading and trailing
      whitespace, so a mid-line tab survives into its output)"))
