(ns jonquil.paint
  "Turns buffer text into a paint plan: style spans a front-end applies as
  tags. Pure. Mirrors upstream DrClojure.syntax/highlight-doc! (41c56b8) with
  the Swing document replaced by data: token styles first, then rainbow and
  unmatched brackets on top, so a later span wins where two overlap."
  (:require [jonquil.syntax :as syntax]))

(def token-styles
  ;; Upstream default-palette, as hex. :symbol and :bracket are absent on
  ;; purpose: highlight-doc! leaves plain symbols at the default style and
  ;; paints brackets only through the rainbow pass.
  {:comment      {:fg "#6a737d" :italic true}
   :string       {:fg "#22863a"}
   :regex        {:fg "#b31d28"}
   :char         {:fg "#22863a"}
   :keyword      {:fg "#6f42c1" :bold true}
   :number       {:fg "#005cc5"}
   :special-form {:fg "#0000cd" :bold true}
   :builtin      {:fg "#005a9e"}
   :constant     {:fg "#0000cd" :bold true}
   :metatag      {:fg "#586069" :italic true}})

(def rainbow
  ;; Upstream rainbow-colors, all bold, cycled by depth mod 7.
  ["#c06800" "#007acc" "#7b1fa2" "#2e7d32" "#c62828" "#00838f" "#ad1457"])

(def unmatched-style {:fg "#dc0000" :bold true})

(defn style
  "The style map for a paint-plan key."
  [k]
  (cond
    (= k :unmatched) unmatched-style
    (and (vector? k) (= :rainbow (first k))) {:fg (nth rainbow (second k)) :bold true}
    :else (get token-styles k)))

(defn paint-plan
  "Returns a vector of [style-key start end]. Style keys are token types,
  [:rainbow n] with n in 0..6, or :unmatched."
  [text]
  (if (or (nil? text) (= "" text))
    []
    (let [tokens (into []
                       (keep (fn [[t s e]] (when (contains? token-styles t) [t s e])))
                       (syntax/tokenize text))
          n (count rainbow)
          brackets (mapv (fn [[s e depth kind]]
                           [(if (= kind :unmatched) :unmatched [:rainbow (mod depth n)]) s e])
                         (:bracket-tokens (syntax/compute-brackets text)))]
      (into tokens brackets))))

(defn style-at
  "The effective style map at offset pos, or nil for the default style."
  [plan pos]
  (some (fn [[k s e]] (when (and (<= s pos) (< pos e)) (style k)))
        (rseq plan)))
