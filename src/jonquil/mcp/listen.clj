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
           (java.net InetAddress ServerSocket Socket)))

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
  ;; Through a temp file and a rename, so an --mcp-attach reading while
  ;; set-dir! rewrites the entry never sees half of it.
  (let [f (entry-file settings-path pid)
        tmp (io/file (.getParentFile f) (str "." pid ".edn.tmp"))]
    (.mkdirs (.getParentFile f))
    (spit tmp (pr-str {:dir dir :port port :pid pid}))
    (when-not (.renameTo tmp f)
      (.delete tmp)
      (spit f (pr-str {:dir dir :port port :pid pid})))))

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
      (when (and (map? m) (integer? (:pid m)) (pos? (:pid m)))
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

(defn- log-connection!
  "One :connection entry in the window's Messages, when the ui keeps them."
  [ui summary]
  (when-let [log! (:log!* ui)]
    (try (log! {:kind :connection :summary summary})
         (catch Throwable e (log "couldn't log a connection:" (or (ex-message e) (str e)))))))

(defn- serve-connection!
  "Serves one accepted socket until the client's EOF, then closes it. A throw
  out of serve! logs one stderr line; the caller accepts the next client.
  The window's Messages get the client's attach (on its initialize, named
  by its clientInfo) and its leave, numbered from conn-count*."
  [sock {:keys [ui dir-fn gui conn-count*]}]
  (let [n (swap! (or conn-count* (atom 0)) inc)
        session* (atom {})]
    (try
      (let [in (io/reader (.getInputStream sock) :encoding "UTF-8")
            out (io/writer (.getOutputStream sock) :encoding "UTF-8")]
        (mcp/serve! {:tools (mcp-window/with-messages
                             (mcp-window/with-plugin-tools
                              (mcp-window/make-window-tools
                               {:ui ui :gui gui :dir (or (dir-fn) (System/getProperty "user.dir"))})
                              ui)
                             ui session*)
                     :on-initialize (fn [ci]
                                      (let [c (mcp-window/client-name ci)]
                                        (swap! session* assoc :client c)
                                        (log-connection! ui (str c " attached (connection " n ")"))))
                     :subscribe (:watch-plugin-tools* ui)
                     :read-line (fn [] (.readLine ^java.io.BufferedReader in))
                     :write-line (fn [s]
                                   (.write ^java.io.Writer out (str s "\n"))
                                   (.flush ^java.io.Writer out))}))
      (catch Throwable e
        (log "a connection failed:" (or (ex-message e) (str e))))
      (finally
        (try (.close sock) (catch Throwable _ nil))
        (log-connection! ui (str (or (:client @session*) "An MCP client") " left (connection " n ")"))))))

(def ^:private accept-retry-ms
  "Pause after a failed accept, so a persistent error doesn't spin. estimate:
  long enough to stop a hot loop, short enough not to hide a transient."
  250)

(def ^:private max-accept-failures
  "Consecutive failed accepts after which the listener gives up. estimate."
  5)

(defn- accept-conn
  "Blocks for the next client socket. A seam for tests."
  [^ServerSocket ss]
  (.accept ss))

(defn- accept-loop!
  "Accepts and serves clients one at a time until ss is closed (silent: that
  is stop!). Any other failure logs one line and retries after a pause; after
  max-accept-failures in a row it logs, closes ss, runs give-up! (which
  unregisters) and exits. A successful accept resets the count."
  [^ServerSocket ss conn-opts give-up!]
  (loop [fails 0]
    (let [r (try (serve-connection! (accept-conn ss) conn-opts)
                 :ok
                 (catch Throwable e
                   (if (.isClosed ss)
                     :closed
                     (do (log "accept failed:" (or (ex-message e) (str e)))
                         :failed))))]
      (case r
        :ok (recur 0)
        :closed nil
        :failed (if (>= (inc fails) max-accept-failures)
                  (do (log "giving up after" max-accept-failures "failed accepts")
                      (try (.close ss) (catch Throwable _ nil))
                      (give-up!))
                  (do (Thread/sleep (long accept-retry-ms))
                      (recur (inc fails))))))))

(defn start-listener!
  "Binds 127.0.0.1 on an OS-picked port, writes this process's registry
  entry, runs housekeeping, and accepts clients one at a time on a future.
  Returns {:port p :stop! (fn []) :set-dir! (fn [dir])}; set-dir! (the window
  re-rooted, follow-root!) rewrites the entry's :dir, so attaching by folder
  follows the window, and each later client is served that folder; stop!
  closes the socket (ending the accept loop) and deletes the entry, and is
  safe to call twice, after which set-dir! writes nothing; a connection already being
  served outlives stop! (the entry is gone but the session runs on) and ends
  with its client or the process. If a step
  after the bind throws, the socket is closed and the exception rethrown. If
  the accept loop gives up, it closes the socket and deletes the entry too.
  :dir defaults to user.dir; :gui is the enqueue fn handed to
  make-window-tools (default: the real GTK one, tests pass a synchronous one)."
  [{:keys [ui dir gui settings-path]}]
  (let [ss (ServerSocket. 0 50 (InetAddress/getByName "127.0.0.1"))
        port (.getLocalPort ss)
        pid (.pid (ProcessHandle/current))
        dir* (atom dir)
        stopped? (atom false)
        stop! (fn []
                (reset! stopped? true)
                (try (.close ss) (catch Throwable _ nil))
                (delete-entry! settings-path pid))
        set-dir! (fn [d]
                   (reset! dir* d)
                   (when-not @stopped?
                     (write-entry! settings-path {:dir d :port port :pid pid})))]
    (try
      (write-entry! settings-path {:dir (or dir (System/getProperty "user.dir")) :port port :pid pid})
      (housekeep! settings-path)
      (catch Throwable e
        (try (.close ss) (catch Throwable _ nil))
        (throw e)))
    (future (accept-loop! ss {:ui ui :dir-fn (fn [] @dir*) :gui gui :conn-count* (atom 0)} stop!))
    {:port port :stop! stop! :set-dir! set-dir!}))

