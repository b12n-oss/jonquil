(ns jonquil.key-prefs-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.key-prefs :as kp]
            [jonquil.keymap :as km]
            [jonquil.user-keys :as uk]))

(def ^:private mac "Mac OS X")
(def ^:private linux "Linux")

(def ^:private commands
  {:run/run {:doc "Run the buffer." :scope :window}
   :run/stop {:doc "Stop the evaluation." :scope :window :palette? false}
   :file/save {:doc "Save." :scope :window}
   :view/zoom {:doc "Zoom." :scope :window}
   :edit/wrap {:doc "Wrap the form." :scope :editor :plugin :wrap-when}})

(def ^:private run-r {:key "r" :mods #{:cmd} :command :run/run})
(def ^:private run-f5 {:key "F5" :command :run/run})
(def ^:private stop {:key "Escape" :when [:evaluating] :command :run/stop})
(def ^:private save {:key "s" :mods #{:cmd} :command :file/save})
(def ^:private wrap {:key "j" :mods #{:cmd :alt} :command :edit/wrap})

(defn- keymap [user]
  {:window (uk/layer user :window [run-f5 run-r stop save {:key "Escape" :outcome :to-gtk}])
   :editor (uk/layer user :editor [wrap])})

(defn- rows [user] (kp/rows {:keymap (keymap user) :commands commands :user user :os mac}))

(deftest rows-test
  (let [rs (rows uk/no-keys)]
    (testing "one row per binding with a command, window keys then editor keys, then the unbound commands"
      (is (= [[:window :run/run "F5"] [:window :run/run "Cmd+R"] [:window :run/stop "Esc"]
              [:window :file/save "Cmd+S"] [:editor :edit/wrap "Cmd+Option+J"]
              [:window :view/zoom ""]]
             (mapv (juxt :scope :command :chord) rs))))
    (testing "each row has the command's description and where the key comes from"
      (is (= ["Run the buffer." "default"] ((juxt :doc :source) (first rs))))
      (is (= "wrap-when" (:source (nth rs 4))))
      (is (= "" (:source (last rs))) "an unbound command has no source"))
    (testing "a binding with a condition says so, and can't be recorded over"
      (is (= [false false true false false false] (mapv :fixed? rs)))))
  (testing "your own binding reads yours"
    (let [mine {:key "e" :mods #{:cmd} :command :run/run}
          rs (rows (assoc uk/no-keys :window [mine]))]
      (is (= [:run/run "Cmd+E" "yours"] ((juxt :command :chord :source) (first rs))))
      (is (= mine (:binding (first rs))))))
  (testing "a command the palette hides gets no unbound row"
    (is (not-any? #(and (= :run/stop (:command %)) (= "" (:chord %))) (rows uk/no-keys)))))

(deftest filter-rows-test
  (let [rs (rows uk/no-keys)]
    (is (= rs (kp/filter-rows rs "")))
    (is (= rs (kp/filter-rows rs "  ")))
    (is (= [:run/run :run/run] (mapv :command (kp/filter-rows rs "run the"))))
    (is (= [:file/save] (mapv :command (kp/filter-rows rs "cmd+s"))) "a chord matches too")
    (is (= [:edit/wrap] (mapv :command (kp/filter-rows rs "WRAP"))) "case doesn't matter")
    (is (= [] (kp/filter-rows rs "no such thing")))))

(def ^:private shift (:shift km/MODIFIER-MASKS))
(def ^:private ctrl (:ctrl km/MODIFIER-MASKS))
(def ^:private meta-mask (:meta km/MODIFIER-MASKS))
(def ^:private alt (:alt km/MODIFIER-MASKS))

(deftest press-chord-test
  (testing "a letter with Cmd on a Mac, with Ctrl elsewhere, is :cmd"
    (is (= {:chord {:key "e" :mods #{:cmd}}} (kp/press-chord (int \e) meta-mask mac)))
    (is (= {:chord {:key "e" :mods #{:cmd}}} (kp/press-chord (int \e) ctrl linux))))
  (testing "Ctrl on a Mac stays Ctrl, and Meta elsewhere stays Meta"
    (is (= {:chord {:key "e" :mods #{:ctrl}}} (kp/press-chord (int \e) ctrl mac)))
    (is (= {:chord {:key "e" :mods #{:meta}}} (kp/press-chord (int \e) meta-mask linux))))
  (testing "several modifiers, and a named key with none"
    (is (= {:chord {:key "k" :mods #{:cmd :shift :alt}}}
           (kp/press-chord (int \k) (bit-or meta-mask shift alt) mac)))
    (is (= {:chord {:key "F9"}} (kp/press-chord (km/key-value "F9") 0 mac))))
  (testing "a modifier key alone is not a chord yet"
    (is (= {:waiting true} (kp/press-chord 0xffe1 shift mac)) "Shift_L")
    (is (= {:waiting true} (kp/press-chord 0xffeb 0 mac)) "Super_L"))
  (testing "a key Jonquil has no name for is refused"
    (is (str/includes? (:problem (kp/press-chord 0x03f shift mac)) "can't bind")))
  (testing "a key that types would shadow typing, so it needs a modifier other than Shift"
    (is (str/includes? (:problem (kp/press-chord (int \e) 0 mac)) "modifier"))
    (is (str/includes? (:problem (kp/press-chord (int \e) shift mac)) "modifier"))
    (is (str/includes? (:problem (kp/press-chord (km/key-value "Return") 0 mac)) "modifier"))
    (is (str/includes? (:problem (kp/press-chord (km/key-value "space") shift mac)) "modifier"))
    (is (= {:chord {:key "Return" :mods #{:cmd}}} (kp/press-chord (km/key-value "Return") meta-mask mac)))
    (is (= {:chord {:key "Escape" :mods #{:shift}}} (kp/press-chord (km/key-value "Escape") shift mac)))))

(deftest conflict-test
  (let [k (keymap uk/no-keys)]
    (testing "a chord another command has"
      (is (= save (:binding (kp/conflict {:keymap k :scope :window :command :run/run :os mac}
                                         {:key "s" :mods #{:cmd}}))))
      (is (= :window (:scope (kp/conflict {:keymap k :scope :window :command :run/run :os mac}
                                          {:key "s" :mods #{:cmd}})))))
    (testing "the command's own chord is no conflict, nor a free chord"
      (is (nil? (kp/conflict {:keymap k :scope :window :command :run/run :os mac} {:key "r" :mods #{:cmd}})))
      (is (nil? (kp/conflict {:keymap k :scope :window :command :run/run :os mac} {:key "F9"}))))
    (testing "an editor chord the window takes first is a conflict too"
      (is (= save (:binding (kp/conflict {:keymap k :scope :editor :command :edit/wrap :os mac}
                                         {:key "s" :mods #{:cmd}})))))
    (testing "a window chord an editor binding has is one as well"
      (is (= wrap (:binding (kp/conflict {:keymap k :scope :window :command :run/run :os mac}
                                         {:key "j" :mods #{:cmd :alt}})))))
    (testing "a binding with a condition counts, its condition taken as holding"
      (is (= stop (:binding (kp/conflict {:keymap k :scope :window :command :run/run :os mac}
                                         {:key "Escape"})))))
    (testing "so does a fixed outcome"
      (is (= :to-gtk (:outcome (:binding (kp/conflict {:keymap {:window [{:key "Escape" :outcome :to-gtk}]}
                                                       :scope :window :command :run/run :os mac}
                                                      {:key "Escape"}))))))))

(deftest record-test
  (let [row (fn [user i] (nth (rows user) i))
        chord {:key "e" :mods #{:cmd}}]
    (testing "a shipped key: yours goes in, the shipped one is removed"
      (let [u (kp/record uk/no-keys (row uk/no-keys 1) chord nil)]
        (is (= [{:key "e" :mods #{:cmd} :command :run/run}] (:window u)))
        (is (= [run-r] (:remove u)))
        (is (= ["F5" "Cmd+E"] (sort-by count (map :chord (filter #(= :run/run (:command %)) (rows u))))))))
    (testing "an unbound command just gets the key"
      (let [u (kp/record uk/no-keys (last (rows uk/no-keys)) chord nil)]
        (is (= [{:key "e" :mods #{:cmd} :command :view/zoom}] (:window u)))
        (is (= [] (:remove u)))))
    (testing "your own key is changed in place"
      (let [u0 (kp/record uk/no-keys (row uk/no-keys 1) chord nil)
            u (kp/record u0 (first (rows u0)) {:key "F9"} nil)]
        (is (= [{:key "F9" :command :run/run}] (:window u)))
        (is (= [run-r] (:remove u)) "the shipped key stays removed")))
    (testing "an editor command's key lands under :editor, with its :args kept"
      (let [k {:key "j" :mods #{:cmd :alt} :command :edit/wrap :args {:n 2}}
            u (kp/record uk/no-keys {:scope :editor :command :edit/wrap :binding k :source "wrap-when"} chord nil)]
        (is (= [{:key "e" :mods #{:cmd} :command :edit/wrap :args {:n 2}}] (:editor u)))
        (is (= [(dissoc k :args)] (:remove u)))))
    (testing "Replace takes the chord from the binding that had it"
      (let [c (kp/conflict {:keymap (keymap uk/no-keys) :scope :window :command :run/run :os mac}
                           {:key "s" :mods #{:cmd}})
            u (kp/record uk/no-keys (row uk/no-keys 1) {:key "s" :mods #{:cmd}} c)]
        (is (= #{run-r save} (set (:remove u))))
        (is (= [:run/run] (map :command (filter #(= "Cmd+S" (:chord %)) (rows u)))))))
    (testing "Replace over one of your own bindings drops that binding"
      (let [u0 (assoc uk/no-keys :window [{:key "e" :mods #{:cmd} :command :file/save}])
            c (kp/conflict {:keymap (keymap u0) :scope :window :command :run/run :os mac} chord)
            u (kp/record u0 (some #(when (= run-r (:binding %)) %) (rows u0)) chord c)]
        (is (= [{:key "e" :mods #{:cmd} :command :run/run}] (:window u)))
        (is (= [run-r] (:remove u)))))
    (testing "recording the same chord twice changes nothing more"
      (let [u (kp/record uk/no-keys (row uk/no-keys 1) chord nil)]
        (is (= u (kp/record u (first (rows u)) chord nil)))))))

(deftest reset-command-test
  (let [u (-> uk/no-keys
              (kp/record (nth (rows uk/no-keys) 1) {:key "e" :mods #{:cmd}} nil)
              (assoc-in [:editor] [{:key "w" :mods #{:cmd} :command :edit/wrap}]))]
    (is (= (assoc uk/no-keys :editor [{:key "w" :mods #{:cmd} :command :edit/wrap}])
           (kp/reset-command u :run/run))
        "yours go and the removed shipped keys come back; other commands' keys stay")
    (is (= uk/no-keys (kp/reset-command uk/no-keys :run/run)))))

(deftest file-text-test
  (let [u {:window [{:command :run/run :mods #{:cmd :shift} :key "e"}
                    {:key "F9" :command :view/zoom :args {:n 1}}]
           :editor []
           :remove [run-r]}
        text (kp/file-text u)
        opts {:commands commands :conditions {:window #{:evaluating} :editor #{}}}]
    (testing "it reads back as what was written"
      (is (= (assoc u :problems []) (uk/read-keys text opts))))
    (testing "one binding a line, the key first"
      (is (str/includes? text "{:window [{:key \"e\" :mods #{"))
      (is (str/includes? text "\n          {:key \"F9\" :command :view/zoom :args {:n 1}}]"))
      (is (str/includes? text "\n :editor []"))
      (is (str/includes? text "\n :remove [{:key \"r\" :mods #{:cmd} :command :run/run}]}")))
    (testing "it starts with a comment saying what wrote it"
      (is (str/starts-with? text kp/written-mark)))
    (testing "nothing at all still writes a readable file"
      (is (= (assoc uk/no-keys :problems []) (uk/read-keys (kp/file-text uk/no-keys) opts))))))

(deftest hand-comments-test
  (is (false? (kp/hand-comments? nil)) "no file")
  (is (false? (kp/hand-comments? "")))
  (is (false? (kp/hand-comments? uk/template)) "the starter file's comments are Jonquil's")
  (is (false? (kp/hand-comments? (kp/file-text uk/no-keys))) "so are a written file's")
  (is (false? (kp/hand-comments? "{:window []}")))
  (is (true? (kp/hand-comments? "{:window []} ; my note")))
  (is (true? (kp/hand-comments? (str uk/template "\n;; mine\n"))))
  (is (false? (kp/hand-comments? "{:window [{:key \"x\" :command :x :args {:s \"a;b\"}}]}")) "a ; in a string is no comment"))
