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

(defn- run-in [e path]
  (let [p (promise) out (atom "")]
    (ev/eval! e (slurp path) {:run? true :path path :on-output (fn [_ s] (swap! out str s))
                              :on-complete #(deliver p %)})
    (assoc (select-keys (deref p 20000 :timeout) [:status :printed]) :out @out)))

(deftest a-project-in-process-loads-its-deps-test
  ;; Before, only :paths were roots in process: "Could not locate
  ;; depslib/core..." for anything under :deps.
  (let [base (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-deps-")))
        stamp (System/nanoTime)
        _ (doseq [[rel text] {"depslib/deps.edn" "{:paths [\"src\"]}"
                              "depslib/src/depslib/core.clj" (str "(ns depslib.core)\n(defn twice [x] (* 2 x))\n;; " stamp "\n")
                              "app/deps.edn" "{:paths [\"src\"] :deps {local/depslib {:local/root \"../depslib\"}}}"
                              "app/.jonquil.edn" "{:runtime :in-process}"
                              "app/src/depsapp.clj" "(ns depsapp (:require [depslib.core :as d]))\n(d/twice 21)\n"}]
            (let [f (io/file base rel)] (.mkdirs (.getParentFile f)) (spit f text)))
        path (canon base "app/src/depsapp.clj")
        e (ev/make (dialect/select dialect/profiles (dialect/find-project path) path nil))]
    (try
      (is (= {:status :ok :printed "42"} (select-keys (run-in e path) [:status :printed])))
      (testing "the dependency's root goes after the project's own, both after Jolt's"
        (let [rs (vec ((requiring-resolve 'jolt.host/source-roots)))]
          (is (= [(canon base "app/src") (canon base "depslib/src")] (vec (take-last 2 rs))))))
      (testing "a REPL entry after it can require from the dependency too"
        (let [p (promise)]
          (ev/eval! e "(require 'depslib.core) (depslib.core/twice 5)" {:on-output (fn [_ _]) :on-complete #(deliver p %)})
          (is (= "10" (:printed (deref p 10000 :timeout))))))
      (finally
        (roots/use-project! nil)
        (doseq [n '[depsapp depslib.core]] (remove-ns n))))))

(deftest a-dependency-that-cant-be-resolved-test
  ;; The Run still goes ahead on the project's :paths, and the console says
  ;; why the rest is missing.
  (let [dir (project! {"deps.edn" "{:paths [\"src\"] :deps {nope/nope {:git/url \"file:///no/such/repo\" :git/sha \"0000000000000000000000000000000000000000\"}}}"
                       ".jonquil.edn" "{:runtime :in-process}"
                       "src/depsbad.clj" "(ns depsbad)\n(+ 1 2)\n"})
        path (canon dir "src/depsbad.clj")
        e (ev/make (dialect/select dialect/profiles (dialect/find-project path) path nil))]
    (try
      (let [r (run-in e path)]
        (is (= :ok (:status r)))
        (is (= "3" (:printed r)))
        (is (re-find #"couldn't resolve .*deps\.edn, so only its :paths are loaded" (:out r)) (:out r)))
      (testing "said once per deps.edn edit, not on every Run"
        (is (not (re-find #"couldn't resolve" (:out (run-in e path))))))
      (finally
        (roots/use-project! nil)
        (remove-ns 'depsbad)))))

(deftest a-failure-is-retried-test
  ;; A failure is kept only a little while, so a network blip heals on a
  ;; later Run (review finding 2).
  (let [dir (project! {"deps.edn" "{:paths [\"src\"] :deps {nope/nope {:git/url \"file:///no/such/repo\" :git/sha \"1111111111111111111111111111111111111111\"}}}"
                       ".jonquil.edn" "{:runtime :in-process}"
                       "src/depsretry.clj" "(ns depsretry)\n(+ 1 2)\n"})
        path (canon dir "src/depsretry.clj")
        e (ev/make (dialect/select dialect/profiles (dialect/find-project path) path nil))]
    (try
      (with-redefs [roots/failure-retry-ms 0]
        (is (re-find #"couldn't resolve" (:out (run-in e path))))
        (is (re-find #"couldn't resolve" (:out (run-in e path))) "tried again, so said again"))
      (finally
        (roots/use-project! nil)
        (remove-ns 'depsretry)))))

(deftest stop-during-a-resolve-test
  ;; Stop while dependencies are being fetched stops the Run, and isn't
  ;; remembered as a failure (review finding 1).
  (let [dir (project! {"deps.edn" "{:paths [\"src\"] :deps {slow/slow {:local/root \"/nowhere\"}}}"
                       ".jonquil.edn" "{:runtime :in-process}"
                       "src/depsstop.clj" "(ns depsstop)\n(+ 1 2)\n"})
        path (canon dir "src/depsstop.clj")
        e (ev/make (dialect/select dialect/profiles (dialect/find-project path) path nil))
        calls (atom 0)
        resolve-var (requiring-resolve 'jolt.deps/resolve-project)]
    (try
      (with-redefs-fn {resolve-var (fn [& _] (swap! calls inc) (Thread/sleep 10000) {:roots []})}
        (fn []
          (let [p (promise)]
            (ev/eval! e (slurp path) {:run? true :path path :on-output (fn [_ _]) :on-complete #(deliver p %)})
            (Thread/sleep 300)
            (ev/interrupt! e)
            (is (= :interrupted (:status (deref p 5000 :timeout)))))))
      (with-redefs-fn {resolve-var (fn [& _] (swap! calls inc) {:roots [(canon dir "src")]})}
        (fn []
          (is (= "3" (:printed (run-in e path))))
          (is (= 2 @calls) "resolved again: the Stop wasn't cached as a failure")))
      (finally
        (roots/use-project! nil)
        (remove-ns 'depsstop)))))
