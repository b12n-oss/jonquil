(ns jonquil.dialect-test
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.dialect :as dialect]
            [jonquil.syntax :as syntax]))

(defn- temp-dir []
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str "jonquil-dialect-test-" (System/nanoTime)))]
    (.mkdirs d)
    (.getCanonicalPath d)))

(defn- write! [dir rel text]
  (let [f (io/file dir rel)]
    (.mkdirs (.getParentFile f))
    (spit f text)
    (.getPath f)))

;; A second, jank-shaped profile, so the tie-break rules have something to
;; break a tie against. Test data only: D1 ships one profile.
(def ^:private other
  {:id :other :name "Other" :extensions #{"oth"} :project-markers ["other.edn"]
   :evaluator :nrepl :runtimes #{:nrepl} :special-forms #{} :builtins #{}})

(def ^:private two [dialect/jolt other])

(deftest syntax-reads-the-jolt-profile-test
  (is (identical? (:special-forms dialect/jolt) syntax/special-forms))
  (is (identical? (:builtins dialect/jolt) syntax/core-builtins))
  (is (= :special-form (first (first (syntax/tokenize "let"))))))

(deftest profile-lookup-test
  (is (= dialect/jolt (dialect/profile :jolt)))
  (is (nil? (dialect/profile :nope)))
  (is (= other (dialect/profile two :other))))

(deftest extension-test
  (is (= "clj" (dialect/extension "/a/b/core.clj")))
  (is (= "jank" (dialect/extension "/a/B.JANK")))
  (is (nil? (dialect/extension "/a/Makefile")))
  (is (nil? (dialect/extension "/a/.bashrc")))
  (is (nil? (dialect/extension nil))))

(deftest find-project-test
  (let [root (temp-dir)
        file (write! root "src/demo/core.clj" "(ns demo.core)")]
    (testing "no marker anywhere up to the root: nil"
      ;; (a temp dir can sit under a directory that does have a marker on
      ;; some machines; this asserts only on what the test created)
      (is (not= root (:dir (dialect/find-project file)))))
    (write! root "deps.edn" "{:paths [\"src\"]}")
    (let [p (dialect/find-project file)]
      (is (= root (:dir p)))
      (is (= #{"deps.edn"} (:markers p)))
      (is (nil? (:config p))))
    (write! root ".jonquil.edn" "{:runtime :in-process}")
    (is (= "{:runtime :in-process}" (:config (dialect/find-project file))))
    (testing "the nearest directory wins"
      (write! root "src/demo/deps.edn" "{}")
      (is (= (str root "/src/demo") (:dir (dialect/find-project file)))))
    (is (nil? (dialect/find-project nil)))))

(deftest parse-config-test
  (is (= {:dialect :jolt :runtime nil :warnings []} (dialect/parse-config "{:dialect :jolt}" nil)))
  (is (= {:warnings []} (dialect/parse-config nil nil)))
  (is (= {:warnings []} (dialect/parse-config "" nil)))
  (doseq [[text needle] [["{:runtime" "isn't valid EDN"]
                         ["[:jolt]" "should hold a map"]
                         ["\"jolt\"" "should hold a map"]]]
    (let [r (dialect/parse-config text nil)]
      (is (nil? (:dialect r)) text)
      (is (str/includes? (first (:warnings r)) needle) text)))
  (let [r (dialect/parse-config "{:dialect \"jolt\" :runtime :nrepl}" nil)]
    (is (nil? (:dialect r)))
    (is (= :nrepl (:runtime r)))
    (is (str/includes? (first (:warnings r)) ":dialect should be a keyword")))
  (is (str/includes? (first (:warnings (dialect/parse-config nil "Permission denied")))
                     "couldn't be read (Permission denied)")))

(defn- proj [markers config]
  {:dir "/p" :markers markers :config config :config-error nil})

(deftest select-test
  (testing "no project, no file: the fallback (the first profile)"
    (is (= {:profile dialect/jolt :runtime :in-process :project-dir nil :source :fallback :warnings []}
           (dialect/select dialect/profiles nil nil nil))))
  (testing ".jonquil.edn wins over markers"
    (let [s (dialect/select two (proj #{"deps.edn"} "{:dialect :other}") "/p/a.clj" nil)]
      (is (= [:other :config :nrepl] [(:id (:profile s)) (:source s) (:runtime s)]))))
  (testing "one marker decides"
    (let [s (dialect/select two (proj #{"other.edn"} nil) "/p/a.clj" :jolt)]
      (is (= [:other :marker] [(:id (:profile s)) (:source s)]))))
  (testing "two markers: the extension breaks the tie"
    (let [s (dialect/select two (proj #{"deps.edn" "other.edn"} nil) "/p/a.oth" nil)]
      (is (= [:other :extension] [(:id (:profile s)) (:source s)]))))
  (testing "a loose file: the extension decides"
    (is (= [:other :extension] ((juxt (comp :id :profile) :source) (dialect/select two nil "/tmp/x.oth" nil))))
    (is (= [:jolt :extension] ((juxt (comp :id :profile) :source) (dialect/select two nil "/tmp/x.clj" :other)))))
  (testing "no extension match: the settings default, then the first profile"
    (is (= [:other :settings] ((juxt (comp :id :profile) :source) (dialect/select two nil "/tmp/README" :other))))
    (is (= [:jolt :fallback] ((juxt (comp :id :profile) :source) (dialect/select two nil nil :nope)))))
  (testing "a runtime the dialect offers is kept; another is a warning"
    (let [s (dialect/select two (proj #{} "{:dialect :other :runtime :nrepl}") nil nil)]
      (is (= :nrepl (:runtime s)))
      (is (= [] (:warnings s))))
    (let [s (dialect/select dialect/profiles (proj #{"deps.edn"} "{:runtime :bogus}") "/p/a.clj" nil)]
      (is (= :in-process (:runtime s)))
      (is (= [".jonquil.edn asks for runtime :bogus, which Jolt doesn't offer here; using in-process"]
             (:warnings s)))))
  (testing "a malformed .jonquil.edn is a warning, and the other rules decide"
    (let [s (dialect/select dialect/profiles (proj #{"deps.edn"} "{:dialect") "/p/a.clj" nil)]
      (is (= [:jolt :marker :in-process] [(:id (:profile s)) (:source s) (:runtime s)]))
      (is (= [".jonquil.edn isn't valid EDN, so it is ignored"] (:warnings s)))))
  (testing "an unknown :dialect is a warning"
    (let [s (dialect/select dialect/profiles (proj #{} "{:dialect :jank}") "/p/a.clj" nil)]
      (is (= :jolt (:id (:profile s))))
      (is (= [".jonquil.edn names :jank, which isn't a known dialect"] (:warnings s)))))
  (is (= "/p" (:project-dir (dialect/select dialect/profiles (proj #{"deps.edn"} nil) nil nil)))))

(deftest label-test
  (is (= "Jolt" (dialect/label {:profile dialect/jolt :runtime :in-process})))
  (is (= "Jolt (nREPL)" (dialect/label {:profile dialect/jolt :runtime :nrepl}))))

(deftest nrepl-runtime-is-offered-test
  (let [s (dialect/select dialect/profiles (proj #{"deps.edn"} "{:runtime :nrepl}") "/p/src/a.clj" nil)]
    (is (= [:jolt :nrepl "/p" []] [(:id (:profile s)) (:runtime s) (:project-dir s) (:warnings s)])))
  (is (= "Jolt (nREPL)" (dialect/label (dialect/select dialect/profiles (proj #{} "{:runtime :nrepl}") nil nil)))))

(deftest selection-key-test
  (let [k (fn [config dir path]
            (dialect/selection-key
             (dialect/select dialect/profiles (when config (assoc (proj #{"deps.edn"} config) :dir dir)) path nil)))]
    (is (= [:jolt :in-process nil] (k nil nil "/tmp/a.clj")))
    (is (= (k nil nil "/tmp/a.clj") (k "{}" "/p" "/p/a.clj")) "in-process ignores the project")
    (is (= [:jolt :nrepl "/p"] (k "{:runtime :nrepl}" "/p" "/p/a.clj")))
    (is (not= (k "{:runtime :nrepl}" "/p" "/p/a.clj") (k "{:runtime :nrepl}" "/q" "/q/a.clj"))
        "another nREPL project needs its own child")))

;; P1.T1: the ClojureDart profile. `with-cljd` is the profile list the
;; window will use once P2 wires the attach evaluator in.
(def ^:private with-cljd [dialect/jolt dialect/cljd])

(deftest cljd-profile-test
  (is (= [:cljd "ClojureDart" #{"cljd"} ["pubspec.yaml"] :attach #{:attach} "NREPL.lock" true]
         ((juxt :id :name :extensions :project-markers :evaluator :runtimes :port-file :experimental) dialect/cljd)))
  (testing "its sets start from Jolt's"
    (is (set/subset? (:special-forms dialect/jolt) (:special-forms dialect/cljd)))
    (is (= (:builtins dialect/jolt) (:builtins dialect/cljd))))
  (testing "and add ClojureDart's own forms, none of which Jolt's sets already hold"
    (let [added (set/difference (:special-forms dialect/cljd) (:special-forms dialect/jolt))]
      (is (every? added ["await" "dart/await" "new" "." "widget" "f/widget" "f/$"]))
      (is (empty? (set/intersection added (:builtins dialect/jolt))))))
  (is (= [dialect/jolt] dialect/profiles) "P2.T4 adds cljd, with the attach evaluator"))

(deftest cljd-select-test
  (testing "a .cljd file in a pubspec.yaml project: cljd by its marker"
    (let [s (dialect/select with-cljd (proj #{"pubspec.yaml"} nil) "/p/lib/main.cljd" nil)]
      (is (= [:cljd :marker :attach "/p" []]
             [(:id (:profile s)) (:source s) (:runtime s) (:project-dir s) (:warnings s)]))))
  (testing "both markers (deps.edn and pubspec.yaml): the extension breaks the tie"
    (let [both (proj #{"deps.edn" "pubspec.yaml"} nil)]
      (is (= [:cljd :extension :attach] ((juxt (comp :id :profile) :source :runtime)
                                         (dialect/select with-cljd both "/p/src/a/drive.cljd" nil))))
      (is (= [:jolt :extension :in-process] ((juxt (comp :id :profile) :source :runtime)
                                             (dialect/select with-cljd both "/p/dev/user.clj" nil))))
      (is (= [:jolt :extension] ((juxt (comp :id :profile) :source)
                                 (dialect/select with-cljd both "/p/src/a/shared.cljc" nil)))
          ".cljc stays Jolt's")
      (is (= [:jolt :fallback] ((juxt (comp :id :profile) :source)
                                (dialect/select with-cljd both "/p/bb.edn" nil)))
          "bb.edn: no extension match, so the first profile")))
  (testing "a loose .cljd file: cljd by extension, no project"
    (is (= [:cljd :extension nil] ((juxt (comp :id :profile) :source :project-dir)
                                   (dialect/select with-cljd nil "/tmp/x.cljd" nil)))))
  (testing ".jonquil.edn {:dialect :cljd} pins it for every file"
    (is (= [:cljd :config :attach] ((juxt (comp :id :profile) :source :runtime)
                                    (dialect/select with-cljd (proj #{"deps.edn" "pubspec.yaml"} "{:dialect :cljd}")
                                                    "/p/dev/user.clj" nil)))))
  (testing "any other runtime: the existing \"doesn't offer here\" warning, then :attach"
    (doseq [rt [":nrepl" ":in-process"]]
      (let [s (dialect/select with-cljd (proj #{"pubspec.yaml"} (str "{:dialect :cljd :runtime " rt "}"))
                              "/p/lib/main.cljd" nil)]
        (is (= :attach (:runtime s)) rt)
        (is (= [(str ".jonquil.edn asks for runtime " rt ", which ClojureDart doesn't offer here; using attach")]
               (:warnings s)) rt)))))

(deftest cljd-label-test
  (let [sel (dialect/select with-cljd (proj #{"pubspec.yaml"} nil) "/p/lib/main.cljd" nil)]
    (is (= "ClojureDart (not running)" (dialect/label sel)))
    (is (= "ClojureDart (not running)" (dialect/label sel nil)))
    (is (= "ClojureDart (attached)" (dialect/label sel :attached)))
    (is (= "ClojureDart (not running)" (dialect/label sel :not-running))))
  (testing "Jolt's labels ignore the connection argument"
    (is (= "Jolt" (dialect/label {:profile dialect/jolt :runtime :in-process} :attached)))
    (is (= "Jolt (nREPL)" (dialect/label {:profile dialect/jolt :runtime :nrepl} :attached)))))

(deftest cljd-selection-key-test
  (let [k (fn [dir path] (dialect/selection-key (dialect/select with-cljd (assoc (proj #{"pubspec.yaml"} nil) :dir dir) path nil)))]
    (is (= [:cljd :attach "/p"] (k "/p" "/p/lib/a.cljd")))
    (is (= (k "/p" "/p/lib/a.cljd") (k "/p" "/p/dev/b.cljd")) "another file in the same app keeps the connection")
    (is (not= (k "/p" "/p/lib/a.cljd") (k "/q" "/q/lib/a.cljd")) "another app needs its own")))
