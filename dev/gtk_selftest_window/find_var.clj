(ns gtk-selftest-window.find-var
  "Find a Var by Name on the window's own Jolt session: the command asks
  the runtime for its loaded vars and lists them in the active tab's
  palette, filtered by name, and Shift+Return shows the picked var's doc."
  (:require [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.help-popover :as help-popover]
            [jonquil.gtk.palette :as palette]
            [jonquil.gtk.popover :as popover]))

(defn row-find-var-lists-and-shows-doc
  "Runs a defn of needle-fn, then Find a Var by Name: typing needle leaves
  the one row needle-fn [x] from user, and Shift+Return closes the palette
  and shows needle-fn's docstring in the quick-doc popover."
  [ui]
  (go-row :find-var-lists-and-shows-doc support/check
    (let [[defined? _] (support/run-and-await ui "(defn needle-fn \"Finds the needle.\" [x] x)" 5000
                                              #(str/includes? % "=> #'user/needle-fn"))
          p (on-gtk (support/run-palette! ui "find a var by name") (:palette ((:active-tab* ui))))
          listed? (await-until 5000 20 #(and (popover/open? p) (< 100 (count (:all @(:state p))))))
          [shown row] (on-gtk (palette/set-query! p "needle")
                              [(palette/shown-docs p) (some-> (first (:shown @(:state p))) :source)])
          taken (on-gtk (palette/handle-key! p 0xff0d g/SHIFT-MASK))
          hp (:help-popover ui)
          doc? (await-until 3000 20 #(and (popover/open? hp)
                                          (str/includes? (help-popover/quick-doc-text hp) "Finds the needle.")))]
      (on-gtk (when (popover/open? p) (popover/hide! p))
              (when (popover/open? hp) (popover/hide! hp))
              (support/check :find-var-lists-and-shows-doc
                             (and defined? listed? (= ["needle-fn [x]"] shown) (= "user" row) (= 1 taken) doc?)
                             {:defined defined? :listed listed? :shown shown :source row :taken taken
                              :doc doc? :status (g/label-text (:status-label ui))})))))
