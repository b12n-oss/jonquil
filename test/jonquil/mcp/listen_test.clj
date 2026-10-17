(ns jonquil.mcp.listen-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [jonquil.evaluator :as ev]
            [jonquil.mcp :as mcp]
            [jonquil.mcp.json :as json]
            [jonquil.mcp.listen :as listen]
            [temp-dirs])
  (:import (java.lang ProcessHandle)
           (java.net ConnectException InetAddress Socket)))

(use-fixtures :once temp-dirs/fixture)

(defn- settings-path []
  (str (temp-dirs/temp-dir! "jonquil-mcp-listen-test-") "/settings.edn"))

(defn- own-pid [] (.pid (ProcessHandle/current)))

(defn- evaluator []
  #_{:clj-kondo/ignore [:missing-protocol-method]}
  (reify ev/Evaluator
    (evaluating? [_] false)
    (waiting-for-input? [_] false)))

(defn- fake-ui []
  {:mcp-eval!* (fn [_ notify]
                 (notify {:done {:result {:status :ok :printed "3"} :output ""}})
                 (fn [] ""))
   :evaluator* (fn [] (evaluator))})

(defn- start! [] (listen/start-listener! {:ui (fake-ui) :dir "/tmp" :gui (fn [f] (f)) :settings-path (settings-path)}))

(defn- connect [port]
  (let [s (Socket. (InetAddress/getByName "127.0.0.1") (int port))]
    (.setSoTimeout s 10000)
    {:socket s
     :in (io/reader (.getInputStream s) :encoding "UTF-8")
     :out (io/writer (.getOutputStream s) :encoding "UTF-8")}))

(defn- send! [c m]
  (.write ^java.io.Writer (:out c) (str (json/encode m) "\n"))
  (.flush ^java.io.Writer (:out c)))

(defn- recv! [c] (json/decode (.readLine ^java.io.BufferedReader (:in c))))

(def ^:private init-msg
  {"jsonrpc" "2.0" "id" 1 "method" "initialize"
   "params" {"protocolVersion" "2024-11-05" "capabilities" {} "clientInfo" {"name" "t" "version" "0"}}})

(deftest registry-dir-under-settings
  (is (= "/x/jonquil/mcp" (listen/registry-dir "/x/jonquil/settings.edn"))))

(deftest registry-round-trip
  (let [sp (settings-path)
        pid (own-pid)]
    (listen/write-entry! sp {:dir "/d" :port 4242 :pid pid})
    (let [es (listen/live-entries sp)]
      (is (= 1 (count es)))
      (is (= {:dir "/d" :port 4242 :pid pid} (select-keys (first es) [:dir :port :pid])))
      (is (number? (:file-mtime (first es)))))
    (listen/delete-entry! sp pid)
    (is (empty? (listen/live-entries sp)))))

(deftest live-entries-filters-dead
  (let [sp (settings-path)]
    (listen/write-entry! sp {:dir "/d" :port 1 :pid 999999})
    (listen/write-entry! sp {:dir "/e" :port 2 :pid (own-pid)})
    (is (= [(own-pid)] (map :pid (listen/live-entries sp))))))

(deftest housekeep-removes-dead
  (let [sp (settings-path)
        dead (io/file (listen/registry-dir sp) "999999.edn")
        live (io/file (listen/registry-dir sp) (str (own-pid) ".edn"))]
    (listen/write-entry! sp {:dir "/d" :port 1 :pid 999999})
    (listen/write-entry! sp {:dir "/e" :port 2 :pid (own-pid)})
    (listen/housekeep! sp)
    (is (not (.exists dead)))
    (is (.exists live))))

(deftest listener-serves-a-real-socket
  (let [l (start!)
        c (connect (:port l))]
    (try
      (send! c init-msg)
      (is (= "2024-11-05" (get-in (recv! c) ["result" "protocolVersion"])))
      (send! c {"jsonrpc" "2.0" "id" 2 "method" "tools/call"
                "params" {"name" "jonquil_eval" "arguments" {"code" "(+ 1 2)"}}})
      (let [text (get-in (recv! c) ["result" "content" 0 "text"])]
        (is (str/ends-with? text "=> 3") text))
      (finally (.close (:socket c)) ((:stop! l))))))

(deftest detach-then-second-client
  (let [l (start!)
        c1 (connect (:port l))]
    (try
      (send! c1 init-msg)
      (recv! c1)
      (.close (:socket c1))
      (let [c2 (connect (:port l))]
        (try
          (send! c2 init-msg)
          (is (= "2024-11-05" (get-in (recv! c2) ["result" "protocolVersion"])))
          (finally (.close (:socket c2)))))
      (finally ((:stop! l))))))

(deftest listener-survives-serve-throw
  (let [err (java.io.StringWriter.)
        calls (atom 0)
        real mcp/serve!]
    (binding [*err* err]
      (with-redefs [mcp/serve! (fn [m]
                                 (when (= 1 (swap! calls inc))
                                   (throw (ex-info "boom from serve" {})))
                                 (real m))]
        (let [l (start!)]
          (try
            (let [c1 (connect (:port l))]
              ;; the throw closes the connection: the client reads EOF
              (is (nil? (.readLine ^java.io.BufferedReader (:in c1))))
              (.close (:socket c1)))
            (let [c2 (connect (:port l))]
              (try
                (send! c2 init-msg)
                (is (= "2024-11-05" (get-in (recv! c2) ["result" "protocolVersion"])))
                (finally (.close (:socket c2))))
              (is (str/includes? (str err) "boom from serve")))
            (finally ((:stop! l)))))))))

(deftest stop-deletes-entry-and-refuses
  (let [sp (settings-path)
        l (listen/start-listener! {:ui (fake-ui) :dir "/tmp" :gui (fn [f] (f)) :settings-path sp})
        entry (io/file (listen/registry-dir sp) (str (own-pid) ".edn"))]
    (is (.exists entry))
    ((:stop! l))
    ((:stop! l))
    (is (not (.exists entry)))
    (is (thrown? ConnectException (connect (:port l))))))
