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
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.help-popover :as help-popover]
            [jonquil.gtk.inspector-pane :as inspector-pane]
            [jonquil.gtk.popover :as popover]))

;; This file's own private copies (gdkkeysyms.h, GTK 4.22.4 installed
;; headers), same convention as every sibling gtk-selftest-*.rows key
;; table -- jonquil.gtk.inspector-pane's own KEY-RETURN/KEY-LEFT are
;; private to that namespace.
(def ^:private KEY-RETURN 0xff0d)
(def ^:private KEY-LEFT 0xff51)
(def ^:private KEY-M 0x06d)

(def fixture-core-text
  ;; The spike fixture's exact text (its own gitignored planning-store
  ;; file): demo.core with greet, a private secret returning :private-ok,
  ;; and nested. Embedded rather than read at run time: the planning
  ;; store is gitignored scratch, so this harness can't depend on it. The
  ;; same text test/jonquil/nrepl/jvm_test.clj's own fixture-core-text
  ;; embeds.
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

;; P3.T2 step 5: the fake's own :full-docs answer for greet, referenced by
;; row-clj-full-doc below -- the same text test/jonquil/nrepl/jvm_test.clj's
;; own full-doc-greet-text embeds.
(def full-doc-greet-text
  "demo.core/greet\n([who])\nSays hello.\n\n== examples")

(defn- temp-project-dir []
  (io/file (System/getProperty "java.io.tmpdir")
           (str "jonquil-selftest-clj-" (System/currentTimeMillis) "-" (random-uuid))))

(defn fixture!
  "Starts the fake server and writes a JVM project directory for it:
  deps.edn, .jonquil.edn {:dialect :clj}, src/demo/core.clj (the spike
  fixture's text), and .nrepl-port holding the fake's port. {:dir :file
  :server :lock-text}, paths canonical.

  P3.T2: the project directory and its src/demo/core.clj are written
  BEFORE the fake starts, so greet's own :docs entry can point :file at
  this SAME file's real canonical path (mirroring jvm_test.clj's own
  lookup-test comment on the same ordering need); map's own :docs entry
  is a fixed jar path, ruling 13's own jar-shortening shape."
  []
  (let [d (temp-project-dir)
        f (io/file d "src/demo/core.clj")]
    (.mkdirs (.getParentFile f))
    (spit (io/file d "deps.edn") "{}\n")
    (spit (io/file d ".jonquil.edn") "{:dialect :clj}\n")
    (spit f fixture-core-text)
    (let [srv (fake/start!
               {:slow {"(slow)" 5000}
                :completions {"gre" [["greet" "demo.core"]]}
                :docs {"greet" {"ns" "demo.core" "name" "greet" "doc" "Says hello."
                                "arglists-str" "([who])" "file" (.getCanonicalPath f) "line" 4}
                       "map" {"ns" "clojure.core" "name" "map"
                              "doc" "Returns a lazy sequence of the results of applying f."
                              "arglists-str" "([f] [f coll] [f c1 c2] [f c1 c2 c3] [f c1 c2 c3 & colls])"
                              "file" "jar:file:/x/clojure-1.12.5.jar!/clojure/core.clj" "line" 2748}}
                :full-docs {"greet" full-doc-greet-text}
                ;; P3.T2: row-clj-inspect-in-out's own REPL entry, a
                ;; single-key map so :in always lands on idx 0 with no
                ;; ambiguity. row-clj-inspect-lost-connection's own delay:
                ;; every inspect-* op waits this long before answering, so
                ;; dropping connections right after an in-step's own
                ;; request is logged (fake/ops-seen) still finds it
                ;; genuinely pending -- review focus 5's own path.
                :answers {"(pick)" "{:k 42}"}
                :inspect-ms 300})
          lock-text (str (:port srv) "\n")]
      (spit (io/file d ".nrepl-port") lock-text)
      (reset! fixture* {:dir (.getCanonicalPath d) :file (.getCanonicalPath f) :server srv :lock-text lock-text}))))

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
  running yet, Reset on Run is unticked and off with its own tooltip (:clj is the
  user's own JVM), and nothing has talked to the server yet."
  [ui]
  (go-row :clj-starts-not-running support/check
    (on-gtk (let [chk (:reset-check ui)]
              (support/check :clj-starts-not-running
                             (and (= "Clojure (JVM) (not running)" (label ui))
                                  (zero? (g/gtk-widget-get-sensitive chk))
                                  (zero? (g/gtk-check-button-get-active chk))
                                  (= (:reset-tooltip dialect/clj) (g/tooltip-text chk))
                                  (empty? (fake/ops-seen (srv))))
                             {:label (label ui) :sensitive (g/gtk-widget-get-sensitive chk)
                              :active (g/gtk-check-button-get-active chk)
                              :tooltip (g/tooltip-text chk) :ops (fake/ops-seen (srv))})))))

