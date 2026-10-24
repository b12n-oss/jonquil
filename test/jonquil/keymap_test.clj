(ns jonquil.keymap-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.keymap :as km]))

(def ^:private ctrl (:ctrl km/MODIFIER-MASKS))
(def ^:private meta-m (:meta km/MODIFIER-MASKS))
(def ^:private shift (:shift km/MODIFIER-MASKS))
(def ^:private alt (:alt km/MODIFIER-MASKS))
(def ^:private super (:super km/MODIFIER-MASKS))

(deftest key-value-test
  (is (= 0x72 (km/key-value "r")))
  (is (= 0x30 (km/key-value "0")))
  (is (= 0x3d (km/key-value "equal")))
  (is (= 0x2b (km/key-value "plus")))
  (is (= 0x7b (km/key-value "braceleft")))
  (is (nil? (km/key-value "nope"))))

(deftest matches-test
  (let [b {:key "r" :mods #{:cmd}}]
    (is (km/matches? b 0x72 ctrl) ":cmd is Control")
    (is (km/matches? b 0x72 meta-m) ":cmd is Meta too")
    (is (km/matches? b 0x72 (bit-or ctrl shift alt super)) "modifiers in neither set are ignored")
    (is (not (km/matches? b 0x72 shift)))
    (is (not (km/matches? b 0x72 0)))
    (is (not (km/matches? b 0x73 ctrl)) "another key"))
  (is (not (km/matches? {:key "l" :mods #{:cmd} :not #{:alt}} 0x6c (bit-or ctrl alt))) ":not up")
  (is (km/matches? {:key "l" :mods #{:cmd} :not #{:alt}} 0x6c ctrl))
  (let [b {:keys #{"equal" "plus"} :mods #{:cmd}}]
    (is (km/matches? b 0x3d ctrl))
    (is (km/matches? b 0x2b ctrl))
    (is (not (km/matches? b 0x2d ctrl)))))

(deftest resolve-binding-test
  (let [bs [{:key "s" :mods #{:cmd :shift} :command :a}
            {:key "s" :mods #{:cmd} :command :b}]]
    (is (= :a (:command (km/resolve-binding bs 0x73 (bit-or ctrl shift)))) "first match wins")
    (is (= :b (:command (km/resolve-binding bs 0x73 ctrl))))
    (is (nil? (km/resolve-binding bs 0x73 0)))))

(deftest problems-test
  (let [ids #{:x}]
    (is (= [] (km/problems [{:key "r" :mods #{:cmd} :not #{:alt} :command :x :args {:n 1}}] ids)))
    (testing "one problem per rule, naming the binding's position"
      (doseq [[b why] [[{:mods #{:cmd} :command :x} "neither :key nor :keys"]
                       [{:key "r" :keys #{"s"} :command :x} "both :key and :keys"]
                       [{:key "nope" :command :x} "unknown key name"]
                       [{:keys #{"r" "nope"} :command :x} "unknown key name in :keys"]
                       [{:key "r" :mods #{:hyper} :command :x} "unknown modifier"]
                       [{:key "r" :not #{:hyper} :command :x} "unknown modifier in :not"]
                       [{:key "r" :command :y} "unregistered command"]
                       [{:key "r" :command :x :when [:z]} "unknown binding key"]]]
        (let [ps (km/problems [{:key "a" :command :x} b] ids)]
          (is (= 1 (count ps)) (str why ": " (pr-str ps)))
          (is (str/includes? (str (first ps)) "binding 1") (str why ": " (pr-str ps))))))))
