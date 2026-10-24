(ns jonquil.keymap-window-oracle-test
  "The Part 1 differential gate (plugins design): the shipped keymap must
  do what the cond clauses it replaced in jonquil.gtk.window/on-window-key
  did, for every key they name (and a few they don't) under every modifier
  combination and condition state. One oracle per slice: legacy-block (S1),
  legacy-slot (S2, on top of S1), legacy-escape (S3, on top of S2) and
  legacy-window (S4, the whole handler, on top of S3).

  legacy-block is S1's block transcribed from window.clj at 82bf741
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
  ;; bracketleft (the source panel's back chord); F5 and bracketleft are
  ;; keymap bindings since S4, checked by keymap-matches-legacy-window-test
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

(def ^:private KEY-ESCAPE 0xff1b)

;; S1's and S2's matrices ask resolve-binding for the first match, which
;; isn't what runs once outcomes exist (slice S3). Escape, F5 and
;; bracketleft are bindings of their own now (S3, S4), so these matrices
;; leave them out; keymap-matches-legacy-window-test checks them, and every
;; other key, through window-answer.
(def ^:private slot-keyvals (remove #{KEY-ESCAPE 0xffc2 0x5b} keyvals))

(deftest keymap-matches-legacy-test
  (is (= 32 (count (distinct states))))
  (doseq [kv slot-keyvals st states]
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
  (doseq [kv (concat slot-keyvals [0xffc0 0xffc3]) st states cs condition-states]
    (let [old (legacy-slot kv st cs)
          new (some-> (km/resolve-binding (:window km/shipped) kv st (holds-for cs))
                      ((juxt :command #(or (:args %) {}))))]
      (is (= old new)
          (str "keyval " (format "0x%x" kv) " state " st " " (pr-str cs)
               ": legacy " (pr-str old) " keymap " (pr-str new))))))

;; --- slice S3: outcomes, and the Escape chain above S2 ---------------------

(defn- legacy-escape
  "Slice S3's oracle: on-window-key's Escape chain (window.clj at a1fe230),
  from the find bar's clause through the bare-Escape 0, then S2's
  legacy-slot. Answers [outcome calls]: outcome is :handled (the old 1),
  :to-gtk (the old 0 from the bar or bare Escape) or :unmatched (on to the
  clauses below the slot), and calls are the [command-id args] whose
  action ran, in order. The bar's handle-key! and close-popovers-fn are
  opaque here: cs gives their answers (bar-answer-1?, popover-closed?), and
  close-popovers-fn counts as a call whenever its clause is reached, since
  the old cond ran it to find out. tree? false is a window with no
  tree-keys."
  [keyval state {:keys [bar-focused? bar-answer-1? popover-closed? inspector-focused?
                        source-focused? tree? tree-focused? tree-menu-open? evaluating?]
                 :as cs}]
  (let [pop [:popover/close-all {}]]
    (cond
      (and (= keyval KEY-ESCAPE) bar-focused?)
      [(if bar-answer-1? :handled :to-gtk) [[:find/bar-key {}]]]
      (and (= keyval KEY-ESCAPE) popover-closed?) [:handled [pop]]
      (and (= keyval KEY-ESCAPE) inspector-focused?) [:handled [pop [:inspector/close {}]]]
      (and (= keyval KEY-ESCAPE) source-focused?) [:handled [pop [:source/close {}]]]
      (and (= keyval KEY-ESCAPE) tree? tree-focused? tree-menu-open?)
      [:handled [pop [:tree/close-menu {}]]]
      (and (= keyval KEY-ESCAPE) tree? tree-focused?) [:handled [pop [:tree/leave {}]]]
      (and (= keyval KEY-ESCAPE) evaluating?) [:handled [pop [:run/stop {}]]]
      (= keyval KEY-ESCAPE) [:to-gtk [pop]]
      :else (if-let [call (legacy-slot keyval state cs)]
              [:handled [call]]
              [:unmatched []]))))

(def ^:private escape-flags
  [:bar-focused? :bar-answer-1? :popover-closed? :inspector-focused?
   :tree? :tree-focused? :tree-menu-open? :evaluating?])

(defn- flag-states [flags]
  (for [n (range (bit-shift-left 1 (count flags)))]
    (into {} (map-indexed (fn [i f] [f (bit-test n i)]) flags))))

(defn- fake-ctx
  "The per-key ctx the registry's conditions read, faked from cs, so the
  matrix asks the real window-conditions (tree-keys nil included)."
  [{:keys [preedit? open? source-focused? bar-focused? inspector-focused?
           tree? tree-focused? tree-menu-open? evaluating?]}]
  {:find-bar-navigable? (constantly (and open? (not preedit?)))
   :source-keys {:focused? (constantly source-focused?)}
   :find-bar-focused? (constantly bar-focused?)
   :inspector-focused? (constantly inspector-focused?)
   :tree-keys (when tree? {:focused? (constantly tree-focused?)
                           :menu-open? (constantly tree-menu-open?)})
   :evaluating? (constantly evaluating?)})

(defn- keymap-window
  "The keymap's answer for the same cell: window-answer, which is what
  on-window-key calls, over the shipped :window vector with the real
  conditions, and a run! that records each command it runs and answers
  what the command would: the bar's 1 or 0, the popover close's
  closed-something or :next, the jump back's went-back or :next, and
  :handled for the rest."
  [keyval state {:keys [bar-answer-1? popover-closed? jumped? rename-focused?] :as cs}]
  (let [calls (atom [])
        run! (fn [b]
               (swap! calls conj [(:command b) (or (:args b) {})])
               (case (:command b)
                 :find/bar-key (if bar-answer-1? :handled :to-gtk)
                 :popover/close-all (if popover-closed? :handled :next)
                 :source/jump-back (if jumped? :handled :next)
                 :handled))
        ctx (assoc (fake-ctx cs) :rename-focused? (constantly rename-focused?))
        answer (registry/window-answer (:window km/shipped) keyval state
                                       {:holds? (registry/holds-fn registry/window-conditions ctx)
                                        :run! run!})]
    [answer @calls]))

;; --- slice S4: the rename guard, F5 and Cmd/Ctrl+[; no cond left ----------

(defn- legacy-window
  "Slice S4's oracle: the whole of on-window-key's cond at 9f5ba46, the
  rename guard, F5, the keymap slot (S3's legacy-escape, on top of S2 and
  S1) and the two Cmd/Ctrl+[ clauses below it, answering [answer calls]
  with answer the old 1 or 0. jumped? is the jump-back fn's answer; an
  absent :jump-back! passes the key on the same way, which the registry
  test covers."
  [keyval state {:keys [rename-focused? source-focused? jumped?] :as cs}]
  (cond
    (and (not= keyval KEY-ESCAPE) rename-focused?) [0 []]
    (= keyval 0xffc2) [1 [[:run/run {}]]]
    :else
    (let [[outcome calls] (legacy-escape keyval state cs)]
      (case outcome
        :handled [1 calls]
        :to-gtk [0 calls]
        (cond
          (and (command-chord? state) (= keyval 0x5b) source-focused?) [1 [[:source/back {}]]]
          (and (command-chord? state) (= keyval 0x5b)) [(if jumped? 1 0) [[:source/jump-back {}]]]
          :else [0 []])))))

(def ^:private s4-flags [:rename-focused? :jumped?])

(defn- mismatches [cells]
  (keep (fn [[kv st cs]]
          (let [old (legacy-window kv st cs) new (keymap-window kv st cs)]
            (when (not= old new)
              (str "keyval " (format "0x%x" kv) " state " st " " (pr-str cs)
                   ": legacy " (pr-str old) " keymap " (pr-str new)))))
        cells))

(deftest keymap-matches-legacy-window-test
  ;; Escape crossed with every modifier subset, every Escape flag, S2's
  ;; condition states and S4's two flags; then every other key with the
  ;; Escape flags all off and all on, which must not leak into them.
  ;; Replaces S3's Escape-only test, which stopped at dispatch.
  (let [esc-flags (flag-states escape-flags)
        s4 (flag-states s4-flags)
        escape-cells (for [st states ef esc-flags cs condition-states f s4]
                       [KEY-ESCAPE st (merge cs ef f)])
        all-off (zipmap escape-flags (repeat false))
        all-on (zipmap escape-flags (repeat true))
        other-cells (for [kv (concat (remove #{KEY-ESCAPE} keyvals) [0xffc0 0xffc3]) st states
                          cs condition-states ef [all-off all-on] f s4]
                      [kv st (merge cs ef f)])
        t0 (System/nanoTime)
        bad (vec (mismatches (concat escape-cells other-cells)))
        ms (quot (- (System/nanoTime) t0) 1000000)]
    (println (str "keymap-matches-legacy-window-test: "
                  (+ (count escape-cells) (count other-cells)) " cells in " ms " ms"))
    (is (= 256 (count esc-flags)))
    (is (= 262144 (count escape-cells)))
    (is (zero? (count bad)) (str (count bad) " mismatches, first: " (pr-str (take 3 bad))))))

(deftest shipped-keymap-resolves-test
  (is (= [] (km/problems (:window km/shipped) (set (keys registry/window-commands))
                         (set (keys registry/window-conditions))))))

(deftest format-chords-pass-through-test
  ;; Review Focus 2: Cmd+Alt+L and Cmd+Alt+F belong to the editor
  (let [ctrl-alt (bit-or (:ctrl M) (:alt M))]
    (is (nil? (keymap-answer 0x06c ctrl-alt)))
    (is (nil? (keymap-answer 0x066 ctrl-alt)))))
