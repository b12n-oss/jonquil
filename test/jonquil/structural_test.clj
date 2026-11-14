(ns jonquil.structural-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.edit :as edit]
            [jonquil.structural :as se]))

(defn- snap
  "A snapshot from text with | marking the caret."
  [marked]
  (let [caret (str/index-of marked "|")]
    {:text (str/replace marked "|" "") :caret caret :sel-start caret :sel-end caret}))

(defn- after
  "What op makes of marked: the new text with | at the new caret, or nil
  when the op doesn't apply."
  [op marked]
  (let [s (snap marked)]
    (when-let [{:keys [edits caret]} (op s)]
      (let [text (edit/apply-edits (:text s) edits)]
        (str (subs text 0 caret) "|" (subs text caret))))))

(deftest slurp-forward-test
  (is (= "(a| b) c" (after se/slurp-forward "(a|) b c")))
  (is (= "(a| (b c)) d" (after se/slurp-forward "(a|) (b c) d")))
  (is (= "[a {:k| 1 x}] y" (after se/slurp-forward "[a {:k| 1} x] y")) "the innermost list slurps")
  (is (= "(|a)" (after se/slurp-forward "(|) a")) "an empty list takes the form without a gap")
  (is (= "(a| 'b)" (after se/slurp-forward "(a|) 'b")) "a reader prefix comes along")
  (is (= "(a| \"x y\")" (after se/slurp-forward "(a|) \"x y\"")))
  (is (nil? (after se/slurp-forward "(a|)")) "nothing to slurp")
  (is (nil? (after se/slurp-forward "a| b")) "not inside a list")
  (is (= "(a (b|) c)" (after se/slurp-forward "(a (b|)) c")) "with nothing after the inner list, the next one out slurps"))

(deftest barf-forward-test
  (is (= "(a|) b" (after se/barf-forward "(a| b)")))
  (is (= "(a) |b" (after se/barf-forward "(a |b)")) "the caret stays on its text")
  (is (= "(|) a" (after se/barf-forward "(|a)")) "the only form leaves an empty list")
  (is (= "(a|) (b c)" (after se/barf-forward "(a| (b c))")))
  (is (nil? (after se/barf-forward "(|)")))
  (is (nil? (after se/barf-forward "a| b"))))

(deftest slurp-backward-test
  (is (= "(a |b)" (after se/slurp-backward "a (|b)")))
  (is (= "x (a |b)" (after se/slurp-backward "x a (|b)")))
  (is (= "#{a |b}" (after se/slurp-backward "a #{|b}")) "the opener moves whole, prefix and all")
  (is (nil? (after se/slurp-backward "(|b)")))
  (is (nil? (after se/slurp-backward "a |b"))))

(deftest barf-backward-test
  (is (= "a (|b)" (after se/barf-backward "(a |b)")))
  (is (= "a (b| c)" (after se/barf-backward "(a b| c)")))
  (is (= "a (|)" (after se/barf-backward "(a|)")) "the only form leaves an empty list")
  (is (nil? (after se/barf-backward "(|)")))
  (is (nil? (after se/barf-backward "a |b"))))

(deftest raise-test
  (is (= "|b" (after se/raise "(a |b c)")))
  (is (= "(x |(b c))" (after se/raise "(x (a |(b c) d))")))
  (is (= "(x b|ar)" (after se/raise "(x (foo b|ar))")) "the caret keeps its place in the form")
  (is (nil? (after se/raise "|a b")) "nothing to raise out of")
  (is (nil? (after se/raise "(|)")) "no form at the caret"))

(deftest splice-test
  (is (= "a |b c" (after se/splice "(a |b c)")))
  (is (= "(x a |b)" (after se/splice "(x [a |b])")))
  (is (= "a |b" (after se/splice "#{a |b}")) "the prefix goes with the opener")
  (is (nil? (after se/splice "a |b"))))

(deftest wrap-test
  (is (= "(|a) b" (after se/wrap-round "|a b")))
  (is (= "x [f|oo] y" (after se/wrap-square "x f|oo y")))
  (is (= "{|(a b)}" (after se/wrap-curly "|(a b)")))
  (is (= "(a (|b))" (after se/wrap-round "(a |b)")))
  (is (= "a (|)" (after se/wrap-round "a |")) "with no form at the caret, an empty pair")
  (is (= "(a (|))" (after se/wrap-round "(a |)"))))

(deftest kill-form-test
  (is (= "(a |c)" (after se/kill-form "(a |b c)")) "the form and the gap after it")
  (is (= "(a|)" (after se/kill-form "(a |b)")) "the last form takes the gap before it")
  (is (= "(|)" (after se/kill-form "(|a)")))
  (is (= "(a |d)" (after se/kill-form "(a |(b c) d)")))
  (is (= "(a |c)" (after se/kill-form "(a b|ar c)")) "the caret inside a form kills that form")
  (is (= "bar" (:copy (se/kill-form (snap "(a b|ar c)")))) "and says what it removed, for the clipboard")
  (is (nil? (after se/kill-form "(|)")))
  (is (nil? (se/kill-form (snap "  |  ")))))

