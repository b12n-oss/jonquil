(ns jonquil.gtk.parts.prefs
  "The Preferences window as a window part (the command and its key are
  jonquil.parts.prefs/spec). Its Keys tab chooses the key set
  (jonquil.key-sets), lists the live key bindings,
  records a new key for one, and writes your keys.edn (jonquil.user-keys),
  which the command palette part then reloads."
  (:require [clojure.string :as str]
            [jonquil.gtk.prefs-window :as pw]
            [jonquil.key-prefs :as kp]
            [jonquil.key-sets :as key-sets]
            [jonquil.keymap :as km]
            [jonquil.plugin-registry :as plugin-registry]
            [jonquil.settings :as settings]
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
            :user @user-keys/live :os (os)
            :key-set (let [id @key-sets/live]
                       {:label (key-sets/label id)
                        :window (key-sets/bindings id :window (os))
                        :editor (key-sets/bindings id :editor (os))})}))

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

(defn- step-row
  "The row a step is added to. first-chord, when there is one, is a chord
  recorded just before and not written yet: the row then has it as its
  whole key, in user as well. Answers [user row]."
  [row first-chord]
  (if first-chord
    [(kp/record @user-keys/live row first-chord nil)
     (assoc row :binding (kp/chord-binding row first-chord))]
    [@user-keys/live row]))

(defn- commit-step!
  "Adds chord to the row's key as one more step, taking the keys of taken's
  bindings."
  [ctx {:keys [row first-chord]} chord taken]
  (let [[user row] (step-row row first-chord)]
    (apply-change! ctx {:user (kp/add-step user row chord taken)
                        :done (str (kp/chord-text (kp/with-step row chord) (os))
                                   " now runs " (named (:doc row)) ".")}
                   false)))

(defn- wait-for-step!
  "Makes the next key press one more step of row index's key. first-chord
  is a chord just recorded for the row and not written yet, or nil. why
  opens the message."
  [{:keys [win state]} {:keys [row index first-chord]} why]
  (let [so-far (if first-chord (kp/chord-text first-chord (os)) (:chord row))]
    (reset! state {:mode :step :row row :index index :first-chord first-chord})
    (pw/set-key-label! win index (str so-far " …"))
    (pw/say! win (str why "Press the next key for " (named (:doc row)) ", after " so-far ". Esc cancels.") nil)))

(defn- start-step!
  "Add a step: the next key press goes after the selected row's key."
  [{:keys [win state] :as ctx} index]
  (let [row (when index (get (pw/rows win) index))]
    (cond
      (nil? row) (pw/say! win "Select a row first." nil)
      (:fixed? row) (pw/say! win "That key is part of a chain of keys and can't be changed here." nil)
      (nil? (:binding row)) (pw/say! win (str (named (:doc row)) " has no key yet. Click Add a key first.") nil)
      :else (do (when @state (refresh! ctx))
                (wait-for-step! ctx {:row row :index index} "")))))

(defn- step-recorded!
  "A chord arrived as the next step: asks before taking keys from other
  commands, else adds it."
  [{:keys [win state] :as ctx} {:keys [row first-chord] :as s} chord]
  (let [mine (kp/with-step (second (step-row row first-chord)) chord)
        taken (kp/step-conflicts {:keymap (live-keymap) :command (:command row) :os (os)} mine)
        commands (plugin-registry/all-commands @plugin-registry/live)]
    (if (seq taken)
      (let [b (:binding (first taken))]
        (reset! state (assoc s :mode :conflict :chord chord :taken taken :step? true))
        (pw/say! win (str (kp/chord-text mine (os)) " can't work beside " (kp/chord-text b (os))
                          ", which runs " (named (get-in commands [(:command b) :doc]))
                          (when (next taken) (str ", and " (dec (count taken)) " more"))
                          ". Replace?")
                 ["Replace" "Cancel"]))
      (commit-step! ctx s chord nil))))

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
    (cond
      ;; a chord that starts key sequences can't also be a key by itself:
      ;; it replaces them, or becomes the first chord of one more
      (:then (:binding taken))
      (let [all (kp/sequences-on {:keymap (live-keymap) :command (:command row) :os (os)} chord)
            n (count all)]
        (reset! state {:mode :shared :row row :index (:index @state) :chord chord :taken all})
        (pw/say! win (str (kp/chord-text chord (os)) " starts "
                          (if (= 1 n) "the key sequence for " (str n " key sequences, such as the one for "))
                          (named (get-in commands [(:command (:binding (first all))) :doc]))
                          ". As a single key it would replace " (if (= 1 n) "it" "them") ".")
                 ["Replace" "Add a second key" "Cancel"]))

      taken
      (let [b (:binding taken)
            what (if-let [id (:command b)]
                   (str "runs " (named (get-in commands [id :doc])))
                   "is one Jonquil keeps for itself")]
        (reset! state {:mode :conflict :row row :chord chord :taken taken})
        (pw/say! win (str (kp/chord-text chord (os)) " " what ". Replace it?") ["Replace" "Cancel"]))

      :else (commit! ctx row chord nil))))

