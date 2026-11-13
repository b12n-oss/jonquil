(ns jonquil.metrics-gate-test
  "ci/metrics-gate.clj, run as jolt metrics:gate runs it, on hand-made
  jolt-metrics reports."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jolt.process :as p]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(def ^:private allow
  {:critical-free ["src/" "test/"] :unused-requires [] :unused-private [] :unused-public []})

(def ^:private clean {:queue [] :errors [] :cleanup {}})

(defn- gate
  "Runs the gate on report and allowlist a: {:exit :out}."
  [report a]
  (let [dir (temp-dirs/temp-dir! "jonquil-metrics-gate-test-")
        r (str dir "/report.edn")
        al (str dir "/allow.edn")]
    (spit r (pr-str report))
    (spit al (pr-str a))
    (select-keys (p/sh "jolt" "ci/metrics-gate.clj" r al) [:exit :out])))

(defn- critical [path]
  {:path path :name "f" :line 3 :kind :function :severity :critical
   :violations [{:rule :cognitive :value 40 :limit 30 :band :critical}]})

(deftest a-clean-report-passes-test
  (let [{:keys [exit out]} (gate clean allow)]
    (is (zero? exit))
    (is (str/includes? out "metrics gate: no critical findings under src/, test/"))))

(deftest criticals-fail-only-under-gated-paths-test
  (testing "src/ and test/ fail, naming the place and the rule"
    (let [{:keys [exit out]} (gate (assoc clean :queue [(critical "src/jonquil/a.clj") (critical "test/jonquil/b_test.clj")]) allow)]
      (is (= 1 exit))
      (is (str/includes? out "critical function src/jonquil/a.clj:3 f: cognitive 40 > 30"))
      (is (str/includes? out "critical function test/jonquil/b_test.clj:3"))))
  (testing "dev/ isn't gated yet"
    (is (zero? (:exit (gate (assoc clean :queue [(critical "dev/fake_jvm_nrepl.clj")]) allow))))))

(deftest an-unreadable-file-fails-test
  (let [{:keys [exit out]} (gate (assoc clean :errors [{:path "test/jonquil/x_test.clj" :error "EOF while reading"}]) allow)]
    (is (= 1 exit))
    (is (str/includes? out "unreadable test/jonquil/x_test.clj: EOF while reading"))))

(deftest unused-code-must-be-listed-with-a-reason-test
  (let [found (assoc clean :cleanup {:unused-public [{:path "src/jonquil/eval.clj" :name "format-value"}]})]
    (testing "unlisted: fails"
      (let [{:keys [exit out]} (gate found allow)]
        (is (= 1 exit))
        (is (str/includes? out "new unused-public: src/jonquil/eval.clj format-value"))))
    (testing "listed with a reason: passes"
      (is (zero? (:exit (gate found (assoc allow :unused-public [{:path "src/jonquil/eval.clj" :name "format-value" :why "x"}]))))))
    (testing "listed without a reason: fails"
      (let [{:keys [exit out]} (gate found (assoc allow :unused-public [{:path "src/jonquil/eval.clj" :name "format-value"}]))]
        (is (= 1 exit))
        (is (str/includes? out "has no :why"))))))

(deftest a-stale-entry-fails-and-libs-compare-as-text-test
  (testing "listed but no longer reported"
    (let [{:keys [exit out]} (gate clean (assoc allow :unused-requires [{:path "src/a.clj" :lib "clojure.set" :why "x"}]))]
      (is (= 1 exit))
      (is (str/includes? out "stale unused-requires entry src/a.clj clojure.set"))))
  (testing "a lib written as a symbol in the allowlist matches the report's string"
    (is (zero? (:exit (gate (assoc clean :cleanup {:unused-requires [{:path "src/a.clj" :lib "clojure.set" :refers ["union"]}]})
                            (assoc allow :unused-requires [{:path "src/a.clj" :lib 'clojure.set :why "x"}])))))))

(deftest a-misconfigured-gate-fails-instead-of-passing-test
  (testing "no :critical-free (a typo) gates nothing, so it fails"
    (let [{:keys [exit out]} (gate (assoc clean :queue [(critical "src/jonquil/a.clj")]) {:critical-fre ["src/"]})]
      (is (= 1 exit))
      (is (str/includes? out ":critical-free"))))
  (testing "a report without the sections the gate reads fails"
    (let [{:keys [exit out]} (gate {} allow)]
      (is (= 1 exit))
      (is (str/includes? out "isn't a jolt-metrics report")))))
