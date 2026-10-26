(ns jonquil.plugin-isolated-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [jonquil.plugin-checker :as checker]
            [jonquil.plugin-isolated :as isolated]
            [jonquil.plugin-loader :as loader]
            [jonquil.plugin-registry :as reg]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(deftest request-text-round-trips-test
  (let [req (#'loader/request-for (reg/empty-registry) "cw" "Linux")]
    (is (= {:request req :file-id "cw" :load-ms 10000 :soak-ms 2000}
           (edn/read-string (isolated/request-text {:request req :file-id "cw"}))))))

(deftest scrub-env-test
  (is (= #{"PATH" "HOME" "JAVA_HOME" "KEYBOARD"}
         (set (keys (isolated/scrub-env {"PATH" "p" "HOME" "h" "GITHUB_TOKEN" "t"
                                         "AWS_SECRET_ACCESS_KEY" "s" "OPENAI_API_KEY" "k"
                                         "my_password" "x" "SSH_AUTH_SOCK" "a"
                                         "JAVA_HOME" "j" "KEYBOARD" "kb"})))))
  (is (= {"PATH" "/bin"}
         (isolated/scrub-env {"PATH" "/bin" "AWS_ACCESS_KEY_ID" "a" "GITHUB_PAT" "b" "DB_PASS" "c"
                              "OPENAI_APIKEY" "d" "GPG_PASSPHRASE" "e" "MY_PRIVATE_THING" "f"})))
  (is (contains? (isolated/scrub-env {"PATHS" "x"}) "PATHS") "_PAT is a suffix, not a substring"))

(def ^:private good {:ok? true :problems [] :warnings ["w"] :timings {:cw/wrap 3}
                     :plugin {:id :cw :doc "d" :effects #{} :commands [] :bindings [] :hooks []}})

(defn- parse [out exit & {:as more}]
  (isolated/parse-result (merge {:out out :exit exit} more)))

(deftest parse-good-test
  (is (= (assoc good :definition nil) (parse (str "junk\n" (checker/result-text good)) 0))))

(deftest parse-failed-test
  (let [r (parse (checker/result-text {:ok? false :problems ["p"] :warnings [] :timings {}}) 0)]
    (is (false? (:ok? r)))
    (is (= ["p"] (:problems r)))))

(deftest parse-last-pair-wins-test
  (let [r (parse (str (checker/result-text {:ok? true})
                      (checker/result-text {:ok? false :problems ["real"] :warnings [] :timings {}}))
                 0)]
    (is (= ["real"] (:problems r)))))

(defn- one-problem [r] (is (false? (:ok? r))) (is (= 1 (count (:problems r))) (pr-str r)) (first (:problems r)))

(deftest parse-garbage-test
  (doseq [body ["{:ok?" "[1 2]" "{:ok? \"yes\"}" "{:ok? false :problems [1]}"]]
    (is (str/starts-with? (one-problem (parse (str "\n__JONQUIL_CHECK__\n" body "\n__END_CHECK__\n") 0))
                          "the check printed a result that isn't one: ")
        body)))

(deftest parse-missing-test
  (is (= "the check printed no result (exit 1): Exception in thread main\nboom"
         (one-problem (parse "Exception in thread main\nboom\n" 1))))
  (is (= "the check printed no result (exit 137): " (one-problem (parse nil 137)))))

(deftest parse-overrun-test
  (is (= "the check didn't finish in 30 s, so it was stopped"
         (one-problem (parse (checker/result-text good) 0 :timed-out? true)))))

(deftest parse-nonzero-after-result-test
  (is (= "the check exited with 3 after printing its result"
         (one-problem (parse (checker/result-text good) 3)))))

(deftest parse-rejects-a-half-shaped-result-test
  ;; review I2: a pass must carry no problems and a whole :plugin summary;
  ;; a fail carries no :plugin; :warnings and :timings have their shapes
  (doseq [v [{:ok? true :problems ["x"] :warnings [] :timings {} :plugin (:plugin good)}
             {:ok? true :problems [] :warnings [] :timings {} :plugin {:id :cw :effects #{}}}
             {:ok? true :problems [] :warnings [] :timings {}}
             (assoc good :warnings "w")
             (assoc good :warnings [1])
             (assoc good :timings [1])
             (assoc-in good [:plugin :effects] [:console])
             {:ok? false :problems ["p"] :warnings [] :timings {} :plugin (:plugin good)}]]
    (is (str/starts-with? (one-problem (parse (checker/result-text v) 0))
                          "the check printed a result that isn't one: ")
        (pr-str v))))

(deftest parse-overrun-names-the-deadline-test
  (is (= ["the check didn't finish in 3 s, so it was stopped"]
         (:problems (isolated/parse-result {:out "" :exit 143 :timed-out? true :deadline-ms 3000})))))

(deftest read-output-test
  (let [d (temp-dirs/temp-dir! "jonquil-isolated-")
        f (io/file d "check.log")]
    (spit f "short")
    (is (= "short" (isolated/read-output (.getPath f))))
    (spit f (str (apply str (repeat isolated/OUTPUT-MAX-BYTES "x")) "TAIL"))
    (let [s (isolated/read-output (.getPath f))]
      (is (= isolated/OUTPUT-MAX-BYTES (count s)))
      (is (str/ends-with? s "TAIL")))
    (is (= "" (isolated/read-output (.getPath (io/file d "missing.log")))))))

(defn- check-folders [base]
  (filter #(str/starts-with? % "jonquil-plugin-check-") (.list (io/file base))))

(deftest check-without-jolt-is-unchecked-test
  (let [tmp (temp-dirs/temp-dir! "jonquil-isolated-")]
    (with-redefs [isolated/jolt-program (constantly nil)]
      (let [r (isolated/check! {:source "(ns x)" :file-id "x" :request {} :temp-base tmp})]
        (is (false? (:ok? r)))
        (is (true? (:unchecked? r)))
        (is (= ["there's no jolt to check it in, so it wasn't checked"] (:problems r)))
        (is (empty? (check-folders tmp)) "nothing was written")))))

(deftest check-with-a-missing-program-fails-test
  (let [tmp (temp-dirs/temp-dir! "jonquil-isolated-")
        r (isolated/check! {:source "(ns x)" :file-id "x" :request {} :program "/nonexistent/jolt"
                            :cache-base (temp-dirs/temp-dir! "jonquil-isolated-cache-")
                            :temp-base tmp})]
    (is (false? (:ok? r)))
    (is (nil? (:unchecked? r)))
    (is (str/starts-with? (first (:problems r)) "couldn't start jolt: ") (pr-str r))
    (is (empty? (check-folders tmp)) "the folder was deleted")))

(deftest check-with-an-unwritable-cache-fails-test
  (let [tmp (temp-dirs/temp-dir! "jonquil-isolated-")
        blocker (io/file tmp "a-file")
        _ (spit blocker "")
        r (isolated/check! {:source "(ns x)" :file-id "x" :request {} :program "/bin/sh"
                            :cache-base (.getPath (io/file blocker "under")) :temp-base tmp})]
    (is (str/starts-with? (first (:problems r)) "the checker's files couldn't be written: ") (pr-str r))
    (is (empty? (check-folders tmp)))))

(deftest check-with-an-unusable-temp-base-fails-test
  (let [tmp (temp-dirs/temp-dir! "jonquil-isolated-")
        blocker (io/file tmp "a-file")
        _ (spit blocker "")
        r (isolated/check! {:source "(ns x)" :file-id "x" :request {} :program "/bin/sh"
                            :cache-base (temp-dirs/temp-dir! "jonquil-isolated-cache-")
                            :temp-base (.getPath blocker)})]
    (is (false? (:ok? r)))
    (is (str/starts-with? (first (:problems r)) "the check's folder couldn't be made: ") (pr-str r))))
