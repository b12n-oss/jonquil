(ns jonquil.dialect-test
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jonquil.cljd :as cljd]
            [jonquil.cljs :as cljs]
            [jonquil.dialect :as dialect]
            [jonquil.syntax :as syntax]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(defn- temp-dir [] (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-dialect-test-"))))

(defn- write! [dir rel text]
  (let [f (io/file dir rel)]
    (.mkdirs (.getParentFile f))
    (spit f text)
    (.getPath f)))

(defn- temp-project
  "Writes files ({relative-path text}) into a fresh temp dir and returns its
  canonical path."
  [files]
  (let [dir (temp-dir)]
    (doseq [[rel text] files] (write! dir rel text))
    dir))

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

(deftest parse-config-parinfer-test
  (is (= :smart (:parinfer (dialect/parse-config "{:parinfer :smart}" nil))))
  (is (= :off (:parinfer (dialect/parse-config "{:parinfer :off}" nil)))
      "a project can pin Parinfer off")
  (let [r (dialect/parse-config "{:parinfer :fancy}" nil)]
    (is (not (contains? r :parinfer)))
    (is (str/includes? (first (:warnings r)) ":parinfer")))
  (is (not (contains? (dialect/parse-config "{:dialect :jolt}" nil) :parinfer))))

(deftest parse-config-format-style-test
  (is (= :paren-stable (:format-style (dialect/parse-config "{:format-style :paren-stable}" nil))))
  (is (= :plain (:format-style (dialect/parse-config "{:format-style :plain}" nil)))
      "a project can pin the plain formatter even with Parinfer on")
  (let [r (dialect/parse-config "{:format-style :fancy}" nil)]
    (is (not (contains? r :format-style)))
    (is (str/includes? (first (:warnings r)) ":format-style")))
  (is (not (contains? (dialect/parse-config "{:dialect :jolt}" nil) :format-style))))

(defn- proj [markers config]
  {:dir "/p" :markers markers :config config :config-error nil})

(deftest select-test
  (testing "no project, no file: the fallback (the first profile)"
    (is (= {:profile dialect/jolt :runtime :in-process :project-dir nil :source :fallback :warnings []}
           (dialect/select dialect/profiles nil nil nil))))
  (testing ".jonquil.edn wins over markers"
    (let [s (dialect/select two (proj #{"deps.edn"} "{:dialect :other}") "/p/a.clj" nil)]
      (is (= [:other :config :nrepl] [(:id (:profile s)) (:source s) (:runtime s)]))))
  (testing "one marker decides, when its profile's own extension agrees (or there's no path at all)"
    (let [s (dialect/select two (proj #{"other.edn"} nil) "/p/a.oth" :jolt)]
      (is (= [:other :marker] [(:id (:profile s)) (:source s)])))
    (let [s (dialect/select two (proj #{"other.edn"} nil) nil :jolt)]
      (is (= [:other :marker] [(:id (:profile s)) (:source s)]))))
  (testing "I2: a single marker doesn't outrank another profile's own extension"
    (let [s (dialect/select two (proj #{"other.edn"} nil) "/p/a.clj" :jolt)]
      (is (= [:jolt :extension] [(:id (:profile s)) (:source s)]))))
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
      (is (= :nrepl (:runtime s)))
      (is (= [".jonquil.edn asks for runtime :bogus, which Jolt doesn't offer here; using nREPL"]
             (:warnings s)))))
  (testing "a malformed .jonquil.edn is a warning, and the other rules decide"
    (let [s (dialect/select dialect/profiles (proj #{"deps.edn"} "{:dialect") "/p/a.clj" nil)]
      (is (= [:jolt :marker :nrepl] [(:id (:profile s)) (:source s) (:runtime s)]))
      (is (= [".jonquil.edn isn't valid EDN, so it is ignored"] (:warnings s)))))
  (testing "an unknown :dialect is a warning"
    (let [s (dialect/select dialect/profiles (proj #{} "{:dialect :bogus}") "/p/a.clj" nil)]
      (is (= :jolt (:id (:profile s))))
      (is (= [".jonquil.edn names :bogus, which isn't a known dialect"] (:warnings s)))))
  (is (= "/p" (:project-dir (dialect/select dialect/profiles (proj #{"deps.edn"} nil) nil nil)))))

(deftest jolt-project-default-test
  (let [rt (fn [project path] (:runtime (dialect/select dialect/profiles project path nil)))]
    (testing "a deps.edn project runs Jolt out of process"
      (is (= :nrepl (rt (proj #{"deps.edn"} nil) "/p/src/a.clj")))
      (is (= :nrepl (rt (proj #{"deps.edn"} nil) nil)) "an untitled tab in the project"))
    (testing ".jonquil.edn {:runtime :in-process} opts a project back"
      (is (= :in-process (rt (proj #{"deps.edn"} "{:runtime :in-process}") "/p/src/a.clj"))))
    (testing "no project: in process"
      (is (= :in-process (rt nil "/tmp/a.clj")))
      (is (= :in-process (rt nil nil))))
    (testing "a folder found without Jolt's own marker stays in process"
      (is (= :in-process (rt (proj #{} "{:dialect :jolt}") "/p/a.clj")) "only .jonquil.edn")
      (is (= :in-process (rt (proj #{"pubspec.yaml"} nil) "/p/a.clj")) "another dialect's marker"))
    (testing "other dialects keep their own evaluator"
      (is (= :attach (rt (proj #{"deps.edn"} "{:dialect :clj}") "/p/a.clj"))))))

(deftest label-test
  (is (= "Jolt" (dialect/label {:profile dialect/jolt :runtime :in-process})))
  (is (= "Jolt (nREPL)" (dialect/label {:profile dialect/jolt :runtime :nrepl}))))

(deftest nrepl-runtime-is-offered-test
  (let [s (dialect/select dialect/profiles (proj #{"deps.edn"} "{:runtime :nrepl}") "/p/src/a.clj" nil)]
    (is (= [:jolt :nrepl "/p" []] [(:id (:profile s)) (:runtime s) (:project-dir s) (:warnings s)])))
  (is (= "Jolt (nREPL)" (dialect/label (dialect/select dialect/profiles (proj #{} "{:runtime :nrepl}") nil nil)))))

(deftest select-passes-parinfer-through-test
  (testing "any dialect's project carries its :parinfer override"
    (is (= :paren (:parinfer (dialect/select dialect/profiles
                                             (proj #{"deps.edn"} "{:parinfer :paren}")
                                             "/p/a.clj" nil))))
    (is (not (contains? (dialect/select dialect/profiles (proj #{"deps.edn"} "{:dialect :jolt}") "/p/a.clj" nil)
                        :parinfer)))))

(deftest select-passes-format-style-through-test
  (testing "any dialect's project carries its :format-style pin (the window
           row caught select dropping it while parse-config was green)"
    (is (= :paren-stable (:format-style (dialect/select dialect/profiles
                                                        (proj #{"deps.edn"} "{:format-style :paren-stable}")
                                                        "/p/a.clj" nil))))
    (is (not (contains? (dialect/select dialect/profiles (proj #{"deps.edn"} "{:dialect :jolt}") "/p/a.clj" nil)
                        :format-style)))))

(deftest selection-key-test
  (let [k (fn [config dir path]
            (dialect/selection-key
             (dialect/select dialect/profiles (when config (assoc (proj #{"deps.edn"} config) :dir dir)) path nil)))]
    (is (= [:jolt :in-process nil] (k nil nil "/tmp/a.clj")))
    (is (= (k nil nil "/tmp/a.clj") (k "{:runtime :in-process}" "/p" "/p/a.clj")) "in-process ignores the project")
    (is (= [:jolt :nrepl "/p"] (k "{}" "/p" "/p/a.clj")) "a deps.edn project runs out of process by default")
    (is (= [:jolt :nrepl "/p"] (k "{:runtime :nrepl}" "/p" "/p/a.clj")))
    (is (not= (k "{:runtime :nrepl}" "/p" "/p/a.clj") (k "{:runtime :nrepl}" "/q" "/q/a.clj"))
        "another nREPL project needs its own child")))

;; the ClojureDart profile. `with-cljd` is the profile list the
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
  (is (= [dialect/jolt dialect/cljd dialect/clj dialect/jank dialect/cljs dialect/nrepl] dialect/profiles)
      "clj joined in P2.T4, jank in D3 P1.T1, cljs next, then nrepl (claims no extension or marker)"))

(deftest cljd-select-test
  (testing "a .cljd file in a pubspec.yaml project: cljd by its marker"
    (let [s (dialect/select with-cljd (proj #{"pubspec.yaml"} nil) "/p/lib/main.cljd" nil)]
      (is (= [:cljd :marker :attach "/p" []]
             [(:id (:profile s)) (:source s) (:runtime s) (:project-dir s) (:warnings s)]))))
  (testing "both markers (deps.edn and pubspec.yaml): the extension breaks the tie"
    (let [both (proj #{"deps.edn" "pubspec.yaml"} nil)]
      (is (= [:cljd :extension :attach] ((juxt (comp :id :profile) :source :runtime)
                                         (dialect/select with-cljd both "/p/src/a/drive.cljd" nil))))
      (is (= [:jolt :extension :nrepl] ((juxt (comp :id :profile) :source :runtime)
                                        (dialect/select with-cljd both "/p/dev/user.clj" nil)))
          "deps.edn is Jolt's own marker, so its project default applies")
      (is (= [:jolt :extension] ((juxt (comp :id :profile) :source)
                                 (dialect/select with-cljd both "/p/src/a/shared.cljc" nil)))
          ".cljc stays Jolt's")
      (is (= [:jolt :fallback] ((juxt (comp :id :profile) :source)
                                (dialect/select with-cljd both "/p/bb.edn" nil)))
          "bb.edn: no extension match, so the first profile")))
  (testing "a loose .cljd file: cljd by extension, no project"
    (is (= [:cljd :extension nil] ((juxt (comp :id :profile) :source :project-dir)
                                   (dialect/select with-cljd nil "/tmp/x.cljd" nil)))))
  (testing "I2: a pubspec-only project's single marker (cljd) doesn't outrank a Jolt-extensioned file"
    (let [pubspec-only (proj #{"pubspec.yaml"} nil)]
      (is (= [:jolt :extension] ((juxt (comp :id :profile) :source)
                                 (dialect/select with-cljd pubspec-only "/p/tool/build.clj" nil))))
      (is (= [:jolt :fallback] ((juxt (comp :id :profile) :source)
                                (dialect/select with-cljd pubspec-only "/p/bb.edn" nil))))
      (is (= [:cljd :marker] ((juxt (comp :id :profile) :source)
                              (dialect/select with-cljd pubspec-only "/p/lib/x.cljd" nil))))
      (is (= [:cljd :marker] ((juxt (comp :id :profile) :source)
                              (dialect/select with-cljd pubspec-only nil nil))))))
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

;; the :clj (JVM Clojure) profile. Not yet in dialect/profiles; it joins once the window can reach make-attached :clj.
(def ^:private all3 [dialect/jolt dialect/cljd dialect/clj])

(deftest clj-profile-test
  (is (= :clj (:id dialect/clj)))
  (is (= "Clojure (JVM)" (:name dialect/clj)))
  (is (= #{"clj" "cljc"} (:extensions dialect/clj)))
  (is (= [] (:project-markers dialect/clj)))
  (is (= :attach (:evaluator dialect/clj)))
  (is (= #{:attach} (:runtimes dialect/clj)))
  (is (= ".nrepl-port" (:port-file dialect/clj)))
  (is (= "This is your own JVM, so Jonquil doesn't unload namespaces in it." (:reset-tooltip dialect/clj)))
  (is (= "Hot reload can't unload a namespace, so every Run adds to the running app." (:reset-tooltip dialect/cljd)))
  (is (some #{dialect/clj} dialect/profiles) "ruling 3: joins profiles in P2.T4"))

(deftest clj-selection-test
  (let [src "(ns demo.core)\n"
        proj1 (temp-project {"deps.edn" "{}" ".jonquil.edn" "{:dialect :clj}" "src/demo/core.clj" src})
        proj2 (temp-project {"deps.edn" "{}" "bb.edn" "{}" "src/demo/core.clj" src})
        core-path (str proj1 "/src/demo/core.clj")
        core-path2 (str proj2 "/src/demo/core.clj")
        deps-path2 (str proj2 "/deps.edn")
        bb-path2 (str proj2 "/bb.edn")]
    (try
      (testing ".jonquil.edn {:dialect :clj} selects :clj, runtime :attach"
        (let [sel (dialect/select all3 (dialect/find-project all3 core-path) core-path nil)]
          (is (= :clj (:id (:profile sel))))
          (is (= :attach (:runtime sel)))
          (is (= :config (:source sel)))))
      (testing "the same project without .jonquil.edn stays Jolt"
        (is (= :jolt (:id (:profile (dialect/select all3 (dialect/find-project all3 core-path2) core-path2 nil))))))
      (testing "a settings default of :clj doesn't take a .clj file from Jolt"
        (is (= :jolt (:id (:profile (dialect/select all3 (dialect/find-project all3 core-path2) core-path2 :clj))))))
      (testing "I2: a settings default of :clj doesn't take deps.edn or bb.edn from Jolt inside a deps.edn project"
        (let [sel-deps (dialect/select all3 (dialect/find-project all3 deps-path2) deps-path2 :clj)
              sel-bb (dialect/select all3 (dialect/find-project all3 bb-path2) bb-path2 :clj)]
          (is (= [:jolt :fallback] [(:id (:profile sel-deps)) (:source sel-deps)]))
          (is (= [:jolt :fallback] [(:id (:profile sel-bb)) (:source sel-bb)]))))
      (testing "I2: a loose file with no project still gets the settings default of :clj"
        (let [sel (dialect/select all3 nil "/tmp/x.txt" :clj)]
          (is (= :clj (:id (:profile sel))))
          (is (= :settings (:source sel)))
          (is (nil? (:project-dir sel)))))
      (testing "an untitled buffer with a settings default of :clj lands in :clj, with no project dir"
        (let [sel (dialect/select all3 nil nil :clj)]
          (is (= :clj (:id (:profile sel))))
          (is (nil? (:project-dir sel)))))
      (testing "I2: a settings default naming a profile with its own markers (:cljd) is unaffected inside a project"
        (let [sel (dialect/select all3 (proj #{} nil) "/p/README" :cljd)]
          (is (= [:cljd :settings] [(:id (:profile sel)) (:source sel)]))))
      (testing "the label"
        (is (= "Clojure (JVM) (not running)" (dialect/label {:profile dialect/clj :runtime :attach} nil)))
        (is (= "Clojure (JVM) (attached)" (dialect/label {:profile dialect/clj :runtime :attach} :attached))))
      (finally
        (temp-dirs/delete-tree! proj1)
        (temp-dirs/delete-tree! proj2)))))

(deftest reset-toggle-test
  (let [jolt-sel {:profile dialect/jolt :runtime :in-process}
        cljd-sel {:profile dialect/cljd :runtime :attach}
        clj-sel {:profile dialect/clj :runtime :attach}]
    (testing "an attach runtime unticks and disables it, and keeps the tick it took down"
      (is (= {:state {:saved true} :active? false :sensitive? false :tooltip (:reset-tooltip dialect/clj)}
             (dialect/reset-toggle {} clj-sel true))))
    (testing "back to Jolt puts the kept tick back up, either way"
      (is (= {:state {} :active? true :sensitive? true :tooltip nil}
             (dialect/reset-toggle {:saved true} jolt-sel false)))
      (is (= {:state {} :active? false :sensitive? true :tooltip nil}
             (dialect/reset-toggle {:saved false} jolt-sel false))))
    (testing "attach to attach keeps the first saved tick, not the unticked box"
      (is (= {:saved true}
             (:state (dialect/reset-toggle {:saved true} cljd-sel false))))
      (is (= (:reset-tooltip dialect/cljd) (:tooltip (dialect/reset-toggle {:saved true} cljd-sel false)))))
    (testing "Jolt to Jolt leaves the box as the person set it"
      (is (= {:state {} :active? false :sensitive? true :tooltip nil}
             (dialect/reset-toggle {} jolt-sel false))))))

(deftest auto-require-config-test
  (testing "a vector of symbols is read as is"
    (is (= '[my.app.core my.app.dev]
           (:auto-require (dialect/parse-config "{:dialect :clj :auto-require [my.app.core my.app.dev]}" nil)))))
  (testing "absent or empty: no key, no warning, so other configs read exactly as before"
    (is (= {:dialect :clj :runtime nil :warnings []} (dialect/parse-config "{:dialect :clj}" nil)))
    (is (= {:dialect :clj :runtime nil :warnings []} (dialect/parse-config "{:dialect :clj :auto-require []}" nil))))
  (doseq [text ["{:dialect :clj :auto-require my.app.core}"
                "{:dialect :clj :auto-require [my.app.core \"my.app.dev\"]}"
                "{:dialect :clj :auto-require (my.app.core)}"]]
    (testing (str "malformed is a warning and ignored, the rest still read: " text)
      (let [r (dialect/parse-config text nil)]
        (is (= :clj (:dialect r)))
        (is (nil? (:auto-require r)))
        (is (str/includes? (first (:warnings r)) ":auto-require should be a vector of namespace symbols"))))))

(deftest auto-require-selection-test
  (testing "a :clj project's list reaches the selection"
    (let [s (dialect/select all3 (proj #{"deps.edn"} "{:dialect :clj :auto-require [my.app.core]}") "/p/a.clj" nil)]
      (is (= :clj (:id (:profile s))))
      (is (= '[my.app.core] (:auto-require s)))
      (is (= [] (:warnings s)))))
  (testing "a Jolt project's list reaches the selection too, in or out of process"
    (let [s (dialect/select all3 (proj #{"deps.edn"} "{:auto-require [my.app.core]}") "/p/a.clj" nil)]
      (is (= :jolt (:id (:profile s))))
      (is (= '[my.app.core] (:auto-require s)))
      (is (= [] (:warnings s)))))
  (testing "any other dialect ignores it, with a warning"
    (let [s (dialect/select dialect/profiles (proj #{"deps.edn"} "{:dialect :jank :auto-require [my.app.core]}") "/p/a.jank" nil)]
      (is (= :jank (:id (:profile s))))
      (is (nil? (:auto-require s)))
      (is (= 1 (count (:warnings s))))
      (is (str/includes? (first (:warnings s)) ":auto-require only applies to JVM Clojure and Jolt")))))

;; the :jank profile, which Jonquil launches itself.
(def ^:private all4 [dialect/jolt dialect/cljd dialect/clj dialect/jank])

(deftest jank-profile-test
  (is (= :jank (:id dialect/jank)))
  (is (contains? (:extensions dialect/jank) "jank"))
  (is (contains? (:special-forms dialect/jank) "cpp/raw"))
  (is (contains? (:special-forms dialect/jank) "let*"))
  (is (contains? (:special-forms dialect/jank) "defn") "Jolt's forms are kept")
  (is (= #{:launch} (:runtimes dialect/jank))))

(deftest jank-selection-test
  (let [dir (temp-project {"deps.edn" "{}" "src/a.jank" "(ns a)\n" "src/b.jank" "(ns b)\n"
                           "src/c.clj" "(ns c)\n"})
        sel-of (fn [path]
                 (dialect/select all4 (dialect/find-project all4 path) path nil))]
    (try
      (testing "a .jank file in a deps.edn project: jank by extension"
        (let [sel (sel-of (str dir "/src/a.jank"))]
          (is (= [:jank :extension :launch] [(:id (:profile sel)) (:source sel) (:runtime sel)]))))
      (testing "a loose .jank file"
        (is (= :jank (:id (:profile (dialect/select all4 nil "/tmp/x.jank" nil))))))
      (testing ".jonquil.edn {:dialect :jank} takes a .clj file"
        (write! dir ".jonquil.edn" "{:dialect :jank}")
        (let [sel (sel-of (str dir "/src/c.clj"))]
          (is (= [:jank :config :launch] [(:id (:profile sel)) (:source sel) (:runtime sel)]))))
      (testing "two .jank files in one project share a selection key"
        (is (= (dialect/selection-key (sel-of (str dir "/src/a.jank")))
               (dialect/selection-key (sel-of (str dir "/src/b.jank"))))))
      (finally (temp-dirs/delete-tree! dir)))))

(deftest jank-label-test
  (is (= "jank (not running)" (dialect/label {:profile dialect/jank :runtime :launch} nil)))
  (is (= "jank (running)" (dialect/label {:profile dialect/jank :runtime :launch} :attached))))

(deftest experimental-message-test
  (doseq [p dialect/profiles :when (:experimental p)]
    (is (string? (:experimental-message p)) (str (:id p))))
  ;; dialect can't require cljd, so the text lives in both places.
  (is (= cljd/experimental-message (:experimental-message dialect/cljd)))
  (is (= cljs/experimental-message (:experimental-message dialect/cljs))))

;; The :cljs profile, attached to a shadow-cljs nREPL server.
(def ^:private all5 [dialect/jolt dialect/cljd dialect/clj dialect/jank dialect/cljs])

(deftest cljs-profile-test
  (is (= dialect/cljs (dialect/profile :cljs)))
  (is (= :cljs (:id dialect/cljs)))
  (is (= #{"cljs"} (:extensions dialect/cljs)))
  (is (= ["shadow-cljs.edn"] (:project-markers dialect/cljs)))
  (is (= [:launch :attach #{:attach :launch} ".shadow-cljs/nrepl.port"]
         [(:evaluator dialect/cljs) (:project-evaluator dialect/cljs) (:runtimes dialect/cljs) (:port-file dialect/cljs)]))
  (is (= "ClojureScript" (:name dialect/cljs)))
  (is (= {:attach "ClojureScript (shadow-cljs)" :launch "ClojureScript (node)"} (:runtime-names dialect/cljs)))
  (is (= (:special-forms dialect/jolt) (:special-forms dialect/cljs))))

(deftest cljs-selection-test
  (let [dir (temp-project {"shadow-cljs.edn" "{}" "src/a.cljs" "(ns a)\n" "src/b.clj" "(ns b)\n"})
        sel-of (fn [path]
                 (dialect/select all5 (dialect/find-project all5 path) path nil))]
    (try
      (testing "a .cljs file in a shadow-cljs.edn project: cljs by marker"
        (let [sel (sel-of (str dir "/src/a.cljs"))]
          (is (= [:cljs :marker :attach] [(:id (:profile sel)) (:source sel) (:runtime sel)]))))
      (testing "the marker alone doesn't take a .clj file"
        (is (not= :cljs (:id (:profile (sel-of (str dir "/src/b.clj")))))))
      (testing "select with :markers #{shadow-cljs.edn}"
        (is (= :cljs (:id (:profile (dialect/select all5 (proj #{"shadow-cljs.edn"} nil) "/p/a.cljs" nil))))))
      (testing ".jonquil.edn {:dialect :cljs} selects it"
        (let [s (dialect/select all5 (proj #{} "{:dialect :cljs}") "/p/a.clj" nil)]
          (is (= [:cljs :config] [(:id (:profile s)) (:source s)]))))
      (finally (temp-dirs/delete-tree! dir)))))

(deftest cljs-config-test
  (testing "a map is carried through"
    (is (= {:shadow-build :app} (:cljs (dialect/parse-config "{:dialect :cljs :cljs {:shadow-build :app}}" nil)))))
  (testing "nil and absent say nothing"
    (is (= {:dialect :cljs :runtime nil :warnings []} (dialect/parse-config "{:dialect :cljs :cljs nil}" nil)))
    (is (= {:dialect :cljs :runtime nil :warnings []} (dialect/parse-config "{:dialect :cljs}" nil))))
  (testing "an empty map is carried"
    (is (= {} (:cljs (dialect/parse-config "{:cljs {}}" nil)))))
  (testing "another shape is a warning and dropped"
    (let [r (dialect/parse-config "{:dialect :cljs :cljs 5}" nil)]
      (is (not (contains? r :cljs)))
      (is (= [".jonquil.edn :cljs should be a map, like {:shadow-build :app}"] (:warnings r))))))

(deftest cljs-config-selection-test
  (testing "a :cljs project's map reaches the selection"
    (let [s (dialect/select all5 (proj #{} "{:dialect :cljs :cljs {:shadow-build :app}}") "/p/a.cljs" nil)]
      (is (= {:shadow-build :app} (:cljs s)))
      (is (= [] (:warnings s)))))
  (testing "an empty map is carried"
    (is (= {} (:cljs (dialect/select all5 (proj #{} "{:dialect :cljs :cljs {}}") "/p/a.cljs" nil)))))
  (testing "nil carries nothing and warns nothing"
    (let [s (dialect/select all5 (proj #{} "{:dialect :cljs :cljs nil}") "/p/a.cljs" nil)]
      (is (not (contains? s :cljs)))
      (is (= [] (:warnings s)))))
  (testing "any other dialect ignores it, with a warning"
    (let [s (dialect/select all5 (proj #{"deps.edn"} "{:cljs {:shadow-build :app}}") "/p/a.clj" nil)]
      (is (= :jolt (:id (:profile s))))
      (is (not (contains? s :cljs)))
      (is (= [".jonquil.edn :cljs only applies to ClojureScript, not Jolt, so it is ignored"] (:warnings s))))))

(deftest nrepl-config-test
  (testing "a valid :nrepl map is kept"
    (is (= {:host "box" :port 1667 :port-file "tmp/port"}
           (:nrepl (dialect/parse-config "{:dialect :nrepl :nrepl {:host \"box\" :port 1667 :port-file \"tmp/port\"}}" nil))))
    (is (= [] (:warnings (dialect/parse-config "{:nrepl {:port 7}}" nil)))))
  (testing "a bad value is a warning, and only that key is dropped"
    (let [r (dialect/parse-config "{:nrepl {:host \"\" :port 70000 :port-file \"/abs\"}}" nil)]
      (is (= {} (:nrepl r)))
      (is (= 3 (count (:warnings r))))
      (is (every? #(str/includes? % ".jonquil.edn :nrepl") (:warnings r)))))
  (testing "a non-map :nrepl is a warning"
    (let [r (dialect/parse-config "{:nrepl 1667}" nil)]
      (is (nil? (:nrepl r)))
      (is (= [".jonquil.edn :nrepl should be a map, like {:port 1667} or {:host \"box\" :port 1667}"] (:warnings r))))))

(deftest nrepl-select-test
  (let [s (dialect/select dialect/profiles (proj #{"bb.edn"} "{:dialect :nrepl :nrepl {:port 1667}}") "/p/a.clj" nil)]
    (is (= [:nrepl :config :attach "/p" []] [(:id (:profile s)) (:source s) (:runtime s) (:project-dir s) (:warnings s)]))
    (is (= {:port 1667} (:nrepl s))))
  (testing "no project still selects it, from .jonquil.edn alone"
    (is (= :nrepl (:id (:profile (dialect/select dialect/profiles (proj #{} "{:dialect :nrepl}") "/p/a.bb" nil))))))
  (testing "nothing selects it without .jonquil.edn"
    (is (not= :nrepl (:id (:profile (dialect/select dialect/profiles nil "/tmp/a.clj" nil)))))
    (is (not= :nrepl (:id (:profile (dialect/select dialect/profiles (proj #{"bb.edn"} nil) "/p/a.bb" nil))))))
  (testing ":nrepl for another dialect is a warning"
    (let [s (dialect/select dialect/profiles (proj #{"deps.edn"} "{:dialect :clj :nrepl {:port 1}}") "/p/a.clj" nil)]
      (is (nil? (:nrepl s)))
      (is (= [".jonquil.edn :nrepl only applies to the nREPL dialect, not Clojure (JVM), so it is ignored"] (:warnings s)))))
  (testing "another endpoint needs another evaluator"
    (let [k (fn [cfg] (dialect/selection-key (dialect/select dialect/profiles (proj #{} cfg) "/p/a.clj" nil)))]
      (is (not= (k "{:dialect :nrepl :nrepl {:port 1}}") (k "{:dialect :nrepl :nrepl {:port 2}}")))
      (is (= (k "{:dialect :nrepl :nrepl {:port 1}}") (k "{:dialect :nrepl :nrepl {:port 1}}"))))))

(deftest cljs-launch-selection-test
  (let [sel (fn [markers cfg path] (dialect/select all5 (proj markers cfg) path nil))]
    (testing "a loose .cljs file launches"
      (let [s (dialect/select all5 nil "/tmp/x.cljs" nil)]
        (is (= [:cljs :launch] [(:id (:profile s)) (:runtime s)]))))
    (testing "a project without shadow-cljs.edn launches; with it, attaches"
      (is (= :launch (:runtime (sel #{"deps.edn"} nil "/p/a.cljs"))))
      (is (= :attach (:runtime (sel #{"shadow-cljs.edn"} nil "/p/a.cljs")))))
    (testing ".jonquil.edn :runtime overrides either way"
      (is (= :attach (:runtime (sel #{"deps.edn"} "{:dialect :cljs :runtime :attach}" "/p/a.cljs"))))
      (is (= :launch (:runtime (sel #{"shadow-cljs.edn"} "{:dialect :cljs :runtime :launch}" "/p/a.cljs")))))
    (testing ":cljs {:shadow-build} stays on a launch selection, without a warning"
      (let [s (sel #{"deps.edn"} "{:dialect :cljs :cljs {:shadow-build :app}}" "/p/a.cljs")]
        (is (= [:launch {:shadow-build :app} []] [(:runtime s) (:cljs s) (:warnings s)]))))))

(deftest cljs-launch-label-test
  (let [launch (dialect/select all5 nil "/tmp/x.cljs" nil)
        attach (dialect/select all5 (proj #{"shadow-cljs.edn"} nil) "/p/a.cljs" nil)]
    (is (= "ClojureScript (node) (running)" (dialect/label launch :attached)))
    (is (= "ClojureScript (node) (not running)" (dialect/label launch nil)))
    (is (= "ClojureScript (shadow-cljs) (attached)" (dialect/label attach :attached)))
    (is (= "ClojureScript (shadow-cljs) (not running)" (dialect/label attach nil)))))

(deftest cljs-launch-reset-toggle-test
  (let [launch (dialect/select all5 nil "/tmp/x.cljs" nil)
        jolt-sel {:profile dialect/jolt :runtime :in-process}
        tip (get-in dialect/cljs [:reset-tooltips :launch])]
    (testing "ClojureScript on node starts unticked but clickable, keeping the tick it took down"
      (is (= {:state {:saved true} :active? false :sensitive? true :tooltip tip}
             (dialect/reset-toggle {} launch true))))
    (testing "a tick the person gives it there stays while the window stays on node"
      (is (= {:state {:saved true} :active? true :sensitive? true :tooltip tip}
             (dialect/reset-toggle {:saved true} launch true))))
    (testing "back to Jolt puts the earlier tick back up"
      (is (= {:state {} :active? true :sensitive? true :tooltip nil}
             (dialect/reset-toggle {:saved true} jolt-sel false))))
    (testing "the shadow attach runtime is unchanged: unticked and disabled"
      (let [attach (assoc launch :runtime :attach)]
        (is (= [false false (:reset-tooltip dialect/cljs)]
               ((juxt :active? :sensitive? :tooltip) (dialect/reset-toggle {} attach true))))))))

(deftest cljs-target-selection-test
  (let [sel (fn [cfg] (dialect/select all5 (proj #{"deps.edn"} cfg) "/p/a.cljs" nil))
        shadow (fn [cfg] (dialect/select all5 (proj #{"shadow-cljs.edn"} cfg) "/p/a.cljs" nil))]
    (testing "a launch selection defaults to node"
      (let [s (sel nil)]
        (is (= [:launch :node []] [(:runtime s) (:cljs-target s) (:warnings s)]))))
    (testing ":browser and :node are taken as written"
      (is (= [:browser []] ((juxt :cljs-target :warnings) (sel "{:cljs {:target :browser}}"))))
      (is (= [:node []] ((juxt :cljs-target :warnings) (sel "{:cljs {:target :node}}")))))
    (testing ":forward-console? reaches the selection for a browser launch"
      (is (false? (:cljs-console? (sel "{:cljs {:target :browser :forward-console? false}}"))))
      (is (true? (:cljs-console? (sel "{:cljs {:target :browser}}"))))
      (is (true? (:cljs-console? (sel "{:cljs {:target :browser :forward-console? true}}"))))
      (is (nil? (:cljs-console? (sel "{:cljs {:target :node :forward-console? false}}")))
          "node has no console hook; the key is browser-only")
      (is (not= (dialect/selection-key (sel "{:cljs {:target :browser}}"))
                (dialect/selection-key (sel "{:cljs {:target :browser :forward-console? false}}")))
          "flipping the option rebuilds the evaluator, so it can't be silently dropped")
      (is (= (dialect/selection-key (sel "{:cljs {:target :browser}}"))
             (dialect/selection-key (sel "{:cljs {:target :browser :forward-console? true}}")))
          "spelling the default changes nothing"))
    (testing "a non-boolean :forward-console? warns and forwards"
      (let [s (sel "{:cljs {:target :browser :forward-console? :off}}")]
        (is (true? (:cljs-console? s)))
        (is (= [".jonquil.edn :cljs :forward-console? should be true or false, not :off; forwarding stays on"]
               (:warnings s)))))
    (testing "an unknown value falls back to node with a warning"
      (let [s (sel "{:cljs {:target :deno}}")]
        (is (= :node (:cljs-target s)))
        (is (= [".jonquil.edn :cljs :target should be :node or :browser, not :deno; using :node"] (:warnings s)))))
    (testing "an attach selection ignores :target, with a warning"
      (let [s (shadow "{:cljs {:target :browser}}")]
        (is (= :attach (:runtime s)))
        (is (not (contains? s :cljs-target)))
        (is (= [".jonquil.edn :cljs :target applies only to ClojureScript that Jonquil starts, not shadow-cljs, so it is ignored"]
               (:warnings s))))
      (is (= [] (:warnings (shadow "{:cljs {:shadow-build :app}}")))))
    (testing "a :runtime :attach override in a non-shadow project also ignores it"
      (let [s (sel "{:dialect :cljs :runtime :attach :cljs {:target :browser}}")]
        (is (not (contains? s :cljs-target)))
        (is (= 1 (count (:warnings s))))))
    (testing "other dialects get no :cljs-target"
      (is (not (contains? (dialect/select all5 (proj #{"deps.edn"} "{:cljs {:target :browser}}") "/p/a.clj" nil) :cljs-target))))))

(deftest cljs-target-key-label-reset-test
  (let [sel (fn [cfg] (dialect/select all5 (proj #{"deps.edn"} cfg) "/p/a.cljs" nil))
        node (sel nil)
        browser (sel "{:cljs {:target :browser}}")]
    (testing "switching target builds a new evaluator"
      (is (not= (dialect/selection-key node) (dialect/selection-key browser)))
      (is (= (dialect/selection-key node) (dialect/selection-key (sel "{:cljs {:target :node}}")))))
    (testing "label names the browser"
      (is (= "ClojureScript (browser) (running)" (dialect/label browser :attached)))
      (is (= "ClojureScript (browser) (not running)" (dialect/label browser nil)))
      (is (= "ClojureScript (node) (running)" (dialect/label node :attached))))
    (testing "Reset starts unticked, like node"
      (is (= (dialect/reset-toggle {} node true) (dialect/reset-toggle {} browser true))))))
