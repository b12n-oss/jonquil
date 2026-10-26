(ns jonquil.plugin-isolated-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jonquil.plugin-checker :as checker]
            [jonquil.plugin-isolated :as isolated]
            [jonquil.plugin-loader :as loader]
            [jonquil.plugin-registry :as reg]))

(deftest request-text-round-trips-test
  (let [req (#'loader/request-for (reg/empty-registry) "cw" "Linux")]
    (is (= {:request req :file-id "cw" :load-ms 10000 :soak-ms 2000}
           (edn/read-string (isolated/request-text {:request req :file-id "cw"}))))))

(deftest scrub-env-test
  (is (= #{"PATH" "HOME" "JAVA_HOME" "KEYBOARD"}
         (set (keys (isolated/scrub-env {"PATH" "p" "HOME" "h" "GITHUB_TOKEN" "t"
                                         "AWS_SECRET_ACCESS_KEY" "s" "OPENAI_API_KEY" "k"
                                         "my_password" "x" "SSH_AUTH_SOCK" "a"
                                         "JAVA_HOME" "j" "KEYBOARD" "kb"}))))))

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
