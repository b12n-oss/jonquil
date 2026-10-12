(ns gtk-selftest-window.dialects
  "D1.T4 rows: the dialect label, the in-process engine behind the
  Evaluator protocol, and .jonquil.edn warnings when a file opens."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [gtk-async :refer [await-until on-gtk go-row]]
            [gtk-selftest-window.support :as support]
            [jonquil.dialect :as dialect]
            [jonquil.evaluator :as ev]
            [jonquil.gtk.editor :as editor]
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

(defn row-editor-paints-profile-sets
  "P1.T2 (cljd plan): with the cljd profile, the editor paints f/widget
  with the special-form tag; back on the window's own profile (Jolt's) it
  paints plain. The row restores the window's profile before it records,
  so later rows paint as before.

  Deviation from the brief's verbatim code (reported; see the report):
  the brief names the has-tag-at? check `special?`, a let-bound fn passed
  to await-until by symbol and reused negated. audit_gtk_calls.clj only
  exempts a GTK-touching call sitting INSIDE on-gtk or literally as
  await-until's own third argument -- a fn bound earlier and passed by
  name is walked at its OWN definition site (the let), where it isn't
  exempt, and flags the g/has-tag-at? call inside it. Inlining the same
  check as await-until's literal third argument, once plain and once
  negated, keeps it exempt without changing what the row does."
  [ui]
  (go-row :editor-paints-profile-sets support/check
    (let [ed (:editor ui)]
      (on-gtk (editor/set-profile! ed dialect/cljd)
              (editor/set-text! ed "(f/widget 1)"))
      (let [cljd? (await-until 2000 20 (fn [] (g/has-tag-at? (:buf ed) 1 (get (:tags ed) :special-form))))
            _ (on-gtk (editor/set-profile! ed (:profile ((:selection* ui)))))
            plain? (await-until 2000 20 (fn [] (not (g/has-tag-at? (:buf ed) 1 (get (:tags ed) :special-form)))))]
        (support/check :editor-paints-profile-sets (and cljd? plain?) {:cljd cljd? :plain plain?})))))

(defn row-selection-throw-still-paints-new-profile
  "Fix round 1 (Important): window.clj's add-watch ::dialect callback used
  to call editor/set-profile! only after apply-selection! returned, in
  the SAME try -- a throw anywhere inside apply-selection!, once past its
  own (reset! sel* sel), skipped set-profile! outright and left the
  editor painting whatever profile it held before the open, even though
  @sel* had already moved to the new selection. The fix moved
  set-profile! into a finally, so it runs from @sel* whether or not
  apply-selection! threw.

  This row forces dialect/label to throw for one open: show-selection!
  (apply-selection!'s own second statement, right after reset! sel*
  sel) calls it first, so the throw fires at the earliest point
  apply-selection! can fail -- the exact shape the finding describes.

  Measured, not g/gtk-label-set-text (the more obvious place to throw,
  show-selection!'s OWN next call): editor/set-text! -- open-path!'s
  first step, before it ever resets the path atom that ::dialect
  watches -- fires the SAME editor's on-cursor-moved! listeners, and
  window.clj's own listener calls g/gtk-label-set-text on the line/col
  label. Redefining that var throws there first, inside open-path!'s
  OWN try, before the watch ever fires at all -- caught by open-path!'s
  \"; open:\" catch, never reaching apply-selection! or our fix.
  dialect/label has no such second caller in this path: show-selection!,
  dialect-text and start-and-report!'s status text are the only
  callers, and none of those three run before the watch fires.

  Seeding the editor with the cljd profile first (a profile the temp
  project's own Jolt selection can never produce on its own) means only
  a real editor/set-profile! call after the throw can put it back to
  Jolt's; before the fix, it would have stayed cljd."
  [ui]
  (go-row :selection-throw-still-paints-new-profile support/check
    (let [ed (:editor ui)
          [file dir] (temp-project! nil)]
      (try
        (let [mark (on-gtk (editor/set-profile! ed dialect/cljd)
                           (let [m (support/out-end-offset ui)]
                             (with-redefs [dialect/label
                                           (fn [& _] (throw (ex-info "forced dialect label failure" {})))]
                               ((:open-path! ui) file))
                             m))
              warned? (await-until 1000 20 #(str/includes? (support/since ui mark) "; project: forced dialect label failure"))]
          (on-gtk (let [warned (support/since ui mark)
                        profile @(:profile ed)
                        sel-profile (:profile ((:selection* ui)))]
                    (support/check :selection-throw-still-paints-new-profile
                                   (and warned? (identical? dialect/jolt profile) (identical? profile sel-profile))
                                   {:warned warned :profile (:id profile) :sel-profile (:id sel-profile)}))))
        (finally
          (on-gtk (delete-tree! dir)))))))
