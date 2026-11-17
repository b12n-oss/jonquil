(ns jonquil.describe-run-test
  (:require [clojure.string]
            [clojure.test :refer [deftest is]]
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

;; --- answers -------------------------------------------------------------

(def ^:private root "/p")

(def ^:private files ["/p/src/demo/core.clj" "/p/src/demo/config.clj"])

(defn- locate [f] (tr/locate files f))

(def ^:private opts {:root root :locate locate})

(def ^:private ns-value
  {:namespaces [{:name "clojure.core" :file "clojure/core.clj" :vars 700}
                {:name "demo.core" :file "demo/core.clj" :vars 4}
                {:name "demo.config" :file "/p/src/demo/config.clj" :vars 2}
                {:name "user" :file nil :vars 0}]
   :dropped 0})

(deftest namespaces-answer-own-first-and-hidden
  (let [{:keys [text data]} (dr/namespaces-answer ns-value opts)]
    (is (= ["demo.config" "demo.core"] (map #(get % "name") (get data "namespaces"))))
    (is (= "src/demo/core.clj" (get (second (get data "namespaces")) "file")))
    (is (= 2 (get data "hidden")))
    (is (= "ok" (get data "status")))
    (is (= "demo.config\ndemo.core\n(+ 2 Clojure and library namespaces; pass all: true to list them)" text))))

(deftest namespaces-answer-all
  (let [{:keys [text data]} (dr/namespaces-answer ns-value (assoc opts :all? true))]
    (is (= ["demo.config" "demo.core" "clojure.core" "user"] (map #(get % "name") (get data "namespaces"))))
    (is (= [true true false false] (map #(get % "own") (get data "namespaces"))))
    (is (= 0 (get data "hidden")))
    (is (= "demo.config\ndemo.core\nclojure.core\nuser" text))))

(def ^:private vars-value
  {:found? true
   :vars [{:name "blank?" :arglists ["[s]"] :doc nil :file "demo/core.clj" :line 12 :private? true :macro? false}
          {:name "parse-line" :arglists ["[s]" "[s opts]"] :doc "Splits one line." :file "demo/core.clj" :line 30 :private? false :macro? false}
          {:name "with-log" :arglists ["[& body]"] :doc nil :file nil :line nil :private? false :macro? true}]
   :dropped 0})

(deftest vars-answer-rows-and-text
  (let [{:keys [text data]} (dr/vars-answer vars-value (assoc opts :ns "demo.core"))]
    (is (= "demo.core" (get data "ns")))
    (is (= {"name" "parse-line" "arglists" ["[s]" "[s opts]"] "doc" "Splits one line."
            "file" "src/demo/core.clj" "line" 30 "private" false "macro" false}
           (second (get data "vars"))))
    (is (= ["blank? [s] (private)  src/demo/core.clj:12"
            "parse-line [s] [s opts]  Splits one line.  src/demo/core.clj:30"
            "with-log [& body] (macro)"]
           (clojure.string/split-lines text)))))

(deftest vars-answer-private-filter
  (let [{:keys [data]} (dr/vars-answer vars-value (assoc opts :ns "demo.core" :private? false))]
    (is (= ["parse-line" "with-log"] (map #(get % "name") (get data "vars"))))))

(deftest vars-answer-limit-counts-dropped
  (let [{:keys [text data]} (dr/vars-answer (assoc vars-value :dropped 5) (assoc opts :ns "demo.core" :limit 1))]
    (is (= 1 (count (get data "vars"))))
    (is (= 7 (get data "dropped")))
    (is (clojure.string/ends-with? text "\n(7 more not shown; raise limit to see them)"))))

(deftest vars-answer-not-found
  (is (= {:text "Namespace demo.nope isn't loaded. Run its file (jonquil_run_file), then call again."
          :error? true
          :data {"status" "not-found" "ns" "demo.nope"}}
         (dr/vars-answer {:found? false :vars [] :dropped 0} (assoc opts :ns "demo.nope")))))

(def ^:private apropos-value
  {:vars [{:ns "clojure.string" :name "blank?" :arglists ["[s]"] :doc "True if s is blank." :file "clojure/string.clj" :line 288}
          {:ns "demo.config" :name "blank-config" :arglists ["[]"] :doc nil :file "demo/config.clj" :line 3}
          {:ns "demo.core" :name "blank?" :arglists ["[s]"] :doc nil :file "demo/core.clj" :line 12}]
   :dropped 0})

(deftest apropos-answer-own-first-and-limit
  (let [{:keys [text data error?]} (dr/apropos-answer apropos-value (assoc opts :query "blank"))]
    (is (false? error?))
    (is (= "blank" (get data "query")))
    (is (= [["demo.config" "blank-config"] ["demo.core" "blank?"] ["clojure.string" "blank?"]]
           (map (juxt #(get % "ns") #(get % "name")) (get data "matches"))))
    (is (= ["demo.config/blank-config []  src/demo/config.clj:3"
            "demo.core/blank? [s]  src/demo/core.clj:12"
            "clojure.string/blank? [s]  clojure/string.clj:288"]
           (clojure.string/split-lines text))))
  (let [{:keys [data]} (dr/apropos-answer apropos-value (assoc opts :query "blank" :limit 2))]
    (is (= 2 (count (get data "matches"))))
    (is (= 1 (get data "dropped"))))
  (let [{:keys [data]} (dr/apropos-answer apropos-value (assoc opts :query "blank" :all? false))]
    (is (= 2 (count (get data "matches"))))
    (is (= 0 (get data "dropped")) "the rest are left out by choice, not dropped")))

(deftest apropos-answer-no-match
  (is (= {:text "No loaded var's name contains \"zzz\"." :error? false
          :data {"status" "ok" "query" "zzz" "matches" [] "dropped" 0}}
         (dr/apropos-answer {:vars [] :dropped 0} (assoc opts :query "zzz")))))

(deftest file-shown-relative-or-as-given
  (is (= "src/demo/config.clj" (dr/shown-file "/p/src/demo/config.clj" opts)))
  (is (= "src/demo/core.clj" (dr/shown-file "demo/core.clj" opts)))
  (is (= "clojure/string.clj" (dr/shown-file "clojure/string.clj" opts)))
  (is (nil? (dr/shown-file nil opts))))

(deftest clamp-limit-bounds
  (is (= 50 (dr/clamp-limit nil 50)))
  (is (= 1 (dr/clamp-limit 0 50)))
  (is (= 5000 (dr/clamp-limit 9999 50)))
  (is (= 50 (dr/clamp-limit "x" 50)))
  (is (= 7 (dr/clamp-limit 7.6 50))))
