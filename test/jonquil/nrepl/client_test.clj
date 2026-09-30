(ns jonquil.nrepl.client-test
  "connect's bounded timeout. hang-once makes a connect that never
  finishes deterministic: a ServerSocket bound to loopback with backlog
  1, its one slot already taken by a filler nobody accepts, so the next
  connect to it hangs until the caller's own timeout fires (measured
  against jolt v0.8.15's connect timeout, jolt-lang/jolt#1192)."
  (:require [clojure.test :refer [deftest is]]
            [jonquil.nrepl.client :as client]))

(defn- hang-once
  "[server filler port]. server and filler must both be closed by the
  caller once the probing connect is done with them."
  []
  (let [server (java.net.ServerSocket. 0 1 (java.net.InetAddress/getByName "127.0.0.1"))
        port (.getLocalPort server)
        filler (java.net.Socket.)]
    (.connect filler (java.net.InetSocketAddress. "127.0.0.1" port) 1000)
    [server filler port]))

(deftest connect-times-out-on-a-hanging-endpoint-test
  (let [[server filler port] (hang-once)]
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
        (.close filler)
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
