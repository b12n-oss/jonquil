(ns jonquil.plugin-mcp-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [jonquil.plugin-history :as history]
            [jonquil.plugin-loader :as loader]
            [jonquil.plugin-mcp :as pm]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(def ^:private now (constantly "T"))

(defn- accepted! [d id text]
  (history/record! {:plugins-dir d :id id :text text :source :user :reason "edited by hand" :now now}))

(defn- propose! [d id text & {:as more}]
  (history/record-proposal! (merge {:plugins-dir d :id id :text text :reason "r" :status :pending
                                    :check {:unchecked? true} :now now}
                                   more)))

(defn- status! [d id n status & [note]]
  (history/record-status! {:plugins-dir d :id id :n n :status status :note note :now now}))

(defn- dir [] (temp-dirs/temp-dir! "jonquil-pmcp-"))

(defn- state [d & {:as more}]
  (merge {:plugins? true :plugins-dir d :registry {:plugins {}} :disabled #{}} more))

(deftest plugin-id-test
  (is (loader/plugin-id? "fmt-2"))
  (doseq [x ["Fmt" "../x" "" nil :fmt]]
    (is (not (loader/plugin-id? x)) (pr-str x))))

(deftest propose-refusal-nul-test
  ;; the review pane shows source through GTK, which cuts
  ;; at a NUL, while Jolt's reader reads past one inside a string, so a
  ;; proposal could hide code from the person reviewing it. Refused at the
  ;; gate, naming the line.
  (let [ok {:id "fmt" :source "(ns x)" :reason "faster" :plugins? true}]
    (is (= "the source holds a NUL byte on line 2, which the review pane can't show"
           (pm/propose-refusal (assoc ok :source "(ns x)\n(def a \"y\u0000\")"))))
    (is (= "the source holds a NUL byte on line 1, which the review pane can't show"
           (pm/propose-refusal (assoc ok :source "\u0000"))))
    ;; the reason is agent text shown at the review gate
    ;; through GTK labels, which are C strings too
    (is (= "the reason holds a NUL byte on line 1, which the review pane can't show"
           (pm/propose-refusal (assoc ok :reason "fast\u0000er"))))
    (is (nil? (pm/propose-refusal ok)))))

(deftest propose-refusal-test
  (let [ok {:id "fmt" :source "(ns x)" :reason "faster" :plugins? true}]
    (is (nil? (pm/propose-refusal ok)))
    (is (= "plugins are off in this window (--no-plugins), so it takes no proposals"
           (pm/propose-refusal (assoc ok :plugins? false :id "Bad"))))
    (is (= "Bad isn't a plugin id: lowercase letters, digits and dashes, starting with a letter"
           (pm/propose-refusal (assoc ok :id "Bad" :source nil))))
    (is (= "a proposal needs its source as text" (pm/propose-refusal (assoc ok :source nil :reason ""))))
    (is (= "a proposal needs a reason" (pm/propose-refusal (assoc ok :reason "  "))))
    (is (= "the source is 262145 characters, more than the 262144 a proposal may have"
           (pm/propose-refusal (assoc ok :source (apply str (repeat 262145 "x"))))))
    (is (nil? (pm/propose-refusal (assoc ok :source (apply str (repeat 262144 "x"))))))
    ;; the reason reaches the history, the console and the panel
    ;; too, and a refused id is echoed back
    (is (= "the reason is 4001 characters, more than the 4000 a proposal may have"
           (pm/propose-refusal (assoc ok :reason (apply str (repeat 4001 "r"))))))
    (is (nil? (pm/propose-refusal (assoc ok :reason (apply str (repeat 4000 "r"))))))
    (is (= (str (apply str (repeat 40 "X")) "… isn't a plugin id: lowercase letters, digits and dashes, starting with a letter")
           (pm/propose-refusal (assoc ok :id (apply str (repeat 100000 "X"))))))))

(defn- three-plugins []
  (let [d (dir)]
    (accepted! d "a" "(a1)")
    (propose! d "a" "(a2)" :based-on 1)
    (spit (io/file d "b.clj") "(b)")
    (spit (io/file d "c.clj") "(c)")
    d))

(def ^:private reg-a {:plugins {:a {:source "(a1)" :definition {:doc "Wraps." :effects #{:eval :console}}}}})

(deftest list-text-test
  (let [r (pm/list-text (state (three-plugins) :registry reg-a :disabled #{:c}))]
    (is (= {:error? false
            :text (str/join "\n" ["a: live v1, loaded, file deleted, effects :console :eval, pending v2. Wraps."
                                  "b: no live version, not loaded, effects unknown, no pending proposal."
                                  "c: no live version, disabled, effects unknown, no pending proposal."])}
           r))))

(deftest list-text-empty-test
  (is (= {:text "No plugins. Call jonquil_plugin_propose to propose one." :error? false}
         (pm/list-text (state (dir))))))

(deftest list-text-unreadable-history-test
  (let [d (dir)]
    (propose! d "a" "(a1)")
    (propose! d "b" "(b1)")
    (spit (io/file (history/history-dir d "a") "history.edn") "{:not")
    (is (= {:error? false
            :text (str/join "\n" ["a: history unreadable, not loaded, effects unknown, history unreadable."
                                  "b: no live version, not loaded, effects unknown, pending v1."])}
           (pm/list-text (state d))))))

(defn- histories
  "a: v1 accepted by hand, v2 superseded by v3, v3 rejected with a note."
  []
  (let [d (dir)]
    (accepted! d "a" "(a1)")
    (propose! d "a" "(a2)" :based-on 1)
    (propose! d "a" "(a3)" :based-on 1)
    (status! d "a" 3 :rejected "too slow")
    d))

(deftest read-text-live-test
  (let [d (histories)]
    (is (= {:error? false
            :text (str/join "\n" ["a: live v1" "" "(a1-live)" "" "History:"
                                  "v1 accepted (user, T): edited by hand"
                                  "v2 superseded (agent, T): r"
                                  "v3 rejected (agent, T): r; note: too slow"])}
           (pm/read-text (assoc (state d :registry {:plugins {:a {:source "(a1-live)"}}}) :id "a"))))
    (is (str/starts-with? (:text (pm/read-text (assoc (state d) :id "a"))) "a: live v1\n\n(a1)\n\nHistory:")
        "not loaded: the latest accepted version's source")
    (propose! d "b" "(b1)" :status :rejected-by-checks :problems ["p1" "p2"] :check nil)
    (is (= "b: no live version\n\nHistory:\nv1 rejected-by-checks (agent, T): r; problems: p1; p2"
           (:text (pm/read-text (assoc (state d) :id "b")))))
    (spit (io/file (history/history-dir d "a") "history.edn") "{:not")
    (let [r (pm/read-text (assoc (state d) :id "a"))]
      (is (:error? r))
      (is (str/starts-with? (:text r) "a's history can't be read: ")))))

(deftest read-text-version-test
  (let [d (histories)]
    (is (= {:text "a v2: superseded\n\n(a2)" :error? false} (pm/read-text (assoc (state d) :id "a" :version 2))))
    (is (= {:text "a has no v9. Call jonquil_plugin_read with just the id to see its history." :error? true}
           (pm/read-text (assoc (state d) :id "a" :version 9))))))

(deftest read-text-traversal-test
  (let [seen (atom [])
        real history/read-entries]
    (with-redefs [history/read-entries (fn [x] (swap! seen conj x) (real x))]
      (doseq [id ["../x" "../../etc" "/etc"]]
        (let [r (pm/read-text (assoc (state (dir)) :id id))]
          (is (:error? r))
          (is (str/includes? (:text r) "isn't a plugin id")))
        (is (:error? (pm/status-text (assoc (state (dir)) :id id))))))
    (is (= [] @seen))))

(deftest read-text-no-plugin-test
  (is (= {:text "There's no plugin zz. Call jonquil_plugins_list to see them." :error? true}
         (pm/read-text (assoc (state (dir)) :id "zz")))))

(defn- status-of [d id & [n]] (pm/status-text (assoc (state d) :id id :n n)))

(deftest status-text-test
  (let [d (histories)]
    (is (= {:error? false
            :text "a v3: rejected. The user's note: too slow Call jonquil_plugin_propose with a new version to try again."}
           (status-of d "a")))
    (is (= "a v2: superseded. A newer proposal replaced it." (:text (status-of d "a" 2))))
    (is (= "a v1: accepted." (:text (status-of d "a" 1))))
    (is (= {:text "a has no v9." :error? true} (status-of d "a" 9)))
    (accepted! d "a" "(by hand)")
    (is (str/starts-with? (:text (status-of d "a")) "a v3: rejected.") "a later hand edit isn't a proposal")
    (propose! d "b" "(b1)")
    (is (= "b v1: pending. The user decides in the window. Call jonquil_plugin_status again later."
           (:text (status-of d "b"))))
    (status! d "b" 1 :rejected)
    (is (= "b v1: rejected. The user left no note. Call jonquil_plugin_propose with a new version to try again."
           (:text (status-of d "b"))))
    (propose! d "c" "(c1)" :status :rejected-by-checks :problems ["p1" "p2"] :check nil)
    (is (= "c v1: rejected-by-checks. Problems: p1; p2. Fix them and call jonquil_plugin_propose again."
           (:text (status-of d "c"))))
    (accepted! d "e" "(e1)")
    (is (= {:text "e has no proposals. Call jonquil_plugin_propose to make one." :error? true}
           (status-of d "e")))))

(deftest propose-text-test
  (is (= {:error? true
          :text "status: rejected-by-checks\nproposal: v5\nproblems:\n- p1\ncheck log: /t/a b/check.log\nFix the problems and call jonquil_plugin_propose again with id fmt."}
         (pm/propose-text "fmt" {:status :rejected-by-checks :n 5 :problems ["p1"] :log "/t/a b/check.log"})))
  (is (= {:error? false
          :text "status: pending\nproposal: v4\nThe user reviews it in the window. Call jonquil_plugin_status with id fmt and n 4 to see what they decide."}
         (pm/propose-text "fmt" {:status :pending :n 4 :problems [] :lines ["x"]})))
  (is (= {:error? true
          :text "status: rejected-by-checks\nproposal: v5\nproblems:\n- p1\n- p2\nFix the problems and call jonquil_plugin_propose again with id fmt."}
         (pm/propose-text "fmt" {:status :rejected-by-checks :n 5 :problems ["p1" "p2"]})))
  (is (= {:error? true
          :text "status: refused\nproblems:\n- a proposal needs a reason\nFix that and call jonquil_plugin_propose again."}
         (pm/propose-text "fmt" {:status :refused :n nil :problems ["a proposal needs a reason"]})))
  (is (= {:error? true
          :text "status: unknown\nCall jonquil_plugin_status with id fmt to see whether it was recorded."}
         (pm/propose-text "fmt" {:status :unknown :n nil :problems [] :lines ["didn't finish"]}))))

(deftest status-text-shows-a-kept-log-test
  (let [d (dir)
        log (io/file (temp-dirs/temp-dir! "jonquil-pmcp-log-") "check.log")]
    (spit log "out")
    (propose! d "c" "(c1)" :status :rejected-by-checks :problems ["p"] :check-log (.getPath log))
    (is (= (str "c v1: rejected-by-checks. Problems: p. The check's log: " (.getPath log)
                ". Fix them and call jonquil_plugin_propose again.")
           (:text (status-of d "c"))))
    (.delete log)
    (is (= "c v1: rejected-by-checks. Problems: p. Fix them and call jonquil_plugin_propose again."
           (:text (status-of d "c"))))))
