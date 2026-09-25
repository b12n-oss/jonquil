(ns jonquil.paint
  "Turns buffer text into a paint plan: style spans a front-end applies as
  tags. Pure. Mirrors upstream DrClojure.syntax/highlight-doc! (41c56b8) with
  the Swing document replaced by data: token styles first, then rainbow and
  unmatched brackets on top, so a later span wins where two overlap."
  (:require [jonquil.syntax :as syntax]
            [jonquil.theme :as theme]))

(def token-styles
  ;; Each token kind's bold/italic flags (upstream default-palette's, at
  ;; 41c56b8). Colours come from the active theme (jonquil.theme). :symbol
  ;; and :bracket are absent on purpose: highlight-doc! leaves plain symbols
  ;; at the default style and paints brackets only through the rainbow pass.
  {:comment      {:italic true}
   :string       {}
   :regex        {}
   :char         {}
   :keyword      {:bold true}
   :number       {}
   :special-form {:bold true}
   :builtin      {}
   :constant     {:bold true}
   :metatag      {:italic true}})

(def rainbow-count
  ;; Upstream cycles rainbow brackets by depth mod 7.
  7)

(defn style
  "The style map for a paint-plan key under theme t (a jonquil.theme map):
  :fg from the theme, :bold/:italic from the kind."
  [t k]
  (cond
    (= k :unmatched) {:fg (:red t) :bold true}
    (and (vector? k) (= :rainbow (first k))) {:fg (nth (:rainbow t) (second k)) :bold true}
    :else (when-let [flags (get token-styles k)]
            (assoc flags :fg (theme/token-color t k)))))

(defn style-at
  "The effective style map at offset pos under theme t, or nil for the
  default style."
  [t plan pos]
  (some (fn [[k s e]] (when (and (<= s pos) (< pos e)) (style t k)))
        (rseq plan)))

(defn paint-plan
  "Returns a vector of [style-key start end]. Style keys are token types,
  [:rainbow n] with n in 0..6, or :unmatched."
  [text]
  (if (or (nil? text) (= "" text))
    []
    (let [tokens (into []
                       (keep (fn [[t s e]] (when (contains? token-styles t) [t s e])))
                       (syntax/tokenize text))
          n rainbow-count
          brackets (mapv (fn [[s e depth kind]]
                           [(if (= kind :unmatched) :unmatched [:rainbow (mod depth n)]) s e])
                         (:bracket-tokens (syntax/compute-brackets text)))]
      (into tokens brackets))))
