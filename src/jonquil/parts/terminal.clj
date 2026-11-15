(ns jonquil.parts.terminal
  "The terminal in the window's bottom area as a window part's data half:
  the commands that show one and open another, and the key. The install half is
  jonquil.gtk.parts.terminal.")

(def spec
  "The terminal part: Show the terminal, on Ctrl+` on every system, and
  New Terminal, which has no key."
  {:id :terminal
   :commands
   {:terminal/show {:doc "Show the terminal, or go back to the editor from it." :scope :window
                    :call [:terminal-show-fn]}
    :terminal/new {:doc "Open another terminal." :scope :window
                   :call [:terminal-new-fn]}}
   :bindings {:window [{:key "grave" :exact #{:ctrl} :command :terminal/show}]}})
