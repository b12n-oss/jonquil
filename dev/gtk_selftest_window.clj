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
  (editor/set-text! (:editor ui) "(println :hi) (+ 1 2)")
  ((:run!* ui))
  (poll-until 5000 20
              (fn [] (let [t (console-text ui)]
                       (and (str/includes? t ":hi") (str/includes? t "=> 3"))))
              (fn [ok]
                (let [text (console-text ui)
                      status (g/label-text (:status-label ui))]
                  (check :run-output (and ok (= "Ready" status)) {:console text :status status})
                  (next)))))

(defn- row-run-while-running [ui next]
  (editor/set-text! (:editor ui) "(loop [] (recur))")
  ((:run!* ui))
  (poll-until 2000 20
              (fn [] (eval/evaluating? (:ctx (:session ui))))
              (fn [started?]
                ((:run!* ui))
                (poll-until 2000 20
                            (fn [] (str/includes? (console-text ui) "already in progress"))
                            (fn [warned?]
                              ((:stop!* ui))
                              (poll-until 3000 20
                                          (fn [] (str/includes? (console-text ui) "; interrupted"))
                                          (fn [stopped?]
                                            (check :run-while-running
                                                   (and started? warned? stopped?)
                                                   {:started started? :warned warned? :stopped stopped?
                                                    :console (console-text ui)})
                                            (next))))))))

(defn- row-stdin-roundtrip [ui next]
  (editor/set-text! (:editor ui) "(let [n (read-line)] (str \"Hello, \" n))")
  ((:run!* ui))
  (poll-until 3000 20
              (fn [] @(:waiting? (:console ui)))
              (fn [waiting?]
                (let [cv (:console ui)]
                  (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "World" -1 1)
                  (cview/handle-return! cv false)
                  (poll-until 3000 20
                              (fn [] (str/includes? (console-text ui) "=> \"Hello, World\""))
                              (fn [ok]
                                (check :stdin-roundtrip (and waiting? ok)
                                       {:waiting waiting? :console (console-text ui)})
                                (next)))))))

(defn- row-stop-while-reading [ui next]
  (editor/set-text! (:editor ui) "(let [n (read-line)] (str \"Hello, \" n))")
  ((:run!* ui))
  (poll-until 3000 20
              (fn [] @(:waiting? (:console ui)))
              (fn [waiting-before]
                ((:stop!* ui))
                (poll-until 3000 20
                            (fn [] (str/includes? (console-text ui) "; interrupted"))
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
                                                (next))))))))))

(defn- row-run-unbalanced [ui next]
  ;; The brief's literal fixture was "(ok)\n\n(defn f [x]" -- measured against
  ;; the real engine, (ok) throws an unresolved-symbol error at EVAL time,
  ;; before load-string's read/eval loop ever reaches the unclosed third
  ;; form, so the result is a plain :error, never "EOF while reading", and
  ;; the unclosed-bracket message never fires. (+ 1 1) evaluates cleanly, so
  ;; the loop advances to read the still-unclosed third form and genuinely
  ;; hits EOF -- same line-3 shape, the intended code path.
  (editor/set-text! (:editor ui) "(+ 1 1)\n\n(defn f [x]")
  ((:run!* ui))
  (poll-until 3000 20
              (fn [] (str/includes? (console-text ui) "The ( on line 3 is never closed."))
              (fn [ok]
                (check :run-unbalanced ok {:console (console-text ui)})
                (next))))

(defn- row-reset-vs-accumulate [ui next]
  (run-and-wait! ui "(defn greet [] :hi)" 3000
                 (fn [_]
                   (run-and-wait! ui "(greet)" 3000
                                  (fn [_]
                                    (let [first-error? (str/includes? (console-text ui) "Error [")]
                                      (session/set-mode! (:session ui) :accumulate)
                                      (run-and-wait! ui "(defn greet [] :hi)" 3000
                                                     (fn [_]
                                                       (run-and-wait! ui "(greet)" 3000
                                                                      (fn [_]
                                                                        (check :reset-vs-accumulate
                                                                               (and first-error? (str/includes? (console-text ui) "=> :hi"))
                                                                               {:first-error first-error? :console (console-text ui)})
                                                                        (next)))))))))))

(defn- row-repl-after-run [ui next]
  (run-and-wait! ui "(def x 41)" 3000
                 (fn [_]
                   (let [cv (:console ui)]
                     (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(inc x)" -1 1)
                     (cview/handle-return! cv false)
                     (poll-until 3000 20 (fn [] (idle? ui))
                                 (fn [_]
                                   (check :repl-after-run (str/includes? (console-text ui) "=> 42")
                                          {:console (console-text ui)})
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
