(ns jonquil.gtk.parts
  "The window parts, in install order. A part is a namespace with one install
  fn, (part wctx) -> contribution, tagged ^{:part/id :name}; adding a feature
  is its namespace plus one entry here. A part may read another's :exports
  only when it comes later."
  (:require [jonquil.gtk.parts.layouts :as layouts]
            [jonquil.gtk.parts.replay :as replay]))

(def installs
  "Install fns, in order."
  [layouts/part replay/part])
