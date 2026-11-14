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
