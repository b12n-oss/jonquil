(ns fake-cljd-nrepl
  "A fake ClojureDart nREPL server for jonquil.nrepl.attach's tests and the
  cljd self-test window. It imitates the nrepl-poc fork's replies as they
  were measured against a running app on 2026-09-28:

  - describe advertises the fork's eight ops; clone answers :new-session.
  - A new session's first request gets the banner as :out, then
    {:ns \"cljd.user\"}, before any value.
  - eval and load-file evaluate their text form by form, in the session's
    namespace. An (ns x ...) form switches the session and answers only
    {:ns x}; any other form answers {:value v} then {:ns current}. The
    request's own :ns field is ignored, as the fork ignores it.
  - The first failing form answers {:err \"Error:  nil\\n\"}, {:ns current}
    and {:status [\"done\" \"eval-error\"]}, and nothing after it runs.
  - (in-ns ...) is refused with the fork's own sentence.
  - A form that prints (:prints) answers {:out text} before its value, as
    fork 8aaa4628 and later do.
  - lookup answers lookup-error, as the older fork 573781a2 did, and an
    unknown op answers unknown-op.

  It never evaluates anything: a form's value comes from :answers (keyed by
  the form's pr-str), else the sum for (+ numbers...), a closure string for
  defn/defn-, \"nil\" for comment, and \":ok\" for anything else.

  Every message it receives is kept, so a test can assert which ops a
  client sent (never interrupt, never close)."
  (:require [clojure.string :as str]
            [jonquil.nrepl.bencode :as bencode]))

(def fork-ops
  ;; The describe reply's ops, measured.
  ["clone" "close" "describe" "eval" "interrupt" "load-file" "lookup" "ls-sessions"])

(def banner
  ;; The start of the fork's per-session banner, measured.
  "ClojureDart\n\nUse `(pick!)` to toggle the widget picker. The picked widget becomes `*1`.\n")

(def fail-err
  ;; What the fork answered for format.cljd's tenth form, measured.
  "Error:  nil\n")

(def in-ns-err
  ;; The fork's own refusal, measured.
  "`in-ns` is not available as a standalone REPL form in ClojureDart. Declare namespace dependencies in an `(ns ...)` form.")

(defn- read-forms
  "code's top-level forms, in order. Throws on unbalanced code."
  [code]
  (let [r (java.io.PushbackReader. (java.io.StringReader. code))]
    (loop [acc []]
      (let [f (read r false ::eof)]
        (if (= ::eof f) acc (recur (conj acc f)))))))

(defn- head [form] (when (seq? form) (first form)))

(defn- default-value [form]
  (cond
    ;; Jonquil's wake-up probe is (+ 1 2), so sums of numbers answer as the
    ;; app would (measured: "3").
    (and (= '+ (head form)) (every? number? (rest form))) (str (apply + (rest form)))
    (#{'defn 'defn-} (head form)) (str "Closure: () => dynamic from Function '" (second form) "': static.")
    (= 'comment (head form)) "nil"
    :else ":ok"))

(defn- matches? [marker form] (and marker (str/includes? (pr-str form) marker)))

(defn- evaluate!
  "Answers one eval or load-file request, form by form. reply sends one
  message map (string keys) with the request's id. Returns :dropped when a
  :drop-on form closed the connection."
  [{:keys [opts sessions]} sock reply m]
  (let [sid (get m "session")
        text (or (get m "file") (get m "code") "")
        fresh? (get-in @sessions [sid :fresh?])
        cur (fn [] (get-in @sessions [sid :ns] "cljd.user"))]
    (when fresh?
      (swap! sessions assoc-in [sid :fresh?] false)
      (reply {"out" banner})
      (reply {"ns" "cljd.user"}))
    (if (when-let [served (get-in @sessions [sid :served])]
          (<= (swap! served inc) (:asleep-for opts 0)))
      (do (reply {"value" "nil"}) (reply {"ns" (cur)}) (reply {"status" ["done"]}))
      (let [forms (try (read-forms text) (catch Exception e e))]
        (if (instance? Exception forms)
          (do (reply {"err" (str (ex-message forms) "\n")}) (reply {"status" ["done" "eval-error"]}))
          (loop [fs (seq forms)]
            (let [f (first fs)]
              (cond
                (nil? fs) (reply {"status" ["done"]})

                (matches? (:drop-on opts) f)
                (do (.close ^java.net.Socket sock) :dropped)

                (= 'ns (head f))
                (do (swap! sessions assoc-in [sid :ns] (str (second f)))
                    (reply {"ns" (cur)})
                    (recur (next fs)))

                (= 'in-ns (head f))
                (reply {"err" in-ns-err "status" ["done" "eval-error"]})

                (matches? (:fail-on opts) f)
                (do (reply {"err" fail-err}) (reply {"ns" (cur)}) (reply {"status" ["done" "eval-error"]}))

                :else
                (do (when-let [ms (some (fn [[marker ms]] (when (matches? marker f) ms)) (:slow opts))]
                      (Thread/sleep (long ms)))
                    (when-let [printed (some (fn [[marker text]] (when (matches? marker f) text)) (:prints opts))]
                      (reply {"out" printed}))
                    (reply {"value" (get (:answers opts) (pr-str f) (default-value f))})
                    (reply {"ns" (cur)})
                    (recur (next fs)))))))))))

(defn- handle!
  [{:keys [opts sessions log counter] :as srv} sock reply m]
  (swap! log conj (dissoc m "id"))
  (case (get m "op")
    "describe" (reply {"ops" (zipmap (:ops opts fork-ops) (repeat {})) "status" ["done"]})
    "clone" (let [sid (str "fake-session-" (swap! counter inc))]
              (swap! sessions assoc sid {:ns "cljd.user" :fresh? true :served (atom 0)})
              (reply {"new-session" sid "status" ["done"]}))
    ("eval" "load-file") (evaluate! srv sock reply m)
    "lookup" (reply {"status" ["done" "lookup-error"]})
    ("interrupt" "close" "ls-sessions") (reply {"status" ["done"]})
    (reply {"err" (str "Unknown op: " (get m "op")) "status" ["done" "unknown-op"]})))

(defn- serve-connection! [{:keys [opts conns] :as srv} ^java.net.Socket sock]
  (swap! conns conj sock)
  (let [src (bencode/byte-source (.getInputStream sock))
        out (.getOutputStream sock)
        lock (Object.)]
    (try
      (loop []
        (let [m (bencode/decode src)]
          (when-not (= ::bencode/eof m)
            (let [reply (fn [kv]
                          (locking lock
                            (.write ^java.io.OutputStream out ^bytes (bencode/encode (assoc kv "id" (get m "id"))))
                            (.flush ^java.io.OutputStream out)))]
              (when-not (:mute? opts)
                (when-not (= :dropped (handle! srv sock reply m))
                  (recur)))))))
      (catch Throwable _ nil)
      (finally
        (try (.close sock) (catch Throwable _ nil))))))

(defn start!
  "Starts a fake server on a free loopback port and returns it. opts:
  :ops      the ops describe advertises (default fork-ops)
  :answers  {form-pr-str printed-value}
  :slow     {marker ms}: a form whose pr-str holds marker answers after ms
  :fail-on  marker: that form fails as the fork's did, and the rest is skipped
  :prints   {marker text}: that form answers {:out text} before its value
  :drop-on  marker: that form closes the connection without an answer
  :asleep-for n: a new session's first n eval or load-file requests answer
            nil without evaluating, as a REPL that isn't awake yet does
  :mute?    read requests but never answer any"
  ([] (start! {}))
  ([opts]
   (let [ss (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))
         srv {:opts opts :server ss :port (.getLocalPort ss)
              :sessions (atom {}) :log (atom []) :counter (atom 0)
              :conns (atom []) :stopped? (atom false)}]
     (future
       (try
         (loop []
           (let [sock (.accept ss)]
             (if @(:stopped? srv)
               (.close sock)
               (do (future (serve-connection! srv sock))
                   (recur)))))
         (catch Throwable _ nil)))
     srv)))

(defn ops-seen
  "Every op the server has received, in order."
  [srv]
  (mapv (fn [m] (get m "op")) @(:log srv)))

(defn requests
  "Every request the server has received (string keys, no :id), in order."
  [srv]
  @(:log srv))

(defn drop-connections!
  "Closes every client socket from the server's side, the way an app that
  quits drops its nREPL clients."
  [srv]
  (doseq [s @(:conns srv)] (try (.close ^java.net.Socket s) (catch Throwable _ nil)))
  nil)

(defn stop!
  "Stops accepting and drops every client. Measured on Jolt v0.8.13: closing
  a ServerSocket doesn't wake a thread blocked in accept, so this connects
  once first to let the accept loop see the flag and end."
  [srv]
  (reset! (:stopped? srv) true)
  (try (.close (java.net.Socket. "127.0.0.1" (int (:port srv)))) (catch Throwable _ nil))
  (try (.close ^java.net.ServerSocket (:server srv)) (catch Throwable _ nil))
  (drop-connections! srv))
