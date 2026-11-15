(ns jonquil.gtk.key-seq-hint
  "What the window shows while a key sequence is under way
  (jonquil.key-seq): the chords so far in the status bar at once, and after
  a pause a list of what can follow, over the last lines of the active
  editor. The list is a popover that takes no focus, so the next key still
  goes to the window's key handler."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [jonquil.gtk.callbacks :refer [callable on-gui!]]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.popover :as popover]
            [jonquil.key-seq :as key-seq]
            [jonquil.plugin-registry :as plugin-registry]))

(def PAUSE-MS
  "How long a prefix waits before the list opens: someone who knows the
  sequence has pressed the next key by then and never sees it."
  500)

(defn- os [] (System/getProperty "os.name"))

(defn- lists
  "The key sequence list popover of every tab."
  [{:keys [tabs]}]
  (keep :key-seq-pop @tabs))

(defn- hide-all! [ctx]
  (doseq [pp (lists ctx)]
    (when (popover/open? pp) (popover/hide! pp))))

(defn- show-list!
  "Opens the list of what can follow p over the active tab's editor."
  [{:keys [active-tab]} p]
  (when-let [pp (:key-seq-pop (active-tab))]
    (let [commands (plugin-registry/all-commands @plugin-registry/live)]
      (g/gtk-label-set-text (:label pp) (str/join "\n" (key-seq/list-lines p commands (os))))
      (popover/show-at-bottom! pp))))

(defn install!
  "Starts following jonquil.key-seq/live for the window whose ctx this is
  ({:tabs :active-tab :set-status! :status-label}). The list is each tab's
  own :key-seq-pop (jonquil.gtk.tab). Answers {:shown? (fn [])}, for the
  self-tests: whether a list is open."
  [{:keys [set-status!] :as ctx}]
  (let [;; when the list is due, in ms, or nil
        due (atom nil)
        tick (callable (fn [_data]
                         (let [p (key-seq/pending)]
                           (when (and p @due (>= (System/currentTimeMillis) @due))
                             (reset! due nil)
                             (show-list! ctx p)))
                         0)
                       [:pointer] :int)
        sync! (fn [old new]
                (let [p (:pending new)]
                  (hide-all! ctx)
                  (cond
                    p (do (set-status! (key-seq/status-text p (os)))
                          (reset! due (+ (System/currentTimeMillis) PAUSE-MS))
                          (g/g-timeout-add (+ PAUSE-MS 10) tick ffi/null))
                    (:note new) (do (reset! due nil) (set-status! (str "; " (:note new))))
                    ;; the sequence ran its command, which may have said
                    ;; something itself: only our own text is taken down
                    (:pending old) (do (reset! due nil)
                                       (when (= (key-seq/status-text (:pending old) (os))
                                                (g/label-text (:status-label ctx)))
                                         (set-status! "Ready"))))))]
    (add-watch key-seq/live ::hint
               (fn [_ _ old new] (when (not= old new) (on-gui! #(sync! old new)))))
    {:shown? (fn [] (boolean (some popover/open? (lists ctx))))}))
