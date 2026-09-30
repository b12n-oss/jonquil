(ns gtk-selftest-cljd.rows
  "Rows for the ClojureDart window: a real window on a throwaway app
  directory whose NREPL.lock names a fake fork server running in this
  process (dev/fake_cljd_nrepl.clj). Nothing here reads or connects to a
  real app's NREPL.lock."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [fake-cljd-nrepl :as fake]
            [gtk-async :refer [await-until go-row on-gtk]]
            [gtk-selftest-window.support :as support]
            [jonquil.cljd :as cljd]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.editor :as editor]
            [jonquil.gtk.ffi :as g]
            [jonquil.gtk.help-popover :as help-popover]
            [jonquil.inspect :as inspect]
            [jonquil.nrepl.attach :as attach]))

;; Deviation (measured against a throwaway read, not run live): the
;; comment form's closing paren sits on its own line, never on (slow)'s
;; own line -- run-selection-text sends the caret's WHOLE line with no
;; selection, and "  (slow))" would hand the fake server an extra,
;; unmatched close-paren it can't read, failing row-cljd-stop-detaches
;; before Stop ever runs.
(def fixture-drive
  "(ns demo.drive)\n\n(defn where [] 1)\n\n(comment\n  (where)\n  (slow)\n  )\n")

(def ^:private where-answer "{:slide 21, :of 54}")

(def ^:private where-info
  ;; cljd plan P4.T1: lookup's found shape (fork 685e56de: nREPL's stock
  ;; info, string keys, no file or line).
  {"ns" "demo.drive" "name" "where" "doc" "Where the deck is now." "arglists" "([])" "arglists-str" "([])"})

;; The fixture app and its fake server, stashed so rows and
;; after-close-checks! (which runs after main/start! returns) reach them.
(defonce ^:private fixture* (atom nil))

(defn- temp-app-dir []
  (io/file (System/getProperty "java.io.tmpdir")
           (str "jonquil-selftest-cljd-" (System/currentTimeMillis) "-" (random-uuid))))

(defn fixture!
  "Starts the fake server and writes an app directory for it: pubspec.yaml,
  NREPL.lock holding the fake's port, and dev/demo/drive.cljd. {:dir :file
  :server :lock-text}, paths canonical."
  []
  (let [srv (fake/start! {:answers {"(where)" where-answer} :slow {"(slow)" 5000} :docs {"where" where-info}})
        d (temp-app-dir)
        f (io/file d "dev/demo/drive.cljd")
        lock-text (str (:port srv) "\n")]
    (.mkdirs (.getParentFile f))
    (spit (io/file d "pubspec.yaml") "name: demo\n")
    (spit (io/file d "NREPL.lock") lock-text)
    (spit f fixture-drive)
    (reset! fixture* {:dir (.getCanonicalPath d) :file (.getCanonicalPath f) :server srv :lock-text lock-text})))

(defn evaluator [ui] ((:evaluator* ui)))

(defn idle? [ui] (not (ev/evaluating? (evaluator ui))))

(defn- srv [] (:server @fixture*))

