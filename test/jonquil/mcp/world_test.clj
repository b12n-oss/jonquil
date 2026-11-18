(ns jonquil.mcp.world-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jonquil.mcp.world :as mw]))

(def ^:private id "0001794978573311")
(def ^:private path (str "/w/" id ".world"))

(deftest save-ok-test
  (is (= {:text (str "Saved 3 vars as world " id ".") :error? false
          :data {"status" "ok" "id" id "saved" 3 "placeholders" []}}
         (mw/save-answer {:saved 3 :placeholders [] :path path}))))

(deftest save-placeholders-test
  (let [r (mw/save-answer {:saved 2 :path path :placeholders [{:var "user/f" :kind "file"}]})]
    (is (= [{"var" "user/f" "kind" "file"}] (get-in r [:data "placeholders"])))
    (is (str/includes? (:text r) "user/f (file)"))
    (is (false? (:error? r)))))

(deftest save-unwritable-test
  (let [r (mw/save-answer {:error "nothing was saved: a var holds what can't be written"
                           :unwritable [{:var "user/conn" :kind "socket"}]})]
    (is (true? (:error? r)))
    (is (= "error" (get-in r [:data "status"])))
    (is (= [{"var" "user/conn" "kind" "socket"}] (get-in r [:data "unwritable"])))
    (is (str/includes? (:text r) "user/conn (socket)"))))

(deftest restore-ok-test
  (let [r (mw/restore-answer {:first {:saved 1 :placeholders [] :path "/w/0001794978600000.world"}
                              :restore {:restored 4 :placeholders []}})]
    (is (= {"status" "ok" "restored" 4 "placeholders" [] "undo_id" "0001794978600000"} (:data r)))
    (is (false? (:error? r)))
    (is (str/includes? (:text r) "Restored 4 vars."))
    (is (str/includes? (:text r) "0001794978600000"))))

(deftest restore-placeholders-test
  (let [r (mw/restore-answer {:first {:saved 1 :placeholders [] :path path}
                              :restore {:restored 2 :placeholders [{:var "user/f" :description "an open file"}]}})]
    (is (= [{"var" "user/f" "description" "an open file"}] (get-in r [:data "placeholders"])))
    (is (str/includes? (:text r) "user/f: an open file"))))

(deftest restore-first-save-failed-test
  (let [r (mw/restore-answer {:first {:error "can't write it" :unwritable [{:var "user/s" :kind "socket"}]}})]
    (is (true? (:error? r)))
    (is (= "error" (get-in r [:data "status"])))
    (is (str/starts-with? (:text r) "Nothing was restored"))
    (is (= [{"var" "user/s" "kind" "socket"}] (get-in r [:data "unwritable"])))))

(deftest restore-gone-and-error-test
  (is (= "unknown-id" (get-in (mw/restore-answer {:first {:saved 0 :placeholders [] :path path}
                                                  :restore {:missing true}})
                              [:data "status"])))
  (let [r (mw/restore-answer {:first {:saved 0 :placeholders [] :path path} :restore {:error "boom"}})]
    (is (true? (:error? r)))
    (is (= {"status" "error" "error" "boom" "undo_id" id} (:data r)))))

(deftest refusals-test
  (is (= {:text "No world saved by an agent for this project yet: jonquil_world_save makes one."
          :error? false :data {"status" "no-world"}}
         mw/no-world-answer))
  (is (= {"status" "unknown-id"} (:data (mw/unknown-id-answer "x"))))
  (is (true? (:error? (mw/unknown-id-answer "x"))))
  (is (= {"status" "unsupported"} (:data (mw/unsupported-answer {:profile {:name "JVM Clojure"}}))))
  (is (= "timeout" (get-in (mw/timeout-answer 500) [:data "status"]))))

(deftest defs-test
  (is (= ["jonquil_world_save" "jonquil_world_restore"] (map #(get % "name") [mw/save-def mw/restore-def])))
  (is (= #{"path" "timeout_ms"} (set (keys (get-in mw/save-def ["inputSchema" "properties"])))))
  (is (= #{"path" "id" "timeout_ms"} (set (keys (get-in mw/restore-def ["inputSchema" "properties"])))))
  (is (= #{"timeout_ms"} (set (keys (get-in mw/window-save-def ["inputSchema" "properties"])))))
  (is (= #{"id" "timeout_ms"} (set (keys (get-in mw/window-restore-def ["inputSchema" "properties"]))))))

(deftest timeout-answers-say-it-may-still-finish-test
  ;; review #3
  (is (str/includes? (:text (mw/timeout-answer 500)) "may still finish"))
  (let [r (mw/timeout-answer 500 "/w/0000000000009.world")]
    (is (= "0000000000009" (get-in r [:data "undo_id"])))
    (is (str/includes? (:text r) "0000000000009"))))

(deftest restore-target-reads-any-id-test
  ;; review #4: a number, and blank as none
  (let [es [{:path "/w/1795001234567.world" :kind :agent}]]
    (is (= "/w/1795001234567.world" (:path (mw/restore-target es 1795001234567))))
    (is (= "/w/1795001234567.world" (:path (mw/restore-target es " "))) "blank reads as no id")))
