(ns gtk-selftest-window.plugins
  "Plugin rows: a plugin file in the selftest's own plugins folder
  (beside its temp settings file, never the user's) loads through
  :load-plugins!*, its key runs its :edit command, the palette lists and
  runs it, a broken file is named while a good one works, and a throwing
  :edit is contained with the plugin's id; reload rows; :run commands
  printing, editing the tab they started on, and refusing it once it has
  closed; quiet and visible evals and a timed-out eval that's stopped.
  Each row restores the live registry and deletes its
  files."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.palette :as palette]
            [jonquil.plugin :as p]
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

;; --- reload, a failed reload, save-to-reload, a removed file --

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
      (fn [loaded]
        (let [j0 (wraps-on? ui KEY-J)
              _ (io/delete-file (io/file ((:plugins-dir* ui)) "p4-gone.clj"))
              lines ((:reload-plugins!* ui))
              j1 (wraps-on? ui KEY-J)]
          (support/check :plugin-removed-file
                         (and j0 (not j1) (some #(= "plugin p4-gone removed: its file is gone" %) lines))
                         {:j-before j0 :j-after j1 :loaded loaded :lines lines}))))))

;; --- :run commands and their effects ----------------------------

(defonce captured (atom nil))

(defn- run-source
  "A plugin file for id declaring effects, with one window-scope :run
  command, (fn [ctx] run-body), bound to Cmd/Ctrl+Alt+J."
  [id effects run-body]
  (str "(ns jonquil.plugins." id "\n  (:require [clojure.string :as str]\n            [jonquil.plugin :as p]))\n\n"
       "(p/defplugin\n"
       "  {:id :" id "\n"
       "   :doc \"A :run command row's plugin.\"\n"
       "   :effects " effects "\n"
       "   :commands [{:id :" id "/go :doc \"Go.\" :scope :window :run (fn [ctx] " run-body ")}]\n"
       "   :bindings [{:key \"j\" :mods #{:cmd :alt} :command :" id "/go}]})\n"))

(defn- press-j! [ui] (on-gtk ((:handle-window-key! ui) nil KEY-J 0 ctrl-alt nil)))

(defn row-plugin-run-prints
  "A :console plugin's window key runs its :run command on a worker, and
  what it prints reaches the console."
  [ui]
  (go-row :plugin-run-prints support/check
    (with-plugins ui {"p5-say.clj" (run-source "p5-say" "#{:console}" "(p/print! ctx \"p5 hello\")")}
      (fn [lines]
        (let [handled (press-j! ui)
              seen? (await-until 3000 20 #(str/includes? (support/console-text ui) "p5 hello"))]
          (support/check :plugin-run-prints (and (= 1 handled) seen?)
                         {:handled handled :seen seen? :lines lines}))))))

(defn row-plugin-run-replaces-text
  "A :buffer plugin's :run command reads its tab's text and replaces it."
  [ui]
  (go-row :plugin-run-replaces-text support/check
    (with-plugins ui {"p5-up.clj" (run-source "p5-up" "#{:buffer}" "(p/replace-text! ctx (str/upper-case (p/buffer-text ctx)))")}
      (fn [lines]
        (on-gtk (editor/set-text! (:editor ui) "(a b)"))
        (press-j! ui)
        (let [up? (await-until 3000 20 #(= "(A B)" (editor/editor-text (:editor ui))))]
          (support/check :plugin-run-replaces-text up?
                         {:text (on-gtk (editor/editor-text (:editor ui))) :lines lines}))))))

(defn row-plugin-replace-text-refuses-nul
  "p/replace-text! with a NUL in the text throws, naming
  the line (the effect impl's pre-check), so the command fails loudly
  instead of landing truncated text, and the tab is untouched. Without
  the pre-check the apply-op! funnel's nil would mislabel this as
  read-only."
  [ui]
  (go-row :plugin-replace-text-refuses-nul support/check
    (with-plugins ui {"p5-nul.clj" (run-source "p5-nul" "#{:buffer}"
                                               "(p/replace-text! ctx (str \"a\" (char 0) \"b\"))")}
      (fn [lines]
        (on-gtk (editor/set-text! (:editor ui) "(a b)"))
        (press-j! ui)
        (let [said? (await-until 3000 20 #(str/includes? (support/console-text ui)
                                                         "holds a NUL byte on line 1"))
              text (on-gtk (editor/editor-text (:editor ui)))]
          (support/check :plugin-replace-text-refuses-nul
                         (and said? (= "(a b)" text))
                         {:said said? :text text :lines lines}))))))

(defn row-plugin-run-closed-tab
  "A :run command started on a second tab keeps that tab: once it has
  closed, the command's buffer-text throws \"that tab was closed\" and the
  first tab is untouched."
  [ui]
  (go-row :plugin-run-closed-tab support/check
    (with-plugins ui {"p5-keep.clj" (run-source "p5-keep" "#{:buffer}" "(reset! gtk-selftest-window.plugins/captured ctx)")}
      (fn [lines]
        (reset! captured nil)
        (let [first-id (on-gtk (editor/set-text! (:editor ui) "(first)") (:id ((:active-tab* ui))))
              second-id (on-gtk ((:new-tab!* ui)) (:id ((:active-tab* ui))))]
          (try
            (press-j! ui)
            (let [got? (await-until 3000 20 #(some? @captured))
                  _ (on-gtk ((:close-tab! ui) second-id))
                  e (try (p/buffer-text @captured) nil (catch :default e e))
                  first-text (on-gtk (editor/editor-text (:editor ui)))]
              (support/check :plugin-run-closed-tab
                             (and got? (not= first-id second-id)
                                  (= second-id (::p/tab @captured))
                                  (= "that tab was closed" (some-> e ex-message))
                                  (= "(first)" first-text))
                             {:got got? :tab (::p/tab @captured) :second second-id
                              :error (some-> e ex-message) :first-text first-text :lines lines}))
            (finally
              (on-gtk (when (some #(= second-id (:id %)) ((:tabs* ui))) ((:close-tab! ui) second-id))
                      ((:select-tab! ui) first-id)))))))))

;; --- the :eval effect -----------------------------------------

(defn- typed!
  "code entered the way the MCP's eval enters it (echoed, in the history,
  shifting *1): the :done map, or ::timeout."
  [ui code]
  (let [done (promise)]
    (on-gtk ((:mcp-eval!* ui) code (fn [m] (deliver done m))))
    (deref done 5000 ::timeout)))

(defn- status-text [ui] (on-gtk (g/label-text (:status-label ui))))

(defn row-plugin-eval-quiet
  "p/eval! answers the value and output without touching the console, and
  the person's *1 is still theirs."
  [ui]
  (go-row :plugin-eval-quiet support/check
    (with-plugins ui {"p5b-quiet.clj" (run-source "p5b-quiet" "#{:eval :console}"
                                                   "(let [r (p/eval! ctx \"(println \\\"side\\\") (* 6 7)\")] (p/status! ctx (pr-str r)))")}
      (fn [lines]
        (typed! ui "(+ 1 2)")
        (let [mark (on-gtk (support/out-end-offset ui))
              _ (press-j! ui)
              want {:value "42" :out "side\n" :err ""}
              got? (await-until 5000 20 #(= want (try (edn/read-string (g/label-text (:status-label ui))) (catch :default _ nil))))
              console (on-gtk (support/since ui mark))
              star1 (get-in (typed! ui "*1") [:done :result :printed])]
          (support/check :plugin-eval-quiet
                         (and got? (not (str/includes? console "side")) (not (str/includes? console "42"))
                              (= "3" star1))
                         {:status (status-text ui) :console console :star1 star1 :lines lines}))))))

(defn row-plugin-repl-visible
  "p/repl! shows the eval at the console and answers its value."
  [ui]
  (go-row :plugin-repl-visible support/check
    (with-plugins ui {"p5b-repl.clj" (run-source "p5b-repl" "#{:eval :console}"
                                                  "(p/status! ctx (str \"v=\" (:value (p/repl! ctx \"(+ 20 22)\"))))")}
      (fn [lines]
        (let [mark (on-gtk (support/out-end-offset ui))
              _ (press-j! ui)
              got? (await-until 5000 20 #(= "v=42" (g/label-text (:status-label ui))))
              shown? (await-until 3000 20 #(str/includes? (support/since ui mark) "=> 42"))]
          (support/check :plugin-repl-visible (and got? shown?)
                         {:status (status-text ui) :console (on-gtk (support/since ui mark)) :lines lines}))))))

(defn row-plugin-eval-timeout
  "An eval past its :timeout-ms throws, saying so, and is stopped: the next
  eval answers."
  [ui]
  (go-row :plugin-eval-timeout support/check
    (with-plugins ui {"p5b-slow.clj" (run-source "p5b-slow" "#{:eval :console}"
                                                  "(p/status! ctx (try (p/eval! ctx \"(Thread/sleep 5000)\" {:timeout-ms 300}) \"no throw\" (catch :default e (ex-message e))))")}
      (fn [lines]
        (press-j! ui)
        (let [said? (await-until 5000 20 #(= "the eval took longer than 300 ms and was stopped" (g/label-text (:status-label ui))))
              next-eval (typed! ui "(+ 1 1)")]
          (support/check :plugin-eval-timeout
                         (and said? (= "2" (get-in next-eval [:done :result :printed])))
                         {:status (status-text ui) :next next-eval :lines lines}))))))

;; --- the after-hooks ---------------------------------------------

(defn- hook-source
  "A plugin file for id declaring effects whose :hooks are hooks-text, a
  map literal of point to (fn [ctx event] ...)."
  [id effects hooks-text]
  (str "(ns jonquil.plugins." id "\n  (:require [jonquil.plugin :as p]))\n\n"
       "(p/defplugin\n"
       "  {:id :" id "\n"
       "   :doc \"A hook row's plugin.\"\n"
       "   :effects " effects "\n"
       "   :hooks " hooks-text "})\n"))

(defn- run-text!
  "Sets the active tab's text and presses Run."
  [ui text]
  (on-gtk (editor/set-text! (:editor ui) text)
          ((:run!* ui))))

(defn row-plugin-hook-after-save
  "A saved tab's :after-save hook gets the path it was saved to."
  [ui]
  (go-row :plugin-hook-after-save support/check
    (with-plugins ui {"p6-saved.clj" (hook-source "p6-saved" "#{:console}"
                                                  "{:after-save (fn [ctx e] (p/print! ctx (str \"p6 saved \" (.getName (java.io.File. (:path e))))))}")}
      (fn [lines]
        (let [f (java.io.File/createTempFile "p6-save" ".clj")
              first-id (on-gtk (:id ((:active-tab* ui))))
              second-id (on-gtk ((:new-tab!* ui)) (:id ((:active-tab* ui))))]
          (try
            (on-gtk ((:save-to! ui) (.getPath f)))
            (let [seen? (await-until 3000 20 #(str/includes? (support/console-text ui) (str "p6 saved " (.getName f))))]
              (support/check :plugin-hook-after-save seen? {:seen seen? :lines lines}))
            (finally
              (on-gtk (when (some #(= second-id (:id %)) ((:tabs* ui))) ((:close-tab! ui) second-id))
                      ((:select-tab! ui) first-id))
              (.delete f))))))))

(defn row-plugin-hook-after-run
  "A Run's :after-run hook gets its kind and value."
  [ui]
  (go-row :plugin-hook-after-run support/check
    (with-plugins ui {"p6-ran.clj" (hook-source "p6-ran" "#{:console}"
                                                "{:after-run (fn [ctx e] (p/print! ctx (str \"p6 ran \" (:kind e) \" \" (:value e))))}")}
      (fn [lines]
        (run-text! ui "(+ 1 2)")
        (let [seen? (await-until 5000 20 #(str/includes? (support/console-text ui) "p6 ran :run 3"))]
          (support/check :plugin-hook-after-run seen? {:seen seen? :lines lines}))))))

(defn row-plugin-hook-on-error
  "A Run's error reaches :on-error with its source and line."
  [ui]
  (go-row :plugin-hook-on-error support/check
    (with-plugins ui {"p6-err.clj" (hook-source "p6-err" "#{:console}"
                                                "{:on-error (fn [ctx e] (p/print! ctx (str \"p6 error \" (:source e) \" line \" (:line e))))}")}
      (fn [lines]
        (run-text! ui "(def a 1)\n(def b 2)\n(foo-undefined 3)")
        (let [seen? (await-until 5000 20 #(str/includes? (support/console-text ui) "p6 error :run line 3"))]
          (support/check :plugin-hook-on-error seen?
                         {:seen seen? :tail (let [t (support/console-text ui)] (subs t (max 0 (- (count t) 400))))
                          :lines lines}))))))

(defn- occurrences [s sub]
  (loop [i 0 n 0]
    (let [j (str/index-of s sub i)]
      (if j (recur (inc j) (inc n)) n))))

(defn row-plugin-hook-repl-no-loop
  "An :on-error hook whose own p/repl! throws doesn't fire itself again."
  [ui]
  (go-row :plugin-hook-repl-no-loop support/check
    (with-plugins ui {"p6-loop.clj" (hook-source "p6-loop" "#{:console :eval}"
                                                 "{:on-error (fn [ctx e] (swap! gtk-selftest-window.plugins/captured (fnil inc 0)) (p/repl! ctx \"(/ 1 0)\"))}")}
      (fn [lines]
        (reset! captured nil)
        (let [mark (on-gtk (support/out-end-offset ui))
              _ (run-text! ui "(/ 1 0)")
              two? (await-until 5000 20 #(and (= 1 @captured)
                                              (= 2 (occurrences (support/since ui mark) "Divide by zero"))))
              _ (Thread/sleep 1000)
              n @captured]
          (reset! captured nil)
          (support/check :plugin-hook-repl-no-loop (and two? (= 1 n))
                         {:two two? :captured n :console (on-gtk (support/since ui mark)) :lines lines}))))))

(defn row-plugin-before-run-abort
  "A :before-run hook's {:abort ...} stops the Run: its line shows and the
  code is never sent."
  [ui]
  (go-row :plugin-before-run-abort support/check
    (with-plugins ui {"p6b-stop.clj" (hook-source "p6b-stop" "#{}"
                                                  "{:before-run (fn [_ e] (if (.contains ^String (:text e) \"p6b-stop\") {:abort \"p6b said no\"} e))}")}
      (fn [lines]
        (let [mark (on-gtk (support/out-end-offset ui))
              _ (run-text! ui "(println \"p6b-printed\") ; p6b-stop")
              seen? (await-until 5000 20 #(str/includes? (support/since ui mark)
                                                         "; plugin p6b-stop stopped this Run: p6b said no"))
              _ (Thread/sleep 500)
              out (support/since ui mark)]
          (support/check :plugin-before-run-abort (and seen? (not (str/includes? out "p6b-printed")))
                         {:seen seen? :console out :lines lines}))))))

(defn row-plugin-before-run-form
  "Run Form asks :before-run with :kind :form and the form's text, then runs."
  [ui]
  (go-row :plugin-before-run-form support/check
    (with-plugins ui {"p6b-form.clj" (hook-source "p6b-form" "#{}"
                                                  "{:before-run (fn [_ e] (reset! gtk-selftest-window.plugins/captured [(:kind e) (:text e)]) e)}")}
      (fn [lines]
        (reset! captured nil)
        (let [mark (on-gtk (support/out-end-offset ui))
              _ (on-gtk (let [ed (:editor ui)]
                          (editor/set-text! ed "(+ 40 2)")
                          (support/place-cursor! (:buf ed) 1)
                          ((:run-form!* ui))))
              ran? (await-until 5000 20 #(str/includes? (support/since ui mark) "42"))
              got @captured]
          (reset! captured nil)
          (support/check :plugin-before-run-form (and ran? (= [:form "(+ 40 2)"] got))
                         {:ran ran? :captured got :console (support/since ui mark) :lines lines}))))))

(defn row-plugin-before-run-overrun
  "A :before-run hook past the budget is reported and the Run goes ahead."
  [ui]
  (go-row :plugin-before-run-overrun support/check
    (with-plugins ui {"p6b-slow.clj" (hook-source "p6b-slow" "#{}"
                                                  "{:before-run (fn [_ e] (Thread/sleep 400) e)}")}
      (fn [lines]
        (let [mark (on-gtk (support/out-end-offset ui))
              _ (run-text! ui "(+ 1 2)")
              both? (await-until 5000 20 #(let [t (support/since ui mark)]
                                            (and (str/includes? t "the :before-run hooks took longer than 100 ms")
                                                 (str/includes? t "so the Run went ahead")
                                                 (str/includes? t "3"))))
              out (support/since ui mark)]
          ;; let the hook return before the next row
          (Thread/sleep 500)
          (support/check :plugin-before-run-overrun both? {:console out :lines lines}))))))
