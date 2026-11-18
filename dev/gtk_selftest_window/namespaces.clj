(ns gtk-selftest-window.namespaces
  "UF8's Namespaces panel: Cmd/Ctrl+Alt+0 opens it on the runtime's loaded
  namespaces, the filter finds one a Run made, Return steps into its vars,
  Backspace steps back onto it, Close hides the panel."
  (:require [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jolt.ffi :as ffi]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.namespaces-pane :as np]))

(defn- panel-key!
  "Cmd/Ctrl+Alt+digit through the window's key handler."
  [ui digit]
  ((:handle-window-key! ui) ffi/null (int digit) 0 (bit-or g/META-MASK g/ALT-MASK) ffi/null))

(defn- handler [ui k] (k @(:handlers (:namespaces-pane ui))))

(defn- rows [ui] (np/row-texts (:namespaces-pane ui)))

(def ^:private code
  "(in-ns 'demo.nsb)\n(clojure.core/refer-clojure)\n(defn greet \"Hi.\" [n] n)\n(def ^:private secret 1)\n(in-ns 'user)")

(defn row-namespaces-panel
  "A Run defines demo.nsb with greet and a private secret; Cmd/Ctrl+Alt+0
  opens the panel; filtering by its name shows it; Return on it lists
  greet and secret under the crumb; Backspace goes back with demo.nsb
  selected; Close hides the panel."
  [ui]
  (go-row :namespaces-panel support/check
    (let [p (:namespaces-pane ui)
          [ran? _] (support/run-and-await ui code 5000 #(str/includes? % "=>"))
          _ (on-gtk (panel-key! ui \0))
          shown? (await-until 3000 20 #(pos? (g/gtk-widget-get-visible (:root p))))
          listed? (await-until 5000 50 #(and (seq (rows ui)) (not= "Loading…" (np/footer-text p))))
          _ (on-gtk ((handler ui :on-filter) "demo.nsb"))
          found? (await-until 3000 20 #(some #{"demo.nsb"} (rows ui)))
          row (on-gtk (first (filter #(= "demo.nsb" (:name %)) @(:rows p))))
          _ (on-gtk ((handler ui :on-activate) row false))
          stepped? (await-until 5000 50 #(= ["greet" "secret"] (rows ui)))
          crumb (on-gtk (np/crumb-text p))
          _ (on-gtk ((handler ui :on-back)))
          back? (await-until 5000 50 #(= "demo.nsb" (:name (np/selected-row p))))
          _ (on-gtk ((handler ui :on-close)))
          hidden? (await-until 3000 20 #(zero? (g/gtk-widget-get-visible (:root p))))]
      (on-gtk (support/check :namespaces-panel
                             (and ran? shown? listed? found? stepped? back? hidden?
                                  (= "All namespaces › demo.nsb · 2 vars" crumb))
                             {:ran ran? :shown shown? :listed listed? :found found? :stepped stepped?
                              :crumb crumb :back back? :hidden hidden? :rows (rows ui)
                              :footer (np/footer-text p)})))))
