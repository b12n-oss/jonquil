(ns jonquil.nrepl.prepl
  "A client for the line-EDN prepl the ClojureScript child serves: Clojure
  source text goes out one form per line, one EDN map per line comes back
  ({:tag :ret|:out|:err|:tap ...}).

  Replies are read with a BufferedReader's readLine on a daemon thread
  (measured on Jolt: a PushbackReader over a socket blocks until more than
  one line has arrived) and handed to on-reply in arrival order. Lines
  jonquil.cljs-node/parse-reply rejects are skipped. {:tag :closed} is
  delivered exactly once, at EOF, on a read error, or from close!, whichever
  comes first. A reader blocked on a socket closed from another thread may
  never see EOF on Jolt, so close! delivers it itself."
  (:require [jolt.socket]
            [jonquil.cljs-node :as cljs-node]))

(def connect-timeout-ms 2000)

(defn- notify-closed!
  "Delivers {:tag :closed} once per connection, whoever gets here first."
  [{:keys [closed-sent? on-reply]}]
  (when (compare-and-set! closed-sent? false true)
    (try (on-reply {:tag :closed}) (catch Throwable _ nil))))

(defn- read-loop [{:keys [^java.io.BufferedReader in on-reply open?] :as conn}]
  (try
    (loop []
      (when-let [line (.readLine in)]
        (when-let [m (cljs-node/parse-reply line)]
          ;; A throwing on-reply loses only that reply, never the reader.
          (try (on-reply m) (catch Throwable _ nil)))
        (recur)))
    (catch Throwable _ nil))
  (reset! open? false)
  (notify-closed! conn))

(defn connect!
  "Connects to host:port and starts the reader thread. Throws
  java.io.IOException when nothing listens (or the connect takes longer than
  2000 ms); the socket is closed before the throw. on-reply is called on the
  reader thread."
  [host port on-reply]
  (let [sock (java.net.Socket.)]
    (try
      (.connect sock (java.net.InetSocketAddress. ^String host (int port)) (int connect-timeout-ms))
      (catch Throwable e
        (try (.close sock) (catch Throwable _ nil))
        (throw e)))
    (let [conn {:socket sock
                :in (java.io.BufferedReader.
                     (java.io.InputStreamReader. (.getInputStream sock) "UTF-8"))
                :out (java.io.OutputStreamWriter. (.getOutputStream sock) "UTF-8")
                :lock (Object.)
                :on-reply on-reply
                :open? (atom true)
                :closed-sent? (atom false)}
          t (Thread. ^Runnable (fn [] (read-loop conn)))]
      (.setDaemon t true)
      (.start t)
      (assoc conn :thread t))))

(defn open? [conn] (boolean (and conn @(:open? conn))))

(defn send!
  "Writes text and a newline, flushed, under the connection's lock. After
  close! (or once the peer is gone) it throws java.io.IOException and writes
  nothing, so a caller can't mistake a dropped form for a sent one."
  [{:keys [^java.io.Writer out lock] :as conn} text]
  (locking lock
    (when-not (open? conn)
      (throw (java.io.IOException. "prepl connection closed")))
    (.write out (str text "\n"))
    (.flush out))
  nil)

(defn close!
  "Closes the socket. Idempotent. on-reply gets its one {:tag :closed}
  (from here if the reader hasn't already delivered it)."
  [conn]
  (when conn
    (reset! (:open? conn) false)
    (try (.close ^java.net.Socket (:socket conn)) (catch Throwable _ nil))
    (notify-closed! conn))
  nil)
