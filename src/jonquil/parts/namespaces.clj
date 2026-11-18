(ns jonquil.parts.namespaces
  "UF8's Namespaces panel as a window part's data half: its palette command
  and the panel it contributes (the panel key stays in keymap.edn). The
  install half is jonquil.gtk.parts.namespaces.")

(def spec
  "The Namespaces part: its command, run through the window context's
  :panel-focus-fn, and its panel's palette label."
  {:id :namespaces
   :commands
   {:panel/namespaces {:doc "Focus the Namespaces panel, or open it." :scope :window
                       :call [:panel-focus-fn :namespaces]}}
   :panels [{:id :namespaces :label "Namespaces"}]})
