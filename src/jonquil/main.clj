(ns jonquil.main
  "Jonquil's entry point: delegates to jonquil.gtk.window, which builds the
  window and blocks in the GTK main loop."
  (:require [jonquil.gtk.window :as window]
            [jonquil.runtime-registry :as registry]))

(defn start!
  "Builds the Jonquil window and blocks in the GTK main loop. opts is
  {:file path-or-nil :on-ready (fn [ui]) :settings-path path-or-nil}
  (:settings-path defaults to (jonquil.settings/settings-path), TH.T5); see
  jonquil.gtk.window/start! for ui's shape."
  [opts]
  (window/start! opts))

(defn -main [& args]
  (start! {:file (first args)})
  ;; The GTK loop has ended by the time g-application-run (inside start!)
  ;; returns, so blocking here is fine -- unlike ev/stop! (jonquil.nrepl.
  ;; evaluator), which never blocks the GTK thread. registry/stop-all!
  ;; stops every evaluator this process ever made and waits, bounded, for
  ;; any start still in flight to finish tearing itself down, so
  ;; System/exit below can't cut that off mid-flight and orphan a child.
  ;; Guarded so a throw here can't skip the exit.
  (try (registry/stop-all!)
       (catch Throwable e
         (binding [*out* *err*]
           (println "jonquil: stopping every runtime failed:" (ex-message e)))))
  (System/exit 0))
