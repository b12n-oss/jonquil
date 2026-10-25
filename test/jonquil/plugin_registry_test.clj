(ns jonquil.plugin-registry-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jonquil.command-registry :as cmd-registry]
            [jonquil.keymap :as km]
            [jonquil.plugin :as p]
            [jonquil.plugin-registry :as reg]))

(defn- plugin
  "A definition for id with one :edit command (edit) bound to Cmd+Alt+J."
  [id edit & {:keys [more-commands more-bindings]}]
  {:id (keyword id) :doc "d" :effects #{:console}
   :commands (into [{:id (keyword id "wrap") :doc "Wrap." :scope :editor :edit edit}] more-commands)
   :bindings (into [{:key "j" :mods #{:cmd :alt} :command (keyword id "wrap")}] more-bindings)})

(defn- with [& defs]
  (reduce (fn [r d] (reg/install r (:id d) {:definition d :source "" :file "" :layer :user}))
          (reg/empty-registry) defs))

(def ^:private ctrl-alt (bit-or (:ctrl km/MODIFIER-MASKS) (:alt km/MODIFIER-MASKS)))

(deftest empty-registry-is-the-shipped-keymap-test
  (let [r (reg/empty-registry)]
    (is (= (:window km/shipped) (reg/window-bindings r)))
    (is (= (:editor km/shipped) (reg/editor-bindings r)))
    (is (= cmd-registry/commands (reg/all-commands r)))
    (is (= {} (reg/commands r)))))

(deftest edit-command-outcomes-test
  (let [r (with (plugin "p3-a" (fn [s] (p/wrap-form s "(when " ")"))))
        applied (atom [])
        ctx (fn [snap] {:snapshot (constantly snap) :apply-op! #(swap! applied conj %)})
        run (fn [snap] (cmd-registry/run-command! (reg/commands r) {:command :p3-a/wrap} (ctx snap)))]
    (is (= :editor (:scope (get (reg/commands r) :p3-a/wrap))))
    (is (= :p3-a (:plugin (get (reg/commands r) :p3-a/wrap))))
    (is (= :handled (run {:text "(a b)" :caret 1 :sel-start 1 :sel-end 1})))
    (is (= 1 (count @applied)))
    (reset! applied [])
    (is (= :to-gtk (run {:text "(a)\n\n(b)" :caret 4 :sel-start 4 :sel-end 4})) "no form: nil op")
    (is (= [] @applied)))
  (let [r (with (plugin "p3-throw" (fn [_] (throw (ex-info "kaboom" {})))))
        e (try (cmd-registry/run-command! (reg/commands r) {:command :p3-throw/wrap}
                                          {:snapshot (constantly {:text "" :caret 0}) :apply-op! identity})
               nil (catch :default e e))]
    (is (some? e))
    (is (str/starts-with? (ex-message e) "plugin p3-throw: "))
    (is (str/includes? (ex-message e) "kaboom"))))

(deftest run-commands-left-out-test
  (let [r (with (plugin "p3-run" (fn [_] nil)
                        :more-commands [{:id :p3-run/say :doc "Say." :scope :window :run (fn [_ _] nil)}]
                        :more-bindings [{:key "j" :mods #{:cmd :alt :shift} :command :p3-run/say}]))]
    (is (= #{:p3-run/wrap} (set (keys (reg/commands r)))))
    (is (= [] (reg/bindings r :window)))
    (is (= 1 (count (reg/bindings r :editor))))))

(deftest binding-order-test
  (doseq [order [["p3-a" "p3-b"] ["p3-b" "p3-a"]]]
    (let [r (apply with (map #(plugin % (fn [s] s)) order))
          b (km/resolve-binding (reg/editor-bindings r) 0x6a ctrl-alt (constantly true))]
      (is (= :p3-a/wrap (:command b)) (pr-str order))))
  (let [r (with (plugin "p3-a" (fn [s] s)))]
    (is (= (inc (count (:editor km/shipped))) (count (reg/editor-bindings r))))
    (is (= :p3-a/wrap (:command (first (reg/editor-bindings r)))) "plugin bindings come first")))

(deftest remove-plugin-test
  (let [r (reg/remove-plugin (with (plugin "p3-a" (fn [s] s))) :p3-a)]
    (is (= {} (reg/commands r)))
    (is (= (:editor km/shipped) (reg/editor-bindings r)))))
