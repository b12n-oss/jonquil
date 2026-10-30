(ns jonquil.jank
  "Pure decisions for the jank dialect: the console's sentences, the code
  strings sent for find-ns and docs, and readers for jank's replies. No
  sockets and no GTK: receives text and reply vectors
  (jonquil.nrepl.client messages, top-level keys keywords)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [jonquil.cljd :as cljd]
            [jonquil.nrepl.ops :as ops]))

;; --- the console's sentences ---

(defn starting-message [binary]
  (str "; Starting jank (" binary ")..."))

(def no-binary-message
  "; jank isn't installed. Install it (brew install jank-lang/jank/jank) or set JONQUIL_JANK to its path.")

(defn start-failed-message [text]
  (str "; jank didn't start: " text))

(def reset-message
  "; Reset: restarted jank.")

(def stopped-message
  "; Stopped. jank restarted, so earlier definitions are gone.")

(def lost-connection
  "jank exited. The next evaluation starts it again.")

(defn not-loaded-message [ns-name]
  (str "; " ns-name " isn't loaded in jank yet. Run the file first."))

(def busy-doc
  "No docs while jank is running.")

(def limitations
  ["jank is experimental, and Jonquil starts and stops its own jank process."
   "Stop restarts jank, so earlier definitions are gone afterwards."
   "Programs can't read input: jank hasn't implemented read-line yet."
   "Jump to definition outside this file and the inspector aren't available."])

;; --- the connect policy ---

(def connect-policy
  ;; No :port-file here: jank writes its port into a run dir the evaluator
  ;; owns, so the evaluator adds a :port-file fn. :prompt-ns: a restarted
  ;; jank has forgotten every namespace, so a connect starts in user.
  {:messages {:no-project "; This file isn't in a project, so Jonquil can't start jank for it. Save it inside the project that has .jonquil.edn."
              :not-running (fn [dir] (str "; jank isn't running for " dir ". Run something to start it."))
              :bad-port-file "; jank's port file doesn't hold a port number, so Jonquil can't reach jank."
              :refused (fn [port] (str "; jank's port file says port " port ", but nothing answers there. jank may have exited."))
              :not-server (fn [port] (str "; Port " port " isn't a jank nREPL server Jonquil can use."))
              :connecting (fn [port] (str "; Connecting to jank on port " port "..."))}
   :on-connect {:session-ns nil :prompt-ns 'user}})

;; --- code sent to jank ---

(defn find-ns-code
  "Code that answers true when the namespace is loaded in jank (jank has
  no namespace-not-found status, so a missing ns is asked about first)."
  [ns-name]
  (str "(some? (find-ns '" ns-name "))"))

(defn meta-doc-code
  "Code that answers [arglists-string doc] for sym, or nil when it doesn't
  resolve. Sent through eval, since the lookup op wedges jank (jank issue
  998, measured 2026-10-12)."
  [sym]
  (str "(let [v (resolve '" sym ")] (when v (let [m (meta v)] [(pr-str (:arglists m)) (:doc m)])))"))

;; --- replies ---

(defn loaded?
  "True when a reply's :value is \"true\" (the answer to find-ns-code)."
  [msgs]
  (boolean (some (fn [m] (= "true" (:value m))) msgs)))

(defn meta-doc
  "A jonquil.docs/symbol-doc shaped map for sym from the replies to
  meta-doc-code: :found with :arglists and :doc, else cljd/no-docs for an
  unknown symbol, an error or a value that isn't the expected vector."
  [sym msgs]
  (let [no-docs (cljd/no-docs sym (str "No docs for " sym))
        v (when-not ((ops/statuses msgs) "eval-error")
            (some-> (last (keep :value msgs))
                    (as-> s (try (edn/read-string s) (catch Exception _ nil)))))]
    (if (and (vector? v) (= 2 (count v)))
      (let [[a d] v]
        {:status :found :symbol sym :name sym
         :arglists (when-not (= "nil" a) a)
         :doc d})
      no-docs)))

(defn- first-line [s]
  (first (str/split-lines (str/trim (str s)))))

(defn eval-result
  "ops/eval-result with this dialect's lost-connection sentence. Homebrew
  jank 0.1 reports an error as the :value with blank :err and no :ex, so
  an eval-error with no report takes the last :value's first line."
  [msgs stop-requested?]
  (let [r (ops/eval-result msgs stop-requested? lost-connection)]
    (if (and (= :error (:status r))
             ((ops/statuses msgs) "eval-error")
             (str/blank? (str (some :ex msgs) (apply str (keep :err msgs)))))
      (assoc r :error-text (str "Error: " (first-line (last (keep :value msgs)))))
      r)))
