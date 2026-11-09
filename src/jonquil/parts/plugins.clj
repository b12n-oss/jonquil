(ns jonquil.parts.plugins
  "The plugin panels' behaviour as a window part's data half: the commands
  that open the review and the plugin list, and Reload Plugins. The install half is jonquil.gtk.parts.plugins.")

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
                   :call [:plugins-list-fn]}
    ;; reload the plugin files that changed, appeared or went
    ;; away; no chord, the palette reaches it
    :plugins/reload {:doc "Reload the plugin files that changed." :scope :window
                     :call [:reload-plugins-fn]}}})
