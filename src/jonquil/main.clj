(ns jonquil.main
  "Jonquil's entry point: delegates to jonquil.gtk.window, which builds the
  window and blocks in the GTK main loop."
  (:require [clojure.java.io :as io]
            [jonquil.gtk.window :as window]
            [jonquil.login-path :as login-path]
            [jonquil.mcp :as mcp]
            [jonquil.mcp.listen :as listen]
            [jonquil.mcp.tools :as mcp-tools]
            [jonquil.mcp.window :as mcp-window]
            [jonquil.plugin-loader :as plugin-loader]
            [jonquil.runtime-registry :as registry]
            [jonquil.settings :as settings]))

(defn start!
  "Builds the Jonquil window and blocks in the GTK main loop. opts is
  {:file path-or-nil :dir path-or-nil :cwd path-or-nil :argv [\"jonquil\" ...]
  :on-ready (fn [ui]) :settings-path path-or-nil :plugins? bool}
  (:settings-path defaults to (jonquil.settings/settings-path);
  :plugins? false starts with no plugins, --no-plugins); see
  jonquil.gtk.window/start! for ui's shape."
  [opts]
  (window/start! opts))

(defn gtk-argv
  "The argv handed to g_application_run: \"jonquil\", then every arg made
  absolute against cwd. Absolute paths can't start with a dash, so GTK's
  option parser never sees a file named like an option (--help). Pure: no
  filesystem check, no canonicalisation."
  [args cwd]
  (into ["jonquil"]
        (map (fn [a] (let [f (io/file a)]
                       (if (.isAbsolute f) a (.getAbsolutePath (io/file cwd a)))))
             args)))

(defn mcp-args
  "{:dir abs-dir :window? bool} when the first arg is \"--mcp\", else nil. An
  optional \"--window\" next selects the live-window server; the optional
  directory after it is made absolute against the working directory, else the
  working directory itself. Pure apart from reading user.dir."
  [args]
  (when (= "--mcp" (first args))
    (let [cwd (System/getProperty "user.dir")
          window? (= "--window" (second args))
          d (nth args (if window? 2 1) nil)]
      {:window? window?
       :dir (cond
              (nil? d) cwd
              (.isAbsolute (io/file d)) d
              :else (.getAbsolutePath (io/file cwd d)))})))

(defn attach-args
  "{:dir abs-dir-or-nil} when the first arg is \"--mcp-attach\", else nil. The
  optional directory after it is made absolute against the working directory;
  without one :dir is nil (attach to the only listening window)."
  [args]
  (when (= "--mcp-attach" (first args))
    (let [cwd (System/getProperty "user.dir")
          d (second args)]
      {:dir (cond
              (nil? d) nil
              (.isAbsolute (io/file d)) d
              :else (.getAbsolutePath (io/file cwd d)))})))

(defn- attach-mcp!
  "Bridges stdio to a listening window and returns the exit code. Like
  serve-mcp!, the root *out* goes to stderr and only :write-line reaches the
  real stdout. Runs before anything GTK."
  [{:keys [dir]}]
  (let [real-out *out*]
    (alter-var-root #'*out* (constantly *err*))
    (listen/bridge! {:dir dir
                     :settings-path (settings/settings-path)
                     :read-line (fn [] (read-line))
                     :write-line (fn [line]
                                   (binding [*out* real-out]
                                     (println line)
                                     (flush)))})))

(defonce ^:private listener (atom nil))

(defn- stop-listener!
  "Stops the MCP listener if one runs (idempotent), so its registry entry
  dies with the process."
  []
  (when-let [l (first (reset-vals! listener nil))]
    (try (when-let [unfollow! (:unfollow! l)] (unfollow!))
         ((:stop! l))
         (catch Throwable e
           (binding [*out* *err*]
             (println "jonquil: stopping the MCP listener failed:" (ex-message e)))))))

(defn- start-listener-when-enabled!
  "The launch's :on-ready: starts the MCP listener when :mcp-listen? is on.
  Reads the tree root in this turn (the GTK thread). A failure is said on
  stderr and the window carries on."
  [ui]
  (try
    (let [sp (settings/settings-path)]
      (when (:mcp-listen? (settings/load-settings sp))
        (let [root (some-> (:root @(:state (:file-tree ui))) io/file .getCanonicalPath)
              l (listen/start-listener! {:ui ui :dir root :settings-path sp})]
          (reset! listener (assoc l :unfollow! (listen/follow-root! (:state (:file-tree ui)) l))))))
    (catch Throwable e
      (binding [*out* *err*]
        (println "jonquil: MCP listener not started:" (ex-message e))))))

(defn- serve-mcp!
  "Headless MCP over stdio. JSON-RPC owns the real stdout, so the root *out*
  is pointed at stderr first: a runtime thread's println then lands on
  stderr, and only :write-line (bound to the saved writer) reaches stdout."
  [{:keys [dir]}]
  (let [real-out *out*]
    (alter-var-root #'*out* (constantly *err*))
    (login-path/start!)
    (binding [*out* *err*]
      (println "jonquil: MCP server on stdio, default directory" dir))
    (mcp/serve! {:tools (mcp-tools/make-tools {:dir dir :settings-path (settings/settings-path)})
                 :read-line (fn [] (read-line))
                 :write-line (fn [line]
                               (binding [*out* real-out]
                                 (println line)
                                 (flush)))})))

;; The window variant: the real stdout is kept for JSON-RPC, the root *out* goes
;; to stderr, and the server runs on its own thread once the window is up. The
;; GTK loop owns this thread; it ends when the EOF quit closes the window.
(defn- serve-mcp-window!
  [{:keys [dir plugins?]}]
  (let [real-out *out*
        cwd (System/getProperty "user.dir")]
    (alter-var-root #'*out* (constantly *err*))
    (login-path/start!)
    (start! {:cwd cwd
             :argv (gtk-argv [dir] cwd)
             :plugins? plugins?
             :on-ready (fn [ui]
                         (future
                           (mcp-window/serve-window!
                            {:ui ui
                             :dir dir
                             :read-line (fn [] (read-line))
                             :write-line (fn [line]
                                           (binding [*out* real-out]
                                             (println line)
                                             (flush)))})))})))

(defn- run-main [args plugins?]
  (when-let [m (attach-args args)]
    (System/exit (attach-mcp! m)))
  ;; Asks the login shell for PATH in the background when launched from
  ;; Finder; self-tests call start! directly and keep the process PATH.
  (when-not (mcp-args args) (login-path/start!))
  ;; A SIGTERM, SIGHUP or SIGINT to Jonquil itself must not orphan the
  ;; runtimes. Probed 2026-10-15: the hook runs on all three signals. A
  ;; normal quit also runs stop-all! just before System/exit, so it runs
  ;; twice, and the second call returns at once.
  (jolt.host/add-shutdown-hook
   (fn []
     (stop-listener!)
     (try (registry/stop-all!)
          (catch Throwable e
            (binding [*out* *err*]
              (println "jonquil: stopping every runtime failed:" (ex-message e)))))))
  ;; Every argument goes through GApplication's "open" signal, so several
  ;; files open as tabs and a folder roots the file tree. :cwd roots the
  ;; tree when there's no argument.
  (if-let [m (mcp-args args)]
    (if (:window? m) (serve-mcp-window! (assoc m :plugins? plugins?)) (serve-mcp! m))
    (let [cwd (System/getProperty "user.dir")]
      (start! {:cwd cwd :argv (gtk-argv args cwd) :plugins? plugins?
               :on-ready start-listener-when-enabled!})))
  ;; The GTK loop has ended by the time g-application-run (inside start!)
  ;; returns, so blocking here is fine -- unlike ev/stop! (jonquil.nrepl.
  ;; evaluator), which never blocks the GTK thread. registry/stop-all!
  ;; stops every evaluator this process ever made and waits, bounded, for
  ;; any start still in flight to finish tearing itself down, so
  ;; System/exit below can't cut that off mid-flight and orphan a child.
  ;; Guarded so a throw here can't skip the exit.
  (stop-listener!)
  (try (registry/stop-all!)
       (catch Throwable e
         (binding [*out* *err*]
           (println "jonquil: stopping every runtime failed:" (ex-message e)))))
  (System/exit 0))

(defn -main [& all-args]
  ;; --no-plugins (or JONQUIL_NO_PLUGINS=1 from the Dock or
  ;; Finder) starts the window with no plugins; nothing else sees the flag
  (let [{:keys [args plugins?]} (plugin-loader/plugin-flags all-args #(System/getenv %))]
    (run-main args plugins?)))
