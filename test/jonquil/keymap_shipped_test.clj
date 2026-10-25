(ns jonquil.keymap-shipped-test
  "The shipped keymap's own pins (plugins design, Part 1). Slices S1 to S8
  were each gated by a differential oracle, a transcription of the key
  clauses they replaced; S9 deleted those once every slice was merged
  green. What they checked now lives here and in the window self-test
  rows: every binding names a registered command and condition (the spec's
  pin), the editor's format chords pass the window by, the palette chord,
  and three properties only an oracle had checked, that no editor binding
  takes a plain Return (what let smart enter move into the typing stages,
  S6), that Run Form takes Cmd/Ctrl+Enter whatever else is held, and the
  Escape chain's order (S3)."
  (:require [clojure.test :refer [deftest is]]
            [jonquil.command-registry :as registry]
            [jonquil.keymap :as km]))

(def ^:private M km/MODIFIER-MASKS)

(def ^:private states
  ;; all 32 subsets of the five modifiers
  (let [bits (vals M)]
    (for [n (range 32)]
      (reduce bit-or 0 (keep-indexed (fn [i b] (when (bit-test n i) b)) bits)))))

(defn- keymap-answer [keyval state]
  (some-> (km/resolve-binding (:window km/shipped) keyval state)
          ((juxt :command #(or (:args %) {})))))

(defn- command-chord? [state]
  (not (zero? (bit-and state (bit-or (:ctrl M) (:meta M))))))

(deftest shipped-keymap-resolves-test
  (is (= [] (km/problems (:window km/shipped) (set (keys registry/window-commands))
                         (set (keys registry/window-conditions))))))

(deftest shipped-editor-keymap-resolves-test
  (is (= [] (km/problems (:editor km/shipped) (set (keys registry/editor-commands))
                         (set (keys registry/editor-conditions))))))

(deftest format-chords-pass-through-test
  ;; Review Focus 2: Cmd+Alt+L and Cmd+Alt+F belong to the editor
  (let [ctrl-alt (bit-or (:ctrl M) (:alt M))]
    (is (nil? (keymap-answer 0x06c ctrl-alt)))
    (is (nil? (keymap-answer 0x066 ctrl-alt)))))

(deftest palette-chord-test
  ;; slice S8: Cmd/Ctrl+Shift+P opens the palette; P was bound nowhere before
  (is (= [:palette/open {}] (keymap-answer 0x70 (bit-or (:ctrl M) (:shift M)))))
  (is (= [:palette/open {}] (keymap-answer 0x70 (bit-or (:meta M) (:shift M)))))
  (doseq [st [(:ctrl M) (:shift M) 0]]
    (is (nil? (keymap-answer 0x70 st)) (str "state " st))))

(deftest plain-return-never-bound-test
  ;; every condition holds, so no binding is skipped for one that doesn't
  (doseq [kv [0xff0d 0xff8d] st states :when (not (command-chord? st))]
    (is (= :unmatched (km/dispatch (:editor km/shipped) kv st
                                   {:holds? (constantly true) :run! (constantly :handled)}))
        (str "keyval " (format "0x%x" kv) " state " st))))

(deftest run-form-takes-any-command-chord-test
  (doseq [kv [0xff0d 0xff8d] st states :when (command-chord? st)]
    (is (= :run/form (:command (km/resolve-binding (:editor km/shipped) kv st (constantly true))))
        (str "keyval " (format "0x%x" kv) " state " st))))

(deftest escape-chain-order-test
  (is (= [:find/bar-key :popover/close-all :inspector/close :source/close
          :tree/close-menu :tree/leave :run/stop [:outcome :to-gtk]]
         (for [b (:window km/shipped) :when (= "Escape" (:key b))]
           (or (:command b) [:outcome (:outcome b)])))))
