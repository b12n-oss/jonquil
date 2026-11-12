(ns jonquil.breakpoints
  "Where a breakpoint can go in Clojure text, and the text a Run sends with
  its breakpoints in (LM5). No GTK here: the window hands in the text and
  1-based line numbers.

  A breakpoint is a form inserted on its line, never a newline, so line
  numbers stay put. It throws an ex-info inside jolt.debug's break handler
  and catches it again on the spot: the handler pauses through
  jonquil.debugger/breakpoint-hook, and with the hook empty the form does
  nothing.

  Two kinds of place hold one:
  - a body form of a form that takes a body (defn, fn, let, do, when, try,
    ...): the breakpoint goes in front of it as a statement of its own;
  - any other list or symbol whose value is used (a call argument, a let
    init, an if branch): it is wrapped as (do <breakpoint> expr), which
    keeps its value.
  Anything else holds none: a param vector, a binding name, a catch clause,
  a case test, quoted code, a defn's name or docstring, the ns form, a macro
  that may read its arguments as code. When a position isn't known to be
  evaluated, it holds none."
  (:require [clojure.string :as str]
            [jonquil.commands :as commands]))

;; --- the inserted form --------------------------------------------------------

(defn breakpoint-form
  "The one-line form for a breakpoint on line n. Everything is qualified, so
  a namespace that excludes clojure.core names still compiles it."
  [n]
  (str "(try (jolt.debug/with-break-handler"
       " (clojure.core/fn [t fs] (clojure.core/when-let [h (clojure.core/deref jonquil.debugger/breakpoint-hook)] (h t fs)))"
       " (throw (clojure.core/ex-info \"Breakpoint\" {:jonquil/breakpoint " n "})))"
       " (catch clojure.lang.ExceptionInfo e"
       " (clojure.core/when-not (:jonquil/breakpoint (clojure.core/ex-data e)) (throw e))))"))

(defn breakpoint-throw?
  "True when ex-data is a breakpoint's (breakpoint-form's throw)."
  [data]
  (boolean (and (map? data) (:jonquil/breakpoint data))))

;; --- reading the text ---------------------------------------------------------

