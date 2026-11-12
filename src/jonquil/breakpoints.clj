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
            [jonquil.changed :as changed]
            [jonquil.commands :as commands]))

;; --- the inserted form --------------------------------------------------------

(defn breakpoint-form
  "The one-line form for a breakpoint on line n. Everything is qualified, so
  a namespace that excludes clojure.core names still compiles it. opts:
  :step? marks a step point (it pauses only while stepping) and :tab names
  the tab the code came from, for the gutter's arrow."
  ([n] (breakpoint-form n {}))
  ([n {:keys [step? tab]}]
   (str "(try (jolt.debug/with-break-handler"
        " (clojure.core/fn [t fs] (clojure.core/when-let [h (clojure.core/deref jonquil.debugger/breakpoint-hook)] (h t fs)))"
        " (throw (clojure.core/ex-info \"Breakpoint\" {:jonquil/breakpoint " n
        (when step? " :jonquil/step true")
        (when (some? tab) (str " :jonquil/tab " (pr-str tab)))
        "})))"
        " (catch clojure.lang.ExceptionInfo e"
        " (clojure.core/when-not (:jonquil/breakpoint (clojure.core/ex-data e)) (throw e))))")))

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

(defn- head-symbol
  "The text of a list's head when it is a plain symbol, namespace and all,
  else nil."
  [text {:keys [kids]}]
  (when-let [h (first kids)]
    (when (and (symbol-atom? text h) (not (str/starts-with? (atom-text text h) "@")))
      (atom-text text h))))

(defn- split-sym
  "[ns name] of symbol text s; ns nil when s isn't qualified."
  [s]
  (let [i (str/index-of s "/")]
    (if (or (nil? i) (= s "/") (zero? i) (= i (dec (count s))))
      [nil s]
      [(subs s 0 i) (subs s (inc i))])))

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
            (case (when (= :list (kind text x)) (head-symbol text x))
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
  #{"let" "loop" "let*" "loop*" "binding" "with-open" "with-redefs" "with-local-vars"
    "when-let" "when-some" "when-first" "dotimes"})

