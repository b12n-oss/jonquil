(ns jonquil.parts.layouts
  "LM8's named layouts as a window part's data half: its palette commands.
  The install half is jonquil.gtk.parts.layouts. Using a saved layout is a
  palette entry (jonquil.layouts/entries), not a command here.")

(def spec
  "The layouts part's commands, run through the window context's
  :layout-save-fn and :layout-forget-fn."
  {:id :layouts
   :commands
   {:layout/save {:doc "Save the layout: the file tree, the panel and the split, named after what shows." :scope :window
                  :call [:layout-save-fn]}
    :layout/forget {:doc "Forget every saved layout." :scope :window :call [:layout-forget-fn]}}})
