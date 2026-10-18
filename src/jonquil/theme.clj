(ns jonquil.theme
  "Jonquil's colour themes as data. Pure. The four palettes come from
  a sibling ClojureDart deck app's theme (palettes and
  syntax-palettes, read 2026-09-25): each Jonquil token kind takes the
  colour of the slides class its tokenizer uses for the same text. The
  rainbow, :red, :find-highlight, :selection and :caret values are
  Jonquil's own, picked from each palette; :red is never in :rainbow, so
  an unmatched bracket never looks like a matched one.")

(def themes
  {:light
   {:name :light :dark? false
    :background "#ffffff" :text "#24292f" :caret "#0066cc"
    :selection "#b6d7ff" :find-highlight "#fff2a8" :red "#cf222e"
    :tokens {:comment "#8c95a0" :string "#0a3069" :char "#0a3069"
             :regex "#9a6700" :keyword "#cf222e" :special-form "#cf222e"
             :number "#0550ae" :builtin "#953800" :constant "#cf222e"
             :metatag "#a54a85"}
    :rainbow ["#953800" "#0550ae" "#6639ba" "#116329" "#0a3069" "#a54a85" "#9a6700"]
    :console {:prompt "#6639ba" :result "#0a3069" :system "#8c95a0"}}
   :dark
   {:name :dark :dark? true
    :background "#000000" :text "#e0e0e0" :caret "#6cb4ff"
    :selection "#3e4451" :find-highlight "#5c4b00" :red "#e06c75"
    :tokens {:comment "#6e7480" :string "#98c379" :char "#98c379"
             :regex "#e5c07b" :keyword "#c678dd" :special-form "#c678dd"
             :number "#d19a66" :builtin "#56b6c2" :constant "#c678dd"
             :metatag "#b392d6"}
    :rainbow ["#d19a66" "#61afef" "#c678dd" "#98c379" "#56b6c2" "#e5c07b" "#b392d6"]
    :console {:prompt "#61afef" :result "#98c379" :system "#6e7480"}}
   :solarized
   {:name :solarized :dark? false
    :background "#fdf6e3" :text "#657b83" :caret "#268bd2"
    :selection "#eee8d5" :find-highlight "#f6e3a1" :red "#dc322f"
    ;; The completion list's selected row: base2 (:selection) is 1.14:1
    ;; against base3, too faint to see; base1 under base03 text is 2.48:1
    ;; against the background and 5.61:1 for the text (WCAG ratios,
    ;; computed 2026-10-18). Other themes use :selection, 1.36-2.15:1.
    :row-selected {:background "#93a1a1" :text "#002b36"}
    :tokens {:comment "#93a1a1" :string "#2aa198" :char "#2aa198"
             :regex "#b58900" :keyword "#859900" :special-form "#859900"
             :number "#d33682" :builtin "#cb4b16" :constant "#859900"
             :metatag "#6c71c4"}
    :rainbow ["#b58900" "#268bd2" "#6c71c4" "#859900" "#2aa198" "#d33682" "#cb4b16"]
    :console {:prompt "#268bd2" :result "#2aa198" :system "#93a1a1"}}
   :navy
   {:name :navy :dark? true
    :background "#1a1b2e" :text "#c8cad8" :caret "#7eb8da"
    :selection "#2e3250" :find-highlight "#4d4520" :red "#f07178"
    :tokens {:comment "#6f7390" :string "#c3e88d" :char "#c3e88d"
             :regex "#ffcb6b" :keyword "#c792ea" :special-form "#c792ea"
             :number "#f78c6c" :builtin "#82aaff" :constant "#c792ea"
             :metatag "#a3afff"}
    :rainbow ["#f78c6c" "#82aaff" "#c792ea" "#c3e88d" "#ffcb6b" "#a3afff" "#7eb8da"]
    :console {:prompt "#82aaff" :result "#c3e88d" :system "#6f7390"}}})

(def theme-names
  "The palettes, one per entry in themes."
  [:light :dark :solarized :navy])

(def choice-names
  "The cycle order Cmd/Ctrl+Shift+M walks. :auto is a choice, not a
  palette: resolve-choice maps it to :light or :dark."
  [:light :dark :solarized :navy :auto])

(def default-theme :light)

(defn choice?
  "True when k is one of choice-names."
  [k]
  (boolean (some #{k} choice-names)))

(defn resolve-choice
  "The palette name a choice k stands for. :auto is :dark when system-dark?
  is true and :light otherwise. Any other k is returned unchanged."
  [k system-dark?]
  (if (= :auto k)
    (if system-dark? :dark :light)
    k))

(defn theme
  "The theme map for k, or the default theme's for an unknown k."
  [k]
  (get themes k (get themes default-theme)))

(defn next-theme
  "The choice name after k in choice-names, wrapping. An unknown k starts
  the cycle from the default theme."
  [k]
  (let [i (.indexOf ^java.util.List choice-names k)]
    (if (neg? i)
      (nth choice-names (mod (inc (.indexOf ^java.util.List choice-names default-theme))
                             (count choice-names)))
      (nth choice-names (mod (inc i) (count choice-names))))))

(defn token-color
  "The foreground for a jonquil.paint token kind."
  [t kind]
  (get (:tokens t) kind (:text t)))

(defn console-color
  "The foreground for a jonquil.console style kind: :out is the text
  colour, :err and :error the theme's red, the rest from :console."
  [t kind]
  (case kind
    :out (:text t)
    (:err :error) (:red t)
    (get (:console t) kind (:text t))))

(defn code-css
  "The CSS for both code views, and for every P4 caret-anchored popover, at
  font size px. Measured on GTK 4.22.4 (2026-09-25): background-color,
  color and caret-color apply on the view's own node (.jonquil-code); color
  on the text subnode does not recolour the text; the selection rule
  applies on 'text selection'.

  The popover.jonquil-popover rules are P4 probe 7's measured selector: a
  popover follows the GtkSettings colour scheme but not the theme palette
  or the code font without them. font-size on the list/row and
  label.jonquil-doc rules is this task's own addition (probe 7 didn't set
  it), so a zoom reaches the completion/doc popovers the same way it
  already reaches the code views. The entry rule (fix wave item 5, M8) is
  the rename popover's own GtkEntry: background/text like the popover's
  own contents, plus a selection rule so the prefilled, selected symbol
  name reads against the theme's own selection colour rather than GTK's
  default blue."
  [t px]
  (str ".jonquil-code { font-size: " px "px; "
       "background-color: " (:background t) "; "
       "color: " (:text t) "; "
       "caret-color: " (:caret t) "; } "
       ".jonquil-code text selection { "
       "background-color: " (:selection t) "; "
       "color: " (:text t) "; } "
       "popover.jonquil-popover > contents, popover.jonquil-popover > arrow { "
       "background-color: " (:background t) "; "
       "color: " (:text t) "; } "
       "popover.jonquil-popover list, popover.jonquil-popover row { "
       "background-color: " (:background t) "; "
       "color: " (:text t) "; "
       "font-size: " px "px; } "
       "popover.jonquil-popover row:selected { "
       "background-color: " (get-in t [:row-selected :background] (:selection t)) "; "
       "color: " (get-in t [:row-selected :text] (:text t)) "; } "
       "popover.jonquil-popover label.jonquil-doc { "
       "font-family: monospace; "
       "font-size: " px "px; } "
       "popover.jonquil-popover entry { "
       "background-color: " (:background t) "; "
       "color: " (:text t) "; } "
       "popover.jonquil-popover entry selection { "
       "background-color: " (:selection t) "; "
       "color: " (:text t) "; }"))
