(ns jonquil.gtk.parts
  "The window parts, in install order. A part is a namespace with one install
  fn, (part wctx) -> contribution, tagged ^{:part/id :name}; adding a feature
  is its namespace plus one entry here. A part may read another's :exports
  only when it comes later.")

(def installs
  "Install fns, in order. Empty until the first part moves in."
  [])
