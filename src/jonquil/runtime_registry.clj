(ns jonquil.runtime-registry
  "Every evaluator jonquil.gtk.window has made in this process, in one
  GTK-free registry, so jonquil.main can stop all of them and wait for any
  still-starting one to finish before System/exit -- no window is left
  holding a child once the process itself is about to end.

  window.clj adds an evaluator (jonquil.evaluator/make's result) as soon
  as it makes one, and removes it once ev/stop! has run on it, whether
  that's a real window close (close-request-fn) or an evaluator swap
  (D2.T5, opening a file from another runtime, via apply-selection!).

  Kept separate from jonquil.gtk.window (a GTK-free namespace) so the live
  nREPL tests below can drive it directly, the same way
  jonquil.nrepl.evaluator-test drives jonquil.nrepl.evaluator without any
  GTK involved."
  (:require [jonquil.evaluator :as ev]
            [jonquil.nrepl.child :as child]
            [jonquil.nrepl.evaluator :as nrepl]))

(defonce ^:private live (atom #{}))

(defn register!
  "Adds ev to the registry. Called once per jonquil.evaluator/make."
  [ev]
  (swap! live conj ev)
  nil)

(defn unregister!
  "Removes ev from the registry. Called once ev/stop! has run on it."
  [ev]
  (swap! live disj ev)
  nil)

(defn- wait-until-settled!
  "Polls nrepl/settled? every 20ms until it's true or deadline-ms passes."
  [ev deadline-ms]
  (let [t0 (System/currentTimeMillis)]
    (loop []
      (cond
        (nrepl/settled? ev) true
        (> (- (System/currentTimeMillis) t0) deadline-ms) false
        :else (do (Thread/sleep 20) (recur))))))

(defn stop-all!
  "Synchronously stops every registered evaluator, then waits, bounded,
  for any nREPL evaluator whose start was still in flight when stopped to
  finish tearing itself down. ev/stop! (jonquil.nrepl.evaluator) never
  blocks on a start already in flight -- it sets the evaluator's own
  terminal flag and returns at once, leaving the still-running
  start-runtime! to notice it and tear down what it just started. Without
  this wait, jonquil.main's System/exit right after this returns could cut
  that teardown off mid-flight and orphan the child it was about to kill.
  A plain Evaluator (the in-process engine) has no child to wait for, so
  only nREPL evaluators are waited on here; stop! itself already handles
  every evaluator kind.

  Each evaluator's wait is bounded by child/ready-deadline-ms (a start's
  own ceiling) plus child/term-grace-ms (the teardown's own ceiling), so
  it can't hang forever on a truly wedged child; it still returns once
  that time is up either way."
  []
  (let [evs @live]
    (doseq [e evs]
      (try (ev/stop! e) (catch Throwable _ nil)))
    (doseq [e evs]
      (when (instance? jonquil.nrepl.evaluator.NRepl e)
        (wait-until-settled! e (+ child/ready-deadline-ms child/term-grace-ms))))
    nil))
