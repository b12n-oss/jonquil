(ns jonquil.nrepl.ops
  "Pure translation between nREPL replies and Jonquil's own shapes.

  Replies are jonquil.nrepl.client messages: top-level keys keywords,
  nested maps as decoded (string keys). Three completion shapes exist
  (dialects spike): vanilla jolt {candidate ns}, jolt with
  nrepl.middleware {candidate type}, jank {candidate type ns}; fields are
  read by keyword or string. jolt-lang/nrepl's lookup carries no file or
  line; a JVM server's does."
  (:require [clojure.string :as str]
            [jonquil.syntax :as syntax]))

(def disconnected "jonquil-disconnected")

(defn- field [m k]
  (let [v (get m k)] (if (some? v) v (get m (name k)))))

(defn statuses
  "Every status string across msgs."
  [msgs]
  (into #{} (mapcat :status) msgs))

(defn op-names
  "The op names a describe reply advertises."
  [msgs]
  (into #{} (map name) (keys (some :ops msgs))))

(defn nrepl-version
  "[major minor] from a describe reply's nrepl version, or nil when msgs
  has no nrepl entry or either part isn't an integer (or msgs isn't a
  vector, as request-sync's :timeout isn't)."
  [msgs]
  (when (vector? msgs)
    (let [major (get-in (some :versions msgs) ["nrepl" "major"])
          minor (get-in (some :versions msgs) ["nrepl" "minor"])]
      (when (and (integer? major) (integer? minor))
        [major minor]))))

(defn- category [c]
  (cond (= "special-form" (field c :type)) :special
        (= "clojure.core" (field c :ns)) :builtin
        :else :user))

(defn candidates
  "{:symbol :category} maps from a completions reply; [] when it has none."
  [msgs]
  (into [] (keep (fn [c] (when-let [s (field c :candidate)] {:symbol s :category (category c)})))
        (some :completions msgs)))

(defn merge-candidates
  "offline (jonquil.docs/candidates) plus the server's names it lacks,
  sorted the way jonquil.syntax/get-autocomplete-candidates sorts a
  non-blank prefix. offline's entry wins for a name both have."
  [prefix offline server]
  (let [have (into #{} (map :symbol) offline)
        extra (remove (fn [c] (have (:symbol c))) server)
        prefix (or prefix "")]
    (vec (sort-by (fn [{:keys [symbol category]}]
                    [(if (str/starts-with? symbol prefix) 0 1)
                     (case category :user 0 :special 1 :builtin 2 3)
                     (str/lower-case symbol)
                     symbol])
                  (concat offline (distinct extra))))))

(defn doc-info
  "A jonquil.docs/symbol-doc-shaped map from a lookup reply, or nil when
  the reply names nothing (an unknown symbol, a special form, or a server
  without lookup: {:status [\"done\" \"unknown-op\"]}). :file and :line
  pass through when the reply carries them, else neither key is added; a
  blank :file (babashka's lookup sends \"\", measured) counts as none."
  [sym msgs]
  (let [m (some (fn [m] (when (or (:info m) (field m :name)) m)) msgs)
        info (or (:info m) m)]
    (when (and m (field info :name))
      (cond-> {:status :found
               :symbol sym
               :ns (field info :ns)
               :name (field info :name)
               :arglists (or (field info :arglists-str) (field info :arglists))
               :doc (field info :doc)}
        (not (str/blank? (field info :file))) (assoc :file (field info :file))
        (field info :line) (assoc :line (field info :line))))))

(defn- first-line [s]
  (first (str/split-lines (str/trim (str s)))))

(def ^:private err-header
  "A header line of clojure.main's err->msg, each phase's (checked against
  clojure.main 1.12.5 and Jolt's port): the message follows on the next
  line. JVM nREPL, and jolt-lang/nrepl since its #11, send that text as err
  before eval-error, and their ex is then only the class name. Not anchored
  to a line start, since *err* output with no newline can come right
  before it, so it needs the header's own ending, \" at <location>).\", to
  tell it from a message that merely starts the same way. \\r? for a
  server on Windows."
  #"(?:(?:Execution|Syntax|Unexpected) error|Error (?:printing return value|reading eval result))[^\n]* at [^\n]*\)\.\r?\n")

(def ^:private python-traceback "Traceback (most recent call last):")

(defn- last-line [s]
  (some-> (last (remove str/blank? (str/split-lines (str s)))) str/trim))

(defn- compiler-message
  "The message line of Basilisp's compiler and reader error block, which
  it sends as err (measured, Basilisp 0.5.1):
  \"  exception: <class '...CompilerException'>\\n    message: unable to
  resolve symbol 'x' in this context\\n   location: <nREPL Input>:1\". The
  last message: line after the last exception: line, or nil when err has
  no such block."
  [err]
  (when-let [i (str/last-index-of err "exception: <class '")]
    (some->> (str/split-lines (subs err i))
             (keep #(second (re-find #"^\s*message: (.*)$" %)))
             last str/trim not-empty)))

(defn- error-message
  "An eval-error's message: the first non-blank line after the last err->msg
  header in the err text (the last, so the program's own *err* output
  before it isn't taken). With no header and an :ex that is a class
  (\"class X\"), the LAST err line naming that class, since a server that
  doesn't use clojure.main's format (babashka: err
  \"java.lang.ArithmeticException: Divide by zero user REPL:1:1\", ex
  \"class java.lang.ArithmeticException\") puts the message there while
  :ex holds only the class; the last, so the program's own earlier stderr
  that happens to name the class isn't taken. With no header and no class,
  Basilisp's shapes (measured, 0.5.1): the message line of its compiler
  error block in err, else the last line of a Python traceback in ex or err
  (\"ZeroDivisionError: Fraction(1, 0)\", the exception Python names
  last). Else ex, else the err text's first line."
  [msgs]
  (let [err (apply str (keep :err msgs))
        header (last (re-seq err-header err))
        after (when header (subs err (+ (str/last-index-of err header) (count header))))
        ex (some :ex msgs)
        cls (when (and ex (str/starts-with? ex "class "))
              (some-> ex (subs (count "class ")) str/trim not-empty))
        named (when (and (not header) cls)
                (some-> (last (filter #(str/includes? % cls) (str/split-lines err))) str/trim))
        python (when-not (or header cls)
                 (or (compiler-message err)
                     (some #(when (str/includes? (str %) python-traceback) (last-line %)) [ex err])))]
    (or (not-empty (first-line after))
        named
        python
        (first-line (or ex err)))))

(defn eval-result
  "jonquil.eval's result shape for one request's replies: {:status
  :ok|:error|:interrupted} plus :printed (the server's printed value) for
  :ok, :error-text for :error, and :ns (a symbol) when a reply names one.
  stop-requested? turns an error or a lost connection into :interrupted,
  as Stop does in-process. lost-text is the error message when the
  connection is lost."
  ([msgs stop-requested?]
   (eval-result msgs stop-requested? "The Jolt runtime exited before this evaluation finished."))
  ([msgs stop-requested? lost-text]
   (let [st (statuses msgs)
         ns-str (some :ns (reverse msgs))
         r (cond
             (or (st "interrupted") (and stop-requested? (or (st disconnected) (st "eval-error"))))
             {:status :interrupted}

             (st disconnected)
             {:status :error :error-text lost-text}

             (st "eval-error")
             {:status :error
              :error-text (str "Error: " (error-message msgs))}

             :else
             {:status :ok :printed (or (last (keep :value msgs)) "nil")})]
     (cond-> r ns-str (assoc :ns (symbol ns-str))))))

(defn- keeping-history
  "Wraps body, code for an eval Jonquil sends on the user's session for its
  own bookkeeping, so the user's *1, *2 and *3 come out unchanged.

  The server shifts *3 <- *2, *2 <- *1, *1 <- value after every successful
  eval (jolt.nrepl, alter-var-root on the global vars). So this pre-shifts
  *2 <- *3 and *1 <- *2 and returns the old *1: after the server's own
  shift the user's *1, *2 and *3 are exactly what they were (final review
  finding 1). The cost is printing the old *1 as this eval's value.
  Fully qualified, since it runs in whatever namespace the session is in."
  [body]
  (str "(clojure.core/let [o1 clojure.core/*1 o2 clojure.core/*2 o3 clojure.core/*3]"
       " " body
       " (clojure.core/alter-var-root (var clojure.core/*2) (clojure.core/constantly o3))"
       " (clojure.core/alter-var-root (var clojure.core/*1) (clojure.core/constantly o2))"
       " o1)"))

(defn reset-code
  "Code for the child that removes each namespace in names that exists,
  then `user`, and recreates `user`: the in-process Reset on Run's rule
  (jonquil.session/reset-namespaces!) run where the program lives. names
  is the running owner's namespaces plus the REPL's (tabs spec). Leaves
  the user's *1, *2 and *3 alone (see keeping-history)."
  [names]
  (keeping-history
   (str "(clojure.core/doseq [n '" (pr-str (vec names)) "]"
        " (clojure.core/when (clojure.core/find-ns n) (clojure.core/remove-ns n)))"
        " (clojure.core/remove-ns 'user) (clojure.core/create-ns 'user)")))

(def ^:private refer-helpers-code
  ;; The REPL helpers the in-process engine refers before each evaluation
  ;; (jonquil.eval), referred here into the session's current namespace.
  ;; In its own try, so a failure never costs the namespace snapshot.
  (str "(try (clojure.core/require 'clojure.repl 'clojure.pprint)"
       " (clojure.core/refer 'clojure.repl :only '[doc find-doc apropos dir])"
       " (clojure.core/refer 'clojure.pprint :only '[pprint pp])"
       " (catch :default _ nil))"))

(def ns-snapshot-code
  "Code that prints [namespace-names loaded-libs] in the child on :out:
  read before and after an evaluation to learn which namespaces it created
  (jonquil.session/namespaces-created). It also refers the REPL helpers
  into the session's namespace first, as the in-process engine does, and
  leaves the user's *1, *2 and *3 alone (see keeping-history)."
  (keeping-history
   (str refer-helpers-code
        " (clojure.core/print (clojure.core/pr-str [(clojure.core/mapv clojure.core/ns-name (clojure.core/all-ns))"
        " (clojure.core/vec (clojure.core/loaded-libs))]))"
        " (clojure.core/flush)")))

(def ^:private stdin-names
  #{"read-line" "read" "*in*" "clojure.core/read-line" "clojure.core/read" "clojure.core/*in*"})

(defn reads-stdin?
  "True when code names read-line, read or *in* outside strings and
  comments. The nREPL runtime has no stdin, so the console warns first."
  [code]
  (let [code (or code "")]
    (boolean (some (fn [[t s e]] (and (#{:symbol :builtin} t) (stdin-names (subs code s e))))
                   (syntax/tokenize code)))))
