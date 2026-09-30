(ns jonquil.nrepl.ops
  "Pure translation between nREPL replies and Jonquil's own shapes.

  Replies are jonquil.nrepl.client messages: top-level keys keywords,
  nested maps as decoded (string keys). Three completion shapes exist
  (dialects spike): vanilla jolt {candidate ns}, jolt with
  nrepl.middleware {candidate type}, jank {candidate type ns}; fields are
  read by keyword or string. lookup never carries :file or :line."
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
  without lookup: {:status [\"done\" \"unknown-op\"]})."
  [sym msgs]
  (let [m (some (fn [m] (when (or (:info m) (field m :name)) m)) msgs)
        info (or (:info m) m)]
    (when (and m (field info :name))
      {:status :found
       :symbol sym
       :ns (field info :ns)
       :name (field info :name)
       :arglists (or (field info :arglists-str) (field info :arglists))
       :doc (field info :doc)})))

(defn- first-line [s]
  (first (str/split-lines (str/trim (str s)))))

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
              :error-text (str "Error: " (first-line (or (some :ex msgs)
                                                         (apply str (keep :err msgs)))))}

             :else
             {:status :ok :printed (or (last (keep :value msgs)) "nil")})]
     (cond-> r ns-str (assoc :ns (symbol ns-str))))))

(defn reset-code
  "Code for the child that removes every namespace not in baseline (the
  namespace names right after the child started) and not a loaded
  library, then `user`, and recreates `user`: the in-process Reset on
  Run's rule (jonquil.session/reset-namespaces!) run where the program
  lives. Measured over the wire: user defs, Run-created namespaces and a
  load-file namespace go, a required library stays."
  [baseline]
  (str "(let [keep (into (set '" (pr-str (vec baseline)) ") (loaded-libs))]"
       " (doseq [n (map ns-name (all-ns))] (when-not (contains? keep n) (remove-ns n)))"
       " (remove-ns 'user) (create-ns 'user) nil)"))

(def ^:private stdin-names
  #{"read-line" "read" "*in*" "clojure.core/read-line" "clojure.core/read" "clojure.core/*in*"})

(defn reads-stdin?
  "True when code names read-line, read or *in* outside strings and
  comments. The nREPL runtime has no stdin, so the console warns first."
  [code]
  (let [code (or code "")]
    (boolean (some (fn [[t s e]] (and (#{:symbol :builtin} t) (stdin-names (subs code s e))))
                   (syntax/tokenize code)))))
