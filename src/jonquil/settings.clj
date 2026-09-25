(ns jonquil.settings
  "The user's saved settings: a small EDN map at
  $XDG_CONFIG_HOME/jonquil/settings.edn, or ~/.config/jonquil/settings.edn
  when XDG_CONFIG_HOME is unset or blank. Only :theme is stored. Loading
  never throws: a missing file is the default quietly, and an unreadable
  file or an unknown theme is the default plus a :warning. Saving writes
  a temp file beside the target and renames it over the target with
  ATOMIC_MOVE (the same move jonquil.gtk.window's Save uses), and returns
  nil or an error string instead of throwing."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jonquil.theme :as theme]))

(defn settings-path
  "The settings file's path. env is (fn [name] value), System/getenv in
  production and a map in tests; home is the user's home directory."
  ([] (settings-path (fn [n] (System/getenv n)) (System/getProperty "user.home")))
  ([env home]
   (let [xdg (env "XDG_CONFIG_HOME")
         base (if (str/blank? xdg) (str home "/.config") xdg)]
     (str base "/jonquil/settings.edn"))))

(defn load-settings
  "{:theme k} from path, plus :warning when the file exists but can't be
  used. A missing file is {:theme theme/default-theme} with no warning."
  [path]
  (let [f (io/file path)]
    (if-not (.exists f)
      {:theme theme/default-theme}
      (let [m (try (edn/read-string (slurp f)) (catch Exception _ ::unreadable))]
        (cond
          (= ::unreadable m)
          {:theme theme/default-theme
           :warning (str "couldn't read " path ", using " (name theme/default-theme))}

          (and (map? m) (contains? theme/themes (:theme m)))
          {:theme (:theme m)}

          :else
          {:theme theme/default-theme
           :warning (str path " has no known :theme, using " (name theme/default-theme))})))))

(defn save-settings!
  "Writes {:theme k} to path atomically. nil on success, else a message."
  [path {:keys [theme]}]
  (let [tmp (str path ".tmp-" (System/nanoTime))]
    (try
      (io/make-parents path)
      (spit tmp (pr-str {:theme theme}))
      (java.nio.file.Files/move
       (.toPath (java.io.File. ^String tmp))
       (.toPath (java.io.File. ^String path))
       (into-array java.nio.file.CopyOption
                   [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                    java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
      nil
      (catch Exception e
        (try (io/delete-file tmp true) (catch Exception _ nil))
        (str "couldn't save " path ": " (.getMessage e))))))
