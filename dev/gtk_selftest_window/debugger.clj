(ns gtk-selftest-window.debugger
  "The Frames panel: on its own in a transient window, then the real
  window's commands after an error. On a Jolt with jolt.debug the row sees
  frames and locals and opens a local in the inspector; on one without, it
  sees the sentence that says so."
  (:require [selftest-keys]
            [clojure.string :as str]
            [clojure.core.async :refer [<! timeout]]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jonquil.debugger :as debugger]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.console-view :as cview]
            [jonquil.gtk.debugger-pane :as dpane]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.palette :as palette]
            [jonquil.gtk.popover :as popover]
            [temp-dirs]))

(defn row-debugger-pane-shows
  "show! lists a row per frame and per local under the error's title, a
  note on its own, and activating a row calls the frame or local handler."
  [_ui]
  (go-row :debugger-pane-shows support/check
    (let [{:keys [win pane]} (on-gtk (let [win (g/gtk-window-new)
                                           pane (dpane/make-pane)]
                                       (g/gtk-window-set-child win (:root pane))
                                       (selftest-keys/watch! win)
                                       (g/gtk-window-present win)
                                       {:win win :pane pane}))
          calls (atom [])
          data {:title "ArithmeticException: Divide by zero"
                :frames [{:fn "demo.core/inner" :file "/x/demo/core.clj" :line 2
                          :locals [{:name "x" :text "21"}]}]}]
      (try
        (await-until 2000 20 #(pos? (g/gtk-widget-get-width (:root pane))))
        (on-gtk
         (dpane/set-handlers! pane {:on-frame (fn [i] (swap! calls conj [:frame i]))
                                    :on-local (fn [i n] (swap! calls conj [:local i n]))
                                    :on-eval (fn [i code] (swap! calls conj [:eval i code]))})
         (dpane/show! pane data)
         (dpane/activate! pane 0)
         (dpane/activate! pane 1)
         ;; show! selected the first row, the frame: its index goes with the code
         (dpane/eval-text! pane "(inc x)")
         (let [rows (dpane/row-texts pane)
               title (dpane/title-text pane)
               cleared? (= "" (dpane/field-text pane))
               _ (dpane/show! pane {:note "No error yet."})
               rows2 (dpane/row-texts pane)
               _ (dpane/activate! pane 0)
               _ (dpane/eval-text! pane "1")]
           (support/check :debugger-pane-shows
                          (and (= ["demo.core/inner  core.clj:2" "    x = 21"] rows)
                               (= "ArithmeticException: Divide by zero" title)
                               (= ["No error yet."] rows2)
                               cleared?
                               (= [[:frame 0] [:local 0 "x"] [:eval 0 "(inc x)"] [:eval nil "1"]] @calls))
                          {:rows rows :title title :rows2 rows2 :calls @calls :cleared cleared?})))
        (finally (on-gtk (g/gtk-window-close win)))))))

(defn row-debugger-pane-wiring
  "Each restart button reaches :on-restart with its id, Close reaches
  :on-close, and a throw in a handler lands on the footer."
  [_ui]
  (go-row :debugger-pane-wiring support/check
    (let [{:keys [win pane]} (on-gtk (let [win (g/gtk-window-new)
                                           pane (dpane/make-pane)]
                                       (g/gtk-window-set-child win (:root pane))
                                       (selftest-keys/watch! win)
                                       (g/gtk-window-present win)
                                       {:win win :pane pane}))
          calls (atom [])]
      (try
        (await-until 2000 20 #(pos? (g/gtk-widget-get-width (:root pane))))
        (on-gtk
         (reset! (:on-restart pane) (fn [id] (swap! calls conj [:restart id])))
         (doseq [id [:retry :edit :define :continue]]
           (g/g-signal-emit-by-name (get (:restart-btns pane) id) "clicked"))
         (dpane/set-handlers! pane {:on-close (fn [] (swap! calls conj [:close]))})
         (g/g-signal-emit-by-name (:close-btn pane) "clicked")
         (dpane/set-handlers! pane {:on-close (fn [] (throw (ex-info "boom" {})))})
         (g/g-signal-emit-by-name (:close-btn pane) "clicked")
         (let [footer (dpane/footer-text pane)]
           (support/check :debugger-pane-wiring
                          (and (= [[:restart :retry] [:restart :edit] [:restart :define]
                                   [:restart :continue] [:close]]
                                  @calls)
                               (= "; boom" footer))
                          {:calls @calls :footer footer})))
        (finally (on-gtk (g/gtk-window-close win)))))))

;; --- the real window -----------------------------------------------------

(defn- run-palette!
  "Opens the palette, filters it to query and runs the first entry, then
  closes it. GTK-thread only."
  [ui query]
  (let [p (:palette ui)]
    ((:open-palette!* ui))
    (palette/set-query! p query)
    (palette/run-selected! p)
    (when (popover/open? p) (popover/hide! p))))

(defn- status [ui] (g/label-text (:status-label ui)))

(def ^:private NO-API "Frames need a Jolt with jolt.debug (newer than this one).")

(def ^:private LOCAL-ROW "    xs = [1 2 3]")

(defn row-frames-after-error
  "Keep every local, Run a form that throws, then Show the last error: the
  panel's title is the error; with jolt.debug its rows hold the frame and
  its local, and activating the local opens it in the inspector; without
  it, the one row says what's needed. Debug mode goes back off."
  [ui]
  (go-row :frames-after-error support/check
    (let [dp (:debugger-pane ui)
          avail? (debugger/available?)]
      (try
        (on-gtk (run-palette! ui "keep every local"))
        (let [mode? (await-until 3000 20 #(= (status ui) (if avail?
                                                           "; Debug mode on: code you load from now on keeps its locals."
                                                           (str "; " NO-API))))
              [ran? _] (support/run-and-await ui "(defn dbg-inner [xs]\n  (let [n (count xs)] (/ n 0)))\n(dbg-inner [1 2 3])"
                                              5000 #(str/includes? % "Divide by zero"))
              _ (on-gtk (run-palette! ui "show the last error"))
              shown? (await-until 3000 20 #(= "ArithmeticException: Divide by zero" (dpane/title-text dp)))
              rows (on-gtk (dpane/row-texts dp))
              rows-ok? (if avail?
                         (boolean (and (some #{LOCAL-ROW} rows)
                                       (some #(str/starts-with? % "user/dbg-inner  line 2 (no file)") rows)))
                         (= [NO-API] rows))
              evaluated? (if avail?
                           (let [m (on-gtk (support/out-end-offset ui))]
                             (on-gtk (dpane/eval-text! dp "(count xs)"))
                             (and (await-until 3000 20 #(str/includes? (support/since ui m) "; in user/dbg-inner: (count xs)\n=> 3"))
                                  (let [m2 (on-gtk (support/out-end-offset ui))]
                                    (on-gtk (dpane/eval-text! dp "(/ n 0)"))
                                    (await-until 3000 20 #(str/includes? (support/since ui m2)
                                                                         "; in user/dbg-inner: ArithmeticException: Divide by zero")))))
                           true)
              still-last? (if avail?
                            (do (on-gtk (run-palette! ui "show the last error"))
                                (await-until 3000 20 #(= "ArithmeticException: Divide by zero" (dpane/title-text dp))))
                            true)
              inspected? (if avail?
                           (do (on-gtk (dpane/activate! dp (first (keep-indexed (fn [i r] (when (= r LOCAL-ROW) i)) rows))))
                               (await-until 3000 20 #(str/includes? (g/label-text (:info (:inspector ui))) "3 items")))
                           true)
              _ (when avail? (on-gtk (run-palette! ui "keep every local")))
              off? (or (not avail?) (await-until 3000 20 #(= (status ui) "; Debug mode off.")))]
          (on-gtk (support/check :frames-after-error (and mode? ran? shown? rows-ok? evaluated? still-last? inspected? off?)
                                 {:available avail? :mode mode? :ran ran? :shown shown? :rows rows
                                  :evaluated evaluated? :still-last still-last?
                                  :inspected inspected? :off off? :status (status ui)})))
        (finally
          ;; the in-process runtime is this process: never leave later rows
          ;; compiling in debug mode, whatever failed above
          (when avail? (debugger/set-debug-mode! false))
          (on-gtk (when-let [f (:on-close @(:handlers dp))] (f))
                  ((:close-inspector!* ui))))))))

;; --- Break on error -----------------------------------------------------------

(def ^:private BREAK-ON "; Break on error on: an error pauses where it was thrown.")
(def ^:private BREAK-OFF "; Break on error off.")

(defn- enter!
  "Types code at the console prompt and presses Return, without waiting
  for it to end (a paused entry doesn't); the out offset before it."
  [ui code]
  (on-gtk (let [cv (:console ui)
                m (support/out-end-offset ui)]
            (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) code -1 1)
            (cview/handle-return! cv false)
            m)))

(defn- break-off!
  "Never leaves a pause or Break on error behind for later rows."
  [ui]
  (ev/continue-break! (:session ui))
  (await-until 3000 20 #(support/idle? ui))
  (swap! (:state (:session ui)) assoc :break-on? false)
  (on-gtk (when-let [f (:on-close @(:handlers (:debugger-pane ui)))] (f))))

(defn row-break-pauses
  "Break on error on: an entry that throws pauses before anything unwinds.
  The panel says Paused and offers Continue, finally hasn't run, the field
  sees the throw site's binding, and Continue lets the error through:
  finally runs and the panel shows the error's frames. Without jolt.debug
  the toggle says what's needed."
  [ui]
  (go-row :break-pauses support/check
    (let [dp (:debugger-pane ui)]
      (if-not (debugger/available?)
        (do (on-gtk (run-palette! ui "break on error"))
            (let [said? (await-until 3000 20 #(= (status ui) (str "; " NO-API)))]
              (on-gtk (support/check :break-pauses said? {:available false :status (status ui)}))))
        (try
          ;; debug mode first, so the entry keeps its locals
          (debugger/set-debug-mode! true)
          (on-gtk (run-palette! ui "break on error"))
          (let [on? (await-until 3000 20 #(= (status ui) BREAK-ON))
                ;; a defn: a top-level expression keeps no frame of its own
                m0 (enter! ui (str "(def brk-fin (atom false)) (def ^:dynamic *brk-d* :root) "
                                   "(defn brk-inner [n] (try (/ n 0) (finally (reset! brk-fin true))))"))
                defined? (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m0) "#'user/brk-inner")))
                m (enter! ui "(binding [*brk-d* :bound] (brk-inner 21))")
                paused? (await-until 5000 20 #(str/starts-with? (dpane/title-text dp) "Paused: ArithmeticException"))
                said? (on-gtk (str/includes? (support/since ui m) "; Paused at ArithmeticException: Divide by zero."))
                paused-status (on-gtk (status ui))
                labels (on-gtk (dpane/restart-labels dp))
                rows (on-gtk (dpane/row-texts dp))
                local? (boolean (some #{"    n = 21"} rows))
                fin-before @@(resolve 'user/brk-fin)
                m2 (on-gtk (support/out-end-offset ui))
                _ (on-gtk (dpane/eval-text! dp "*brk-d*"))
                bound? (await-until 3000 20 #(str/includes? (support/since ui m2) "=> :bound"))
                _ (on-gtk (dpane/press-restart! dp :continue))
                errored? (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m2) "Divide by zero")))
                fin-after @@(resolve 'user/brk-fin)
                refreshed? (await-until 3000 20 #(= "ArithmeticException: Divide by zero" (dpane/title-text dp)))
                _ (on-gtk (run-palette! ui "break on error"))
                off? (await-until 3000 20 #(= (status ui) BREAK-OFF))]
            (on-gtk (support/check :break-pauses
                                   (and on? defined? paused? said? local? (= ["Continue"] labels)
                                        (str/starts-with? paused-status "; Paused: ArithmeticException: Divide by zero")
                                        (false? fin-before) bound? errored? (true? fin-after) refreshed? off?)
                                   {:on on? :defined defined? :paused paused? :said said? :status paused-status
                                    :labels labels :local local? :rows (take 4 rows) :fin-before fin-before :bound bound?
                                    :field-out (subs (support/since ui m2) 0 (min 300 (count (support/since ui m2))))
                                    :errored errored? :fin-after fin-after :refreshed refreshed? :off off?
                                    :title (dpane/title-text dp)})))
          (finally (debugger/set-debug-mode! false) (break-off! ui)))))))

(defn row-break-stop
  "Stop while paused ends the evaluation and the pause; the next entry
  runs without pausing on what came before."
  [ui]
  (go-row :break-stop support/check
    (if-not (debugger/available?)
      (on-gtk (support/check :break-stop true {:available false}))
      (let [dp (:debugger-pane ui)]
        (try
          (on-gtk (run-palette! ui "break on error"))
          (let [on? (await-until 3000 20 #(= (status ui) BREAK-ON))
                _ (enter! ui "(let [n 1] (/ n 0))")
                paused? (await-until 5000 20 #(str/starts-with? (dpane/title-text dp) "Paused:"))
                _ (on-gtk ((:stop!* ui)))
                stopped? (await-until 5000 20 #(and (support/idle? ui) (not (str/starts-with? (dpane/title-text dp) "Paused:"))))
                m (enter! ui "(+ 1 2)")
                next? (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m) "=> 3")))]
            (on-gtk (support/check :break-stop (and on? paused? stopped? next?)
                                   {:on on? :paused paused? :stopped stopped? :next next?
                                    :title (dpane/title-text dp)})))
          (finally (break-off! ui)))))))

(defn row-break-stop-blocked
  "Stop on an evaluation blocked in a sleep, with Break on error on, stops
  it: the InterruptedException the sleep throws is never paused on."
  [ui]
  (go-row :break-stop-blocked support/check
    (if-not (debugger/available?)
      (on-gtk (support/check :break-stop-blocked true {:available false}))
      (let [dp (:debugger-pane ui)]
        (try
          (on-gtk (run-palette! ui "break on error"))
          (let [on? (await-until 3000 20 #(= (status ui) BREAK-ON))
                m (enter! ui "(Thread/sleep 60000)")
                running? (await-until 3000 20 #(not (support/idle? ui)))
                _ (<! (timeout 300))
                _ (on-gtk ((:stop!* ui)))
                stopped? (await-until 5000 20 #(support/idle? ui))
                paused? (on-gtk (or (str/includes? (support/since ui m) "; Paused")
                                    (str/starts-with? (dpane/title-text dp) "Paused:")))]
            (on-gtk (support/check :break-stop-blocked (and on? running? stopped? (not paused?))
                                   {:on on? :running running? :stopped stopped? :paused paused?})))
          (finally (break-off! ui)))))))

(defn row-break-flag-ends-with-its-eval
  "Continue at a break in Inspect Form, which records no failure, then a
  later error with Break on error off: the Frames panel stays closed, since
  Continue's refresh belonged to the evaluation that paused."
  [ui]
  (go-row :break-flag-ends-with-its-eval support/check
    (if-not (debugger/available?)
      (on-gtk (support/check :break-flag-ends-with-its-eval true {:available false}))
      (let [dp (:debugger-pane ui)
            ed (:editor ui)]
        (try
          (on-gtk (run-palette! ui "break on error"))
          (let [on? (await-until 3000 20 #(= (status ui) BREAK-ON))
                _ (on-gtk (editor/set-text! ed "(/ 1 0)")
                          (support/place-cursor! (:buf ed) 2)
                          ((:inspect-form!* ui)))
                paused? (await-until 5000 20 #(str/starts-with? (dpane/title-text dp) "Paused:"))
                _ (on-gtk (dpane/press-restart! dp :continue))
                continued? (await-until 5000 20 #(support/idle? ui))
                _ (on-gtk (when-let [f (:on-close @(:handlers dp))] (f)))
                closed? (await-until 3000 20 #(zero? (g/gtk-widget-get-visible (:root dp))))
                _ (on-gtk (run-palette! ui "break on error"))
                off? (await-until 3000 20 #(= (status ui) BREAK-OFF))
                m (enter! ui "(/ 1 0)")
                errored? (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m) "Divide by zero")))
                _ (<! (timeout 500))
                shown? (on-gtk (pos? (g/gtk-widget-get-visible (:root dp))))]
            (on-gtk (support/check :break-flag-ends-with-its-eval
                                   (and on? paused? continued? closed? off? errored? (not shown?))
                                   {:on on? :paused paused? :continued continued? :closed closed?
                                    :off off? :errored errored? :panel-shown shown? :title (dpane/title-text dp)})))
          (finally (break-off! ui)))))))

(defn row-break-flag-survives-next-start
  "The evaluators go idle before on-complete, so the next evaluation can
  start before the paused one's failure is recorded. Driven through the
  window's own break handlers in that order: Continue's refresh still
  happens, and the next evaluation's end leaves it alone."
  [ui]
  (go-row :break-flag-survives-next-start support/check
    (let [dp (:debugger-pane ui)
          failure* (:failure* (:console ui))
          saved @failure*
          h (:break-handlers @(:state (:session ui)))
          a (Object.) b (Object.)]
      (try
        ((:on-eval-start h) a)
        ((:on-resume h) {:continued? true})
        ;; the next evaluation starts in the gap, before a's failure lands
        ((:on-eval-start h) b)
        (reset! failure* {:kind :repl :code "(/ 1 0)" :error-text "ArithmeticException: Divide by zero"})
        ((:on-eval-end h) a)
        (let [shown? (await-until 3000 20 #(pos? (g/gtk-widget-get-visible (:root dp))))]
          (on-gtk (when-let [f (:on-close @(:handlers dp))] (f)))
          ;; b's own Continue, then a's end arriving late: b's refresh stays
          ((:on-resume h) {:continued? true})
          ((:on-eval-end h) a)
          (reset! failure* {:kind :repl :code "(/ 2 0)" :error-text "ArithmeticException: Divide by zero"})
          (let [shown-b? (await-until 3000 20 #(pos? (g/gtk-widget-get-visible (:root dp))))]
            (on-gtk (support/check :break-flag-survives-next-start (and shown? shown-b?)
                                   {:shown shown? :shown-b shown-b?}))))
        (finally
          ((:on-eval-end h) b)
          (on-gtk (when-let [f (:on-close @(:handlers dp))] (f)))
          (reset! failure* saved))))))

(defn row-break-off-by-default
  "With Break on error off, an entry that throws errors at once."
  [ui]
  (go-row :break-off-by-default support/check
    (let [m (enter! ui "(/ 1 0)")
          errored? (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m) "Divide by zero")))
          paused? (on-gtk (str/includes? (support/since ui m) "; Paused"))]
      (on-gtk (support/check :break-off-by-default (and errored? (not paused?)) {:errored errored? :paused paused?})))))

;; --- breakpoints (LM5) ------------------------------------------------------------

(defn- active-ed [ui] (:ed ((:active-tab* ui))))

(defn- dots [ui] @(:dots (:gutter (:changed (active-ed ui)))))

(defn- caret-to-line!
  "The caret at the start of line n (1-based) of the active tab's text."
  [ui n]
  (let [ed (active-ed ui)
        text (editor/editor-text ed)
        at (loop [i 0 l 1] (if (= l n) i (recur (inc (str/index-of text "\n" i)) (inc l))))]
    (support/place-cursor! (:buf ed) at)))

(defn- breakpoints-off!
  "Never leaves a dot, a pause, the hook or debug mode behind for later
  rows. The active tab is emptied, or with close? closed (a file's tab)."
  ([ui] (breakpoints-off! ui false))
  ([ui close?]
   (break-off! ui)
   (on-gtk (let [ed (active-ed ui)]
             (doseq [m (dots ui)] (g/gtk-text-buffer-delete-mark (:buf ed) m))
             (reset! (:dots (:gutter (:changed ed))) [])
             (if close?
               ((:close-tab! ui) (:id ((:active-tab* ui))))
               (editor/set-text! ed ""))))
   (reset! debugger/breakpoint-hook nil)
   (debugger/set-debug-mode! false)))

(defn row-breakpoint-live-at-once
  "Toggle Breakpoint alone, no Run: the dot is drawn and the defn holding
  it is sent again, so calling the fn from the console pauses at that
  line. The Frames panel is titled with the line, shows the local, the
  field evaluates with it, and Continue runs on to the fn's value. Toggled
  off, the same call runs through."
  [ui]
  (go-row :breakpoint-live-at-once support/check
    (if-not (debugger/break-available?)
      (do (on-gtk (run-palette! ui "toggle a breakpoint"))
          (let [said? (await-until 3000 20 #(str/starts-with? (status ui) "; "))]
            (on-gtk (support/check :breakpoint-live-at-once said? {:available false :status (status ui)}))))
      (let [dp (:debugger-pane ui)]
        (try
          ;; a saved file's tab that never ran: a form with edits that
          ;; haven't run (unsaved, or changed since the last Run) isn't sent
          (let [f (java.io.File. ^String (temp-dirs/temp-dir! "jonquil-selftest-bp-") "bpw.clj")]
            (spit f "(defn bpw [x]\n  (let [y (* x 2)]\n    (+ y 1)))\n")
            (on-gtk ((:open-file! ui) (.getPath f))
                    (caret-to-line! ui 3)
                    (run-palette! ui "toggle a breakpoint")))
          (let [live? (await-until 8000 20 #(str/starts-with? (status ui) "; Breakpoint set at line 3; it's live now."))
                set-status (on-gtk (status ui))
                dot? (on-gtk (= 1 (count (dots ui))))
                m (enter! ui "(bpw 20)")
                paused? (await-until 5000 20 #(= "Breakpoint at line 3" (dpane/title-text dp)))
                said? (on-gtk (str/includes? (support/since ui m) "; Paused at the breakpoint on line 3."))
                rows (on-gtk (dpane/row-texts dp))
                local? (boolean (some #{"    y = 40"} rows))
                m2 (on-gtk (support/out-end-offset ui))
                _ (on-gtk (dpane/eval-text! dp "(* y 10)"))
                field? (await-until 3000 20 #(str/includes? (support/since ui m2) "=> 400"))
                _ (on-gtk (dpane/press-restart! dp :continue))
                ran-on? (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m2) "=> 41")))
                _ (on-gtk (caret-to-line! ui 3) (run-palette! ui "toggle a breakpoint"))
                off? (await-until 8000 20 #(= (status ui) "; Breakpoint removed; the form runs without it now."))
                gone? (on-gtk (empty? (dots ui)))
                m3 (enter! ui "(bpw 1)")
                through? (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m3) "=> 3")))
                not-paused? (on-gtk (not (str/includes? (support/since ui m3) "; Paused")))]
            (on-gtk (support/check :breakpoint-live-at-once
                                   (and live? dot? paused? said? local? field? ran-on? off? gone? through? not-paused?)
                                   {:live live? :set-status set-status :dot dot? :paused paused? :said said?
                                    :rows (take 4 rows) :local local? :field field? :ran-on ran-on? :off off?
                                    :gone gone? :through through? :not-paused not-paused?
                                    :status (status ui) :title (dpane/title-text dp)})))
          (finally (breakpoints-off! ui true)))))))

(defn row-breakpoint-placement
  "A toggle on a line that can't pause moves the dot to the next one that
  can; a form that does more than define code is never sent by a toggle
  (its side effect doesn't run again); Clear All Breakpoints takes every
  dot away."
  [ui]
  (go-row :breakpoint-placement support/check
    (if-not (debugger/break-available?)
      (on-gtk (support/check :breakpoint-placement true {:available false}))
      (try
        (let [code "(def bp-hits (atom 0))\n(def bpz (swap! bp-hits inc))\n(defn bpq [a]\n  (inc a))"
              [ran? _] (support/run-and-await ui code 5000 #(str/includes? % "#'user/bpq"))
              _ (on-gtk (caret-to-line! ui 2) (run-palette! ui "toggle a breakpoint"))
              unsent? (await-until 8000 20 #(str/starts-with? (status ui) "; Breakpoint set at line 2; it takes effect when you next run this form."))
              _ (on-gtk (caret-to-line! ui 3) (run-palette! ui "toggle a breakpoint"))
              moved? (await-until 8000 20 #(str/starts-with? (status ui) "; Breakpoint moved to line 4; it's live now."))
              two? (on-gtk (= 2 (count (dots ui))))
              hits @@(resolve 'user/bp-hits)
              _ (on-gtk (run-palette! ui "clear all breakpoints"))
              cleared? (await-until 8000 20 #(= (status ui) "; Cleared 2 breakpoints; the forms run without them now."))
              none? (on-gtk (empty? (dots ui)))
              hits2 @@(resolve 'user/bp-hits)]
          (on-gtk (support/check :breakpoint-placement
                                 (and ran? unsent? moved? two? (= 1 hits) cleared? none? (= 1 hits2))
                                 {:ran ran? :unsent unsent? :moved moved? :two two? :hits hits :cleared cleared?
                                  :none none? :hits2 hits2 :status (status ui)})))
        (finally (breakpoints-off! ui))))))

(defn row-breakpoint-waits-for-unrun-edits
  "A toggle in a form edited since it ran doesn't send it, so the
  unfinished edit doesn't run: the status bar says to Run Form, and the
  function still does what it did."
  [ui]
  (go-row :breakpoint-waits-for-unrun-edits support/check
    (if-not (debugger/break-available?)
      (on-gtk (support/check :breakpoint-waits-for-unrun-edits true {:available false}))
      (try
        (let [[ran? _] (support/run-and-await ui "(defn bpe [a]\n  (inc a))" 5000 #(str/includes? % "#'user/bpe"))
              _ (on-gtk (editor/set-text! (:editor ui) "(defn bpe [a]\n  (dec a))")
                        (caret-to-line! ui 2)
                        (run-palette! ui "toggle a breakpoint"))
              said? (await-until 8000 20 #(str/starts-with? (status ui) "; Breakpoint set; this form has edits that haven't run, so Run Form to apply both."))
              dot? (on-gtk (= 1 (count (dots ui))))
              same? (= 2 ((resolve 'user/bpe) 1))]
          (on-gtk (support/check :breakpoint-waits-for-unrun-edits (and ran? said? dot? same?)
                                 {:ran ran? :said said? :dot dot? :same same? :status (status ui)})))
        (finally (breakpoints-off! ui))))))

(defn row-breakpoint-asks-again-after-a-run
  "Before the file's namespace is loaded, a name it defines can't be told
  from a macro, so a line inside a call to it holds no breakpoint and the
  status bar says why. A Run asks the runtime again (a stale answer gets
  replaced), and then the same toggle sets the dot."
  [ui]
  (go-row :breakpoint-asks-again-after-a-run support/check
    (if-not (debugger/break-available?)
      (on-gtk (support/check :breakpoint-asks-again-after-a-run true {:available false}))
      (try
        (let [f (java.io.File. ^String (temp-dirs/temp-dir! "jonquil-selftest-bpns-") "r.clj")
              _ (spit f "(ns bpns.r)\n(defn frob [x] x)\n(defn h [x]\n  (frob\n    (inc x)))\n")
              _ (on-gtk ((:open-file! ui) (.getPath f))
                        (caret-to-line! ui 5)
                        (run-palette! ui "toggle a breakpoint"))
              refused? (await-until 8000 20 #(and (str/starts-with? (status ui) "; No code from this line on can hold a breakpoint.")
                                                  (str/includes? (status ui) "namespace isn't loaded yet")))
              none? (on-gtk (empty? (dots ui)))
              ;; a stale answer: the Run must replace it
              _ (on-gtk (reset! (:bp-heads (:changed (active-ed ui)))
                                {:ns-form "(ns bpns.r)" :ns-found? true :heads {"frob" {:var "bpns.r/frob" :macro true}}}))
              _ (on-gtk ((:run!* ui)))
              asked? (await-until 8000 20 #(= {:var "bpns.r/frob"}
                                              (get-in @(:bp-heads (:changed (active-ed ui))) [:heads "frob"])))
              _ (on-gtk (caret-to-line! ui 5) (run-palette! ui "toggle a breakpoint"))
              set? (await-until 8000 20 #(str/starts-with? (status ui) "; Breakpoint set at line 5; it's live now."))]
          (on-gtk (support/check :breakpoint-asks-again-after-a-run (and refused? none? asked? set?)
                                 {:refused refused? :none none? :asked asked? :set set? :status (status ui)
                                  :heads @(:bp-heads (:changed (active-ed ui)))})))
        (finally (breakpoints-off! ui true))))))

;; --- stepping (LM5) ----------------------------------------------------------------

(defn- arrow-line
  "The 1-based line of the active tab's gutter arrow, or nil."
  [ui]
  (let [ed (active-ed ui)
        gt (:gutter (:changed ed))]
    (when-let [m @(:arrow gt)]
      (let [text (editor/editor-text ed)]
        (inc (count (filter #{\newline} (subs text 0 (g/mark-offset (:buf ed) m)))))))))

(defn row-step-follows-the-lines
  "A dot on line 2 of a three-line defn (and one in its caller, so the
  caller is instrumented too), call the caller: it pauses at line 2. Step
  twice: the Frames title and the gutter's arrow follow line 3, then the
  caller's next line. Continue runs to the end and the arrow goes."
  [ui]
  (go-row :step-follows-the-lines support/check
    (if-not (debugger/break-available?)
      (on-gtk (support/check :step-follows-the-lines true {:available false}))
      (let [dp (:debugger-pane ui)]
        (try
          (let [f (java.io.File. ^String (temp-dirs/temp-dir! "jonquil-selftest-step-") "steps.clj")
                _ (spit f "(defn sa [x]\n  (let [y (inc x)]\n    (* y 2)))\n(defn sb [x]\n  (let [r (sa x)]\n    (+ r 1)))\n")
                m0 (on-gtk (let [m (support/out-end-offset ui)] ((:open-file! ui) (.getPath f)) ((:run!* ui)) m))
                ran? (await-until 8000 20 #(and (support/idle? ui) (str/includes? (support/since ui m0) "#'user/sb")))
                _ (on-gtk (caret-to-line! ui 2) (run-palette! ui "toggle a breakpoint"))
                dot2? (await-until 8000 20 #(str/starts-with? (status ui) "; Breakpoint set at line 2; it's live now."))
                _ (on-gtk (caret-to-line! ui 6) (run-palette! ui "toggle a breakpoint"))
                dot6? (await-until 8000 20 #(str/starts-with? (status ui) "; Breakpoint set at line 6; it's live now."))
                m (enter! ui "(sb 1)")
                at2? (await-until 5000 20 #(and (= "Breakpoint at line 2" (dpane/title-text dp)) (= 2 (arrow-line ui))))
                labels (on-gtk (dpane/restart-labels dp))
                _ (on-gtk (run-palette! ui "step to the next line"))
                at3? (await-until 5000 20 #(and (= "Stepped to line 3" (dpane/title-text dp)) (= 3 (arrow-line ui))))
                _ (on-gtk (dpane/press-restart! dp :step))
                at6? (await-until 5000 20 #(and (= "Breakpoint at line 6" (dpane/title-text dp)) (= 6 (arrow-line ui))))
                stepped? (on-gtk (str/includes? (support/since ui m) "; Stepped to line 3."))
                _ (on-gtk (dpane/press-restart! dp :continue))
                done? (await-until 5000 20 #(and (support/idle? ui) (str/includes? (support/since ui m) "=> 5")))
                no-arrow? (await-until 3000 20 #(nil? (arrow-line ui)))]
            (on-gtk (support/check :step-follows-the-lines
                                   (and ran? dot2? dot6? at2? (= ["Continue" "Step"] labels) at3? at6? stepped? done? no-arrow?)
                                   {:ran ran? :dot2 dot2? :dot6 dot6? :at2 at2? :labels labels :at3 at3? :at6 at6?
                                    :stepped stepped? :done done? :no-arrow no-arrow?
                                    :title (dpane/title-text dp) :arrow (arrow-line ui) :status (status ui)})))
          (finally (breakpoints-off! ui true)))))))
