(ns jonquil.mcp.tools-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [jonquil.dialect :as dialect]
            [jonquil.evaluator :as ev]
            [jonquil.file-tree :as ft]
            [jonquil.mcp.tools :as tools]
            [jonquil.runtime-registry :as registry]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

;; Made on first use, not at load: temp-dirs' fixture deletes every recorded
;; directory when any namespace's tests end, and all test namespaces load first.
(def ^:private env
  (delay
    (let [dir (temp-dirs/temp-dir! "jonquil-mcp-test-")
          settings-path (str (io/file (temp-dirs/temp-dir! "jonquil-mcp-test-") "settings.edn"))]
      (spit (io/file dir "deps.edn") "{}")
      ;; In process, so bb test starts no Jolt child (a deps.edn project
      ;; otherwise runs out of process; bb mcp:smoke covers that path).
      (spit (io/file dir ".jonquil.edn") "{:runtime :in-process}")
      {:dir dir
       :t (tools/make-tools {:dir dir :settings-path settings-path})})))

(defn- dir [] (:dir @env))

(defn- call [name args] ((:call (:t @env)) name args))

(deftest defs-name-six-tools
  (is (= #{"jonquil_eval" "jonquil_run_file" "jonquil_check_brackets"
           "jonquil_doc" "jonquil_complete" "jonquil_project"}
         (set (map #(get % "name") (:defs (:t @env))))))
  (is (every? #(vector? (get-in % ["inputSchema" "required"])) (:defs (:t @env)))))

(deftest eval-value-and-output
  (is (= {:text "hi\n=> 3" :error? false}
         (call "jonquil_eval" {"code" "(println \"hi\") (+ 1 2)"}))))

(deftest eval-error-is-error
  (let [r (call "jonquil_eval" {"code" "(/ 1 0)"})]
    (is (:error? r))
    (is (str/includes? (:text r) "Divide by zero"))))

(deftest eval-keeps-state
  (call "jonquil_eval" {"code" "(def x 41)"})
  (is (str/ends-with? (:text (call "jonquil_eval" {"code" "(inc x)"})) "=> 42")))

(deftest eval-timeout-interrupts
  (let [t0 (System/currentTimeMillis)
        r (call "jonquil_eval" {"code" "(loop [] (recur))" "timeout_ms" 300})]
    (is (< (- (System/currentTimeMillis) t0) 6000))
    (is (:error? r))
    (is (str/includes? (:text r) "300"))
    (is (= "=> 2" (:text (call "jonquil_eval" {"code" "(+ 1 1)"}))))))

(deftest eval-print-without-newline
  (is (= "x\n=> 1" (:text (call "jonquil_eval" {"code" "(print \"x\") 1"})))))

(deftest stuck-evaluation-is-evicted
  (let [d (temp-dirs/temp-dir! "jonquil-mcp-test-")
        _ (spit (io/file d "deps.edn") "{}")
        ;; The fresh evaluator after the eviction stays in process too.
        _ (spit (io/file d ".jonquil.edn") "{:runtime :in-process}")
        k (dialect/selection-key (ft/project-selection d nil))
        stopped (atom false)
        fake (reify ev/Evaluator
               (eval! [_ _ _] nil)
               (start! [_] nil)
               (evaluating? [_] true)
               (waiting-for-input? [_] false)
               (send-input! [_ _] nil)
               (send-eof! [_] nil)
               (history [_] nil)
               (prompt-ns [_] 'user)
               (set-mode! [_ _] nil)
               (complete [_ _ _ _ _] [])
               (lookup [_ _ _ _] nil)
               (limitations [_] [])
               (interrupt! [_] nil)
               (stop! [_] (reset! stopped true) nil))
        tt (tools/make-tools {:dir d :cache (atom {k fake}) :grace-ms 200
                              :settings-path (str (io/file d "s.edn"))})
        r ((:call tt) "jonquil_eval" {"code" "(+ 1 1)" "timeout_ms" 100})]
    (is (:error? r))
    (is (str/includes? (:text r) "fresh one will be started"))
    (is @stopped)
    (is (= "=> 2" (:text ((:call tt) "jonquil_eval" {"code" "(+ 1 1)"}))))))

(deftest eval-needs-code
  (is (thrown? Throwable (call "jonquil_eval" {}))))

(deftest run-file
  (let [f (io/file (dir) "a.clj")]
    (spit f "(ns demo.a) (def y 7) y")
    (let [r (call "jonquil_run_file" {"path" (.getPath f)})]
      (is (false? (:error? r)))
      (is (str/ends-with? (:text r) "=> 7")))))

(deftest run-file-missing-or-dir
  (let [missing (str (io/file (dir) "nope.clj"))]
    (is (thrown-with-msg? Throwable (re-pattern (java.util.regex.Pattern/quote missing))
                          (call "jonquil_run_file" {"path" missing})))
    (is (thrown-with-msg? Throwable (re-pattern (java.util.regex.Pattern/quote (dir)))
                          (call "jonquil_run_file" {"path" (dir)})))))

(deftest check-brackets-ok-and-problem
  (is (= {:text "ok" :error? false} (call "jonquil_check_brackets" {"text" "(+ 1 2)"})))
  (let [r (call "jonquil_check_brackets" {"text" "(+ 1\n(2"})]
    (is (false? (:error? r)))
    (is (str/includes? (:text r) "never closed")))
  (let [f (io/file (dir) "b.clj")]
    (spit f "(+ 1 2")
    (is (str/includes? (:text (call "jonquil_check_brackets" {"path" (.getPath f)}))
                       "never closed")))
  (is (thrown? Throwable (call "jonquil_check_brackets" {}))))

(deftest doc-for-map
  (let [r (call "jonquil_doc" {"symbol" "map"})]
    (is (false? (:error? r)))
    (is (str/includes? (:text r) "map"))
    (is (str/includes? (:text r) "[")))
  (let [r (call "jonquil_doc" {"symbol" "no-such-thing-xyz"})]
    (is (false? (:error? r)))
    (is (seq (:text r)))))

(deftest complete-prefix
  (let [r (call "jonquil_complete" {"prefix" "ma"})
        lines (str/split-lines (:text r))]
    (is (false? (:error? r)))
    (is (some #{"map"} lines))
    (is (<= (count lines) 50)))
  (is (= "No completions for zzqqxx." (:text (call "jonquil_complete" {"prefix" "zzqqxx"})))))

(deftest project-info
  (let [p (temp-dirs/temp-dir! "jonquil-mcp-test-")
        before (count @@#'registry/live)]
    (spit (io/file p ".jonquil.edn") "{:runtime :nrepl}")
    (let [r (call "jonquil_project" {"path" p})]
      (is (false? (:error? r)))
      (is (str/includes? (:text r) "nREPL"))
      (is (str/includes? (:text r) (.getCanonicalPath (io/file p))))
      (is (str/includes? (:text r) "Chosen by: fallback")))
    (is (= before (count @@#'registry/live)))))

(deftest unknown-tool-throws-and-nil-args-count-as-empty
  (let [ex (try (call "jonquil_nope" {}) nil (catch Throwable e e))]
    (is (= "Unknown tool: jonquil_nope" (ex-message ex))))
  (is (= "Missing required argument: symbol"
         (ex-message (try ((:call (:t @env)) "jonquil_doc" nil) nil (catch Throwable e e))))))

(deftest eval-path-picks-the-project
  (let [d (temp-dirs/temp-dir! "jonquil-mcp-test-")
        _ (spit (io/file d "deps.edn") "{}")
        _ (spit (io/file d ".jonquil.edn") "{:runtime :in-process}")
        _ (spit (io/file d "a.clj") "(ns a)")
        tt (tools/make-tools {:dir (dir) :settings-path (str (io/file d "s.edn"))})
        c (fn [args] ((:call tt) "jonquil_eval" args))]
    (is (= {:text "=> 3" :error? false} (c {"code" "(+ 1 2)" "path" (str (io/file d "a.clj"))})) "a file picks its project")
    (is (= {:text "=> 3" :error? false} (c {"code" "(+ 1 2)" "path" (str d)})) "a directory picks its project")
    (is (= {:text "=> 3" :error? false} (c {"code" "(+ 1 2)" "path" "a.clj"})) "a relative path resolves against the server dir")))
