(ns jonquil.nrepl.break-middleware
  "nREPL middleware for an out-of-process Jolt child: Break on error
  (jonquil.debugger's break) for the session's own evaluations.

  Like jonquil.nrepl.stdin-middleware, nothing in Jonquil requires this
  namespace: jonquil.nrepl.middleware-files embeds it, and the child loads it
  first in :nrepl/middleware, outside jolt-lang/nrepl's session middleware.
  That middleware runs each eval on the session's worker, which it starts
  with bound-fn during clone, so jolt.nrepl/*eval-wrapper*, bound here
  around the clone, is that worker's (stdin's *in* works the same way).

  jolt.nrepl/evaluate catches every throw inside its own try, and calls the
  wrapper inside it with the evaluation's thunk (an eval or a load-file), so
  the break sees the throw first. A Jolt without the hook (before
  jolt-lang/jolt#1474) can't pause, and the check says so. Only an eval the
  client sent with \"jonquil-break\" \"1\" arms its session; the program's
  own eval-string or load-string isn't wrapped. The client sends one eval
  at a time per session, so the arming set when an eval arrives is the one
  that runs (and it ends with that eval). The pause and its end go to the client on the eval's
  own reply, as \"jonquil-paused\" (frames-data, printed) and
  \"jonquil-resumed\" (\"continued\" or \"stopped\"), with no status.

  Breakpoints (LM5) pause through the same messages. The jonquil-breakpoints
  op arms or disarms jonquil.debugger/breakpoint-hook with the session's own
  hook, and an eval sent with \"jonquil-breakpoints\" \"1\" arms it too (a
  restarted child) and lets a breakpoint pause while that eval runs."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [jolt.nrepl :as server]
            [jonquil.debugger :as debugger]))

(defn hook-var
  "jolt.nrepl/*eval-wrapper*, or nil on a Jolt without it. Looked up when
  called: it doesn't resolve on such a Jolt where this namespace compiles."
  []
  (resolve 'jolt.nrepl/*eval-wrapper*))

(defn hooked?
  "True when this Jolt's nREPL evaluate takes a wrapper around each eval."
  []
  (some? (hook-var)))

(defn resource-url
  "A frame's classpath name as the URL this child loads it from, so the
  window can open it; a path, a URL or a name the classpath lacks stays."
  [f]
  (if (re-find #"^(jar:|file:|/)" f) f (or (some-> (io/resource f) str) f)))

(defn- send! [e m]
  (when-let [r @(:reply e)]
    (try (r m) (catch Throwable _ nil))))

(defn- break-callbacks
  "The debugger hooks for session e: pause and resume notify the client
  through e's reply, and frames resolve through the classpath."
  [e]
  {:on-pause (fn [d]
               (send! e {"jonquil-paused" (binding [*print-length* nil *print-level* nil] (pr-str d))}))
   :on-resume (fn [{:keys [continued?]}]
                (send! e {"jonquil-resumed" (if continued? "continued" "stopped")}))
   :resolve-file resource-url})

(defn entry
  "One session's break state: the break, whether its current eval may
  pause on an error (:armed?) or at a breakpoint (:bp-ok?), that eval's
  reply, and the session's breakpoint hook (LM5). A breakpoint pauses only
  while an eval the client marked runs, since the pause can only reach the
  client on that eval's reply. :stepping is whether a step point pauses,
  from a Step to the next pause."
  []
  (let [e {:break (debugger/make-break) :armed? (atom false) :reply (atom nil) :bp-ok? (atom false)
           :stepping (atom false)}]
    (assoc e :hook (debugger/breakpoint-handler (:break e) (fn [] (break-callbacks e))
                                                (fn [] @(:bp-ok? e))
                                                (:stepping e)))))

(defn- run-armed
  "Runs ev under session e's break, then drops the arming."
  [e ev]
  (try
    ((debugger/break-wrapper (:break e) (break-callbacks e)) ev)
    (finally
      ;; the arming was this eval's: nothing else the worker runs
      ;; after it counts as the person's. A pause a Stop's interrupt
      ;; left published (its cleanup skipped) ends here too; the
      ;; client drops its own when the eval finishes.
      (reset! (:armed? e) false)
      (debugger/end-stale-pause! (:break e) {} {:other-thread :wake}))))

(defn session-wrapper
  "Session e's jolt.nrepl/*eval-wrapper*: runs the evaluation's thunk under
  the session's break when the eval was armed, else just runs it."
  [e]
  (fn [ev]
    (try
      (if @(:armed? e)
        (run-armed e ev)
        (ev))
      (finally
        ;; a breakpoint reached after this eval can't reach the client,
        ;; and a Step it ended in doesn't carry over to the next
        (reset! (:bp-ok? e) false)
        (reset! (:stepping e) false)
        (debugger/end-stale-pause! (:break e) {} {:other-thread :wake})))))

(defn- as-long [x] (if (string? x) (parse-long x) x))

(defn- clone-request!
  "Hands a clone on to next with a new entry's wrapper bound, keeping the
  entry under the new session id once the clone's reply names it."
  [sessions next request]
  (let [e (entry)]
    (with-bindings (if-let [v (hook-var)] {v (session-wrapper e)} {})
      (next (assoc request :reply (fn [m]
                                    (when-let [new-sid (get m "new-session")]
                                      (swap! sessions assoc new-sid e))
                                    ((:reply request) m)))))))

(defn- arm-and-pass!
  "An eval or load-file on session entry e (or none): arms it when the
  client asked, keeps the reply, and hands the request on."
  [e next request]
  (when e
    (reset! (:armed? e) (= "1" (str (get request "jonquil-break"))))
    (let [bp? (= "1" (str (get request "jonquil-breakpoints")))]
      ;; a restarted child, or one that never heard the op: arm before
      ;; the code compiles, so it keeps its locals, and say how it went
      ;; on the eval's own reply (the client's pending breakpoints)
      (when bp?
        (let [r (if (identical? (:hook e) @debugger/breakpoint-hook)
                  {:on? true}
                  (debugger/arm-breakpoints! (:hook e)))]
          (try ((:reply request) (if-let [err (:error r)]
                                   {"jonquil-breakpoints-error" err}
                                   {"jonquil-breakpoints-armed" "true"}))
               (catch Throwable _ nil))))
      (reset! (:bp-ok? e) bp?))
    (reset! (:reply e) (:reply request)))
  (next request))

(defn- break-check-reply []
  (cond
    (not (debugger/break-available?)) {"status" ["done"] "available" "false"}
    (not (hooked?))
    {"status" ["done"] "available" "false"
     "reason" (str "This Jolt's nREPL server has no jolt.nrepl/*eval-wrapper*, "
                   "so its errors can't pause.")}
    :else {"status" ["done"] "available" "true"}))

(defn- break-eval! [e request]
  ;; answered from the paused thread, as jonquil-frame-eval is answered
  (if e
    (debugger/break-eval! (:break e) (as-long (get request "frame")) (str (get request "code"))
                          (fn [r]
                            (server/respond request (if-let [err (:error r)]
                                                      {"status" ["done"] "error" err}
                                                      {"status" ["done"] "frame-out" (:out r) "text" (:text r)}))))
    (server/respond request {"status" ["done"] "error" "Not paused any more."})))

(defn- heads-reply
  "jonquil.debugger/macro-heads for the symbols in heads (an EDN vector),
  in namespace ns."
  [ns heads]
  (try {"status" ["done"]
        "heads" (pr-str (debugger/macro-heads ns (edn/read-string (str heads))))}
       (catch Throwable t {"status" ["done"] "error" (or (ex-message t) (str (class t)))})))

(defn- breakpoints-reply
  "Arms or disarms session entry e's breakpoint hook (on, \"true\" or
  \"false\")."
  [e on]
  (let [r (cond (nil? e) {:error "No such session."}
                (= "true" (str on)) (debugger/arm-breakpoints! (:hook e))
                :else (debugger/disarm-breakpoints! (:hook e)))]
    (if-let [err (:error r)]
      {"status" ["done"] "error" err}
      {"status" ["done"] "on" (str (boolean (:on? r)))
       "debug-turned-on" (str (boolean (:debug-turned-on? r)))})))

(defn handler
  "The middleware's request handler over sessions (an atom, session id ->
  entry), handing everything but its own ops on to next."
  [sessions next]
  (fn [request]
    (let [sid (get request "session")
          e (get @sessions sid)]
      (case (get request "op")
        "clone" (clone-request! sessions next request)
        ("eval" "load-file") (arm-and-pass! e next request)
        "jonquil-break-check" (server/respond request (break-check-reply))
        "jonquil-breakpoints"
        (server/respond request (if-let [heads (get request "heads")]
                                  (heads-reply (get request "ns") heads)
                                  (breakpoints-reply e (get request "on"))))
        "jonquil-break-eval" (break-eval! e request)
        "jonquil-break-continue"
        (do (when e (reset! (:stepping e) false))
            (server/respond request {"status" ["done"]
                                     "continued" (str (boolean (and e (debugger/continue! (:break e)))))}))
        "jonquil-break-step"
        (server/respond request {"status" ["done"]
                                 "stepped" (str (boolean (and e (debugger/step! (:break e) (:stepping e)))))})
        "interrupt"
        (do (when e
              (reset! (:stepping e) false)
              (debugger/break-stop! (:break e)))
            (next request))
        "close"
        (do (swap! sessions dissoc sid)
            (next request))
        (next request)))))

(defonce ^:private sessions (atom {}))

(defn paused-local-for
  "The value of local-name in session sid's paused error's frame i, as
  jonquil.debugger/paused-local answers (::not-paused without a session)."
  [sid error-id i local-name]
  (if-let [e (get @sessions sid)]
    (debugger/paused-local (:break e) error-id i local-name)
    ::debugger/not-paused))

(defn break
  "The middleware: Break on error for every session cloned through it."
  [next]
  (server/register-ops! "jonquil-break-check" "jonquil-break-eval" "jonquil-break-continue"
                        "jonquil-break-step" "jonquil-breakpoints")
  (handler sessions next))
