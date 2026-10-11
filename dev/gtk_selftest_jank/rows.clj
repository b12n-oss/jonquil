(ns gtk-selftest-jank.rows
  "Rows for the jank window: a real window on a throwaway project whose
  src/demo/core.jank selects the :jank profile, with JONQUIL_JANK naming
  dev/fake-jank. The window's evaluator starts that fake as a real child
  process (the real launcher, its held-open stdin and its run dir), so the
  rows can check pids and the run dir on disk. Nothing here needs a jank
  install."
  (:require [clojure.core.async :refer [<! timeout]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jolt.process :as proc]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.help-popover :as help-popover]
            [jonquil.gtk.popover :as popover]
            [jonquil.inspect :as inspect]
            [jonquil.jank :as jank]
            [jonquil.nrepl.jank :as nrepl-jank]
            [temp-dirs]))

(def ^:private KEY-F12 0xffc9)
(def ^:private KEY-I 0x069)

(def fixture-core-text
  (str "(ns demo.core)\n"
       "\n"
       "(defn greet\n"
       "  \"Says hi.\"\n"
       "  [w]\n"
       "  (str \"hello \" w))\n"
       "\n"
       "(greet \"x\")\n"))

;; The fixture project, stashed so rows and after-close-checks! (which runs
;; after main/start! returns) reach it. :closing holds the pid and run dir
;; row-jank-close-kills-child recorded right before it quit.
(defonce ^:private fixture* (atom nil))

(defn fixture! []
  (let [d (io/file (.getCanonicalPath (io/file (temp-dirs/temp-dir! "jonquil-selftest-jank-"))))
        f (io/file d "src/demo/core.jank")]
    (.mkdirs (.getParentFile f))
    (spit f fixture-core-text)
    (reset! fixture* {:dir (.getCanonicalPath d) :file (.getCanonicalPath f)})))

(defn evaluator [ui] ((:evaluator* ui)))

(defn idle? [ui] (not (ev/evaluating? (evaluator ui))))

(defn- label [ui] (g/label-text (:dialect-label ui)))

(defn- pid-of [ui] (nrepl-jank/child-pid (evaluator ui)))

(defn- pid-gone?
  "True when no process has pid. Runs a subprocess, so call it from a row
  body, never from an await-until predicate (those run on the GTK thread)."
  [pid]
  (not (zero? (:exit (proc/sh "kill" "-0" (str pid))))))

(defn- await-gone
  "Parks until pid is gone or deadline-ms passes. A channel-parking loop in
  the row's own go block."
  [pid deadline-ms]
  (let [t0 (System/currentTimeMillis)]
    (loop []
      (cond (pid-gone? pid) true
            (>= (- (System/currentTimeMillis) t0) deadline-ms) false
            :else (do (<! (timeout 50)) (recur))))))

