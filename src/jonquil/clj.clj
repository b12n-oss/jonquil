(ns jonquil.clj
  "Pure decisions for the JVM attach dialect: the console's sentences,
  the buffer's namespace, reply decisions, and REPL interactions.
  No sockets and no GTK: receives text and reply vectors
  (jonquil.nrepl.client messages, top-level keys keywords)."
  (:require [clojure.string :as str]
            [jonquil.cljd :as cljd]
            [jonquil.nrepl.ops :as ops]))

;; --- the console's sentences ---

(def no-project-message
  "; This file isn't in a project, so there's no .nrepl-port to read. Save it inside the project that has .jonquil.edn.")

(defn not-running-message [dir]
  (str "; No nREPL server here. Start one in " dir " with clojure -M:serve (clojure-cli.repl), or any nREPL server that writes .nrepl-port."))

(def bad-port-file-message
  "; .nrepl-port doesn't hold a port number, so Jonquil can't reach the server.")

(defn refused-message [port]
  (str "; .nrepl-port says port " port ", but nothing answers there. The server may have stopped."))

(defn not-server-message [port]
  (str "; Port " port " isn't an nREPL server Jonquil can use."))

(defn connecting-message [port]
  (str "; Connecting to the nREPL server on port " port "..."))

(defn not-loaded-message [ns-name]
  (str "; " ns-name " isn't loaded in the server yet. Run the file first."))

(def interrupt-failed-message
  "; The server didn't stop the evaluation, so Jonquil detached. It may still be running.")

(def not-attached-doc
  "No docs: Jonquil isn't attached to the server yet. Run something first.")

(def inspect-not-attached
  "Nothing to inspect yet: Jonquil isn't attached to the server. Run something first.")

(def no-lookup-doc
  "No docs: this server has no lookup op.")

(def lookup-timeout-doc
  "No docs: the server didn't answer in time.")

(defn not-loaded-doc
  "No docs: ns-name isn't loaded yet. jonquil.nrepl.jvm's lookup and
  full-doc say this when neither the buffer's namespace nor the prompt's
  can answer."
  [ns-name]
  (str "No docs: " ns-name " isn't loaded in the server yet. Run the file first."))

(def lost-connection
  "Lost the connection to the nREPL server.")

(def limitations
  ["Jonquil attaches to a server you started: it never starts or stops the JVM."
   "Reset on Run isn't available: this is your own JVM, so Jonquil doesn't unload namespaces in it."
   "Stdin can't be closed: an empty stdin fails on nREPL 1.7.0, so a read waits for a line."
   "Jump outside this buffer shows where the definition is, but doesn't open the file."
   "Output printed from other threads after an evaluation finishes isn't shown."])

;; --- the connect policy ---

(def connect-policy
  {:messages {:no-project no-project-message
              :not-running not-running-message
              :bad-port-file bad-port-file-message
              :refused refused-message
              :not-server not-server-message
              :connecting connecting-message}
   ;; :inspect-registered? (ruling 4): whether the connect-time eval of
   ;; nil has been sent on this connection's session yet, so a fresh
   ;; connect always resets it to false and a later reconnect registers
   ;; again. :prompt-ns: a new connection is a new session, so the
   ;; prompt starts back in user rather than on the last Run's namespace
   ;; (which a restarted server has never heard of).
   :on-connect {:session-ns nil :inspect-registered? false :prompt-ns 'user}})

;; --- the buffer's namespace ---

(defn buffer-ns
  "The buffer's ns form's name string, or nil when there's no ns form."
  [text]
  (:name (cljd/ns-form text)))

(defn help-ns
  "The :ns a completions or lookup request is sent with: the buffer's own
  ns form name, else the prompt namespace's name."
  [text prompt-ns]
  (or (buffer-ns text) (str prompt-ns)))

;; --- replies ---

(defn ns-not-found?
  "True when any message's :status contains 'namespace-not-found'."
  [msgs]
  (boolean ((ops/statuses msgs) "namespace-not-found")))

(defn eval-result
  "ops/eval-result with this dialect's lost-connection sentence."
  [msgs stop-requested?]
  (ops/eval-result msgs stop-requested? lost-connection))

(defn prompt-after-run
  "The namespace for the prompt after an evaluation. When the result is
  :ok and the buffer has a namespace, return that namespace; otherwise
  return the current namespace."
  [text result current]
  (if (and (= :ok (:status result)) (buffer-ns text))
    (symbol (buffer-ns text))
    current))

(defn need-input?
  "True when the message indicates the server needs stdin input."
  [m]
  (boolean (some (fn [s] (= "need-input" s)) (:status m))))

(defn stdin-line
  "Append a newline to text unless it already ends with one."
  [text]
  (if (str/ends-with? text "\n")
    text
    (str text "\n")))
