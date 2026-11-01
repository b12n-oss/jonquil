(ns jonquil.restarts
  "Restarts for the last failed evaluation, the Lisp Machine debugger's,
  scoped to what a stack that already unwound allows: Retry it, Edit it at
  the prompt, or Define the name it couldn't resolve and retry. A failure
  is {:kind :run|:repl|:selection|:form :code :tab-id :error-text}, which
  jonquil.gtk.window records. No GTK here."
  (:require [clojure.string :as str]))

(defn missing-name
  "The unqualified name in an \"Unable to resolve symbol: NAME in this
  context\" error (in process, the Jolt child and JVM Clojure all say it
  this way), or nil, also for a qualified name or one that isn't a symbol."
  [error-text]
  ;; never the reader: read-string honours #=, so text in an error message
  ;; (a server's, an agent's, a program's) could run code in Jonquil itself
  (when-let [[_ n] (and error-text
                        (re-find #"Unable to resolve symbol: ([^\s,;()\[\]{}\"'`~^@\\]+) in this context" error-text))]
    (when-not (or (str/includes? n "/") (re-find #"^[0-9#:']" n))
      n)))

(def ^:private at-the-prompt
  ;; the kinds whose code can be edited at the console prompt: a Run is a
  ;; whole file, edited in its tab
  #{:repl :selection :form})

(defn options
  "The restarts for failure, in order: Retry always, Edit and retry and
  Define NAME and retry for code that can go to the prompt (the latter
  only when a name was missing). [] for no failure."
  [{:keys [kind error-text] :as failure}]
  (if-not failure
    []
    (let [prompt? (at-the-prompt kind)
          n (missing-name error-text)]
      (cond-> [{:id :retry :label "Retry"}]
        prompt? (conj {:id :edit :label "Edit and retry"})
        (and prompt? n) (conj {:id :define :label (str "Define " n " and retry")})))))

(defn define-input
  "The console input for Define and retry: (def NAME ) on its own line
  before code, the caret where the value goes."
  [n code]
  (let [head (str "(def " n " ")]
    {:text (str head ")\n" code) :caret (count head)}))

(defn record-failure!
  "The failure atom's bookkeeping after an evaluation ends: an :error
  records where (with :error-text from (error-text-fn result)), an :ok
  clears it, anything else (a Stop) leaves it. Never throws: the
  evaluation's own completion must not be lost to it."
  [failure* where result error-text-fn]
  (try
    (case (:status result)
      :error (reset! failure* (assoc where :error-text (try (error-text-fn result) (catch Throwable _ nil))))
      :ok (reset! failure* nil)
      nil)
    (catch Throwable _ nil))
  nil)
