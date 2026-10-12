(ns jonquil.main
  "Jonquil's entry point: delegates to jonquil.gtk.window, which builds the
  window and blocks in the GTK main loop."
  (:require [clojure.java.io :as io]
            [jonquil.gtk.window :as window]
            [jonquil.login-path :as login-path]
            [jonquil.runtime-registry :as registry]))

(defn start!
  "Builds the Jonquil window and blocks in the GTK main loop. opts is
  {:file path-or-nil :dir path-or-nil :cwd path-or-nil :argv [\"jonquil\" ...]
  :on-ready (fn [ui]) :settings-path path-or-nil}
  (:settings-path defaults to (jonquil.settings/settings-path), TH.T5); see
  jonquil.gtk.window/start! for ui's shape."
  [opts]
  (window/start! opts))

(defn gtk-argv
  "The argv handed to g_application_run: \"jonquil\", then every arg made
  absolute against cwd. Absolute paths can't start with a dash, so GTK's
  option parser never sees a file named like an option (--help). Pure: no
  filesystem check, no canonicalisation."
  [args cwd]
  (into ["jonquil"]
        (map (fn [a] (let [f (io/file a)]
                       (if (.isAbsolute f) a (.getAbsolutePath (io/file cwd a)))))
             args)))

(defn -main [& args]
  ;; Asks the login shell for PATH in the background when launched from
  ;; Finder; self-tests call start! directly and keep the process PATH.
  (login-path/start!)
  ;; Every argument goes through GApplication's "open" signal, so several
  ;; files open as tabs and a folder roots the file tree. :cwd roots the
  ;; tree when there's no argument.
  (let [cwd (System/getProperty "user.dir")]
    (start! {:cwd cwd :argv (gtk-argv args cwd)}))
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