(defn row-clj-inspect-before-run
  "I1 scenario A: Cmd/Ctrl+I before any Run. inspectable? is true (:ops
  is still empty, ruling 5), so the panel opens and :start's own reply
  is {:error clj/inspect-not-attached} -- a fresh, nil-state :error
  reply (jonquil.inspect/open) must show that sentence in the footer,
  not throw computing position text from a view with no :start."
  [ui]
  (go-row :clj-inspect-before-run support/check
    (let [pane (:inspector ui)]
      (on-gtk ((:inspect-last!* ui)))
      (let [shown? (await-until 2000 20
                                #(str/includes? (g/label-text (:footer pane)) clj/inspect-not-attached))]
        (on-gtk
         (support/check :clj-inspect-before-run
                        (and (pos? (g/gtk-widget-get-visible (:root pane))) shown?)
                        {:shown shown? :footer (g/label-text (:footer pane))
                         :visible (g/gtk-widget-get-visible (:root pane))}))))))

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

(defn row-clj-reconnect-resets-prompt
  "A new connection is a fresh session, so the prompt goes back to user=> .
  Run moves the console's prompt to demo.core=> , the server drops, and
  the next REPL entry reconnects: it evaluates in user and the prompt
  reads user=> , not the last Run's namespace."
  [ui]
  (go-row :clj-reconnect-resets-prompt support/check
    (let [tail #(support/since ui (support/out-end-offset ui))
          [ran? _] (run-and-await ui fixture-core-text 10000 #(str/includes? % "=> #'demo.core/nested"))
          moved? (await-until 2000 20 #(= "demo.core=> " (tail)))
          _ (fake/drop-connections! (srv))
          not-running? (await-until 2000 20 #(= "Clojure (JVM) (not running)" (label ui)))
          cv (:console ui)
          n (count (fake/requests (srv)))
          mark (on-gtk (let [m (support/out-end-offset ui)]
                         (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(+ 1 2)" -1 1)
                         (cview/handle-return! cv false)
                         m))
          answered? (await-until 5000 20 #(and (idle? ui) (str/includes? (support/since ui mark) "=> 3")))
          reset? (await-until 2000 20 #(= "user=> " (tail)))
          sent-ns (keep #(get % "ns") (filter #(= "eval" (get % "op")) (drop n (fake/requests (srv)))))]
      (on-gtk (support/check :clj-reconnect-resets-prompt
                             (and ran? moved? not-running? answered? reset? (= ["user"] sent-ns))
                             {:ran ran? :moved moved? :not-running not-running? :answered answered?
                              :reset reset? :sent-ns sent-ns :tail (tail)})))))

;; --- P3.T2 rows: quick doc, jump, and full doc on a second press -----------

(defn row-clj-quick-doc
  "Ctrl+Q on greet in (greet \"x\"), with the evaluator already attached
  (an earlier row's own Run): ev/lookup answers from the fake's own :docs
  table, and the quick-doc popover shows demo.core/greet's own short doc."
  [ui]
  (go-row :clj-quick-doc support/check
    (on-gtk (let [ed (:editor ui) hp (:help-popover ui)]
              (editor/set-text! ed "(greet \"x\")")
              (support/place-cursor! (:buf ed) 2)
              ((:quick-doc!* ui))
              (let [text (help-popover/quick-doc-text hp)]
                (support/check :clj-quick-doc
                               (and (popover/open? hp)
                                    (str/includes? text "demo.core/greet")
                                    (str/includes? text "Says hello."))
                               {:open (popover/open? hp) :text text})
                (popover/hide! hp))))))

(defn row-clj-jump-external
  "F12 on map in (map inc [1]): jump-target finds no definition in the
  buffer, so it falls to the :external case and shows the SAME quick-doc
  popover -- ev/lookup's own Defined in line (ruling 13, P3.T1) ends with
  the fake's own jar path and line."
  [ui]
  (go-row :clj-jump-external support/check
    (on-gtk (let [ed (:editor ui) hp (:help-popover ui)]
              (editor/set-text! ed "(map inc [1])")
              (support/place-cursor! (:buf ed) 2)
              ((:jump!* ui))
              (let [text (help-popover/quick-doc-text hp)]
                (support/check :clj-jump-external
                               (and (popover/open? hp)
                                    (str/ends-with? text "Defined in clojure-1.12.5.jar!/clojure/core.clj:2748"))
                               {:open (popover/open? hp) :text text})
                (popover/hide! hp))))))

(defn row-clj-full-doc
  "Quick doc twice on greet (ruling 11): the second press is a repeat on
  the SAME target while the popover is still open, so it asks the fake's
  own doc op instead of re-showing the same text. Within 2000 ms
  full-doc-text holds the fake's own :full-docs answer, the quick-doc
  popover has closed, and the status no longer says Loading."
  [ui]
  (go-row :clj-full-doc support/check
    (let [hp (:help-popover ui) fp (:full-doc-popover ui)]
      (on-gtk (let [ed (:editor ui)]
                (editor/set-text! ed "(greet \"x\")")
                (support/place-cursor! (:buf ed) 2)
                ((:quick-doc!* ui))
                ((:quick-doc!* ui))))
      (let [ok (await-until 2000 20
                            #(and (= full-doc-greet-text (help-popover/full-doc-text fp))
                                  (not (popover/open? hp))
                                  (not (str/includes? (g/label-text (:status-label ui)) "Loading"))))]
        (on-gtk (support/check :clj-full-doc ok
                               {:ok ok :text (help-popover/full-doc-text fp)
                                :hp-open (popover/open? hp)
                                :status (g/label-text (:status-label ui))})
                (popover/hide! fp))))))

(defn row-clj-full-doc-escape
  "With the full-doc popover open (the same double quick-doc press as
  row-clj-full-doc), Escape closes it -- window.clj's own popover
  registry (:close-popovers!*), the same path P4.T3's own escape row
  uses."
  [ui]
  (go-row :clj-full-doc-escape support/check
    (let [fp (:full-doc-popover ui)]
      (on-gtk (let [ed (:editor ui)]
                (editor/set-text! ed "(greet \"x\")")
                (support/place-cursor! (:buf ed) 2)
                ((:quick-doc!* ui))
                ((:quick-doc!* ui))))
      (let [opened? (await-until 2000 20 #(popover/open? fp))]
        (on-gtk (let [handled ((:handle-window-key! ui) nil support/KEY-ESCAPE 0 0 nil)]
                  (support/check :clj-full-doc-escape
                                 (and opened? (= 1 handled) (not (popover/open? fp)))
                                 {:opened opened? :handled handled :open (popover/open? fp)})))))))

;; --- P3.T2 rows: the inspector over the JVM attach evaluator ---------------

(defn row-clj-inspect-after-run
  "Run on the fixture, then :inspect-last!* (review focus 1): within
  3000 ms the panel shows the Run's own value. load-file answers only
  the LAST form's own value, the def's own printed var string
  \"#'demo.core/nested\" -- MEASURED (jolt run, not recalled):
  edn/read-string can't parse a #' literal (\"No dispatch macro for:
  '\"), so fake-jvm-nrepl's own read-printed-value keeps it as the
  plain STRING, and jonquil.inspect.view/start's own datafy is a no-op
  on a string -- entries answers nil for it (no map/set/sequential/
  List), so this renders as a LEAF, :info \"String\" (type-name's own
  class name), never \"Var\": the brief's own guess didn't hold, this
  is what's actually measured."
  [ui]
  (go-row :clj-inspect-after-run support/check
    (let [pane (:inspector ui)
          [ok? mark] (run-and-await ui fixture-core-text 10000 #(str/includes? % "=> #'demo.core/nested"))]
      (on-gtk ((:inspect-last!* ui)))
      (let [opened? (await-until 3000 20
                                 #(and (pos? (g/gtk-widget-get-visible (:root pane)))
                                       (= "*1" (g/label-text (:header pane)))))]
        (on-gtk
         (let [header (g/label-text (:header pane))
               info (g/label-text (:info pane))
               text (g/buffer-text (:buf pane))]
           (support/check :clj-inspect-after-run
                          (and ok? opened? (= "*1" header) (= "String" info)
                               (= "\"#'demo.core/nested\"" text))
                          {:ok ok? :opened opened? :header header :info info :text text
                           :console (support/since ui mark)})))))))

(defn row-clj-inspect-in-out
  "A REPL entry the fake answers as a map (fixture!'s own :answers,
  \"(pick)\" -> \"{:k 42}\"): open (a single-entry map, so :in's own idx
  0 is unambiguous -- the row \":k · 42\"), in lands on the leaf 42, out
  comes back to *1 with the row reselected."
  [ui]
  (go-row :clj-inspect-in-out support/check
    (let [cv (:console ui) pane (:inspector ui)
          mark (on-gtk (let [m (support/out-end-offset ui)]
                         (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(pick)" -1 1)
                         (cview/handle-return! cv false)
                         m))
          submitted? (await-until 3000 20
                                  #(and (idle? ui) (str/includes? (support/since ui mark) "=> {:k 42}")))]
      (on-gtk ((:inspect-last!* ui)))
      (let [opened? (await-until 3000 20
                                 #(and (= "*1" (g/label-text (:header pane)))
                                       (str/includes? (g/label-text (:info pane)) "1 items")))
            h-in (on-gtk (inspector-pane/dispatch-key! pane KEY-RETURN))
            in? (await-until 2000 20 #(str/includes? (g/label-text (:header pane)) "/ :k"))
            h-out (on-gtk (inspector-pane/dispatch-key! pane KEY-LEFT))
            out? (await-until 2000 20 #(= "*1" (g/label-text (:header pane))))]
        (on-gtk
         (let [tag0? (g/has-tag-at? (:buf pane) 0 (:selected-tag pane))]
           (support/check :clj-inspect-in-out
                          (and submitted? opened? (= 1 h-in) in? (= 1 h-out) out? tag0?)
                          {:submitted submitted? :opened opened? :h-in h-in :in in?
                           :h-out h-out :out out? :tag0 tag0? :text (support/since ui mark)})))))))

(defn row-clj-inspect-lost-connection
  "Review focus 5: the server dies while the panel is open. Continues
  from row-clj-inspect-in-out's own pane (back on *1, still the
  one-entry {:k 42} view): an in-step (Return) is sent, confirmed on
  the wire (fake/ops-seen sees \"inspect-in\" -- fixture!'s own
  :inspect-ms 300 leaves it genuinely pending), then the server drops
  every connection while that reply is still owed -- jonquil.nrepl.
  client's own open? flips only once ITS reader thread notices the
  close, so the request already went out and the reply itself reports
  the drop (P3.T1 inspect-lost-connection-test's own path). Within
  3000 ms the footer names jonquil.clj/lost-connection and the toolbar
  buttons go insensitive."
  [ui]
  (go-row :clj-inspect-lost-connection support/check
    (let [pane (:inspector ui)
          n (count (fake/ops-seen (srv)))
          h (on-gtk (inspector-pane/dispatch-key! pane KEY-RETURN))
          sent? (await-until 2000 20 #(some #{"inspect-in"} (drop n (fake/ops-seen (srv)))))
          _ (fake/drop-connections! (srv))
          shown? (await-until 3000 20 #(str/includes? (g/label-text (:footer pane)) clj/lost-connection))]
      (on-gtk
       (let [footer (g/label-text (:footer pane))
             sensitive (mapv g/gtk-widget-get-sensitive
                             [(:back-btn pane) (:meta-btn pane) (:table-btn pane) (:def-btn pane)])]
         (support/check :clj-inspect-lost-connection
                        (and (= 1 h) sent? shown? (every? zero? sensitive))
                        {:h h :sent sent? :shown shown? :footer footer :sensitive sensitive}))))))

(defn row-clj-inspect-lost-idle
  "I2: the server dies while the panel is IDLE (nothing in flight),
  unlike row-clj-inspect-lost-connection above, which drops mid round-
  trip. Picks up from that row's own dead connection: a plain REPL
  entry reconnects (mirroring row-clj-reconnects-after-restart), a
  fresh :inspect-last!* opens a live, enabled view, then the connection
  drops again with nothing pending. The next key must answer
  jonquil.clj/lost-connection, never clj/inspect-not-attached (jvm.clj's
  inspect! distinguishes a never-attached :conn, nil, from one that was
  open and dropped, non-nil but closed)."
  [ui]
  (go-row :clj-inspect-lost-idle support/check
    (let [cv (:console ui)
          pane (:inspector ui)
          mark (on-gtk (let [m (support/out-end-offset ui)]
                         (g/gtk-text-buffer-insert-interactive-at-cursor (:buf cv) "(+ 1 2)" -1 1)
                         (cview/handle-return! cv false)
                         m))
          reconnected? (await-until 5000 20
                                    #(and (idle? ui) (str/includes? (support/since ui mark) "=> 3")))]
      (on-gtk ((:inspect-last!* ui)))
      (let [opened? (await-until 2000 20 #(pos? (g/gtk-widget-get-visible (:root pane))))
            _ (fake/drop-connections! (srv))
            not-running? (await-until 2000 20 #(= "Clojure (JVM) (not running)" (label ui)))
            h (on-gtk (inspector-pane/dispatch-key! pane KEY-M))
            shown? (await-until 2000 20 #(str/includes? (g/label-text (:footer pane)) clj/lost-connection))]
        (on-gtk
         (let [footer (g/label-text (:footer pane))]
           (support/check :clj-inspect-lost-idle
                          (and reconnected? opened? not-running? (= 1 h) shown?
                               (not (str/includes? footer clj/inspect-not-attached)))
                          {:reconnected reconnected? :opened opened? :not-running not-running?
                           :h h :shown shown? :footer footer})))))))

(defn row-clj-reset-tick-round-trip
  "Reset on Run's tick through a switch to Jolt and back. The window
  starts attached, so the box is unticked and off. Opening a plain Jolt
  file puts the default tick back up and turns it on. Unticked there, it
  stays unticked through the JVM file and back again. Ticked, the JVM file
  takes it down once more. Every open is synchronous (the path watch runs
  apply-selection! on the GTK thread), so one on-gtk reads each state. The
  row ends back on the JVM file, which row-clj-close and after-close-checks!
  expect: that evaluator never connects, so the fake still sees no close."
  [ui]
  (go-row :clj-reset-tick-round-trip support/check
    (let [d (temp-project-dir)
          plain (io/file d "src/demo/plain.clj")]
      (.mkdirs (.getParentFile plain))
      (spit (io/file d "deps.edn") "{}\n")
      (spit plain "(+ 1 2)\n")
      (try
        (on-gtk
         (let [chk (:reset-check ui)
               clj-file (:file @fixture*)
               plain-file (.getCanonicalPath plain)
               state (fn [] [(g/gtk-check-button-get-active chk) (g/gtk-widget-get-sensitive chk)])
               start (state)
               _ ((:open-path! ui) plain-file)
               jolt1 (state)
               _ (g/gtk-check-button-set-active chk 0)
               _ ((:open-path! ui) clj-file)
               clj2 (state)
               _ ((:open-path! ui) plain-file)
               jolt2 (state)
               _ (g/gtk-check-button-set-active chk 1)
               _ ((:open-path! ui) clj-file)
               end (state)
               seen [start jolt1 clj2 jolt2 end]]
           (support/check :clj-reset-tick-round-trip
                          (= [[0 0] [1 1] [0 0] [0 1] [0 0]] seen)
                          {:seen seen :label (label ui)})))
        (finally
          (on-gtk (doseq [f (reverse (file-seq d))] (.delete ^java.io.File f))))))))

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
