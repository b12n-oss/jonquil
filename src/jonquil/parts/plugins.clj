(ns jonquil.parts.plugins
  "The plugin panels' behaviour as a window part's data half: the commands
  that open the review and the plugin list. Reload Plugins joins them in a
  later step. The install half is jonquil.gtk.parts.plugins.")

(def spec
  "The plugins part: its commands, run through the fns the install half
  answers."
  {:id :plugins
   :commands
   {;; open the review of the agents' pending proposals
    :plugins/review {:doc "Review the plugin proposals agents made." :scope :window
                     :call [:review-proposals-fn]}
    ;; the plugin list, to enable, disable or open one
    :plugins/list {:doc "Show the plugins: enable, disable or open one." :scope :window
                   :call [:plugins-list-fn]}}})
