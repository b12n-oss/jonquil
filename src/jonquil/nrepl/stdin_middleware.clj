(ns jonquil.nrepl.stdin-middleware
  "nREPL middleware for an out-of-process Jolt child: stdin, so read-line and
  read in a program wait for what the user types at Jonquil's prompt.

  Like jonquil.nrepl.inspect-middleware, nothing in Jonquil requires this
  namespace: jonquil.nrepl.middleware-files embeds it, and the child loads
  it first in :nrepl/middleware, outside jolt-lang/nrepl's own. That's what
  makes it work (measured 2026-10-18): the session middleware starts each
  session's worker thread with bound-fn while it handles clone, and
  jolt.nrepl/evaluate rebinds *out* and *err* but not *in*, so binding *in*
  here, around the clone, gives every eval in that session this reader.

  The wire protocol is JVM nREPL's, which jonquil.nrepl.jvm already speaks:
  a read with nothing buffered sends need-input on the session's current
  eval, and a stdin op answers it, \"\" meaning end of input for that read
  only. A read blocked on the queue isn't one of Chez's safe points, so an
  interrupt alone wouldn't free it; the middleware also sees interrupt and
  wakes a waiting read, which then throws the {:jolt/interrupted true} the
  session reports as interrupted (measured: 3.3 ms)."
  (:require [jolt.nrepl :as server])
  (:import (clojure.lang LineNumberingPushbackReader)
           (java.io Reader StringReader)
           (java.util.concurrent LinkedBlockingQueue)))

(defn entry
  "One session's stdin state: the queue stdin ops feed, the reply fn of the
  session's current eval, the text not yet read, and whether a read waits."
  []
  {:queue (LinkedBlockingQueue.)
   :reply (atom nil)
   :current (atom (StringReader. ""))
   :waiting? (atom false)})

(defn waiting? [e] @(:waiting? e))

(defn offer!
  "Hands text to the read waiting in this session; \"\" is end of input for
  that read. true when a read was waiting. Text that arrives with no read
  waiting is dropped, as the in-process engine starts each run with an
  empty queue: kept, it would answer some later, unrelated read (a Return
  racing a Stop could leave the typed line for the next eval)."
  [e text]
  (boolean (when (waiting? e)
             (.offer ^LinkedBlockingQueue (:queue e) (if (= "" text) ::eof text)))))

(defn interrupt!
  "Wakes a read waiting in this session so it throws Jolt's interrupted
  signal, after dropping anything queued or half read. Does nothing when no
  read waits: a marker left in the queue would interrupt the next,
  unrelated read."
  [e]
  (when (waiting? e)
    (.clear ^LinkedBlockingQueue (:queue e))
    (reset! (:current e) (StringReader. ""))
    (.offer ^LinkedBlockingQueue (:queue e) ::interrupt)))

(defn new-eval!
  "A new evaluation in this session: its reply is the one reads ask on, and
  text the last one left unread (a line past the one it read) doesn't
  carry over, matching the in-process engine's per-run reader."
  [e reply]
  (reset! (:reply e) reply)
  (reset! (:current e) (StringReader. ""))
  (.clear ^LinkedBlockingQueue (:queue e)))

(defn reader
  "A java.io.Reader over e: buffered text first; with none, flush *out* (the
  eval's own writer, which jolt only sends on a flush, so a print of a
  prompt shows before the wait), mark the read waiting, need-input on the
  current eval's reply, then a blocking take. Waiting is set before
  need-input goes out, so a stdin or an interrupt answering it always
  finds the read waiting."
  [{:keys [queue reply current waiting?]}]
  (proxy [Reader] []
    (close [] nil)
    (read
      ([] (let [buf (make-array Character/TYPE 1)
                n (.read ^Reader this buf 0 1)]
            (if (neg? n) -1 (int (aget ^chars buf 0)))))
      ([cbuf off len]
       (loop []
         (let [n (.read ^StringReader @current cbuf off len)]
           (if (neg? n)
             (do (try (flush) (catch Throwable _ nil))
                 (reset! waiting? true)
                 (let [item (try (when-let [r @reply] (r {"status" ["need-input"]}))
                                 (.take ^LinkedBlockingQueue queue)
                                 (finally (reset! waiting? false)))]
                   (cond
                     (= ::eof item) -1
                     (= ::interrupt item) (throw (ex-info "interrupted" {:jolt/interrupted true}))
                     :else (do (reset! current (StringReader. (str item))) (recur)))))
             n)))))))

(defn- clone!
  "Hands a clone on to next with *in* over a fresh entry, which is
  registered under the new session's id once next answers with it."
  [sessions next request]
  (let [e (entry)]
    (binding [*in* (LineNumberingPushbackReader. (reader e))]
      (next (assoc request :reply (fn [m]
                                    (when-let [new-sid (get m "new-session")]
                                      (swap! sessions assoc new-sid e))
                                    ((:reply request) m)))))))

(defn- stdin-status
  "A stdin request's status for session entry e: done once offered to a
  waiting read, not-waiting when nothing reads, an error for an unknown
  session."
  [e request]
  (cond (nil? e) ["done" "error"]
        (offer! e (str (get request "stdin"))) ["done"]
        :else ["done" "not-waiting"]))

(defn handler
  "The middleware's request handler over sessions (an atom, session id ->
  entry), handing everything but stdin on to next."
  [sessions next]
  (fn [request]
    (let [sid (get request "session")]
      (case (get request "op")
        "clone" (clone! sessions next request)
        ("eval" "load-file")
        (do (when-let [e (get @sessions sid)] (new-eval! e (:reply request)))
            (next request))
        "stdin" (server/respond request {"status" (stdin-status (get @sessions sid) request)})
        "interrupt"
        (do (when-let [e (get @sessions sid)] (interrupt! e))
            (next request))
        "close"
        (do (swap! sessions dissoc sid)
            (next request))
        (next request)))))

(defonce ^:private sessions (atom {}))

(defn stdin
  "The middleware: stdin for every session cloned through it."
  [next]
  (server/register-ops! "stdin")
  (handler sessions next))
