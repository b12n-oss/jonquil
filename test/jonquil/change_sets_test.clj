(ns jonquil.change-sets-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.change-sets :as cs]
            [jonquil.changed :as changed]
            [jonquil.edit :as edit]))

(def last-run "(ns a)\n\n(defn f [x] (inc x))\n\n(defn g [] 1)\n")
(def now "(ns a)\n\n(defn f [x] (+ x 2))\n\n(defn g [] 1)\n\n(defn h [] 3)\n")

(deftest from-changed-test
  (let [es (cs/from-changed now last-run "/p/a.clj")]
    (is (= ["defn f" "defn h"] (map :name es)))
    (is (= ["(defn f [x] (+ x 2))" "(defn h [] 3)"] (map :text es)))
    (is (= ["(defn f [x] (inc x))" nil] (map :before es)) "new since the last Run has no earlier text")
    (is (= [["defn" "f"] ["defn" "h"]] (map :sig es)))
    (is (= ["/p/a.clj" "/p/a.clj"] (map :path es)))
    (is (= [0 0] (map :nth es))))
  (is (= [] (cs/from-changed last-run last-run "/p/a.clj")) "nothing changed, nothing gathered")
  (is (= [] (cs/from-changed now nil "/p/a.clj")) "a tab that never ran has no changed forms")
  (testing "the gutter's own ran set decides which forms count"
    (is (= ["defn f"] (map :name (cs/from-changed now last-run "/p/a.clj"
                                                  (changed/record nil "(ns a)\n(defn g [] 1)\n(defn h [] 3)" :run)))))))

(deftest same-signature-keeps-its-place-test
  (let [lr "(defmethod m :a [x] 1)\n(defmethod m :b [x] 2)\n"
        nw "(defmethod m :a [x] 1)\n(defmethod m :b [x] 22)\n"
        [e :as es] (cs/from-changed nw lr "/p/m.clj")]
    (is (= 1 (count es)))
    (is (= ["defmethod" "m" ":b"] (:sig e)))
    (is (= "(defmethod m :b [x] 2)" (:before e)))))

(deftest entry-diff-test
  (let [[f h] (cs/from-changed now last-run "/p/a.clj")]
    (is (= "- (defn f [x] (inc x))\n+ (defn f [x] (+ x 2))" (cs/entry-diff f)))
    (is (= "+ (defn h [] 3)" (cs/entry-diff h)) "a new form is all added")))

(defn- applied [text {:keys [edits]}] (edit/apply-edits text edits))

(deftest revert-edits-test
  (let [es (cs/from-changed now last-run "/p/a.clj")
        r (cs/revert-edits now es)]
    (is (= "(ns a)\n\n(defn f [x] (inc x))\n\n(defn g [] 1)\n\n(defn h [] 3)\n" (applied now r)))
    (is (= [{:name "defn h" :reason "it is new since the last Run, so there is no earlier text to put back"}]
           (:skipped r))))
  (testing "a form that no longer matches its signature is skipped and named"
    (let [es (cs/from-changed now last-run "/p/a.clj")
          moved "(ns a)\n\n(defn f2 [x] (+ x 2))\n\n(defn g [] 1)\n"
          r (cs/revert-edits moved es)]
      (is (= [] (:edits r)))
      (is (= ["defn f" "defn h"] (map :name (:skipped r))))
      (is (= "This tab has no (defn f ...) any more." (:reason (first (:skipped r)))))))
  (testing "a form already back as it was is skipped"
    (let [es (cs/from-changed now last-run "/p/a.clj")
          r (cs/revert-edits last-run (take 1 es))]
      (is (= [] (:edits r)))
      (is (= "it already reads as it did at the last Run" (:reason (first (:skipped r)))))))
  (testing "edits come in ascending order, so they apply as one op"
    (let [lr "(def a 1)\n(def b 2)\n(def c 3)\n"
          nw "(def a 10)\n(def b 2)\n(def c 30)\n"
          es (cs/from-changed nw lr "/p/c.clj")
          r (cs/revert-edits nw (reverse es))]
      (is (= lr (applied nw r)))
      (is (= (sort (map first (:edits r))) (map first (:edits r)))))))

(deftest set-text-test
  (let [s {:name "idea" :entries (cs/from-changed now last-run "/p/a.clj")}
        t (cs/set-text s)]
    (is (str/includes? t "/p/a.clj  defn f"))
    (is (str/includes? t "- (defn f [x] (inc x))\n+ (defn f [x] (+ x 2))"))
    (is (str/includes? t "/p/a.clj  defn h (new)"))
    (is (not (str/includes? t "—")))))

(deftest schema-test
  (let [e (first (cs/from-changed now last-run "/p/a.clj"))
        s (cs/make-set "idea" [e] {:at 5})]
    (is (= {:version 1 :name "idea" :at 5 :by nil :entries [e]} s))
    (is (cs/valid-set? s))
    (is (not (cs/valid-set? (assoc s :version 2))))
    (is (not (cs/valid-set? (assoc s :entries [(dissoc e :text)]))))
    (is (not (cs/valid-set? (assoc s :entries [(assoc e :sig "defn")]))))
    (is (not (cs/valid-set? (assoc s :entries [(assoc e :before 3)]))))
    (is (not (cs/valid-set? (assoc s :entries []))) "a set holds at least one form")
    (is (not (cs/valid-set? "x")))
    (is (cs/valid-set? (assoc s :by "Claude")))))

