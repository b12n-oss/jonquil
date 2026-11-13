(ns demo-shots.panels
  "The Lisp Machine panel scenes of dev/demo_shots.clj: find usages, compare
  form, the Reader, change sets, breakpoint, trace, line counts, processes
  and messages. Each scene is (fn [ui] ch) like the scenes in demo-shots."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [demo-shots.support :refer [all-saved! caret-to-line! check! console-enter! demo-project! idle?
                                        image-path out-end-offset own-pid panel-key! place-cursor!
                                        run-palette! since status-text]]
            [gtk-async :as ga :refer [await-until go-row on-gtk]]
            [jonquil.debugger :as debugger]
            [jonquil.gtk.change-sets-pane :as change-sets-pane]
            [jonquil.gtk.debugger-pane :as dpane]
            [jonquil.gtk.docs-pane :as docs-pane]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.locations-pane :as lpane]
            [jonquil.gtk.messages-pane :as messages-pane]
            [jonquil.gtk.processes-pane :as processes-pane]
            [jonquil.gtk.trace-pane :as trace-pane]
            [jonquil.mcp.window :as window]))

(defn scene-find-usages
  "Find Usages in Project with the caret on a/join: the def and the call of
  demo.a/join, with str/join (another var) and c.clj's join, which :use
  brought in and the file can't decide, offered as Other vars and
  Unresolved."
  [ui]
  (go-row :find-usages check!
    (let [ns-b "(ns demo.b\n  (:require [demo.a :as a]\n            [clojure.string :as str]))\n"
          root (demo-project! {"deps.edn" "{:paths [\"src\"]}\n"
                               ".jonquil.edn" "{:runtime :in-process}\n"
                               "src/demo/a.clj" "(ns demo.a)\n\n(defn join [x]\n  (str \"<\" x \">\"))\n"
                               "src/demo/b.clj" (str ns-b "\n(a/join 1)\n(str/join \", \" [2 3])\n")
                               "src/demo/c.clj" "(ns demo.c\n  (:use demo.a))\n\n(join 3)\n"})
          b-path (str root "/src/demo/b.clj")
          pane (:locations-pane ui)
          _ (on-gtk ((:simulate-folder-result! ui) {:path root})
                    ((:toggle-file-tree!* ui))
                    ((:open-file! ui) b-path))
          ed (on-gtk (:ed ((:active-tab* ui))))
          _ (on-gtk (place-cursor! (:buf ed) (+ (count ns-b) 4))
                    (run-palette! ui "find usages in project"))
          listed? (await-until 5000 20 #(= 2 (count (lpane/row-texts pane))))
          rows (on-gtk (lpane/row-texts pane))
          title (on-gtk (lpane/title-text pane))
          labels (on-gtk (lpane/match-labels-text pane))
          _ (ga/settle 2)
          {:keys [exit err]} (ga/screenshot (image-path :find-usages "png") (own-pid))]
      (on-gtk (all-saved! ui)
              (check! :find-usages
                      (and listed? (= 2 (count rows)) (= ["Other vars (1)" "Unresolved (1)"] labels)
                           (str/starts-with? title "Usages of demo.a/join") (zero? exit))
                      {:rows rows :title title :labels labels :screenshot-exit exit :screenshot-err err})))))

(defn scene-compare-form
  "This form vs the file on disk: the tab's defn greet edited, and the
  Locations list titled for that form only, though another form differs
  too."
  [ui]
  (go-row :compare-form check!
    (let [root (demo-project! {"src/talk/greeting.clj"
                               "(ns talk.greeting)\n\n(defn greet [name]\n  (str \"Hello, \" name \"!\"))\n\n(def punctuation \"!\")\n"})
          path (str root "/src/talk/greeting.clj")
          _ (on-gtk ((:open-file! ui) path))
          ed (on-gtk (:ed ((:active-tab* ui))))
          edited "(ns talk.greeting)\n\n(defn greet [name]\n  (str \"Hi, \" name \"!\"))\n\n(def punctuation \"?\")\n"
          _ (on-gtk (editor/apply-op-plain! ed {:edits [[0 (count (editor/editor-text ed)) edited]] :caret 0})
                    (place-cursor! (:buf ed) 40)
                    (run-palette! ui "this form vs the file on disk"))
          pane (:locations-pane ui)
          listed? (await-until 3000 20 #(= 1 (count (lpane/row-texts pane))))
          title (on-gtk (lpane/title-text pane))
          _ (ga/settle 2)
          {:keys [exit err]} (ga/screenshot (image-path :compare-form "png") (own-pid))]
      (on-gtk (all-saved! ui)
              (check! :compare-form (and listed? (= "Differences in defn greet from disk" title) (zero? exit))
                      {:title title :screenshot-exit exit :screenshot-err err})))))

(defn scene-reader
  "The Reader on clojure.core/map: opened from a symbol at the caret, then a
  click on the qualified name follows to a second page, so Back is live,
  and the page is bookmarked. In process, so the doc is quick doc's text."
  [ui]
  (go-row :reader check!
    (let [pane (:docs-pane ui)
          _ (on-gtk (editor/set-text! (:editor ui) "(map inc [1 2 3])")
                    (place-cursor! (:buf (:editor ui)) 2)
                    (panel-key! ui \9))
          shown? (await-until 3000 20 #(and (pos? (g/gtk-widget-get-visible (:root pane)))
                                            (str/starts-with? (docs-pane/title-text pane) "map")))
          text (on-gtk (docs-pane/view-text pane))
          at (str/index-of text "clojure.core/map")
          _ (on-gtk (when at (docs-pane/follow-offset! pane (+ at 3))))
          followed? (await-until 3000 20 #(str/includes? (docs-pane/title-text pane) "page 2 of 2"))
          _ (on-gtk ((:on-bookmark @(:handlers pane))))
          marked? (await-until 3000 20 #(= "Bookmarked" (docs-pane/bookmark-label pane)))
          _ (ga/settle 2)
          title (on-gtk (docs-pane/title-text pane))
          {:keys [exit err]} (ga/screenshot (image-path :reader "png") (own-pid))]
      (on-gtk (check! :reader (and shown? at followed? marked? (zero? exit))
                      {:title title :screenshot-exit exit :screenshot-err err})))))

(defn scene-change-sets
  "The change sets panel on a set of two changed forms: a Run, edits to
  greet and shout, Make a Change Set from the forms, kept under a name."
  [ui]
  (go-row :change-sets check!
    (let [ran "(ns talk.greeting)\n\n(defn greet [name]\n  (str \"Hello, \" name \"!\"))\n\n(defn shout [s]\n  (str s \"!\"))\n"
          edited "(ns talk.greeting)\n\n(defn greet [name]\n  (str \"Hi, \" name \"!\"))\n\n(defn shout [s]\n  (.toUpperCase (str s \"!\")))\n"
          ;; the panel prints each form's absolute path, so the project sits
          ;; at a short one; removed again below
          dir (io/file "/tmp/my-talk")
          _ (do (.mkdirs dir) (spit (io/file dir "greeting.clj") ran))
          p (:change-sets-pane ui)
          _ (on-gtk ((:open-file! ui) (.getPath (io/file dir "greeting.clj"))))
          ed (on-gtk (:ed ((:active-tab* ui))))
          _ (on-gtk ((:run!* ui)))
          recorded? (await-until 8000 20 #(= ran @(:last-run (:changed ed))))
          _ (on-gtk (editor/apply-op-plain! ed {:edits [[0 (count ran) edited]] :caret 0})
                    (run-palette! ui "make a change set from the forms"))
          drafted? (await-until 3000 20 #(str/includes? (change-sets-pane/summary-text p) "2 forms in 1 file"))
          _ (on-gtk (change-sets-pane/set-name! p "friendlier-greetings")
                    (change-sets-pane/click! p :save))
          kept? (await-until 3000 20 #(not (str/includes? (change-sets-pane/summary-text p) "(not kept yet)")))
          _ (ga/settle 2)
          diff (on-gtk (change-sets-pane/diff-text p))
          summary (on-gtk (change-sets-pane/summary-text p))
          {:keys [exit err]} (ga/screenshot (image-path :change-sets "png") (own-pid))]
      (on-gtk (all-saved! ui)
              (doseq [f (reverse (file-seq (io/file "/tmp/my-talk")))] (.delete f))
              (check! :change-sets
                      (and recorded? drafted? kept? (str/includes? diff "+   (.toUpperCase") (zero? exit))
                      {:summary summary :diff diff :screenshot-exit exit :screenshot-err err})))))

(defn scene-breakpoint
  "A breakpoint on line 3 of a saved file: the red dot in the gutter, then
  a console call of the function pauses there with the Frames panel titled
  Breakpoint at line 3 and the locals listed. Continue afterwards."
  [ui]
  (go-row :breakpoint check!
    (if-not (debugger/break-available?)
      (on-gtk (check! :breakpoint false {:reason "this Jolt has no jolt.debug; nothing captured"}))
      (let [dp (:debugger-pane ui)
            root (demo-project! {"src/shop/price.clj"
                                 "(defn total [price qty]\n  (let [subtotal (* price qty)]\n    (+ subtotal 5)))\n"})
            _ (on-gtk ((:open-file! ui) (str root "/src/shop/price.clj")))
            ed (on-gtk (:ed ((:active-tab* ui))))
            _ (on-gtk (caret-to-line! ed 3)
                      (run-palette! ui "toggle a breakpoint"))
            live? (await-until 8000 20 #(str/starts-with? (status-text ui) "; Breakpoint set at line 3; it's live now."))
            dot? (on-gtk (= 1 (count @(:dots (:gutter (:changed ed))))))
            _ (console-enter! ui "(total 20 3)")
            paused? (await-until 5000 20 #(= "Breakpoint at line 3" (dpane/title-text dp)))
            rows (on-gtk (dpane/row-texts dp))
            local? (boolean (some #{"    subtotal = 60"} rows))
            _ (ga/settle 2)
            {:keys [exit err]} (ga/screenshot (image-path :breakpoint "png") (own-pid))
            _ (on-gtk (dpane/press-restart! dp :continue))
            _ (await-until 5000 20 #(idle? ui))
            _ (on-gtk (let [m @(:dots (:gutter (:changed ed)))]
                        (doseq [d m] (g/gtk-text-buffer-delete-mark (:buf ed) d))
                        (reset! (:dots (:gutter (:changed ed))) [])
                        (reset! debugger/breakpoint-hook nil)
                        (debugger/set-debug-mode! false)
                        (all-saved! ui)))]
        (on-gtk (check! :breakpoint (and live? dot? paused? local? (zero? exit))
                        {:live live? :dot dot? :paused paused? :rows (take 6 rows)
                         :screenshot-exit exit :screenshot-err err}))))))

(defn scene-trace
  "Trace on a small recursive function and its helper: sum-squares calls
  itself and square, both traced, so the Trace panel lists the outer call
  with each square call indented under it (Jolt doesn't record a traced
  function's call to itself)."
  [ui]
  (go-row :trace check!
    (let [code "(defn square [x]\n  (* x x))\n\n(defn sum-squares [n]\n  (if (zero? n)\n    0\n    (+ (square n) (sum-squares (dec n)))))"
          mark (on-gtk (let [m (out-end-offset ui)]
                         (editor/set-text! (:editor ui) code)
                         ((:run!* ui))
                         m))
          ran? (await-until 5000 20 #(and (idle? ui) (str/includes? (since ui mark) "#'user/sum-squares")))
          trace-at! (fn [needle want]
                      (on-gtk (place-cursor! (:buf (:editor ui)) (+ 1 (str/index-of code needle)))
                              (run-palette! ui "trace the function at the caret"))
                      (await-until 3000 20 #(= want (status-text ui))))
          t1? (trace-at! "square [x]" "; Tracing user/square.")
          t2? (trace-at! "sum-squares [n]" "; Tracing user/sum-squares.")
          m2 (console-enter! ui "(sum-squares 3)")
          called? (await-until 5000 20 #(and (idle? ui) (str/includes? (since ui m2) "=> 14")))
          _ (on-gtk (run-palette! ui "show the calls of the traced"))
          pane (:trace-pane ui)
          shown? (await-until 3000 20 #(= 4 (count (trace-pane/row-texts pane))))
          rows (on-gtk (trace-pane/row-texts pane))
          _ (ga/settle 2)
          {:keys [exit err]} (ga/screenshot (image-path :trace "png") (own-pid))]
      (on-gtk (check! :trace (and ran? t1? t2? called? shown? (zero? exit))
                      {:rows rows :screenshot-exit exit :screenshot-err err})))))

(defn scene-line-counts
  "Count how often each line runs, then Run a saved file: the gutter labels
  each line that ran with its hits."
  [ui]
  (go-row :line-counts check!
    (let [code "(ns demo.spin)\n\n(defn spin [n]\n  (loop [i 0]\n    (when (< i n)\n      (recur (inc i)))))\n\n(spin 5)\n"
          root (demo-project! {"src/demo/spin.clj" code})
          _ (on-gtk ((:open-file! ui) (str root "/src/demo/spin.clj")))
          ed (on-gtk (:ed ((:active-tab* ui))))
          counts-of (fn [] (mapv second @(:counts (:gutter (:changed ed)))))
          _ (on-gtk (run-palette! ui "count how often each line runs"))
          on? (await-until 8000 20 #(= "; Line counts on: Run your file to count its lines." (status-text ui)))
          _ (on-gtk ((:run!* ui)))
          counted? (await-until 8000 20 #(some #{"6"} (counts-of)))
          labels (on-gtk (counts-of))
          _ (ga/settle 2)
          {:keys [exit err]} (ga/screenshot (image-path :line-counts "png") (own-pid))
          _ (on-gtk (run-palette! ui "count how often each line runs")
                    (all-saved! ui))]
      (on-gtk (check! :line-counts (and on? counted? (zero? exit))
                      {:labels labels :screenshot-exit exit :screenshot-err err})))))

(defn scene-processes
  "The Processes panel after a Run that spawned two fibers that keep
  yielding and one that finished."
  [ui]
  (go-row :processes check!
    (let [code (str "(def stop? (atom false))\n"
                    "(defn worker []\n"
                    "  (loop []\n"
                    "    (when-not @stop?\n"
                    "      (jolt.fibers/yield)\n"
                    "      (recur))))\n"
                    "(jolt.fibers/spawn worker)\n"
                    "(jolt.fibers/spawn worker)\n"
                    "(jolt.fibers/spawn (fn [] :done))\n"
                    ":spawned")
          procp (:processes-pane ui)
          _ (on-gtk (panel-key! ui \8))
          shown? (await-until 3000 20 #(pos? (g/gtk-widget-get-visible (:root procp))))
          mark (on-gtk (let [m (out-end-offset ui)]
                         (editor/set-text! (:editor ui) code)
                         ((:run!* ui))
                         m))
          ran? (await-until 5000 20 #(and (idle? ui) (str/includes? (since ui mark) "=>")))
          rows-of (fn [] (processes-pane/row-texts procp))
          listed? (await-until 4000 50 #(and (<= 3 (count (rows-of)))
                                             (some (fn [r] (str/includes? r "done")) (rows-of))))
          rows (on-gtk (rows-of))
          _ (ga/settle 2)
          {:keys [exit err]} (ga/screenshot (image-path :processes "png") (own-pid))
          _ (on-gtk (reset! @(resolve 'user/stop?) true))]
      (on-gtk (check! :processes (and shown? ran? listed? (zero? exit))
                      {:rows rows :screenshot-exit exit :screenshot-err err})))))

(defn scene-messages
  "The Messages panel with a few entries: a client called Claude Code
  replaced the text of two tabs and read the tab list (Reads ticked), and
  with the Reads filter ticked."
  [ui]
  (go-row :messages check!
    (let [mp (:messages-pane ui)
          root (demo-project! {"src/talk/core.clj" "(ns talk.core)\n\n(defn hello [] \"hi\")\n"
                               "src/talk/demo.clj" "(ns talk.demo)\n"})
          [t1 t2] (on-gtk ((:open-file! ui) (str root "/src/talk/core.clj"))
                          ((:open-file! ui) (str root "/src/talk/demo.clj"))
                          (take-last 2 ((:tabs* ui))))
          tools (window/with-messages
                  (fn [] (window/make-window-tools {:ui ui :dir root})) ui (atom {:client "Claude Code"}))
          call (fn [tname args] ((:call (tools)) tname args))
          _ (call "jonquil_tabs" {})
          r1 (call "jonquil_replace_text" {"text" "(ns talk.core)\n\n(defn hello [name]\n  (str \"hi \" name))\n" "id" (:id t1)})
          r2 (call "jonquil_replace_text" {"text" "(ns talk.demo\n  (:require [talk.core :as c]))\n\n(c/hello \"Jonquil\")\n" "id" (:id t2)})
          _ (on-gtk (panel-key! ui \7)
                    (messages-pane/set-kind! mp :agent-read true))
          listed? (await-until 3000 20 #(<= 3 (count (messages-pane/row-texts mp))))
          rows (on-gtk (messages-pane/row-texts mp))
          _ (ga/settle 2)
          {:keys [exit err]} (ga/screenshot (image-path :messages "png") (own-pid))]
      (on-gtk (all-saved! ui)
              (check! :messages (and (not (:error? r1)) (not (:error? r2)) listed? (zero? exit))
                      {:rows rows :screenshot-exit exit :screenshot-err err})))))