(defn- run-and-await
  "Sets the editor to code, Runs, and parks until the evaluator is idle and
  (expect-fn text-since-start) holds. [ok mark]."
  [ui code deadline-ms expect-fn]
  (let [mark (on-gtk (let [m (support/out-end-offset ui)]
                       (editor/set-text! (:editor ui) code)
                       ((:run!* ui))
                       m))
        ok (await-until deadline-ms 20 #(and (idle? ui) (expect-fn (support/since ui mark))))]
    [ok mark]))

(defn- repl-line!
  "Types line at the console prompt and presses Return. Returns the mark."
  [ui line]
  (on-gtk (let [cv (:console ui) m (support/out-end-offset ui)]
            (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) line -1 1)
            (cview/handle-return! cv false)
            m)))

(defn- close-extra-tabs!
  "Marks every tab but the first saved and closes it, then selects the
  first. GTK-thread only."
  [ui]
  (doseq [t (rest ((:tabs* ui)))]
    (editor/mark-saved! (:ed t))
    ((:close-tab! ui) (:id t)))
  ((:select-tab! ui) (:id (first ((:tabs* ui))))))

(defn row-jank-starts-not-running
  "The window opened src/demo/core.jank: the label says jank isn't running,
  the console carries the experimental line and the four limits, and Reset
  on Run is available (jank is launched, not attached)."
  [ui]
  (go-row :jank-starts-not-running support/check
    (on-gtk (let [text (support/console-text ui)
                  sensitive (g/gtk-widget-get-sensitive (:reset-check ui))
                  limits (count (re-seq #"; Limit: " text))]
              (support/check :jank-starts-not-running
                             (and (= "jank (not running)" (label ui))
                                  (str/includes? text "; jank support is experimental.")
                                  (= 4 limits)
                                  (= 1 sensitive))
                             {:label (label ui) :limits limits :sensitive sensitive :text text})))))

(defn row-jank-run-moves-prompt
  "Run starts the fake: the console says it is starting jank, the label
  turns to running, and the prompt moves to the buffer's namespace."
  [ui]
  (go-row :jank-run-moves-prompt support/check
    (let [binary (System/getenv "JONQUIL_JANK")
          starting (jank/starting-message binary)
          [ok? mark] (run-and-await ui fixture-core-text 20000 #(str/includes? % "=> "))
          running? (await-until 2000 20 #(= "jank (running)" (label ui)))
          prompt? (await-until 2000 20 #(= "demo.core=> " (support/since ui (support/out-end-offset ui))))]
      (on-gtk (support/check :jank-run-moves-prompt
                             (and ok? running? prompt? (str/includes? (support/since ui mark) starting))
                             {:ok ok? :running running? :prompt prompt? :label (label ui)
                              :starting starting :text (support/since ui mark)})))))

(defn row-jank-repl-line
  "A REPL line at the prompt is evaluated in the running child."
  [ui]
  (go-row :jank-repl-line support/check
    (let [mark (repl-line! ui "(+ 1 2)")
          ok (await-until 5000 20 #(and (idle? ui) (str/includes? (support/since ui mark) "=> 3")))]
      (on-gtk (support/check :jank-repl-line ok {:text (support/since ui mark)})))))

(defn row-jank-selection-not-loaded
  "Run Selection on (f) in a second tab whose namespace (demo.other) jank
  never loaded: the not-loaded sentence, nothing else sent."
  [ui]
  (go-row :jank-selection-not-loaded support/check
    (let [text "(ns demo.other)\n(f)"
          t2 (on-gtk ((:new-tab!* ui)))
          mark (on-gtk (let [m (support/out-end-offset ui) ed (:ed t2)]
                         ((:select-tab! ui) (:id t2))
                         (editor/set-text! ed text)
                         (support/place-cursor! (:buf ed) (str/index-of text "(f)"))
                         ((:run-selection!* ui))
                         m))
          ok (await-until 5000 20
                          #(and (idle? ui)
                                (str/includes? (support/since ui mark) (jank/not-loaded-message "demo.other"))))]
      (on-gtk (try
                (support/check :jank-selection-not-loaded ok {:text (support/since ui mark)})
                (finally (close-extra-tabs! ui)))))))

(defn row-jank-quick-doc
  "Quick doc on greet in (greet \"x\"), with jank idle and running: the
  popover shows the fake's docstring."
  [ui]
  (go-row :jank-quick-doc support/check
    (let [hp (:help-popover ui)]
      (on-gtk (let [ed (:editor ui)]
                (editor/set-text! ed "(greet \"x\")")
                (support/place-cursor! (:buf ed) 2)
                ((:quick-doc!* ui))))
      (let [ok (await-until 3000 20 #(str/includes? (help-popover/quick-doc-text hp) "Says hi."))]
        (on-gtk (support/check :jank-quick-doc (and ok (popover/open? hp))
                               {:ok ok :open (popover/open? hp) :text (help-popover/quick-doc-text hp)})
                (popover/hide! hp))))))

(defn row-jank-f12-in-buffer
  "F12 on the greet call lands the caret on the greet of its defn, a jump
  inside the buffer that needs no jank lookup."
  [ui]
  (go-row :jank-f12-in-buffer support/check
    (on-gtk (let [ed (:editor ui)
                  text fixture-core-text
                  call (str/last-index-of text "greet")]
              (editor/set-text! ed text)
              (support/place-cursor! (:buf ed) (+ call 2))
              (let [handled (editor/handle-key! ed KEY-F12 0)
                    caret (g/cursor-offset (:buf ed))
                    want (+ (str/index-of text "(defn ") (count "(defn "))]
                (support/check :jank-f12-in-buffer
                               (and (= 1 handled) (= want caret))
                               {:handled handled :caret caret :want want}))))))

(defn row-jank-inspector-unavailable
  "Cmd/Ctrl+I says jank can't be inspected, in the status line. The status
  label is set through the GUI queue, so the row waits for it."
  [ui]
  (go-row :jank-inspector-unavailable support/check
    (let [handled (on-gtk (do (g/gtk-label-set-text (:status-label ui) "")
                              ((:handle-window-key! ui) nil KEY-I 0 g/CONTROL-MASK nil)))
          ok (await-until 2000 20 #(= inspect/unavailable-message (g/label-text (:status-label ui))))]
      (on-gtk (support/check :jank-inspector-unavailable
                             (and (= 1 handled) ok)
                             {:handled handled :status (g/label-text (:status-label ui))})))))

(defn row-jank-stop-restarts
  "(spin) never finishes, so Stop kills the child: the console says jank
  restarted, the label goes back to not running, and the old pid is gone.
  The next REPL line starts a new child."
  [ui]
  (go-row :jank-stop-restarts support/check
    (let [old (pid-of ui)
          mark (repl-line! ui "(spin)")
          started? (await-until 3000 20 #(not (idle? ui)))
          _ (on-gtk ((:stop!* ui)))
          stopped? (await-until 5000 20 #(and (idle? ui)
                                              (str/includes? (support/since ui mark) jank/stopped-message)))
          not-running? (await-until 2000 20 #(= "jank (not running)" (label ui)))
          gone? (and old (await-gone old 3000))
          mark2 (repl-line! ui "(+ 1 2)")
          answered? (await-until 20000 20 #(and (idle? ui) (str/includes? (support/since ui mark2) "=> 3")))
          new (pid-of ui)]
      (on-gtk (support/check :jank-stop-restarts
                             (and old started? stopped? not-running? gone? answered? new (not= old new))
                             {:old old :new new :started started? :stopped stopped? :not-running not-running?
                              :gone gone? :answered answered? :text (support/since ui mark)})))))

(defn row-jank-reset-restarts
  "With Reset on Run ticked, a second Run replaces the child: the console
  says so and the pid changes. The tick is put back afterwards."
  [ui]
  (go-row :jank-reset-restarts support/check
    (let [chk (:reset-check ui)
          ;; Untick, then tick: only a toggled signal tells the evaluator
          ;; the mode (see the report: the window's first evaluator starts
          ;; with no mode, so an already-ticked box does nothing).
          before (on-gtk (let [a (g/gtk-check-button-get-active chk)]
                           (g/gtk-check-button-set-active chk 0)
                           (g/gtk-check-button-set-active chk 1)
                           a))]
      (try
        (let [[ok1 _] (run-and-await ui fixture-core-text 30000 #(str/includes? % "=> "))
              pid1 (pid-of ui)
              [ok2 mark2] (run-and-await ui fixture-core-text 30000
                                         #(and (str/includes? % jank/reset-message) (str/includes? % "=> ")))
              pid2 (pid-of ui)
              gone? (and pid1 (await-gone pid1 3000))]
          (on-gtk (support/check :jank-reset-restarts
                                 (and ok1 ok2 pid1 pid2 (not= pid1 pid2) gone?)
                                 {:ok1 ok1 :ok2 ok2 :pid1 pid1 :pid2 pid2 :gone gone?
                                  :text (support/since ui mark2)})))
        (finally
          (on-gtk (g/gtk-check-button-set-active chk before)))))))

(defn row-jank-close-kills-child
  "The deliberately LAST row: records the child's pid and run dir, then
  fires the app's quit action. after-close-checks! asserts they are gone
  once the GTK loop has ended."
  [ui]
  (go-row :jank-close-kills-child support/check
    (on-gtk (let [e (evaluator ui)]
              (swap! fixture* assoc :closing {:pid (nrepl-jank/child-pid e) :run-dir (nrepl-jank/run-dir e)})
              (editor/set-text! (:editor ui) "")
              ((:activate-quit-action!* ui))))))

(defn after-close-checks!
  "Called by -main once main/start! returns: the child process is gone, its
  run dir is gone, and the project directory never got a .nrepl-port. Then
  deletes the project."
  []
  (let [{:keys [dir closing]} @fixture*
        {:keys [pid run-dir]} closing
        ;; the kill is synchronous in jank-child/kill!, but give a slow
        ;; exit a moment before calling the child alive.
        gone? (boolean (and pid (loop [n 0]
                                  (cond (pid-gone? pid) true
                                        (>= n 50) false
                                        :else (do (Thread/sleep 100) (recur (inc n)))))))]
    (support/check :jank-close-recorded (boolean (and pid run-dir)) {:closing closing})
    (support/check :jank-close-pid-gone gone? {:pid pid})
    (support/check :jank-close-run-dir-gone (boolean (and run-dir (not (.exists (io/file run-dir))))) {:run-dir run-dir})
    (support/check :jank-no-port-file-in-project (not (.exists (io/file dir ".nrepl-port"))) {:dir dir})
    (doseq [x (reverse (file-seq (io/file dir)))] (.delete ^java.io.File x))))
