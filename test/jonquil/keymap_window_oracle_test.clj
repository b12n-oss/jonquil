(ns jonquil.keymap-window-oracle-test
  "The Part 1 differential gate for slice S1 (plugins design): the shipped
  keymap must pick the same command as the cond block it replaced in
  jonquil.gtk.window/on-window-key, for every key the block names (and a
  few it doesn't) under every modifier combination.

  legacy-block is that block transcribed from window.clj at 82bf741
  (identical at b49a276), Cmd/Ctrl+R through the KEY-BRACELEFT step
  clause, with only these substitutions: the Cmd/Ctrl+Shift+O source
  clause is dropped (it stays in on-window-key, ahead of the slot); each
  (do (f args) 1) becomes its [command-id args]; command-chord?,
  shift-held? and alt-held? become the same bit tests over
  jonquil.keymap/MODIFIER-MASKS; KEY-* become window.clj's literal values."
  (:require [clojure.test :refer [deftest is]]
            [jonquil.command-registry :as registry]
            [jonquil.keymap :as km]))

(def ^:private M km/MODIFIER-MASKS)

(defn- command-chord? [state]
  (not (zero? (bit-and state (bit-or (:ctrl M) (:meta M))))))

(defn- shift-held? [state] (not (zero? (bit-and state (:shift M)))))

(defn- alt-held? [state] (not (zero? (bit-and state (:alt M)))))

(defn- legacy-block [keyval state]
  (cond
    (and (command-chord? state) (= keyval 0x72)) [:run/run {}]
    (and (command-chord? state) (= keyval 0x6f)) [:file/open {}]
    (and (command-chord? state) (shift-held? state) (= keyval 0x73)) [:file/save-as {}]
    (and (command-chord? state) (= keyval 0x73)) [:file/save {}]
    (and (command-chord? state) (= keyval 0x77)) [:tab/close {}]
    (and (command-chord? state) (shift-held? state) (= keyval 0x065)) [:tree/toggle {}]
    (and (command-chord? state) (not (shift-held? state)) (= keyval 0x065)) [:run/selection {}]
    (and (command-chord? state) (not (alt-held? state)) (shift-held? state) (= keyval 0x06c))
    [:console/clear-last-output {}]
    (and (command-chord? state) (not (alt-held? state)) (= keyval 0x06c)) [:console/clear {}]
    (and (command-chord? state) (not (alt-held? state)) (not (shift-held? state)) (= keyval 0x066))
    [:find/open {}]
    (and (command-chord? state) (= keyval 0x068)) [:find/open-replace {}]
    (and (command-chord? state) (contains? #{0x03d 0x02b} keyval)) [:view/zoom-in {}]
    (and (command-chord? state) (= keyval 0x02d)) [:view/zoom-out {}]
    (and (command-chord? state) (= keyval 0x030)) [:view/zoom-reset {}]
    (and (command-chord? state) (<= 0x031 keyval 0x039))
    [:tab/select {:n (- keyval (dec 0x031))}]
    (and (command-chord? state) (= keyval 0x074)) [:tab/new {}]
    (and (command-chord? state) (shift-held? state) (= keyval 0x06d)) [:view/cycle-theme {}]
    (and (command-chord? state) (shift-held? state) (= keyval 0x069)) [:inspect/form {}]
    (and (command-chord? state) (= keyval 0x069)) [:inspect/last {}]
    (and (command-chord? state) (= keyval 0x07d)) [:tab/next {}]
    (and (command-chord? state) (= keyval 0x07b)) [:tab/previous {}]
    :else nil))

(def ^:private keyvals
  ;; every keyval the block names, then some it doesn't: a, g, F5, Escape,
  ;; bracketleft (the source panel's back chord, kept in on-window-key)
  (concat [0x72 0x6f 0x73 0x77 0x065 0x06c 0x066 0x068 0x03d 0x02b 0x02d 0x030]
          (range 0x031 0x03a)
          [0x074 0x06d 0x069 0x07d 0x07b]
          [0x61 0x67 0xffc2 0xff1b 0x05b]))

(def ^:private states
  ;; all 32 subsets of the five modifiers
  (let [bits (vals M)]
    (for [n (range 32)]
      (reduce bit-or 0 (keep-indexed (fn [i b] (when (bit-test n i) b)) bits)))))

(defn- keymap-answer [keyval state]
  (some-> (km/resolve-binding (:window km/shipped) keyval state)
          ((juxt :command #(or (:args %) {})))))

(deftest keymap-matches-legacy-test
  (is (= 32 (count (distinct states))))
  (doseq [kv keyvals st states]
    (let [old (legacy-block kv st) new (keymap-answer kv st)]
      (is (= old new)
          (str "keyval " (format "0x%x" kv) " state " st ": legacy " (pr-str old) " keymap " (pr-str new))))))

;; --- slice S2: the three clauses above the S1 block -----------------------

(defn- legacy-slot
  "Slice S2's oracle: the find-nav, F6 and Cmd+Shift+O-source clauses that
  sat directly above the S1 slot in on-window-key (window.clj at 6f3909b),
  then the S1 block. The find-nav clause is find-bar/handle-nav-key!'s own
  body inlined (find_bar.clj at 6f3909b): preedit? and open? are its two
  guards. Same substitution rules as legacy-block."
  [keyval state {:keys [preedit? open? source-focused?]}]
  (or (cond
        preedit? nil
        (not open?) nil
        (and (= keyval 0xffc0) (shift-held? state)) [:find/previous {}]
        (= keyval 0xffc0) [:find/next {}]
        (and (command-chord? state) (shift-held? state) (= keyval 0x67)) [:find/previous {}]
        (and (command-chord? state) (= keyval 0x67)) [:find/next {}]
        :else nil)
      (cond
        (and (= keyval 0xffc3) (not (shift-held? state))) [:focus/toggle {}]
        (and (command-chord? state) (shift-held? state) (= keyval 0x6f) source-focused?)
        [:source/open-here {}]
        :else (legacy-block keyval state))))

(def ^:private condition-states
  (for [p [false true] o [false true] f [false true]]
    {:preedit? p :open? o :source-focused? f}))

(defn- holds-for [{:keys [preedit? open? source-focused?]}]
  {:find-bar-navigable (and open? (not preedit?))
   :source-focused source-focused?})

(deftest keymap-matches-legacy-slot-test
  (is (= 8 (count condition-states)))
  (doseq [kv (concat keyvals [0xffc0 0xffc3 0x67]) st states cs condition-states]
    (let [old (legacy-slot kv st cs)
          new (some-> (km/resolve-binding (:window km/shipped) kv st (holds-for cs))
                      ((juxt :command #(or (:args %) {}))))]
      (is (= old new)
          (str "keyval " (format "0x%x" kv) " state " st " " (pr-str cs)
               ": legacy " (pr-str old) " keymap " (pr-str new))))))

(deftest shipped-keymap-resolves-test
  (is (= [] (km/problems (:window km/shipped) (set (keys registry/window-commands))
                         (set (keys registry/window-conditions))))))

(deftest format-chords-pass-through-test
  ;; Review Focus 2: Cmd+Alt+L and Cmd+Alt+F belong to the editor
  (let [ctrl-alt (bit-or (:ctrl M) (:alt M))]
    (is (nil? (keymap-answer 0x06c ctrl-alt)))
    (is (nil? (keymap-answer 0x066 ctrl-alt)))))
