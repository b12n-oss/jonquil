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
  (is (= 0xffc0 (km/key-value "F3")))
  (is (= 0xffc3 (km/key-value "F6")))
  (is (= 0xff1b (km/key-value "Escape")))
  (is (= 0xffc2 (km/key-value "F5")))
  (is (= 0x5b (km/key-value "bracketleft")))
  (is (= 0x20 (km/key-value "space")))
  (is (= 0x2f (km/key-value "slash")))
  (is (= 0x3b (km/key-value "semicolon")))
  (is (= 0xffbe (km/key-value "F1")))
  (is (= 0xffbf (km/key-value "F2")))
  (is (= 0xffc9 (km/key-value "F12")))
  (is (= 0xff0d (km/key-value "Return")))
  (is (= 0xff8d (km/key-value "KP_Enter")))
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
    (is (= [] (km/problems [{:key "r" :when [:c] :command :x}] ids #{:c})))
    (is (= [] (km/problems [{:key "r" :outcome :to-gtk} {:key "s" :outcome :handled}] ids)))
    (is (= [] (km/problems [{:key "r" :exact #{:shift} :physical? true :command :x}] ids)))
    (testing "one problem per rule, naming the binding's position"
      (doseq [[b why] [[{:mods #{:cmd} :command :x} "neither :key nor :keys"]
                       [{:key "r" :keys #{"s"} :command :x} "both :key and :keys"]
                       [{:key "nope" :command :x} "unknown key name"]
                       [{:keys #{"r" "nope"} :command :x} "unknown key name in :keys"]
                       [{:key "r" :mods #{:hyper} :command :x} "unknown modifier"]
                       [{:key "r" :not #{:hyper} :command :x} "unknown modifier in :not"]
                       [{:key "r" :command :y} "unregistered command"]
                       [{:key "r" :command :x :whenever [:z]} "unknown binding key"]
                       [{:key "r" :command :x :when [:nope]} "unregistered condition"]
                       [{:key "r" :command :x :when :c} ":when isn't a vector"]
                       [{:key "r" :outcome :bogus} "unknown outcome"]
                       [{:key "r" :command :x :outcome :to-gtk} "both :command and :outcome"]
                       [{:key "r"} "neither :command nor :outcome"]
                       [{:key "r" :exact #{:cmd} :command :x} ":exact names :cmd"]
                       [{:key "r" :exact #{:shift} :mods #{:ctrl} :command :x} ":exact with :mods"]
                       [{:key "r" :exact #{} :not #{:alt} :command :x} ":exact with :not"]
                       [{:key "r" :physical? "yes" :command :x} ":physical? not boolean"]
                       [{:key "r" :exact [:shift] :command :x} ":exact a vector"]
                       [{:key "r" :exact :shift :command :x} ":exact a keyword"]
                       [{:key "r" :mods :cmd :command :x} ":mods a keyword"]
                       [{:key "r" :not 5 :command :x} ":not a number"]
                       [{:keys :r :command :x} ":keys a keyword"]]]
        (let [ps (km/problems [{:key "a" :command :x} b] ids #{:c})]
          (is (= 1 (count ps)) (str why ": " (pr-str ps)))
          (is (str/includes? (str (first ps)) "binding 1") (str why ": " (pr-str ps))))))))

(deftest problems-wording-and-order-test
  ;; characterization: the exact sentences, and their order, for bindings
  ;; that break several rules at once
  (is (= ["binding 0 :keys isn't a set of key names" "binding 0 :mods isn't a set"
          "binding 0 :not isn't a set" "binding 0 :exact isn't a set"
          "binding 0 has :exact alongside :mods or :not"
          "binding 0 :physical? isn't true or false"
          "binding 0 has both :command and :outcome"
          "binding 0 names an unknown outcome :bogus, not :handled or :to-gtk"
          "binding 0 :when isn't a vector" "binding 0 has unknown keys (:zzz)"]
         (km/problems [{:keys :r :mods :cmd :not 5 :exact [:shift] :physical? "y"
                        :command :x :outcome :bogus :when :c :zzz 1}]
                      #{:x} #{:c})))
  (is (= ["binding 0 has both :key and :keys" "binding 0 names an unknown modifier (:hyper)"
          "binding 0 :exact names (:cmd), only :shift :alt :ctrl :meta"
          "binding 0 has :exact alongside :mods or :not"
          "binding 0 has both :command and :outcome"
          "binding 0 names an unregistered command :y"
          "binding 0 names an unknown outcome :bogus, not :handled or :to-gtk"
          "binding 0 names an unregistered condition (:nope)"
          "binding 0 has unknown keys (:extra)"]
         (km/problems [{:key "r" :keys #{"s"} :mods #{:hyper} :exact #{:cmd :shift}
                        :command :y :outcome :bogus :when [:nope] :extra 1}]
                      #{:x} #{:c})))
  (is (= ["binding 0 names an unknown key (\"nope\")"
          "binding 0 :exact names (:cmd), only :shift :alt :ctrl :meta"
          "binding 0 has :exact alongside :mods or :not"
          "binding 0 has neither :command nor :outcome"
          "binding 1 names an unknown key (\"nope\")"
          "binding 1 names an unknown modifier (:hyper)"
          "binding 1 has neither :command nor :outcome"]
         (km/problems [{:key "nope" :exact #{:cmd} :not #{:alt}}
                       {:keys #{"nope" "r"} :mods #{:hyper}}]
                      #{:x} #{:c})))
  (is (= ["binding 0 has neither :key nor :keys" "binding 0 has neither :command nor :outcome"]
         (km/problems [{:mods #{:cmd}}] #{:x} #{:c}))))

(deftest when-test
  (let [b {:key "o" :mods #{:cmd :shift} :when [:a :b] :command :x}
        st (bit-or ctrl shift)]
    (is (km/matches? b 0x6f st #{:a :b}) "every condition holds")
    (is (not (km/matches? b 0x6f st #{:a})) "one condition false")
    (is (not (km/matches? b 0x6f st (constantly false))))
    (is (not (km/matches? b 0x6f st)) "a :when binding never matches without holds?")
    (is (= :x (:command (km/resolve-binding [b] 0x6f st #{:a :b}))))
    (is (km/matches? {:key "r" :mods #{:cmd}} 0x72 ctrl) "the 3-arity still works without :when")))

(deftest when-order-test
  ;; conditions run only after the key and modifiers match, in order,
  ;; stopping at the first false
  (let [calls (atom [])
        holds? (fn [id] (swap! calls conj id) (= id :b))
        b {:key "o" :mods #{:cmd :shift} :when [:a :b] :command :x}
        st (bit-or ctrl shift)]
    (km/resolve-binding [b] 0x72 st holds?)
    (is (= [] @calls) "another key: no condition is asked")
    (km/resolve-binding [b] 0x6f ctrl holds?)
    (is (= [] @calls) "modifiers don't match: no condition is asked")
    (km/resolve-binding [b] 0x6f st holds?)
    (is (= [:a] @calls) ":a is false, so :b is never asked")))

(deftest dispatch-test
  (let [ran (atom [])
        run! (fn [answers] (fn [b] (swap! ran conj (:command b)) (get answers (:command b))))
        esc 0xff1b
        a {:key "Escape" :command :a}
        b {:key "Escape" :command :b}]
    (testing ":next moves on to the next matching binding"
      (is (= :handled (km/dispatch [a b] esc 0 {:holds? (constantly true)
                                                :run! (run! {:a :next :b :handled})})))
      (is (= [:a :b] @ran)))
    (testing "a fixed outcome answers without running anything after it"
      (reset! ran [])
      (is (= :to-gtk (km/dispatch [{:key "Escape" :outcome :to-gtk} b] esc 0
                                  {:holds? (constantly true) :run! (run! {:b :handled})})))
      (is (= [] @ran)))
    (testing ":to-gtk from a command stops the walk"
      (reset! ran [])
      (is (= :to-gtk (km/dispatch [a b] esc 0 {:holds? (constantly true)
                                               :run! (run! {:a :to-gtk :b :handled})})))
      (is (= [:a] @ran)))
    (testing "no match is :unmatched"
      (reset! ran [])
      (is (= :unmatched (km/dispatch [a b] 0x72 0 {:holds? (constantly true)
                                                   :run! (run! {:a :handled})})))
      (is (= [] @ran)))
    (testing "an unregistered command (run! answers nil) moves on"
      (reset! ran [])
      (is (= :handled (km/dispatch [a b] esc 0 {:holds? (constantly true)
                                                :run! (run! {:b :handled})})))
      (is (= [:a :b] @ran)))
    (testing "a :when that doesn't hold skips the binding without running it"
      (reset! ran [])
      (is (= :handled (km/dispatch [(assoc a :when [:c]) b] esc 0
                                   {:holds? (constantly false) :run! (run! {:a :handled :b :handled})})))
      (is (= [:b] @ran)))))

(deftest exact-test
  ;; :exact is exactly that set among Shift, Alt, Control and Meta; Super is
  ;; ignored, as shift-only? and no-modifiers? ignored it
  (let [b {:key "F1" :exact #{:shift}} f1 0xffbe]
    (is (km/matches? b f1 shift))
    (is (km/matches? b f1 (bit-or shift super)))
    (doseq [st [(bit-or shift ctrl) (bit-or shift meta-m) (bit-or shift alt) 0]]
      (is (not (km/matches? b f1 st)) (str "state " st))))
  (let [b {:key "F12" :exact #{}} f12 0xffc9]
    (is (km/matches? b f12 0))
    (is (km/matches? b f12 super))
    (doseq [st [shift ctrl meta-m alt]]
      (is (not (km/matches? b f12 st)) (str "state " st)))))

(deftest panel-keys-test
  ;; Cmd/Ctrl+Alt+1..6 focus or open the panels, ahead of Cmd/Ctrl+1's tab
  (let [run (fn [kv st physical]
              (let [got (atom nil)]
                (km/dispatch (:window km/shipped) kv st
                             {:holds? (constantly false) :physical physical
                              :run! (fn [b] (reset! got [(:command b) (:args b)]) :handled)})
                @got))]
    (is (= [[:panel/inspector nil] [:panel/source nil] [:panel/locations nil]
            [:panel/frames nil] [:panel/trace nil] [:panel/plugins nil] [:panel/messages nil]
            [:panel/processes nil] [:pages/show nil]]
           (for [d "123456789"] (run (int d) (bit-or ctrl alt) nil)))
        "Linux: Ctrl+Alt and the plain digit")
    (is (= [:panel/trace nil] (run 0x100221e (bit-or meta-m alt) "5"))
        "macOS: Option+5's infinity, through the physical key")
    (is (= [:panel/messages nil] (run 0xb6 (bit-or meta-m alt) "7"))
        "macOS: Option+7's pilcrow, through the physical key")
    (is (= [:panel/processes nil] (run 0x1002022 (bit-or meta-m alt) "8"))
        "macOS: Option+8's bullet, through the physical key")
    (is (= [:pages/show nil] (run 0xaa (bit-or meta-m alt) "9"))
        "macOS: Option+9's ordinal indicator, through the physical key")
    (is (= [:tab/select {:n 1}] (run 0x31 ctrl nil)) "Ctrl+1 is still tab 1")
    (is (not= :panel/inspector (first (run 0x31 (bit-or ctrl alt shift) nil))) "Shift held: not a panel key")))

(deftest physical-test
  ;; the macOS Option-translated keyval matches through the physical key,
  ;; on a binding that names :alt or opts in with :physical?
  (let [b {:keys #{"f" "l"} :mods #{:cmd :alt} :physical? true}
        st (bit-or meta-m alt)]
    (is (km/matches? b 0x8f6 st (constantly false) "f"))
    (is (km/matches? b 0xac st (constantly false) "l"))
    (is (not (km/matches? b 0x8f6 st (constantly false) nil)))
    (is (not (km/matches? b 0x8f6 st (constantly false) "q")) "another physical key")
    (is (not (km/matches? b 0x8f6 meta-m (constantly false) "f")) "the modifiers still count")
    (is (km/matches? b 0x66 st (constantly false) nil) "the plain keyval still matches"))
  (is (km/matches? {:keys #{"f" "l"} :mods #{:cmd :alt}} 0x8f6 (bit-or meta-m alt)
                   (constantly false) "f")
      "a binding that names :alt matches by the physical key without :physical?")
  (is (km/matches? {:key "s" :exact #{:ctrl :alt}} 0xdf (bit-or ctrl alt) (constantly false) "s")
      "an :exact set that names :alt does too")
  (is (not (km/matches? {:key "s" :mods #{:cmd}} 0xdf (bit-or meta-m alt) (constantly false) "s"))
      "a binding that doesn't name :alt never matches by the physical key")
  (is (= :handled (km/dispatch [{:key "f" :mods #{:cmd} :physical? true :command :x}] 0x8f6 ctrl
                               {:holds? (constantly true) :run! (constantly :handled) :physical "f"}))
      "dispatch passes :physical through")
  (is (= :unmatched (km/dispatch [{:key "f" :mods #{:cmd} :physical? true :command :x}] 0x8f6 ctrl
                                 {:holds? (constantly true) :run! (constantly :handled)}))))

(deftest problems-empty-exact-test
  (is (= [] (km/problems [{:key "r" :exact #{} :command :x}] #{:x}))))
