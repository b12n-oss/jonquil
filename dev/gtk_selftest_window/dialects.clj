(ns gtk-selftest-window.dialects
  "D1.T4 rows: the dialect label, the in-process engine behind the
  Evaluator seam, and .jonquil.edn warnings when a file opens."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [gtk-selftest-window.support :as support]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.ffi :as g]))

(defn- temp-project!
  "A project under the temp dir: deps.edn, .jonquil.edn holding config
  (none when nil) and src/demo/a.clj. The file's canonical path."
  [config]
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str "jonquil-selftest-dialect-" (System/nanoTime)))
        f (io/file d "src/demo/a.clj")]
    (.mkdirs (.getParentFile f))
    (spit (io/file d "deps.edn") "{:paths [\"src\"]}")
    (when config (spit (io/file d ".jonquil.edn") config))
    (spit f "(+ 1 2)")
    (.getCanonicalPath f)))

(defn- open-then-run
  "Opens a fresh project's file, then Runs (+ 1 2). opts is {:row :config
  :expect} (:row the check keyword, :config .jonquil.edn text or nil,
  :expect the substring warned must hold, or nil). Passes when the run
  printed => 3, the label still reads Jolt, the evaluator is the same
  one, and the console since the open holds expect (a string) or, for
  nil, no \"; project:\" line at all. ui and next stay positional (the
  same [ui next] shape every row-fn in this whole self-test suite uses,
  ui first since it's the structural thing every helper acts on, next
  last since it's the continuation), but :row/:config/:expect -- the
  actual per-call configuration -- go in an explicit map, the same shape
  jonquil.gtk.completion/make-completion's own opts uses, rather than
  three more positional slots a caller has to count.

  Deviation from the brief's verbatim code (reported; see the report):
  cview/append! (window.clj's show-selection!, called synchronously from
  open-path!'s add-watch) never writes the buffer itself -- it queues and
  schedules a drain via on-gui!, which always defers through g_idle_add,
  even when already called from the GTK thread (measured: jonquil.gtk.
  callbacks/on-gui!'s own doc). Reading `since` right after (:open-path!
  ui) returns -- the brief's own ordering -- races that drain and reads
  the console before the warning (if any) has actually landed, so `warned`
  is read here, inside run-and-wait!'s own :done callback, after the run
  has gone idle -- by then the main loop has ticked many times, and every
  other row's own on-gui!-deferred output (the run's banner, its own
  \"=> 3\") is already proven to land reliably by that same point."
  [ui {:keys [row config expect]} next]
  (let [file (temp-project! config)
        mark (support/out-end-offset ui)
        before ((:evaluator* ui))]
    ((:open-path! ui) file)
    (support/run-and-wait!
     ui "(+ 1 2)" 5000 (fn [t] (str/includes? t "=> 3"))
     (fn [ran? _]
       (let [warned (support/since ui mark)
             label (g/label-text (:dialect-label ui))
             ok (and ran? (= "Jolt" label)
                     (identical? before ((:evaluator* ui)))
                     (if expect
                       (str/includes? warned expect)
                       (not (str/includes? warned "; project:"))))]
         (support/check row ok {:warned warned :label label :ran? ran?})
         (next))))))

(defn row-dialect-label-default [ui next]
  (support/check :dialect-label-default (= "Jolt" (g/label-text (:dialect-label ui))) {})
  (next))

(defn row-evaluator-is-the-session [ui next]
  (let [ev-inst ((:evaluator* ui))
        sel ((:selection* ui))]
    (support/check :evaluator-is-the-session
                   (and (identical? ev-inst (:session ui))
                        (satisfies? ev/Evaluator ev-inst)
                        (= :in-process (:runtime sel)))
                   {:runtime (:runtime sel)}))
  (next))

(defn row-project-config-malformed-warns [ui next]
  (open-then-run ui {:row :project-config-malformed-warns
                     :config "{:runtime"
                     :expect "; project: .jonquil.edn isn't valid EDN, so it is ignored"}
                 next))

(defn row-project-config-unknown-runtime-warns [ui next]
  (open-then-run ui {:row :project-config-unknown-runtime-warns
                     :config "{:runtime :bogus}"
                     :expect "; project: .jonquil.edn asks for runtime :bogus, which Jolt doesn't offer here; using in-process"}
                 next))

(defn row-project-open-keeps-evaluator [ui next]
  (open-then-run ui {:row :project-open-keeps-evaluator :config nil :expect nil} next))