(deftest copy-form-test
  (is (= {:copy "(b c)"} (se/copy-form (snap "(a |(b c) d)"))))
  (is (= {:copy "bar"} (se/copy-form (snap "(a b|ar)"))))
  (is (nil? (se/copy-form (snap "(|)")))))

(deftest move-test
  (testing "forward: to the end of the form at or after the caret, else out past the closer"
    (is (= "(a b| c)" (after se/forward "(a |b c)")))
    (is (= "(a (b c)| d)" (after se/forward "(a| (b c) d)")))
    (is (= "(a bar| c)" (after se/forward "(a b|ar c)")))
    (is (= "(a b)| c" (after se/forward "(a b|) c")))
    (is (nil? (after se/forward "a b|"))))
  (testing "backward: to the start of the form before the caret, else out before the opener"
    (is (= "(a |b c)" (after se/backward "(a b| c)")))
    (is (= "(a |(b c) d)" (after se/backward "(a (b c)| d)")))
    (is (= "(a |bar c)" (after se/backward "(a b|ar c)")))
    (is (= "x |(a b)" (after se/backward "x (|a b)")))
    (is (nil? (after se/backward "|a b"))))
  (testing "up: to the opener of the list around the caret"
    (is (= "(a |(b c))" (after se/up "(a (b |c))")))
    (is (= "|#{a b}" (after se/up "#{a |b}")))
    (is (nil? (after se/up "a |b"))))
  (testing "down: into the next list at or after the caret"
    (is (= "(a (|b c))" (after se/down "(a| (b c))")))
    (is (= "x [|y]" (after se/down "|x [y]")))
    (is (nil? (after se/down "(a |b)")))))

(deftest unfinished-text-test
  (testing "an unclosed list has no forms to work on, so nothing applies and nothing throws"
    (doseq [op [se/slurp-forward se/barf-forward se/slurp-backward se/barf-backward se/raise
                se/splice se/kill-form se/copy-form se/forward se/backward se/up se/down]]
      (is (nil? (op (snap "(a |b"))))))
  (testing "a string's and a comment's brackets don't count"
    (is (= "(a| \"(\")" (after se/slurp-forward "(a|) \"(\"")))
    (is (= "(a| b) ; (x" (after se/slurp-forward "(a|) b ; (x")))))

;; --- the rest of the paredit set ---------------------------------------------

(defn- snap-sel
  "A snapshot with the selection between [ and ] marks and the caret at ]."
  [marked]
  (let [a (str/index-of marked "<")
        b (dec (str/index-of marked ">"))]
    {:text (-> marked (str/replace "<" "") (str/replace ">" "")) :caret b :sel-start a :sel-end b}))

(defn- selected
  "The text an op selects in marked (| caret or <..> selection), or nil."
  [op marked]
  (let [s (if (str/includes? marked "<") (snap-sel marked) (snap marked))]
    (when-let [{[a b] :sel} (op s)]
      (when (and a b) (subs (:text s) a b)))))

(deftest expand-and-shrink-selection-test
  (se/forget-selection!)
  (testing "expand: the form at the caret, then each list around it"
    (is (= "bar" (selected se/expand-selection "(foo (b|ar baz))")))
    (is (= "(bar baz)" (selected se/expand-selection "(foo (<bar> baz))")))
    (is (= "(foo (bar baz))" (selected se/expand-selection "(foo <(bar baz)>)")))
    (is (nil? (se/expand-selection (snap-sel "<(foo)>"))) "nothing larger to take"))
  (testing "shrink goes back the way expand came"
    (se/forget-selection!)
    (let [text "(foo (bar baz))"
          s0 {:text text :caret 7 :sel-start 7 :sel-end 7}
          e1 (se/expand-selection s0)
          s1 (assoc s0 :caret (:caret e1) :sel-start (first (:sel e1)) :sel-end (second (:sel e1)))
          e2 (se/expand-selection s1)
          s2 (assoc s0 :caret (:caret e2) :sel-start (first (:sel e2)) :sel-end (second (:sel e2)))]
      (is (= [6 9] (:sel e1)))
      (is (= [5 14] (:sel e2)))
      (is (= [6 9] (:sel (se/shrink-selection s2))))
      (is (= 7 (:caret (se/shrink-selection s1))) "back to the bare caret")
      (is (nil? (:sel (se/shrink-selection s1))))))
  (testing "shrink with nothing remembered does nothing"
    (se/forget-selection!)
    (is (nil? (se/shrink-selection (snap-sel "(<foo> bar)"))))))

(deftest select-forward-and-backward-test
  (is (= "b" (selected se/select-forward "(a |b c)")))
  (is (= "b c" (selected se/select-forward "(a <b> c)")))
  (is (nil? (se/select-forward (snap-sel "(a <b>)"))))
  (is (= "a" (selected se/select-backward "(a| b)")))
  (is (= "a b" (selected se/select-backward "(a <b> c)")))
  (is (nil? (se/select-backward (snap "(|a)")))))

