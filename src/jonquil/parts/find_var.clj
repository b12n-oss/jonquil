(ns jonquil.parts.find-var
  "Find a Var by Name as a window part's data half: its command, in the
  Help menu and the palette, with no key. The install half is
  jonquil.gtk.parts.find-var.")

(def spec
  "The find-var part: its command, run through the window context's
  :find-var-fn. Its two palette entries ([:var/open m], [:var/doc s]) are
  the window's own, run by :find-var-open-fn and :find-var-doc-fn."
  {:id :find-var
   :commands
   {:help/find-var {:doc "Find a Var by Name…" :scope :window :call [:find-var-fn]}}})
