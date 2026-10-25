(ns jonquil.plugin-check-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jonquil.command-registry :as registry]
            [jonquil.keymap :as km]
            [jonquil.plugin-check :as pc]))

(def ^:private request
  (pc/request-from-registry {:commands registry/commands
                             :conditions (into (set (keys registry/window-conditions))
                                               (keys registry/editor-conditions))
                             :keymap km/shipped :os "Linux"}))

(def ^:private good
  {:id :wrap-when
   :doc "Wrap the form at the caret in (when ...)."
   :effects #{}
   :commands [{:id :wrap-when/wrap :doc "Wrap the form at the caret in (when ...)."
               :scope :editor :edit (fn [s] s)}]
   ;; the spec's example chord is Cmd+Alt+W, which the window's Cmd+W
   ;; (close the tab) takes first; J is free in both scopes
   :bindings [{:key "j" :mods #{:cmd :alt} :command :wrap-when/wrap}]})

(defn- check
  ([defs] (check defs "wrap-when"))
  ([defs file-id] (pc/validate (assoc request :definitions defs :file-id file-id))))

(defn- one-problem [result]
  (and (false? (:ok? result)) (= 1 (count (:problems result)))))

(defn- with-command [over]
  (assoc good :commands [(merge (first (:commands good)) over)] :bindings []))

(deftest good-plugin-passes-test
  (is (= {:ok? true :problems [] :warnings []} (check [good]))))

(deftest definition-count-test
  (is (one-problem (check [])) "no defplugin")
  (is (one-problem (check [good good])) "two"))

(deftest id-test
  (is (one-problem (check [(assoc good :id :Wrap)] "Wrap")) "file id fails the regex")
  (is (one-problem (check [(assoc good :id :other)])) ":id differs from the file")
  (is (one-problem (check [(assoc good :id "wrap-when")])) ":id is a string")
  (is (one-problem (check [(dissoc good :doc)])) "no :doc"))

(deftest effects-test
  (let [r (check [(dissoc good :effects)])]
    (is (one-problem r))
    (is (str/includes? (first (:problems r)) "#{}")))
  (is (one-problem (check [(assoc good :effects [:console])])) "not a set")
  (is (one-problem (check [(assoc good :effects #{:disk})])) "unknown effect")
  (is (:ok? (check [(assoc good :effects #{})]))))

(deftest commands-test
  (doseq [[why over] [["wrong namespace" {:id :other/wrap}]
                      ["no doc" {:doc nil}]
                      ["console scope" {:scope :console}]
                      ["both" {:run (fn [_ _] :handled)}]
                      ["neither" {:edit nil}]
                      ["edit in window scope" {:scope :window}]]]
    (is (one-problem (check [(with-command over)])) why))
  (is (one-problem (check [(assoc good :commands [(first (:commands good)) (first (:commands good))]
                                  :bindings [])]))
      "duplicate id")
  (let [run-cmd {:id :wrap-when/say :doc "Say hi." :scope :window :run (fn [_ _] nil)}]
    (is (one-problem (check [(assoc good :commands [run-cmd] :bindings [])])) ":run in a pure plugin")
    (is (:ok? (check [(assoc good :effects #{:console} :commands [run-cmd] :bindings [])])))))

(deftest hooks-test
  (is (one-problem (check [(assoc good :hooks {:before-save (fn [_ e] e)})])) "unknown hook")
  (is (one-problem (check [(assoc good :hooks {:after-save "no"})])) "not a fn")
  (is (:ok? (check [(assoc good :hooks {:after-save (fn [_ e] e)})]))))

(deftest unknown-keys-test
  (is (one-problem (check [(assoc good :bindngs [])]))))

(deftest malformed-shapes-test
  (doseq [[why defs] [["definition is a vector" [[:id :wrap-when]]]
                      [":commands is a map" [(assoc good :commands {:a 1} :bindings [])]]
                      ["a command is a string" [(assoc good :commands ["wrap"] :bindings [])]]
                      [":effects is a string" [(assoc good :effects "pure")]]
                      [":hooks is a vector" [(assoc good :hooks [:after-save])]]]]
    (let [r (try (check defs) (catch :default e {:threw (ex-message e)}))]
      (is (nil? (:threw r)) (str why ": " (:threw r)))
      (is (and (false? (:ok? r)) (seq (:problems r))) why))))

(deftest ok-agrees-with-problems-test
  (doseq [defs [[good] [] [(dissoc good :effects)]]]
    (let [r (check defs)]
      (is (= (:ok? r) (empty? (:problems r)))))))

;; --- bindings --------------------------------------------------------------

(def ^:private both
  ;; an :editor and a :window command, so bindings can reach either scope
  (assoc good :effects #{:console}
         :commands [(first (:commands good))
                    {:id :wrap-when/say :doc "Say hi." :scope :window :run (fn [_ _] nil)}]))

(defn- bind [b] (check [(assoc both :bindings [b])]))

(defn- mentions? [result k s]
  (some #(str/includes? % s) (get result k)))

(deftest typing-keys-test
  (doseq [b [{:key "w" :command :wrap-when/wrap}
             {:key "w" :mods #{:shift} :command :wrap-when/wrap}
             {:key "w" :mods #{:alt} :command :wrap-when/wrap}
             {:key "Return" :command :wrap-when/wrap}
             {:key "Return" :mods #{:shift} :command :wrap-when/wrap}
             {:key "space" :command :wrap-when/wrap}
             {:key "w" :command :wrap-when/say}
             {:key "w" :command :run/run}]]
    (let [r (bind b)]
      (is (one-problem r) (pr-str b))
      (is (mentions? r :problems "a key you type") (pr-str b))))
  (is (:ok? (bind {:key "j" :mods #{:cmd :alt} :command :wrap-when/wrap})))
  (is (:ok? (bind {:key "F2" :mods #{:shift :alt} :command :wrap-when/wrap}))))

(deftest unreachable-test
  (let [r (bind {:key "r" :mods #{:cmd} :command :wrap-when/wrap})]
    (is (one-problem r))
    (is (mentions? r :problems ":run/run"))
    (is (mentions? r :problems ":window")))
  (is (one-problem (bind {:key "r" :mods #{:cmd :alt} :command :wrap-when/wrap}))
      "Cmd+Alt+R too: the window's Cmd+R doesn't exclude Alt")
  (is (:ok? (bind {:key "j" :mods #{:cmd :alt} :command :wrap-when/wrap}))))

(deftest shadow-test
  (let [r (bind {:key "w" :mods #{:cmd} :command :wrap-when/say})]
    (is (:ok? r))
    (is (mentions? r :warnings ":tab/close")))
  (let [r (bind {:key "slash" :mods #{:cmd} :command :wrap-when/say})]
    (is (:ok? r))
    (is (mentions? r :warnings ":edit/toggle-comment")))
  (let [r (bind {:key "b" :mods #{:ctrl} :command :wrap-when/wrap})]
    (is (:ok? r))
    (is (mentions? r :warnings ":help/jump")))
  (is (= [] (:warnings (bind {:key "F2" :mods #{:shift :alt} :command :wrap-when/wrap})))))

(deftest binding-scope-test
  (let [r (bind {:key "j" :mods #{:cmd :alt} :command :console/complete})]
    (is (one-problem r))
    (is (mentions? r :problems "console")))
  (is (:ok? (bind {:key "j" :mods #{:cmd :alt} :command :run/run}))
      "a registered window command, validated as :window"))

(deftest binding-parse-test
  (doseq [b [{:key "nope" :mods #{:cmd} :command :wrap-when/wrap}
             {:key "j" :mods #{:cmd} :command :wrap-when/missing}
             {:key "j" :mods #{:cmd} :when [:nope] :command :wrap-when/wrap}]]
    (let [r (bind b)]
      (is (one-problem r) (pr-str b))
      (is (mentions? r :problems "bindings: ") (pr-str b)))))
