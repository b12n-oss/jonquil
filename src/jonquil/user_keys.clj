(ns jonquil.user-keys
  "Your own key bindings: keys.edn beside the settings file, read into
  {:window [...] :editor [...] :remove [...]} and laid over the plugins' and
  the shipped bindings. No GTK here.

  :window and :editor hold bindings shaped like jonquil/keymap.edn's. They
  go ahead of every other binding in their scope, so the first match is
  yours. :remove names a plugin's or a shipped binding by its chord and
  command, and that binding is dropped.

  A bad entry is skipped and named, and the rest load. Text that doesn't
  read loads nothing. Neither stops Jonquil from starting."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [jonquil.keymap :as km]))

(def no-keys
  "What loads from no file, or from one with nothing usable in it."
  {:window [] :editor [] :remove []})

(defonce ^{:doc "The key bindings loaded from keys.edn, shaped like no-keys.
  jonquil.plugin-registry's window-bindings and editor-bindings lay it over
  theirs; the window sets it when it starts and on a reload."}
  live
  (atom no-keys))

(defonce ^{:doc "{:reload! (fn []) :open! (fn [])} for the window that owns
  keys.edn: reload! reads the file again and reports in the console, open!
  shows it in a tab. The command palette part sets it, and Preferences
  calls it after it has written the file."}
  window-fns
  (atom {}))

(defn keys-path
  "keys.edn's path: beside the settings file at settings-path."
  [settings-path]
  (str (.getParent (java.io.File. ^String settings-path)) "/keys.edn"))

(def template
  "The text of a new keys.edn. It binds nothing."
  (str ";; Your own key bindings for Jonquil. Saving this file in Jonquil reloads it,\n"
       ";; and so does \"Reload your key bindings\" in the command palette.\n"
       ";;\n"
       ";; :window keys work anywhere in the window, :editor keys while the editor\n"
       ";; has the focus. Yours are tried first, so they win over Jonquil's own.\n"
       ";; :remove drops one of Jonquil's or a plugin's bindings: name its key,\n"
       ";; its modifiers and its command.\n"
       ";;\n"
       ";; \"List every key binding\" in the palette prints each command's id and\n"
       ";; keys. Modifiers are :cmd (Cmd on a Mac, Ctrl elsewhere), :shift, :alt,\n"
       ";; :ctrl, :meta and :super.\n"
       ";;\n"
       ";; {:window [{:key \"e\" :mods #{:cmd :shift} :command :run/run}]\n"
       ";;  :editor []\n"
       ";;  :remove [{:key \"r\" :mods #{:cmd} :command :run/run}]}\n"
       "{:window []\n"
       " :editor []\n"
       " :remove []}\n"))

(defn- scope-word [scope] (if (= :editor scope) "an editor" "a window"))

(defn- binding-problem
  "What's wrong with binding b of scope, or nil: jonquil.keymap's own checks,
  then that its command belongs to scope."
  [b scope {:keys [commands conditions]}]
  (if-not (map? b)
    "isn't a map"
    (let [found (km/problems [b] (set (keys commands)) (get conditions scope #{}))
          other (:scope (get commands (:command b)))]
      (cond
        (seq found) (str/join ", " (map #(str/replace-first % "binding 0 " "") found))
        (and other (not= scope other))
        (str "names " (pr-str (:command b)) ", which is " (scope-word other) " command")))))

(defn- remove-problem
  "What's wrong with :remove entry r, or nil. It needs a chord and a command."
  [r]
  (cond
    (not (map? r)) "isn't a map"
    (and (nil? (:key r)) (nil? (:keys r))) "has neither :key nor :keys"
    (not (contains? r :command)) "has no :command"))

(defn- checked
  "[kept problems] for the entries under k: each entry that problem-of
  passes is kept, and each one it doesn't is named by its place, from 1."
  [m k problem-of]
  (let [v (get m k)]
    (cond
      (nil? v) [[] []]
      (not (sequential? v)) [[] [(str k " isn't a vector; skipped")]]
      :else
      (reduce (fn [[kept problems] [i e]]
                (if-let [p (problem-of e)]
                  [kept (conj problems (str k " entry " (inc i) " " p "; skipped"))]
                  [(conj kept e) problems]))
              [[] []]
              (map-indexed vector v)))))

(defn- read-map
  "{:value m} for text's first form when it is a map (or text is blank),
  else {:problem sentence}."
  [text]
  (if (str/blank? text)
    {:value {}}
    (try
      (let [m (edn/read-string text)]
        (cond
          (nil? m) {:value {}}
          (map? m) {:value m}
          :else {:problem "the file must hold a map, {:window [...] :editor [...] :remove [...]}"}))
      (catch Exception e
        {:problem (str "couldn't be read (" (ex-message e) "), so none of it is loaded")}))))

(defn read-keys
  "keys.edn's text as {:window [...] :editor [...] :remove [...] :problems
  [sentence ...]}. opts is {:commands {id {:scope s}} :conditions {scope
  #{id ...}}}, the registered commands and conditions."
  [text opts]
  (let [{:keys [value problem]} (read-map text)]
    (if problem
      (assoc no-keys :problems [problem])
      (let [unknown (remove #{:window :editor :remove} (keys value))
            [window wp] (checked value :window #(binding-problem % :window opts))
            [editor ep] (checked value :editor #(binding-problem % :editor opts))
            [removed rp] (checked value :remove remove-problem)]
        {:window window :editor editor :remove removed
         :problems (vec (concat (when (seq unknown)
                                  [(str "has unknown keys " (pr-str (vec unknown))
                                        ", only :window :editor :remove")])
                                wp ep rp))}))))

(defn- chord
  "What identifies a binding for :remove: its key names, its modifiers and
  its command."
  [b]
  [(set (if (:key b) [(:key b)] (:keys b)))
   (set (or (:exact b) (:mods b)))
   (:command b)])

(defn layer
  "base, the plugins' and the shipped bindings of scope, with user's laid
  over it: the bindings user's :remove names are dropped, and user's own
  bindings for scope go first."
  [user scope base]
  (let [gone (set (map chord (:remove user)))
        kept (if (seq gone) (remove #(contains? gone (chord %)) base) base)]
    (into (vec (get user scope)) kept)))

(defn load-keys
  "Reads keys.edn at path: {:status :absent | :loaded, :keys (shaped like
  no-keys), :problems [sentence ...]}. A file that can't be opened counts as
  loaded with one problem."
  [path opts]
  (let [f (java.io.File. ^String path)]
    (if-not (.exists f)
      {:status :absent :keys no-keys :problems []}
      (let [r (try (read-keys (slurp f) opts)
                   (catch Exception e
                     (assoc no-keys :problems [(str "couldn't be opened (" (ex-message e) ")")])))]
        {:status :loaded :keys (dissoc r :problems) :problems (:problems r)}))))

(defn report-lines
  "Console lines for a load of path: nothing for a missing file, else how
  many bindings loaded and each problem."
  [path {:keys [status keys problems]}]
  (if (= :absent status)
    []
    (let [n (+ (count (:window keys)) (count (:editor keys)))
          gone (count (:remove keys))
          loaded (str (case n 0 "no key bindings" 1 "1 key binding" (str n " key bindings"))
                      " of yours loaded"
                      (when (pos? gone) (str ", " gone " removed"))
                      ".")]
      (into [(str "; " path ": " loaded)]
            (map #(str "; " path ": " %))
            problems))))
