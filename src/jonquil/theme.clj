(ns jonquil.theme
  "Jonquil's colour themes as data. Pure. The four palettes come from
  a sibling ClojureDart deck app's theme (palettes and
  syntax-palettes, read 2026-09-25): each Jonquil token kind takes the
  colour of the slides class its tokenizer uses for the same text. The
  rainbow, :red, :find-highlight, :changed-mark (the gutter bar on a form
  edited since it last ran), :selection and :caret values are
  Jonquil's own, picked from each palette; :red is never in :rainbow, so
  an unmatched bracket never looks like a matched one.

  The six popular themes after them (Gruvbox Dark and Light, Dracula, Nord,
  Zenburn, Monokai; added 2026-11-13) take every token, rainbow, text and
  background colour from each original's published palette, checked on
  2026-11-13 against the source NOTICE names: morhetz/gruvbox's
  colors/gruvbox.vim, dracula/emacs's dracula-theme.el, nordtheme/nord's
  src/nord.css (plus nordtheme/vim's nord3_gui_bright #616E88 for comments,
  as Nord's own editor ports use), bbatsov/zenburn-emacs's
  zenburn-theme.el and oneKelvinSmith/monokai-emacs's monokai-theme.el. Their token mapping is Jonquil's own, worked
  out from the Emacs font-lock faces each theme styles: the keyword face
  for :special-form, the constant face for :keyword, :constant and
  :number, the builtin or function-name face for :builtin, the string face
  for :string and :char, and the type or preprocessor face for :metatag.
  Which palette colours fill the rainbow and :red is Jonquil's choice,
  under the same two rules as above, and :find-highlight is the one colour
  in each that is Jonquil's own, a dim tint picked to keep the text readable.

  Six more (Tokyo Night, Catppuccin Mocha and Latte, One Dark, Kanagawa,
  Rosé Pine; added 2026-11-14) follow the same rules, with colours read from
  folke/tokyonight.nvim's lua/tokyonight/colors/night.lua and storm.lua (night
  builds on storm), catppuccin/palette's palette.json, joshdick/onedark.vim's
  autoload/onedark.vim, rebelot/kanagawa.nvim's lua/kanagawa/colors.lua and
  rose-pine/neovim's lua/rose-pine/palette.lua. One Dark's comment grey
  (#5c6370 upstream) is a little lighter here, because the original sits
  just under the 2.4 contrast floor against its background.")

(def themes
  {:light
   {:name :light :dark? false
    :background "#ffffff" :text "#24292f" :caret "#0066cc"
    :selection "#b6d7ff" :find-highlight "#fff2a8" :red "#cf222e"
    :changed-mark "#0969da"
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
    :changed-mark "#61afef"
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
    :changed-mark "#268bd2"
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
    :changed-mark "#82aaff"
    :tokens {:comment "#6f7390" :string "#c3e88d" :char "#c3e88d"
             :regex "#ffcb6b" :keyword "#c792ea" :special-form "#c792ea"
             :number "#f78c6c" :builtin "#82aaff" :constant "#c792ea"
             :metatag "#a3afff"}
    :rainbow ["#f78c6c" "#82aaff" "#c792ea" "#c3e88d" "#ffcb6b" "#a3afff" "#7eb8da"]
    :console {:prompt "#82aaff" :result "#c3e88d" :system "#6f7390"}}
   :gruvbox-dark
   {:name :gruvbox-dark :dark? true
    :background "#282828" :text "#ebdbb2" :caret "#fabd2f"
    :selection "#504945" :find-highlight "#79740e" :red "#fb4934"
    :changed-mark "#83a598"
    :tokens {:comment "#928374" :string "#b8bb26" :char "#b8bb26"
             :regex "#fabd2f" :keyword "#d3869b" :special-form "#fb4934"
             :number "#d3869b" :builtin "#fe8019" :constant "#d3869b"
             :metatag "#8ec07c"}
    :rainbow ["#fe8019" "#83a598" "#d3869b" "#b8bb26" "#8ec07c" "#fabd2f" "#bdae93"]
    :console {:prompt "#83a598" :result "#b8bb26" :system "#928374"}}
   :gruvbox-light
   {:name :gruvbox-light :dark? false
    :background "#fbf1c7" :text "#3c3836" :caret "#076678"
    :selection "#d5c4a1" :find-highlight "#fabd2f" :red "#9d0006"
    :changed-mark "#076678"
    :tokens {:comment "#928374" :string "#79740e" :char "#79740e"
             :regex "#b57614" :keyword "#8f3f71" :special-form "#9d0006"
             :number "#8f3f71" :builtin "#af3a03" :constant "#8f3f71"
             :metatag "#427b58"}
    :rainbow ["#af3a03" "#076678" "#8f3f71" "#79740e" "#427b58" "#b57614" "#7c6f64"]
    :console {:prompt "#076678" :result "#79740e" :system "#928374"}}
   :dracula
   {:name :dracula :dark? true
    :background "#282a36" :text "#f8f8f2" :caret "#f8f8f2"
    :selection "#44475a" :find-highlight "#6d6a3a" :red "#ff5555"
    :changed-mark "#bd93f9"
    :tokens {:comment "#6272a4" :string "#f1fa8c" :char "#f1fa8c"
             :regex "#ffb86c" :keyword "#bd93f9" :special-form "#ff79c6"
             :number "#bd93f9" :builtin "#50fa7b" :constant "#bd93f9"
             :metatag "#8be9fd"}
    :rainbow ["#ffb86c" "#8be9fd" "#bd93f9" "#50fa7b" "#f1fa8c" "#ff79c6" "#ccccc7"]
    :console {:prompt "#bd93f9" :result "#f1fa8c" :system "#6272a4"}}
   :nord
   {:name :nord :dark? true
    :background "#2e3440" :text "#d8dee9" :caret "#d8dee9"
    :selection "#434c5e" :find-highlight "#6b5f3e" :red "#bf616a"
    :changed-mark "#88c0d0"
    :tokens {:comment "#616e88" :string "#a3be8c" :char "#a3be8c"
             :regex "#ebcb8b" :keyword "#8fbcbb" :special-form "#81a1c1"
             :number "#b48ead" :builtin "#88c0d0" :constant "#81a1c1"
             :metatag "#d08770"}
    :rainbow ["#d08770" "#88c0d0" "#b48ead" "#a3be8c" "#ebcb8b" "#81a1c1" "#8fbcbb"]
    :console {:prompt "#88c0d0" :result "#a3be8c" :system "#616e88"}}
   :zenburn
   {:name :zenburn :dark? true
    :background "#3f3f3f" :text "#dcdccc" :caret "#ffffef"
    :selection "#5f5f5f" :find-highlight "#5f5f3f" :red "#dca3a3"
    :changed-mark "#8cd0d3"
    :tokens {:comment "#7f9f7f" :string "#cc9393" :char "#cc9393"
             :regex "#dfaf8f" :keyword "#bfebbf" :special-form "#f0dfaf"
             :number "#8cd0d3" :builtin "#93e0e3" :constant "#bfebbf"
             :metatag "#7cb8bb"}
    :rainbow ["#dfaf8f" "#8cd0d3" "#dc8cc3" "#7f9f7f" "#f0dfaf" "#93e0e3" "#9fc59f"]
    :console {:prompt "#8cd0d3" :result "#bfebbf" :system "#7f9f7f"}}
   :monokai
   {:name :monokai :dark? true
    :background "#272822" :text "#f8f8f2" :caret "#f8f8f0"
    :selection "#49483e" :find-highlight "#6b6a32" :red "#f92672"
    :changed-mark "#66d9ef"
    :tokens {:comment "#75715e" :string "#e6db74" :char "#e6db74"
             :regex "#fd971f" :keyword "#ae81ff" :special-form "#f92672"
             :number "#ae81ff" :builtin "#66d9ef" :constant "#ae81ff"
             :metatag "#a6e22e"}
    :rainbow ["#fd971f" "#66d9ef" "#ae81ff" "#a6e22e" "#e6db74" "#fd5ff0" "#a1efe4"]
    :console {:prompt "#66d9ef" :result "#e6db74" :system "#75715e"}}
   :tokyo-night
   {:name :tokyo-night :dark? true
    :background "#1a1b26" :text "#c0caf5" :caret "#c0caf5"
    :selection "#394b70" :find-highlight "#59502f" :red "#f7768e"
    :changed-mark "#2ac3de"
    :tokens {:comment "#565f89" :string "#9ece6a" :char "#9ece6a"
             :regex "#b4f9f8" :keyword "#ff9e64" :special-form "#bb9af7"
             :number "#ff9e64" :builtin "#7aa2f7" :constant "#ff9e64"
             :metatag "#2ac3de"}
    :rainbow ["#ff9e64" "#7aa2f7" "#bb9af7" "#9ece6a" "#e0af68" "#7dcfff" "#1abc9c"]
    :console {:prompt "#7aa2f7" :result "#9ece6a" :system "#565f89"}}
   :catppuccin-mocha
   {:name :catppuccin-mocha :dark? true
    :background "#1e1e2e" :text "#cdd6f4" :caret "#f5e0dc"
    :selection "#45475a" :find-highlight "#5b5139" :red "#f38ba8"
    :changed-mark "#74c7ec"
    :tokens {:comment "#7f849c" :string "#a6e3a1" :char "#a6e3a1"
             :regex "#f5c2e7" :keyword "#fab387" :special-form "#cba6f7"
             :number "#fab387" :builtin "#89b4fa" :constant "#fab387"
             :metatag "#f9e2af"}
    :rainbow ["#fab387" "#89b4fa" "#cba6f7" "#a6e3a1" "#f9e2af" "#94e2d5" "#f5c2e7"]
    :console {:prompt "#89b4fa" :result "#a6e3a1" :system "#7f849c"}}
   :catppuccin-latte
   {:name :catppuccin-latte :dark? false
    :background "#eff1f5" :text "#4c4f69" :caret "#1e66f5"
    :selection "#bcc0cc" :find-highlight "#f2d9a0" :red "#d20f39"
    :changed-mark "#1e66f5"
    :tokens {:comment "#7c7f93" :string "#40a02b" :char "#40a02b"
             :regex "#e64553" :keyword "#fe640b" :special-form "#8839ef"
             :number "#fe640b" :builtin "#1e66f5" :constant "#fe640b"
             :metatag "#179299"}
    :rainbow ["#fe640b" "#1e66f5" "#8839ef" "#40a02b" "#179299" "#7287fd" "#209fb5"]
    :console {:prompt "#1e66f5" :result "#40a02b" :system "#7c7f93"}}
   :one-dark
   {:name :one-dark :dark? true
    :background "#282c34" :text "#abb2bf" :caret "#61afef"
    :selection "#3e4452" :find-highlight "#5c5230" :red "#e06c75"
    :changed-mark "#61afef"
    :tokens {:comment "#666f7e" :string "#98c379" :char "#98c379"
             :regex "#56b6c2" :keyword "#d19a66" :special-form "#c678dd"
             :number "#d19a66" :builtin "#61afef" :constant "#d19a66"
             :metatag "#e5c07b"}
    :rainbow ["#d19a66" "#61afef" "#c678dd" "#98c379" "#e5c07b" "#56b6c2" "#be5046"]
    :console {:prompt "#61afef" :result "#98c379" :system "#666f7e"}}
   :kanagawa
   {:name :kanagawa :dark? true
    :background "#1f1f28" :text "#dcd7ba" :caret "#c8c093"
    :selection "#2d4f67" :find-highlight "#5a4f30" :red "#e82424"
    :changed-mark "#7fb4ca"
    :tokens {:comment "#727169" :string "#98bb6c" :char "#98bb6c"
             :regex "#c0a36e" :keyword "#ffa066" :special-form "#957fb8"
             :number "#d27e99" :builtin "#7e9cd8" :constant "#ffa066"
             :metatag "#7aa89f"}
    :rainbow ["#ffa066" "#7e9cd8" "#957fb8" "#98bb6c" "#e6c384" "#7fb4ca" "#d27e99"]
    :console {:prompt "#7e9cd8" :result "#98bb6c" :system "#727169"}}
   :rose-pine
   {:name :rose-pine :dark? true
    :background "#191724" :text "#e0def4" :caret "#ebbcba"
    :selection "#403d52" :find-highlight "#5e5036" :red "#eb6f92"
    :changed-mark "#9ccfd8"
    :tokens {:comment "#6e6a86" :string "#f6c177" :char "#f6c177"
             :regex "#95b1ac" :keyword "#c4a7e7" :special-form "#31748f"
             :number "#c4a7e7" :builtin "#ebbcba" :constant "#c4a7e7"
             :metatag "#9ccfd8"}
    :rainbow ["#ebbcba" "#9ccfd8" "#c4a7e7" "#f6c177" "#31748f" "#95b1ac" "#908caa"]
    :console {:prompt "#9ccfd8" :result "#f6c177" :system "#6e6a86"}}})

(def theme-names
  "The palettes, one per entry in themes."
  [:light :dark :solarized :navy :gruvbox-dark :gruvbox-light :dracula :nord :zenburn :monokai
   :tokyo-night :catppuccin-mocha :catppuccin-latte :one-dark :kanagawa :rose-pine])

(def choice-names
  "The cycle order Cmd/Ctrl+Shift+M walks. :auto is a choice, not a
  palette: resolve-choice maps it to :light or :dark."
  [:light :dark :solarized :navy :gruvbox-dark :gruvbox-light :dracula :nord :zenburn :monokai
   :tokyo-night :catppuccin-mocha :catppuccin-latte :one-dark :kanagawa :rose-pine :auto])

(def ^:private labels
  {:light "Light" :dark "Dark" :solarized "Solarized" :navy "Navy"
   :gruvbox-dark "Gruvbox Dark" :gruvbox-light "Gruvbox Light" :dracula "Dracula"
   :nord "Nord" :zenburn "Zenburn" :monokai "Monokai"
   :tokyo-night "Tokyo Night" :catppuccin-mocha "Catppuccin Mocha"
   :catppuccin-latte "Catppuccin Latte" :one-dark "One Dark" :kanagawa "Kanagawa"
   :rose-pine "Rosé Pine" :auto "Auto"})

(defn label
  "The name a person reads for choice k: \"Gruvbox Dark\" for :gruvbox-dark."
  [k]
  (get labels k (name k)))

(defn palette-entries
  "The command palette's entries for picking a theme, one per choice in
  cycle order: {:id [:theme/use k] :doc \"Use theme: Label\" :chords []
  :source \"theme\"}. The window runs these itself, as it does a layout's."
  []
  (mapv (fn [k] {:id [:theme/use k] :doc (str "Use theme: " (label k)) :chords [] :source "theme"})
        choice-names))

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

(defn- code-view-css
  "The rules for the code views' own node and their selection, at font size
  px."
  [t px]
  (str ".jonquil-code { font-size: " px "px; "
       "background-color: " (:background t) "; "
       "color: " (:text t) "; "
       "caret-color: " (:caret t) "; } "
       ".jonquil-code text selection { "
       "background-color: " (:selection t) "; "
       "color: " (:text t) "; } "))

(defn- popover-list-css
  "The rules for a popover's frame, list, rows and doc label, at font size
  px."
  [t px]
  (str "popover.jonquil-popover > contents, popover.jonquil-popover > arrow { "
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
       "font-size: " px "px; } "))

(defn- popover-entry-css
  "The rules for the rename popover's entry and its selection."
  [t]
  (str "popover.jonquil-popover entry { "
       "background-color: " (:background t) "; "
       "color: " (:text t) "; } "
       "popover.jonquil-popover entry selection { "
       "background-color: " (:selection t) "; "
       "color: " (:text t) "; }"))

(defn code-css
  "The CSS for both code views, and for every caret-anchored popover, at
  font size px. Measured on GTK 4.22.4 (2026-09-25): background-color,
  color and caret-color apply on the view's own node (.jonquil-code); color
  on the text subnode does not recolour the text; the selection rule
  applies on 'text selection'.

  The popover.jonquil-popover rules are a measured selector: a
  popover follows the GtkSettings colour scheme but not the theme palette
  or the code font without them. font-size on the list/row and
  label.jonquil-doc rules lets a zoom reach the completion/doc popovers
  the same way it reaches the code views. The entry rule is
  the rename popover's own GtkEntry: background/text like the popover's
  own contents, plus a selection rule so the prefilled, selected symbol
  name reads against the theme's own selection colour rather than GTK's
  default blue."
  [t px]
  (str (code-view-css t px) (popover-list-css t px) (popover-entry-css t)))
