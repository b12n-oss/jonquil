(ns jonquil.parts.messages
  "The Messages panel as a window part's data half: its palette command and
  the panel it contributes (the panel key stays in keymap.edn). The install
  half is jonquil.gtk.parts.messages.")

(def spec
  "The Messages part: its command, run through the window context's
  :panel-focus-fn, and its panel's palette label."
  {:id :messages
   :commands
   {:panel/messages {:doc "Focus the Messages panel, or open it." :scope :window
                     :call [:panel-focus-fn :messages]}}
   :panels [{:id :messages :label "Messages"}]})
