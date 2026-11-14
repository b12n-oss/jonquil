(ns jonquil.parts.prefs
  "The Preferences window as a window part's data half: the command that
  opens it and its key. The install half is jonquil.gtk.parts.prefs.")

(def spec
  "The Preferences part: Open Preferences, on Cmd/Ctrl+comma."
  {:id :prefs
   :commands
   {:prefs/open {:doc "Open Preferences." :scope :window :call [:prefs-open-fn]}}
   :bindings {:window [{:key "comma" :mods #{:cmd} :command :prefs/open}]}})
