(ns jonquil.plugin-loader
  "Finds the user's plugin files and loads them one by one (plugins design,
  Part 2, \"Startup is per plugin\"): <settings-dir>/plugins/<id>.clj, in
  id order, each through jonquil.plugin-protocol/run-protocol, so one
  broken file is skipped and named while the others load. reload answers
  the next registry and console lines; load-step! applies one to the live
  registry, on the window's load-agent, and records each plugin it made
  live in jonquil.plugin-history; revert-step! checks an old version the
  same way and, on a pass, writes it back as a new version. reload only
  reads files; nothing here touches GTK or throws."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jonquil.command-registry :as cmd-registry]
            [jonquil.keymap :as km]
            [jonquil.plugin-check :as plugin-check]
            [jonquil.plugin-history :as history]
            [jonquil.plugin-protocol :as protocol]
            [jonquil.plugin-registry :as plugin-registry]
            [jonquil.settings :as settings]))

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

(defn plugin-file?
  "True when path is a plugin file directly in dir (<id>.clj), comparing
  canonical paths, so a symlinked folder counts."
  [dir path]
  (let [f (io/file path)
        n (.getName f)
        parent (.getParentFile (.getAbsoluteFile f))]
    (boolean (and parent (.endsWith n ".clj")
                  (re-matches id-pattern (subs n 0 (- (count n) 4)))
                  (= (.getCanonicalPath (io/file dir)) (.getCanonicalPath parent))))))

(def ^:private conditions
  ;; per scope, so a binding's :when is checked against its own scope's
  {:window (set (keys cmd-registry/window-conditions))
   :editor (set (keys cmd-registry/editor-conditions))})

