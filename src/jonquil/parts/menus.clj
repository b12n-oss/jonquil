(ns jonquil.parts.menus
  "The window's menus as a window part's data half. The menus list the
  commands other parts and the core already have (jonquil/menus.edn); this
  part adds one of its own, Reset on Run, which the Run menu shows with a
  tick. The install half is jonquil.gtk.parts.menus.")

(def spec
  "The menus part: Reset on Run, which the header bar has no room for."
  {:id :menus
   :commands
   {:run/reset-on-run {:doc "Reset on Run: turn it on or off." :scope :window
                       :call [:reset-toggle-fn]}}})
