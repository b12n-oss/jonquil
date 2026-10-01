(ns jonquil.nrepl.connection-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.dialect]
            [jonquil.evaluator :as ev]
            [jonquil.nrepl.attach]
            [jonquil.nrepl.connection :as connection]
            [jonquil.session]))

(deftest parse-port-test
  (is (= 61151 (connection/parse-port "61151\n")) "a port file holds the port and a newline")
  (is (= 61151 (connection/parse-port "  61151  ")))
  (doseq [bad ["" "\n" "abc" "0" "-1" "70000" "61151 61152" nil]]
    (is (nil? (connection/parse-port bad)) (pr-str bad))))

(deftest attached-protocol-test
  (let [sel {:profile jonquil.dialect/cljd :runtime :attach :project-dir "/nowhere"}
        e (ev/make sel)]
    (is (satisfies? connection/Attached e))
    (is (false? (connection/connected? e)))
    (is (not (satisfies? connection/Attached (ev/make {:runtime :in-process}))))))

(deftest make-attached-unknown-dialect-test
  (let [ex (try (ev/make {:profile {:id :nope} :runtime :attach}) nil
                (catch Exception e e))]
    (is (= "No attach evaluator for dialect :nope" (ex-message ex)))
    (is (= {:dialect :nope} (ex-data ex)))))

(deftest message-text-test
  ;; ruling 6: a message is a string or a fn of its argument
  (is (= "plain" (#'connection/message-text {:k "plain"} :k 42)))
  (is (= "port 42" (#'connection/message-text {:k (fn [p] (str "port " p))} :k 42))))
