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
  production and a map in tests; home is the user's home directory. A
  relative XDG_CONFIG_HOME (anything not starting with \"/\") is invalid
  per the XDG spec (fix wave item 4) and is treated like a blank one."
  ([] (settings-path (fn [n] (System/getenv n)) (System/getProperty "user.home")))
  ([env home]
   (let [xdg (env "XDG_CONFIG_HOME")
         base (if (or (str/blank? xdg) (not (str/starts-with? xdg "/")))
                (str home "/.config")
                xdg)]
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

(defn- resolved-target
  "path itself, unless it exists and is a symlink, in which case the real
  file it points at (fix wave item 3: a save used to replace the symlink
  with a plain file, so a dotfiles-managed settings.edn lost its link and
  the linked target never saw the new value). Measured on this Jolt pin
  (2026-09-26, throwaway probe): java.nio.file.Files/isSymbolicLink,
  Files/createSymbolicLink and Path/.toRealPath all work. isSymbolicLink
  answers false for a path that doesn't exist, so no separate .exists
  check is needed."
  [path]
  (let [p (.toPath (java.io.File. ^String path))]
    (if (java.nio.file.Files/isSymbolicLink p)
      (str (.toRealPath p (make-array java.nio.file.LinkOption 0)))
      path)))

(defn save-settings!
  "Writes {:theme k} to path atomically. nil on success, else a message.
  When path is a symlink, the real target is rewritten in place (the temp
  file sits beside the target and the rename lands on the target), so the
  link itself survives the save."
  [path {:keys [theme]}]
  (let [tmp (atom nil)]
    (try
      (let [target (resolved-target path)]
        (reset! tmp (str target ".tmp-" (System/nanoTime)))
        (io/make-parents target)
        (spit @tmp (pr-str {:theme theme}))
        (java.nio.file.Files/move
         (.toPath (java.io.File. ^String @tmp))
         (.toPath (java.io.File. ^String target))
         (into-array java.nio.file.CopyOption
                     [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                      java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
        nil)
      (catch Exception e
        (when @tmp (try (io/delete-file @tmp true) (catch Exception _ nil)))
        (str "couldn't save " path ": " (.getMessage e))))))
