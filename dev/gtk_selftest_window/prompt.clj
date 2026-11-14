(ns gtk-selftest-window.prompt
  "The console prompt names the session's namespace, clj style
  (`user=> `, `demo.x=> `), and follows it after a REPL line, a Run and a
  slow evaluation with text already typed behind it.

  Every row is go-style (dev/gtk_async.clj): (fn [ui] ch). Each GTK step
  runs through on-gtk, each wait parks in await-until, and each row records
  exactly one check. The live prompt is read as the console's tail from
  out-end: run-row clears the input between rows, so that tail is the
  prompt followed by whatever a row has typed."
  (:require [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]))

(defn- tail
  "The console from out-end onward: the live prompt plus any typed input."
  [ui]
  (support/since ui (support/out-end-offset ui)))

(defn- submit!
  "Types code at the console's caret and presses Return. GTK thread only."
  [ui code]
  (let [cv (:console ui)]
    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) code -1 1)
    (cview/handle-return! cv false)))

(defn- back-to-user
  "Clears the input, then parks until an (in-ns 'user) REPL line has put
  the prompt back on user=> , so later rows start where a fresh window
  does. Returns whether it got there."
  [ui]
  (on-gtk (cview/clear-input! (:console ui))
          (submit! ui "(in-ns 'user)"))
  (await-until 3000 20 #(and (support/idle? ui) (= "user=> " (tail ui)))))

(defn row-prompt-starts-user
  "A fresh window's prompt reads user=> , both as text and as the view's
  own prompt value."
  [ui]
  (go-row :prompt-starts-user support/check
    (let [[t v] (on-gtk [(tail ui) @(:prompt-text (:console ui))])]
      (on-gtk (support/check :prompt-starts-user (and (= "user=> " t) (= "user=> " v))
                             {:tail t :prompt v})))))

(defn row-prompt-follows-repl-ns
  "(ns demo.x) at the prompt moves the live prompt to demo.x=> ."
  [ui]
  (go-row :prompt-follows-repl-ns support/check
    (on-gtk (submit! ui "(ns demo.x)"))
    (let [ok (await-until 3000 20 #(and (support/idle? ui) (= "demo.x=> " (tail ui))))
          t (on-gtk (tail ui))
          back (back-to-user ui)]
      (on-gtk (support/check :prompt-follows-repl-ns (and ok back) {:tail t :back back})))))

(defn row-prompt-keeps-typed-input
  "Text typed behind the prompt while a slow evaluation runs survives the
  rewrite when that evaluation moves the namespace: the prompt becomes
  demo.y=> with the typed text intact after it, and the caret stays at
  the end of that text."
  [ui]
  (go-row :prompt-keeps-typed-input support/check
    (on-gtk (submit! ui "(do (Thread/sleep 800) (ns demo.y))"))
    (let [busy (await-until 2000 20 #(not (support/idle? ui)))
          _ (on-gtk (g/gtk-text-buffer-insert-interactive-at-cursor (:buf (:console ui)) "typed" -1 1))
          ok (await-until 4000 20 #(and (support/idle? ui) (= "demo.y=> typed" (tail ui))))
          [t caret-at-end?] (on-gtk (let [buf (:buf (:console ui))]
                                      [(tail ui)
                                       (= (g/cursor-offset buf) (count (g/buffer-text buf)))]))
          back (back-to-user ui)]
      (on-gtk (support/check :prompt-keeps-typed-input (and busy ok caret-at-end? back)
                             {:busy busy :tail t :caret-at-end caret-at-end? :back back})))))

(defn row-prompt-follows-run
  "Run on a buffer that opens with (ns demo.z) moves the prompt to
  demo.z=> ."
  [ui]
  (go-row :prompt-follows-run support/check
    (let [[ran _] (support/run-and-await ui "(ns demo.z)\n(def v 1)" 3000 #(str/includes? % "#'demo.z/v"))
          ok (await-until 2000 20 #(= "demo.z=> " (tail ui)))
          t (on-gtk (tail ui))
          back (back-to-user ui)]
      (on-gtk (support/check :prompt-follows-run (and ran ok back) {:ran ran :tail t :back back})))))

(defn row-prompt-echo-uses-prompt
  "Cmd/Ctrl+E echoes the entry behind the current prompt: demo.e=> (inc 41)
  after (ns demo.e), not a fixed marker."
  [ui]
  (go-row :prompt-echo-uses-prompt support/check
    (on-gtk (submit! ui "(ns demo.e)"))
    (let [moved (await-until 3000 20 #(and (support/idle? ui) (= "demo.e=> " (tail ui))))
          mark (on-gtk (let [ed (:editor ui)
                             m (support/out-end-offset ui)
                             text "(inc 41)"]
                         (editor/set-text! ed text)
                         (support/place-cursor! (:buf ed) (count text))
                         ((:run-selection!* ui))
                         m))
          ok (await-until 3000 20 #(let [t (support/since ui mark)]
                                     (and (str/includes? t "demo.e=> (inc 41)\n") (str/includes? t "=> 42"))))
          t (on-gtk (support/since ui mark))
          back (back-to-user ui)]
      (on-gtk (support/check :prompt-echo-uses-prompt (and moved ok back) {:moved moved :console t :back back})))))

(defn row-bang-runs-a-command
  "!list every key binding at the prompt runs that palette command: the
  console says so and prints the bindings, the prompt comes back, nothing
  is evaluated, and Up brings the line back."
  [ui]
  (go-row :bang-runs-a-command support/check
    (let [mark (on-gtk (support/out-end-offset ui))
          _ (on-gtk (submit! ui "!list every key binding"))
          ran? (await-until 3000 20 #(let [t (support/since ui mark)]
                                       ;; the bindings name the platform's own modifier
                                       (and (str/includes? t "; ! List every key binding.")
                                            (or (str/includes? t "Cmd") (str/includes? t "Ctrl"))
                                            (str/ends-with? t "user=> "))))
          text (on-gtk (support/since ui mark))
          recalled (on-gtk (cview/handle-history! (:console ui) :prev) (cview/input-text (:console ui)))
          _ (on-gtk (cview/clear-input! (:console ui)))]
      (on-gtk (support/check :bang-runs-a-command
                             (and ran? (not (str/includes? text "Unable to resolve")) (= "!list every key binding" recalled))
                             {:ran ran? :recalled recalled :tail (subs text (max 0 (- (count text) 200)))})))))

(defn row-bang-lists-choices
  "! words that match several commands list them and run none; words that
  match nothing say so."
  [ui]
  (go-row :bang-lists-choices support/check
    (let [mark (on-gtk (support/out-end-offset ui))
          _ (on-gtk (submit! ui "! key"))
          listed? (await-until 3000 20 #(str/includes? (support/since ui mark) "commands match \"key\":"))
          _ (on-gtk (submit! ui "! zzqx"))
          none? (await-until 3000 20 #(str/includes? (support/since ui mark) "; ! No command matches \"zzqx\"."))
          text (on-gtk (support/since ui mark))]
      (on-gtk (support/check :bang-lists-choices (and listed? none? (not (str/includes? text "; ! List every key binding.")))
                             {:listed listed? :none none? :tail (subs text (max 0 (- (count text) 300)))})))))

(defn row-bang-clears-the-last-output
  "!clear the last entry's output clears the entry before the ! line, as
  Cmd/Ctrl+Shift+L would, not the ! line itself."
  [ui]
  (go-row :bang-clears-the-last-output support/check
    (let [mark (on-gtk (support/out-end-offset ui))
          _ (on-gtk (submit! ui "(+ 40 2)"))
          ran? (await-until 3000 20 #(and (support/idle? ui) (str/includes? (support/since ui mark) "=> 42")))
          _ (on-gtk (submit! ui "!clear the last entry's output"))
          cleared? (await-until 3000 20 #(not (str/includes? (support/since ui mark) "=> 42")))
          text (on-gtk (support/since ui mark))]
      (on-gtk (support/check :bang-clears-the-last-output (and ran? cleared? (str/ends-with? text "user=> "))
                             {:ran ran? :cleared cleared? :text text})))))

