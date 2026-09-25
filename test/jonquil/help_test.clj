(ns jonquil.help-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.help :as help]))

(defn- snap
  ([text caret] {:text text :caret caret :sel-start caret :sel-end caret})
  ([text caret s e] {:text text :caret caret :sel-start s :sel-end e}))

(def cands [{:symbol "map" :category :builtin}
            {:symbol "mapv" :category :builtin}
            {:symbol "max" :category :builtin}])

(deftest prefix-at-test
  (is (= {:prefix "ma" :start 1 :end 3 :word-end 3} (help/prefix-at (snap "(ma" 3))))
  (is (= {:prefix "ma" :start 1 :end 3 :word-end 4} (help/prefix-at (snap "(map" 3))))
  (is (nil? (help/prefix-at (snap "(ma " 4))) "after a space there is no prefix")
  (is (nil? (help/prefix-at (snap "" 0))))
  (is (nil? (help/prefix-at (snap "(ma" 3 1 3))) "a selection means no completion")
  (is (= "ma" (:prefix (help/prefix-at (snap "😀 (ma" 5)))) "character offsets past an emoji")
  (is (= "안" (:prefix (help/prefix-at (snap "(안" 2))))))

(deftest open-and-move-test
  (let [p (help/prefix-at (snap "(ma" 3))
        s (help/open-state p cands)]
    (is (= 0 (:index s)))
    (is (false? (:single? s)))
    (is (= "map" (:symbol (help/selected s))))
    (is (= "max" (:symbol (help/selected (help/move s 5)))) "clamped at the end")
    (is (= "map" (:symbol (help/selected (help/move s -3)))) "clamped at the start")
    (is (true? (:single? (help/open-state p [(first cands)]))))
    (is (nil? (help/open-state p [])))
    (is (nil? (help/open-state nil cands)))))

(deftest commit-op-test
  (let [s (help/move (help/open-state (help/prefix-at (snap "(ma x)" 3)) cands) 1)]
    (is (= {:edits [[1 3 "mapv"]] :caret 5 :sel nil} (help/commit-op s))))
  (testing "the whole word around the caret is replaced"
    (let [s (help/open-state (help/prefix-at (snap "(mapx)" 3)) cands)]
      (is (= {:edits [[1 5 "map"]] :caret 4 :sel nil} (help/commit-op s))))))

(deftest refine-test
  (let [s (help/open-state (help/prefix-at (snap "(ma" 3)) cands)]
    (is (= "map" (:prefix (:prefix-info (help/refine s (snap "(map" 4) [(first cands)])))))
    (is (nil? (help/refine s (snap "(ma " 4) cands)) "a space closes it")
    (is (nil? (help/refine s (snap "(ma (x" 6) cands)) "a different word closes it")
    (is (nil? (help/refine s (snap "(map" 4) [])) "no candidates left closes it")))

(deftest target-symbol-test
  (is (= "greet" (help/target-symbol (snap "(greet \"x\")" 3))))
  (is (= "greet" (help/target-symbol (snap "(greet \"x\")" 0 1 6))))
  (is (nil? (help/target-symbol (snap "(greet \"x\")" 0 0 6))) "a selection that isn't one symbol")
  (is (nil? (help/target-symbol (snap "; greet" 4))) "inside a comment")
  (is (nil? (help/target-symbol (snap "(f \"greet\")" 6))) "inside a string"))

(deftest jump-target-test
  (let [text "(defn greet [name] name)
(greet "x")"]
    (is (= {:kind :buffer :offset 6} (help/jump-target (snap text 27) nil)))
    (testing "a local binding, with the caret anywhere in the usage"
      (doseq [c [19 20 23]]
        (is (= {:kind :buffer :offset 13} (help/jump-target (snap text c) nil)) (str "caret " c))))
    (is (= :external (:kind (help/jump-target (snap "(map inc [1])" 2) {:status :found :name "map" :ns "clojure.core"}))))
    (is (nil? (help/jump-target (snap "(nope 1)" 2) {:status :not-found})))
    (is (nil? (help/jump-target (snap "; nope" 3) nil)) "no symbol at the caret")))

(deftest doc-text-test
  (is (re-find #"No documentation found" (help/doc-text "zzz" nil)))
  (is (re-find #"clojure.core/map" (help/doc-text "map" {:status :found :ns "clojure.core" :name "map" :arglists "([f coll])" :doc "Returns a lazy sequence."}))))

(deftest rename-problem-test
  (let [s (snap "(defn greet [n] n)\n(greet 1)" 8)]
    (is (nil? (help/rename-problem s "hello")))
    (is (= "Type a new name." (help/rename-problem s "  ")))
    (is (= "That's the current name." (help/rename-problem s "greet")))
    (is (= "Can't rename 'greet' to 'a b'." (help/rename-problem s "a b")))
    (is (= "Put the caret on a symbol first." (help/rename-problem (snap "; x" 2) "y")))))