(def ^:private test-then-body
  ;; test & body
  #{"when" "when-not" "while" "locking"})

(def ^:private just-body
  ;; & body
  #{"do" "future" "delay" "lazy-seq" "with-out-str"})

(def ^:private all-args
  ;; every argument is evaluated as it is
  #{"if" "if-not" "and" "or" "cond" "condp" "recur" "throw"})

(def ^:private first-only
  ;; only the first argument is plain code: the rest is threaded
  #{"->" "->>" "some->" "some->>" "cond->" "cond->>" "as->" "doto"})

(def ^:private no-code
  ;; nothing inside is code we may touch: quoted, compile-time, interop
  ;; whose target may be a class, or a declaration
  #{"quote" "var" "ns" "import" "require" "use" "refer" "declare" "defprotocol"
    "definterface" "defmulti" "defmacro" "gen-class" "refer-clojure" "in-ns" "load"
    "." ".." "letfn*" "case*" "deftype*" "reify*" "monitor-enter" "monitor-exit"})

(def ^:private named
  ;; the rest of the core heads list-parts knows by name
  #{"defn" "defn-" "fn" "fn*" "def" "defonce" "defmethod" "deftype" "defrecord" "reify"
    "extend-type" "extend-protocol" "proxy" "if-let" "if-some" "doseq" "for" "letfn"
    "try" "case" "set!" "new" "comment"})

(def ^:private known-core
  (into #{} cat [body-binding test-then-body just-body all-args first-only no-code named]))

(def ^:private known-elsewhere
  ;; macros outside clojure.core whose shape is known, by their var
  {"clojure.test/deftest" "deftest" "clojure.test/testing" "testing"
   "clojure.test/is" "is" "clojure.test/are" "are"})

(def ^:private specials
  ;; special forms: never vars, so the runtime can't resolve them
  #{"if" "do" "let*" "loop*" "fn*" "letfn*" "try" "quote" "var" "." "new" "set!"
    "throw" "recur" "def" "case*" "monitor-enter" "monitor-exit" "deftype*" "reify*"})

(defn- head-kind
  "How a list's head reads, given heads (the runtime's answer, symbol text
  -> {:var \"ns/name\" :macro true}, or nil when it resolves to nothing;
  :heads absent when the runtime didn't answer):
  [:known n] for a form list-parts knows by name n, :macro for any other
  macro (nothing inside is touched), :call for a fn or an unresolved name
  (a local, interop). An unresolved name counts as a macro when the
  runtime didn't find the buffer's namespace (:ns-found? false), since it
  may come from that namespace's :refer. With no answer, a head that isn't
  a known core name counts as a macro."
  [text node {:keys [heads text-macros ns-found?]}]
  (let [s (head-symbol text node)
        [ns-part nm] (when s (split-sym s))]
    (cond
      (nil? s) :call
      (and (nil? ns-part) (specials s)) [:known s]
      (contains? text-macros s) :macro
      (= "clojure.core" ns-part) (if (known-core nm) [:known nm] :macro)
      (and heads (contains? heads s))
      (let [{v :var m :macro} (get heads s)
            [vns vname] (when v (split-sym v))]
        (cond
          (nil? v) (if (false? ns-found?) :macro :call)
          (= "clojure.core" vns) (if (known-core vname) [:known vname] (if m :macro :call))
          (known-elsewhere v) [:known (known-elsewhere v)]
          m :macro
          :else :call))
      (and (nil? ns-part) (known-core s)) [:known s]
      (and (nil? ns-part) (contains? #{"deftest" "testing" "is" "are"} s)) [:known s]
      :else :macro)))

(defn- tagged
  "The nodes in kids written right after a #tag (a tagged literal's value):
  read by the tag's reader, not code."
  [text kids]
  (into #{} (keep (fn [[a b]]
                    (when (and (= :atom (kind text a))
                               (let [t (atom-text text a)]
                                 (and (str/starts-with? t "#") (> (count t) 1)
                                      (not (#{\' \_ \? \{ \( \"} (.charAt ^String t 1))))))
                      b)))
        (partition 2 1 kids)))

(defn- list-parts
  "The [node position] pairs of a list's code, by its head; position is
  :stmt for a body form, :expr for any other evaluated one."
  [text {:keys [kids] :as node} opts]
  (let [k (head-kind text node opts)
        n (when (vector? k) (second k))
        xs (rest kids)]
    (cond
      (= :macro k) nil
      (= :call k) (if (head-symbol text node) (exprs xs) (exprs kids))
      (no-code n) nil
      (#{"is" "are"} n) nil
      (#{"defn" "defn-"} n) (defn-parts text xs)
      (= "deftest" n) (stmts (rest xs))
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
      (all-args n) (exprs xs)
      :else nil)))

(defn- pausable?
  "True when node, in an evaluated position, can hold a breakpoint: a list
  or a symbol."
  [text node]
  (or (and (= :list (kind text node)) (not (quoted? text node)) (seq (:kids node)))
      (symbol-atom? text node)))

(defn- walk
  "Adds node's spots, and those of the code inside it, to acc (a vector).
  pos is :top, :stmt or :expr."
  [text opts acc [node pos]]
  (let [acc (if (and (not= :top pos) (pausable? text node))
              (conj acc {:start (:start node) :end (:end node) :kind (if (= :stmt pos) :statement :expression)})
              acc)
        parts (when-not (quoted? text node)
                (case (kind text node)
                  :list (list-parts text node opts)
                  (:vector :map :set) (exprs (:kids node))
                  nil))
        skip (tagged text (:kids node))]
    (reduce (partial walk text opts) acc (remove (fn [[n _]] (skip n)) parts))))

(defn head-symbols
  "Every distinct list head in text that is a plain symbol: what to ask
  the runtime about (macro-heads)."
  [text]
  (letfn [(heads [node]
            (when (:kids node)
              (cons (head-symbol text node) (mapcat heads (:kids node)))))]
    (into (sorted-set) (comp (mapcat heads) (remove nil?)) (commands/top-level-forms text))))

(defn text-macros
  "The names text's own top-level (defmacro name ...) forms define."
  [text]
  (into #{} (keep (fn [f]
                    (when (and (= :list (kind text f)) (= "defmacro" (head-symbol text f)))
                      (when-let [nm (second (:kids f))] (atom-text text nm)))))
        (commands/top-level-forms text)))

(defn line-starts
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
  start at the same offset keep the outer one first. opts' :heads is the
  runtime's answer about text's head symbols (macro-heads, in
  jonquil.debugger); without it any head that isn't a known core form is
  taken for a macro, and nothing inside it holds a breakpoint. A defmacro
  in text names a macro either way."
  ([text] (spots text {}))
  ([text opts]
   (let [starts (line-starts text)
         opts (assoc opts :text-macros (text-macros text))
         forms (commands/top-level-forms text)
         skip (tagged text forms)]
     (->> (reduce (fn [acc form] (walk text opts acc [form :top])) [] (remove skip forms))
          (map-indexed (fn [i s] (assoc s :i i :line (line-of starts (:start s)))))
          (sort-by (juxt :start :i))
          (mapv #(dissoc % :i))))))

(defn- spot-on
  "The first spot that starts on line, or nil."
  [all line]
  (first (filter #(= line (:line %)) all)))

(def NOWHERE
  "What place says when no line from there on can hold a breakpoint."
  "No code from this line on can hold a breakpoint.")

(defn ns-form-text
  "The text of text's ns form (its first top-level (ns ...)), or nil: what
  a cached answer about heads is kept against, so a changed :require or
  :refer makes it stale even when the name stays."
  [text]
  (some (fn [f] (when (and (= :list (kind text f)) (= "ns" (head-symbol text f)))
                  (subs text (:start f) (:end f))))
        (commands/top-level-forms text)))

(defn cached-heads
  "The spots opts a tab's cached answer gives for text: {:heads :ns-found?}
  when cache ({:ns-form :heads :ns-found?}) was asked with the ns form text
  has now (nil for none), else {} (every head the window doesn't know
  counts as a macro). Within an answer, a head it doesn't name (typed
  since) counts as a macro too."
  [cache text]
  (if (and cache (= (:ns-form cache) (ns-form-text text)))
    (select-keys cache [:heads :ns-found?])
    {}))

(defn dropped-lines
  "The lines of lines (each once, in order) where nothing can hold a
  breakpoint now, read with opts: a Run leaves them out."
  [text lines opts]
  (let [all (spots text opts)]
    (vec (remove #(spot-on all %) (sort (distinct lines))))))

(defn dropped-message
  "The status line naming dropped lines (dropped-lines), nil for none."
  [ls]
  (case (count ls)
    0 nil
    1 (str "; Breakpoint at line " (first ls) " is inside a macro, so it won't pause.")
    (str "; Breakpoints at lines " (str/join ", " ls) " are inside a macro, so they won't pause.")))

(defn refresh-report
  "The status line after a Run asked about heads again: the dots in lines
  that are inside a macro by answer, when answer has :heads and the text
  (now) is still what was asked about (asked); else nil."
  [{:keys [asked now answer lines]}]
  (when (and (:heads answer) (= asked now))
    (dropped-message (dropped-lines now lines (select-keys answer [:heads :ns-found?])))))

(defn still-set?
  "True when a toggle's change ({:added? :mark}) may still be reported:
  a removal always, an added dot while its mark is among marks (a failed
  arm may have taken every dot away meanwhile)."
  [{:keys [added? mark]} marks]
  (boolean (or (not added?) (some #(= mark %) marks))))

(defn place
  "Where a breakpoint asked for on line goes: {:line l}, l being line when
  something on it can pause, else the next line that can (and :moved?
  true), or {:error NOWHERE}. opts as spots takes them."
  ([text line] (place text line {}))
  ([text line opts]
   (let [all (spots text opts)]
     (if-let [s (first (filter #(>= (:line %) line) all))]
       (cond-> {:line (:line s)} (not= line (:line s)) (assoc :moved? true))
       {:error NOWHERE}))))

(defn- units
  "text's stepping units: its top-level forms, a (comment ...) block's
  forms taken one by one instead of the block."
  [text]
  (mapcat (fn [f] (if (commands/comment-form? text f) (rest (:kids f)) [f]))
          (commands/top-level-forms text)))

(defn- edits
  "The [offset string] insertions for spot s, a breakpoint on its line (a
  step point with :step?), from tab."
  [tab {:keys [start end kind line step?]}]
  (let [bp (breakpoint-form line {:step? step? :tab tab})]
    (if (= :statement kind)
      [[start (str bp " ") 0]]
      [[start (str "(do " bp " ") 0] [end ")" 1]])))

(defn instrument
  "text with a breakpoint at each of lines (1-based) that holds one, as
  {:text :lines}, :lines being the lines that got one. With range
  {:start :end}, only that part of text is answered (and only breakpoints
  wholly inside it count): the code a Run Form or Run Selection sends, read
  in the context of the whole text. opts' :heads as spots takes it, and
  :tab goes in each inserted form.

  Stepping: every other line that can pause in a unit holding a
  breakpoint gets a step point (:steps, their lines), which pauses only
  while stepping. A unit is a top-level form, or a form directly inside a
  (comment ...) block. Units without a breakpoint get none."
  ([text lines] (instrument text lines {}))
  ([text lines {:keys [start end tab] :or {start 0} :as opts}]
   (let [end (or end (count text))
         all (spots text (select-keys opts [:heads]))
         inside? #(and (<= start (:start %)) (<= (:end %) end))
         dots (->> (distinct lines) (keep #(spot-on all %)) (filter inside?))
         dotted (set (map :line dots))
         held (filter (fn [u] (some #(and (<= (:start u) (:start %)) (< (:start %) (:end u))) dots))
                      (units text))
         steps (->> all
                    (filter (fn [sp] (some #(and (<= (:start %) (:start sp)) (< (:start sp) (:end %))) held)))
                    (remove #(dotted (:line %)))
                    (map :line) distinct
                    (keep #(spot-on all %))
                    (filter inside?)
                    (map #(assoc % :step? true)))
         chosen (concat dots steps)
         ;; from the end back, so earlier offsets stay good; at one offset
         ;; an opening goes in first, so the closing ) inserted after it
         ;; ends up in front of it
         ins (sort-by (fn [[o _ order]] [(- o) order]) (mapcat #(edits tab %) chosen))
         sb (StringBuilder. ^String (subs text start end))]
     (doseq [[o s] ins]
       (.insert sb (int (- o start)) ^String s))
     {:text (str sb) :lines (vec (sort (map :line dots))) :steps (vec (sort (map :line steps)))})))

;; --- Live at once: which top-level form a toggle sends again -------------------

(def ^:private defining
  ;; deftype, defrecord, defprotocol and defmulti stay out: sending them
  ;; again makes a new class or dispatch table, which changes the program
  #{"defn" "defn-" "defmacro" "defmethod" "extend-type" "extend-protocol"})

(defn- fn-value?
  "True for a def's value that is literally (fn ...), #(...) or (reify ...)."
  [text node]
  (and node (= :list (kind text node))
       (or (= "#" (prefix text node))
           (contains? #{"fn" "fn*" "reify"} (head-symbol text node)))))

(defn defines-code?
  "True when form (a top-level form of text) only defines code, so sending
  it again runs nothing else: defn, defmethod, deftype and the like, or a
  def or defonce whose value is literally a fn or a reify."
  [text form]
  (and (= :list (kind text form)) (not (quoted? text form))
       (let [n (head-symbol text form)
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

(defn edited?
  "True when the form at rng ({:start :end}) of text has edits that
  haven't run. After a Run, LM3's changed forms against ran (the tab's
  record) say so. In a tab that never ran (ran nil) the form counts as
  edited when the buffer has unsaved edits (:dirty?) or the form isn't in
  the file on disk as it stands (:disk-text, nil without a file, :too-big
  for one too big to read, which counts as edited). Line ends are compared
  as \\n."
  ([text ran rng] (edited? text ran rng {}))
  ([text ran rng {:keys [dirty? disk-text]}]
   (boolean
    (and rng
         (if ran
           (some #(and (= (:start rng) (:start %)) (= (:end rng) (:end %)))
                 (changed/changed text ran))
           (or dirty?
               (= :too-big disk-text)
               (and disk-text
                    (not (str/includes? (str/replace disk-text "\r\n" "\n")
                                        (str/replace (subs text (:start rng) (:end rng)) "\r\n" "\n"))))))))))

;; --- what a toggle says ------------------------------------------------------------

(def NEXT-RUN
  "A toggle's tail when its form wasn't sent again."
  "it takes effect when you next run this form.")

(defn toggle-message
  "The status line for a toggle. t: :added? (else removed), :line, :moved?
  (the breakpoint went to the next line that can hold one), :sent (:ok,
  :failed with :error, :busy, :refused, :edited (not sent: the form has
  edits that haven't run), or nil when the form isn't one to send again) and :debug-on? (the toggle turned debug mode on)."
  [{:keys [added? line moved? sent error debug-on?]}]
  (let [what (cond (not added?) "Breakpoint removed"
                   (= :edited sent) "Breakpoint set"
                   moved? (str "Breakpoint moved to line " line)
                   :else (str "Breakpoint set at line " line))
        tail (case sent
               :ok (if added? "it's live now." "the form runs without it now.")
               :failed (str "sending its form again failed, so " NEXT-RUN
                            (when error (str " " error)))
               :busy (str "something is running, so " NEXT-RUN)
               :refused NEXT-RUN
               :edited "this form has edits that haven't run, so Run Form to apply both."
               NEXT-RUN)]
    (str "; " what "; " tail
         (when debug-on? " Debug mode is on now, so frames keep their locals."))))
