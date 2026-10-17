(ns fake-nrepl-core
  "The socket plumbing dev/fake_cljd_nrepl.clj and dev/fake_jvm_nrepl.clj
  share: the accept loop, one thread per connection decoding bencode and
  handing each request to the fake's own handle!, the request log, and a
  stop! that doesn't return until every connection thread has ended. What
  a fake answers stays in that fake; nothing here knows any op.

  A server is a map: {:opts :server :port :sessions :log :counter :conns
  :futures :stopped? :stop-latch :busy}, plus whatever extra state the
  fake passes to start-server!. handle! is called as
  (handle! srv sock stop-latch closed reply m) and returns :dropped to end
  the connection."
  (:require [clojure.string :as str]
            [jonquil.nrepl.bencode :as bencode]))

(defn read-forms
  "code's top-level forms, in order. Throws on unbalanced code."
  [code]
  (let [r (java.io.PushbackReader. (java.io.StringReader. code))]
    (loop [acc []]
      (let [f (read r false ::eof)]
        (if (= ::eof f) acc (recur (conj acc f)))))))

(defn head [form] (when (seq? form) (first form)))

(defn matches? [marker form] (and marker (str/includes? (pr-str form) marker)))

(defn live? [stop-latch closed] (not (or (realized? stop-latch) (realized? closed))))

(defn wait!
  "Waits up to ms, waking at once (well under ms) once stop-latch or closed
  resolves. Jolt's socket read/write don't check a :closed flag on their
  own, so a Thread/sleep here would otherwise sleep out its full ms even
  after stop! or drop-connections! has ended this connection."
  [stop-latch closed ms]
  (let [deadline (+ (System/currentTimeMillis) (long ms))]
    (loop []
      (when (and (< (System/currentTimeMillis) deadline) (live? stop-latch closed))
        (Thread/sleep (min 10 (max 0 (- deadline (System/currentTimeMillis)))))
        (recur)))))

(defn- serve-connection!
  [{:keys [opts conns stop-latch] :as srv} handle! ^java.net.Socket sock]
  (let [closed (promise)]
    (swap! conns conj {:sock sock :closed closed})
    (let [src (bencode/byte-source (.getInputStream sock))
          out (.getOutputStream sock)
          lock (Object.)]
      (try
        (loop []
          (when (live? stop-latch closed)
            (let [m (bencode/decode src)]
              (when (and (not= ::bencode/eof m) (live? stop-latch closed))
                (let [reply (fn [kv]
                              (when (and (not (:mute? opts)) (live? stop-latch closed))
                                (locking lock
                                  (.write ^java.io.OutputStream out ^bytes (bencode/encode (merge {"id" (get m "id")} kv)))
                                  (.flush ^java.io.OutputStream out))))
                      result (handle! srv sock stop-latch closed reply m)]
                  (when (and (not= :dropped result) (live? stop-latch closed))
                    (recur)))))))
        (catch Throwable _ nil)
        (finally
          (try (.close sock) (catch Throwable _ nil)))))))

(defn start-server!
  "Starts a server on a free loopback port that hands every request to
  handle!, and returns it. extra is merged into the server map (a fake's
  own state atoms)."
  [opts handle! extra]
  (let [ss (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))
        srv (merge {:opts opts :server ss :port (.getLocalPort ss)
                    :sessions (atom {}) :log (atom []) :counter (atom 0)
                    :conns (atom []) :futures (atom [])
                    :stopped? (atom false) :stop-latch (promise) :busy (atom 0)}
                   extra)]
    (future
      (try
        (loop []
          (let [sock (.accept ss)]
            (if @(:stopped? srv)
              (.close sock)
              (do (swap! (:futures srv) conj (future (serve-connection! srv handle! sock)))
                  (recur)))))
        (catch Throwable _ nil)))
    srv))

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
  quits drops its nREPL clients. Delivers each connection's :closed first,
  so a :slow/:slow-ops wait in flight on it wakes at once instead of
  sleeping out its full ms against a socket that's about to disappear."
  [srv]
  (doseq [{:keys [sock closed]} @(:conns srv)]
    (deliver closed true)
    (try (.close ^java.net.Socket sock) (catch Throwable _ nil)))
  nil)

(defn- await-bounded!
  "Waits up to ms-total for (pred) to become true, polling every 10ms."
  [ms-total pred]
  (let [deadline (+ (System/currentTimeMillis) (long ms-total))]
    (loop []
      (when (and (not (pred)) (< (System/currentTimeMillis) deadline))
        (Thread/sleep 10)
        (recur)))))

(defn stop!
  "Stops accepting and drops every client, and doesn't return until every
  connection's own thread has actually ended (bounded), so a stale thread
  from this server can never answer a socket a later server reuses (jolt's
  socket read/write don't check a :closed flag on their own, so a thread
  that outlived stop! could otherwise still read and reply on a reused
  fd -- measured, 2 of 3 runs). Order: wake every sleeper (the latch),
  wait for in-flight replies to finish (busy), only then close sockets, and
  only then wait for the connection threads themselves to end. Measured on
  Jolt v0.8.13: closing a ServerSocket doesn't wake a thread blocked in
  accept, so this connects once first to let the accept loop see the flag
  and end."
  [srv]
  (reset! (:stopped? srv) true)
  (deliver (:stop-latch srv) true)
  (await-bounded! 1000 (fn [] (zero? @(:busy srv))))
  (try (.close (java.net.Socket. "127.0.0.1" (int (:port srv)))) (catch Throwable _ nil))
  (try (.close ^java.net.ServerSocket (:server srv)) (catch Throwable _ nil))
  (drop-connections! srv)
  (let [deadline (+ (System/currentTimeMillis) 1000)]
    (doseq [fut @(:futures srv)]
      (deref fut (max 0 (- deadline (System/currentTimeMillis))) nil)))
  nil)