(deftest safe-name-test
  (is (= "my-idea" (cs/safe-name "my idea")))
  (is (= "a-b" (cs/safe-name "a/b")))
  (is (= "x.y" (cs/safe-name "..x.y")) "no leading dots")
  (is (nil? (cs/safe-name "  ")))
  (is (nil? (cs/safe-name "///")) "nothing usable left")
  (is (<= (count (cs/safe-name (apply str (repeat 200 "a")))) 60)))

(defn- tmp-dir []
  (let [d (io/file (System/getProperty "java.io.tmpdir") (str "jq-cs-" (System/nanoTime)))]
    (.mkdirs d)
    (.getPath d)))

(deftest dir-test
  (is (= "/s/change-sets/proj-" (subs (cs/sets-dir "/s/settings.edn" "/dev/proj") 0 (count "/s/change-sets/proj-"))))
  (is (= (cs/sets-dir "/s/settings.edn" "/dev/proj") (cs/sets-dir "/s/settings.edn" "/dev/proj")))
  (is (not= (cs/sets-dir "/s/settings.edn" "/dev/proj") (cs/sets-dir "/s/settings.edn" "/other/proj")))
  (is (= "/s/change-sets/no-folder" (cs/sets-dir "/s/settings.edn" nil))))

(deftest write-read-list-test
  (let [dir (str (tmp-dir) "/sets")
        e (first (cs/from-changed now last-run "/p/a.clj"))
        s (cs/make-set "idea" [e] {:at 5})]
    (is (nil? (cs/write-set! dir s)) "no error")
    (is (= {:set s} (cs/read-set (cs/set-path dir "idea"))))
    (spit (str dir "/broken.edn") "{:version 1")
    (spit (str dir "/odd.edn") (pr-str {:version 1 :name "odd" :entries "no"}))
    (spit (str dir "/notes.txt") "ignored")
    (let [l (cs/list-sets dir)]
      (is (= ["broken" "idea" "odd"] (map :name l)))
      (is (= [true false true] (map (comp boolean :broken) l)))
      (is (string? (:broken (first l)))))
    (is (= {:broken "It is not a valid change set."} (cs/read-set (str dir "/odd.edn"))))
    (is (some? (:broken (cs/read-set (str dir "/missing.edn")))))
    (testing "a set named the same is replaced"
      (is (nil? (cs/write-set! dir (assoc s :at 9))))
      (is (= 9 (:at (:set (cs/read-set (cs/set-path dir "idea")))))))
    (testing "delete"
      (is (nil? (cs/delete-set! dir "idea")))
      (is (= ["broken" "odd"] (map :name (cs/list-sets dir)))))
    (is (= [] (cs/list-sets (str dir "/nowhere"))))))

(deftest write-refuses-an-unnameable-set-test
  (let [e (first (cs/from-changed now last-run "/p/a.clj"))]
    (is (string? (cs/write-set! (tmp-dir) (cs/make-set "///" [e] {:at 1}))))))

(deftest proposal-test
  (let [root (tmp-dir)
        f (str root "/a.clj")
        _ (spit f now)
        r (cs/proposal-entries root [{"path" "a.clj" "text" "(defn f [x] (* x 9))"}
                                     {"path" f "text" "(defn zz [] 0)"}])]
    (is (= {:entries [{:path f :sig ["defn" "f"] :nth 0 :name "defn f"
                       :text "(defn f [x] (* x 9))" :before "(defn f [x] (+ x 2))"}
                      {:path f :sig ["defn" "zz"] :nth 0 :name "defn zz"
                       :text "(defn zz [] 0)" :before nil}]}
           r)))
  (testing "refusals"
    (is (:error (cs/proposal-entries "/r" [])))
    (is (:error (cs/proposal-entries "/r" [{"path" "a.clj"}])))
    (is (:error (cs/proposal-entries "/r" [{"path" "a.clj" "text" "  "}])))
    (is (:error (cs/proposal-entries "/r" [{"path" "a.clj" "text" "(defn f ["}])) "unbalanced text")
    (is (:error (cs/proposal-entries "/r" [{"path" "a.clj" "text" "(def a 1) (def b 2)"}])) "two forms in one entry")
    (is (:error (cs/proposal-entries "/r" "x")))
    (is (:error (cs/proposal-entries "/r" (repeat (inc cs/MAX-PROPOSED-FORMS) {"path" "a.clj" "text" "(def a 1)"}))))))

(deftest summary-text-test
  (let [es (cs/from-changed now last-run "/p/a.clj")
        s (cs/make-set "idea" es {:at 1})]
    (is (= "Change set \"idea\": 2 forms in 1 file." (cs/summary-text s false)))
    (is (= "Change set \"idea\" (not kept yet): 2 forms in 1 file." (cs/summary-text s true)))
    (is (= "Change set \"idea\": 1 form in 1 file, proposed by Claude."
           (cs/summary-text (assoc s :by "Claude" :entries (take 1 es)) false)))
    (is (= "Change set \"idea\": 2 forms in 2 files."
           (cs/summary-text (update-in s [:entries 1 :path] (constantly "/p/b.clj")) false)))))
