(ns gtk-selftest-window.processes
  "LM4's Processes panel: Cmd/Ctrl+Alt+8 opens it and starts watching,
  a fiber the program spawns then shows with its state, Interrupt ends it,
  Forget finished drops it."
  (:require [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jolt.ffi :as ffi]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.processes-pane :as pp]))

(defn- panel-key!
  "Cmd/Ctrl+Alt+digit through the window's key handler."
  [ui digit]
  ((:handle-window-key! ui) ffi/null (int digit) 0 (bit-or g/META-MASK g/ALT-MASK) ffi/null))

(defn- rows [ui] (pp/row-texts (:processes-pane ui)))

(defn- handler [ui k] (k @(:handlers (:processes-pane ui))))

(defn row-processes-interrupt-and-forget
  "Cmd/Ctrl+Alt+8 opens the panel; a fiber a Run spawns then is listed as
  live, Interrupt makes it dead, Forget finished drops it, Close hides the
  panel."
  [ui]
  (go-row :processes-interrupt-and-forget support/check
    (let [procp (:processes-pane ui)
          _ (on-gtk (panel-key! ui \8))
          shown? (await-until 3000 20 #(pos? (g/gtk-widget-get-visible (:root procp))))
          [ran? _] (support/run-and-await ui "(def pstop (atom false))\n(jolt.fibers/spawn (fn [] (loop [] (when-not @pstop (jolt.fibers/yield) (recur)))))"
                                          5000 #(str/includes? % "=>"))
          live? (await-until 4000 50 #(some (fn [r] (re-find #"^#\d+  (running|ready|parked)  " r)) (rows ui)))
          id (on-gtk (pp/selected-id procp))
          _ (on-gtk ((handler ui :on-interrupt) id))
          dead? (await-until 4000 50 #(some (fn [r] (str/starts-with? r (str "#" id "  dead"))) (rows ui)))
          _ (on-gtk ((handler ui :on-forget)))
          forgot? (await-until 4000 50 #(not-any? (fn [r] (str/starts-with? r (str "#" id "  "))) (rows ui)))
          _ (on-gtk ((handler ui :on-close)))
          hidden? (await-until 3000 20 #(zero? (g/gtk-widget-get-visible (:root procp))))]
      (on-gtk (support/check :processes-interrupt-and-forget
                             (and shown? ran? live? (some? id) dead? forgot? hidden?)
                             {:shown shown? :ran ran? :live live? :id id :dead dead? :forgot forgot? :hidden hidden?
                              :rows (rows ui)})))))
