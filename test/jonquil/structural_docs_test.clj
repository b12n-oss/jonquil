(ns jonquil.structural-docs-test
  "docs/guide/structural-editing.md shows a before and an after for every
  structural editing command. This runs each row against the real command,
  so the page can't say something the command doesn't do, and checks each
  row's Key column against the Calva paredit key set."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jonquil.command-registry :as registry]
            [jonquil.edit :as edit]
            [jonquil.key-sets :as key-sets]
            [jonquil.structural :as se]))

(def ^:private page "docs/guide/structural-editing.md")

(def ^:private commands
  "Structural command id by its description without the full stop, as the
  page's first column writes it."
  (into {} (for [[id c] registry/editor-commands :when (= "paredit" (namespace id))]
             [(str/replace (:doc c) #"\.$" "") id])))

(defn- rows
  "[title before after key] for each example row of the page."
  []
  (for [line (str/split-lines (slurp page))
        :let [[_ title key before after] (re-matches #"\| (.+?) \| (.+?) \| `(.+)` \| `(.+)` \|" line)]
        :when title]
    [title before after key]))

(defn- chord-text
  "A set's binding the way the page writes it: Ctrl, Alt, Shift and Cmd in
  that order, then the key."
  [{:keys [key exact]}]
  (str/join "+" (concat (keep (fn [[m word]] (when (contains? exact m) word))
                              [[:ctrl "Ctrl"] [:alt "Alt"] [:shift "Shift"] [:meta "Cmd"]])
                        [(get {"period" "." "comma" "," "BackSpace" "Backspace"} key
                              (if (= 1 (count key)) (str/upper-case key) key))])))

(defn- key-cell
  "The page's Key column for command id: the Calva set's keys that are the
  same on both systems, then the ones only a Mac or only Linux has, or
  none."
  [id]
  (let [on (fn [os] (->> (key-sets/bindings :calva :editor os)
                         (filter #(= id (:command %)))
                         (map chord-text)))
        mac (on "Mac OS X")
        linux (on "Linux")
        both (filter (set linux) mac)
        only (fn [word ks] (when-let [ks (seq (remove (set both) ks))]
                             (str word ": " (str/join ", " ks))))
        parts (remove nil? [(when (seq both) (str/join ", " both))
                            (only "Mac" mac) (only "Linux" linux)])]
    (if (seq parts) (str/join "; " parts) "none")))

(defn- snapshot
  "The snapshot an example describes: ‸ is the caret, «…» a selection."
  [marked]
  (if (str/includes? marked "«")
    (let [a (str/index-of marked "«")
          b (dec (str/index-of marked "»"))]
      {:text (-> marked (str/replace "«" "") (str/replace "»" "")) :caret b :sel-start a :sel-end b})
    (let [c (str/index-of marked "‸")]
      {:text (str/replace marked "‸" "") :caret c :sel-start c :sel-end c})))

(defn- shown
  "What op makes of snapshot s, written the way the page writes it."
  [s {:keys [edits caret sel]}]
  (let [text (edit/apply-edits (:text s) (or edits []))
        caret (or caret (:caret s))
        [a b] sel]
    (if (and a b (< a b))
      (str (subs text 0 a) "«" (subs text a b) "»" (subs text b))
      (str (subs text 0 caret) "‸" (subs text caret)))))

(defn- run-example
  "The command id's result on before, as the page would show it. Shrink
  selection only goes back from an Expand selection, so its example is
  reached by expanding from its own After first."
  [id before after]
  (let [f (ns-resolve 'jonquil.structural (symbol (name id)))]
    (se/forget-selection!)
    (if (= :paredit/shrink-selection id)
      (do (se/expand-selection (snapshot after))
          (shown (snapshot before) (se/shrink-selection (snapshot before))))
      (shown (snapshot before) (f (snapshot before))))))

(deftest every-example-on-the-page-is-what-the-command-does-test
  (doseq [[title before after _key] (rows)]
    (let [id (get commands title)]
      (is (some? id) (str "the page names a command the registry doesn't have: " title))
      (when id
        (is (= after (run-example id before after)) (str title ": " before))))))

(deftest the-page-covers-every-structural-command-test
  (is (= (set (vals commands)) (set (keep (comp commands first) (rows)))))
  (is (= (count commands) (count (rows))) "one example a command"))

(deftest the-key-column-is-the-calva-set-test
  (doseq [[title _before _after key] (rows)]
    (when-let [id (get commands title)]
      (is (= (key-cell id) key) title))))