(defn run-and-await
  "support/run-and-await's contract, idle read through the evaluator."
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

(defn row-cljd-starts-not-running
  "The window opened drive.cljd: an attach evaluator, not connected, the
  label says so, Reset on Run is unticked and off with its tooltip, the console says
  it's experimental and lists the limits, and nothing has talked to the
  server yet."
  [ui]
  (go-row :cljd-starts-not-running support/check
    (let [said? (await-until 2000 20 #(str/includes? (support/since ui 0) "; Limit: Stop detaches"))]
      (on-gtk (let [t (support/since ui 0)
                    chk (:reset-check ui)]
                (support/check :cljd-starts-not-running
                               (and said?
                                    (instance? jonquil.nrepl.attach.Attach (evaluator ui))
                                    (= "ClojureDart (not running)" (label ui))
                                    (zero? (g/gtk-widget-get-sensitive chk))
                                    (zero? (g/gtk-check-button-get-active chk))
                                    (= "Hot reload can't unload a namespace, so every Run adds to the running app."
                                       (g/tooltip-text chk))
                                    (str/includes? t cljd/experimental-message)
                                    (not (attach/connected? (evaluator ui)))
                                    (empty? (fake/ops-seen (srv))))
                               {:label (label ui) :sensitive (g/gtk-widget-get-sensitive chk)
                                :active (g/gtk-check-button-get-active chk)
                                :tooltip (g/tooltip-text chk) :ops (fake/ops-seen (srv))}))))))

(defn row-cljd-load-prints-loaded
  "Run is load-file: one summary line, the banner once, and the label
  turns to attached."
  [ui]
  (go-row :cljd-load-prints-loaded support/check
    (let [[ok? mark] (run-and-await ui fixture-drive 10000 #(str/includes? % "; Loaded demo.drive (2 forms)"))
          attached? (await-until 2000 20 #(= "ClojureDart (attached)" (label ui)))
          req (last (fake/requests (srv)))]
      (on-gtk (let [t (support/since ui mark)]
                (support/check :cljd-load-prints-loaded
                               (and ok? attached?
                                    (str/includes? t "; Connecting to the app on port ")
                                    (str/includes? t "ClojureDart\n")
                                    ;; no value line; the live prompt
                                    ;; (demo.drive=> ) also holds "=> "
                                    (not-any? #(str/starts-with? % "=> ") (str/split-lines t))
                                    (= ["load-file" (:file @fixture*)] [(get req "op") (get req "file-path")])
                                    (= 'demo.drive (ev/prompt-ns (evaluator ui))))
                               {:ok? ok? :attached? attached? :label (label ui) :text t :req req}))))))

(defn row-cljd-run-selection-in-buffer-ns
  "Cmd/Ctrl+E on (where) in the comment block: the session is already in
  demo.drive after the load, so only the selection is sent."
  [ui]
  (go-row :cljd-run-selection-in-buffer-ns support/check
    (let [n (count (fake/requests (srv)))
          [ok? mark] (run-selection-and-await ui fixture-drive "(where)" 5000 #(str/includes? % (str "=> " where-answer)))
          ;; Deviation (measured against a throwaway read, not run live):
          ;; run-selection-text sends the caret's WHOLE line with no
          ;; selection, so the wire carries "  (where)" (the fixture's own
          ;; indentation), not the bare form -- str/trim each captured
          ;; request before comparing, since the point here is that only
          ;; (where) was sent, not its indentation.
          sent (mapv (fn [m] (str/trim (get m "code"))) (drop n (fake/requests (srv))))]
      (on-gtk (support/check :cljd-run-selection-in-buffer-ns
                             (and ok? (= ["(where)"] sent))
                             {:ok? ok? :sent sent :text (support/since ui mark)})))))

(defn row-cljd-run-form-in-comment
  "cljd plan P3.T2: Cmd/Ctrl+Enter's fn on a multi-line form inside a
  comment block sends exactly that form, in the buffer's namespace (the
  session is already there, so nothing else is sent)."
  [ui]
  (go-row :cljd-run-form-in-comment support/check
    (let [text (str fixture-drive "\n(comment\n  (let [w (where)]\n    w))\n")
          n (count (fake/requests (srv)))
          mark (on-gtk (let [m (support/out-end-offset ui)
                             ed (:editor ui)]
                         (editor/set-text! ed text)
                         (support/place-cursor! (:buf ed) (str/index-of text "w))"))
                         ((:run-form!* ui))
                         m))
          ok? (await-until 5000 20 #(and (idle? ui) (str/includes? (support/since ui mark) "=> :ok")))
          sent (mapv (fn [m] (get m "code")) (drop n (fake/requests (srv))))]
      (on-gtk (support/check :cljd-run-form-in-comment
                             (and ok? (= ["(let [w (where)]\n    w)"] sent))
                             {:ok? ok? :sent sent :text (support/since ui mark)})))))

(defn row-cljd-quick-doc-from-app
  "cljd plan P4.T1: attached, quick doc on where shows the app's own doc,
  from lookup in the buffer's namespace."
  [ui]
  (go-row :cljd-quick-doc-from-app support/check
    (on-gtk (let [ed (:editor ui)]
              (editor/set-text! ed fixture-drive)
              (support/place-cursor! (:buf ed) (inc (str/index-of fixture-drive "(where)")))
              ((:quick-doc!* ui))
              (let [text (help-popover/quick-doc-text (:help-popover ui))
                    req (last (fake/requests (srv)))]
                (support/check :cljd-quick-doc-from-app
                               (and (str/includes? text "demo.drive/where") (str/includes? text "Where the deck is now.")
                                    (= ["lookup" "where" "demo.drive"] [(get req "op") (get req "sym") (get req "ns")]))
                               {:text text :req req}))))))

(defn row-cljd-completion-offline
  "cljd plan P4.T1: completion is offline. \"(wher\" commits to the
  buffer's own where, and nothing was sent to the app for it."
  [ui]
  (go-row :cljd-completion-offline support/check
    (on-gtk (let [ed (:editor ui)
                  n (count (fake/requests (srv)))
                  text (str fixture-drive "(wher")]
              (editor/set-text! ed text)
              (support/place-cursor! (:buf ed) (count text))
              ((:complete!* ui))
              (let [after (editor/editor-text ed)
                    sent (drop n (fake/requests (srv)))]
                (support/check :cljd-completion-offline
                               (and (str/ends-with? after "(where") (empty? sent))
                               {:tail (subs after (max 0 (- (count after) 12))) :sent sent}))))))

(defn row-cljd-stop-detaches
  "Stop during a slow form detaches: the console says so, the label goes
  back to not running, and the server never got interrupt or close."
  [ui]
  (go-row :cljd-stop-detaches support/check
    (let [mark (on-gtk (let [m (support/out-end-offset ui)
                             ed (:editor ui)]
                         (editor/set-text! ed fixture-drive)
                         (support/place-cursor! (:buf ed) (str/index-of fixture-drive "(slow)"))
                         ((:run-selection!* ui))
                         m))
          started? (await-until 2000 20 #(not (idle? ui)))
          _ (on-gtk ((:stop!* ui)))
          stopped? (await-until 2000 20 #(and (idle? ui)
                                              (str/includes? (support/since ui mark) "; Detached from the app.")
                                              (str/includes? (support/since ui mark) "; interrupted")))
          not-running? (await-until 2000 20 #(= "ClojureDart (not running)" (label ui)))
          ops (fake/ops-seen (srv))]
      (on-gtk (support/check :cljd-stop-detaches
                             (and started? stopped? not-running? (not-any? #{"interrupt" "close"} ops))
                             {:started started? :stopped stopped? :label (label ui) :ops ops
                              :text (support/since ui mark)})))))

(defn row-cljd-quick-doc-detached
  "cljd plan P4.T1: detached (after the Stop above), quick doc says so and
  doesn't connect."
  [ui]
  (go-row :cljd-quick-doc-detached support/check
    (on-gtk (let [ed (:editor ui)
                  n (count (fake/requests (srv)))]
              (editor/set-text! ed fixture-drive)
              (support/place-cursor! (:buf ed) (inc (str/index-of fixture-drive "(where)")))
              ((:quick-doc!* ui))
              (let [text (help-popover/quick-doc-text (:help-popover ui))]
                (support/check :cljd-quick-doc-detached
                               (and (str/includes? text cljd/not-attached-doc)
                                    (= n (count (fake/requests (srv))))
                                    (not (attach/connected? (evaluator ui))))
                               {:text text}))))))

(defn row-cljd-reconnects-after-detach
  "The next Run Selection reconnects with a fresh clone and answers."
  [ui]
  (go-row :cljd-reconnects-after-detach support/check
    (let [[ok? mark] (run-selection-and-await ui fixture-drive "(where)" 10000 #(str/includes? % (str "=> " where-answer)))
          attached? (await-until 2000 20 #(= "ClojureDart (attached)" (label ui)))
          clones (count (filter #{"clone"} (fake/ops-seen (srv))))]
      (on-gtk (support/check :cljd-reconnects-after-detach
                             (and ok? attached? (= 2 clones)
                                  (str/includes? (support/since ui mark) "; Connecting to the app on port "))
                             {:ok? ok? :clones clones :text (support/since ui mark)})))))

(defn row-cljd-idle-stop-detaches
  "Fix round 2, finding B: Stop pressed while connected but idle (nothing
  running) also detaches and prints the same detached line a running
  Stop's on-output prints -- exactly once -- and the label goes back to
  not running."
  [ui]
  (go-row :cljd-idle-stop-detaches support/check
    (let [attached? (await-until 2000 20 #(= "ClojureDart (attached)" (label ui)))
          mark (on-gtk (support/out-end-offset ui))
          _ (on-gtk ((:stop!* ui)))
          not-running? (await-until 2000 20 #(= "ClojureDart (not running)" (label ui)))
          text (on-gtk (support/since ui mark))
          detached-count (count (re-seq #"; Detached from the app\." text))]
      (on-gtk (support/check :cljd-idle-stop-detaches
                             (and attached? not-running? (= 1 detached-count))
                             {:attached? attached? :not-running? not-running?
                              :count detached-count :text text})))))

(defn row-cljd-not-running-message
  "Opening a file from another app with no NREPL.lock switches the
  evaluator; Run then says the app isn't running, in the console's :system
  style (never the error style), and nothing is written there."
  [ui]
  (go-row :cljd-not-running-message support/check
    (let [d (temp-app-dir)
          f (io/file d "lib/main.cljd")
          _ (on-gtk (.mkdirs (.getParentFile f))
                    (spit (io/file d "pubspec.yaml") "name: other\n")
                    (spit f "(ns other.main)\n")
                    ((:open-path! ui) (.getCanonicalPath f)))
          [ok? mark] (run-and-await ui "(ns other.main)\n" 5000 #(str/includes? % cljd/not-running-message))]
      (on-gtk (let [cv (:console ui)
                    t (support/since ui mark)
                    at (+ mark (or (str/index-of t cljd/not-running-message) 0))
                    system? (g/has-tag-at? (:buf cv) at (get (:tags cv) :system))
                    error? (g/has-tag-at? (:buf cv) at (get (:tags cv) :error))
                    wrote? (.exists (io/file d "NREPL.lock"))]
                (doseq [x (reverse (file-seq d))] (.delete ^java.io.File x))
                (support/check :cljd-not-running-message
                               (and ok? system? (not error?) (not wrote?)
                                    (= "ClojureDart (not running)" (label ui)))
                               {:ok? ok? :system system? :error error? :wrote wrote? :text t}))))))

(defn row-cljd-inspect-unavailable
  "cljd's attach evaluator never extends jonquil.evaluator/Inspector at
  all (jonquil.nrepl.attach.Attach has no such protocol impl), so ruling
  6's availability check in inspect-last! fails before building any
  request: the status bar gets the unavailable sentence and the pane's
  root stays hidden."
  [ui]
  (go-row :cljd-inspect-unavailable support/check
    (let [pane (:inspector ui)]
      (on-gtk ((:inspect-last!* ui)))
      (let [shown? (await-until 2000 20
                                #(= inspect/unavailable-message (g/label-text (:status-label ui))))]
        (on-gtk
         (support/check :cljd-inspect-unavailable
                        (and shown? (zero? (g/gtk-widget-get-visible (:root pane))))
                        {:shown shown? :status (g/label-text (:status-label ui))
                         :visible (g/gtk-widget-get-visible (:root pane))}))))))

(defn row-cljd-close
  "The deliberately LAST row: fires the app's quit action, the path a Quit
  AppleEvent takes. after-close-checks! records the result once the GTK
  loop is gone."
  [ui]
  (go-row :cljd-close support/check
    (on-gtk (editor/set-text! (:editor ui) "")
            ((:activate-quit-action!* ui)))))

(defn after-close-checks!
  "Called by -main once main/start! returns. The server never got
  interrupt or close, from Stop or from the window closing, and the
  fixture's NREPL.lock is byte for byte what fixture! wrote, with no
  REPL.lock beside it. Then stops the fake server and deletes the app."
  []
  (let [{:keys [dir server lock-text]} @fixture*
        lock (io/file dir "NREPL.lock")
        ops (fake/ops-seen server)]
    (support/check :cljd-never-interrupt-or-close (not-any? #{"interrupt" "close"} ops) {:ops ops})
    (support/check :cljd-lock-untouched
                   (and (.exists lock) (= lock-text (slurp lock)) (not (.exists (io/file dir "REPL.lock"))))
                   {:lock (when (.exists lock) (slurp lock))})
    (fake/stop! server)
    (doseq [x (reverse (file-seq (io/file dir)))] (.delete ^java.io.File x))))
