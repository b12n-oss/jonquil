(ns jonquil.parts.docs
  "LM6's docs panel as a window part's data half: its four palette commands
  and the panel it contributes (the key stays in keymap.edn, and opening a
  bookmark or a guide page is a palette entry, jonquil.docs-panel's
  bookmark-entries and guide-entries, not a command here). The install half
  is jonquil.gtk.parts.docs.")

(def spec
  "The docs part: its commands, run through the window context's
  :docs-show-fn, :docs-bookmark-fn, :docs-bookmarks-fn and :docs-guide-fn,
  and its panel's palette label. The words of each doc are chosen so that no
  query that reached another command before reaches these (the quick doc
  command's `show docs`, `docs`)."
  {:id :reader
   :commands
   {:pages/show {:doc "Show the documentation in a panel" :scope :window :call [:docs-show-fn]}
    :pages/bookmark {:doc "Bookmark This Page" :scope :window :call [:docs-bookmark-fn]}
    :pages/bookmarks {:doc "Open a Bookmarked Page…" :scope :window :call [:docs-bookmarks-fn]}
    :pages/guide {:doc "Open a Guide Page…" :scope :window :call [:docs-guide-fn]}}
   :panels [{:id :reader :label "Reader"}]})
