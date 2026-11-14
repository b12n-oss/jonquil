(ns gtk-selftest-window.prefs
  "The Preferences window's Keys tab: Cmd/Ctrl+comma opens it, a click on a
  row's key and a key press record a new key into keys.edn beside the
  window's settings file, the live key map follows, and Reset this key
  takes it back."
  (:require [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jolt.ffi :as ffi]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.prefs-window :as pw]
            [jonquil.key-prefs :as kp]
            [jonquil.keymap :as km]
            [jonquil.plugin-registry :as plugin-registry]
            [jonquil.user-keys :as user-keys]))

(defn- free-chord
  "The first of a few unlikely chords that nothing has, as {:chord :keyval
  :state}; Meta stands for Cmd/Ctrl, as in the other rows."
  []
  (let [plugins @plugin-registry/live
        keymap {:window (plugin-registry/window-bindings plugins)
                :editor (plugin-registry/editor-bindings plugins)}
        os (System/getProperty "os.name")
        state (bit-or g/META-MASK g/SHIFT-MASK g/ALT-MASK)]
    (first (for [k ["j" "u" "y" "q" "x"]
                 :let [{:keys [chord]} (kp/press-chord (km/key-value k) state os)]
                 :when (and chord (nil? (kp/conflict {:keymap keymap :command :run/run :os os} chord)))]
             {:chord chord :keyval (km/key-value k) :state state}))))

(defn- run-row
  "The index of the row for Run's shipped F5 key, or of its key of yours."
  [w source]
  (first (keep-indexed (fn [i r] (when (and (= :run/run (:command r)) (= source (:source r))
                                            (or (= "yours" source) (= "F5" (:chord r))))
                                   i))
                       (pw/rows w))))

(defn row-prefs-records-a-key
  "Cmd/Ctrl+comma opens Preferences; clicking F5's row and pressing a free
  chord writes it to keys.edn as Run's key and drops F5; the window's own
  key handler then runs Run on the new chord's binding; Reset this key
  brings F5 back."
  [ui]
  (go-row :prefs-records-a-key support/check
    (let [{w :window :as prefs} (:prefs ui)
          path (user-keys/keys-path ((:settings-path* ui)))
          {:keys [chord keyval state]} (free-chord)
          _ (on-gtk ((:handle-window-key! ui) ffi/null (km/key-value "comma") 0 g/META-MASK ffi/null))
          shown? (await-until 3000 20 #(pos? (g/gtk-widget-get-visible (:win w))))
          i (on-gtk (run-row w "default"))
          _ (on-gtk ((:record! prefs) i))
          asked? (on-gtk (str/includes? (:text (pw/said w)) "Press the new key"))
          ;; a modifier alone keeps waiting, then the chord lands
          waited (on-gtk ((:on-key prefs) 0xffe1 g/SHIFT-MASK))
          used (on-gtk ((:on-key prefs) keyval state))
          wrote? (await-until 3000 20 #(and (.exists (java.io.File. ^String path))
                                            (some (fn [b] (= :run/run (:command b))) (:window @user-keys/live))))
          mine (on-gtk (first (:window @user-keys/live)))
          gone (on-gtk (:remove @user-keys/live))
          live-first (on-gtk (:command (km/resolve-binding (plugin-registry/window-bindings @plugin-registry/live)
                                                           keyval state (constantly true))))
          j (on-gtk (run-row w "yours"))
          text (when wrote? (slurp path))
          _ (on-gtk ((:reset! prefs) j))
          back? (await-until 3000 20 #(= user-keys/no-keys @user-keys/live))
          f5-back? (on-gtk (some? (run-row w "default")))
          _ (on-gtk (g/gtk-window-close (:win w)))
          hidden? (await-until 3000 20 #(zero? (g/gtk-widget-get-visible (:win w))))]
      (on-gtk (try
                (support/check :prefs-records-a-key
                               (and shown? (some? i) asked? (= 1 waited) (= 1 used) wrote?
                                    (= (assoc chord :command :run/run) mine)
                                    (= [{:key "F5" :command :run/run}] gone)
                                    (= :run/run live-first) (some? j)
                                    (str/starts-with? (or text "") kp/written-mark)
                                    back? f5-back? hidden?)
                               {:shown shown? :row i :asked asked? :waited waited :used used :wrote wrote?
                                :mine mine :gone gone :live-first live-first :yours-row j
                                :back back? :f5-back f5-back? :hidden hidden?})
                (finally
                  (.delete (java.io.File. ^String path))
                  (reset! user-keys/live user-keys/no-keys)))))))

(defn row-prefs-asks-before-taking-a-key
  "Recording Save's Cmd/Ctrl+S for Run asks first: Cancel changes nothing,
  Replace gives Run the chord and takes it from Save. A keys.edn holding a
  comment of yours is asked about too, and Change anyway rewrites it."
  [ui]
  (go-row :prefs-asks-before-taking-a-key support/check
    (let [{w :window :as prefs} (:prefs ui)
          path (user-keys/keys-path ((:settings-path* ui)))
          s-key (km/key-value "s")
          press! (fn [] (on-gtk ((:record! prefs) (run-row w "default"))
                                ((:on-key prefs) s-key g/META-MASK)
                                (pw/said w)))
          _ (on-gtk ((:open! prefs)))
          asked (press!)
          _ (on-gtk ((:answer! prefs) false))
          cancelled? (on-gtk (and (= user-keys/no-keys @user-keys/live) (nil? @(:state prefs))
                                  (nil? (:answers (pw/said w)))))
          ;; a comment of yours in the file: Replace now leads to a second question
          _ (on-gtk (spit path "{:window []} ; mine\n"))
          _ (press!)
          _ (on-gtk ((:answer! prefs) true))
          warned (on-gtk (pw/said w))
          unwritten? (on-gtk (= "{:window []} ; mine\n" (slurp path)))
          _ (on-gtk ((:answer! prefs) true))
          wrote? (await-until 3000 20 #(some (fn [b] (= :run/run (:command b))) (:window @user-keys/live)))
          gone (on-gtk (set (map :command (:remove @user-keys/live))))
          winner (on-gtk (:command (km/resolve-binding (plugin-registry/window-bindings @plugin-registry/live)
                                                       s-key g/META-MASK (constantly true))))
          text (slurp path)
          _ (on-gtk (g/gtk-window-close (:win w)))]
      (on-gtk (try
                (support/check :prefs-asks-before-taking-a-key
                               (and (= ["Replace" "Cancel"] (:answers asked))
                                    (str/includes? (:text asked) "Replace it?")
                                    cancelled?
                                    (= ["Change anyway" "Cancel"] (:answers warned))
                                    unwritten? wrote?
                                    (= #{:run/run :file/save} gone)
                                    (= :run/run winner)
                                    (not (str/includes? text "mine")))
                               {:asked asked :cancelled cancelled? :warned warned :unwritten unwritten?
                                :wrote wrote? :gone gone :winner winner})
                (finally
                  (.delete (java.io.File. ^String path))
                  (reset! user-keys/live user-keys/no-keys)))))))
