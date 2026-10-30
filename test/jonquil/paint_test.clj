(ns jonquil.paint-test
  "Upstream's three Swing-bound highlighting tests (syntax_test.clj at
  41c56b8: highlight-doc-test, setup-syntax-highlighting-test,
  rainbow-parentheses-test), re-expressed against the paint plan. Colours
  are the light theme's (jonquil.theme); the kinds and flags are
  upstream's."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.dialect :as dialect]
            [jonquil.paint :as paint]
            [jonquil.syntax :as syntax]
            [jonquil.theme :as theme]))

(def light (theme/theme :light))

(deftest highlight-doc-test
  (let [code "(defn greet [name]\n  ; greeting\n  (println (str \"Hello, \" name)))\n(greet \"World\")"
        plan (paint/paint-plan code)]
    (is (= {:fg "#cf222e" :bold true} (paint/style-at light plan 1)))
    (is (= {:fg "#8c95a0" :italic true} (paint/style-at light plan (str/index-of code "; greeting"))))
    (is (= {:fg "#953800"} (paint/style-at light plan (str/index-of code "println"))))
    (is (= {:fg "#0a3069"} (paint/style-at light plan (str/index-of code "\"Hello, \""))))))

(deftest def-is-special-form-test
  (is (= {:fg "#cf222e" :bold true} (paint/style-at light (paint/paint-plan "(def a 10)") 1))))

(deftest rainbow-parentheses-test
  (testing "7 levels applied to nested brackets"
    (let [code "([{[({[()]})]}])"
          plan (paint/paint-plan code)
          matches (:matches (syntax/compute-brackets code))]
      (dotimes [depth 8]
        (is (= {:fg (nth (:rainbow light) (mod depth 7)) :bold true} (paint/style-at light plan depth)))
        (is (= {:fg (nth (:rainbow light) (mod depth 7)) :bold true}
               (paint/style-at light plan (get matches depth)))))))
  (testing "unmatched bracket takes the theme's red"
    (is (= {:fg "#cf222e" :bold true} (paint/style-at light (paint/paint-plan "(foo))") 5)))))

