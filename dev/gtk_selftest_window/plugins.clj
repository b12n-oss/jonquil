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

;; --- plugins P4: reload, a failed reload, save-to-reload, a removed file --

(def ^:private KEY-K 0x06b)

(defn- source-on
  "source for id, with its binding on key instead of J."
  [id key]
  (str/replace (source id) ":key \"j\"" (str ":key \"" key "\"")))

(defn- wraps-on? [ui keyval]
  (on-gtk (let [ed (:editor ui)]
            (editor/set-text! ed "(a b)")
            (support/place-cursor! (:buf ed) 1)
            (editor/handle-key! ed keyval ctrl-alt)
            (= "(when (a b))" (editor/editor-text ed)))))

(defn row-plugin-reload-picks-up-a-change
  "Rewrite the file to bind K, Reload Plugins: J no longer wraps, K does."
  [ui]
  (go-row :plugin-reload-picks-up-a-change support/check
    (with-plugins ui {"p4-wrap.clj" (source "p4-wrap")}
      (fn [_]
        (let [j0 (wraps-on? ui KEY-J)
              _ (spit (io/file ((:plugins-dir* ui)) "p4-wrap.clj") (source-on "p4-wrap" "k"))
              lines ((:reload-plugins!* ui))
              j1 (wraps-on? ui KEY-J)
              k1 (wraps-on? ui KEY-K)]
          (support/check :plugin-reload-picks-up-a-change (and j0 (not j1) k1)
                         {:j-before j0 :j-after j1 :k-after k1 :lines lines}))))))

(defn row-plugin-reload-failure-keeps-old
  "A cut-off rewrite: the old version stays live, the console says so, and
  the file is left exactly as written."
  [ui]
  (go-row :plugin-reload-failure-keeps-old support/check
    (with-plugins ui {"p4-keep.clj" (source "p4-keep")}
      (fn [_]
        (let [f (io/file ((:plugins-dir* ui)) "p4-keep.clj")
              broken "(ns jonquil.plugins.p4-keep\n  (:require [jonquil.plugin :as p]))\n(p/defplugin {:id :p4-keep\n"
              _ (spit f broken)
              lines ((:reload-plugins!* ui))
              j (wraps-on? ui KEY-J)
              on-disk (slurp f)]
          (support/check :plugin-reload-failure-keeps-old
                         (and j (= broken on-disk)
                              (some #(str/includes? % "skipped: line") lines)
                              (some #(str/includes? % "the previous version stays live") lines))
                         {:j j :lines lines :file-unchanged (= broken on-disk)}))))))

(defn row-plugin-save-reloads
  "Saving a plugin file from a tab reloads it: the first tab's text is
  saved to the plugin's path, then K wraps. The tab's path and text are put
  back afterwards."
  [ui]
  (go-row :plugin-save-reloads support/check
    (with-plugins ui {"p4-save.clj" (source "p4-save")}
      (fn [_]
        (let [t (on-gtk ((:active-tab* ui)))
              old-path @(:path t)
              old-text (on-gtk (editor/editor-text (:editor ui)))
              target (str (io/file ((:plugins-dir* ui)) "p4-save.clj"))]
          (try
            (on-gtk (editor/set-text! (:editor ui) (source-on "p4-save" "k"))
                    ((:save-to! ui) target))
            (let [k? (await-until 10000 50 #(contains? (set (map :key (plugin-registry/bindings @plugin-registry/live :editor))) "k"))
                  k (wraps-on? ui KEY-K)]
              (support/check :plugin-save-reloads (and k? k) {:registered k? :k k}))
            (finally
              (on-gtk (reset! (:path t) old-path)
                      (editor/set-text! (:editor ui) old-text)
                      (editor/mark-saved! (:editor ui))))))))))

(defn row-plugin-removed-file
  "Delete a loaded plugin's file and Reload Plugins: its key stops working
  and the console says its file is gone."
  [ui]
  (go-row :plugin-removed-file support/check
    (with-plugins ui {"p4-gone.clj" (source "p4-gone")}
      (fn [_]
        (let [j0 (wraps-on? ui KEY-J)
              _ (io/delete-file (io/file ((:plugins-dir* ui)) "p4-gone.clj"))
              lines ((:reload-plugins!* ui))
              j1 (wraps-on? ui KEY-J)]
          (support/check :plugin-removed-file
                         (and j0 (not j1) (some #(= "plugin p4-gone removed: its file is gone" %) lines))
                         {:j-before j0 :j-after j1 :lines lines}))))))
