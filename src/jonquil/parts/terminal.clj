(ns jonquil.parts.terminal
  "The terminal in the window's bottom area as a window part's data half:
  the command that shows it and its key. The install half is
  jonquil.gtk.parts.terminal.")

(def spec
  "The terminal part: Show the terminal, on Ctrl+` on every system."
  {:id :terminal
   :commands
   {:terminal/show {:doc "Show the terminal, or go back to the editor from it." :scope :window
                    :call [:terminal-show-fn]}}
   :bindings {:window [{:key "grave" :exact #{:ctrl} :command :terminal/show}]}})
