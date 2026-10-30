(ns jonquil.keymap-shipped-test
  "The shipped keymap's own pins. When the key handling moved from
  hand-written clauses to the keymap, each step was gated by a
  differential oracle against the clauses it replaced; what those oracles
  checked now lives here and in the window self-test
  rows: every binding names a registered command and condition, the editor's format chords pass the window by, the palette chord,
  and what only an oracle had checked: no editor binding takes a plain
  Return (what let smart enter move into the typing stages), Run Form
  takes Cmd/Ctrl+Enter whatever else is held, the Escape chain's order and
  conditions (S3), no window binding takes an editor chord, what each
  window chord runs (Shift forms ahead of plain ones), F5 under any
  modifier, and each context-menu item's command (S7)."
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

(defn- key-names [b] (if (:key b) [(:key b)] (seq (:keys b))))

(defn- command-chord? [state]
  (not (zero? (bit-and state (bit-or (:ctrl M) (:meta M))))))

(deftest shipped-keymap-resolves-test
  (is (= [] (km/problems (:window km/shipped) (set (keys registry/window-commands))
                         (set (keys registry/window-conditions))))))

(deftest shipped-editor-keymap-resolves-test
  (is (= [] (km/problems (:editor km/shipped) (set (keys registry/editor-commands))
                         (set (keys registry/editor-conditions))))))

(deftest format-chords-pass-through-test
  ;; Cmd+Alt+L and Cmd+Alt+F belong to the editor
  (let [ctrl-alt (bit-or (:ctrl M) (:alt M))]
    (is (nil? (keymap-answer 0x06c ctrl-alt)))
    (is (nil? (keymap-answer 0x066 ctrl-alt)))))

(deftest palette-chord-test
  ;; step S8: Cmd/Ctrl+Shift+P opens the palette; P was bound nowhere before
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
  ;; each step and the conditions guarding it, in order (step S3)
  (is (= [[:find/bar-key [:find-bar-focused]] [:popover/close-all nil]
          [:inspector/close [:inspector-focused]] [:source/close [:source-focused]]
          [:tree/close-menu [:tree-focused :tree-menu-open]] [:tree/leave [:tree-focused]]
          [:run/stop [:evaluating]] [[:outcome :to-gtk] nil]]
         (for [b (:window km/shipped) :when (contains? (set (key-names b)) "Escape")]
           [(or (:command b) [:outcome (:outcome b)]) (:when b)]))))

(deftest window-never-takes-an-editor-chord-test
  ;; every chord an :editor binding takes reaches the editor: the window's
  ;; capture-phase handler runs first, so a :window binding matching the
  ;; same key and modifiers (Cmd+Shift+F, Format All, against Find's :not)
  ;; would take it. Conditions all hold, the worst case for the window.
  (doseq [b (:editor km/shipped) k (key-names b) st states
          :let [kv (km/key-value k)]
          :when (km/resolve-binding (:editor km/shipped) kv st (constantly true))]
    (is (nil? (km/resolve-binding (:window km/shipped) kv st (constantly true)))
        (str k " state " st))))

(defn- window-command [kv st holds]
  (some-> (km/resolve-binding (:window km/shipped) kv st holds)
          ((juxt :command #(or (:args %) {})))))

(deftest parinfer-chord-test
  ;; Cmd/Ctrl+Alt+P cycles the Parinfer mode (upstream's Ctrl+Alt+P). On
  ;; macOS Option+P arrives as pi (GDK_KEY_Greek_pi, 0x7f0, measured with
  ;; gdk_keyval_from_name 2026-10-29); :physical? matches it by keycode
  ;; through dispatch's :physical option, as Format Selection does.
  (doseq [cmd [(:ctrl M) (:meta M)]]
    (let [st (bit-or cmd (:alt M))]
      (is (= :parinfer/next-mode
             (:command (km/resolve-binding (:window km/shipped) 0x70 st (constantly false)))))
      (is (nil? (km/resolve-binding (:window km/shipped) 0x70 cmd (constantly false)))
          "without Alt, Cmd+P is nothing")
      (is (= :handled (km/dispatch (:window km/shipped) 0x7f0 st
                                   {:holds? (constantly false)
                                    :run! (fn [b] (when (= :parinfer/next-mode (:command b)) :handled))
                                    :physical "p"}))
          "the macOS pi keyval matches through :physical"))))

(deftest window-chords-test
  ;; what each window chord runs, under Control and under Meta; the Shift
  ;; forms sit ahead of their plain ones, which this pins pair by pair
  (doseq [cmd [(:ctrl M) (:meta M)]
          :let [sh (bit-or cmd (:shift M))]
          [kv st holds expected]
          [[0x72 cmd #{} [:run/run {}]]
           [0x6f sh #{} [:file/open {}]]
           [0x6f sh #{:source-focused} [:source/open-here {}]]
           [0x6f cmd #{:source-focused} [:file/open {}]]
           [0x73 sh #{} [:file/save-as {}]]
           [0x73 cmd #{} [:file/save {}]]
           [0x77 cmd #{} [:tab/close {}]]
           [0x65 sh #{} [:tree/toggle {}]]
           [0x65 cmd #{} [:run/selection {}]]
           [0x6c sh #{} [:console/clear-last-output {}]]
           [0x6c cmd #{} [:console/clear {}]]
           [0x66 cmd #{} [:find/open {}]]
           [0x68 cmd #{} [:find/open-replace {}]]
           [0x67 sh #{:find-bar-navigable} [:find/previous {}]]
           [0x67 cmd #{:find-bar-navigable} [:find/next {}]]
           [0x67 cmd #{} nil]
           [0x3d cmd #{} [:view/zoom-in {}]]
           [0x2b cmd #{} [:view/zoom-in {}]]
           [0x2d cmd #{} [:view/zoom-out {}]]
           [0x30 cmd #{} [:view/zoom-reset {}]]
           [0x33 cmd #{} [:tab/select {:n 3}]]
           [0x39 cmd #{} [:tab/select {:n 9}]]
           [0x74 cmd #{} [:tab/new {}]]
           [0x6d sh #{} [:view/cycle-theme {}]]
           [0x6d cmd #{} nil]
           [0x69 sh #{} [:inspect/form {}]]
           [0x69 cmd #{} [:inspect/last {}]]
           [0x7d cmd #{} [:tab/next {}]]
           [0x7b cmd #{} [:tab/previous {}]]
           [0x5b cmd #{:source-focused} [:source/back {}]]
           [0x5b cmd #{} [:source/jump-back {}]]]]
    (is (= expected (window-command kv st holds))
        (str (format "0x%x" kv) " state " st " " holds))))

(deftest f5-runs-whatever-the-modifiers-test
  (doseq [st states]
    (is (= [:run/run {}] (window-command 0xffc2 st #{})) (str "state " st))))

(deftest editor-menu-commands-test
  ;; each context-menu item runs this command (step S7's menu oracle
  ;; pinned it through the fn each item called)
  (is (= [["Run Form" :run/form] ["Jump to Definition" :help/jump] ["Rename Symbol" :edit/rename]
          ["Quick Documentation" :help/quick-doc] ["Inspect Last Result" :inspect/last]
          ["Inspect Form" :inspect/form] ["Complete" :completion/open]
          ["Toggle Comment" :edit/toggle-comment] ["Format All" :edit/format-all]
          ["Format Selection" :edit/format-selection] ["Indent" :edit/indent]
          ["Unindent" :edit/unindent] ["Find" :find/open] ["Replace" :find/open-replace]]
         (map (juxt :label :command) registry/editor-menu)))
  (is (= [["Complete" :console/complete] ["Clear Last Output" :console/clear-last-output]]
         (map (juxt :label :command) registry/console-menu))))
