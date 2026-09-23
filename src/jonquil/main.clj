(ns jonquil.main
  "Jonquil's entry point: delegates to jonquil.gtk.window, which builds the
  window and blocks in the GTK main loop."
  (:require [jonquil.gtk.window :as window]))

(defn start!
  "Builds the Jonquil window and blocks in the GTK main loop. opts is
  {:file path-or-nil :on-ready (fn [ui])}; see jonquil.gtk.window/start!
  for ui's shape."
  [opts]
  (window/start! opts))

(defn -main [& args]
  (start! {:file (first args)})
  (System/exit 0))
