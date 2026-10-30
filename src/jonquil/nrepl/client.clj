(ns jonquil.nrepl.client
  "One nREPL connection, used for every op for the life of a child: eval,
  load-file, interrupt, completions, lookup. Never a second socket (jank's
  server serves exactly one connection per process, measured; the cljd
  attach dialect reuses this client).

  A reader future decodes every message and hands it to the handler
  registered for its :id, so an interrupt and the eval it stops share the
  socket without two readers racing for the same message (a race seen with
  two synchronous readers). Messages
  arrive with top-level keys as keywords and everything nested as decoded
  (string keys).

  When the socket reaches EOF or fails, every pending handler gets one
  synthetic {:status [\"done\" \"jonquil-disconnected\"]} message, so no
  caller waits forever on a child that died."
  (:require [jolt.socket]
            [jonquil.nrepl.bencode :as bencode]))

(def disconnected-status "jonquil-disconnected")

(defn- keywordize [m]
  (into {} (map (fn [[k v]] [(keyword k) v])) m))

(defn done?
  "True when message m carries the \"done\" status."
  [m]
  (boolean (some #{"done"} (:status m))))

(defn- deliver-safely! [handler m]
  ;; A throwing handler loses only itself: the reader must keep reading.
  (try (handler m) (catch Throwable _ nil)))

(defn- fail-pending! [{:keys [pending open?]}]
  (reset! open? false)
  (let [hs (first (reset-vals! pending {}))]
    (doseq [[id h] hs]
      (deliver-safely! h {:id id :status ["done" disconnected-status]}))))

(defn- read-loop [{:keys [src pending] :as conn}]
  (try
    (loop []
      (let [raw (bencode/decode src)]
        (when-not (= raw ::bencode/eof)
          (let [m (keywordize raw)
                id (:id m)
                h (get @pending id)]
            (when h
              (deliver-safely! h m)
              (when (done? m) (swap! pending dissoc id))))
          (recur))))
    (catch Throwable _ nil))
  (fail-pending! conn))

(def default-connect-timeout-ms
  ;; A bare TCP connect to loopback answers in well under a millisecond
  ;; when something is listening; this only bounds the case where nothing
  ;; ever will, so waiting on the OS's own retransmission timeout (tens of
  ;; seconds) doesn't wedge a caller.
  5000)

(declare connect-to)

(defn connect
  "Connects to 127.0.0.1:port and starts the reader: connect-to on the
  loopback address."
  ([port] (connect port default-connect-timeout-ms))
  ([port connect-timeout-ms] (connect-to "127.0.0.1" port connect-timeout-ms)))

(defn connect-to
  "Connects to host:port and starts the reader. Throws
  java.io.IOException when nothing listens there (or host doesn't
  resolve), and java.net.SocketTimeoutException (a java.io.IOException)
  when the connect itself doesn't finish within connect-timeout-ms. A
  failed connect always closes the socket it opened before rethrowing,
  whatever it throws."
  ([host port] (connect-to host port default-connect-timeout-ms))
  ([host port connect-timeout-ms]
   (let [sock (java.net.Socket.)]
     (try
       (.connect sock (java.net.InetSocketAddress. ^String host (int port)) (int connect-timeout-ms))
       (catch Throwable e
         (try (.close sock) (catch Throwable _ nil))
         (throw e)))
     (let [conn {:socket sock
                 :out (.getOutputStream sock)
                 :src (bencode/byte-source (.getInputStream sock))
                 :lock (Object.)
                 :pending (atom {})
                 :open? (atom true)
                 :counter (atom 0)}]
       (assoc conn :reader (future (read-loop conn)))))))

(defn open? [conn] (boolean (and conn @(:open? conn))))

(defn send!
  "Sends one message map as is. false when the connection is closed or
  the write fails."
  [{:keys [out lock] :as conn} msg]
  (if-not (open? conn)
    false
    (try
      (locking lock
        (.write ^java.io.OutputStream out ^bytes (bencode/encode msg))
        (.flush ^java.io.OutputStream out))
      true
      (catch Throwable _ false))))

(defn request!
  "Sends msg with a fresh :id and calls (on-msg m) for every reply to it,
  on the reader thread, until one carries \"done\". Returns the id. On a
  closed connection on-msg gets the synthetic disconnected message at
  once."
  [conn msg on-msg]
  (let [id (str "jq-" (swap! (:counter conn) inc))]
    (swap! (:pending conn) assoc id on-msg)
    (when-not (send! conn (assoc msg :id id))
      (when (get (first (swap-vals! (:pending conn) dissoc id)) id)
        (deliver-safely! on-msg {:id id :status ["done" disconnected-status]})))
    id))

(defn request-sync
  "Sends msg and waits up to timeout-ms for its \"done\" reply. The
  replies in order, or ::timeout (the handler is then dropped)."
  [conn msg timeout-ms]
  (let [acc (atom [])
        done (promise)
        id (request! conn msg (fn [m]
                                (swap! acc conj m)
                                (when (done? m) (deliver done true))))]
    (if (deref done timeout-ms false)
      @acc
      (do (swap! (:pending conn) dissoc id) ::timeout))))

(defn close!
  "Closes the socket and fails whatever is still pending. Measured: a
  reader blocked in a read on a socket closed from another thread never
  sees EOF, so this fails the pending handlers itself. Close after the
  peer is gone (kill the child first) so the reader also ends."
  [conn]
  (when conn
    (try (.close ^java.net.Socket (:socket conn)) (catch Throwable _ nil))
    (fail-pending! conn)
    nil))
