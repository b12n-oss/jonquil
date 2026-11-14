(ns jonquil.parts.menus
  "The window's menus as a window part's data half. It adds no commands:
  the menus list the ones other parts and the core already have
  (jonquil/menus.edn). The install half is jonquil.gtk.parts.menus.")

(def spec
  "The menus part: no commands of its own."
  {:id :menus :commands {}})
