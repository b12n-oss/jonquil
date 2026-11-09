(ns jonquil.parts.file-tree
  "The file tree's behaviour as a window part's data half: its Show/Hide
  Files command. Its chord stays in keymap.edn. The install half is
  jonquil.gtk.parts.file-tree.")

(def spec
  "The file tree part: its command, run through the fn the install half
  answers."
  {:id :file-tree
   :commands
   {:tree/toggle {:doc "Show or hide the file tree." :scope :window :call [:toggle-tree-fn]}}})