(defn- before
  "reg with only the plugins whose ids sort before id: what a plugin is
  checked against, at startup and on a reload alike (plugins load and take
  their keys in id order, and the validator treats every plugin binding
  in the request as one that comes first)."
  [reg id]
  (update reg :plugins #(into {} (filter (fn [[k _]] (neg? (compare (name k) id)))) %)))

(defn- request-for
  "The validate request for id: the built-ins and reg's plugins sorting
  before id."
  [reg id os]
  (let [reg (before reg id)]
    (plugin-check/request-from-registry
                 {:commands (plugin-registry/all-commands reg) :conditions conditions
                  ;; the earlier plugins count like shipped bindings, so a
                  ;; chord one takes is warned about, or unreachable, for this one
                  :keymap {:window (into (plugin-registry/bindings-with-source reg :window)
                                         (:window km/shipped))
                           :editor (into (plugin-registry/bindings-with-source reg :editor)
                                         (:editor km/shipped))}
                  :os os})))

(defn- check
  "id's file text through the protocol against reg's earlier plugins."
  [reg id text os]
  (try (protocol/run-protocol {:where :in-process :source text :file-id id :request (request-for reg id os)})
       (catch Throwable e {:ok? false :problems [(str "checking it threw: " (ex-message e))]})))

(defn- recheck-later
  "After a reload changed the plugins in changed (ids, installed, replaced
  or removed), every live plugin sorting after the first of them is
  validated again against the final state (no load, no soak), the changed
  ones included, since one checked earlier in this pass may now depend on
  a plugin the pass removed. In id order, so a removal cascades. One that
  now fails is removed and named, as a fresh start would skip it; an
  unchanged one that passes prints its warnings, which may be new.
  [reg' lines removed-ids]."
  [reg changed os]
  (if (empty? changed)
    [reg [] #{}]
    (let [first-changed (first (sort (map name changed)))]
      (reduce (fn [[reg lines removed] [k {:keys [definition]}]]
                (let [id (name k)]
                  (if (neg? (compare id first-changed))
                    [reg lines removed]
                    (let [r (try (plugin-check/validate (assoc (request-for reg id os)
                                                               :definitions [definition] :file-id id))
                                 (catch Throwable e {:ok? false :problems [(ex-message e)]}))]
                      (cond
                        (not (:ok? r))
                        [(plugin-registry/remove-plugin reg k)
                         (into lines (map #(str "plugin " id " removed: " %) (:problems r)))
                         (conj removed k)]
                        ;; a plugin this pass loaded already printed its warnings
                        (contains? changed k) [reg lines removed]
                        :else [reg (into lines (map #(str "plugin " id ": " %) (:warnings r))) removed])))))
              [reg [] #{}]
              (sort-by key (:plugins reg))))))

(defn- passed-lines [id d warnings]
  (let [n (count (filter #(or (fn? (:edit %)) (fn? (:run %))) (:commands d)))]
    (into [(str "plugin " id " loaded (" n (if (= 1 n) " command)" " commands)"))]
          (map #(str "plugin " id ": " %) warnings))))

(defn- reload-one
  "One file [id path] against reg: [reg' lines]. Disabled: removed. Text
  (texts' entry for id, else the file's) equal to the live entry's: left
  alone. New or changed: the protocol; a pass installs it, a fail keeps
  the old entry and loads its accepted source again, so its namespace is
  what it was."
  [reg [id path] os disabled texts]
  (let [k (keyword id)
        old (get-in reg [:plugins k])]
    (if (contains? disabled k)
      [(plugin-registry/remove-plugin reg k)
       (if old [(str "plugin " id " is disabled (settings.edn :plugins :disabled)")] [])]
      (let [text (or (get texts id) (try (slurp path) (catch Throwable e e)))]
        (cond
          (instance? Throwable text) [reg [(str "plugin " id " skipped: " (ex-message text))]]
          (and old (= text (:source old))) [reg []]
          :else
          (let [r (check reg id text os)]
            (if (:ok? r)
              (let [d (:definition r)]
                [(plugin-registry/install reg (:id d) {:definition d :source text :file path :layer :user})
                 (passed-lines id d (:warnings r))])
              (let [restored (when old
                               (:problems (protocol/load-candidate {:source (:source old) :file-id id})))]
                [reg (cond-> (mapv #(str "plugin " id " skipped: " %) (:problems r))
                       old (conj (str "plugin " id ": the previous version stays live"))
                       (seq restored) (into (map #(str "plugin " id ": putting the previous version back: " %)
                                                 restored)))]))))))))

(defn reload
  "The next registry from reg and the plugin files in dir: {:registry reg'
  :lines [str ...]}. Each file in id order (or only the one at path only)
  goes through reload-one; with only nil, a live plugin whose file is gone
  is removed too. text, with only, is checked in place of that file's
  text, and the file need not exist (a revert). disabled is the set of ids
  settings.edn turns off. Never throws."
  [{:keys [dir os registry disabled only text]}]
  (let [{:keys [files skipped]} (plugin-files dir)
        disabled (set disabled)
        all-files files
        files (cond
                (and only text)
                (if (plugin-file? dir only)
                  (let [n (.getName (io/file only))] [[(subs n 0 (- (count n) 4)) only]])
                  [])
                only
                (let [c (.getCanonicalPath (io/file only))]
                  (filter (fn [[_ p]] (= c (.getCanonicalPath (io/file p)))) files))
                :else files)
        texts (when (and text (seq files)) {(first (first files)) text})
        on-disk (set (map (comp keyword first) files))
        start {:registry registry
               :lines (if only [] (mapv #(str "plugins/" % ": not a plugin id, skipped") skipped))}
        gone (when-not only (remove on-disk (keys (:plugins registry))))
        start (reduce (fn [acc k]
                        (-> acc
                            (update :registry plugin-registry/remove-plugin k)
                            (update :lines conj (str "plugin " (name k) " removed: its file is gone"))))
                      start gone)
        {reg :registry lines :lines} (reduce (fn [{reg :registry lines :lines} file]
                                               (let [[reg' more] (reload-one reg file os disabled texts)]
                                                 {:registry reg' :lines (into lines more)}))
                                             start files)
        changed-in (fn [r] (set (filter #(not (identical? (get-in registry [:plugins %]) (get-in r [:plugins %])))
                                        (into (set (keys (:plugins registry))) (keys (:plugins r))))))
        ;; a saved plugin that changed may revive the files after it that a
        ;; recheck removed earlier (they bind its commands): try those that
        ;; aren't live, as a fresh start would
        [reg lines] (if (and only (seq files) (seq (changed-in reg)))
                      (let [saved (first (first files))]
                        (reduce (fn [[r ls] [id _ :as f]]
                                  (if (and (pos? (compare id saved)) (not (contains? (:plugins r) (keyword id))))
                                    (let [[r' more] (reload-one r f os disabled nil)] [r' (into ls more)])
                                    [r ls]))
                                [reg lines] all-files))
                      [reg lines])
        changed (changed-in reg)
        [reg more removed] (recheck-later reg changed os)
        ;; a plugin loaded this pass and then removed isn't reported loaded
        lines (remove (fn [l] (some #(str/starts-with? l (str "plugin " (name %) " loaded (")) removed)) lines)]
    {:registry reg :lines (into (vec lines) more)}))

(defn load-all
  "Loads every plugin file in dir, in id order, each on its own: {:registry
  reg :lines [str ...]}, reg holding the ones that passed; disabled ids
  are skipped. A later plugin may name an earlier one's commands. os names
  chords in messages."
  [{:keys [dir os disabled]}]
  (reload {:dir dir :os os :registry (plugin-registry/empty-registry) :disabled disabled}))

(defn- step-opts
  "[warning-lines opts]: with :settings-path, the plugins folder (unless
  :dir is given) and the disabled ids come from there, and a warning about
  :plugins in the settings file is a line."
  [{:keys [settings-path] :as opts}]
  (let [settings (when settings-path (settings/load-settings settings-path))
        ;; the part of a settings warning that touches plugins: their own
        ;; key, or a file that couldn't be read at all (its disabled ids
        ;; are lost then, so say why)
        warn (some->> (:warning settings) (#(str/split % #"; "))
                      (filter #(or (str/includes? % ":plugins") (str/includes? % "couldn't read")))
                      seq (mapv #(str "settings: " %)))]
    [(vec warn)
     (cond-> opts
       settings-path (assoc :dir (or (:dir opts) (plugins-dir settings-path))
                            :disabled (:disabled (:plugins settings))))]))

(defn- record-changed!
  "Records, as a hand edit, each plugin in reg' whose source isn't reg's
  (installed or replaced), unless its text is already the latest version.
  The lines for any that couldn't be recorded."
  [dir reg reg' now]
  (vec (for [[k {:keys [source]}] (sort-by key (:plugins reg'))
             :when (not= source (get-in reg [:plugins k :source]))
             :let [r (history/record! {:plugins-dir dir :id (name k) :text source
                                       :source :user :reason "edited by hand" :now now})]
             :when (:error r)]
         (str "plugin " (name k) ": its history wasn't recorded: " (:error r)))))

(defn load-step!
  "One load against the live registry atom: reads it, reloads (opts are
  reload's, less :registry, plus :now for the history's clock; with
  :settings-path, the plugins folder and the disabled ids come from
  there, and a warning about :plugins in the settings file is a line),
  resets it, records each plugin it made live in the history, and answers
  the console lines. Run on the window's plugin agent, one step at a time,
  so a step always starts from what the previous one left. Never throws:
  a failure is one line, and live is left as it was."
  [live opts]
  (try
    (let [[warn opts] (step-opts opts)
          old @live
          {:keys [registry lines]} (reload (assoc opts :registry old))]
      (reset! live registry)
      (-> warn (into lines) (into (record-changed! (:dir opts) old registry (:now opts)))))
    (catch Throwable e
      [(str "loading plugins failed: " (or (ex-message e) (str e)))])))

(defn revert-step!
  "Reverts plugin id to its version n, on the plugin agent like load-step!
  (opts as load-step!'s, plus :id, :n and an optional :reason): the old
  source goes through reload, so the same checks and the same re-check of
  later plugins run, and only on a pass is it written to <id>.clj, live
  reset, and a :revert version recorded, \"revert to vN\" plus the reason.
  The console lines. Never throws."
  [live {:keys [id n reason now] :as opts}]
  (try
    (let [[warn opts] (step-opts opts)
          dir (:dir opts)
          k (keyword id)
          src (history/version-source (history/history-dir dir id) n)]
      (cond
        (nil? src) (conj warn (str "plugin " id " has no v" n " to revert to"))
        (contains? (set (:disabled opts)) k) (conj warn (str "plugin " id " is disabled, so it wasn't reverted"))
        :else
        (let [old @live
              path (str dir "/" id ".clj")
              {:keys [registry lines]} (reload (assoc opts :registry old :only path :text src))]
          (if (not= src (get-in registry [:plugins k :source]))
            (-> warn (into lines) (conj (str "plugin " id " wasn't reverted to v" n)))
            (if-let [why (history/write-atomic! path src)]
              ;; the check loaded src into the plugin's namespace: put the
              ;; live version back, as reload-one does on a fail (review I3)
              (let [restored (when-let [live-src (get-in old [:plugins k :source])]
                               (:problems (protocol/load-candidate {:source live-src :file-id id})))]
                (-> warn
                    (conj (str "plugin " id " wasn't reverted: " why))
                    (into (map #(str "plugin " id ": putting the live version back: " %) restored))))
              (let [_ (reset! live registry)
                    r (history/record! {:plugins-dir dir :id id :text src :source :revert :always? true :now now
                                        :reason (str "revert to v" n
                                                     (when-not (str/blank? reason) (str ": " reason)))})
                    others (record-changed! dir old (update registry :plugins dissoc k) now)]
                (-> warn (into lines) (into others)
                    (conj (if (:n r)
                            (str "plugin " id " reverted to v" n " (now v" (:n r) ")")
                            (str "plugin " id " reverted to v" n ", but its history wasn't recorded: " (:error r)))))))))))
    (catch Throwable e
      [(str "reverting plugin " id " failed: " (or (ex-message e) (str e)))])))

(defn load-agent
  "The agent every plugin load runs on, one at a time. It keeps going after
  a throw (error mode :continue), handing the throw to on-error, so one bad
  step can't stop every later load."
  [on-error]
  (agent nil :error-mode :continue :error-handler (fn [_ e] (on-error e))))

(defn plugin-flags
  "{:args args-without-the-flag :plugins? bool}: plugins are off with
  --no-plugins, or JONQUIL_NO_PLUGINS=1 in env (fn [name] value) for a Dock
  or Finder launch, which can't pass arguments."
  [args env]
  {:args (vec (remove #{"--no-plugins"} args))
   :plugins? (not (or (some #{"--no-plugins"} args) (= "1" (env "JONQUIL_NO_PLUGINS"))))})
