(ns jonquil.parts.command-palette
  "The command palette and the describe-keys commands as a window part's
  data half. Their chords stay in keymap.edn. The install half is
  jonquil.gtk.parts.command-palette.")

(def spec
  "The command palette part: its commands, run through the fns the install
  half answers."
  {:id :command-palette
   :commands
   {;; The palette. :palette? false marks a command that only works inside the
    ;; key chain guarding it (a focus, an Escape step, a tab number), so the
    ;; palette leaves it out
    :palette/open {:doc "Show the command palette." :scope :window :palette? false
                   :call [:open-palette-fn]}
    ;; describing keys: palette-only, no chords
    :help/describe-key {:doc "Describe the next key you press." :scope :window
                        :call [:describe-key-fn]}
    :help/key-bindings {:doc "List every key binding." :scope :window :call [:key-bindings-fn]}
    :help/recent-keys {:doc "Show the keys you pressed last." :scope :window :call [:recent-keys-fn]}
    :help/version {:doc "Show Jonquil's version." :scope :window :call [:version-fn]}}})
