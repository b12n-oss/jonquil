(ns jonquil.hints-test
  (:require [clojure.test :refer [deftest is]]
            [jonquil.hints :as hints]
            [jonquil.keymap :as km]))

(def ^:private keymap
  {:editor [{:key "F1" :exact #{:shift} :command :help/quick-doc}
            {:key "q" :mods #{:ctrl} :command :help/quick-doc}
            {:key "F12" :exact #{} :command :help/jump}]
   :window []})

(deftest symbol-hint-test
  (is (= "Shift+F1 doc · F12 jump" (hints/hint {:at :symbol :keymap keymap :os "Linux"}))
      "the first chord of each, and a command with no key left out")
  (is (nil? (hints/hint {:at nil :keymap keymap :os "Linux"})) "nothing at the caret"))

(deftest shipped-keymap-test
  (is (= "Ctrl+Q doc · F12 jump · Shift+F6 rename"
         (hints/hint {:at :symbol :keymap (select-keys km/shipped [:window :editor]) :os "Linux"}))))

(deftest at-caret-test
  (is (= :symbol (hints/at-caret "(defn foo [x] (inc x))" 15)))
  (is (nil? (hints/at-caret "(defn foo [x] \"a string\")" 18)) "a string")
  (is (nil? (hints/at-caret "; a comment" 4)))
  (is (nil? (hints/at-caret "" 0))))

(deftest shadowed-chord-test
  (let [km {:editor [{:key "q" :mods #{:ctrl} :command :plugin/thing}
                     {:key "q" :mods #{:ctrl} :command :help/quick-doc}
                     {:key "F1" :exact #{:shift} :command :help/quick-doc}
                     {:key "F12" :exact #{} :when :never :command :help/jump}]
            :window []}]
    (is (= "Shift+F1 doc" (hints/hint {:at :symbol :keymap km :os "Linux"}))
        "Ctrl+Q runs the plugin's command first, and a :when binding may not apply")))

(deftest line-at-test
  (is (= ["(inc x)" 2] (hints/line-at "(defn f [x]\n(inc x)\n)" 14)) "the caret's line and its offset in it")
  (is (= ["" 0] (hints/line-at "" 0))))

