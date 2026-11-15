(ns jonquil.terminal-keys-test
  (:require [clojure.test :refer [deftest is testing]]
            [jonquil.command-registry :as registry]
            [jonquil.gtk.parts.terminal :as terminal]
            [jonquil.keymap :as km]
            [jonquil.terminal-keys :as tk]))

(def ^:private M km/MODIFIER-MASKS)
(def ^:private mac "Mac OS X")
(def ^:private linux "Linux")

(defn- press
  ([n] (press n #{}))
  ([n mods] {:keyval (km/key-value n) :state (reduce bit-or 0 (map M mods))}))

(deftest the-shell-gets-its-keys-test
  ;; plain keys and Control keys are the terminal's on every system
  (doseq [os [mac linux]
          k [(press "a") (press "Return") (press "Escape") (press "Up") (press "BackSpace")
             (press "c" #{:ctrl}) (press "r" #{:ctrl}) (press "w" #{:ctrl}) (press "p" #{:ctrl})
             (press "f" #{:alt}) (press "a" #{:shift})]]
    (is (false? (tk/jonquils? k os)) (str os " " (pr-str k)))))

(deftest jonquil-keeps-a-few-test
  (doseq [os [mac linux]]
    (is (tk/jonquils? (press "F6") os) "F6 goes back to the editor")
    (is (tk/jonquils? (press "grave" #{:ctrl}) os) "Ctrl+` shows and leaves the terminal")
    (is (tk/jonquils? (press "3" #{:ctrl :alt}) os) "a panel key"))
  (testing "on a Mac, every key held with Cmd"
    (is (tk/jonquils? (press "w" #{:meta}) mac))
    (is (tk/jonquils? (press "p" #{:meta :shift}) mac))
    (is (tk/jonquils? (press "c" #{:meta}) mac))
    (is (tk/jonquils? {:keyval 0xa3 :state (bit-or (:meta M) (:alt M)) :physical "3"} mac)
        "Cmd+Option+3, whose keyval Option changes, by its physical key"))
  (testing "elsewhere, only keys held with Control and Shift"
    (is (tk/jonquils? (press "p" #{:ctrl :shift}) linux))
    (is (tk/jonquils? (press "c" #{:ctrl :shift}) linux))
    (is (false? (tk/jonquils? (press "w" #{:meta}) linux)))))

(deftest clipboard-action-test
  (is (= :copy (tk/clipboard-action (press "c" #{:meta}) mac)))
  (is (= :paste (tk/clipboard-action (press "v" #{:meta}) mac)))
  (is (nil? (tk/clipboard-action (press "c" #{:ctrl}) mac)) "Ctrl+C interrupts, on a Mac too")
  (is (= :copy (tk/clipboard-action (press "c" #{:ctrl :shift}) linux)))
  (is (= :paste (tk/clipboard-action (press "v" #{:ctrl :shift}) linux)))
  (is (nil? (tk/clipboard-action (press "c" #{:ctrl}) linux)) "Ctrl+C interrupts")
  (is (nil? (tk/clipboard-action (press "x" #{:ctrl :shift}) linux))))

(deftest a-focused-terminal-takes-the-key-from-the-window-test
  ;; window-answer passes a key on (0) when the terminal has the focus,
  ;; even one a window binding would take, and runs the few Jonquil keeps
  (let [ran (atom [])
        bindings [{:key "r" :mods #{:cmd} :command :run/run}
                  {:key "F6" :command :focus/toggle}]
        answer (fn [terminal? k]
                 (registry/window-answer bindings (:keyval k) (:state k)
                                         {:holds? (fn [id] (and terminal? (= :terminal-focused id)))
                                          :run! (fn [b] (swap! ran conj (:command b)) :handled)}))]
    (is (= 1 (answer false (press "r" #{:ctrl}))) "with the editor focused Ctrl+R runs")
    (is (= 0 (answer true (press "r" #{:ctrl}))) "with the terminal focused it is the shell's")
    (is (= 1 (answer true (press "F6"))) "F6 still switches focus")
    (is (= [:run/run :focus/toggle] @ran))))

(deftest unavailable-test
  (is (nil? (terminal/unavailable false nil)))
  (is (= "no VTE" (terminal/unavailable false "no VTE")))
  (is (nil? (terminal/unavailable true nil)) "a release app that carries VTE has a terminal")
  (is (re-find #"doesn't include the terminal" (terminal/unavailable true "no VTE"))
      "one that doesn't says so in its own words"))

(deftest bundled-vte-test
  (is (nil? (terminal/bundled-vte nil)) "not a release app")
  (is (= "/Apps/Jonquil.app/Contents/Frameworks/libvte-2.91-gtk4.0.dylib"
         (terminal/bundled-vte "/Apps/Jonquil.app/Contents/Frameworks"))))
