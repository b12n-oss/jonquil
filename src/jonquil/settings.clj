(ns jonquil.settings
  "The user's saved settings: a small EDN map at
  $XDG_CONFIG_HOME/jonquil/settings.edn, or ~/.config/jonquil/settings.edn
  when XDG_CONFIG_HOME is unset or blank. It stores :theme, :dialect (the
  fallback dialect for a file no project decides) when the user set one by
  hand, :file-tree {:visible? :width} and the booleans :reopen-tabs? and :mcp-listen?. A save
  merges into what the file already holds, so each feature only writes its
  own keys. Loading never throws: a missing file gives the defaults with no
  warning, and an unreadable file, an unknown theme or an unknown dialect is the
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

    (and (map? m) (theme/choice? (:theme m)))
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

(defn- file-tree-part
  "{:file-tree {:visible? :width}} when m holds a usable one (a boolean
  :visible? and a positive integer :width), {} when it holds none, and
  {:warning ..} otherwise (file-tree plan P1.T2)."
  [path m]
  (let [ft (when (map? m) (get m :file-tree ::none))]
    (cond
      (or (nil? ft) (= ::none ft)) {}
      (and (map? ft) (boolean? (:visible? ft)) (pos-int? (:width ft)))
      {:file-tree (select-keys ft [:visible? :width])}
      :else {:warning (str path " has an unusable :file-tree, ignoring it")})))

(defn- reopen-tabs-part
  "{:reopen-tabs? b} when m holds a boolean one, {} when it holds none (absent
  or nil), and
  {:warning ..} otherwise."
  [path m]
  (let [v (when (map? m) (:reopen-tabs? m))]
    (cond
      (nil? v) {}
      (boolean? v) {:reopen-tabs? v}
      :else {:warning (str path " has an unusable :reopen-tabs?, ignoring it")})))

(defn- mcp-listen-part
  "{:mcp-listen? b} when m holds a boolean one, {} when it holds none (absent
  or nil), and {:warning ..} otherwise."
  [path m]
  (let [v (when (map? m) (:mcp-listen? m))]
    (cond
      (nil? v) {}
      (boolean? v) {:mcp-listen? v}
      :else {:warning (str path " has an unusable :mcp-listen?, ignoring it")})))

(defn load-settings
  "{:theme k} from path, plus :dialect when the file names a known one,
  :file-tree when it holds a usable one, :reopen-tabs? and :mcp-listen? when
  they hold a boolean, and :warning when the file exists but part of it can't be used.
  A missing file is {:theme theme/default-theme} with no warning."
  [path]
  (let [f (io/file path)]
    (if-not (.exists f)
      {:theme theme/default-theme}
      (let [m (try (edn/read-string (slurp f)) (catch Exception _ ::unreadable))
            t (theme-part path m)
            d (dialect-part path m)
            ft (file-tree-part path m)
            rt (reopen-tabs-part path m)
            ml (mcp-listen-part path m)
            warnings (remove nil? [(:warning t) (:warning d) (:warning ft) (:warning rt)
                                   (:warning ml)])]
        (cond-> (merge (dissoc t :warning) (dissoc d :warning) (dissoc ft :warning)
                       (dissoc rt :warning) (dissoc ml :warning))
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
  "Merges map m (say {:theme k}, or {:file-tree {...}}) into the settings
  file at path and writes it atomically, so a save keeps every key the file
  already holds -- a theme change must not drop a hand-set :dialect or the
    file tree's state (file-tree plan P1.T2). A missing file is created with m
  alone; an unreadable or malformed one is left untouched and reported.
  nil on success, else a message.
  When path is a symlink, the real target is rewritten in place (the temp
  file sits beside the target and the rename lands on the target), so the
  link itself survives the save."
  [path m]
  (let [tmp (atom nil)]
    (try
      (let [target (resolved-target path)
            f (io/file target)
            existing (when (.exists f)
                       (let [content (try (slurp target)
                                          (catch Exception _
                                            ;; IO failure: the file is unreadable, so its
                                            ;; other keys can't be kept. Signal an error.
                                            (throw (Exception.
                                                    (str "couldn't read " target
                                                         " to keep its other settings; nothing saved")))))
                             ;; Malformed EDN: refuse rather than overwrite the file's
                             ;; other keys (final review finding 5, now that toggling
                             ;; the tree saves too). Valid EDN that isn't a map has
                             ;; nothing to keep.
                             v (try (edn/read-string content)
                                    (catch Exception _
                                      (throw (Exception.
                                              (str target " isn't valid EDN, so it wasn't overwritten; fix or delete it")))))]
                         (when (map? v) v)))]
        (reset! tmp (str target ".tmp-" (System/nanoTime)))
        (io/make-parents target)
        (spit @tmp (pr-str (merge existing m)))
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
