(ns jonquil.key-sets-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.command-registry :as registry]
            [jonquil.key-sets :as ks]
            [jonquil.keymap :as km]
            [jonquil.plugin-registry :as plugin-registry]
            [jonquil.user-keys :as user-keys]))

(def ^:private systems ["Mac OS X" "Linux"])

(def ^:private M km/MODIFIER-MASKS)

(def ^:private states
  "Every combination of Shift, Alt, Control and Meta."
  (for [s [0 (:shift M)] a [0 (:alt M)] c [0 (:ctrl M)] m [0 (:meta M)]]
    (bit-or s a c m)))

(defn- key-names [b] (if (:key b) [(:key b)] (:keys b)))

(deftest sets-test
  (is (= [:default :calva] (map :id ks/sets)))
  (is (= "Jonquil default" (ks/label :default)))
  (is (= "Calva paredit" (ks/label :calva)))
  (is (= "Jonquil default" (ks/label :no-such-set)) "an unknown id is the default set")
  (doseq [os systems scope [:window :editor]]
    (is (= [] (ks/bindings :default scope os)) "the default set binds nothing")))

(deftest os-test
  (is (= :mac (ks/system "Mac OS X")))
  (is (= :linux (ks/system "Linux")))
  (is (= :linux (ks/system "Windows 11")))
  (let [cmds (fn [os] (set (map :command (ks/bindings :calva :editor os))))]
    (is (contains? (cmds "Mac OS X") :paredit/kill-right) "Ctrl+K is Calva's on a Mac")
    (is (not (contains? (cmds "Mac OS X") :paredit/expand-selection)) "Ctrl+W closes the tab there")
    (is (contains? (cmds "Linux") :paredit/expand-selection)))
  (let [kill (fn [os] (first (filter #(= :paredit/kill-right (:command %)) (ks/bindings :calva :editor os))))]
    (is (nil? (:then (kill "Mac OS X"))) "one chord on a Mac")
    (is (= [{:key "k" :exact #{:ctrl}}] (:then (kill "Linux"))) "Ctrl+K Ctrl+K on Linux"))
  (is (not-any? :os (ks/bindings :calva :editor "Linux")) "the :os is dropped"))

(deftest every-binding-is-sound-test
  ;; a set's bindings pass what keys.edn's must: jonquil.keymap's checks, a
  ;; command of the binding's scope, and no key that types without a
  ;; modifier other than Shift
  (let [commands {:window registry/window-commands :editor registry/editor-commands}]
    (doseq [{:keys [id]} ks/sets os systems scope [:window :editor]
            :let [bs (ks/bindings id scope os)]]
      (is (= [] (km/problems bs (set (keys (get commands scope))) #{})) (str id " " scope " " os))
      (doseq [b bs]
        (is (not (and (some km/typing-key? (key-names b))
                      (empty? (disj (set (or (:exact b) (:mods b))) :shift))))
            (str id " " (pr-str b)))))))

(deftest a-set-reaches-its-commands-test
  ;; with a set on, each of its editor chords runs its own command: no
  ;; window binding takes the key first, and no earlier entry of the set
  ;; shadows it
  (doseq [{:keys [id]} ks/sets os systems
          :let [layered (fn [scope]
                          (user-keys/layer {scope (ks/bindings id scope os) :remove (ks/bindings id :remove os)}
                                           scope (get km/shipped scope)))
                editor (layered :editor)
                window (layered :window)]
          b (ks/bindings id :editor os) k (key-names b) st states
          :let [kv (km/key-value k)
                hit (km/resolve-binding editor kv st (constantly true))]
          :when (= b hit)]
    (is (nil? (km/resolve-binding window kv st (constantly true)))
        (str id " on " os ": the window takes " k " state " st)))
  (doseq [{:keys [id]} ks/sets os systems
          :let [bs (ks/bindings id :editor os)]]
    (is (= [] (km/unreachable-bindings bs bs)) (str id " on " os))))

(deftest the-shipped-keys-bind-no-structural-command-test
  (is (= [] (filter #(= "paredit" (some-> (:command %) namespace))
                    (concat (:window km/shipped) (:editor km/shipped))))))

(deftest live-layer-test
  ;; yours, then the key set, then the shipped keys
  (let [reg (plugin-registry/empty-registry)
        commands #(set (map :command (plugin-registry/editor-bindings reg)))]
    (try
      (reset! ks/live :default)
      (is (not (contains? (commands) :paredit/splice)))
      (reset! ks/live :calva)
      (is (contains? (commands) :paredit/splice))
      (testing "a key of yours goes ahead of the set's, and :remove drops a set's key"
        (let [mine {:key "s" :exact #{:ctrl :alt} :command :paredit/join}
              splice {:key "s" :exact #{:ctrl :alt} :command :paredit/splice}]
          (reset! user-keys/live (assoc user-keys/no-keys :editor [mine] :remove [splice]))
          (is (= mine (first (plugin-registry/editor-bindings reg))))
          (is (not (some #{splice} (plugin-registry/editor-bindings reg))))))
      (finally
        (reset! ks/live :default)
        (reset! user-keys/live user-keys/no-keys)))))

(deftest set-sequences-test
  ;; no two bindings of a set are the same whole sequence, and a set's
  ;; :remove names bindings the shipped keys have
  (doseq [{:keys [id]} ks/sets os systems
          :let [bs (ks/bindings id :editor os)
                whole (fn [b] [(key-names b) (:exact b) (:then b)])]]
    (is (= (count bs) (count (distinct (map whole bs)))) (str id " on " os)))
  (doseq [os systems r (ks/bindings :calva :remove os)]
    (is (some #(and (= (:command r) (:command %)) (= (:key r) (:key %)) (= (:mods r) (:mods %)))
              (concat (:window km/shipped) (:editor km/shipped)))
        (pr-str r)))
  (testing "with the Calva set on, Ctrl+Alt+P no longer cycles Parinfer in the window"
    (let [reg (plugin-registry/empty-registry)
          hit #(km/resolve-binding (plugin-registry/window-bindings reg) (km/key-value "p")
                                   (bit-or (:ctrl M) (:alt M)) (constantly true))]
      (try
        (is (= :parinfer/next-mode (:command (hit))))
        (reset! ks/live :calva)
        (is (nil? (hit)))
        (finally (reset! ks/live :default))))))
