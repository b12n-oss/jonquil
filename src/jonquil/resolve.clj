(ns jonquil.resolve
  "Which var a symbol in a file names, pure, from the file's own text: its
  ns form's aliases, refers and refer-clojure options, its top-level defs,
  the local bindings around the symbol, and clojure.core. Used by Find
  Usages in Project (jonquil.usages) to tell same-named symbols apart
  (ROADMAP LM2). Whatever the text alone can't decide (a :refer :all, a
  :use, a name a macro defines, an ns form that doesn't read) is
  {:unresolved true}, never a guess."
  (:require [clojure.string :as str]
            [jonquil.help :as help]
            [jonquil.syntax :as syntax]))

;; --- ns forms: where they are -------------------------------------------

(def ^:private code-kinds
  ;; the tokenizer's kinds for code, as syntax/find-symbol-occurrences reads them
  #{:symbol :special-form :builtin :constant})

(defn- just-before?
  "Whether text right before offset i, past spaces, ends with prefix."
  [^String text i prefix]
  (str/ends-with? (str/trimr (subs text 0 i)) prefix))

(defn- opener?
  "Whether ch opens a list, vector or map."
  [ch]
  (contains? #{\( \[ \{} ch))

(defn- top-level-or-reader-conditional?
  "Whether a form opened with stack (the offsets of the openers around it)
  is at the top level, or directly inside a reader conditional."
  [text stack]
  (or (empty? stack)
      (and (= 1 (count stack))
           (let [p (peek stack)]
             (or (just-before? text p "#?") (just-before? text p "#?@"))))))

(defn- ns-opener?
  "Whether the ( at offset s, followed by token next, starts a live ns form."
  [text stack s next-token]
  (let [[nk ns' ne] next-token]
    (and (contains? code-kinds nk) (= "ns" (subs text ns' ne))
         (not (just-before? text s "#_"))
         (top-level-or-reader-conditional? text stack))))

(defn- bracket-step
  "[stack spans] after the bracket token at the head of ts (at offset s, the
  next token second in ts): an opener is pushed (and its ns form's span
  recorded), a closer pops."
  [{:keys [text matches]} [stack spans] ts]
  (let [[_ s] (first ts)
        next-token (second ts)]
    (if (opener? (.charAt ^String text (int s)))
      (let [close (when (and (= \( (.charAt ^String text (int s)))
                             (ns-opener? text stack s next-token))
                    (get matches s))]
        [(conj stack s) (cond-> spans close (conj [s (inc close)]))])
      [(if (seq stack) (pop stack) stack) spans])))

(defn ns-spans
  "[[start end] ...] of text's ns forms: a (ns ...) at the top level, or
  directly inside a reader conditional (#?(:clj (ns ...))), but never one
  in a (comment ...) block or after #_. tokens is text's tokens without
  comments."
  [text tokens]
  (let [{:keys [matches]} (syntax/compute-brackets text)
        ctx {:text text :matches matches}]
    (loop [ts (seq tokens) stack [] spans []]
      (if-let [[kind] (first ts)]
        (if (= :bracket kind)
          (let [[stack' spans'] (bracket-step ctx [stack spans] ts)]
            (recur (rest ts) stack' spans'))
          (recur (rest ts) stack spans))
        spans))))

;; --- reader conditionals ------------------------------------------------
;; Jolt's read-string ignores :features and :read-cond (v0.8.26,
;; jolt-lang/jolt#1620), so a #? always reads as Jolt's own branch; the ns
;; text gets its branches picked here, for the file's features, before reading.

(defn- prefix-start
  "The offset of the form whose first token starts at s, stepping back over
  reader prefix characters (#{, 'x, @x) but not past floor."
  [^String text floor s]
  (loop [i s]
    (if (and (> i floor) (contains? #{\# \' \@ \` \~ \^} (.charAt text (int (dec i)))))
      (recur (dec i))
      i)))

(defn- elements
  "[[start end] ...] of the forms directly inside the list opened at p and
  closed at close, from text's tokens and bracket matches."
  [text tokens matches p close]
  (loop [ts (drop-while #(<= (second %) p) tokens) floor (inc p) out []]
    (let [[kind s e] (first ts)]
      (if (or (nil? kind) (>= s close))
        out
        (let [[nk ns'] (second ts)
              ;; the tokenizer splits #?( and #?@( into a ? symbol and a (
              rc? (and (= "?" (subs text s e)) (= :bracket nk)
                       (or (= ns' e) (and (= ns' (inc e)) (= \@ (.charAt ^String text (int e))))))
              end (cond (= :bracket kind) (inc (get matches s s))
                        rc? (inc (get matches ns' ns'))
                        :else e)
              start (prefix-start text floor s)]
          (recur (drop-while #(< (second %) end) ts) end (conj out [start end])))))))

(defn- chosen-branch
  "The text a reader conditional (the list at p..close) reads as for
  features: the first branch whose key is in features or is :default, its
  brackets dropped when splicing?; \"\" when no branch fits."
  [^String text features splicing? els]
  (let [pick (some (fn [[[ks ke] form]]
                     (let [k (keyword (subs text (inc ks) ke))]
                       (when (or (contains? features k) (= :default k)) form)))
                   (partition 2 els))]
    (cond (nil? pick) ""
          splicing? (let [[s e] pick] (subs text (inc s) (dec e)))
          :else (let [[s e] pick] (subs text s e)))))

(defn select-reader-conditionals
  "text with each #?(...) and #?@(...) replaced by the branch it reads as
  for reader features, nested ones included."
  [^String text features]
  (loop [t text]
    (if-let [m (re-find #"#\?@?\(" t)]
      (let [h (str/index-of t m)
            p (+ h (dec (count m)))
            tokens (vec (remove #(= :comment (first %)) (syntax/tokenize t)))
            close (get (:matches (syntax/compute-brackets t)) p)]
        (if (nil? close)
          t
          (recur (str (subs t 0 h)
                      (chosen-branch t features (str/includes? m "@")
                                     (elements t tokens (:matches (syntax/compute-brackets t)) p close))
                      (subs t (inc close))))))
      t)))

;; --- ns forms: what they say ---------------------------------------------

(defn core-ns
  "The core namespace for reader features: cljs.core for ClojureScript,
  cljd.core for ClojureDart, else clojure.core."
  [features]
  (cond (contains? features :cljs) 'cljs.core
        (contains? features :cljd) 'cljd.core
        :else 'clojure.core))

(def ^:private empty-info
  {:ns 'user :aliases {} :refers {} :excludes #{} :core-renames {} :all? false})

(defn- opts-map
  "A libspec vector's options after the lib, as a map; a dangling key is dropped."
  [opts]
  (into {} (map vec) (partition 2 opts)))

(defn- add-refers
  "info with names referred from lib, each under its :rename name if any."
  [info lib names renames]
  (reduce (fn [i n] (assoc-in i [:refers (get renames n n)] (symbol (str lib) (str n))))
          info names))

(defn- add-libspec
  "info after one libspec of a :require (use? false) or :use (use? true)
  clause, with prefix the enclosing prefix list's lib, or nil."
  [info use? prefix spec]
  (let [full (fn [lib] (if prefix (symbol (str prefix "." lib)) (symbol (str lib))))]
    (cond
      (or (symbol? spec) (string? spec))
      (cond-> info use? (assoc :all? true))

      (vector? spec)
      (let [lib (full (first spec))
            {:keys [as as-alias refer only rename]} (opts-map (rest spec))
            names (if use? only refer)
            a (or as as-alias)]
        (cond-> info
          a (assoc-in [:aliases a] lib)
          (= :all names) (assoc :all? true)
          (and use? (nil? only)) (assoc :all? true)
          (sequential? names) (add-refers lib names (or rename {}))))

      (and (seq? spec) (symbol? (first spec)))
      (reduce #(add-libspec %1 use? (full (first spec)) %2) info (rest spec))

      :else info)))

(defn- add-refer-clojure
  "info after a (:refer-clojure :exclude [...] :rename {...}) clause."
  [info core opts]
  (let [{:keys [exclude rename]} (opts-map opts)]
    (cond-> (update info :excludes into exclude)
      rename (update :core-renames into
                     (map (fn [[orig new]] [new (symbol (str core) (str orig))]))
                     rename))))

(defn- add-clause
  "info after one ns clause."
  [info core clause]
  (if-not (seq? clause)
    info
    (case (first clause)
      (:require :require-macros) (reduce #(add-libspec %1 false nil %2) info (rest clause))
      (:use :use-macros) (reduce #(add-libspec %1 true nil %2) info (rest clause))
      :refer-clojure (add-refer-clojure info core (rest clause))
      info)))

(defn ns-info
  "{:ns :aliases :refers :excludes :core-renames :all?} from text's first
  live ns form, read with reader features (a set such as #{:clj}).
  :aliases maps alias to lib, :refers a visible bare name to its var,
  :core-renames a new name to the core var it renames. :all? is true when
  a :refer :all or :use can bring in names the text doesn't list, so a
  bare name found nowhere else stays unresolved. No ns form: user. An ns
  form that doesn't read: :ns nil and :all? true, so the file's own defs
  don't resolve either, rather than resolving wrongly to user."
  [text features]
  (let [tokens (remove #(= :comment (first %)) (syntax/tokenize text))]
    (if-let [[s e] (first (ns-spans text tokens))]
      (let [form (try (read-string (select-reader-conditionals (subs text s e) features))
                      (catch Throwable _ ::unreadable))]
        (if (and (seq? form) (symbol? (second form)))
          (reduce #(add-clause %1 (core-ns features) %2)
                  (assoc empty-info :ns (second form))
                  (drop 2 form))
          (assoc empty-info :ns nil :all? true)))
      empty-info)))

;; --- symbols ----------------------------------------------------------------

(def ^:private core-names
  (delay (set (keys (ns-publics 'clojure.core)))))

(defn- split-qualified
  "[ns-part name] of a qualified symbol's text, or nil for a bare one. A
  lone / is bare."
  [^String t]
  (let [i (str/index-of t "/")]
    (when (and i (pos? i) (< (inc i) (count t)))
      [(subs t 0 i) (subs t (inc i))])))

(defn- core-var
  "The core var bare name n names under info, or nil: not excluded, not
  renamed away, and a public of clojure.core (Jonquil's own, which stands
  in for the dialect's)."
  [{:keys [excludes core-renames]} core n]
  (let [sym (symbol n)
        renamed-away (set (map (comp symbol name) (vals core-renames)))]
    (when (and (contains? @core-names sym)
               (not (contains? excludes sym))
               (not (contains? renamed-away sym)))
      (symbol (str core) n))))

(defn resolve-sym
  "{:var sym} or {:unresolved true} for symbol text t in a file with ns
  info, own-def? telling whether the file defines a bare name at the top
  level, and core its core namespace. Locals are resolve-at's."
  [{:keys [info own-def? core]} t]
  (let [{:keys [aliases refers core-renames] ns' :ns} info]
    (if-let [[q n] (split-qualified t)]
      {:var (symbol (str (get aliases (symbol q) q)) n)}
      (let [sym (symbol t)]
        (if-let [v (or (when (and ns' (own-def? t)) (symbol (str ns') t))
                       (get refers sym)
                       (get core-renames sym)
                       (core-var info core t))]
          {:var v}
          {:unresolved true})))))

(defn- def-head?
  "Whether symbol text t starts a definition form: def, defn, defmacro,
  defonce, defprotocol and the rest of the def family, qualified or not."
  [t]
  (str/starts-with? (or (second (split-qualified t)) t) "def"))

(defn- defined-names
  "The set of names text's (def... name ...) forms define, the name token
  being any code kind (the tokenizer marks a core name such as get as
  :builtin, which syntax/find-definition skips)."
  [^String text]
  (let [ts (vec (remove #(= :comment (first %)) (syntax/tokenize text)))
        txt (fn [[_ s e]] (subs text s e))]
    (into #{}
          (keep (fn [i]
                  (let [[a b c] (subvec ts i (min (count ts) (+ i 3)))]
                    (when (and c (= "(" (txt a))
                               (contains? code-kinds (first b)) (def-head? (txt b))
                               (contains? code-kinds (first c)))
                      (txt c)))))
          (range (count ts)))))

(defn file-context
  "What resolving symbols in text needs, computed once per file: its ns
  info, its core namespace and own-def?, whether the file defines a name."
  [text features]
  (let [defs (delay (defined-names text))]
    {:text text
     :info (ns-info text features)
     :core (core-ns features)
     :own-def? (fn [n] (contains? @defs n))}))

(defn resolve-at
  "The resolution of the symbol whose text t starts at offset in ctx's text
  (a file-context): {:local true} when a binding form around it binds the
  name, else resolve-sym's answer."
  [{:keys [text] :as ctx} offset t]
  (if (and (not (split-qualified t)) (help/local-binding text offset t))
    {:local true}
    (resolve-sym ctx t)))

(defn features-for
  "The reader features to read path with: a .cljs file's are #{:cljs}, a
  .cljd file's #{:cljd}, any other file's the window's own (features)."
  [path features]
  (cond (str/ends-with? path ".cljs") #{:cljs}
        (str/ends-with? path ".cljd") #{:cljd}
        :else features))
