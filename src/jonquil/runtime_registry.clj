(ns jonquil.runtime-registry
  "Every evaluator jonquil.gtk.window has made in this process, in one
  GTK-free registry, so jonquil.main can stop all of them and wait for any
  still-starting one to finish before System/exit -- no window is left
  holding a child once the process itself is about to end.

  window.clj adds an evaluator (jonquil.evaluator/make's result) as soon
  as it makes one, and asks to remove it once ev/stop! has run on it,
  whether that's a real window close (close-request-fn) or an evaluator
  swap (D2.T5, opening a file from another runtime, via apply-selection!).
  unregister! only honours that once the evaluator has actually settled
  (fix-wave C1): ev/stop! (jonquil.nrepl.evaluator) can return while a
  start is still in flight, and until that start's own teardown finishes,
  this registry is the only thing that still knows the evaluator exists.

  Kept separate from jonquil.gtk.window (a GTK-free namespace) so the live
  nREPL tests below can drive it directly, the same way
  jonquil.nrepl.evaluator-test drives jonquil.nrepl.evaluator without any
  GTK involved."
  (:require [jonquil.evaluator :as ev]
            [jonquil.nrepl.child :as child]
            [jonquil.nrepl.evaluator :as nrepl]
            [jonquil.nrepl.jank :as jank]))

(defonce ^:private live (atom #{}))

(defn register!
  "Adds ev to the registry. Called once per jonquil.evaluator/make."
  [ev]
  (swap! live conj ev)
  nil)

(defn- settled?
  "True for any evaluator whose stop! can't still be mid-teardown: an
  nREPL evaluator only once nrepl/settled? says so, a jank evaluator once
  jank/settled? says so (no launch in flight), anything else (no child to
  wait for) at once."
  [ev]
  (cond
    (instance? jonquil.nrepl.evaluator.NRepl ev) (nrepl/settled? ev)
    (instance? jonquil.nrepl.jank.Jank ev) (jank/settled? ev)
    :else true))

(defn unregister!
  "Removes ev from the registry -- but only once it has settled (C1, the
  fix-wave's phase-close review): called once ev/stop! has run on it,
  whether that's a real window close (close-request-fn) or an evaluator
  swap (apply-selection!), but ev/stop! itself never blocks on a start
  already in flight, so at that point ev may still be tearing itself
  down. While that's true, this is a no-op and ev stays registered, so
  stop-all! still stops and waits on it before jonquil.main's System/exit
  runs. Safe to call on an ev that was never registered, or twice on the
  same ev."
  [ev]
  (when (settled? ev)
    (swap! live disj ev))
  nil)

(defn- wait-until-settled!
  "Polls settled? every 20ms until it's true or deadline-ms passes."
  [ev deadline-ms]
  (let [t0 (System/currentTimeMillis)]
    (loop []
      (cond
        (settled? ev) true
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
  A jank evaluator is waited on the same way while a launch is in flight
  (bounded by jank/settle-deadline-ms). A plain Evaluator (the in-process
  engine) has no child to wait for, so only nREPL and jank evaluators are
  waited on here; stop! itself already handles
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
      (cond
        (instance? jonquil.nrepl.evaluator.NRepl e)
        (wait-until-settled! e (+ child/ready-deadline-ms child/term-grace-ms))
        (instance? jonquil.nrepl.jank.Jank e)
        (wait-until-settled! e jank/settle-deadline-ms)))
    nil))
