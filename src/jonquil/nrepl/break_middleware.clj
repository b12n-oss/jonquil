(ns jonquil.nrepl.break-middleware
  "nREPL middleware for an out-of-process Jolt child: Break on error
  (jonquil.debugger's break) for the session's own evaluations.

  Like jonquil.nrepl.stdin-middleware, nothing in Jonquil requires this
  namespace: jonquil.nrepl.middleware-files embeds it, and the child loads it
  first in :nrepl/middleware, outside jolt-lang/nrepl's session middleware.
  That middleware runs each eval on the session's worker, which it starts
  with bound-fn during clone, so *session-break*, bound here around the
  clone, is that worker's (stdin's *in* works the same way).

  jolt.nrepl/evaluate catches every throw inside its own try, so the break
  sits inside it: install-wrapper! wraps jolt.host/eval-string (an eval) and
  clojure.core/load-string (a load-file), both reached through their vars
  (measured 2026-11-02, AOT-loaded and in debug mode). Only an eval the
  client sent with \"jonquil-break\" \"1\" arms its session, and a call
  nested inside a wrapped one (the program's own load-string) passes
  straight through. The pause and its end go to the client on the eval's
  own reply, as \"jonquil-paused\" (frames-data, printed) and
  \"jonquil-resumed\" (\"continued\" or \"stopped\"), with no status."
  (:require [clojure.java.io :as io]
            [jolt.nrepl :as server]
            [jonquil.debugger :as debugger]))

(def ^:dynamic *session-break*
  "The session's break entry (entry), bound around clone so the session's
  worker has it; nil elsewhere."
  nil)

(def ^:dynamic ^:private *inside*
  ;; true on a thread already running a wrapped eval-string or load-string
  false)

(defn entry
  "One session's break state: the break, whether its current eval may
  pause, and that eval's reply."
  []
  {:break (debugger/make-break) :armed? (atom false) :reply (atom nil)})

(defn resource-url
  "A frame's classpath name as the URL this child loads it from, so the
  window can open it; a path, a URL or a name the classpath lacks stays."
  [f]
  (if (re-find #"^(jar:|file:|/)" f) f (or (some-> (io/resource f) str) f)))

(defn- send! [e m]
  (when-let [r @(:reply e)]
    (try (r m) (catch Throwable _ nil))))

(defn wrap
  "f (eval-string or load-string) under the session's break, when this
  thread's session is armed and isn't already inside a wrapped call."
  [f]
  (fn [code]
    (let [e *session-break*]
      (if (and e @(:armed? e) (not *inside*))
        (binding [*inside* true]
          ((debugger/break-wrapper
            (:break e)
            {:on-pause (fn [d]
                         (send! e {"jonquil-paused" (binding [*print-length* nil *print-level* nil] (pr-str d))}))
             :on-resume (fn [{:keys [continued?]}]
                          (send! e {"jonquil-resumed" (if continued? "continued" "stopped")}))
             :resolve-file resource-url})
           (fn [] (f code))))
        (f code)))))

(defonce ^:private installed (atom false))

(defn install-wrapper!
  "Wraps eval-string and load-string once per process."
  []
  (when (compare-and-set! installed false true)
    ;; looked up when called: jolt.host/eval-string doesn't resolve where
    ;; this namespace is compiled
    (some-> (resolve 'jolt.host/eval-string) (alter-var-root wrap))
    (alter-var-root #'clojure.core/load-string wrap))
  nil)

(defn- as-long [x] (if (string? x) (parse-long x) x))

(defn handler
  "The middleware's request handler over sessions (an atom, session id ->
  entry), handing everything but its own ops on to next."
  [sessions next]
  (fn [request]
    (let [sid (get request "session")
          e (get @sessions sid)]
      (case (get request "op")
        "clone"
        (let [e (entry)]
          (binding [*session-break* e]
            (next (assoc request :reply (fn [m]
                                          (when-let [new-sid (get m "new-session")]
                                            (swap! sessions assoc new-sid e))
                                          ((:reply request) m))))))
        ("eval" "load-file")
        (do (when e
              (reset! (:armed? e) (= "1" (str (get request "jonquil-break"))))
              (reset! (:reply e) (:reply request)))
            (next request))
        "jonquil-break-check"
        (server/respond request {"status" ["done"] "available" (str (debugger/break-available?))})
        "jonquil-break-eval"
        ;; answered from the paused thread, as jonquil-frame-eval is answered
        (if e
          (debugger/break-eval! (:break e) (as-long (get request "frame")) (str (get request "code"))
                                (fn [r]
                                  (server/respond request (if-let [err (:error r)]
                                                            {"status" ["done"] "error" err}
                                                            {"status" ["done"] "frame-out" (:out r) "text" (:text r)}))))
          (server/respond request {"status" ["done"] "error" "Not paused any more."}))
        "jonquil-break-continue"
        (server/respond request {"status" ["done"]
                                 "continued" (str (boolean (and e (debugger/continue! (:break e)))))})
        "interrupt"
        (do (when e (debugger/break-stop! (:break e)))
            (next request))
        "close"
        (do (swap! sessions dissoc sid)
            (next request))
        (next request)))))

(defonce ^:private sessions (atom {}))

(defn paused-local-for
  "The value of local-name in session sid's paused error's frame i, or
  :jonquil.debugger/missing."
  [sid error-id i local-name]
  (if-let [e (get @sessions sid)]
    (debugger/paused-local (:break e) error-id i local-name)
    ::debugger/missing))

(defn break
  "The middleware: Break on error for every session cloned through it."
  [next]
  (install-wrapper!)
  (server/register-ops! "jonquil-break-check" "jonquil-break-eval" "jonquil-break-continue")
  (handler sessions next))
