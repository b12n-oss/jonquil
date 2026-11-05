(ns jonquil.mcp.describe-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.mcp.describe :as d]))

(defn- call [tool args result & {:as more}]
  (d/describe-call (merge {:tool tool :args args :result result :client "Claude Code"} more)))

(deftest eval-entry
  (let [e (call "jonquil_eval" {"code" "(+ 1 2)"} {:text "3" :error? false})]
    (is (= :agent (:kind e)))
    (is (= "Claude Code evaluated code" (:summary e)))
    (is (nil? (:target e)))
    (is (= "(+ 1 2)\n\n3" (:detail e))))
  (testing "detail is capped"
    (let [e (call "jonquil_eval" {"code" (str/join "\n" (repeat 30 "x"))} {:text "ok" :error? false})]
      (is (str/includes? (:detail e) "… 10 more lines")))))

(deftest tab-tools-carry-target
  (doseq [[tool summary] [["jonquil_run" "Claude Code ran tab 3"]
                          ["jonquil_run_selection" "Claude Code ran a selection of tab 3"]
                          ["jonquil_open" "Claude Code opened tab 3"]
                          ["jonquil_select_tab" "Claude Code switched to tab 3"]
                          ["jonquil_replace_text" "Claude Code replaced the text of tab 3"]
                          ["jonquil_save" "Claude Code saved tab 3"]]]
    (let [e (call tool {} {:text "ok" :error? false :tab-id 3})]
      (is (= :agent (:kind e)) tool)
      (is (= summary (:summary e)) tool)
      (is (= {:tab-id 3} (:target e)) tool)
      (is (= 3 (:summary-tab e)) tool)))
  (testing "no tab id"
    (let [e (call "jonquil_save" {} {:text "No active tab." :error? true})]
      (is (str/starts-with? (:summary e) "Claude Code saved a tab"))
      (is (nil? (:target e)))
      (is (nil? (:summary-tab e))))))

(deftest stop-noop-is-a-read
  (is (= :agent-read (:kind (call "jonquil_stop" {} {:text "Nothing is running." :error? false}))))
  (let [e (call "jonquil_stop" {} {:text "Stopped." :error? false})]
    (is (= :agent (:kind e)))
    (is (= "Claude Code stopped the evaluation" (:summary e)))))

(deftest reads-are-hidden-kind
  (doseq [[tool args summary] [["jonquil_tabs" {} "Claude Code read the tabs"]
                               ["jonquil_read_tab" {} "Claude Code read tab 2"]
                               ["jonquil_console" {} "Claude Code read the console"]
                               ["jonquil_doc" {"symbol" "map"} "Claude Code read the docs for map"]
                               ["jonquil_complete" {"prefix" "ma"} "Claude Code read completions for ma"]
                               ["jonquil_project" {} "Claude Code read the project"]
                               ["jonquil_check_brackets" {"path" "a.clj"} "Claude Code read the brackets of a.clj"]
                               ["jonquil_plugins_list" {} "Claude Code read the plugins"]
                               ["jonquil_plugin_read" {"id" "p1"} "Claude Code read plugin p1"]
                               ["jonquil_plugin_status" {"id" "p1"} "Claude Code read plugin p1's status"]]]
    (let [e (call tool args {:text "x" :error? false :tab-id (when (= tool "jonquil_read_tab") 2)})]
      (is (= :agent-read (:kind e)) tool)
      (is (= summary (:summary e)) tool)))
  (is (= "Claude Code read the brackets of a tab"
         (:summary (call "jonquil_check_brackets" {"text" "(("} {:text "x" :error? false})))))

(deftest propose-is-plugin-kind
  (let [e (call "jonquil_plugin_propose" {"id" "p1"} {:text "proposed" :error? false :plugin-id "p1"})]
    (is (= :plugin (:kind e)))
    (is (= "Claude Code proposed plugin p1" (:summary e)))
    (is (= {:plugin-id "p1"} (:target e)))))

(deftest unknown-tool-still-logs
  (let [e (d/describe-call {:tool "my_tool" :args {"a" 1} :result {:text "ok" :error? false}})]
    (is (= :plugin (:kind e)))
    (is (= "Agent called my_tool" (:summary e)))
    (is (= "{\"a\" 1}" (:detail e)))))

(deftest failure-summary
  (let [e (call "jonquil_save" {} {:text "The save failed; the console names the reason.\nmore" :error? true :tab-id 3})]
    (is (:error? e))
    (is (= "Claude Code saved tab 3, which failed: The save failed; the console names the reason." (:summary e)))))

(deftest throw-summary
  (let [e (call "jonquil_eval" {"code" "x"} nil :threw "boom")]
    (is (:error? e))
    (is (= "Claude Code evaluated code, which failed: boom" (:summary e)))))

(deftest nil-client-is-agent
  (is (= "Agent opened tab 1" (:summary (d/describe-call {:tool "jonquil_open" :args {} :result {:text "x" :tab-id 1}})))))
