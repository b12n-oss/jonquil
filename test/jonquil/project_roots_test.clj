(ns jonquil.project-roots-test
  "The project's source folders for in-process Jolt, and a project kept in
  process requiring its own namespace through them."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jonquil.dialect :as dialect]
            [jonquil.evaluator :as ev]
            [jonquil.project-roots :as roots]
            [jonquil.session]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(defn- project! [files]
  (let [dir (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-roots-")))]
    (doseq [[rel text] files]
      (let [f (io/file dir rel)]
        (.mkdirs (.getParentFile f))
        (spit f text)))
    dir))

(defn- canon [dir rel] (.getCanonicalPath (io/file dir rel)))

(deftest paths-test
  (testing "deps.edn's :paths, absolute, existing folders only"
    (let [dir (project! {"deps.edn" "{:paths [\"src\" \"resources\" \"gone\"]}"
                         "src/a.clj" "" "resources/x.txt" ""})]
      (is (= [(canon dir "src") (canon dir "resources")] (roots/paths dir)))))
  (testing "no deps.edn, or no :paths: the CLI's default src"
    (let [dir (project! {"src/a.clj" ""})]
      (is (= [(canon dir "src")] (roots/paths dir))))
    (let [dir (project! {"deps.edn" "{:deps {}}" "src/a.clj" ""})]
      (is (= [(canon dir "src")] (roots/paths dir)))))
  (testing "an alias keyword in :paths is skipped"
    (let [dir (project! {"deps.edn" "{:paths [\"src\" :extra]}" "src/a.clj" ""})]
      (is (= [(canon dir "src")] (roots/paths dir)))))
  (testing "a deps.edn that doesn't read as a map counts as missing"
    (let [dir (project! {"deps.edn" "{:paths [" "src/a.clj" ""})]
      (is (= [(canon dir "src")] (roots/paths dir)))))
  (testing "a set of paths works too"
    (let [dir (project! {"deps.edn" "{:paths #{\"src\"}}" "src/a.clj" ""})]
      (is (= [(canon dir "src")] (roots/paths dir)))))
  (testing "an empty :paths is empty"
    (let [dir (project! {"deps.edn" "{:paths []}" "src/a.clj" ""})]
      (is (= [] (roots/paths dir)))))
  (is (= [] (roots/paths nil)) "no project, no folders"))

(deftest a-project-in-process-requires-its-own-namespaces-test
  ;; Before, the Run failed: "Could not locate demo/util.jolt (or
  ;; .clj/.cljc) on the source roots".
  (let [dir (project! {"deps.edn" "{:paths [\"src\"]}"
                       ".jonquil.edn" "{:runtime :in-process}"
                       ;; The trailing comment makes the text unique per run: Jolt
                       ;; v0.8.23's AOT cache is keyed on the text, so an identical
                       ;; file from an earlier run's folder would hand back that
                       ;; folder's :file (jolt-lang/jolt#1404).
                       "src/demo/rootsutil.clj" (str "(ns demo.rootsutil)\n\n(defn helper\n  \"Helps.\"\n  [x]\n  (inc x))\n;; " (System/nanoTime) "\n")
                       "src/demo/rootsa.clj" "(ns demo.rootsa (:require [demo.rootsutil :as u]))\n(u/helper 1)\n"})
        path (canon dir "src/demo/rootsa.clj")
        text (slurp path)
        sel (dialect/select dialect/profiles (dialect/find-project path) path nil)]
    (try
      (is (= :in-process (:runtime sel)))
      (let [e (ev/make sel)
            p (promise)]
        (ev/eval! e text {:run? true :path path :on-output (fn [_ _]) :on-complete #(deliver p %)})
        (is (= {:status :ok :printed "2"} (select-keys (deref p 10000 :timeout) [:status :printed])))
        (testing "the var knows its file, so jump to definition can open it"
          (let [d (ev/lookup e "u/helper" text 52)]
            (is (= (canon dir "src/demo/rootsutil.clj") (:file d)))
            (is (= 3 (:line d)))))
        (testing "a classpath :file, as a Jolt after v0.8.23 records it, resolves on the project's roots"
          (alter-meta! (resolve 'demo.rootsutil/helper) assoc :file "demo/rootsutil.clj")
          (is (= (canon dir "src/demo/rootsutil.clj") (:file (ev/lookup e "u/helper" (str text " ") 52))))))
      (finally
        (roots/use-project! nil)
        (doseq [n '[demo.rootsa demo.rootsutil]] (remove-ns n))))))

(deftest project-roots-come-after-jolts-own-test
  ;; Jolt and Jonquil share this process: a project's folders must not
  ;; shadow their namespaces (review finding 4).
  (let [dir (project! {"src/a.clj" ""})
        before (vec ((requiring-resolve 'jolt.host/source-roots)))]
    (try
      (is (true? (roots/use-project! dir)))
      (let [after (vec ((requiring-resolve 'jolt.host/source-roots)))]
        (is (= (canon dir "src") (peek after)) "the project's folder is last")
        (is (= (vec (remove #{(canon dir "src")} after)) (vec (distinct (remove #{(canon dir "src")} before))))
            "Jolt's own roots keep their order, in front"))
      (finally (roots/use-project! nil)))))

(deftest two-projects-on-one-evaluator-test
  ;; A window keeps one in-process evaluator when it opens a file from
  ;; another in-process project, so each Run applies its own file's project
  ;; (review finding 1).
  (let [mk (fn [ns-a ns-u]
             (project! {"deps.edn" "{:paths [\"src\"]}"
                        ".jonquil.edn" "{:runtime :in-process}"
                        (str "src/" ns-u ".clj") (str "(ns " ns-u ")\n(defn v [] :" ns-u ")\n;; " (System/nanoTime) "\n")
                        (str "src/" ns-a ".clj") (str "(ns " ns-a " (:require [" ns-u " :as u]))\n(u/v)\n")}))
        a (mk "proja" "projautil")
        b (mk "projb" "projbutil")
        run (fn [e path] (let [p (promise)]
                           (ev/eval! e (slurp path) {:run? true :path path :on-output (fn [_ _]) :on-complete #(deliver p %)})
                           (select-keys (deref p 10000 :timeout) [:status :printed])))
        pa (canon a "src/proja.clj")
        e (ev/make (dialect/select dialect/profiles (dialect/find-project pa) pa nil))]
    (try
      (is (= {:status :ok :printed ":projautil"} (run e pa)))
      (is (= {:status :ok :printed ":projbutil"} (run e (canon b "src/projb.clj")))
          "the second project's own namespace, on the same evaluator")
      (finally
        (roots/use-project! nil)
        (doseq [n '[proja projautil projb projbutil]] (remove-ns n))))))
