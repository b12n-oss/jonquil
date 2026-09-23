(ns gtk-selftest-window
  "P2.T7 self-test: opens the REAL Jonquil window via jonquil.main/start!
  and runs the 8 Run/Stop/REPL/stdin/open+save rows against its ui, on the
  same timer-chain pattern as dev/gtk_selftest.clj -- never a fixed sleep,
  every wait polls a condition against a deadline. Rows call ui's exposed
  functions (run!*, stop!*, open-path!, save-to!) directly, or drive the
  console's own submit path (handle-return! over an interactive insert),
  exactly as the buttons/keys and the user's typing do; no keystroke is
  ever synthesized.

  Each row resets the editor and console first and leaves the engine idle
  before returning control to the driver -- a row that starts a program
  stops it and waits for real, rather than trusting the next row's own
  reset to paper over it."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [jonquil.eval :as eval]
            [jonquil.gtk.callbacks :as cb]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.main :as main]
            [jonquil.session :as session]))

(defonce results (atom []))

(defn- check [row ok detail]
  (swap! results conj [row (boolean ok) detail])
  (binding [*out* *err*]
    (println "[gtk-selftest-window]" (name row) (if ok "PASS" "FAIL") (pr-str detail))))

(defn- later [ms f]
  (g/g-timeout-add ms (cb/callable (fn [_data] (f) 0) [:pointer] :int) ffi/null))

(defn- poll-until
  "Polls (pred?) every interval-ms via g_timeout_add until it's truthy or
  deadline-ms elapses, then calls (done final-pred-value). Never sleeps."
  [deadline-ms interval-ms pred? done]
  (let [t0 (System/currentTimeMillis)]
    (letfn [(step []
              (cond
                (pred?) (done true)
                (>= (- (System/currentTimeMillis) t0) deadline-ms) (done false)
                :else (later interval-ms step)))]
      (step))))

(defn- console-text [ui] (g/buffer-text (:buf (:console ui))))
(defn- idle? [ui] (not (eval/evaluating? (:ctx (:session ui)))))

(defn- mark-offset
  "mark's current character offset in buf -- console-view keeps its marks
  (:out-end) in its returned map but doesn't expose an offset-of-mark
  reader, mirroring dev/gtk_selftest.clj's own copy of this helper."
  [buf mark]
  (let [it (ffi/alloc g/ITER-BYTES)]
    (try
      (g/gtk-text-buffer-get-iter-at-mark buf it mark)
      (g/gtk-text-iter-get-offset it)
      (finally (ffi/free it)))))

(defn- out-end-offset
  "The console's out-end mark's current offset: the boundary between output
  history so far and the live prompt/input tail. Capture this BEFORE a step
  runs; `since` below reads the suffix from that offset once the step is
  done, which is exactly the new output the step produced (plus the live
  prompt/input tail, harmless for a substring check) -- never anything an
  earlier row or step already appended."
  [ui]
  (let [cv (:console ui)]
    (mark-offset (:buf cv) (:out-end cv))))

(defn- since
  "Console text from mark onward -- see out-end-offset. Every row below
  scopes its own assertions to this instead of the whole accumulated
  console-text, because run-row! only clears the editor and the console's
  INPUT, never its output history, so a plain (console-text ui) search can
  match a prior row's (or a prior step's) leftover line and pass for the
  wrong reason."
  [ui mark]
  (subs (console-text ui) mark))

(defn- run-and-wait!
  "editor/set-text! code, run!*, then wait (up to deadline-ms) until the
  engine has gone idle, before calling (done)."
  [ui code deadline-ms done]
  (editor/set-text! (:editor ui) code)
  ((:run!* ui))
  (poll-until deadline-ms 20 (fn [] (idle? ui)) done))

;; --- rows --------------------------------------------------------------
;; Each row is (fn [ui next] ...): it acts on ui, records exactly one
;; check, then calls (next).

(defn- row-run-output [ui next]
  (let [mark (out-end-offset ui)]
    (editor/set-text! (:editor ui) "(println :hi) (+ 1 2)")
    ((:run!* ui))
    (poll-until 5000 20
                (fn [] (let [t (since ui mark)]
                         (and (str/includes? t ":hi") (str/includes? t "=> 3"))))
                (fn [ok]
                  ;; Poll the status label too, rather than a single read, for
                  ;; the same reason: consistency with every other condition
                  ;; here being a poll-with-deadline, never a one-shot check.
                  (poll-until 2000 20
                              (fn [] (= "Ready" (g/label-text (:status-label ui))))
                              (fn [ready?]
                                (check :run-output (and ok ready?)
                                       {:console (since ui mark) :status (g/label-text (:status-label ui))})
                                (next)))))))

