(ns jonquil.gtk.parts
  "The window parts, in install order. A part is a namespace with one install
  fn, (part wctx) -> contribution, tagged ^{:part/id :name}; adding a feature
  is its namespace plus one entry here. A part may read another's :exports
  only when it comes later."
  (:require [jonquil.parts.extra :as extra]
            [jonquil.gtk.parts.layouts :as layouts]
            [jonquil.gtk.parts.processes :as processes]
            [jonquil.gtk.parts.replay :as replay]
            [jonquil.gtk.parts.time-form :as time-form]
            [jonquil.gtk.parts.worlds :as worlds]))

(def installs
  "Install fns, in order: the real parts, then any registered in
  jonquil.parts.extra."
  (into [layouts/part worlds/part time-form/part processes/part replay/part] @extra/installs*))
