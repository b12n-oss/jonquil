(ns jonquil.nrepl.inspect-middleware
  "nREPL middleware for an out-of-process Jolt child: the inspector's ops,
  over the same jonquil.inspect.view code the in-process inspector runs.

  Nothing in Jonquil requires this namespace. jonquil.nrepl.inspect-files
  embeds its text and view.clj's when Jonquil is compiled, writes both to a
  cache folder, and the child loads them from there as a :local/root dep
  whose middleware sits after jolt-lang/nrepl's in :nrepl/middleware.

  The ops and keys are the JVM inspector's (clojure-cli.repl), so the
  evaluator maps requests and reads views the same way for both, plus ns on
  inspect-def for the target namespace. They're middleware ops rather than
  evals on purpose: jolt.nrepl/evaluate shifts the global *1 *2 *3 after
  every successful eval, and an op leaves the user's history alone (spike,
  2026-10-18). inspect-start reads that global *1."
  (:require [jolt.nrepl :as server]
            [jonquil.inspect.view :as view]))

(def ops
  ["inspect-start" "inspect-in" "inspect-out" "inspect-page"
   "inspect-toggle-meta" "inspect-toggle-table" "inspect-def"])

(defn- as-long
  "A bencode integer, or the same number sent as a string."
  [x]
  (if (string? x) (parse-long x) x))

(defn- target-ns
  "inspect-def's namespace: request ns when it names a loaded one, else user."
  [ns-name]
  (or (when ns-name (find-ns (symbol ns-name))) (the-ns 'user)))

(defn handle
  "The response map for an inspect-* request, or nil for any other op.
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
               nil)]
    (when-let [[op op-args] args]
      {"status" ["done"] "view" (pr-str (view/run-op state op op-args))})))

(defonce ^:private state (atom nil))

(def ^:private op-set (set ops))

(defn answer!
  "Answers request on a future of its own, one inspector op at a time
  (locking state), and returns that future. jolt.nrepl calls a handler on
  the connection's reader thread, and rendering a view realises part of
  *1, so a slow or endless value must not stop that thread reading Run,
  completion or Stop's interrupt (review finding 3). A throw becomes a
  done reply carrying its message, so the client never waits on nothing."
  [state request]
  (future
    (try
      (server/respond request (locking state (handle state request)))
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
