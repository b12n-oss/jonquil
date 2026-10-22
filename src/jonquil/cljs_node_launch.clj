(ns jonquil.cljs-node-launch
  "The launcher Jonquil's ClojureScript-on-node runtime starts in its child
  JVM: clojure -M -i <this file> -m jonquil.cljs-node-launch. JVM Clojure
  only, run inside the child. Jolt never loads it; Jonquil embeds its text
  (jonquil.nrepl.cljs-node-files) and writes it to a cache folder.

  It serves a prepl on a loopback port the OS picks, prints the ready line
  `jonquil-cljs-prepl <port>`, and exits when stdin ends, taking node with it.
  node picks its own port too: it binds port 0 and writes the port it got
  to a file the patched create-socket reads, so no other process can take
  the port between a probe and node's bind."
  (:require [clojure.core.server :as server]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.net InetAddress ServerSocket)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private script "cljs/repl/node_repl.js")
(def ^:private unpatched ".listen(PORT);")

(defn- script-text [] (slurp (io/resource script)))

(def ^:private run-dir
  "This JVM's own folder for the patched script and node's port files."
  (delay (.toFile (Files/createTempDirectory "jonquil-cljs-" (make-array FileAttribute 0)))))

(defn- port-file
  "Where node writes the port it bound for the env whose :port is token."
  [token]
  (io/file @run-dir (str "port-" token)))

(defn- patched
  "The listen call that binds node to 127.0.0.1 on a port the OS picks, then
  writes that port to port-<PORT> in dir through a rename, so a reader never
  sees half the number. PORT is the env's token, not a port."
  [^java.io.File dir]
  (str ".listen(0, \"127.0.0.1\", function () {"
       " var fs = require(\"fs\");"
       " var f = " (pr-str (str (.getPath dir) "/port-")) " + PORT;"
       " fs.writeFileSync(f + \".tmp\", String(this.address().port));"
       " fs.renameSync(f + \".tmp\", f); });"))

