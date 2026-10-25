(ns gtk-selftest-window.plugins
  "Plugins P3's rows: a plugin file in the selftest's own plugins folder
  (beside its temp settings file, never the user's) loads through
  :load-plugins!*, its key runs its :edit command, the palette lists and
  runs it, a broken file is named while a good one works, and a throwing
  :edit is contained with the plugin's id. Each row restores the live
  registry and deletes its files."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.palette :as palette]
            [jonquil.plugin-registry :as plugin-registry]))

(def ^:private KEY-J 0x06a)
(def ^:private ctrl-alt (bit-or g/CONTROL-MASK g/ALT-MASK))

(defn- source
  "A plugin file for id with one :edit command, edit-body over snapshot s,
  bound to Cmd/Ctrl+Alt+J."
  ([id] (source id "(p/wrap-form s \"(when \" \")\")"))
  ([id edit-body]
   (str "(ns jonquil.plugins." id "\n  (:require [jonquil.plugin :as p]))\n\n"
        "(p/defplugin\n"
        "  {:id :" id "\n"
        "   :doc \"Wrap the form at the caret in (when ...).\"\n"
        "   :effects #{}\n"
        "   :commands [{:id :" id "/wrap :doc \"Wrap the form at the caret in (when ...).\"\n"
        "               :scope :editor :edit (fn [s] " edit-body ")}]\n"
        "   :bindings [{:key \"j\" :mods #{:cmd :alt} :command :" id "/wrap}]})\n")))

(defn- with-plugins
  "Writes files ({name text}) into the plugins folder, loads it, runs (f
  lines), then deletes the files and restores the live registry."
  [ui files f]
  (let [dir (io/file ((:plugins-dir* ui)))
        before @plugin-registry/live]
    (.mkdirs dir)
    (try
      (doseq [[n text] files] (spit (io/file dir n) text))
      (f ((:load-plugins!* ui)))
      (finally
        (doseq [[n _] files] (io/delete-file (io/file dir n) true))
        (reset! plugin-registry/live before)))))

(defn row-plugin-key-runs-edit
  "The plugin's Cmd/Ctrl+Alt+J wraps the form at the caret."
  [ui]
  (go-row :plugin-key-runs-edit support/check
    (with-plugins ui {"p3-wrap.clj" (source "p3-wrap")}
      (fn [lines]
        (on-gtk (let [ed (:editor ui)]
                  (editor/set-text! ed "(a b)")
                  (support/place-cursor! (:buf ed) 1)
                  (let [handled (editor/handle-key! ed KEY-J ctrl-alt)
                        text (editor/editor-text ed)]
                    (support/check :plugin-key-runs-edit
                                   (and (= 1 handled) (= "(when (a b))" text))
                                   {:handled handled :text text :lines lines}))))))))

(defn row-plugin-in-palette
  "The palette lists the plugin's command with the plugin as its source,
  and running it from there wraps the form."
  [ui]
  (go-row :plugin-in-palette support/check
    (with-plugins ui {"p3-pal.clj" (source "p3-pal")}
      (fn [_]
        (on-gtk (let [ed (:editor ui) p (:palette ui)]
                  (editor/set-text! ed "(a b)")
                  (support/place-cursor! (:buf ed) 1)
                  ((:open-palette!* ui))
                  (let [entry (some #(when (= :p3-pal/wrap (:id %)) %) (:all @(:state p)))]
                    (palette/set-query! p "p3-pal")
                    (palette/run-selected! p)
                    (let [text (editor/editor-text ed)]
                      (support/check :plugin-in-palette
                                     (and (= "p3-pal" (:source entry)) (= "(when (a b))" text))
                                     {:entry entry :text text})))))))))

(defn row-plugin-broken-file-named
  "One broken file among good ones: the good plugin's key works and the
  console names the broken one with its line."
  [ui]
  (go-row :plugin-broken-file-named support/check
    (let [mark (on-gtk (support/out-end-offset ui))]
      (with-plugins ui {"p3-good.clj" (source "p3-good")
                        "p3-bad.clj" "(ns jonquil.plugins.p3-bad\n  (:require [jonquil.plugin :as p]))\n(p/defplugin {:id :p3-bad\n"}
        (fn [lines]
          (let [named? (await-until 2000 20 #(str/includes? (support/since ui mark) "plugin p3-bad skipped: line"))]
            (on-gtk (let [ed (:editor ui)]
                      (editor/set-text! ed "(a b)")
                      (support/place-cursor! (:buf ed) 1)
                      (editor/handle-key! ed KEY-J ctrl-alt)
                      (let [text (editor/editor-text ed)]
                        (support/check :plugin-broken-file-named
                                       (and named? (= "(when (a b))" text))
                                       {:named named? :text text :lines lines}))))))))))

(defn row-plugin-edit-throw-contained
  "A plugin :edit that throws when the key runs it: the key still counts
  as used, the buffer is unchanged, and the status bar names the plugin."
  [ui]
  (go-row :plugin-edit-throw-contained support/check
    ;; it throws only on this row's buffer: one that always threw would fail
    ;; its soak and never load
    (with-plugins ui {"p3-throw.clj" (source "p3-throw" "(if (= \"(a b)\" (:text s)) (throw (ex-info \"kaboom\" {})) nil)")}
      (fn [lines]
        (let [[handled text] (on-gtk (let [ed (:editor ui)]
                                       (editor/set-text! ed "(a b)")
                                       (support/place-cursor! (:buf ed) 1)
                                       [(editor/dispatch-key! ed KEY-J ctrl-alt) (editor/editor-text ed)]))
              status? (await-until 1000 20 #(str/includes? (g/label-text (:status-label ui)) "plugin p3-throw: "))]
          (on-gtk (support/check :plugin-edit-throw-contained
                                 (and (= 1 handled) (= "(a b)" text) status?)
                                 {:handled handled :text text :status (g/label-text (:status-label ui))
                                  :lines lines})))))))
