(ns jonquil.plugin-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.edit :as edit]
            [jonquil.plugin :as p]))

(deftest defplugin-test
  (testing "inside a load, each call is recorded in order, with its line"
    (let [defs (binding [p/*definitions* (atom [])]
                 (p/defplugin {:id :a})
                 (p/defplugin {:id :b})
                 @p/*definitions*)]
      (is (= [:a :b] (map :id defs)))
      (is (every? #(let [l (::p/line %)] (or (nil? l) (integer? l))) defs))))
  (testing "a non-map argument is recorded as it is, for the validator to name"
    (let [defs (binding [p/*definitions* (atom [])] (p/defplugin [1 2]) @p/*definitions*)]
      (is (= [[1 2]] defs))))
  (testing "outside a load it throws"
    (let [e (try (p/defplugin {:id :a}) nil (catch :default e e))]
      (is (some? e))
      (is (true? (:plugin/outside-load (ex-data e)))))))

(defn- snap [text caret] {:text text :caret caret :sel-start caret :sel-end caret})

(deftest form-at-caret-test
  (is (= {:text "(a (b c))" :start 0 :end 9} (p/form-at-caret (snap "(a (b c))" 4))))
  (is (nil? (p/form-at-caret (snap "   " 1))))
  (is (nil? (p/form-at-caret (snap "(a)\n\n(b)" 4))) "between forms"))

(deftest wrap-form-test
  (let [s (snap "(a b)" 1)
        op (p/wrap-form s "(when " ")")]
    (is (= "(when (a b))" (edit/apply-edits (:text s) (:edits op))))
    (is (= 7 (:caret op))))
  (is (nil? (p/wrap-form (snap "(a)\n\n(b)" 4) "(when " ")"))))

(defn- ctx-with
  "A plugin ctx declaring effects, its impl recording each call into calls."
  [effects calls]
  {::p/effects effects ::p/tab 7
   ::p/impl {:print! #(swap! calls conj [:print! %])
             :status! #(swap! calls conj [:status! %])
             :buffer-text (fn [tab] (swap! calls conj [:buffer-text tab]) "text")
             :replace-text! (fn [tab s] (swap! calls conj [:replace-text! tab s]))
             :open! (fn [path] (swap! calls conj [:open! path]) 9)}})

(defn- thrown [f] (try (f) nil (catch :default e e)))

(deftest effect-gating-test
  (let [calls (atom [])
        c (ctx-with #{:console} calls)]
    (is (nil? (p/print! c "hi")))
    (p/status! c "st")
    (is (= [[:print! "hi"] [:status! "st"]] @calls))
    (let [e (thrown #(p/buffer-text c))]
      (is (= "this plugin didn't declare :buffer" (ex-message e)))
      (is (= :buffer (:plugin/effect (ex-data e)))))
    (is (some? (thrown #(p/replace-text! c "x"))))
    (is (some? (thrown #(p/open! c "/a.clj"))))
    (is (= 2 (count @calls)) "a refused call reaches no impl"))
  (let [calls (atom [])
        c (ctx-with #{:buffer} calls)]
    (is (= "text" (p/buffer-text c)))
    (p/replace-text! c "new")
    (is (= 9 (p/open! c "/a.clj")))
    (is (= [[:buffer-text 7] [:replace-text! 7 "new"] [:open! "/a.clj"]] @calls))
    (is (= "this plugin didn't declare :console" (ex-message (thrown #(p/print! c "x")))))))

(deftest eval-not-yet-test
  (let [c (ctx-with #{:eval} (atom []))]
    (is (= "the :eval effect functions arrive in a later slice" (ex-message (thrown #(p/eval! c "(+ 1 2)")))))
    (is (= "the :eval effect functions arrive in a later slice" (ex-message (thrown #(p/repl! c "(+ 1 2)"))))))
  (let [c (ctx-with #{} (atom []))]
    (is (= "this plugin didn't declare :eval" (ex-message (thrown #(p/eval! c "1")))))))
