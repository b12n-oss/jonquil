(ns gtk-selftest-window.dialects
  "D1.T4 rows: the dialect label, the in-process engine behind the
  Evaluator seam, and .jonquil.edn warnings when a file opens."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [gtk-async :refer [on-gtk go-row]]
            [gtk-selftest-window.support :as support]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.ffi :as g]))

(defn- temp-project!
  "A project under the temp dir: deps.edn, .jonquil.edn holding config
  (none when nil -- any leftover .jonquil.edn already at this path is
  removed too, belt and braces alongside the name below being unique)
  and src/demo/a.clj. [canonical-file-path canonical-dir-path].

  I2: nanoTime counts from process start (Jolt), measured at about 97 to
  99 ms on a fresh `jolt -e` run, and the dirs were never deleted -- about
  785 sit in $TMPDIR from before this fix. So two rows in the same run
  could collide on the same dir name, and when :config was nil, nothing
  removed a stale .jonquil.edn a collision left behind. currentTimeMillis
  plus a random-uuid can't collide, and the row deletes its own dir when
  it's done (below)."
  [config]
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str "jonquil-selftest-dialect-" (System/currentTimeMillis) "-" (random-uuid)))
        f (io/file d "src/demo/a.clj")
        config-file (io/file d ".jonquil.edn")]
    (.mkdirs (.getParentFile f))
    (spit (io/file d "deps.edn") "{:paths [\"src\"]}")
    (if config
      (spit config-file config)
      (io/delete-file config-file true))
    (spit f "(+ 1 2)")
    [(.getCanonicalPath f) (.getCanonicalPath d)]))

(defn- delete-tree!
  "Deletes dir and everything under it, deepest files first. dir is
  temp-project!'s own unique dir, so this never touches another row's or
  the user's files."
  [dir]
  (let [f (io/file dir)]
    (when (.exists f)
      (doseq [child (reverse (file-seq f))]
        (.delete child)))))

(defn- open-then-run
  "Opens a fresh project's file, then Runs (+ 1 2). opts is {:row :config
  :expect} (:row the check keyword, :config .jonquil.edn text or nil,
  :expect the substring warned must hold, or nil). Passes when the run
  printed => 3, the label still reads Jolt, the evaluator is the same
  one, and the console since the open holds expect (a string) or, for
  nil, no \"; project:\" line at all.

  Deviation from the brief's verbatim code (reported; see the report):
  cview/append! (window.clj's show-selection!, called synchronously from
  open-path!'s add-watch) never writes the buffer itself -- it queues and
  schedules a drain via on-gui!, which always defers through g_idle_add,
  even when already called from the GTK thread (measured: jonquil.gtk.
  callbacks/on-gui!'s own doc). Reading `since` right after (:open-path!
  ui) returns -- the brief's own ordering -- races that drain and reads
  the console before the warning (if any) has actually landed, so `warned`
  is read here, inside run-and-await's own second on-gtk step, after the
  run has gone idle -- by then the main loop has ticked many times, and
  every other row's own on-gui!-deferred output (the run's banner, its
  own \"=> 3\") is already proven to land reliably by that same point.

  I2: deletes the row's own temp dir in a finally, once the run above has
  gone idle (so the evaluator has let go of the file), through on-gtk --
  the same GTK-thread discipline as the rest of the row, since the
  deleted deps.edn/.jonquil.edn/source file sit next to state open-path!'s
  own on-gui!-deferred watch callbacks may still read."
  [ui {:keys [row config expect]}]
  (go-row row support/check
    (let [[file dir] (temp-project! config)]
      (try
        (let [[mark before] (on-gtk (let [m (support/out-end-offset ui)
                                          before ((:evaluator* ui))]
                                      ((:open-path! ui) file)
                                      [m before]))
              [ran? _] (support/run-and-await ui "(+ 1 2)" 5000 #(str/includes? % "=> 3"))]
          (on-gtk (let [warned (support/since ui mark)
                        label (g/label-text (:dialect-label ui))
                        ok (and ran? (= "Jolt" label)
                                (identical? before ((:evaluator* ui)))
                                (if expect
                                  (str/includes? warned expect)
                                  (not (str/includes? warned "; project:"))))]
                    (support/check row ok {:warned warned :label label :ran? ran?}))))
        (finally
          (on-gtk (delete-tree! dir)))))))

(defn row-dialect-label-default [ui]
  (go-row :dialect-label-default support/check
    (on-gtk (support/check :dialect-label-default (= "Jolt" (g/label-text (:dialect-label ui))) {}))))

(defn row-evaluator-is-the-session [ui]
  (go-row :evaluator-is-the-session support/check
    (on-gtk (let [ev-inst ((:evaluator* ui))
                  sel ((:selection* ui))]
              (support/check :evaluator-is-the-session
                             (and (identical? ev-inst (:session ui))
                                  (satisfies? ev/Evaluator ev-inst)
                                  (= :in-process (:runtime sel)))
                             {:runtime (:runtime sel)})))))

(defn row-project-config-malformed-warns [ui]
  (open-then-run ui {:row :project-config-malformed-warns
                     :config "{:runtime"
                     :expect "; project: .jonquil.edn isn't valid EDN, so it is ignored"}))

(defn row-project-config-unknown-runtime-warns [ui]
  (open-then-run ui {:row :project-config-unknown-runtime-warns
                     :config "{:runtime :bogus}"
                     :expect "; project: .jonquil.edn asks for runtime :bogus, which Jolt doesn't offer here; using in-process"}))

(defn row-project-open-keeps-evaluator [ui]
  (open-then-run ui {:row :project-open-keeps-evaluator :config nil :expect nil}))
