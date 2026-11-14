(ns jonquil.gtk.parts.prefs
  "The Preferences window as a window part (the command and its key are
  jonquil.parts.prefs/spec). Its Keys tab lists the live key bindings,
  records a new key for one, and writes your keys.edn (jonquil.user-keys),
  which the command palette part then reloads."
  (:require [clojure.string :as str]
            [jonquil.gtk.prefs-window :as pw]
            [jonquil.key-prefs :as kp]
            [jonquil.keymap :as km]
            [jonquil.plugin-registry :as plugin-registry]
            [jonquil.user-keys :as user-keys]))

(def ^:private KEY-ESCAPE (km/key-value "Escape"))

(defn- os [] (System/getProperty "os.name"))

(defn- named
  "A command's description for the middle of a sentence: in quotes, without
  its full stop."
  [doc]
  (str "\"" (str/replace (or doc "") #"\.$" "") "\""))

(defn- live-keymap []
  (let [plugins @plugin-registry/live]
    {:window (plugin-registry/window-bindings plugins)
     :editor (plugin-registry/editor-bindings plugins)}))

(defn- all-rows
  "Every row of the Keys tab, from the live key map."
  []
  (kp/rows {:keymap (live-keymap)
            :commands (plugin-registry/all-commands @plugin-registry/live)
            :user @user-keys/live :os (os)}))

(defn- refresh!
  "Shows the rows that match the search box."
  [{:keys [win]}]
  (pw/show-rows! win (kp/filter-rows (all-rows) (pw/search-text win))))

(defn- idle!
  "Ends whatever the tab was in the middle of and says text."
  [{:keys [win state] :as ctx} text]
  (reset! state nil)
  (refresh! ctx)
  (pw/say! win text nil))

(defn- write-keys!
  "Writes user as keys.edn beside the window's settings file, whole, by a
  temp file moved over it, then has the window reload it. Answers nil, or
  the sentence saying why it couldn't."
  [{:keys [settings-path*]} user]
  (let [path (user-keys/keys-path @settings-path*)
        tmp (str path ".tmp")]
    (try
      (.mkdirs (.getParentFile (java.io.File. ^String path)))
      (spit tmp (kp/file-text user))
      (java.nio.file.Files/move
       (.toPath (java.io.File. ^String tmp)) (.toPath (java.io.File. ^String path))
       (into-array java.nio.file.CopyOption
                   [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                    java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
      (if-let [reload! (:reload! @user-keys/window-fns)]
        (reload!)
        (reset! user-keys/live user))
      nil
      (catch Exception e
        (.delete (java.io.File. ^String tmp))
        (str "Couldn't write " path ": " (ex-message e))))))

(defn- keys-text
  "keys.edn's text, or nil when there is no file or it can't be read."
  [{:keys [settings-path*]}]
  (let [f (java.io.File. ^String (user-keys/keys-path @settings-path*))]
    (when (.exists f)
      (try (slurp f) (catch Exception _ nil)))))

(defn- apply-change!
  "Writes user and says done, or says why it couldn't. A file with
  comments of yours is asked about first, once: change is {:user :done} and
  is kept in state until the answer."
  [{:keys [win state] :as ctx} {:keys [user done] :as change} sure?]
  (if (and (not sure?) (kp/hand-comments? (keys-text ctx)))
    (do (reset! state {:mode :comments :change change})
        (pw/say! win "keys.edn has comments of yours, and a change made here rewrites the file without them."
                 ["Change anyway" "Cancel"]))
    (idle! ctx (or (write-keys! ctx user) done))))

(defn- commit!
  "Records chord for row, taking the chord from taken's binding when there
  is one."
  [ctx row chord taken]
  (apply-change! ctx {:user (kp/record @user-keys/live row chord taken)
                      :done (str (kp/chord-text chord (os)) " now runs " (named (:doc row)) ".")}
                 false))

(defn- start-record!
  "A click on row index's key: the next key press is its new key."
  [{:keys [win state] :as ctx} index]
  (when-let [row (get (pw/rows win) index)]
    (if (:fixed? row)
      (pw/say! win "That key is part of a chain of keys and can't be changed here." nil)
      (do (when @state (refresh! ctx))
          (reset! state {:mode :recording :row row :index index})
          (pw/set-key-label! win index "Press keys…")
          (pw/say! win (str "Press the new key for " (named (:doc row)) ". Esc cancels.") nil)))))

(defn- recorded!
  "A chord arrived for the row being recorded: asks before taking it from
  another command, else records it."
  [{:keys [win state] :as ctx} {:keys [row]} chord]
  (let [taken (kp/conflict {:keymap (live-keymap) :command (:command row) :os (os)} chord)
        commands (plugin-registry/all-commands @plugin-registry/live)]
    (if taken
      (let [b (:binding taken)
            what (if-let [id (:command b)]
                   (str "runs " (named (get-in commands [id :doc])))
                   "is one Jonquil keeps for itself")]
        (reset! state {:mode :conflict :row row :chord chord :taken taken})
        (pw/say! win (str (kp/chord-text chord (os)) " " what ". Replace it?") ["Replace" "Cancel"]))
      (commit! ctx row chord nil))))

(defn- on-key
  "The window's key handler. While a key is being recorded every key is
  the tab's: Escape cancels, a modifier alone waits, a key Jonquil can't
  bind says so, and a chord is recorded. Otherwise the key is GTK's."
  [{:keys [win state] :as ctx} keyval gdk-state]
  (let [s @state]
    (if (not= :recording (:mode s))
      0
      (let [{:keys [chord problem]} (kp/press-chord keyval gdk-state (os))]
        (cond
          (and (= KEY-ESCAPE keyval) (nil? (:mods chord))) (idle! ctx "Cancelled.")
          problem (pw/say! win problem nil)
          chord (recorded! ctx s chord))
        1))))

(defn- on-answer
  "Replace or Change anyway (yes? true), or Cancel."
  [{:keys [state] :as ctx} yes?]
  (let [{:keys [mode row chord taken change]} @state]
    (cond
      (not yes?) (idle! ctx "Cancelled.")
      (= :conflict mode) (commit! ctx row chord taken)
      (= :comments mode) (apply-change! ctx change true))))

(defn- on-reset
  "Reset this key: the selected row's command goes back to its plugin's or
  Jonquil's keys."
  [{:keys [win] :as ctx} index]
  (if-let [row (when index (get (pw/rows win) index))]
    (let [user (kp/reset-command @user-keys/live (:command row))]
      (if (= user @user-keys/live)
        (idle! ctx (str (named (:doc row)) " already has its own keys."))
        (apply-change! ctx {:user user :done (str (named (:doc row)) " is back to its own keys.")} false)))
    (pw/say! win "Select a row first." nil)))

(defn- install
  "The Preferences part for one window: builds the window, hidden, and
  answers the fn that opens it. ui's :prefs is {:window :open! :on-key
  :record! :answer! :reset! :state}, the same fns the widgets call, for the
  self-tests."
  [{:keys [win] :as wctx}]
  (let [w (pw/make-window win)
        ctx (assoc wctx :win w :state (atom nil))
        open! (fn [] (idle! ctx "") (pw/present! w))
        on-key! (fn [keyval gdk-state] (on-key ctx keyval gdk-state))]
    (pw/set-handlers!
     w {:on-search (fn [_text] (when (nil? @(:state ctx)) (refresh! ctx)))
        :on-record (fn [i] (start-record! ctx i))
        :on-answer (fn [yes?] (on-answer ctx yes?))
        :on-open-file (fn [] (when-let [f (:open! @user-keys/window-fns)] (f)))
        :on-reset (fn [i] (on-reset ctx i))
        :on-key on-key!})
    {:fns {:prefs-open-fn open!}
     :ui {:prefs {:window w :open! open! :on-key on-key!
                  :record! (fn [i] (start-record! ctx i))
                  :answer! (fn [yes?] (on-answer ctx yes?))
                  :reset! (fn [i] (on-reset ctx i))
                  :state (:state ctx)}}}))

(def part
  "The Preferences part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :prefs}))
