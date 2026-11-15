(ns jonquil.terminal-colors
  "The terminal's colours and font, as plain data: the theme's own text and
  background, and one of two palettes of the 16 ANSI colours, by whether
  the theme is dark. The terminal engine's own palette is made for a black
  or a white background and reads badly on a theme's: dark blue on a dark
  theme, bright yellow on a light one."
  (:require [clojure.string :as str]))

(def LIGHT
  "The 16 ANSI colours on a light theme: black, red, green, yellow, blue,
  magenta, cyan, white, then the bright eight. Each is dark enough to read
  on a light background, except bright white, which stays white for the
  programs that paint a background with it."
  ["#171421" "#c01c28" "#1a7f4b" "#8f5e00" "#12488b" "#a347ba" "#17727f" "#77767b"
   "#5e5c64" "#d6202a" "#1f8f55" "#9a6700" "#1a5fb4" "#9141ac" "#1b8594" "#ffffff"])

(def DARK
  "The 16 ANSI colours on a dark theme, in the same order. Each is light
  enough to read on a dark background, except black, which stays dark for
  the programs that paint a background with it."
  ["#241f31" "#f66151" "#33d17a" "#e9ad0c" "#62a0ea" "#d07ad9" "#33c7de" "#c0bfbc"
   "#9a9996" "#ff8a7a" "#57e389" "#f8e45c" "#99c1f1" "#dc8add" "#7fe3f2" "#f6f5f4"])

(defn background-end
  "The index of the one colour in a theme's palette that isn't meant to be
  read on the theme's background: black on a dark theme, bright white on a
  light one."
  [dark?]
  (if dark? 0 15))

(defn palette
  "The 16 ANSI colours for a theme that is dark, or isn't."
  [dark?]
  (if dark? DARK LIGHT))

(defn colors
  "What the terminal is given for theme t (a jonquil.theme map): its text
  on its background, and the palette for its kind."
  [{:keys [text background dark?]}]
  {:foreground text :background background :palette (palette dark?)})

(defn font
  "The terminal's font at the code views' size, px pixels: the same
  monospace font, as a Pango description."
  [px]
  (str "Monospace " px "px"))

(defn- channel [c]
  (if (<= c 0.03928) (/ c 12.92) (Math/pow (/ (+ c 0.055) 1.055) 2.4)))

(defn luminance
  "The relative luminance of a \"#rrggbb\" colour, from 0.0 to 1.0 (WCAG)."
  [hex]
  (let [h (str/replace hex #"^#" "")
        [r g b] (map #(channel (/ (Integer/parseInt (subs h % (+ % 2)) 16) 255.0)) [0 2 4])]
    (+ (* 0.2126 r) (* 0.7152 g) (* 0.0722 b))))

(defn contrast
  "The WCAG contrast ratio between two \"#rrggbb\" colours, from 1.0 to 21.0."
  [a b]
  (let [[hi lo] (sort > [(luminance a) (luminance b)])]
    (/ (+ hi 0.05) (+ lo 0.05))))
