(ns jonquil.nrepl.connection-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [fake-jank-nrepl :as fake-jank]
            [jonquil.dialect]
            [jonquil.evaluator :as ev]
            [jonquil.jank :as jank]
            [jonquil.nrepl.attach :as attach]
            [jonquil.nrepl.connection :as connection]
            [jonquil.session]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

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
  ;; a message is a string or a fn of its argument
  (is (= "plain" (#'connection/message-text {:k "plain"} :k 42)))
  (is (= "port 42" (#'connection/message-text {:k (fn [p] (str "port " p))} :k 42))))

(deftest connect-port-file-policy-test
  ;; The selection's project dir has no port file; the policy's :port-file
  ;; points at a temp file holding the fake server's port, so connect! reads
  ;; that one (jank's port file lives in a run dir, not the project).
  (let [dir (temp-dirs/temp-dir! "jonquil-connect-port-file-")
        f (java.io.File. (str dir "/.nrepl-port"))
        srv (fake-jank/start!)
        e (attach/make-attach {:profile jonquil.dialect/cljd :runtime :attach :project-dir "/nowhere"}
                              {:describe-timeout-ms 2000 :clone-timeout-ms 2000})]
    (try
      (spit f (str (:port srv) "\n"))
      (let [without (connection/connect! e nil jank/connect-policy)]
        (is (= :notice (:status without)) "no :port-file: the selection's project dir is used")
        (is (false? (connection/connected? e))))
      (is (nil? (connection/connect! e nil (assoc jank/connect-policy :port-file (fn [_] f))))
          "connects through the policy's port file")
      (is (true? (connection/connected? e)))
      (finally
        (connection/detach! e)
        (fake-jank/stop! srv)))))