(defn follow-root!
  "Keeps listener l's folder on the window's tree root: watches tree-state
  (the file tree's state atom, whose :root an Open Folder re-root changes)
  and calls l's set-dir! with the new root's canonical path. A failure is
  said on stderr; the window carries on. Returns a fn that removes the
  watch."
  [tree-state l]
  (add-watch tree-state ::follow-root
             (fn [_ _ old new]
               (let [r (:root new)]
                 (when (and r (not= (:root old) r))
                   (try ((:set-dir! l) (.getCanonicalPath (io/file r)))
                        (catch Throwable e
                          (log "couldn't follow the new folder:" (or (ex-message e) (str e)))))))))
  (fn [] (remove-watch tree-state ::follow-root)))

;; --- discovery and the --mcp-attach bridge -----------------------------------

(defn- canonical [dir]
  (.getCanonicalPath (io/file dir)))

(defn- window-list
  "\"<dir> (port N), ...\" for every entry."
  [entries]
  (str/join ", " (map (fn [e] (str (or (:dir e) "(no folder)") " (port " (:port e) ")")) entries)))

(defn pick-entry
  "Chooses the window to attach to: {:entry e} or {:error sentence}. With a
  dir, the entries whose canonical dir equals the canonical dir (the newest
  by :file-mtime if several). Without one, the only entry. No match, no
  entry, or several with no dir gives an error sentence that lists each live
  window's dir and port."
  [entries dir]
  (let [hits (if dir
               (let [d (canonical dir)]
                 (filter #(and (:dir %) (= d (canonical (:dir %)))) entries))
               (when (= 1 (count entries)) entries))]
    (cond
      (seq hits) {:entry (apply max-key :file-mtime hits)}
      (empty? entries) {:error "no Jonquil window is listening. Open one with :mcp-listen? true in settings.edn."}
      dir {:error (str "no Jonquil window has " (canonical dir) " open. Open windows: " (window-list entries))}
      :else {:error (str "several Jonquil windows are listening, so name a directory: "
                         (window-list entries))})))

(defn discover
  "pick-entry over the registry's live entries."
  [settings-path dir]
  (pick-entry (live-entries settings-path) dir))

(defn bridge!
  "Relays stdio lines to a listening window and back. Returns the exit code:
  1 (sentence on *err*) when no window can be found or connected, else 0 once
  either side reaches EOF. Both pumps run on daemon threads and either end
  releases the wait, so the bridge cannot hang on one dead side; the socket
  is closed on the way out."
  [{:keys [read-line write-line settings-path dir]}]
  (let [{:keys [entry error]} (try (discover settings-path dir)
                                   (catch Exception e {:error (str "cannot read the window registry: " (ex-message e))}))
        sock (when entry
               (try (Socket. (InetAddress/getByName "127.0.0.1") (int (:port entry)))
                    (catch Exception e
                      (binding [*out* *err*]
                        (println (str "jonquil: cannot connect to the window on port " (:port entry)
                                      ": " (ex-message e))))
                      nil)))]
    (cond
      error (do (binding [*out* *err*] (println (str "jonquil: " error))) 1)
      (nil? sock) 1
      :else
      (let [done (promise)
            closing (atom false)
            err *err*
            in (io/reader (.getInputStream ^Socket sock) :encoding "UTF-8")
            out (io/writer (.getOutputStream ^Socket sock) :encoding "UTF-8")
            pump (fn [f] (doto (Thread. ^Runnable (fn [] (try (f)
                                                              (catch Throwable e
                                                                (when-not @closing
                                                                  (binding [*out* err]
                                                                    (println "jonquil: the attach relay failed:" (or (ex-message e) (str e))))))
                                                              (finally (deliver done 0)))))
                           (.setDaemon true)
                           (.start)))]
        (pump (fn [] (loop []
                       (when-let [l (read-line)]
                         (.write ^java.io.Writer out (str l "\n"))
                         (.flush ^java.io.Writer out)
                         (recur)))))
        (pump (fn [] (loop []
                       (when-let [l (.readLine ^java.io.BufferedReader in)]
                         (write-line l)
                         (recur)))))
        @done
        (reset! closing true)
        (try (.close ^Socket sock) (catch Throwable _ nil))
        0))))
