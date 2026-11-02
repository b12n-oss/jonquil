(ns jonquil.nrepl.inspect-middleware
  "nREPL middleware for an out-of-process Jolt child: the inspector's ops,
  over the same jonquil.inspect.view code the in-process inspector runs.

  Nothing in Jonquil requires this namespace. jonquil.nrepl.middleware-files
  embeds its text and view.clj's when Jonquil is compiled, writes both to a
  cache folder, and the child loads them from there as a :local/root dep
  whose middleware sits after jolt-lang/nrepl's in :nrepl/middleware.

  The ops and keys are the JVM inspector's (clojure-cli.repl), so the
  evaluator maps requests and reads views the same way for both, plus ns on
  inspect-def for the target namespace. They're middleware ops rather than
  evals on purpose: jolt.nrepl/evaluate shifts the global *1 *2 *3 after
  every successful eval, and an op leaves the user's history alone
  (measured 2026-10-18). inspect-start reads that global *1."
  (:require [jolt.nrepl :as server]
            [jonquil.debugger :as debugger]
            [jonquil.inspect.view :as view]
            [jonquil.nrepl.break-middleware :as break-middleware]
            [jonquil.results-rt :as results-rt]
            [jonquil.trace-rt :as trace-rt]))

(def ops
  ["inspect-start" "inspect-in" "inspect-out" "inspect-page"
   "inspect-toggle-meta" "inspect-toggle-table" "inspect-def"
   "inspect-earlier" "inspect-later"
   ;; the Frames panel (jonquil.debugger), over the session's global *e
   "jonquil-frames" "inspect-local" "jonquil-debug-mode" "jonquil-frame-eval"
   "jonquil-frame-interrupt"
   ;; tracing (jonquil.trace-rt): middleware ops, so *1 stays put
   "jonquil-trace" "inspect-trace"
   ;; live console results (jonquil.results-rt)
   "jonquil-keep-result" "inspect-result"])

(defonce ^:private frame-token
  ;; the token of the frame evaluation running now, for jonquil-frame-interrupt
  (atom nil))

(defn- as-long
  "A bencode integer, or the same number sent as a string."
  [x]
  (if (string? x) (parse-long x) x))

