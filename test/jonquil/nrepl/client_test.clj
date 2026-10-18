(ns jonquil.nrepl.client-test
  "connect's bounded timeout. hang-endpoint makes a connect that never
  finishes deterministic: a ServerSocket bound to loopback with backlog
  1, its backlog filled by fillers nobody accepts, so the next
  connect to it hangs until the caller's own timeout fires (measured
  against jolt v0.8.15's connect timeout, jolt-lang/jolt#1192)."
  (:require [clojure.test :refer [deftest is]]
            [jonquil.nrepl.client :as client]))

(defn- hang-endpoint
  "[server fillers port]. Opens filler sockets to the backlog-1 server,
  each with a 200 ms connect timeout, until one times out: the backlog is
  then full, so the next connect hangs. macOS refuses past the first
  filler, but Linux completes more handshakes than the backlog, so the
  number of fillers varies. Gives up after 64. The caller closes the
  server and every filler."
  []
  (let [server (java.net.ServerSocket. 0 1 (java.net.InetAddress/getByName "127.0.0.1"))
        port (.getLocalPort server)
        addr (java.net.InetSocketAddress. "127.0.0.1" port)]
    (loop [fillers []]
      (when (>= (count fillers) 64)
        (doseq [f fillers] (.close f))
        (.close server)
        (throw (ex-info "couldn't fill the backlog" {:port port})))
      (let [f (java.net.Socket.)
            fillers (conj fillers f)
            timed-out? (try (.connect f addr 200) false
                            (catch java.net.SocketTimeoutException _ true))]
        (if timed-out?
          [server fillers port]
          (recur fillers))))))

(deftest connect-times-out-on-a-hanging-endpoint-test
  (let [[server fillers port] (hang-endpoint)]
    (try
      (let [t0 (System/currentTimeMillis)]
        (try
          (client/connect port 300)
          (is false "expected connect to throw")
          (catch java.io.IOException e
            (let [elapsed (- (System/currentTimeMillis) t0)]
              (is (instance? java.net.SocketTimeoutException e))
              (is (>= elapsed 300) "didn't give up before the timeout")
              (is (< elapsed 2000) "well within the timeout plus a margin, not the OS's own retry")))))
      (finally
        (doseq [f fillers] (.close f))
        (.close server)))))

(deftest connect-to-a-refused-port-fails-promptly-test
  (let [dead (java.net.ServerSocket. 0 1 (java.net.InetAddress/getByName "127.0.0.1"))
        port (.getLocalPort dead)]
    (.close dead)
    (let [t0 (System/currentTimeMillis)]
      (try
        (client/connect port 5000)
        (is false "expected connect to throw")
        (catch java.io.IOException e
          (is (instance? java.net.ConnectException e))
          (is (< (- (System/currentTimeMillis) t0) 1000)
              "a refused port fails at once, never waiting out the timeout"))))))

(defn- open-fd-count
  "This process's own open descriptor count, via /dev/fd (works inside
  Jolt, measured). A regression guard: a failed connect must never leave
  the socket it opened behind."
  []
  (count (.list (java.io.File. "/dev/fd"))))

(deftest connect-failure-closes-its-socket-test
  (let [dead (java.net.ServerSocket. 0 1 (java.net.InetAddress/getByName "127.0.0.1"))
        port (.getLocalPort dead)]
    (.close dead)
    (let [before (open-fd-count)]
      (dotimes [_ 50]
        (try (client/connect port 2000) (catch Throwable _ nil)))
      (is (= before (open-fd-count))
          "50 failed connects leave the open-descriptor count unchanged"))))

(deftest connect-to-reaches-a-named-host-test
  (let [server (java.net.ServerSocket. 0 1 (java.net.InetAddress/getByName "127.0.0.1"))
        port (.getLocalPort server)
        checked (promise)
        ;; The listener holds the socket until the test has looked: closing
        ;; it at once raced the client's reader, which saw EOF and marked
        ;; the connection closed before open? ran (caught by a 25-run loop,
        ;; 2026-10-18, failing once).
        accepted (future (with-open [s (.accept server)]
                           (deref checked 5000 nil)
                           (.isConnected s)))]
    (try
      (let [conn (client/connect-to "localhost" port 5000)]
        (is (client/open? conn))
        (deliver checked true)
        (is (true? (deref accepted 5000 :timeout)) "the listener on that host accepted it")
        (client/close! conn))
      (finally (deliver checked true) (.close server)))))

(deftest connect-to-an-unknown-host-fails-promptly-test
  (let [t0 (System/currentTimeMillis)]
    (try
      (client/connect-to "no-such-host.invalid" 1667 5000)
      (is false "expected connect-to to throw")
      (catch java.io.IOException _
        (is (< (- (System/currentTimeMillis) t0) 5000) "fails without waiting forever")))))