(def ^:private patched-url
  "The patched script, written once per JVM. deleteOnExit runs in reverse
  registration order, so the folders are registered outermost first and the
  file last: the file goes first, then each folder is empty when its turn
  comes."
  (delay
    (let [dir @run-dir
          f (io/file dir script)]
      (io/make-parents f)
      (doseq [d (reverse (take-while some? (iterate #(.getParentFile ^java.io.File %) (.getParentFile f))))
              :when (.startsWith (.getPath ^java.io.File d) (.getPath dir))]
        (.deleteOnExit ^java.io.File d))
      (spit f (str/replace (script-text) unpatched (patched dir)))
      (.deleteOnExit f)
      (.toURL (.toURI f)))))

(defn- loopback-loader
  "A classloader answering node_repl.js with a copy that listens on
  127.0.0.1 only; every other resource comes from parent."
  [^ClassLoader parent]
  (let [url @patched-url]
    ;; Child-first for this one name: a plain URLClassLoader asks its
    ;; parent first, which finds the jar's unpatched script.
    (proxy [ClassLoader] [parent]
      (getResource [n]
        (if (= n script) url (proxy-super getResource n))))))

(def ^:private next-token (atom 0))

(defn accept
  "Per connection: a ClojureScript prepl on node, bound to loopback."
  []
  (let [t (Thread/currentThread)]
    (.setContextClassLoader t (loopback-loader (.getContextClassLoader t)))
    (require 'cljs.server.node)
    ((resolve 'cljs.server.node/prepl) {:env-opts {:host "127.0.0.1" :port (swap! next-token inc)}})))

(defn- port-file-socket!
  "Replaces cljs.repl.node/create-socket, which setup calls every 50 ms until
  node answers, with one that reads the port node wrote for this env's token
  and connects to it. A missing file throws, as a refused connect does, so
  setup tries again. The file goes once the connect succeeds. Exits 2 when
  the function isn't the one this was written against."
  []
  (require 'cljs.repl.node)
  (let [v (resolve 'cljs.repl.node/create-socket)
        orig @v]
    (when-not (= '([host port]) (:arglists (meta v)))
      (binding [*out* *err*]
        (println (str "jonquil: cljs.repl.node/create-socket has arglists " (:arglists (meta v))
                      ", not ([host port]); this ClojureScript version isn't supported")))
      (System/exit 2))
    (alter-var-root
     v
     (fn [_]
       (fn [host token]
         (let [f (port-file token)
               port (or (parse-long (str/trim (slurp f)))
                        (throw (ex-info "no port in node's port file" {:file (str f)})))
               s (orig host port)]
           (.delete ^java.io.File f)
           s))))))

(def ^:private http-port (atom nil))

(def ^:private prebound
  "The HTTP ServerSocket bound in -main before the ready line, so a taken
  port is reported at start. The replaced cljs.repl.server/start takes it
  the first time it runs and clears the atom; later calls bind afresh."
  (atom nil))

(defn browser-accept
  "Per connection: a ClojureScript prepl on the browser REPL. The HTTP server
  comes up on first use (cljs.repl.server/start, replaced to bind loopback)
  and no browser is launched: Jonquil opens the page."
  []
  (require 'cljs.server.browser)
  ((resolve 'cljs.server.browser/prepl)
   {:env-opts {:host "127.0.0.1" :port @http-port :launch-browser false}}))

(defn- loopback-start!
  "Replaces cljs.repl.server/start with the same body bound to 127.0.0.1
  (ClojureScript's own listens on every interface). Exits 2 when the
  function isn't the one this was written against."
  []
  (require 'cljs.repl.server)
  (let [v (resolve 'cljs.repl.server/start)
        loop-var (resolve 'cljs.repl.server/server-loop)
        state-var (resolve 'cljs.repl.server/state)]
    (when-not (= '([opts]) (:arglists (meta v)))
      (binding [*out* *err*]
        (println (str "jonquil: cljs.repl.server/start has arglists " (:arglists (meta v))
                      ", not ([opts]); this ClojureScript version isn't supported")))
      (System/exit 2))
    (alter-var-root
     v
     (fn [_]
       (fn [opts]
         (let [[old _] (reset-vals! prebound nil)
               ss (or old (ServerSocket. (int (:port opts)) 50 (InetAddress/getByName "127.0.0.1")))]
           (.start (Thread. ((ns-resolve 'clojure.core 'binding-conveyor-fn)
                             (fn [] (@loop-var opts ss)))))
           (swap! @state-var (fn [old] (assoc old :socket ss :port (:port opts))))))))))

(defn- destroy-descendants []
  (doseq [p (iterator-seq (.iterator (.descendants (java.lang.ProcessHandle/current))))]
    (.destroy ^java.lang.ProcessHandle p)))

(defn -main
  "No args: ClojureScript on node. [\"browser\" http-port]: the browser REPL."
  [& args]
  (let [browser? (= "browser" (first args))]
    (if browser?
      (let [port (some-> (second args) parse-long)]
        (when-not port
          (binding [*out* *err*]
            (println "usage: jonquil.cljs-node-launch browser <http-port>"))
          (System/exit 2))
        (reset! http-port port)
        (try (reset! prebound (ServerSocket. (int port) 50 (InetAddress/getByName "127.0.0.1")))
             (catch java.io.IOException _
               (println (str "jonquil-cljs-http-port-taken " port))
               (flush)
               (System/exit 3)))
        (loopback-start!))
      (do (when-not (str/includes? (script-text) unpatched)
            (binding [*out* *err*]
              (println (str "jonquil: " script " has no " unpatched
                            " to bind to loopback; this ClojureScript version isn't supported")))
            (System/exit 2))
          (port-file-socket!)))
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable destroy-descendants))
    ;; Jonquil holds our stdin for its life, so stdin ending means it is gone.
    (future (try (let [in System/in] (loop [] (when-not (neg? (.read in)) (recur))))
                 (catch Throwable _ nil))
            (System/exit 0))
    (let [ss (server/start-server {:name "jonquil-cljs" :address "127.0.0.1" :port 0
                                   :accept (if browser?
                                             'jonquil.cljs-node-launch/browser-accept
                                             'jonquil.cljs-node-launch/accept)})]
      (println (str "jonquil-cljs-prepl " (.getLocalPort ^ServerSocket ss)
                    (when browser? (str " http://127.0.0.1:" @http-port))))
      (flush)
      @(promise))))
