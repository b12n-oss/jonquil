(ns gtk-selftest-clj.rows
  "Rows for the JVM Clojure attach window: a real window on a throwaway
  JVM project directory whose .nrepl-port names a fake nREPL server
  running in this process (dev/fake_jvm_nrepl.clj). Nothing here reads or
  connects to a real project's own .nrepl-port."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [fake-jvm-nrepl :as fake]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jonquil.clj :as clj]
            [jonquil.dialect :as dialect]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]))

(def fixture-core-text
  ;; The spike fixture's exact text (.superpowers/sdd/clj/fixture-core.clj):
  ;; demo.core with greet, a private secret returning :private-ok, and
  ;; nested. Embedded rather than read at run time: the planning store is
  ;; gitignored scratch, so this harness can't depend on it. The same text
  ;; test/jonquil/nrepl/jvm_test.clj's own fixture-core-text embeds.
  (str "(ns demo.core\n"
       "  \"A fixture namespace for the clj-attach spike.\")\n"
       "\n"
       "(defn greet\n"
       "  \"Says hello to someone.\"\n"
       "  [who]\n"
       "  (str \"hello \" who))\n"
       "\n"
       "(defn- secret [] :private-ok)\n"
       "\n"
       "(def nested {:a [1 2 {:b \"héllo 😀\"}] :c #{:x :y} :d (range 30)})\n"))

;; The fixture project and its fake server, stashed so rows and
;; after-close-checks! (which runs after main/start! returns) reach them.
(defonce ^:private fixture* (atom nil))

(defn- temp-project-dir []
  (io/file (System/getProperty "java.io.tmpdir")
           (str "jonquil-selftest-clj-" (System/currentTimeMillis) "-" (random-uuid))))

