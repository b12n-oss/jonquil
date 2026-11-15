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

(deftest key-set-source-test
  ;; a binding the chosen key set holds names the set as its source
  (let [mine {:key "j" :mods #{:cmd :alt} :command :edit/wrap}
        rs (kp/rows {:keymap (keymap uk/no-keys) :commands commands :user uk/no-keys :os mac
                     :key-set {:label "Calva paredit" :window [] :editor [mine]}})]
    (is (= ["Calva paredit"] (map :source (filter #(= :edit/wrap (:command %)) rs))))))

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
  (testing "the physical key names the key when the window knows it: Option+S arrives as sharp s"
    (is (= {:chord {:key "s" :mods #{:ctrl :alt}}} (kp/press-chord 0xdf (bit-or ctrl alt) mac "s")))
    (is (str/includes? (:problem (kp/press-chord 0xdf (bit-or ctrl alt) mac)) "can't bind")
        "without it the character has no name"))
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

(deftest press-step-test
  (let [ctrl (get km/MODIFIER-MASKS :ctrl)]
    (testing "a later step may be a key that types"
      (is (= {:chord {:key "k"}} (kp/press-step (km/key-value "k") 0 mac nil))))
    (is (= {:chord {:key "k" :mods #{:ctrl}}} (kp/press-step (km/key-value "k") ctrl mac nil)))
    (is (= {:waiting true} (kp/press-step 0xffe3 ctrl mac nil)) "Control alone waits")))

(deftest add-step-test
  (let [chord {:key "e" :mods #{:cmd}}
        step {:key "k" :mods #{:ctrl}}
        row (fn [user i] (nth (rows user) i))
        opts {:commands commands :conditions {:window #{:evaluating} :editor #{}}}]
    (testing "a step added to one of your keys changes it in place"
      (let [u0 (kp/record uk/no-keys (row uk/no-keys 1) chord nil)
            u (kp/add-step u0 (first (rows u0)) step nil)]
        (is (= [{:key "e" :mods #{:cmd} :then [step] :command :run/run}] (:window u)))
        (testing "and another goes after it"
          (is (= [step {:key "j"}]
                 (:then (first (:window (kp/add-step u (first (rows u)) {:key "j"} nil)))))))
        (testing "the file keeps the steps"
          (is (= (assoc u :problems []) (uk/read-keys (kp/file-text u) opts))))
        (testing "recording one chord over it drops the steps"
          (is (= [{:key "F9" :command :run/run}]
                 (:window (kp/record u (first (rows u)) {:key "F9"} nil)))))))
    (testing "a step added to Jonquil's key makes it yours and removes the old one"
      (let [u (kp/add-step uk/no-keys (row uk/no-keys 1) step nil)]
        (is (= [(assoc run-r :then [step])] (:window u)))
        (is (= [(select-keys run-r [:key :mods :command])] (:remove u)))))))

(deftest sequences-on-test
  (let [a {:key "r" :mods #{:ctrl :alt}}
        p1 (assoc a :then [{:key "p"}] :command :view/zoom)
        p2 (assoc a :then [{:key "s"}] :command :file/save)
        km {:window [p1 save p2] :editor []}
        on (fn [command chord] (kp/sequences-on {:keymap km :command command :os mac} chord))]
    (is (= [{:scope :window :binding p1} {:scope :window :binding p2}] (on :run/run a)))
    (is (= [{:scope :window :binding p2}] (on :view/zoom a)) "its own don't count")
    (is (= [] (on :run/run {:key "s" :mods #{:cmd}})) "a single key isn't a sequence")
    (testing "Replace takes the chord from each of them"
      (let [u (kp/record uk/no-keys {:scope :window :command :run/run :binding nil} a (on :run/run a))]
        (is (= [(assoc a :command :run/run)] (:window u)))
        (is (= 2 (count (:remove u))))))))

(deftest step-conflicts-test
  (let [a {:key "r" :mods #{:ctrl :alt}}
        seq-p (assoc a :then [{:key "p" :mods #{:ctrl :alt}}] :command :view/zoom)
        km {:window [seq-p save] :editor []}
        ask (fn [mine] (kp/step-conflicts {:keymap km :command :run/run :os mac} mine))]
    (is (= [] (ask (assoc a :then [{:key "s" :mods #{:ctrl :alt}}]))) "two sequences share a first chord")
    (is (= [{:scope :window :binding seq-p}] (ask (assoc a :then [{:key "p" :mods #{:ctrl :alt}}])))
        "the same chords")
    (is (= [{:scope :window :binding seq-p}]
           (ask (assoc a :then [{:key "p" :mods #{:ctrl :alt}} {:key "x"}])))
        "another sequence would finish first")
    (is (= [{:scope :window :binding save}]
           (ask {:key "s" :mods #{:cmd} :then [{:key "x"}]}))
        "a single key would run first")
    (is (= [] (kp/step-conflicts {:keymap km :command :view/zoom :os mac} seq-p)) "its own don't count")))