(defn- on-key
  "The window's key handler. While a key or a step is being recorded every
  key is the tab's: Escape cancels, a modifier alone waits, a key Jonquil
  can't bind says so, and a chord is recorded. Otherwise the key is GTK's."
  [{:keys [win state] :as ctx} keyval gdk-state physical]
  (let [s @state
        step? (= :step (:mode s))]
    (if-not (or step? (= :recording (:mode s)))
      0
      (let [{:keys [chord problem]} (if step?
                                      (kp/press-step keyval gdk-state (os) physical)
                                      (kp/press-chord keyval gdk-state (os) physical))]
        (cond
          (and (= KEY-ESCAPE keyval) (nil? (:mods chord))) (idle! ctx "Cancelled.")
          problem (pw/say! win problem nil)
          (and chord step?) (step-recorded! ctx s chord)
          chord (recorded! ctx s chord))
        1))))

(defn- on-answer
  "Replace or Change anyway (yes? true), or Cancel."
  [{:keys [state] :as ctx} yes?]
  (let [{:keys [mode row chord taken change step?] :as s} @state]
    (cond
      (not yes?) (idle! ctx "Cancelled.")
      (and (= :conflict mode) step?) (commit-step! ctx s chord taken)
      (= :shared mode) (commit! ctx row chord taken)
      (= :conflict mode) (commit! ctx row chord taken)
      (= :comments mode) (apply-change! ctx change true))))

(defn- on-other
  "Add a second key, the third answer about a chord that starts key
  sequences: the chord becomes the first of one more."
  [{:keys [state] :as ctx}]
  (let [{:keys [mode row index chord]} @state]
    (when (= :shared mode)
      (wait-for-step! ctx {:row row :index index :first-chord chord} ""))))

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

(defn- set-place
  "Where key set id sits in the chooser."
  [id]
  (or (first (keep-indexed (fn [i s] (when (= id (:id s)) i)) key-sets/sets)) 0))

(defn- on-key-set
  "The chooser now shows the set at index: turns that set on, at once, and
  saves the choice in the settings file. Nothing to do when it is the set
  already on, which is how the chooser reports being set from code."
  [{:keys [settings-path*] :as ctx} index]
  (when-let [{:keys [id label]} (get key-sets/sets index)]
    (when (not= id @key-sets/live)
      (let [n (+ (count (key-sets/bindings id :window (os)))
                 (count (key-sets/bindings id :editor (os))))
            err (settings/save-settings! @settings-path* {:key-set id})]
        (reset! key-sets/live id)
        (idle! ctx (cond
                     err (str "Key set: " label ", until Jonquil closes. " err)
                     (zero? n) (str "Key set: " label ". Only Jonquil's own keys are on.")
                     :else (str "Key set: " label ". Its " n " keys are on.")))))))

(defn- start-key-set!
  "Turns on the key set the settings file names, the default one when it
  names none, and shows it in the chooser."
  [{:keys [win settings-path*]}]
  (reset! key-sets/live (or (:key-set (settings/load-settings @settings-path*)) key-sets/default-id))
  (pw/set-key-set-index! win (set-place @key-sets/live)))

(defn- install
  "The Preferences part for one window: builds the window, hidden, and
  answers the fn that opens it, and turns on the key set the settings file
  names. ui's :prefs is {:window :open! :on-key :key-set! :record! :answer!
  :reset! :add-step! :other! :state}, the same fns the widgets call, for the self-tests;
  :key-set! shows the set at an index in the chooser, as a click does."
  [{:keys [win] :as wctx}]
  (let [w (pw/make-window win (mapv :label key-sets/sets))
        ctx (assoc wctx :win w :state (atom nil))
        open! (fn [] (idle! ctx "") (pw/present! w))
        on-key! (fn
                  ([keyval gdk-state] (on-key ctx keyval gdk-state nil))
                  ([keyval gdk-state physical] (on-key ctx keyval gdk-state physical)))]
    (pw/set-handlers!
     w {:on-search (fn [_text] (when (nil? @(:state ctx)) (refresh! ctx)))
        :on-record (fn [i] (start-record! ctx i))
        :on-answer (fn [yes?] (on-answer ctx yes?))
        :on-other (fn [] (on-other ctx))
        :on-open-file (fn [] (when-let [f (:open! @user-keys/window-fns)] (f)))
        :on-reset (fn [i] (on-reset ctx i))
        :on-add-step (fn [i] (start-step! ctx i))
        :on-key-set (fn [i] (on-key-set ctx i))
        :on-key on-key!})
    (start-key-set! ctx)
    {:fns {:prefs-open-fn open!}
     :ui {:prefs {:window w :open! open! :on-key on-key!
                  :key-set! (fn [i] (pw/set-key-set-index! w i))
                  :record! (fn [i] (start-record! ctx i))
                  :answer! (fn [yes?] (on-answer ctx yes?))
                  :other! (fn [] (on-other ctx))
                  :reset! (fn [i] (on-reset ctx i))
                  :add-step! (fn [i] (start-step! ctx i))
                  :state (:state ctx)}}}))

(def part
  "The Preferences part's install fn, (part wctx) -> contribution."
  (with-meta install {:part/id :prefs}))
