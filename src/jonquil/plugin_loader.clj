(ns jonquil.plugin-loader
  "Finds the user's plugin files and loads them one by one (plugins design,
  Part 2, \"Startup is per plugin\"): <settings-dir>/plugins/<id>.clj, in
  id order, each through jonquil.plugin-protocol/run-protocol, so one
  broken file is skipped and named while the others load. Answers a
  registry value and console lines; the window swaps it in. Reads files,
  touches no GTK, and never throws."
  (:require [clojure.java.io :as io]
            [jonquil.command-registry :as cmd-registry]
            [jonquil.keymap :as km]
            [jonquil.plugin-check :as plugin-check]
            [jonquil.plugin-protocol :as protocol]
            [jonquil.plugin-registry :as plugin-registry]))

(def ^:private id-pattern #"[a-z][a-z0-9-]*")

(defn plugins-dir
  "The plugins folder beside the settings file at settings-path."
  [settings-path]
  (str (.getParent (io/file settings-path)) "/plugins"))

(defn plugin-files
  "{:files [[id path] ...] :skipped [name ...]}: the .clj files in dir whose
  base name is a plugin id, sorted by id, and the .clj files whose name
  isn't. Anything else is ignored; a missing dir has neither."
  [dir]
  (let [fs (some->> (.listFiles (io/file dir)) seq (filter #(.isFile ^java.io.File %)))
        cljs (filter #(.endsWith ^String (.getName ^java.io.File %) ".clj") fs)
        id-of (fn [f] (let [n (.getName ^java.io.File f)] (subs n 0 (- (count n) 4))))
        ok? (fn [f] (boolean (re-matches id-pattern (id-of f))))]
    {:files (vec (sort-by first (for [f cljs :when (ok? f)] [(id-of f) (.getPath ^java.io.File f)])))
     :skipped (vec (sort (for [f cljs :when (not (ok? f))] (.getName ^java.io.File f))))}))

(def ^:private conditions
  ;; per scope, so a binding's :when is checked against its own scope's
  {:window (set (keys cmd-registry/window-conditions))
   :editor (set (keys cmd-registry/editor-conditions))})

(defn- load-one
  "One file through the protocol against reg (the built-ins plus the
  plugins already loaded): [reg' lines]."
  [reg [id path] os]
  (let [text (try (slurp path) (catch Throwable e e))]
    (if (instance? Throwable text)
      [reg [(str "plugin " id " skipped: " (ex-message text))]]
      (let [request (plugin-check/request-from-registry
                     {:commands (plugin-registry/all-commands reg) :conditions conditions
                      ;; the plugins already loaded count like shipped bindings, so a
                      ;; chord one takes is warned about, or unreachable, for the next
                      :keymap {:window (into (plugin-registry/bindings-with-source reg :window)
                                             (:window km/shipped))
                               :editor (into (plugin-registry/bindings-with-source reg :editor)
                                             (:editor km/shipped))}
                      :os os})
            r (try (protocol/run-protocol {:where :in-process :source text :file-id id :request request})
                   (catch Throwable e {:ok? false :problems [(str "checking it threw: " (ex-message e))]}))]
        (if (:ok? r)
          (let [d (:definition r)
                edits (count (filter #(fn? (:edit %)) (:commands d)))
                runs (count (filter #(fn? (:run %)) (:commands d)))]
            [(plugin-registry/install reg (:id d) {:definition d :source text :file path :layer :user})
             (cond-> [(str "plugin " id " loaded (" edits (if (= 1 edits) " command)" " commands)"))]
               true (into (map #(str "plugin " id ": " %) (:warnings r)))
               (pos? runs) (conj (str "plugin " id ": its :run commands wait for effects, a later slice")))])
          [reg (mapv #(str "plugin " id " skipped: " %) (:problems r))])))))

(defn load-all
  "Loads every plugin file in dir, in id order, each on its own: {:registry
  reg :lines [str ...]}, reg holding the ones that passed. A later plugin
  may name an earlier one's commands. os names chords in messages."
  [{:keys [dir os]}]
  (let [{:keys [files skipped]} (plugin-files dir)]
    (reduce (fn [{:keys [registry lines]} file]
              (let [[reg' more] (load-one registry file os)]
                {:registry reg' :lines (into lines more)}))
            {:registry (plugin-registry/empty-registry)
             :lines (mapv #(str "plugins/" % ": not a plugin id, skipped") skipped)}
            files)))

(defn plugin-flags
  "{:args args-without-the-flag :plugins? bool}: plugins are off with
  --no-plugins, or JONQUIL_NO_PLUGINS=1 in env (fn [name] value) for a Dock
  or Finder launch, which can't pass arguments."
  [args env]
  {:args (vec (remove #{"--no-plugins"} args))
   :plugins? (not (or (some #{"--no-plugins"} args) (= "1" (env "JONQUIL_NO_PLUGINS"))))})
