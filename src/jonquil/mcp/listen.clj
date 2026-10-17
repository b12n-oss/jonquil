(ns jonquil.mcp.listen
  "The MCP listener: lets an MCP client attach to a window that is already
  open. `start-listener!` binds a loopback ServerSocket on an OS-picked port,
  writes a registry entry `<settings-dir>/mcp/<pid>.edn` ({:dir :port :pid})
  so a client can find the window, and serves one connection at a time with
  the unchanged jonquil.mcp/serve! over the window tools. The tool table is
  built once per connection, so nothing is cached across clients.

  Registry entries die with their process only if the process exits cleanly,
  so `live-entries` filters on the pid being alive and `housekeep!` deletes
  the rest."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jonquil.mcp :as mcp]
            [jonquil.mcp.window :as mcp-window])
  (:import (java.lang ProcessHandle)
           (java.net InetAddress ServerSocket SocketException)))

;; --- registry ----------------------------------------------------------------

(defn registry-dir
  "The registry directory beside settings-path: <settings-dir>/mcp."
  [settings-path]
  (str (.getParent (io/file settings-path)) "/mcp"))

(defn- entry-file [settings-path pid]
  (io/file (registry-dir settings-path) (str pid ".edn")))

(defn write-entry!
  "Writes <pid>.edn in the registry, creating the directory."
  [settings-path {:keys [dir port pid]}]
  (let [f (entry-file settings-path pid)]
    (.mkdirs (.getParentFile f))
    (spit f (pr-str {:dir dir :port port :pid pid}))))

(defn delete-entry!
  "Deletes pid's entry; a missing one is fine."
  [settings-path pid]
  (.delete (entry-file settings-path pid)))

(defn- alive?
  "Whether a process with pid is running."
  [pid]
  (let [o (ProcessHandle/of (long pid))]
    (and (.isPresent o) (.isAlive (.get o)))))

(defn- read-entry [^java.io.File f]
  (try
    (let [m (edn/read-string (slurp f))]
      (when (and (map? m) (integer? (:pid m)))
        (assoc m :file-mtime (.lastModified f))))
    (catch Exception _ nil)))

(defn- entry-files [settings-path]
  (->> (.listFiles (io/file (registry-dir settings-path)))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".edn"))))

(defn live-entries
  "The registry's entries whose pid is alive, each with :file-mtime (the
  entry file's modification time, for picking the newest). An unreadable
  entry is skipped."
  [settings-path]
  (->> (entry-files settings-path)
       (keep read-entry)
       (filter #(alive? (:pid %)))))

(defn housekeep!
  "Deletes the registry files whose pid is dead."
  [settings-path]
  (doseq [^java.io.File f (entry-files settings-path)]
    (let [e (read-entry f)]
      (when (and e (not (alive? (:pid e))))
        (.delete f)))))

;; --- the listener ------------------------------------------------------------

(defn- log [& parts]
  (binding [*out* *err*]
    (apply println "jonquil: MCP listener:" parts)))

(defn- serve-connection!
  "Serves one accepted socket until the client's EOF, then closes it. A throw
  out of serve! logs one stderr line; the caller accepts the next client."
  [sock {:keys [ui dir gui]}]
  (try
    (let [in (io/reader (.getInputStream sock) :encoding "UTF-8")
          out (io/writer (.getOutputStream sock) :encoding "UTF-8")]
      (mcp/serve! {:tools (mcp-window/make-window-tools
                           {:ui ui :gui gui :dir (or dir (System/getProperty "user.dir"))})
                   :read-line (fn [] (.readLine ^java.io.BufferedReader in))
                   :write-line (fn [s]
                                 (.write ^java.io.Writer out (str s "\n"))
                                 (.flush ^java.io.Writer out))}))
    (catch Throwable e
      (log "a connection failed:" (or (ex-message e) (str e))))
    (finally
      (try (.close sock) (catch Throwable _ nil)))))

(defn start-listener!
  "Binds 127.0.0.1 on an OS-picked port, writes this process's registry
  entry, runs housekeeping, and accepts clients one at a time on a future.
  Returns {:port p :stop! (fn [])}; stop! closes the socket (ending the
  accept loop through the SocketException that wakes it) and deletes the
  entry, and is safe to call twice. :dir defaults to user.dir; :gui is the
  enqueue fn handed to make-window-tools (default: the real GTK one, tests
  pass a synchronous one)."
  [{:keys [ui dir gui settings-path]}]
  (let [ss (ServerSocket. 0 50 (InetAddress/getByName "127.0.0.1"))
        port (.getLocalPort ss)
        pid (.pid (ProcessHandle/current))
        conn-opts {:ui ui :dir dir :gui gui}]
    (write-entry! settings-path {:dir (or dir (System/getProperty "user.dir")) :port port :pid pid})
    (housekeep! settings-path)
    (future
      (loop []
        (let [sock (try (.accept ss)
                        (catch SocketException e
                          (when-not (.isClosed ss) (log "accept failed:" (ex-message e)))
                          nil))]
          (cond
            sock (do (serve-connection! sock conn-opts) (recur))
            (.isClosed ss) nil
            :else (recur)))))
    {:port port
     :stop! (fn []
              (try (.close ss) (catch Throwable _ nil))
              (delete-entry! settings-path pid))}))