(defn- row-run-while-running [ui next]
  (let [mark (out-end-offset ui)]
    (editor/set-text! (:editor ui) "(loop [] (recur))")
    ((:run!* ui))
    (poll-until 2000 20
                (fn [] (eval/evaluating? (:ctx (:session ui))))
                (fn [started?]
                  ((:run!* ui))
                  (poll-until 2000 20
                              (fn [] (str/includes? (since ui mark) "already in progress"))
                              (fn [warned?]
                                ((:stop!* ui))
                                (poll-until 3000 20
                                            (fn [] (str/includes? (since ui mark) "; interrupted"))
                                            (fn [stopped?]
                                              (check :run-while-running
                                                     (and started? warned? stopped?)
                                                     {:started started? :warned warned? :stopped stopped?
                                                      :console (since ui mark)})
                                              (next)))))))))

(defn- row-stdin-roundtrip [ui next]
  (let [mark (out-end-offset ui)]
    (editor/set-text! (:editor ui) "(let [n (read-line)] (str \"Hello, \" n))")
    ((:run!* ui))
    (poll-until 3000 20
                (fn [] @(:waiting? (:console ui)))
                (fn [waiting?]
                  (let [cv (:console ui)]
                    (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "World" -1 1)
                    (cview/handle-return! cv false)
                    (poll-until 3000 20
                                (fn [] (str/includes? (since ui mark) "=> \"Hello, World\""))
                                (fn [ok]
                                  (check :stdin-roundtrip (and waiting? ok)
                                         {:waiting waiting? :console (since ui mark)})
                                  (next))))))))

(defn- row-stop-while-reading [ui next]
  (let [mark (out-end-offset ui)]
    (editor/set-text! (:editor ui) "(let [n (read-line)] (str \"Hello, \" n))")
    ((:run!* ui))
    (poll-until 3000 20
                (fn [] @(:waiting? (:console ui)))
                (fn [waiting-before]
                  ((:stop!* ui))
                  (poll-until 3000 20
                              (fn [] (str/includes? (since ui mark) "; interrupted"))
                              (fn [interrupted?]
                                ;; waiting? is a plain worker-thread atom reset (no on-gui!
                                ;; scheduling), so it should already be false once "; interrupted"
                                ;; -- itself only visible after an on-gui! drain -- has landed.
                                ;; Poll it too rather than reading once, so a real lag shows up as
                                ;; a slow pass instead of a silent-looking false failure.
                                (let [t0 (System/currentTimeMillis)]
                                  (poll-until 2000 20
                                              (fn [] (not @(:waiting? (:console ui))))
                                              (fn [waiting-cleared?]
                                                (let [cv (:console ui)
                                                      waiting-after @(:waiting? cv)
                                                      ms (- (System/currentTimeMillis) t0)
                                                      input-before (cview/input-text cv)
                                                      inserted (not (zero? (g/gtk-text-buffer-insert-interactive-at-cursor
                                                                            (:buf cv) "z" -1 1)))
                                                      input-after (cview/input-text cv)]
                                                  (check :stop-while-reading
                                                         (and waiting-before interrupted? waiting-cleared?
                                                              (= "" input-before) inserted (= "z" input-after))
                                                         {:waiting-before waiting-before :interrupted interrupted?
                                                          :waiting-cleared waiting-cleared? :waiting-after waiting-after
                                                          :ms-to-clear ms :input-before input-before
                                                          :inserted inserted :input-after input-after})
                                                  (next)))))))))))

(defn- row-run-unbalanced [ui next]
  ;; The brief's literal fixture was "(ok)\n\n(defn f [x]" -- measured against
  ;; the real engine, (ok) throws an unresolved-symbol error at EVAL time,
  ;; before load-string's read/eval loop ever reaches the unclosed third
  ;; form, so the result is a plain :error, never "EOF while reading", and
  ;; the unclosed-bracket message never fires. (+ 1 1) evaluates cleanly, so
  ;; the loop advances to read the still-unclosed third form and genuinely
  ;; hits EOF -- same line-3 shape, the intended code path.
  (let [mark (out-end-offset ui)]
    (editor/set-text! (:editor ui) "(+ 1 1)\n\n(defn f [x]")
    ((:run!* ui))
    (poll-until 3000 20
                (fn [] (str/includes? (since ui mark) "The ( on line 3 is never closed."))
                (fn [ok]
                  (check :run-unbalanced ok {:console (since ui mark)})
                  (next)))))

