(ns jonquil.plugin-checker-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [jonquil.plugin-checker :as checker]
            [jonquil.plugin-isolated :as isolated]
            [jonquil.plugin-loader :as loader]
            [jonquil.plugin-registry :as reg]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(defn- source
  "A plugin file for id: one :edit command, bound to Cmd+Alt+J or to
  command, with top-level forms extra before the defplugin."
  [id & {:keys [command extra] :or {extra ""}}]
  (str "(ns jonquil.plugins." id "\n  (:require [jonquil.plugin :as p]))\n" extra "\n"
       "(p/defplugin\n"
       "  {:id :" id " :doc \"A test plugin.\" :effects #{:console}\n"
       "   :commands [{:id :" id "/wrap :doc \"Wrap it.\" :scope :editor\n"
       "               :edit (fn [s] (p/wrap-form s \"(when \" \")\"))}]\n"
       "   :bindings [{:key \"j\" :mods #{:cmd :alt} :command " (or command (str ":" id "/wrap")) "}]})\n"))

(defn- check-folder
  "A folder holding request.edn for id (with more merged in) and candidate.clj."
  [id text & {:as more}]
  (let [d (temp-dirs/temp-dir! "jonquil-checker-")]
    (spit (io/file d "request.edn")
          (pr-str (merge {:request (#'loader/request-for (reg/empty-registry) id "Linux")
                          :file-id id :load-ms 10000 :soak-ms 2000}
                         more)))
    (spit (io/file d "candidate.clj") text)
    d))

(deftest check-dir-good-test
  (let [r (checker/check-dir (check-folder "cw" (source "cw")))]
    (is (true? (:ok? r)) (pr-str r))
    (is (= :cw (get-in r [:plugin :id])))
    (is (= #{:console} (get-in r [:plugin :effects])))
    (is (= [{:id :cw/wrap :doc "Wrap it." :scope :editor}] (get-in r [:plugin :commands])))
    (is (contains? (:timings r) :cw/wrap))))

(deftest check-dir-bad-test
  (let [r (checker/check-dir (check-folder "cwbad" (source "cwbad" :command ":nope/x")))]
    (is (false? (:ok? r)))
    (is (some #(str/includes? % ":nope/x") (:problems r)) (pr-str r))
    (is (nil? (:plugin r)))))

(deftest check-dir-missing-files-test
  (let [r (checker/check-dir (temp-dirs/temp-dir! "jonquil-checker-"))]
    (is (false? (:ok? r)))
    (is (= 1 (count (:problems r))))
    (is (str/starts-with? (first (:problems r)) "the checker couldn't run: ") (pr-str r))))

(deftest check-dir-uses-the-load-budget-test
  (let [r (checker/check-dir (check-folder "cwslow" (source "cwslow" :extra "(Thread/sleep 3000)")
                                           :load-ms 200))]
    (is (false? (:ok? r)))
    (is (some #(str/includes? % "didn't finish in 200 ms") (:problems r)) (pr-str r))))

(deftest result-text-test
  (is (= "\n__JONQUIL_CHECK__\n{:ok? true}\n__END_CHECK__\n" (checker/result-text {:ok? true}))))

(deftest summary-test
  (let [f (fn [s] s)
        s (checker/summary {:id :sm :doc "d" :effects #{}
                            :commands [{:id :sm/x :doc "x" :scope :editor :edit f}]
                            :bindings [{:key "j" :mods #{:cmd} :command :sm/x}]
                            :hooks {:after-save f}})]
    (is (not-any? fn? (tree-seq coll? seq s)))
    (is (= [:after-save] (:hooks s)))
    (is (= [{:key "j" :mods #{:cmd} :command :sm/x}] (:bindings s))))
  (let [s (checker/summary {:id :sm :doc "d" :effects #{}
                            :mcp-tools [{:name "echo" :doc "Echo it."
                                         :args {"text" {:type "string" :doc "t" :required? true}}
                                         :run (fn [_ a] a)}]})]
    (is (= [{:name "echo" :doc "Echo it." :args {"text" {:type "string" :doc "t" :required? true}}}] (:mcp-tools s)))
    (is (not-any? fn? (tree-seq coll? seq s))))
  (is (not (contains? (checker/summary {:id :sm :doc "d" :effects #{}}) :mcp-tools))
      "no key without tools, so proposals from before P10b still compare equal")
  (is (nil? (checker/summary nil))))

(deftest result-text-has-no-namespaced-maps-test
  (is (str/includes? (checker/result-text {:timings {:lv/w 11}}) "{:lv/w 11}")))

(deftest a-real-result-parses-test
  ;; what check-dir answers passes parse-result's shape check, pass and fail
  (let [good (checker/check-dir (check-folder "cwp" (source "cwp")))
        bad (checker/check-dir (check-folder "cwpbad" (source "cwpbad" :command ":nope/x")))]
    (is (= (assoc good :definition nil)
           (isolated/parse-result {:out (checker/result-text good) :exit 0})))
    (is (= (:problems bad)
           (:problems (isolated/parse-result {:out (checker/result-text bad) :exit 0}))))))
