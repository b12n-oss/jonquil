(ns jonquil.cljs-node-launch
  "The launcher Jonquil's ClojureScript-on-node runtime starts in its child
  JVM: clojure -M -i <this file> -m jonquil.cljs-node-launch. JVM Clojure
  only, run inside the child. Jolt never loads it; Jonquil embeds its text
  (jonquil.nrepl.cljs-node-files) and writes it to a cache folder.

  It serves a prepl on a loopback port the OS picks, prints the ready line
  `jonquil-cljs-prepl <port>`, and exits when stdin ends, taking node with it."
  (:require [clojure.core.server :as server]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.net InetAddress ServerSocket)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private script "cljs/repl/node_repl.js")
(def ^:private unpatched ".listen(PORT);")
(def ^:private patched ".listen(PORT, \"127.0.0.1\");")

(defn- script-text [] (slurp (io/resource script)))

(defn- loopback-loader
  "A classloader answering node_repl.js with a copy that listens on
  127.0.0.1 only; every other resource comes from parent."
  [^ClassLoader parent]
  (let [dir (.toFile (Files/createTempDirectory "jonquil-cljs-" (make-array FileAttribute 0)))
        f (io/file dir script)]
    (io/make-parents f)
    (spit f (str/replace (script-text) unpatched patched))
    (.deleteOnExit f)
    (let [url (.toURL (.toURI f))]
      ;; Child-first for this one name: a plain URLClassLoader asks its
      ;; parent first, which finds the jar's unpatched script.
      (proxy [ClassLoader] [parent]
        (getResource [n]
          (if (= n script) url (proxy-super getResource n)))))))

(defn- free-port []
  (with-open [s (ServerSocket. 0 0 (InetAddress/getByName "127.0.0.1"))]
    (.getLocalPort s)))

(defn accept
  "Per connection: a ClojureScript prepl on node, bound to loopback."
  []
  (let [t (Thread/currentThread)]
    (.setContextClassLoader t (loopback-loader (.getContextClassLoader t)))
    (require 'cljs.server.node)
    ((resolve 'cljs.server.node/prepl) {:env-opts {:host "127.0.0.1" :port (free-port)}})))

(defn- destroy-descendants []
  (doseq [p (iterator-seq (.iterator (.descendants (java.lang.ProcessHandle/current))))]
    (.destroy ^java.lang.ProcessHandle p)))

(defn -main []
  (when-not (str/includes? (script-text) unpatched)
    (binding [*out* *err*]
      (println (str "jonquil: " script " has no " unpatched
                    " to bind to loopback; this ClojureScript version isn't supported")))
    (System/exit 2))
  (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable destroy-descendants))
  ;; Jonquil holds our stdin for its life, so stdin ending means it is gone.
  (future (try (let [in System/in] (loop [] (when-not (neg? (.read in)) (recur))))
               (catch Throwable _ nil))
          (System/exit 0))
  (let [ss (server/start-server {:name "jonquil-cljs" :address "127.0.0.1" :port 0
                                 :accept 'jonquil.cljs-node-launch/accept})]
    (println "jonquil-cljs-prepl" (.getLocalPort ^ServerSocket ss))
    (flush)
    @(promise)))