(defn- row-reset-vs-accumulate
  "Every step's own assertion is scoped (via out-end-offset/since) to the
  output THAT step produced. Without this, run-row!'s reset (editor + input
  only, never the console's output history) would let the first (greet)'s
  own leftover error line -- or even a PRIOR row's -- satisfy first-error?
  regardless of whether Reset actually removed anything; see the fix-round-1
  can-fail proof in the P2.T7 report."
  [ui next]
  (run-and-wait! ui "(defn greet [] :hi)" 3000
                 (fn [_]
                   (let [mark1 (out-end-offset ui)]
                     (run-and-wait! ui "(greet)" 3000
                                    (fn [_]
                                      (let [first-error? (str/includes? (since ui mark1) "Error [")]
                                        (session/set-mode! (:session ui) :accumulate)
                                        (run-and-wait! ui "(defn greet [] :hi)" 3000
                                                       (fn [_]
                                                         (let [mark2 (out-end-offset ui)]
                                                           (run-and-wait! ui "(greet)" 3000
                                                                          (fn [_]
                                                                            (check :reset-vs-accumulate
                                                                                   (and first-error? (str/includes? (since ui mark2) "=> :hi"))
                                                                                   {:first-error first-error?
                                                                                    :first-console (since ui mark1)
                                                                                    :second-console (since ui mark2)})
                                                                            (next)))))))))))))

(defn- row-repl-after-run [ui next]
  (run-and-wait! ui "(def x 41)" 3000
                 (fn [_]
                   (let [cv (:console ui)
                         mark (out-end-offset ui)]
                     (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(inc x)" -1 1)
                     (cview/handle-return! cv false)
                     (poll-until 3000 20 (fn [] (idle? ui))
                                 (fn [_]
                                   (check :repl-after-run (str/includes? (since ui mark) "=> 42")
                                          {:console (since ui mark)})
                                   (next)))))))

(defn- row-save-and-open [ui next]
  (let [tmp (str (System/getProperty "java.io.tmpdir")
                 "/jonquil-selftest-" (System/currentTimeMillis) ".clj")]
    (editor/set-text! (:editor ui) "(+ 1 1)")
    ((:save-to! ui) tmp)
    (let [saved-content (slurp tmp)]
      (editor/set-text! (:editor ui) "(+ 2 2)")
      ((:open-path! ui) tmp)
      (let [text (editor/editor-text (:editor ui))
            title (g/window-title (:window ui))]
        (check :save-and-open
               (and (= "(+ 1 1)" saved-content) (= "(+ 1 1)" text)
                    (some? title) (not (str/includes? title "* ")))
               {:saved-content saved-content :text text :title title})
        (.delete (java.io.File. ^String tmp))
        (next)))))

(def ^:private row-fns
  [row-run-output
   row-run-while-running
   row-stdin-roundtrip
   row-stop-while-reading
   row-run-unbalanced
   row-reset-vs-accumulate
   row-repl-after-run
   row-save-and-open])

;; --- driver --------------------------------------------------------------

(defn- run-row! [ui row-fn next]
  (editor/set-text! (:editor ui) "")
  (cview/clear-input! (:console ui))
  (poll-until 2000 20 (fn [] (idle? ui))
              (fn [_idle?] (row-fn ui next))))

(defn- run-rows! [ui fns]
  (if (empty? fns)
    (let [passed (count (filter second @results)) total (count @results)]
      (println (str "SUMMARY " passed " of " total " passed"))
      ;; gtk_window_close triggers the SAME close-request path a real
      ;; user's close-button click would -- ending the run this way is
      ;; also a live check that closing the window quits the app.
      (later 50 (fn [] (g/gtk-window-close (:window ui)))))
    (run-row! ui (first fns) (fn [] (run-rows! ui (rest fns))))))

(defn -main [& _]
  (main/start! {:on-ready (fn [ui] (later 300 (fn [] (run-rows! ui row-fns))))})
  (System/exit (if (and (seq @results) (every? second @results)) 0 1)))
