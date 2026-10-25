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

;; --- review fixes ----------------------------------------------------------

(deftest conditional-window-binding-is-not-unreachable-test
  ;; the window takes these only sometimes: a :when guard (Cmd+G while the
  ;; find bar can navigate), a command that can pass the key on (Escape's
  ;; popover close, Cmd+['s jump back), the chain's own :to-gtk end
  (doseq [[b shadowed] [[{:key "g" :mods #{:cmd} :command :wrap-when/wrap} ":find/next"]
                        [{:key "Escape" :command :wrap-when/wrap} ":find/bar-key"]
                        [{:key "bracketleft" :mods #{:cmd} :command :wrap-when/wrap} ":source/back"]]]
    (let [r (bind b)]
      (is (:ok? r) (pr-str b r))
      (is (mentions? r :warnings shadowed) (pr-str b r)))))

(deftest guarded-then-unconditional-is-unreachable-test
  ;; Cmd+Shift+O: Open Here while the source panel has focus, else Open
  ;; (Shift isn't in its :not), so the window takes it whichever way
  (let [r (bind {:key "o" :mods #{:cmd :shift} :command :wrap-when/wrap})]
    (is (one-problem r) (pr-str r))
    (is (mentions? r :problems "can never fire"))))

(deftest malformed-bindings-never-throw-test
  (doseq [b [{:key "j" :mods :cmd :command :wrap-when/wrap}
             {:key "j" :mods 5 :command :wrap-when/wrap}
             {:key "j" :not :alt :mods #{:cmd} :command :wrap-when/wrap}
             {:keys :j :mods #{:cmd} :command :wrap-when/wrap}]]
    (let [r (try (bind b) (catch :default e {:threw (ex-message e)}))]
      (is (nil? (:threw r)) (str (pr-str b) " threw " (:threw r)))
      (is (one-problem r) (pr-str b r))))
  (doseq [cs [:x 5]]
    (let [r (try (check [(assoc good :commands cs)]) (catch :default e {:threw (ex-message e)}))]
      (is (nil? (:threw r)) (str cs " threw " (:threw r)))
      (is (false? (:ok? r))))))

(deftest request-conditions-as-any-collection-test
  (doseq [cs [nil [] [:writable] :x]]
    (let [r (try (pc/validate (assoc request :conditions cs :file-id "wrap-when"
                                      :definitions [(assoc both :bindings [{:key "j" :mods #{:cmd :alt} :when [:writable]
                                                                            :command :wrap-when/wrap}])]))
                 (catch :default e {:threw (ex-message e)}))]
      (is (nil? (:threw r)) (str (pr-str cs) " threw " (:threw r))))))

(deftest bad-command-scope-is-one-problem-test
  (let [r (check [(assoc both :commands [(assoc (first (:commands both)) :scope :console)]
                         :bindings [{:key "j" :mods #{:cmd :alt} :command :wrap-when/wrap}])])]
    (is (one-problem r) (pr-str r))))

(deftest meta-and-super-labels-test
  (is (mentions? (bind {:key "w" :mods #{:meta} :command :wrap-when/say}) :warnings "Meta+W"))
  (is (mentions? (bind {:key "a" :mods #{:super} :command :wrap-when/say}) :problems "Super+A")))
