(ns fake-jank-nrepl
  "A fake jank nREPL server for the jank dialect's tests and the :jank
  self-test window, usable in-process (start!) and as a stand-in `jank`
  executable (-main, run by dev/fake-jank). It imitates jank built from
  upstream main 38a6382 as measured 2026-10-12:

  - describe advertises jank-ops and {\"jank\" {\"version-string\"
    \"jank-0.1-alpha\"}}; clone answers a fresh session.
  - eval answers, per form, one {\"value\" v \"ns\" current} message (no
    empty :out/:err messages) and a final {\"status\" [\"done\"]}. A request
    whose \"ns\" isn't loaded evaluates in user: there is no
    namespace-not-found. load-file answers only its LAST form's value and
    ns. (ns x ...) and (in-ns 'x) mark x loaded.
  - (some? (find-ns 'x)) answers \"true\"/\"false\" from the loaded set.
  - The metadata form (jank/meta-doc-code's shape, matched on
    (resolve 'sym)) answers pr-str of [arglists doc] from :docs
    {sym [arglists-str doc]}, or \"nil\" for an unknown symbol.
  - A form matching :fail-on answers {\"err\" report}, {\"ex\" report}, then
    {\"status\" [\"done\" \"eval-error\"]}.
  - completions answers {\"completions\" [{candidate ns type}]}.
  - lookup NEVER answers (jank issue #998 wedges the connection); the
    request is only recorded.
  - interrupt, stdin and anything else answer status
    [\"error\" \"unknown-op\" \"done\"]. jank has no interrupt, so a :slow
    form waits out its ms and wakes early only when the connection drops.

  -main [dir] starts a server with two fixtures the jank self-test window
  relies on: :docs {\"greet\" [\"([w])\" \"Says hi.\"]} and
  :slow {\"(spin)\" 60000}. It writes the port to <dir>/.nrepl-port and
  blocks forever."
  (:require [fake-nrepl-core :as core :refer [head live? matches? read-forms
                                              wait!]]))

(def jank-ops ["clone" "completions" "describe" "eval" "load-file" "lookup"])

(def fail-report
  "─ analyze/unresolved-symbol ─\nerror: The symbol `nope` could not be resolved.\n")

(defn- find-form
  "The first sub-form of form satisfying pred, or nil."
  [pred form]
  (first (filter pred (tree-seq coll? seq form))))

(defn- resolve-sym
  "The symbol name in a (resolve 'sym) sub-form of form, or nil."
  [form]
  (when-let [r (find-form #(and (seq? %) (= 'resolve (first %))) form)]
    (let [a (second r)]
      (str (if (and (seq? a) (= 'quote (first a))) (second a) a)))))

(defn- find-ns-sym [form]
  (when-let [r (find-form #(and (seq? %) (= 'find-ns (first %))) form)]
    (let [a (second r)]
      (str (if (and (seq? a) (= 'quote (first a))) (second a) a)))))

(defn- default-value [loaded current form]
  (cond
    (and (= '+ (head form)) (every? number? (rest form))) (str (apply + (rest form)))
    (#{'def 'defn 'defn-} (head form)) (str "#'" current "/" (second form))
    (#{'in-ns 'ns} (head form)) "nil"
    (nil? form) "nil"
    (find-ns-sym form) (str (contains? loaded (find-ns-sym form)))
    :else ":ok"))

(defn- form-value [{:keys [opts loaded]} current form]
  (let [pr (pr-str form)]
    (cond
      (contains? (:answers opts) pr) (get (:answers opts) pr)
      (resolve-sym form)
      (if-let [[arglists doc] (get (:docs opts) (resolve-sym form))]
        (pr-str [arglists doc])
        "nil")
      :else (default-value @loaded current form))))

(defn- finish-forms!
  "Ends a request whose forms are all answered (load-file answers its last
  value first)."
  [{:keys [reply eval?]} current last-value]
  (when-not eval? (reply {"value" (or last-value "nil") "ns" current}))
  (reply {"status" ["done"]}))

(defn- fail-form!
  "Answers a :fail-on form with its report."
  [{:keys [opts reply]}]
  (let [report (:fail-report opts fail-report)]
    (reply {"err" report})
    (reply {"ex" report})
    (reply {"status" ["done" "eval-error"]})))

(defn- ns-after-form!
  "The current namespace after form f, marking a namespace that in-ns or ns
  names as loaded."
  [loaded f current]
  (case (str (head f))
    "in-ns" (let [t (str (second (second f)))] (swap! loaded conj t) t)
    "ns" (let [t (str (second f))] (swap! loaded conj t) t)
    current))

(defn- answer-form!
  "Waits out f's :slow delay, then answers it. Returns :dropped when the
  server stopped meanwhile, else {:next [current last-value]}."
  [{:keys [srv opts loaded stop-latch closed reply eval?]} f current]
  (let [ms (some (fn [[marker ms]] (when (matches? marker f) ms)) (:slow opts))]
    (when ms (wait! stop-latch closed ms))
    (if-not (live? stop-latch closed)
      :dropped
      (let [new-current (ns-after-form! loaded f current)
            v (form-value srv new-current f)]
        (when eval? (reply {"value" v "ns" new-current}))
        {:next [new-current v]}))))

(defn- form-step!
  "Handles the form at the head of fs. Returns {:next [current last-value]}
  to go on with the rest, anything else ends the request."
  [{:keys [opts] :as ctx} fs current last-value]
  (cond
    (nil? fs) (finish-forms! ctx current last-value)
    (matches? (:fail-on opts) (first fs)) (fail-form! ctx)
    :else (answer-form! ctx (first fs) current)))

(defn- evaluate!
  "Answers one eval or load-file request on the connection's own thread
  (jank handles requests one at a time)."
  [{:keys [opts loaded] :as srv} stop-latch closed reply m]
  (try
    (let [eval? (= "eval" (get m "op"))
          text (or (get m "code") (get m "file") "")
          req-ns (get m "ns")
          start-ns (if (and req-ns (contains? @loaded req-ns)) req-ns "user")
          ctx {:srv srv :opts opts :loaded loaded :stop-latch stop-latch
               :closed closed :reply reply :eval? eval?}]
      (loop [fs (seq (read-forms text)) current start-ns last-value nil]
        (let [r (form-step! ctx fs current last-value)]
          (if-let [[c v] (when (map? r) (:next r))]
            (recur (next fs) c v)
            r))))
    (catch Throwable e
      (try
        (let [report (str (ex-message e) "\n")]
          (reply {"err" report})
          (reply {"ex" report})
          (reply {"status" ["done" "eval-error"]}))
        (catch Throwable _ nil)))))

(defn- handle! [{:keys [opts sessions log counter busy] :as srv} _sock stop-latch closed reply m]
  (swap! log conj (dissoc m "id"))
  (swap! busy inc)
  (try
    (if-not (live? stop-latch closed)
      :dropped
      (case (get m "op")
        "describe" (reply {"ops" (zipmap (:ops opts jank-ops) (repeat {}))
                           "versions" {"jank" {"version-string" "jank-0.1-alpha"}}
                           "status" ["done"]})
        "clone" (let [sid (str "fake-session-" (swap! counter inc))]
                  (swap! sessions assoc sid {:ns "user"})
                  (reply {"new-session" sid "status" ["done"]}))
        ("eval" "load-file") (evaluate! srv stop-latch closed reply m)
        "completions"
        (reply {"completions" (mapv (fn [[c n t]] {"candidate" c "ns" n "type" t})
                                    (get (:completions opts) (get m "prefix")))
                "status" ["done"]})
        ;; jank issue #998: lookup wedges the connection. Record, never answer.
        "lookup" nil
        (reply {"status" ["error" "unknown-op" "done"]})))
    (finally (swap! busy dec))))

(defn start!
  "Starts a fake server on a free loopback port and returns it. opts:
  :ops         the ops describe advertises (default jank-ops)
  :answers     {form-pr-str printed-value}
  :fail-on     marker: a form whose pr-str holds it fails (err, ex, then
               status [\"done\" \"eval-error\"]) and the rest is skipped
  :fail-report the report text (default a measured unresolved-symbol one)
  :slow        {marker ms}: that form waits ms; only a dropped connection
               wakes it early
  :completions {prefix [[candidate ns type] ...]}
  :docs        {sym [arglists-str doc]} for the metadata form
  :loaded      extra namespace names loaded from the start, besides user
  :mute?       read requests but never answer any"
  ([] (start! {}))
  ([opts] (core/start-server! opts handle! {:loaded (atom (into #{"user"} (:loaded opts)))})))

(def ops-seen "Every op the server has received, in order." core/ops-seen)

(def requests "Every request the server has received (string keys, no :id), in order." core/requests)

(def drop-connections!
  "Closes every client socket from the server's side (fake-nrepl-core)."
  core/drop-connections!)

(def stop!
  "Stops the server and waits for its connection threads (fake-nrepl-core)."
  core/stop!)

(def ^:private main-server (atom nil))

(def ^:private main-latch (atom nil))

(defn stop-main!
  "Stops the server -main started, if any, and lets -main return."
  []
  (when-let [srv @main-server] (stop! srv) (reset! main-server nil))
  (when-let [l @main-latch] (deliver l true) (reset! main-latch nil)))

(defn -main
  "Starts the stand-in jank server, writes its port to <dir>/.nrepl-port
  (written under a temp name and renamed, so the file appears whole) and
  blocks until stop-main! is called."
  [dir]
  (let [srv (start! {:docs {"greet" ["([w])" "Says hi."]}
                     :slow {"(spin)" 60000}})
        latch (promise)
        tmp (java.io.File. (str dir "/.nrepl-port.tmp"))]
    (reset! main-server srv)
    (reset! main-latch latch)
    (spit tmp (str (:port srv)))
    (java.nio.file.Files/move (.toPath tmp) (.toPath (java.io.File. (str dir "/.nrepl-port")))
                              (into-array java.nio.file.CopyOption
                                          [java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
    @latch
    nil))
