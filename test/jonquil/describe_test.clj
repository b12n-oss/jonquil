(ns jonquil.describe-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.command-registry :as registry]
            [jonquil.describe :as d]
            [jonquil.keymap :as km]))

(def ^:private M km/MODIFIER-MASKS)

(defn- mac-matches [key-name state]
  (d/key-matches {:keymap km/shipped :commands registry/commands
                  :keyval (km/key-value key-name) :state state}))

(deftest chord-text-test
  (is (= "Cmd+S" (d/chord-text "s" (:meta M) "Mac OS X")))
  (is (= "Ctrl+S" (d/chord-text "s" (:ctrl M) "Linux")))
  (is (= "Cmd+Shift+P" (d/chord-text "p" (bit-or (:meta M) (:shift M)) "Mac OS X")))
  (is (= "F6" (d/chord-text "F6" 0 "Mac OS X"))))

(deftest modifier-key-test
  (is (d/modifier-key? "Shift_L"))
  (is (d/modifier-key? "Meta_R"))
  (is (not (d/modifier-key? "s")))
  (is (not (d/modifier-key? "F6"))))

(deftest key-matches-test
  (testing "Cmd+S is the window's Save"
    (let [ms (mac-matches "s" (:meta M))]
      (is (= [[:window :file/save]] (map (juxt :scope :command) ms)))
      (is (= "built-in" (:source (first ms))))))
  (testing "Escape walks the window chain in shipped order, conditions kept"
    (let [ms (mac-matches "Escape" 0)]
      (is (= [:find/bar-key [:find-bar-focused]] ((juxt :command :when) (first ms))))
      (is (= :to-gtk (:outcome (last ms)))))))

(deftest describe-lines-test
  (is (some #(str/includes? % "runs Save the buffer. (:file/save, built-in, window)")
            (d/describe-lines "Cmd+S" (mac-matches "s" (:meta M)))))
  (is (= ["; Cmd+K isn't bound: it goes to GTK (typed text, or the focused widget's own key)."]
         (d/describe-lines "Cmd+K" (mac-matches "k" (:meta M)))))
  (let [lines (d/describe-lines "Esc" (mac-matches "Escape" 0))]
    (is (str/includes? (first lines) "in order, the first that applies wins:"))
    (is (some #(str/includes? % "only when find-bar-focused") lines))
    (is (some #(str/includes? % "goes to GTK here (window)") lines))))

(deftest remember-test
  (let [ring (reduce d/remember [] (map (fn [i] {:chord (str "F" i) :command nil}) (range 60)))]
    (is (= d/RECENT-MAX (count ring)))
    (is (= "F59" (:chord (peek ring)))))
  (is (= [{:chord "F6" :command :focus/toggle} {:typing 3}]
         (reduce d/remember [] [{:chord "F6" :command :focus/toggle}
                                {:typing 1} {:typing 1} {:typing 1}]))))

(deftest recent-entry-test
  (is (= {:typing 1} (d/recent-entry "a" 0 "Mac OS X" [])))
  (is (= {:typing 1} (d/recent-entry "A" (:shift M) "Mac OS X" [])))
  (is (= {:chord "F6" :command :focus/toggle}
         (d/recent-entry "F6" 0 "Mac OS X" (mac-matches "F6" 0))))
  (is (= {:chord "Cmd+S" :command :file/save}
         (d/recent-entry "s" (:meta M) "Mac OS X" (mac-matches "s" (:meta M))))))

(deftest recent-lines-test
  (is (= ["; No keys yet."] (d/recent-lines [])))
  (is (= ["; Recent keys, oldest first:" "; F6  :focus/toggle" "; typing (2 keys)" "; Cmd+K  unbound"]
         (d/recent-lines [{:chord "F6" :command :focus/toggle} {:typing 2} {:chord "Cmd+K" :command nil}]))))

(deftest binding-lines-test
  (let [lines (d/binding-lines {:keymap km/shipped :commands registry/commands :os "Mac OS X"})]
    (is (= "; Window keys" (first lines)))
    (is (some #{"; Editor keys"} lines))
    (is (some #{"; Cmd+Shift+P  Show the command palette.  built-in"} lines))
    (is (some #{"; Cmd+S  Save the buffer.  built-in"} lines))
    (testing "no line for an :outcome binding (Escape's last, to GTK)"
      (is (not-any? #(re-find #"^; Esc  " %) (remove #(str/includes? % "  built-in") lines))))))