(defn fixture!
  "Starts the fake server and writes a JVM project directory for it:
  deps.edn, .jonquil.edn {:dialect :clj}, src/demo/core.clj (the spike
  fixture's text), and .nrepl-port holding the fake's port. {:dir :file
  :server :lock-text}, paths canonical."
  []
  (let [srv (fake/start! {:slow {"(slow)" 5000} :completions {"gre" [["greet" "demo.core"]]}})
        d (temp-project-dir)
        f (io/file d "src/demo/core.clj")
        lock-text (str (:port srv) "\n")]
    (.mkdirs (.getParentFile f))
    (spit (io/file d "deps.edn") "{}\n")
    (spit (io/file d ".jonquil.edn") "{:dialect :clj}\n")
    (spit f fixture-core-text)
    (spit (io/file d ".nrepl-port") lock-text)
    (reset! fixture* {:dir (.getCanonicalPath d) :file (.getCanonicalPath f) :server srv :lock-text lock-text})))

(defn evaluator [ui] ((:evaluator* ui)))

(defn idle? [ui] (not (ev/evaluating? (evaluator ui))))

(defn- srv [] (:server @fixture*))

(defn run-and-await
  "support/run-and-await's contract, idle read through the evaluator (not
  support/idle?, which reads the in-process session's own :ctx -- the
  wrong evaluator once the window has switched to the JVM attach one)."
  [ui code deadline-ms expect-fn]
  (let [mark (on-gtk (let [m (support/out-end-offset ui)]
                       (editor/set-text! (:editor ui) code)
                       ((:run!* ui))
                       m))
        ok (await-until deadline-ms 20 #(and (idle? ui) (expect-fn (support/since ui mark))))]
    [ok mark]))

(defn run-selection-and-await
  "Sets the editor to text, puts the caret at the start of needle, runs
  Cmd/Ctrl+E's own fn, then parks like run-and-await. [ok mark]."
  [ui text needle deadline-ms expect-fn]
  (let [mark (on-gtk (let [m (support/out-end-offset ui)
                           ed (:editor ui)]
                       (editor/set-text! ed text)
                       (support/place-cursor! (:buf ed) (str/index-of text needle))
                       ((:run-selection!* ui))
                       m))
        ok (await-until deadline-ms 20 #(and (idle? ui) (expect-fn (support/since ui mark))))]
    [ok mark]))

(defn- label [ui] (g/label-text (:dialect-label ui)))

;; --- P2.T4 rows --------------------------------------------------------------

(defn row-clj-starts-not-running
  "The window opened src/demo/core.clj: the label says the JVM isn't
  running yet, Reset on Run is off with its own tooltip (:clj is the
  user's own JVM), and nothing has talked to the server yet."
  [ui]
  (go-row :clj-starts-not-running support/check
    (on-gtk (let [chk (:reset-check ui)]
              (support/check :clj-starts-not-running
                             (and (= "Clojure (JVM) (not running)" (label ui))
                                  (zero? (g/gtk-widget-get-sensitive chk))
                                  (= (:reset-tooltip dialect/clj) (g/tooltip-text chk))
                                  (empty? (fake/ops-seen (srv))))
                             {:label (label ui) :sensitive (g/gtk-widget-get-sensitive chk)
                              :tooltip (g/tooltip-text chk) :ops (fake/ops-seen (srv))})))))

(defn row-clj-run-moves-prompt
  "Run is load-file over the fixture: the console shows the value line,
  the label turns to attached, and the prompt moves to the buffer's own
  namespace (ruling 4)."
  [ui]
  (go-row :clj-run-moves-prompt support/check
    (let [[ok? mark] (run-and-await ui fixture-core-text 10000 #(str/includes? % "=> #'demo.core/nested"))
          attached? (await-until 2000 20 #(= "Clojure (JVM) (attached)" (label ui)))
          prompt-ns (ev/prompt-ns (evaluator ui))]
      (on-gtk (support/check :clj-run-moves-prompt
                             (and ok? attached? (= 'demo.core prompt-ns))
                             {:ok? ok? :attached? attached? :label (label ui) :prompt-ns prompt-ns
                              :text (support/since ui mark)})))))

(defn row-clj-selection-not-loaded
  "Run Selection on (f) in a buffer whose own namespace (demo.other) was
  never loaded on the server: the not-loaded sentence, and the buffer's
  own ns form is never sent as code (rule 3/rule 5). Setting the editor's
  text doesn't change the window's file, so the selection and evaluator
  stay the same as row-clj-run-moves-prompt's."
  [ui]
  (go-row :clj-selection-not-loaded support/check
    (let [n (count (fake/requests (srv)))
          text "(ns demo.other)\n(f)"
          [ok? mark] (run-selection-and-await ui text "(f)" 5000
                                              #(str/includes? % (clj/not-loaded-message "demo.other")))
          sent (drop n (fake/requests (srv)))]
      (on-gtk (support/check :clj-selection-not-loaded
                             (and ok? (not-any? #(str/includes? (or (get % "code") "") "(ns demo.other)") sent))
                             {:ok? ok? :sent sent :text (support/since ui mark)})))))

(defn row-clj-stop-interrupts
  "A REPL entry for a slow form, then Stop: the console shows the
  interrupted line within 2000 ms, and the server saw the interrupt op."
  [ui]
  (go-row :clj-stop-interrupts support/check
    (let [cv (:console ui)
          mark (on-gtk (let [m (support/out-end-offset ui)]
                         (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(slow)" -1 1)
                         (cview/handle-return! cv false)
                         m))
          started? (await-until 2000 20 #(not (idle? ui)))
          _ (on-gtk ((:stop!* ui)))
          stopped? (await-until 2000 20 #(str/includes? (support/since ui mark) "; interrupted"))
          ops (fake/ops-seen (srv))]
      (on-gtk (support/check :clj-stop-interrupts
                             (and started? stopped? (boolean (some #{"interrupt"} ops)))
                             {:started started? :stopped stopped? :ops ops :text (support/since ui mark)})))))

(defn row-clj-stdin
  "A REPL entry for (read-line): the console's input area goes into its
  stdin state (the same check run_repl.clj's own stdin row uses), and
  typing a line answers it."
  [ui]
  (go-row :clj-stdin support/check
    (let [cv (:console ui)
          mark (on-gtk (let [m (support/out-end-offset ui)]
                         (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(read-line)" -1 1)
                         (cview/handle-return! cv false)
                         m))
          waiting? (await-until 3000 20 #(and (ev/waiting-for-input? (evaluator ui)) ((:waiting? cv))))
          _ (on-gtk (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "typed" -1 1)
                    (cview/handle-return! cv false))
          ok (await-until 3000 20 #(str/includes? (support/since ui mark) "=> \"typed\""))]
      (on-gtk (support/check :clj-stdin (and waiting? ok)
                             {:waiting waiting? :text (support/since ui mark)})))))

(defn row-clj-reconnects-after-restart
  "The server drops every connection: the label goes back to not running
  within 2000 ms, and the next REPL entry reconnects and answers."
  [ui]
  (go-row :clj-reconnects-after-restart support/check
    (let [_ (fake/drop-connections! (srv))
          not-running? (await-until 2000 20 #(= "Clojure (JVM) (not running)" (label ui)))
          cv (:console ui)
          mark (on-gtk (let [m (support/out-end-offset ui)]
                         (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(+ 1 2)" -1 1)
                         (cview/handle-return! cv false)
                         m))
          ok (await-until 5000 20 #(and (idle? ui) (str/includes? (support/since ui mark) "=> 3")))
          attached? (await-until 2000 20 #(= "Clojure (JVM) (attached)" (label ui)))]
      (on-gtk (support/check :clj-reconnects-after-restart
                             (and not-running? ok attached?)
                             {:not-running not-running? :ok ok :attached attached? :text (support/since ui mark)})))))

(defn row-clj-close
  "The deliberately LAST row: fires the app's quit action, the path a Quit
  AppleEvent takes. after-close-checks! records the result once the GTK
  loop is gone."
  [ui]
  (go-row :clj-close support/check
    (on-gtk (editor/set-text! (:editor ui) "")
            ((:activate-quit-action!* ui)))))

(defn after-close-checks!
  "Called by -main once main/start! returns. The server never got a close
  op, and the fixture's .nrepl-port is byte for byte what fixture! wrote.
  Then stops the fake server and deletes the project."
  []
  (let [{:keys [dir server lock-text]} @fixture*
        lock (io/file dir ".nrepl-port")
        ops (fake/ops-seen server)]
    (support/check :clj-never-close (not-any? #{"close"} ops) {:ops ops})
    (support/check :clj-lock-untouched
                   (and (.exists lock) (= lock-text (slurp lock)))
                   {:lock (when (.exists lock) (slurp lock))})
    (fake/stop! server)
    (doseq [x (reverse (file-seq (io/file dir)))] (.delete ^java.io.File x))))