(def ^:private prefix-chars #{\' \` \~ \@ \# \?})

(defn- prefix
  "The reader prefix characters written right before node's opening bracket
  (\"'\", \"#\", \"#?\", ...), \"\" for none or an atom."
  [text {:keys [open start]}]
  (if-not open
    ""
    (loop [p open]
      (if (and (> p start) (contains? prefix-chars (.charAt ^String text (int (dec p)))))
        (recur (dec p))
        (subs text p open)))))

(defn- kind
  "node's kind: :list, :vector, :map, :set or :atom."
  [text {:keys [open] :as node}]
  (if-not open
    :atom
    (case (.charAt ^String text (int open))
      \( :list
      \[ :vector
      \{ (if (str/ends-with? (prefix text node) "#") :set :map)
      :atom)))

(defn- atom-text [text {:keys [start end]}] (subs text start end))

(def ^:private not-symbols #{"nil" "true" "false" "&"})

(defn- symbol-atom?
  "True for an atom that is a plain symbol (or a deref of one, @x): not a
  keyword, number, string, character, regex, var quote, quoted or
  metadata-tagged form."
  [text node]
  (and (= :atom (kind text node))
       (let [s (atom-text text node)
             s (if (str/starts-with? s "@") (subs s 1) s)]
         (and (seq s)
              (not (contains? not-symbols s))
              (not (contains? #{\: \" \\ \# \' \` \^ \~ \@} (first s)))
              (not (Character/isDigit (char (first s))))
              (not (and (#{\+ \-} (first s)) (> (count s) 1) (Character/isDigit (char (second s)))))))))

(defn- quoted?
  "True when node is quoted, syntax-quoted, a reader conditional or a
  tagged literal: nothing in it is code we can touch."
  [text node]
  (let [p (prefix text node)]
    (or (str/includes? p "'") (str/includes? p "`") (str/includes? p "?"))))

(defn- head-name
  "The name of a list's head symbol without its namespace (s/defn reads as
  defn, so a library's defn-like macro is treated as one), or nil when the
  head isn't a plain symbol."
  [text {:keys [kids]}]
  (when-let [h (first kids)]
    (when (and (symbol-atom? text h) (not (str/starts-with? (atom-text text h) "@")))
      (let [s (atom-text text h)
            i (str/last-index-of s "/")]
        (if (or (nil? i) (= s "/") (= i (dec (count s))))
          s
          (subs s (inc i)))))))

;; --- the walk: which nodes are code, and where --------------------------------

(defn- exprs [xs] (map (fn [x] [x :expr]) xs))
(defn- stmts [xs] (map (fn [x] [x :stmt]) xs))

(defn- string-atom? [text node] (and (= :atom (kind text node)) (str/starts-with? (atom-text text node) "\"")))

(defn- binding-pairs
  "The inits of a binding vector [name init ...]: each init is code, no
  name is. Not a vector: nothing."
  [text v]
  (when (= :vector (kind text v))
    (exprs (take-nth 2 (rest (:kids v))))))

(defn- seq-bindings
  "doseq's and for's binding vector: inits are code, :when and :while
  values are code, :let's vector is a binding vector."
  [text v]
  (when (= :vector (kind text v))
    (mapcat (fn [[k x]]
              (let [ks (when (= :atom (kind text k)) (atom-text text k))]
                (cond
                  (#{":when" ":while"} ks) (exprs [x])
                  (= ":let" ks) (binding-pairs text x)
                  :else (exprs [x]))))
            (partition-all 2 (:kids v)))))

(defn- arity-body
  "A params-and-body list's code: the body forms after the params vector,
  a {:pre ...} map before more forms left out."
  [text xs]
  (let [body (rest xs)
        body (if (and (next body) (= :map (kind text (first body)))) (rest body) body)]
    (stmts body)))

(defn- fn-tail
  "The code of what follows a fn's name: [params] body..., or one
  ([params] body...) list per arity."
  [text xs]
  (let [x (first xs)]
    (case (when x (kind text x))
      :vector (arity-body text xs)
      :list (mapcat (fn [a] (when (and (= :list (kind text a)) (not (quoted? text a)))
                              (arity-body text (:kids a))))
                    xs)
      nil)))

(defn- defn-parts
  "defn's code: after the name, an optional docstring and attr map, then
  the fn tail."
  [text xs]
  (let [xs (rest xs)
        xs (if (and (next xs) (string-atom? text (first xs))) (rest xs) xs)
        xs (if (and (next xs) (= :map (kind text (first xs)))) (rest xs) xs)]
    (fn-tail text xs)))

(defn- def-parts
  "def's code: the init, after the name and an optional docstring."
  [xs]
  (let [more (rest xs)]
    (case (count more)
      1 (exprs more)
      2 (exprs [(second more)])
      nil)))

(defn- method-lists
  "reify's, deftype's and extend's method lists, (name [params] body...)
  or (name ([params] body...) ...): their bodies are code; anything that
  isn't a list (a protocol or type name, a field vector) is not."
  [text xs]
  (mapcat (fn [m]
            (when (and (= :list (kind text m)) (not (quoted? text m)))
              (fn-tail text (rest (:kids m)))))
          xs))

(defn- try-parts
  "try's code: the body, and the bodies of its catch and finally clauses
  (never a catch's class or name)."
  [text xs]
  (mapcat (fn [x]
            (case (when (= :list (kind text x)) (head-name text x))
              "catch" (stmts (drop 3 (:kids x)))
              "finally" (stmts (rest (:kids x)))
              [[x :stmt]]))
          xs))

(defn- case-parts
  "case's code: the expression, each result, and the default; never a test."
  [xs]
  (let [[e & clauses] xs]
    (concat (when e [[e :expr]])
            (exprs (map second (filter #(= 2 (count %)) (partition-all 2 clauses))))
            (when (odd? (count clauses)) [[(last clauses) :expr]]))))

(defn- letfn-parts
  [text [specs & body]]
  (concat (when (= :vector (kind text specs))
            (mapcat (fn [s] (when (= :list (kind text s)) (fn-tail text (rest (:kids s)))))
                    (:kids specs)))
          (stmts body)))

(def ^:private body-binding
  ;; [bindings] & body
  #{"let" "loop" "binding" "with-open" "with-redefs" "with-local-vars"
    "when-let" "when-some" "when-first" "dotimes"})

(def ^:private test-then-body
  ;; test & body
  #{"when" "when-not" "while" "locking"})

(def ^:private just-body
  ;; & body
  #{"do" "future" "delay" "lazy-seq" "with-out-str"})

(def ^:private first-only
  ;; only the first argument is plain code: the rest is threaded or read
  ;; as a member name
  #{"->" "->>" "some->" "some->>" "cond->" "cond->>" "as->" "doto" "." ".."})

(def ^:private no-code
  ;; nothing inside is code we may touch: quoted, compile-time, or a
  ;; declaration
  #{"quote" "var" "ns" "import" "require" "use" "refer" "declare" "defprotocol"
    "definterface" "defmulti" "defmacro" "gen-class" "is" "are" "deftemplate"
    "refer-clojure" "in-ns" "load"})

(defn- macro-ish?
  "True for a head that isn't core but reads like a macro that may treat
  its arguments as code: (with-foo ...), (defthing ...), (x-> ...)."
  [n]
  (or (str/starts-with? n "with-") (str/starts-with? n "def") (str/includes? n "->")))

(defn- list-parts
  "The [node position] pairs of a list's code, by its head; position is
  :stmt for a body form, :expr for any other evaluated one."
  [text {:keys [kids] :as node}]
  (let [n (head-name text node)
        xs (rest kids)]
    (cond
      (nil? n) (exprs kids)
      (no-code n) nil
      (#{"defn" "defn-" "deftest"} n) (if (= "deftest" n) (stmts (rest xs)) (defn-parts text xs))
      (#{"fn" "fn*"} n) (fn-tail text (if (symbol-atom? text (first xs)) (rest xs) xs))
      (#{"def" "defonce"} n) (def-parts xs)
      (= "defmethod" n) (fn-tail text (drop 2 xs))
      (#{"deftype" "defrecord"} n) (method-lists text (drop 2 xs))
      (#{"reify" "extend-type" "extend-protocol"} n) (method-lists text xs)
      (= "proxy" n) (method-lists text (drop 2 xs))
      (body-binding n) (concat (binding-pairs text (first xs)) (stmts (rest xs)))
      (#{"if-let" "if-some"} n) (concat (binding-pairs text (first xs)) (exprs (rest xs)))
      (#{"doseq" "for"} n) (concat (seq-bindings text (first xs))
                                   ((if (= "doseq" n) stmts exprs) (rest xs)))
      (= "letfn" n) (letfn-parts text xs)
      (test-then-body n) (concat (exprs (take 1 xs)) (stmts (rest xs)))
      (just-body n) (stmts xs)
      (= "testing" n) (stmts (rest xs))
      (= "try" n) (try-parts text xs)
      (= "case" n) (case-parts xs)
      (#{"set!" "new"} n) (exprs (rest xs))
      (first-only n) (exprs (take 1 xs))
      (= "comment" n) (map (fn [x] [x :top]) xs)
      (macro-ish? n) nil
      ;; a call, or a core form whose arguments are all evaluated (if,
      ;; cond, condp, and, or, recur, throw, ...)
      :else (exprs xs))))

(defn- pausable?
  "True when node, in an evaluated position, can hold a breakpoint: a list
  or a symbol."
  [text node]
  (or (and (= :list (kind text node)) (not (quoted? text node)) (seq (:kids node)))
      (symbol-atom? text node)))

(defn- walk
  "Adds node's spots, and those of the code inside it, to acc (a vector).
  pos is :top, :stmt or :expr."
  [text acc [node pos]]
  (let [acc (if (and (not= :top pos) (pausable? text node))
              (conj acc {:start (:start node) :end (:end node) :kind (if (= :stmt pos) :statement :expression)})
              acc)
        parts (when-not (quoted? text node)
                (case (kind text node)
                  :list (list-parts text node)
                  (:vector :map :set) (exprs (:kids node))
                  nil))]
    (reduce (partial walk text) acc parts)))

(defn- line-starts
  "The offset each line starts at, line 1 first."
  [text]
  (loop [i 0 acc [0]]
    (if-let [j (str/index-of text "\n" i)]
      (recur (inc j) (conj acc (inc j)))
      acc)))

(defn- line-of
  "The 1-based line holding offset, given line-starts' vector."
  [starts offset]
  (loop [lo 0 hi (dec (count starts))]
    (if (>= lo hi)
      (inc lo)
      (let [mid (quot (+ lo hi 1) 2)]
        (if (<= (nth starts mid) offset) (recur mid hi) (recur lo (dec mid)))))))

(defn spots
  "Every place in text that can hold a breakpoint, in text order:
  {:start :end :kind :line}, :kind :statement (inserted before it) or
  :expression (wrapped in a do), :line its 1-based start line. Two that
  start at the same offset keep the outer one first."
  [text]
  (let [starts (line-starts text)]
    (->> (reduce (fn [acc form] (walk text acc [form :top])) [] (commands/top-level-forms text))
         (map-indexed (fn [i s] (assoc s :i i :line (line-of starts (:start s)))))
         (sort-by (juxt :start :i))
         (mapv #(dissoc % :i)))))

(defn- spot-on
  "The first spot that starts on line, or nil."
  [all line]
  (first (filter #(= line (:line %)) all)))

(def NOWHERE
  "What place says when no line from there on can hold a breakpoint."
  "No code from this line on can hold a breakpoint.")

(defn place
  "Where a breakpoint asked for on line goes: {:line l}, l being line when
  something on it can pause, else the next line that can (and :moved?
  true), or {:error NOWHERE}."
  [text line]
  (let [all (spots text)]
    (if-let [s (first (filter #(>= (:line %) line) all))]
      (cond-> {:line (:line s)} (not= line (:line s)) (assoc :moved? true))
      {:error NOWHERE})))

(defn- edits
  "The [offset string] insertions for spot s, a breakpoint on its line."
  [{:keys [start end kind line]}]
  (let [bp (breakpoint-form line)]
    (if (= :statement kind)
      [[start (str bp " ") 0]]
      [[start (str "(do " bp " ") 0] [end ")" 1]])))

(defn instrument
  "text with a breakpoint at each of lines (1-based) that holds one, as
  {:text :lines}, :lines being the lines that got one. With range
  {:start :end}, only that part of text is answered (and only breakpoints
  wholly inside it count): the code a Run Form or Run Selection sends, read
  in the context of the whole text."
  ([text lines] (instrument text lines {:start 0 :end (count text)}))
  ([text lines {:keys [start end]}]
   (let [all (spots text)
         chosen (->> (distinct lines)
                     (keep #(spot-on all %))
                     (filter #(and (<= start (:start %)) (<= (:end %) end))))
         ;; from the end back, so earlier offsets stay good; at one offset
         ;; an opening goes in first, so the closing ) inserted after it
         ;; ends up in front of it
         ins (sort-by (fn [[o _ order]] [(- o) order]) (mapcat edits chosen))
         sb (StringBuilder. ^String (subs text start end))]
     (doseq [[o s] ins]
       (.insert sb (int (- o start)) ^String s))
     {:text (str sb) :lines (vec (sort (map :line chosen)))})))

;; --- Live at once: which top-level form a toggle sends again -------------------

(def ^:private defining
  #{"defn" "defn-" "defmacro" "defmethod" "defmulti" "defprotocol" "extend-type"
    "extend-protocol" "deftype" "defrecord"})

(defn- fn-value?
  "True for a def's value that is literally (fn ...), #(...) or (reify ...)."
  [text node]
  (and node (= :list (kind text node))
       (or (= "#" (prefix text node))
           (contains? #{"fn" "fn*" "reify"} (head-name text node)))))

(defn defines-code?
  "True when form (a top-level form of text) only defines code, so sending
  it again runs nothing else: defn, defmethod, deftype and the like, or a
  def or defonce whose value is literally a fn or a reify."
  [text form]
  (and (= :list (kind text form)) (not (quoted? text form))
       (let [n (head-name text form)
             xs (rest (:kids form))]
         (or (contains? defining n)
             (and (#{"def" "defonce"} n)
                  (fn-value? text (last xs))
                  (> (count xs) 1))))))

(defn resend-range
  "The {:start :end} of the top-level form holding line when toggling a
  breakpoint there should send it again, else nil: a form that does more
  than define code (a call, a def of (connect!)) and any form inside a
  (comment ...) block stay unsent."
  [text line]
  (let [starts (line-starts text)
        form (first (filter (fn [{s :start e :end}]
                              (<= (line-of starts s) line (line-of starts (max s (dec e)))))
                            (commands/top-level-forms text)))]
    (when (and form (not (commands/comment-form? text form)) (defines-code? text form))
      {:start (:start form) :end (:end form)})))

;; --- what a toggle says ------------------------------------------------------------

(def NEXT-RUN
  "A toggle's tail when its form wasn't sent again."
  "it takes effect when you next run this form.")

(defn toggle-message
  "The status line for a toggle. t: :added? (else removed), :line, :moved?
  (the breakpoint went to the next line that can hold one), :sent (:ok,
  :failed with :error, :busy, :refused, or nil when the form isn't one to
  send again) and :debug-on? (the toggle turned debug mode on)."
  [{:keys [added? line moved? sent error debug-on?]}]
  (let [what (cond (not added?) "Breakpoint removed"
                   moved? (str "Breakpoint moved to line " line)
                   :else (str "Breakpoint set at line " line))
        tail (case sent
               :ok (if added? "it's live now." "the form runs without it now.")
               :failed (str "sending its form again failed, so " NEXT-RUN
                            (when error (str " " error)))
               :busy (str "something is running, so " NEXT-RUN)
               :refused NEXT-RUN
               NEXT-RUN)]
    (str "; " what "; " tail
         (when debug-on? " Debug mode is on now, so frames keep their locals."))))
