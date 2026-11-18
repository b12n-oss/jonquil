(ns jonquil.mcp.changed-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.changed :as changed]
            [jonquil.change-sets :as change-sets]
            [jonquil.mcp.changed :as mc]))

(def ^:private last-run "(ns a)\n(defn f [] 1)\n(def x 2)")
(def ^:private now "(ns a)\n(defn f [] 2)\n(def x 2)\n(defn g [] 3)")

(defn- forms-of [text last-run]
  (mc/changed-forms text last-run (changed/record nil last-run :run)))

(deftest changed-answer-ok-test
  (let [forms (forms-of now last-run)
        {:keys [error? data]} (mc/changed-answer forms {:never-ran-text mc/never-ran-headless})]
    (is (false? error?))
    (is (= {"status" "ok"
            "forms" [{"name" "f" "line" 2 "end_line" 2 "text" "(defn f [] 2)" "before" "(defn f [] 1)"}
                     {"name" "g" "line" 4 "end_line" 4 "text" "(defn g [] 3)" "before" nil}]}
           data))))

(deftest lines-of-a-multi-line-form-test
  (let [text "(ns a)\n\n(defn f []\n  2)\n"
        [f] (mc/changed-forms text "(ns a)\n\n(defn f []\n  1)\n" (changed/record nil "(ns a)\n\n(defn f []\n  1)\n" :run))]
    (is (= [3 4] [(:line f) (:end-line f)]))))

(deftest never-ran-test
  (is (nil? (mc/changed-forms now nil nil)))
  (is (= {:text mc/never-ran-headless :error? false :data {"status" "never-ran"}}
         (mc/changed-answer nil {:never-ran-text mc/never-ran-headless})))
  (is (= "Run the file once first (jonquil_run_file)." mc/never-ran-headless))
  (is (= "Run the tab once first (jonquil_run)." mc/never-ran-window)))

(deftest reformat-is-no-change-test
  (let [text "(ns a)\n(defn f\n  []\n  1) ; one\n(def x 2)"
        forms (forms-of text last-run)]
    (is (= [] forms))
    (is (= {:text "Nothing has changed since the last run." :error? false :data {"status" "no-changes"}}
           (mc/changed-answer forms {:never-ran-text mc/never-ran-headless})))))

(deftest comment-block-not-listed-test
  (is (= [] (forms-of (str last-run "\n(comment (f) (g))") last-run))))

(deftest names-test
  (let [base "(ns a)"
        names (fn [text] (mapv :name (forms-of (str base "\n" text) base)))]
    (is (= ["h"] (names "(defn- h [] 1)")))
    (is (= ["h"] (names "(defn ^:private h [] 1)")))
    (is (= [nil] (names "(println 1)")))
    (is (= ["defmacro" "h"] (changed/ident (:key (first (changed/forms "(defmacro h [] 1)")))))
        "ident is public")))

(deftest text-shows-a-diff-test
  (let [forms (forms-of now last-run)
        text (:text (mc/changed-answer forms {:never-ran-text mc/never-ran-headless}))
        f (first forms)]
    (is (str/includes? text "f"))
    (is (str/includes? text "line 2"))
    (is (str/includes? text (change-sets/entry-diff f)))
    (testing "a new form says so"
      (is (str/includes? text "g (new)")))))

(deftest run-code-test
  (is (= "(defn f [] 2)\n(defn g [] 3)" (mc/run-code (forms-of now last-run)))))

(deftest sent-line-plural-test
  (is (= "; Running 1 changed form" (mc/sent-line 1)))
  (is (= "; Running 2 changed forms" (mc/sent-line 2))))

(deftest defs-test
  (is (= ["jonquil_changed" "jonquil_run_changed"] (mapv #(get % "name") [mc/changed-def mc/run-changed-def])))
  (is (= ["path"] (get-in mc/changed-def ["inputSchema" "required"])))
  (is (= #{"path" "timeout_ms" "max_output_chars"} (set (keys (get-in mc/run-changed-def ["inputSchema" "properties"])))))
  (is (= #{"id"} (set (keys (get-in mc/window-changed-def ["inputSchema" "properties"])))))
  (is (= #{"id" "timeout_ms" "max_output_chars"} (set (keys (get-in mc/window-run-changed-def ["inputSchema" "properties"])))))
  (is (= [] (get-in mc/window-changed-def ["inputSchema" "required"]))))
