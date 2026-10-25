(ns jonquil.keymap-editor-oracle-test
  "Slice S5's differential gate (plugins design, Part 1): the shipped
  :editor keymap must do what the eleven chord clauses it replaced in
  jonquil.gtk.editor/handle-key! did, for every key they name (and a few
  they don't), over keycodes, both platforms, every modifier subset and
  every command answer.

  legacy-chords is transcribed from editor.clj at 1866034, the clauses from
  Ctrl+Space through F2, with these substitutions: each action becomes its
  [outcome [command-id]], the action's 1/0 read from cs; control-only?,
  shift-only?, no-modifiers?, command-chord?, shift-held? and alt-held?
  become the same bit tests over jonquil.keymap/MODIFIER-MASKS (the ffi's
  masks, which a window self-test row pins); alt-chord-f-or-l? takes os
  and calls the public editor/keycode-fallback?; KEY-* become editor.clj's
  literal values. :unmatched is the old fall-through to the typing
  clauses. Needs GTK4 installed (jonquil.gtk.editor), no display."
  (:require [clojure.test :refer [deftest is]]
            [jonquil.command-registry :as registry]
            [jonquil.gtk.editor :as editor]
            [jonquil.keymap :as km]))

(def ^:private M km/MODIFIER-MASKS)

(defn- bits? [state & ms] (not (zero? (bit-and state (reduce bit-or (map M ms))))))

(defn- command-chord? [state] (bits? state :ctrl :meta))
(defn- shift-held? [state] (bits? state :shift))
(defn- alt-held? [state] (bits? state :alt))
(defn- control-only? [state] (and (bits? state :ctrl) (not (bits? state :meta))))
(defn- shift-only? [state] (and (bits? state :shift) (not (bits? state :meta :ctrl :alt))))
(defn- no-modifiers? [state] (not (bits? state :shift :ctrl :meta :alt)))

(defn- alt-chord-f-or-l? [os keyval keycode state]
  (or (contains? #{0x066 0x06c} keyval)
      (and (< keyval 0x1000) (editor/keycode-fallback? os keycode state))))

(defn- legacy-chords
  [keyval keycode state os {:keys [edit-1? can-redo? writable?]}]
  (let [answer (fn [one?] (if one? :handled :to-gtk))]
    (cond
      (and (control-only? state) (= keyval 0x020)) [:handled [:completion/open]]
      (and (command-chord? state) (contains? #{0x02f 0x03b} keyval)) [(answer edit-1?) [:edit/toggle-comment]]
      (and (command-chord? state) (shift-held? state) (= keyval 0x066)) [(answer edit-1?) [:edit/format-all]]
      (and (command-chord? state) (alt-held? state) (alt-chord-f-or-l? os keyval keycode state))
      [(answer edit-1?) [:edit/format-selection]]
      (and (command-chord? state) (= keyval 0x079) writable?) [(answer can-redo?) [:edit/redo]]
      (and (control-only? state) (= keyval 0x071)) [:handled [:help/quick-doc]]
      (and (shift-only? state) (= keyval 0xffbe)) [:handled [:help/quick-doc]]
      (and (no-modifiers? state) (= keyval 0xffc9)) [:handled [:help/jump]]
      (and (control-only? state) (= keyval 0x062)) [:handled [:help/jump]]
      (and (shift-only? state) (= keyval 0xffc3)) [:handled [:edit/rename]]
      (and (no-modifiers? state) (= keyval 0xffbf)) [:handled [:edit/rename]]
      :else [:unmatched []])))

(defn- fake-ctx
  "The editor ctx's thunks, answering from cs the way the real ones answer
  from the buffer: 1 or 0 for the edits and redo, nil for the rest."
  [{:keys [edit-1? can-redo? writable?]}]
  (let [one (fn [b] (constantly (if b 1 0)))]
    {:complete! (constantly nil) :toggle-comment! (one edit-1?) :format-all! (one edit-1?)
     :format-selection! (one edit-1?) :redo! (one can-redo?) :writable? (constantly writable?)
     :quick-doc! (constantly nil) :jump! (constantly nil) :rename! (constantly nil)}))

(defn- keymap-chords [keyval keycode state os cs]
  (let [calls (atom [])
        ctx (fake-ctx cs)
        outcome (km/dispatch (:editor km/shipped) keyval state
                             {:holds? (registry/holds-fn registry/editor-conditions ctx)
                              :run! (fn [b] (swap! calls conj (:command b))
                                      (registry/run-command! registry/editor-commands b ctx))
                              :physical (editor/physical-key os keyval keycode state)})]
    [outcome @calls]))

(def ^:private keyvals
  ;; every keyval the clauses name; the macOS Option-translated f and l;
  ;; Control_L (keycode 37 on Linux); then some they don't name
  [0x020 0x02f 0x03b 0x066 0x06c 0x079 0x071 0x062 0xffbe 0xffc9 0xffc3 0xffbf
   0x8f6 0x0ac 0xffe3
   0x061 0xff0d 0x028 0xff09 0xff08])

(def ^:private states
  ;; all 32 subsets of the five modifiers
  (let [bits (vals M)]
    (for [n (range 32)]
      (reduce bit-or 0 (keep-indexed (fn [i b] (when (bit-test n i) b)) bits)))))

(def ^:private command-states
  (for [e [false true] r [false true] w [false true]]
    {:edit-1? e :can-redo? r :writable? w}))

(deftest keymap-matches-legacy-chords-test
  (let [cells (for [kv keyvals kc [0 3 37] os ["Mac OS X" "Linux"] st states cs command-states]
                [kv kc st os cs])
        t0 (System/nanoTime)
        bad (vec (keep (fn [[kv kc st os cs]]
                         (let [old (legacy-chords kv kc st os cs) new (keymap-chords kv kc st os cs)]
                           (when (not= old new)
                             (str "keyval " (format "0x%x" kv) " keycode " kc " state " st " " os " "
                                  (pr-str cs) ": legacy " (pr-str old) " keymap " (pr-str new)))))
                       cells))
        ms (quot (- (System/nanoTime) t0) 1000000)]
    (println (str "keymap-matches-legacy-chords-test: " (count cells) " cells in " ms " ms"))
    (is (= 32 (count (distinct states))))
    (is (= 8 (count command-states)))
    (is (zero? (count bad)) (str (count bad) " mismatches, first: " (pr-str (take 3 bad))))))

(deftest shipped-editor-keymap-resolves-test
  (is (= [] (km/problems (:editor km/shipped) (set (keys registry/editor-commands))
                         (set (keys registry/editor-conditions))))))