(deftest every-kind-has-a-colour-in-every-theme-test
  (doseq [k theme/theme-names
          :let [t (theme/theme k)]
          kind (keys paint/token-styles)]
    (is (re-matches #"#[0-9a-f]{6}" (:fg (paint/style t kind))) (str k " " kind)))
  (is (= paint/rainbow-count (count (:rainbow light)))))

(deftest empty-and-plain-text-test
  (is (= [] (paint/paint-plan "")))
  (is (= [] (paint/paint-plan nil)))
  (is (nil? (paint/style-at light (paint/paint-plan "abc") 0))))

;; a profile's own sets decide how symbols paint.
(deftest paint-plan-profile-test
  (let [text "(ns a.b (:require [cljd.flutter :as f]))\n(f/widget :padding 8 (await (fetch)))\n(defn g [x] (map inc x))"
        kind-at (fn [plan pos] (some (fn [[k s e]] (when (and (keyword? k) (<= s pos) (< pos e)) k)) plan))
        at (fn [needle] (str/index-of text needle))]
    (testing "the Jolt profile paints exactly as before"
      (is (= (paint/paint-plan text) (paint/paint-plan dialect/jolt text))))
    (testing "the cljd profile paints its own forms as special forms"
      (let [plan (paint/paint-plan dialect/cljd text)]
        (is (= :special-form (kind-at plan (at "f/widget"))))
        (is (= :special-form (kind-at plan (at "await"))))
        (is (= :special-form (kind-at plan (at "defn"))) "Jolt's set is still there")
        (is (= :builtin (kind-at plan (at "map"))))
        (is (nil? (kind-at plan (at "fetch"))) "a plain symbol keeps the default style")
        (is (= :keyword (kind-at plan (at ":padding"))))))
    (testing "Jolt doesn't know ClojureDart's forms"
      (is (nil? (kind-at (paint/paint-plan text) (at "f/widget")))))))

;; plan-diff

(deftest plan-diff-identity-test
  (let [t "(defn f [x] (inc x))"]
    (is (nil? (paint/plan-diff t (paint/paint-plan t) t (paint/paint-plan t)
                               {:prefix (count t) :suffix (count t)})))
    (let [d (paint/plan-diff t (paint/paint-plan t) t (paint/paint-plan t) nil)]
      (is (some? d))
      (is (= [0 (count t)] (:range d))))))

(deftest plan-diff-small-edit-test
  (let [lines (vec (repeat 200 "(defn foo [x] (inc x))"))
        old-text (str/join "\n" lines)
        at (+ (* 100 23) 7)
        new-text (str (subs old-text 0 at) "z" (subs old-text at))
        d (paint/plan-diff old-text (paint/paint-plan old-text)
                           new-text (paint/paint-plan new-text)
                           {:prefix at :suffix (- (count old-text) at)})
        [a b] (:range d)]
    (is (some? d))
    (is (< (- b a) 40))
    (is (<= a at))
    (is (< at b))))

(deftest plan-diff-same-text-test
  (let [t "(foo 1)\n(bar 2)\n(baz 3)"
        prof {:special-forms #{} :builtins #{"bar"}}
        d (paint/plan-diff t (paint/paint-plan t) t (paint/paint-plan prof t)
                           {:prefix (count t) :suffix (count t)})
        at (str/index-of t "bar")]
    (is (some? d))
    (is (<= (first (:range d)) at))
    (is (>= (second (:range d)) (+ at 3)))))

;; The model: a vector of per-char sets of style keys.

(defn- plan->model [n plan]
  (reduce (fn [m [k s e]]
            (reduce (fn [m i] (update m i conj k)) m (range s e)))
          (vec (repeat n #{}))
          plan))

(defn- edit-model
  "Maps model through replacing [i j) with ins-len chars. Surviving chars keep
  their sets; inserted chars get #{} or, when inherit?, the left neighbour's."
  [model i j ins-len inherit?]
  (let [left (when (and inherit? (pos? i)) (nth model (dec i)))
        fill (vec (repeat ins-len (or left #{})))]
    (into (into (subvec model 0 i) fill) (subvec model j))))

(defn- apply-diff [model {[a b] :range spans :apply}]
  (let [cleared (reduce (fn [m i] (assoc m i #{})) model (range a b))]
    (reduce (fn [m [k s e]]
              (reduce (fn [m i] (update m i conj k)) m (range s e)))
            cleared spans)))

(deftest plan-diff-model-test
  (let [src (slurp "src/jonquil/syntax.clj")
        rnd (java.util.Random. 42)
        alphabet (vec "()[]{}\";\\ ax1:#\n")
        win 2000]
    (doseq [inherit? [false true]
            idx (range 300)]
      (let [start (.nextInt rnd (- (count src) win))
            old-text (subs src start (+ start win))
            i (.nextInt rnd (inc (count old-text)))
            del (min (.nextInt rnd 6) (- (count old-text) i))
            ins (apply str (repeatedly (.nextInt rnd 6) #(nth alphabet (.nextInt rnd (count alphabet)))))
            new-text (str (subs old-text 0 i) ins (subs old-text (+ i del)))
            old-plan (paint/paint-plan old-text)
            new-plan (paint/paint-plan new-text)
            expected (plan->model (count new-text) new-plan)
            mapped (edit-model (plan->model (count old-text) old-plan)
                               i (+ i del) (count ins) inherit?)
            d (paint/plan-diff old-text old-plan new-text new-plan
                               {:prefix i :suffix (- (count old-text) i del)})
            actual (if d (apply-diff mapped d) mapped)]
        (is (= expected actual)
            (str "seed index " idx " inherit? " inherit? " edit "
                 (pr-str {:at i :del del :ins ins :start start})))))))

(defn- bounds-after
  "Task 4's running bounds: the min of o and L - o for an insert of len at o,
  of o and L - e for a delete of [o e), where L is the text length before."
  [bounds text-len o del ins-len]
  (let [e (+ o del)
        p (min (:prefix bounds) o)
        sfx (min (:suffix bounds) (- text-len e))]
    {:prefix p :suffix sfx :len (+ (- text-len del) ins-len)}))

(deftest plan-diff-two-edits-test
  (let [src (slurp "src/jonquil/syntax.clj")
        rnd (java.util.Random. 7)
        alphabet (vec "()[]{}\";\\ ax1:#\n")
        win 2000]
    (doseq [inherit? [false true]
            idx (range 100)]
      (let [start (.nextInt rnd (- (count src) win))
            t0 (subs src start (+ start win))
            rand-ins #(apply str (repeatedly (.nextInt rnd 5) (fn [] (nth alphabet (.nextInt rnd (count alphabet))))))
            i1 (+ 100 (.nextInt rnd 1800))
            del1 (.nextInt rnd 4)
            ins1 (rand-ins)
            t1 (str (subs t0 0 i1) ins1 (subs t0 (+ i1 del1)))
            ;; second edit strictly left of the first
            i2 (.nextInt rnd (- i1 20))
            del2 (.nextInt rnd 4)
            ins2 (rand-ins)
            t2 (str (subs t1 0 i2) ins2 (subs t1 (+ i2 del2)))
            b1 (bounds-after {:prefix (count t0) :suffix (count t0)} (count t0) i1 del1 (count ins1))
            b2 (bounds-after b1 (count t1) i2 del2 (count ins2))
            m1 (edit-model (plan->model (count t0) (paint/paint-plan t0)) i1 (+ i1 del1) (count ins1) inherit?)
            m2 (edit-model m1 i2 (+ i2 del2) (count ins2) inherit?)
            p2 (paint/paint-plan t2)
            d (paint/plan-diff t0 (paint/paint-plan t0) t2 p2 (select-keys b2 [:prefix :suffix]))]
        (is (= (plan->model (count t2) p2) (if d (apply-diff m2 d) m2))
            (str "seed index " idx " inherit? " inherit? " edits "
                 (pr-str [{:at i1 :del del1 :ins ins1} {:at i2 :del del2 :ins ins2}] {:start start})))))))
