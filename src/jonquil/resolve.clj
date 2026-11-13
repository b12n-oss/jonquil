(ns jonquil.resolve
  "Which var a symbol in a file names, pure, from the file's own text: its
  ns form's aliases, refers and refer-clojure options, its top-level defs,
  the local bindings around the symbol, and clojure.core. Used by Find
  Usages in Project (jonquil.usages) to tell same-named symbols apart
  (the Lisp Machine track's locations work). Whatever the text alone can't decide (a :refer :all, a
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
;; A #? is picked here, for the file's own features, before reading: even
;; a reader that honours :features keeps its platform's :clj on, as the
;; JVM does (and Jolt v0.8.26 ignores :features outright,
;; jolt-lang/jolt#1620), so reading a .cljc file the way ClojureScript sees
;; it needs only :cljs on, which no read-string option gives.

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

(defn- first-conditional
  "{:hash :open :splicing?} of text's first live #?( or #?@( (from tokens,
  so never one inside a string or comment), or nil."
  [^String text tokens]
  (some (fn [[[k s e] [nk ns']]]
          (when (and (= :symbol k) (= "?" (subs text s e)) (pos? s)
                     (= \# (.charAt text (int (dec s))))
                     (= :bracket nk) (= \( (.charAt text (int ns'))))
            (cond (= ns' e) {:hash (dec s) :open ns' :splicing? false}
                  (and (= ns' (inc e)) (= \@ (.charAt text (int e)))) {:hash (dec s) :open ns' :splicing? true}
                  :else nil)))
        (partition 2 1 tokens)))

(defn select-reader-conditionals
  "text with each #?(...) and #?@(...) replaced by the branch it reads as
  for reader features, nested ones included. A #? in a string or comment
  is left alone; an unclosed one leaves the text as it is."
  [^String text features]
  (loop [t text]
    (let [tokens (vec (remove #(= :comment (first %)) (syntax/tokenize t)))
          matches (:matches (syntax/compute-brackets t))]
      (if-let [{:keys [hash open splicing?]} (first-conditional t tokens)]
        (if-let [close (get matches open)]
          (recur (str (subs t 0 hash)
                      (chosen-branch t features splicing? (elements t tokens matches open close))
                      (subs t (inc close))))
          t)
        t))))

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

(defn- check-shape!
  "Throws when ok? is false: an ns option of the wrong shape (a symbol
  where a vector belongs, say), which ns-info turns into an unreadable ns."
  [ok? form]
  (when-not ok? (throw (ex-info "ns option of the wrong shape" {:form form}))))

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
        (check-shape! (or (nil? names) (= :all names) (sequential? names)) spec)
        (check-shape! (or (nil? rename) (map? rename)) spec)
        (cond-> info
          a (assoc-in [:aliases a] lib)
          (= :all names) (assoc :all? true)
          (and use? (nil? only)) (assoc :all? true)
          (sequential? names) (add-refers lib names (or rename {}))))

      (and (seq? spec) (symbol? (first spec)))
      (reduce #(add-libspec %1 use? (full (first spec)) %2) info (rest spec))

      :else info)))

(defn- add-refer-clojure
  "info after a (:refer-clojure :exclude [...] :only [...] :rename {...})
  clause. :only keeps just those core names, as :core-only."
  [info core opts]
  (let [{:keys [exclude only rename]} (opts-map opts)]
    (check-shape! (or (nil? exclude) (sequential? exclude)) opts)
    (check-shape! (or (nil? only) (sequential? only)) opts)
    (check-shape! (or (nil? rename) (map? rename)) opts)
    (cond-> (update info :excludes into exclude)
      only (assoc :core-only (set only))
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
  form that doesn't read, or holds an option of the wrong shape: :ns nil
  and :all? true, so the file's own defs and core names don't resolve
  either, rather than resolving wrongly. A file with in-ns and no ns form:
  :ns nil. :core-only is there only when :refer-clojure has :only."
  [text features]
  (let [tokens (remove #(= :comment (first %)) (syntax/tokenize text))
        unreadable (assoc empty-info :ns nil :all? true)]
    (if-let [[s e] (first (ns-spans text tokens))]
      (try
        (let [form (read-string (select-reader-conditionals (subs text s e) features))]
          (if (and (seq? form) (symbol? (second form)))
            (reduce #(add-clause %1 (core-ns features) %2)
                    (assoc empty-info :ns (second form))
                    (drop 2 form))
            unreadable))
        (catch Throwable _ unreadable))
      ;; an in-ns file's namespace is set at run time: not user
      (if (re-find #"\(\s*in-ns\b" text)
        (assoc empty-info :ns nil)
        empty-info))))

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
  "The core var bare name n names under ctx, or nil: a public of
  clojure.core (Jonquil's own, which stands in for the dialect's), not
  excluded, not renamed away, and in :core-only when the ns form has one."
  [{:keys [info core renamed-away]} n]
  (let [sym (symbol n)
        {:keys [excludes core-only]} info]
    (when (and (contains? @core-names sym)
               (not (contains? excludes sym))
               (not (contains? renamed-away sym))
               (or (nil? core-only) (contains? core-only sym)))
      (symbol (str core) n))))

(defn- qualified-var
  "The var qualified symbol q/n names under info, or nil: q an alias, the
  file's own namespace, or a dotted full namespace name. A bare q that's no
  alias (a top-level require's, a class) can't be decided."
  [{:keys [aliases] ns' :ns} q n]
  (let [lib (or (get aliases (symbol q))
                (when (and ns' (= q (str ns'))) ns')
                (when (str/includes? q ".") (symbol q)))]
    (when lib (symbol (str lib) n))))

(defn resolve-sym
  "{:var sym} or {:unresolved true} for symbol text t in ctx (a
  file-context): qualified through qualified-var; bare, the file's own def,
  then an explicit refer or a refer-clojure rename, then clojure.core
  unless a :refer :all or :use could shadow it. Locals are resolve-at's."
  [{:keys [info own-def?] :as ctx} t]
  (let [{:keys [refers core-renames all?] ns' :ns} info
        v (if-let [[q n] (split-qualified t)]
            (qualified-var info q n)
            (let [sym (symbol t)]
              (or (when (and ns' (own-def? t)) (symbol (str ns') t))
                  (get refers sym)
                  (get core-renames sym)
                  (when-not all? (core-var ctx t)))))]
    (if v {:var v} {:unresolved true})))

(def ^:private def-heads
  ;; the forms whose second element names a var (or a class, for the
  ;; record and type forms) in the file's namespace; defmethod adds a method
  ;; to a multimethod defined elsewhere, so it isn't one
  #{"def" "defn" "defn-" "defmacro" "defonce" "defmulti" "defprotocol"
    "definterface" "defrecord" "deftype" "defstruct" "definline"})

(defn- def-head?
  "Whether symbol text t starts a definition form, qualified or not."
  [t]
  (contains? def-heads (or (second (split-qualified t)) t)))

(defn- name-after-head
  "The text of the name token after a def head at index i of src's tokens,
  past metadata (^:private, ^String, ^{...}), or nil."
  [{:keys [^String text tokens matches]} i]
  (loop [j (inc i)]
    (when-let [[k s e] (get tokens j)]
      (cond
        (= :metatag k) (recur (inc j))
        ;; ^{...}: the tokenizer gives the { alone; skip to past its match
        (and (= :bracket k) (= \{ (.charAt text (int s))) (pos? s) (= \^ (.charAt text (int (dec s)))))
        (let [close (get matches s)]
          (recur (or (some (fn [x] (when (> (second (get tokens x)) close) x)) (range (inc j) (count tokens)))
                     (count tokens))))
        (contains? code-kinds k) (subs text s e)
        :else nil))))

(defn- defined-names
  "The set of names src's def forms define, the name token being any code
  kind (the tokenizer marks a core name such as get as :builtin, which
  syntax/find-definition skips)."
  [{:keys [^String text tokens] :as src}]
  (into #{}
        (keep (fn [i]
                (let [[ka sa ea] (get tokens i)
                      [kb sb eb] (get tokens (inc i))]
                  (when (and kb (= :bracket ka) (= "(" (subs text sa ea))
                             (contains? code-kinds kb) (def-head? (subs text sb eb)))
                    (name-after-head src (inc i))))))
        (range (count tokens))))

(defn file-context
  "What resolving symbols in text needs, computed once per file: its ns
  info and ns form span, its core namespace, the core names it renames
  away, own-def? (whether the file defines a name) and src, its tokens and
  bracket matches."
  [text features]
  (let [src (delay (let [tokens (vec (remove #(= :comment (first %)) (syntax/tokenize text)))]
                     {:text text :tokens tokens
                      :matches (:matches (syntax/compute-brackets text))
                      :ns-span (first (ns-spans text tokens))}))
        defs (delay (defined-names @src))
        info (ns-info text features)]
    {:text text
     :info info
     :src src
     :core (core-ns features)
     :renamed-away (set (map (comp symbol name) (vals (:core-renames info))))
     :own-def? (fn [n] (contains? @defs n))}))

(defn- enclosing-vector
  "[open close] of the innermost [...] around offset in src, or nil."
  [{:keys [^String text matches]} offset]
  (some (fn [o]
          (let [c (get matches o)]
            (when (and (= \[ (.charAt text (int o))) c (< offset c)) [o c])))
        (range (dec offset) -1 -1)))

(defn- in-ns-form
  "The resolution of the symbol t at offset inside the file's ns form: a
  name in a :refer or :only vector is the var it refers; anything else
  there (the ns name, a lib, an alias) is unresolved."
  [{:keys [src info]} offset t]
  (let [[o] (enclosing-vector @src offset)
        before (when o (str/trimr (subs (:text @src) 0 o)))
        v (when (and before (or (str/ends-with? before ":refer") (str/ends-with? before ":only")))
            (some (fn [v] (when (= (name v) t) v)) (vals (:refers info))))]
    (if v {:var v} {:unresolved true})))

(defn resolve-at
  "The resolution of the symbol whose text t starts at offset in ctx's text
  (a file-context). Inside the ns form, in-ns-form. Else a binding around
  it decides first: the binding site itself, or a use after its bindings
  vector, is {:local true}; a use inside that vector before the binding
  (an init that may name the outer var) is unresolved; a use before the
  binding (a def's own name, ahead of its parameters) is no local. Then
  resolve-sym."
  [ctx offset t]
  (let [[s e] (:ns-span @(:src ctx))]
    (if (and s (<= s offset) (< offset e))
      (in-ns-form ctx offset t)
      (let [b (when-not (split-qualified t) (help/local-binding-in @(:src ctx) offset t))
            [vo vc] (when b (enclosing-vector @(:src ctx) (:start b)))]
        (cond
          (nil? b) (resolve-sym ctx t)
          (= (:start b) offset) {:local true}
          (< offset (:start b)) (resolve-sym ctx t)
          (and vo (< vo offset vc)) {:unresolved true}
          :else {:local true})))))

(defn features-for
  "The reader features to read path with: a .cljs file's are #{:cljs}, a
  .cljd file's #{:cljd}, a .clj file's the window's own when they include
  :clj, else #{:clj}, and any other file's (.cljc) the window's own."
  [path features]
  (cond (str/ends-with? path ".cljs") #{:cljs}
        (str/ends-with? path ".cljd") #{:cljd}
        (str/ends-with? path ".clj") (if (contains? features :clj) features #{:clj})
        :else features))