(deftest drag-test
  (is (= "(b |a c)" (after se/drag-forward "(|a b c)")))
  (is (= "(a c |b)" (after se/drag-forward "(a |b c)")))
  (is (= "(a c b|ar)" (after se/drag-forward "(a b|ar c)")) "the caret keeps its place in the form")
  (is (nil? (after se/drag-forward "(a |b)")))
  (is (= "(|b a c)" (after se/drag-backward "(a |b c)")))
  (is (= "(|(x) a)" (after se/drag-backward "(a |(x))")) "the caret stays on the dragged form")
  (is (nil? (after se/drag-backward "(|a b)"))))

(deftest transpose-test
  (is (= "(b a| c)" (after se/transpose "(a| b c)")))
  (is (= "(b a| c)" (after se/transpose "(a |b c)")))
  (is (= "((y) (x)|)" (after se/transpose "((x)| (y))")))
  (is (nil? (after se/transpose "(|a b)")))
  (is (nil? (after se/transpose "(a b|)"))))

(deftest join-and-split-test
  (testing "join two lists of one kind, or two strings"
    (is (= "(a| b)" (after se/join "(a)| (b)")))
    (is (= "[a| b]" (after se/join "[a] |[b]")))
    (is (= "\"ab|cd\"" (after se/join "\"ab\"| \"cd\"")))
    (is (nil? (after se/join "(a)| [b]")) "different brackets don't join")
    (is (nil? (after se/join "a| b"))))
  (testing "split the list at the caret, or the string"
    (is (= "(a)| (b)" (after se/split "(a |b)")))
    (is (= "[a b]| [c]" (after se/split "[a b| c]")))
    (is (= "(f \"ab\"| \"cd\")" (after se/split "(f \"ab|cd\")")))
    (is (nil? (after se/split "a |b")))))

(deftest kill-more-test
  (testing "to the end and the start of the list"
    (is (= "(a |)" (after se/kill-list-forward "(a |b c)")))
    (is (= "b c" (:copy (se/kill-list-forward (snap "(a |b c)")))))
    (is (= "(|b c)" (after se/kill-list-backward "(a |b c)")))
    (is (nil? (after se/kill-list-forward "a |b"))))
  (testing "right: to the end of the line, taking whole any form that starts on it"
    (is (= "(a |\n b)" (after se/kill-right "(a |x y\n b)")))
    (is (= "(a |\n c)" (after se/kill-right "(a |(x\n y)\n c)")))
    (is (= "(a |)" (after se/kill-right "(a |b c)")) "never past the list's closer")
    (is (= "(f \"ab|\")" (after se/kill-right "(f \"ab|cd\")")) "inside a string, to its closing quote"))
  (testing "left: to the start of the line, the same way"
    (is (= "(a\n|y)" (after se/kill-left "(a\n x |y)")))
    (is (= "(|c)" (after se/kill-left "(a b |c)")))
    (is (= "(f \"|cd\")" (after se/kill-left "(f \"ab|cd\")"))))
  (testing "the form before the caret"
    (is (= "(a | c)" (after se/kill-form-backward "(a b| c)")))
    (is (= "(a |c)" (after se/kill-form-backward "(a b |c)")))
    (is (nil? (after se/kill-form-backward "(|a)")))))

(deftest splice-killing-test
  (is (= "x a | y" (after se/splice-killing-forward "x (a |b c) y")))
  (is (= "x |c y" (after se/splice-killing-backward "x (a b |c) y")))
  (is (nil? (after se/splice-killing-forward "a |b"))))

(deftest convolute-test
  (is (= "(frob (let [x 1] |a b))" (after se/convolute "(let [x 1] (frob |a b))")))
  (is (= "(g (f |x y))" (after se/convolute "(f (g |x) y)")))
  (is (nil? (after se/convolute "(f |x)")) "needs a list around the list"))

(deftest wrap-and-rewrap-test
  (is (= "#{|a} b" (after se/wrap-set "|a b")))
  (is (= "\"|a\" b" (after se/wrap-quote "|a b")))
  (is (= "[a |b]" (after se/rewrap-square "(a |b)")))
  (is (= "(a |b)" (after se/rewrap-round "{a |b}")))
  (is (= "{a |b}" (after se/rewrap-curly "[a |b]")))
  (is (= "#{a |b}" (after se/rewrap-set "(a |b)")))
  (is (= "(a |b)" (after se/rewrap-round "#{a |b}")) "a set's # goes with its brace")
  (is (= "'[a |b]" (after se/rewrap-square "'(a |b)")) "a quote stays")
  (is (nil? (after se/rewrap-round "a |b"))))

(deftest more-moves-test
  (is (= "(a (b c)| d)" (after se/forward-up "(a (b |c) d)")))
  (is (= "(a (b c|) d)" (after se/backward-down "(a (b c) |d)")))
  (is (= "(a b c|)" (after se/list-end "(a |b c)")))
  (is (= "(|a b c)" (after se/list-start "(a b |c)")))
  (is (nil? (after se/forward-up "a |b")))
  (is (nil? (after se/backward-down "(|a b)")))
  (is (nil? (after se/list-end "a |b"))))
