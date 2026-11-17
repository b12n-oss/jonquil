(ns jonquil.describe-run-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.describe-run :as dr]
            [jonquil.test-run :as tr]))

(defn- evaluate! [code] (binding [*ns* *ns*] (load-string code)))

(defn- value [code] (tr/read-value (pr-str (evaluate! code))))

(defn- with-ns [ns-name code f]
  (try (evaluate! (str "(ns " ns-name ")\n" code)) (f)
       (finally (remove-ns (symbol ns-name)))))

(def ^:private fixture-a
  "(defn f \"One.\nTwo.\" [x] x) (defn- p [] 1) (defmacro m [] nil)")

(defn- row [rows k n] (first (filter #(= n (get % k)) rows)))

(deftest namespaces-snippet-lists-a-loaded-ns
  (with-ns "uf4.fx.a" fixture-a
    (fn []
      (let [v (value (dr/namespaces-snippet))]
        (is (= 3 (:vars (row (:namespaces v) :name "uf4.fx.a"))))
        (is (= 0 (:dropped v)))))))

(deftest vars-snippet-fields
  (with-ns "uf4.fx.a" fixture-a
    (fn []
      (let [v (value (dr/vars-snippet {:ns "uf4.fx.a"}))
            rows (:vars v)]
        (is (:found? v))
        (is (= ["[x]"] (:arglists (row rows :name "f"))))
        (is (= "One." (:doc (row rows :name "f"))))
        (is (false? (:private? (row rows :name "f"))))
        (is (true? (:private? (row rows :name "p"))))
        (is (true? (:macro? (row rows :name "m"))))
        ;; Jolt v0.8.27 gives a defmacro from load-string no :line
        (is (every? number? (map #(:line (row rows :name %)) ["f" "p"])))))))

(deftest vars-snippet-missing-ns
  (is (false? (:found? (value (dr/vars-snippet {:ns "uf4.fx.nope"}))))))

(deftest vars-snippet-rejects-a-bad-name
  (is (thrown? Exception (dr/vars-snippet {:ns "a) (b"}))))

(deftest odd-meta-keeps-the-row
  (with-ns "uf4.fx.odd" "(def ^{:doc 42 :arglists :bad} odd 1) (alter-meta! #'odd dissoc :line)"
    (fn []
      (let [r (row (:vars (value (dr/vars-snippet {:ns "uf4.fx.odd"}))) :name "odd")]
        (is (some? r))
        (is (nil? (:doc r)))
        (is (= [] (:arglists r)))
        (is (nil? (:line r)))))))

(deftest apropos-snippet-substring-any-case
  (with-ns "uf4.fx.b" "(defn fx-needle-one [] 1) (defn other [] 2)"
    (fn []
      (let [rows (:vars (value (dr/apropos-snippet {:query "FX-NEEDLE"})))]
        (is (= [["uf4.fx.b" "fx-needle-one"]] (map (juxt :ns :name) rows)))))))

(deftest runtime-cap-counts-the-rest
  (with-ns "uf4.fx.c" "(def a 1) (def b 2) (def c 3) (def d 4) (def e 5)"
    (fn []
      (let [v (value (dr/vars-snippet {:ns "uf4.fx.c" :cap 3}))]
        (is (= 3 (count (:vars v))))
        (is (= 2 (:dropped v))))
      (let [v (value (dr/apropos-snippet {:query "" :cap 3}))]
        (is (= 3 (count (:vars v))))
        (is (pos? (:dropped v))))
      (let [v (value (dr/namespaces-snippet {:cap 1}))]
        (is (= 1 (count (:namespaces v))))
        (is (pos? (:dropped v)))))))

(deftest snippets-are-qualified
  (doseq [s [(dr/namespaces-snippet) (dr/vars-snippet {:ns "a.b"}) (dr/apropos-snippet {:query "x"})]]
    (is (= "clojure.core" (namespace (first (read-string s)))))
    (is (nil? (re-find #"[(\[ ](let|fn|map|filter|keep|meta|vals|str) " s)) s)))

(deftest supported-dialects
  (is (dr/supported? :jolt))
  (is (dr/supported? :clj))
  (is (not (dr/supported? :cljs))))
