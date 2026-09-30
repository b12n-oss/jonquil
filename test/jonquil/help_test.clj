(ns jonquil.help-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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
(greet \"x\")"]
    (is (= {:kind :buffer :offset 6} (help/jump-target (snap text 27) nil)))
    (testing "a local binding, with the caret anywhere in the usage"
      (doseq [c [19 20 23]]
        (is (= {:kind :buffer :offset 13} (help/jump-target (snap text c) nil)) (str "caret " c))))
    (is (= :external (:kind (help/jump-target (snap "(map inc [1])" 2) {:status :found :name "map" :ns "clojure.core"}))))
    (is (nil? (help/jump-target (snap "(nope 1)" 2) {:status :not-found})))
    (is (nil? (help/jump-target (snap "; nope" 3) nil)) "no symbol at the caret")))

(deftest jump-target-scope-test
  (testing "a param shadows a top-level def of the same name"
    (let [text "(def x 1)\n(defn f [x] x)"]
      (is (= {:kind :buffer :offset 19} (help/jump-target (snap text 22) nil)))))
  (testing "a let binding shadows a top-level def of the same name"
    (let [text "(def y 1)\n(defn f [x] (let [y x] y))"]
      (is (= {:kind :buffer :offset 28} (help/jump-target (snap text 33) nil)))))
  (testing "nested lets: the innermost binding wins"
    (let [text "(let [x 1] (let [x 2] x))"]
      (is (= {:kind :buffer :offset 17} (help/jump-target (snap text 22) nil)))))
  (testing "a binding whose scope doesn't contain the caret is never chosen"
    (let [text "(defn f [] (let [x 1] x))\n(defn g [] x)"]
      (is (nil? (help/jump-target (snap text 37) nil)))))
  (testing "an unbound usage gives nil, never an earlier usage of the same name"
    (let [text "(println zz)\n(inc zz)"]
      (is (nil? (help/jump-target (snap text 18) nil)))))
  (testing "multi-arity fn: each arity's own params, not the other arity's"
    (let [text "(defn f ([x] x) ([x y] (+ x y)))"]
      (is (= {:kind :buffer :offset 20} (help/jump-target (snap text 28) nil)) "second arity's y")
      (is (= {:kind :buffer :offset 18} (help/jump-target (snap text 26) nil))
          "second arity's own x, not the first arity's")))
  (testing "destructuring, where cheap: :keys and a vector pattern"
    (let [text "(defn f [{:keys [a b]}] a)"]
      (is (= {:kind :buffer :offset 17} (help/jump-target (snap text 24) nil))))
    (let [text "(defn f [[a b]] b)"]
      (is (= {:kind :buffer :offset 12} (help/jump-target (snap text 16) nil)))))
  (testing "jump-local-binding's own case is unchanged"
    (let [text "(defn f [x] (let [y x] y))"]
      (is (= {:kind :buffer :offset 18} (help/jump-target (snap text 23) nil)))))
  (testing "quoted data whose first child is a vector is never a bare arity clause"
    (let [text "(defn f [q] (println '([q] q)))"]
      (is (= {:kind :buffer :offset 9} (help/jump-target (snap text 27) nil))
          "the real param, not the quoted data's own q at 24")))
  (testing "a quoted vector inside a doseq binding is never mistaken for a binding"
    (let [text "(defn handler [msg] (doseq [rule '([msg :a] [msg :b])] (println msg)))"]
      (is (= {:kind :buffer :offset 15} (help/jump-target (snap text 36) nil))
          "handler's own param, never itself")
      (is (= {:kind :buffer :offset 15} (help/jump-target (snap text 45) nil))
          "handler's own param, never the sibling quoted vector")))
  (testing "when-let's own binding shadows a top-level def of the same name"
    (let [text "(def y 1)\n(defn f [] (when-let [y 2] y))"]
      (is (= {:kind :buffer :offset 32} (help/jump-target (snap text 37) nil)))))
  (testing "if-let's own binding is in scope in the THEN branch"
    (let [text "(def z 1)\n(defn f [] (if-let [z 2] z z))"]
      (is (= {:kind :buffer :offset 30} (help/jump-target (snap text 35) nil)))))
  (testing "if-let's own binding is NOT in scope in the ELSE branch"
    (let [text "(def z 1)\n(defn f [] (if-let [z 2] z z))"]
      (is (= {:kind :buffer :offset 5} (help/jump-target (snap text 37) nil))
          "the top-level def, since the if-let binding never reaches the else branch"))))

(deftest doc-text-test
  (is (re-find #"No documentation found" (help/doc-text "zzz" nil)))
  (is (re-find #"clojure.core/map" (help/doc-text "map" {:status :found :ns "clojure.core" :name "map" :arglists "([f coll])" :doc "Returns a lazy sequence."}))))

(deftest location-line-test
  (is (= "Defined in /p/src/demo/core.clj:4" (help/location-line "/p/src/demo/core.clj" 4)))
  (is (= "Defined in clojure-1.12.5.jar!/clojure/core.clj:2748"
         (help/location-line "jar:file:/Users/x/.m2/repository/org/clojure/clojure/1.12.5/clojure-1.12.5.jar!/clojure/core.clj" 2748)))
  (is (= "Defined in /p/a.clj" (help/location-line "/p/a.clj" nil)))
  (is (nil? (help/location-line nil 4))))

(deftest doc-text-location-test
  (let [d {:status :found :symbol "greet" :ns "demo.core" :name "greet" :arglists "([who])" :doc "Says hello."}]
    (is (str/ends-with? (help/doc-text "greet" (assoc d :location "Defined in /p/core.clj:4")) "\n\nDefined in /p/core.clj:4"))
    (is (= (help/doc-text "greet" d) (help/doc-text "greet" (assoc d :file "unknown" :line 3)))
        "ruling 13: :file alone adds nothing")))

(deftest full-doc-status-test
  (is (= "This server has no full-doc op." (help/full-doc-status "map" {:status :no-op})))
  (is (= "Full docs for map timed out." (help/full-doc-status "map" {:status :timeout})))
  (is (= "No docs: Jonquil isn't attached to the server yet. Run something first."
         (help/full-doc-status "map" {:status :not-attached})))
  (is (= "No docs: demo.never isn't loaded in the server yet. Run the file first."
         (help/full-doc-status "map" {:status :not-loaded :ns "demo.never"})))
  (is (= "No full docs: the server doesn't know map."
         (help/full-doc-status "map" {:status :not-found})))
  (is (nil? (help/full-doc-status "map" {:status :ok :text "x"}))))

(deftest rename-problem-test
  (let [s (snap "(defn greet [n] n)\n(greet 1)" 8)]
    (is (nil? (help/rename-problem s "hello")))
    (is (= "Type a new name." (help/rename-problem s "  ")))
    (is (= "That's the current name." (help/rename-problem s "greet")))
    (is (= "Can't rename 'greet' to 'a b'." (help/rename-problem s "a b")))
    (is (= "Put the caret on a symbol first." (help/rename-problem (snap "; x" 2) "y")))))
