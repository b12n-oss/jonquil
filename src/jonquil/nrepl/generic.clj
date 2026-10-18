(ns jonquil.nrepl.generic
  "The nREPL dialect's pure decisions: where to connect, what to say, and
  what a given server can't do. The evaluator is jonquil.nrepl.jvm's Jvm
  record; this namespace is what differs for any server that answers
  nREPL rather than a JVM's.

  Measured against babashka's server (2026-10-18, v1.13.220): it writes no
  port file; it has no interrupt and no stdin op; its completion is fuzzy,
  through complete (and through completions, which one connection listed
  and another didn't); lookup's :file is empty; a read
  blocks the session for good, and a new connection clones a fresh one.

  And against Basilisp's (0.5.1, same day): it writes .nrepl-port; no
  interrupt, stdin, lookup or completions op, but complete (prefix only)
  and info; errors come as a Python traceback or a compiler block
  (jonquil.nrepl.ops/error-message); a read reads the server process's own
  stdin."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jonquil.source :as source]))

(def default-host "127.0.0.1")

(defn endpoint
  "{:host :port} for selection's .jonquil.edn :nrepl. :port nil means read
  it from the port file."
  [selection]
  (let [{:keys [host port]} (:nrepl selection)]
    {:host (or host default-host) :port port}))

(defn port-file
  "The port file in the project directory: .jonquil.edn's :port-file, else
  the profile's, or nil without a project."
  [selection]
  (when-let [dir (:project-dir selection)]
    (io/file dir (or (:port-file (:nrepl selection)) (:port-file (:profile selection))))))

(defn- at
  "host:port as one writes it, an IPv6 host in brackets."
  [host port]
  (str (if (and (str/includes? host ":") (not (str/starts-with? host "["))) (str "[" host "]") host)
       ":" port))

(defn connect-policy
  "jonquil.nrepl.connection/connect!'s policy for selection: the endpoint
  and port file above, and sentences that name the host."
  [selection]
  (let [{:keys [host]} (endpoint selection)
        pf (or (:port-file (:nrepl selection)) (:port-file (:profile selection)))]
    {:endpoint (fn [ev] (endpoint (:selection ev)))
     :port-file (fn [ev] (port-file (:selection ev)))
     :messages
     {:no-project "; This file isn't in a project, so there's no .jonquil.edn saying where the nREPL server is. Save it in the folder that has .jonquil.edn."
      :not-running (fn [dir]
                     (str "; No nREPL server port here: " pf " isn't in " dir ". Start your server"
                          " (for babashka, bb nrepl-server 1667), then put"
                          " {:dialect :nrepl :nrepl {:port 1667}} in .jonquil.edn, or write its port to " pf "."))
      :bad-port-file (str "; " pf " doesn't hold a port number, so Jonquil can't reach the server.")
      :refused (fn [port] (str "; Nothing answers at " (at host port) ". Is the server running there?"))
      :not-server (fn [port] (str "; " (at host port) " isn't an nREPL server Jonquil can use: it needs eval and load-file."))
      :connecting (fn [port] (str "; Connecting to the nREPL server at " (at host port) "..."))}}))

(def always-limits
  ["Jonquil attaches to a server you started: it never starts or stops it."
   "Reset on Run isn't available: Jonquil doesn't unload namespaces in a server you started."])

(defn limitations
  "Sentences naming what this server can't do: always-limits, and once
  connected, one for each missing op the window would use."
  [ops connected?]
  (cond-> always-limits
    (and connected? (not (contains? ops "interrupt")))
    (conj "This server has no interrupt op, so Stop disconnects: the evaluation keeps running in the server, and the next Run connects a fresh session.")
    (and connected? (not (contains? ops "stdin")))
    (conj "This server has no stdin op, so Jonquil can't send a read any input: babashka's read waits until Stop, and Basilisp's reads the server's own stdin, the terminal it was started in.")
    (and connected? (not (or (contains? ops "completions") (contains? ops "complete"))))
    (conj "Completion offers only this buffer's names: the server has no completion op.")
    ;; jonquil.nrepl.jvm's quick doc sends lookup, else info
    (and connected? (not (or (contains? ops "lookup") (contains? ops "info"))))
    (conj "No quick docs: the server has no lookup or info op.")))

(defn local-file?
  "True when a lookup's :file names a file that exists on this machine: a
  path, or a file: URL. Any server's :file is kept only then. babashka,
  after a load-file, answers clojure.core/map with
  file:<its working dir>/clojure/core.clj, which doesn't exist (measured
  2026-10-18), and a remote server's paths are its own disk's. Resolved
  the way the source panel resolves it (jonquil.source/location: a path,
  a percent-encoded file: URL, or a jar: URL), then the file (or the jar)
  must exist here."
  [file]
  (boolean
   (when-not (str/blank? file)
     (when-let [{:keys [path jar]} (source/location {:file file} {})]
       (.isFile (io/file (or path jar)))))))

(def stdin-notice
  ;; Measured 2026-10-18: babashka v1.13.220's read-line blocks the session
  ;; for good. Basilisp 0.5.1's reads the server process's own stdin: a line
  ;; typed there, "" at once when that stdin is closed, and it waits when
  ;; it's open with nothing written.
  "; This server has no stdin op, so Jonquil can't send this read any input. On babashka it waits until Stop, which disconnects; on Basilisp it reads the server's own stdin, so type the line in the terminal you started the server in.\n")

(defn with-prefix
  "The candidates whose :symbol starts with prefix. babashka's complete is
  fuzzy (\"ma\" also brings Throwable->map and format), and the window's
  list is a prefix list."
  [prefix candidates]
  (let [p (or prefix "")]
    (filterv (fn [c] (str/starts-with? (str (:symbol c)) p)) candidates)))