(defn- target-ns
  "inspect-def's namespace: request ns when it names a loaded one, else user."
  [ns-name]
  (or (when ns-name (find-ns (symbol ns-name))) (the-ns 'user)))

(defn- view-reply
  "The response map for an inspect-* view op, or nil for any other op.
  state is an atom holding the view (jonquil.inspect.view's own state)."
  [state request]
  (let [g (fn [k] (get request k))
        args (case (g "op")
               "inspect-start" [:start {:value *1 :rows (as-long (g "rows")) :cols (as-long (g "cols"))}]
               "inspect-in" [:in {:idx (as-long (g "idx"))}]
               "inspect-out" [:out {}]
               "inspect-page" [:page {:delta (as-long (g "delta"))}]
               "inspect-toggle-meta" [:meta {:cursor (as-long (g "cursor"))}]
               "inspect-toggle-table" [:table {}]
               "inspect-def" [:def {:name (g "name") :target-ns (target-ns (g "ns"))}]
               "inspect-earlier" [:earlier {}]
               "inspect-later" [:later {}]
               nil)]
    (when-let [[op op-args] args]
      {"status" ["done"] "view" (pr-str (view/run-op state op op-args))})))

(defn handle
  "The response map for one of ops, or nil for any other op. The frames
  ops read jolt.nrepl's global *e, the last error, as inspect-start reads
  *1. state is an atom holding the view."
  [state request]
  (let [g (fn [k] (get request k))]
    (case (g "op")
      "jonquil-frames"
      ;; a throw is an "error", never a reply without its data
      (try
        {"status" ["done"]
         "data" (binding [*print-length* nil *print-level* nil]
                  (pr-str (if-let [e *e]
                            (debugger/frames-data e {:frames-fn (debugger/frames-fn) :resolve-file break-middleware/resource-url})
                            {:note "No error yet."})))}
        (catch Throwable e {"status" ["done"] "error" (or (ex-message e) (str (class e)))}))
      "jonquil-debug-mode"
      (let [r (try (debugger/toggle-debug-mode!)
                   (catch Throwable e {:error (or (ex-message e) (str (class e)))}))]
        (if-let [err (:error r)]
          {"status" ["done"] "error" err}
          {"status" ["done"] "on" (str (:on? r))}))
      "inspect-local"
      ;; a paused break's local first (Break on error), then the last error's
      (let [paused (break-middleware/paused-local-for (g "session") (g "error-id") (as-long (g "frame")) (g "local"))
            v (if-let [e (when (= ::debugger/missing paused) *e)]
                (if (debugger/stale? e (g "error-id"))
                  ::stale
                  (debugger/local-value e (as-long (g "frame")) (g "local") {:frames-fn (debugger/frames-fn)}))
                paused)]
        {"status" ["done"]
         "view" (pr-str (case v
                            ::debugger/missing {:error "That local is gone."}
                            ::stale {:error debugger/STALE}
                            (view/run-op state :start {:value v :rows (as-long (g "rows")) :cols (as-long (g "cols"))})))})
      "jonquil-frame-eval"
      ;; "frame-out", not "out": a client streams any "out" it sees
      (let [token (debugger/make-token)]
        (try
          (let [e *e
                r (cond (nil? e) {:error "No error yet."}
                        (debugger/stale? e (g "error-id")) {:error debugger/STALE}
                        :else (do (reset! frame-token token)
                                  (debugger/eval-in-frame e (as-long (g "frame")) (g "code")
                                                          {:frames-fn (debugger/frames-fn) :token token})))]
            (if-let [err (:error r)]
              {"status" ["done"] "error" err}
              {"status" ["done"] "frame-out" (:out r) "text" (:text r)}))
          (catch Throwable t {"status" ["done"] "error" (or (ex-message t) (str (class t)))})
          (finally (compare-and-set! frame-token token nil))))
      "jonquil-trace"
      (try
        {"status" ["done"]
         "data" (binding [*print-length* nil *print-level* nil]
                  (pr-str (trace-rt/op (keyword (g "action"))
                                       {:sym (g "sym") :ns (g "ns") :since (some-> (g "since") as-long)})))}
        (catch Throwable e {"status" ["done"] "error" (or (ex-message e) (str (class e)))}))
      "jonquil-keep-result"
      ;; the value the eval just made (*1) or its error (*e), kept for the
      ;; console's line
      {"status" ["done"]
       "data" (pr-str (results-rt/keep! (if (= "error" (g "what")) *e *1)))}
      "inspect-result"
      (let [v (results-rt/value (as-long (g "result-id")))]
        {"status" ["done"]
         "view" (pr-str (if (= v ::results-rt/gone)
                            {:error "That result is gone."}
                            (view/run-op state :start {:value v :rows (as-long (g "rows")) :cols (as-long (g "cols"))})))})
      "inspect-trace"
      (let [v (trace-rt/value (as-long (g "trace-id")) (keyword (g "part")))]
        {"status" ["done"]
         "view" (pr-str (cond
                          (= v ::trace-rt/gone) {:error "That call is gone."}
                          (= v ::trace-rt/lazy) {:error "That value was a lazy seq nothing had realized, so it wasn't kept."}
                          :else (view/run-op state :start {:value v :rows (as-long (g "rows")) :cols (as-long (g "cols"))})))})
      "jonquil-frame-interrupt"
      (do (some-> @frame-token debugger/interrupt-token!)
          {"status" ["done"]})
      (view-reply state request))))

(defonce ^:private state (atom nil))

(def ^:private op-set (set ops))

(defn answer!
  "Answers request on a future of its own, one inspector op at a time
  (locking state), and returns that future. jolt.nrepl calls a handler on
  the connection's reader thread, and rendering a view realises part of
  *1, so a slow or endless value must not stop that thread reading Run,
  completion or Stop's interrupt. A throw becomes a
  done reply carrying its message, so the client never waits on nothing."
  [state request]
  (future
    (try
      (server/respond request (if (#{"jonquil-frame-eval" "jonquil-frame-interrupt" "jonquil-trace" "jonquil-keep-result"} (get request "op"))
                                ;; the user's form, as slow as it is, and the
                                ;; trace ops (their own atom): never under the
                                ;; lock every inspector op takes
                                (handle state request)
                                (locking state (handle state request))))
      (catch Throwable e
        (try (server/respond request {"status" ["done" "error"]
                                      "view" (pr-str {:path "" :info "" :header ""
                                                      :note (or (ex-message e) (str (class e)))})})
             (catch Throwable _ nil))))))

(defn inspect
  "The middleware: answers the inspect-* ops, hands everything else on."
  [handler]
  (apply server/register-ops! ops)
  (fn [request]
    (if (op-set (get request "op"))
      (do (answer! state request) nil)
      (handler request))))
