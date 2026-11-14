(ns jonquil.settings
  "The user's saved settings: a small EDN map at
  $XDG_CONFIG_HOME/jonquil/settings.edn, or ~/.config/jonquil/settings.edn
  when XDG_CONFIG_HOME is unset or blank. It stores :theme, :dialect (the
  fallback dialect for a file no project decides) when the user set one by
  hand, :file-tree {:visible? :width}, the booleans :reopen-tabs?, :remember-session? and :mcp-listen?,
  and :parinfer (:off unless set), and :key-set (the chosen key set, jonquil.key-sets). A save
  merges into what the file already holds, so each feature only writes its
  own keys. Loading never throws: a missing file gives the defaults with no
  warning, and an unreadable file, an unknown theme or an unknown dialect is the
  default plus a :warning. Saving writes
  a temp file beside the target and renames it over the target with
  ATOMIC_MOVE (the same move jonquil.gtk.window's Save uses), and returns
  nil or an error string instead of throwing."
  (:require [jonquil.docs-panel :as docs-panel]
            [jonquil.layouts :as layouts]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jonquil.dialect :as dialect]
            [jonquil.key-sets :as key-sets]
            [jonquil.theme :as theme]))

(defn settings-path
  "The settings file's path. env is (fn [name] value), System/getenv in
  production and a map in tests; home is the user's home directory. A
  relative XDG_CONFIG_HOME (anything not starting with \"/\") is invalid
  per the XDG spec and is treated like a blank one."
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
  {:warning ..} otherwise."
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

(defn- remember-session-part
  "{:remember-session? b} when m holds a boolean one, {} when it holds none
  (absent or nil), and {:warning ..} otherwise."
  [path m]
  (let [v (when (map? m) (:remember-session? m))]
    (cond
      (nil? v) {}
      (boolean? v) {:remember-session? v}
      :else {:warning (str path " has an unusable :remember-session?, ignoring it")})))

(defn- mcp-listen-part
  "{:mcp-listen? b} when m holds a boolean one, {} when it holds none (absent
  or nil), and {:warning ..} otherwise."
  [path m]
  (let [v (when (map? m) (:mcp-listen? m))]
    (cond
      (nil? v) {}
      (boolean? v) {:mcp-listen? v}
      :else {:warning (str path " has an unusable :mcp-listen?, ignoring it")})))

(def parinfer-modes
  "The values :parinfer may hold, shared with .jonquil.edn's reader. :off
  is the default, and a file with no :parinfer means :off."
  dialect/parinfer-modes)

(defn- parinfer-part
  "{:parinfer k} when m holds one of parinfer-modes, {} when it holds none,
  and {:warning ..} otherwise."
  [path m]
  (let [v (when (map? m) (:parinfer m))]
    (cond
      (nil? v) {}
      (contains? parinfer-modes v) {:parinfer v}
      :else {:warning (str path " has an unknown :parinfer " (pr-str v) ", using :off")})))

(defn- key-set-part
  "{:key-set id} when m holds a key set Jonquil has (jonquil.key-sets/ids),
  {} when it holds none, and {:warning ..} otherwise."
  [path m]
  (let [v (when (map? m) (:key-set m))]
    (cond
      (nil? v) {}
      (contains? key-sets/ids v) {:key-set v}
      :else {:warning (str path " has an unknown :key-set " (pr-str v) ", using the default keys")})))

(defn- plugins-part
  "{:plugins {:disabled #{id ...}}} when m's :plugins holds a set of
  keyword ids under :disabled, {} when there's none, and {:warning ..}
  otherwise (the plugin list writes it; this only reads it)."
  [path m]
  (let [v (when (map? m) (:plugins m))
        d (when (map? v) (:disabled v))]
    (cond
      (nil? v) {}
      (and (map? v) (nil? d)) {}
      (and (set? d) (every? keyword? d)) {:plugins {:disabled d}}
      :else {:warning (str path "'s :plugins :disabled isn't a set of plugin ids, ignoring it")})))

(defn- layouts-part
  "{:layouts {name layout}} keeping the entries with a string name and a
  map layout (LM8's named layouts), {} when there are none, and a
  :warning when some were dropped."
  [path m]
  (let [v (when (map? m) (:layouts m))]
    (cond
      (nil? v) {}
      (not (map? v)) {:warning (str path "'s :layouts isn't a map, ignoring it")}
      :else (let [good (into {} (filter (fn [[k l]] (and (string? k) (layouts/valid? l)))) v)]
              (cond-> {}
                (seq good) (assoc :layouts good)
                (< (count good) (count v)) (assoc :warning (str path "'s :layouts has entries that aren't a name and a layout, ignoring them")))))))

(defn- doc-bookmarks-part
  "{:doc-bookmarks [page ...]} keeping the valid pages of m's list once each
  (LM6's docs panel), {} when there are none, and a :warning when the list
  isn't one or some entries were dropped."
  [path m]
  (let [v (when (map? m) (:doc-bookmarks m))]
    (cond
      (nil? v) {}
      (not (sequential? v)) {:warning (str path "'s :doc-bookmarks isn't a list, ignoring it")}
      :else (let [good (reduce docs-panel/add-bookmark [] v)]
              (cond-> {}
                (seq good) (assoc :doc-bookmarks good)
                (< (count good) (count v)) (assoc :warning (str path "'s :doc-bookmarks has entries that aren't a doc page, ignoring them")))))))

(defn- settings-parts
  "Each settings key's part of the file map m read from path, in a fixed
  order: {key value, :warning msg} maps, either one absent when empty."
  [path m]
  (map #(% path m)
       [theme-part dialect-part file-tree-part reopen-tabs-part remember-session-part
        mcp-listen-part plugins-part parinfer-part key-set-part layouts-part doc-bookmarks-part]))

(defn load-settings
  "{:theme k} from path, plus :dialect when the file names a known one,
  :file-tree when it holds a usable one, :reopen-tabs?, :remember-session? and
  :mcp-listen? when they hold a boolean, :plugins when it holds a set of disabled ids,
  :parinfer when it holds one of parinfer-modes, :key-set when it holds a
  key set's id, :layouts when it holds
  named layouts, :doc-bookmarks when it holds doc pages, and
  :warning when the file exists but part of it can't be used.
  A missing file is {:theme theme/default-theme} with no warning."
  [path]
  (let [f (io/file path)]
    (if-not (.exists f)
      {:theme theme/default-theme}
      (let [parts (settings-parts path (try (edn/read-string (slurp f)) (catch Exception _ ::unreadable)))
            warnings (keep :warning parts)]
        (cond-> (apply merge (map #(dissoc % :warning) parts))
          (seq warnings) (assoc :warning (str/join "; " warnings)))))))

(defn read-doc-bookmarks
  "The docs panel's bookmarks as the file at path holds them, for a change
  that will write them back: {:bookmarks [page ...]} (none when the file or
  the key is missing), or {:error msg} when the file can't be read, isn't
  EDN, or its :doc-bookmarks isn't a list of valid pages without repeats.
  load-settings drops what it can't use, so a write built from it would
  delete what the person wrote; this refuses instead."
  [path]
  (let [f (io/file path)]
    (if-not (.exists f)
      {:bookmarks []}
      (let [m (try (edn/read-string (slurp f)) (catch Exception _ ::unreadable))
            v (when (map? m) (:doc-bookmarks m))
            good (when (sequential? v) (reduce docs-panel/add-bookmark [] v))]
        (cond
          (= ::unreadable m) {:error (str "couldn't read " path)}
          (not (map? m)) {:bookmarks []}
          (nil? v) {:bookmarks []}
          (not (sequential? v)) {:error (str path "'s :doc-bookmarks isn't a list")}
          (not= (count good) (count v)) {:error (str path "'s :doc-bookmarks has entries that aren't a doc page, or repeats")}
          :else {:bookmarks good})))))

(defn- resolved-target
  "path itself, unless it exists and is a symlink, in which case the real
  file it points at (a save would otherwise replace the symlink
  with a plain file, so a dotfiles-managed settings.edn lost its link and
  the linked target never saw the new value). Measured on this Jolt pin
  (2026-09-26): java.nio.file.Files/isSymbolicLink,
  Files/createSymbolicLink and Path/.toRealPath all work. isSymbolicLink
  answers false for a path that doesn't exist, so no separate .exists
  check is needed."
  [path]
  (let [p (.toPath (java.io.File. ^String path))]
    (if (java.nio.file.Files/isSymbolicLink p)
      (str (.toRealPath p (make-array java.nio.file.LinkOption 0)))
      path)))

(defn- existing-settings
  "The settings map in file f (at target) to merge a save into, nil when
  there's none to keep. Throws, with the message save-settings! reports,
  when it can't be read or isn't EDN, so nothing gets overwritten."
  [target ^java.io.File f]
  (when (.exists f)
    (let [content (try (slurp target)
                       (catch Exception _
                         ;; IO failure: the file is unreadable, so its
                         ;; other keys can't be kept. Signal an error.
                         (throw (Exception.
                                 (str "couldn't read " target
                                      " to keep its other settings; nothing saved")))))
          ;; Malformed EDN: refuse rather than overwrite the file's
          ;; other keys (toggling the tree saves too). Valid EDN that isn't a map has
          ;; nothing to keep.
          v (try (edn/read-string content)
                 (catch Exception _
                   (throw (Exception.
                           (str target " isn't valid EDN, so it wasn't overwritten; fix or delete it")))))]
      (when (map? v) v))))

(defn- move-over!
  "Moves the file tmp over target in one atomic step."
  [tmp target]
  (java.nio.file.Files/move
   (.toPath (java.io.File. ^String tmp))
   (.toPath (java.io.File. ^String target))
   (into-array java.nio.file.CopyOption
               [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                java.nio.file.StandardCopyOption/ATOMIC_MOVE])))

(defn save-settings!
  "Merges map m (say {:theme k}, or {:file-tree {...}}) into the settings
  file at path and writes it atomically, so a save keeps every key the file
  already holds -- a theme change must not drop a hand-set :dialect or the
  file tree's state. A missing file is created with m
  alone; an unreadable or malformed one is left untouched and reported.
  nil on success, else a message.
  When path is a symlink, the real target is rewritten in place (the temp
  file sits beside the target and the rename lands on the target), so the
  link itself survives the save."
  [path m]
  (let [tmp (atom nil)]
    (try
      (let [target (resolved-target path)
            existing (existing-settings target (io/file target))]
        (reset! tmp (str target ".tmp-" (System/nanoTime)))
        (io/make-parents target)
        (spit @tmp (pr-str (merge existing m)))
        (move-over! @tmp target)
        nil)
      (catch Exception e
        (when @tmp (try (io/delete-file @tmp true) (catch Exception _ nil)))
        (str "couldn't save " path ": " (.getMessage e))))))

(defn- read-for-update
  "The settings map at target before an update, or nil when there's no
  file. Throws, with the message the caller reports, when it can't be read
  or isn't EDN, so nothing gets overwritten."
  [target]
  (when (.exists (io/file target))
    (let [content (try (slurp target)
                       (catch Exception _
                         (throw (Exception. (str "couldn't read " target "; nothing saved")))))]
      (try (edn/read-string content)
           (catch Exception _
             (throw (Exception. (str target " isn't valid EDN, so it wasn't overwritten; fix or delete it"))))))))

(defn- plugins-problem
  "Why the settings file at target can't take a plugin change, nil when it
  can: plugins (its :plugins) must be a map and d (its :disabled) a set of
  plugin ids when they are there at all."
  [target plugins d]
  (cond
    (and (some? plugins) (not (map? plugins)))
    (str target "'s :plugins isn't a map, so it wasn't changed; fix it by hand")
    (and (some? d) (not (and (set? d) (every? keyword? d))))
    (str target "'s :plugins :disabled isn't a set of plugin ids, so it wasn't changed; fix it by hand")))

(defn save-plugin-disabled!
  "Adds plugin id (a keyword) to the settings file's :plugins :disabled, or
  with disabled? false takes it out, keeping every other key, :plugins'
  own included (save-settings! merges only the top level, so
  handing it a :plugins map alone would drop :trusted-projects). Refuses,
  naming the file, when :plugins isn't a map or :disabled isn't a set of
  ids, and when the file can't be read or parsed. nil on success, else a
  message."
  [path id disabled?]
  (try
    (let [target (resolved-target path)
          m (read-for-update target)
          plugins (when (map? m) (:plugins m))
          d (when (map? plugins) (:disabled plugins))]
      (or (plugins-problem target plugins d)
          (save-settings! path {:plugins (update (or plugins {}) :disabled (fnil (if disabled? conj disj) #{}) id)})))
    (catch Exception e (str "couldn't save " path ": " (.getMessage e)))))
