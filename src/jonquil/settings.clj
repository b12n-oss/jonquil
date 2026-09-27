(ns jonquil.settings
  "The user's saved settings: a small EDN map at
  $XDG_CONFIG_HOME/jonquil/settings.edn, or ~/.config/jonquil/settings.edn
  when XDG_CONFIG_HOME is unset or blank. It stores :theme, and :dialect
  (the fallback dialect for a file no project decides) when the user set
  one by hand. Loading never throws: a missing file is the default quietly,
  and an unreadable file, an unknown theme or an unknown dialect is the
  default plus a :warning. Saving writes
  a temp file beside the target and renames it over the target with
  ATOMIC_MOVE (the same move jonquil.gtk.window's Save uses), and returns
  nil or an error string instead of throwing."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jonquil.dialect :as dialect]
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

(defn- theme-part
  "{:theme k} from the file's map m, plus :warning when m can't give one."
  [path m]
  (cond
    (= ::unreadable m)
    {:theme theme/default-theme
     :warning (str "couldn't read " path ", using " (name theme/default-theme))}

    (and (map? m) (contains? theme/themes (:theme m)))
    {:theme (:theme m)}

    :else
    {:theme theme/default-theme
     :warning (str path " has no known :theme, using " (name theme/default-theme))}))

(defn- dialect-part
  "{:dialect k} when m names a known dialect, {:warning ..} when it names
  an unknown one, {} when it names none."
  [path m]
  (let [d (when (map? m) (:dialect m))]
    (cond
      (nil? d) {}
      (dialect/profile d) {:dialect d}
      :else {:warning (str path " names :dialect " (pr-str d) ", which isn't a known dialect")})))

(defn load-settings
  "{:theme k} from path, plus :dialect when the file names a known one, plus
  :warning when the file exists but part of it can't be used. A missing
  file is {:theme theme/default-theme} with no warning."
  [path]
  (let [f (io/file path)]
    (if-not (.exists f)
      {:theme theme/default-theme}
      (let [m (try (edn/read-string (slurp f)) (catch Exception _ ::unreadable))
            t (theme-part path m)
            d (dialect-part path m)
            warnings (remove nil? [(:warning t) (:warning d)])]
        (cond-> (merge (dissoc t :warning) (dissoc d :warning))
          (seq warnings) (assoc :warning (str/join "; " warnings)))))))

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
  "Writes {:theme k} to path atomically, keeping a :dialect: the one given,
  else the one the file already names (a theme change must not drop a
  hand-set dialect). nil on success, else a message.
  When path is a symlink, the real target is rewritten in place (the temp
  file sits beside the target and the rename lands on the target), so the
  link itself survives the save."
  [path {:keys [theme dialect]}]
  (let [tmp (atom nil)]
    (try
      (let [target (resolved-target path)
            f (io/file target)
            kept (if dialect
                   dialect
                   ;; No dialect given; try to keep the existing one.
                   ;; Distinguish IO failure (file unreadable) from parse failure.
                   (when (.exists f)
                     (try
                       (let [content (slurp target)]
                         ;; File is readable; try to parse it and extract dialect.
                         (try
                           (:dialect (edn/read-string content))
                           (catch Exception _
                             ;; Parse failed (malformed EDN); no dialect to keep.
                             nil)))
                       (catch Exception _
                         ;; IO failure; file is unreadable. Signal an error.
                         (throw (Exception.
                                 (str "couldn't read " target " to keep its :dialect; theme not saved")))))))]
        (reset! tmp (str target ".tmp-" (System/nanoTime)))
        (io/make-parents target)
        (spit @tmp (pr-str (cond-> {:theme theme} kept (assoc :dialect kept))))
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
