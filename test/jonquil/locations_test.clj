(ns jonquil.locations-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.locations :as loc]
            [jonquil.syntax :as syntax]))

(def ^:private src
  (str "(defmulti area :shape)\n"
       "(defmethod area :sq [s] (* (:w s) (:w s)))\n"
       "(defmethod area :ci [c] (* 3 (:r c)))\n"
       "(area {:shape :sq :w 2})"))

(deftest find-definitions-test
  (let [ds (syntax/find-definitions src "area")]
    (is (= ["defmulti" "defmethod" "defmethod"] (map :form ds)))
    (is (= [1 2 3] (map :line ds)))
    (is (every? #(= "area" (subs src (:start %) (:end %))) ds)))
  (testing "find-definition still stops at the first"
    (is (= 1 (:line (syntax/find-definition src "area")))))
  (is (= [] (syntax/find-definitions src "perimeter"))))

(deftest uses-test
  (let [{:keys [title entries index]} (loc/uses src "area")]
    (is (= "Uses of area" title))
    (is (nil? index))
    (is (= [1 2 3 4] (map :line entries)))
    (is (= "(area {:shape :sq :w 2})" (:text (last entries))))
    (is (every? #(= "area" (subs src (:start %) (:end %))) entries))))

(deftest definitions-test
  (let [{:keys [title entries]} (loc/definitions src "area")]
    (is (= "Definitions of area" title))
    (is (= [1 2 3] (map :line entries)))
    (is (= "(defmulti area :shape)" (:text (first entries))))))

(deftest matches-test
  (let [{:keys [title entries]} (loc/matches src "shape")]
    (is (= "Matches for shape" title))
    (is (= [1 4] (map :line entries))))
  (testing "case-insensitive, as the find bar searches"
    (is (= 2 (count (:entries (loc/matches "Shape shape" "shape")))))))

(deftest entries-text-test
  (testing "a long line is trimmed and cut to 120 characters"
    (let [line (str "    " (apply str (repeat 200 "x")) "  ")
          [e] (loc/entries line [[4 5]])]
      (is (= 120 (count (:text e))))
      (is (= \x (first (:text e)))))))

(deftest long-list-test
  (testing "a list stops at LIST-MAX places and its title says so"
    (let [text (apply str (repeat 2500 "x\n"))
          {:keys [title entries]} (loc/matches text "x")]
      (is (= loc/LIST-MAX (count entries)))
      (is (= "Matches for x (the first 1000 of 2500)" title))
      (testing "and every line number is right, counted in one pass"
        (is (= (range 1 (inc loc/LIST-MAX)) (map :line entries))))))
  (testing "a list under the cap keeps its plain title"
    (is (= "Matches for x" (:title (loc/matches "x\nx" "x")))))
  (testing "a big file's one-letter search stays fast (was 18 s)"
    (let [text (apply str (repeat 4000 "(defn e [e] (e e))\n"))
          t0 (System/nanoTime)
          _ (loc/matches text "e")
          ms (/ (- (System/nanoTime) t0) 1e6)]
      (is (< ms 2000) (str ms " ms")))))

(deftest step-test
  (let [l (loc/uses src "area")]
    (is (= 0 (get-in (loc/step l 1) [:list :index])))
    (is (= 3 (get-in (loc/step l -1) [:list :index])))
    (let [at-last (assoc l :index 3)]
      (is (= {:list at-last :moved? false} (loc/step at-last 1))))
    (let [at-first (assoc l :index 0)]
      (is (false? (:moved? (loc/step at-first -1))))
      (is (= 1 (get-in (loc/step at-first 1) [:list :index])))))
  (testing "an empty list never moves"
    (let [e (loc/uses src "perimeter")]
      (is (= [] (:entries e)))
      (is (false? (:moved? (loc/step e 1)))))))

(deftest position-text-test
  (let [l (loc/uses src "area")]
    (is (= "4 places" (loc/position-text l)))
    (is (= "2 of 4" (loc/position-text (assoc l :index 1))))
    (is (= "1 place" (loc/position-text (loc/definitions "(def x 1)" "x"))))
    (is (= "No places" (loc/position-text (loc/uses src "perimeter"))))))

(def ^:private scan-result
  {:entries [{:path "/p/a.clj" :start 1 :end 5 :line 1 :text "(join 1)" :how :call}
             {:path "/p/a.clj" :start 9 :end 13 :line 2 :text "(map join x)" :how :value}
             {:path "/p/b.clj" :start 6 :end 10 :line 1 :text "(defn join [])" :how :definition}]
   :files 3 :matched 2})

(deftest project-uses-test
  (let [lst (loc/project-uses scan-result "join")]
    (is (= "Usages of join (3 in 2 files)" (:title lst)))
    (is (= :project (:scope lst)))
    (is (= [0 1 2] (map :id (:entries lst))) "each entry knows its place in the whole list")
    (is (= (set loc/hows) (:hows lst)) "every kind shown")
    (is (nil? (:index lst))))
  (is (= "Usages of join (1 in 1 file)"
         (:title (loc/project-uses (assoc scan-result :entries (take 1 (:entries scan-result)) :matched 1) "join")))))

(deftest set-hows-test
  (let [lst (loc/project-uses scan-result "join")]
    (testing "only the ticked kinds show, in order"
      (is (= [:call :definition] (map :how (:entries (loc/set-hows lst #{:call :definition}))))))
    (testing "the current place stays when it's still shown"
      (let [at-def (assoc lst :index 2)]
        (is (= 1 (:index (loc/set-hows at-def #{:call :definition}))))))
    (testing "a hidden current place moves to the next shown one, else the last"
      (is (= 1 (:index (loc/set-hows (assoc lst :index 1) #{:call :definition}))) "value hidden: on to the definition")
      (is (= 0 (:index (loc/set-hows (assoc lst :index 2) #{:call}))) "nothing after: the last shown"))
    (testing "nothing ticked: no places, no index"
      (let [none (loc/set-hows (assoc lst :index 0) #{})]
        (is (= [] (:entries none)))
        (is (nil? (:index none)))
        (is (= "No places" (loc/position-text none)))))
    (testing "ticking it again brings the rows back"
      (is (= 3 (count (:entries (loc/set-hows (loc/set-hows lst #{}) (set loc/hows)))))))))

(def ^:private resolved-scan
  {:entries [{:path "/p/a.clj" :start 1 :end 5 :line 1 :text "(join 1)" :how :call :match :this}
             {:path "/p/a.clj" :start 9 :end 13 :line 2 :text "(str/join x)" :how :call :match :other}
             {:path "/p/b.clj" :start 6 :end 10 :line 1 :text "(defn join [])" :how :definition :match :this}
             {:path "/p/c.clj" :start 6 :end 10 :line 3 :text "(join 2)" :how :call :match :unresolved}]
   :files 3 :matched 3 :target {:var 'my.text/join}})

(deftest project-uses-resolved-test
  (let [lst (loc/project-uses resolved-scan "join")]
    (testing "the target var's own uses show; the title names the var and counts them"
      (is (= "Usages of my.text/join (2 in 2 files)" (:title lst)))
      (is (= ["(join 1)" "(defn join [])"] (map :text (:entries lst))))
      (is (= #{:this} (:matches lst))))
    (testing "the other kinds are counted for their buttons"
      (is (= {:other 1 :unresolved 1} (:match-counts lst)))
      (is (:target-var? lst)))
    (testing "ticking a match kind brings its entries back, in order, with how still applied"
      (is (= ["(join 1)" "(defn join [])" "(join 2)"]
             (map :text (:entries (loc/set-hows lst (conj (set loc/hows) :unresolved))))))
      (is (= ["(join 1)" "(str/join x)" "(join 2)"]
             (map :text (:entries (loc/set-hows lst #{:call :other :unresolved}))))))
    (testing "rows say when a place isn't the target var"
      (let [l (assoc (loc/set-hows lst (into (set loc/hows) [:other :unresolved])) :root "/p")]
        (is (= ["a.clj:1: call: (join 1)" "a.clj:2: call, other var: (str/join x)"
                "b.clj:1: def: (defn join [])" "c.clj:3: call, unresolved: (join 2)"]
               (map #(loc/row-text l %) (:entries l)))))))
  (testing "a by-name list (no var target) shows every entry and no match buttons"
    (let [lst (loc/project-uses scan-result "join")]
      (is (not (:target-var? lst)))
      (is (= 3 (count (:entries lst)))))))

(deftest project-uses-keeps-target-entries-test
  (testing "past LIST-MAX the target var's own entries are kept first"
    (let [n (+ 5 loc/LIST-MAX)
          es (vec (for [i (range n)]
                    {:path "/p/a.clj" :start i :end (inc i) :line i :text "x" :how :call
                     :match (if (< i (- n 3)) :other :this)}))
          lst (loc/project-uses {:entries es :files 1 :matched 1 :target {:var 'a/x}} "x")]
      (is (= loc/LIST-MAX (count (:all lst))))
      (is (= 3 (count (:entries lst))) "all three of the var's own uses survive the cut")
      (is (= (sort (map :start (:all lst))) (map :start (:all lst))) "still in file order"))))

(deftest row-text-test
  (let [lst (assoc (loc/project-uses scan-result "join") :root "/p")]
    (is (= ["a.clj:1: call: (join 1)" "a.clj:2: value: (map join x)" "b.clj:1: def: (defn join [])"]
           (map #(loc/row-text lst %) (:entries lst))))
    (is (= "/q/c.clj:4: ns: x" (loc/row-text lst {:path "/q/c.clj" :line 4 :how :ns :text "x"}))
        "outside the root: the whole path"))
  (is (= "4: (area 1)" (loc/row-text (loc/uses "(area 1)" "area") {:line 4 :text "(area 1)"}))
      "a buffer list: line and text"))
