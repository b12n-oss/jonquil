(ns jonquil.cljd
  "Pure decisions for the ClojureDart attach dialect: the console's
  sentences, the buffer's ns form, NREPL.lock's port, the fresh-session
  wake-up probe, and a load's one-line summary. No sockets and no GTK:
  jonquil.nrepl.attach hands in text and reply vectors (jonquil.nrepl.client
  messages, top-level keys keywords).

  Reply shapes were measured against the ClojureDart
  nREPL fork on
  2026-09-28: a load-file answers one :value per top-level form, an :ns
  message after each, and stops at the first form that fails with :err and
  an eval-error status."
  (:require [clojure.string :as str]
            [jonquil.nrepl.ops :as ops]
            [jonquil.syntax :as syntax]))

(def default-ns
  ;; A fresh session starts here: every probe's first reply named it.
  "cljd.user")

;; --- the console's sentences -------------------------------------------------

(def not-running-message
  "; ClojureDart app isn't running here. Start it with `bb dev:macos` (or `clj -M:cljd flutter`).")

(defn refused-message [port]
  (str "; NREPL.lock says port " port ", but nothing answers there. The app may have quit; restart it."))

(defn not-cljd-message [port]
  (str "; Port " port " isn't a ClojureDart nREPL server."))

(def bad-port-file-message
  "; NREPL.lock doesn't hold a port number, so Jonquil can't reach the app.")

(def no-project-message
  "; This file isn't inside a ClojureDart project (no pubspec.yaml above it), so there's no NREPL.lock to read.")

(defn connecting-message [port]
  (str "; Connecting to the app on port " port "..."))

(def still-waiting-message "; Still waiting on the app...")

(def detached-message
  "; Detached from the app. The app wasn't touched; a change already sent may still land.")

(def lost-connection "Lost the connection to the app.")

(def save-first-message
  "Save the file first: the app's error messages and its watcher both go by the file's path.")

(def experimental-message
  "; ClojureDart support is experimental. Jonquil connects to the running app on the first Run.")

;; --- the buffer's namespace ------------------------------------------------

(defn- discard-or-quoted?
  "True when the nearest non-whitespace text immediately before position
  pos in text is a #_ discard or a reader prefix (' ` ~ @), so a top-level
  (...) there reads as data, not as a candidate ns form. tokenize has no
  token for #, ' or `, so those prefixes are otherwise invisible to
  ns-form's scan. comment-spans are the buffer's [start end) comment
  token ranges: a prefix character that only appears at the end of a
  header comment (\";; see 'README'\") isn't a real prefix."
  [^String text pos comment-spans]
  (let [i (loop [i (dec (long pos))]
            (if (and (>= i 0) (Character/isWhitespace (.charAt text i)))
              (recur (dec i))
              i))]
    (and (>= i 0)
         (not (some (fn [[s e]] (and (<= s i) (< i e))) comment-spans))
         (let [ch (.charAt text i)]
           (or (contains? #{\' \` \~ \@} ch)
               (and (= \_ ch) (pos? i) (= \# (.charAt text (dec i)))))))))

(defn- caret-before?
  "True when the nearest non-whitespace character in text before pos is
  ^, so the :bracket token starting at pos is a ^{...} metadata map, not
  a bare map literal."
  [^String text pos]
  (let [i (loop [i (dec (long pos))]
            (if (and (>= i 0) (Character/isWhitespace (.charAt text i)))
              (recur (dec i))
              i))]
    (and (>= i 0) (= \^ (.charAt text i)))))

(defn- skip-past
  "The first toks index at or after j whose token starts past close (a
  matched bracket's own closing position), so a ^{...} metadata map's
  tokens -- its keys, values and closing brace -- are skipped in one
  jump rather than one at a time."
  [toks j close]
  (if-let [tok (get toks j)]
    (if (<= (long (second tok)) (long close)) (recur toks (inc j) close) j)
    j))

(defn- skip-ns-metadata
  "toks index start-i advanced past every :metatag token and ^{...}
  metadata map (a bare ^ right before { isn't tokenized at all, so a
  map's own :bracket token only counts as metadata when caret-before?
  finds the ^ that introduces it) -- the prefix can stack, as in
  ^:a ^{:doc ...} name. Returns the ns form's name token index, or an
  out-of-range index when the metadata never ends."
  [^String text toks matches start-i]
  (loop [j start-i]
    (let [[t s] (get toks j)]
      (cond
        (= :metatag t) (recur (inc j))
        (and (= :bracket t) (= \{ (.charAt text (int s))) (caret-before? text (long s)) (matches (long s)))
        (recur (skip-past toks (inc j) (matches (long s))))
        :else j))))

(defn ns-form
  "The buffer's first top-level (ns ...) form as {:name str :text str}, or
  nil when there's none, or it's never closed. Brackets inside strings and
  comments don't count, and metadata before the name is skipped, including
  a ^{...} metadata map, which can stack with ^:keyword tags. A
  discarded (#_) or quoted/reader-prefixed ('/`/~/@) top-level form is
  skipped, since it isn't the buffer's real ns form."
  [text]
  (when-not (str/blank? text)
    (let [raw-toks (syntax/tokenize text)
          comment-spans (keep (fn [[t s e]] (when (= :comment t) [s e])) raw-toks)
          toks (filterv (fn [[t]] (not= :comment t)) raw-toks)
          {:keys [matches bracket-tokens]} (syntax/compute-brackets text)
          top-opens (into #{} (keep (fn [[s _ depth kind]]
                                      (when (and (= :open kind) (zero? depth) (= \( (.charAt ^String text (int s))))
                                        (long s))))
                          bracket-tokens)
          word (fn [i] (when-let [[_ s e] (get toks i)] (subs text s e)))]
      (some (fn [i]
              (let [[t s] (nth toks i)]
                (when (and (= :bracket t) (contains? top-opens (long s)) (= "ns" (word (inc i)))
                           (not (discard-or-quoted? text (long s) comment-spans)))
                  (when-let [close (get matches (long s))]
                    (let [name-i (skip-ns-metadata text toks matches (+ i 2))
                          [nt ns-start ns-end] (get toks name-i)]
                      (when (#{:symbol :special-form :builtin} nt)
                        {:name (subs text ns-start ns-end)
                         :text (subs text s (inc close))}))))))
            (range (count toks))))))

(defn target-ns
  "What Run Selection and Run Form evaluate in: the buffer's ns form, or
  (ns cljd.user) when the buffer has none. {:name str :text str}."
  [text]
  (or (ns-form text) {:name default-ns :text (str "(ns " default-ns ")")}))

;; --- NREPL.lock ------------------------------------------------------------

;; --- replies ---------------------------------------------------------------

(def probe-code
  ;; Jonquil's own first eval on a fresh session: never the user's code, so
  ;; nothing the user wrote is ever sent twice.
  "(+ 1 2)")

(def not-awake-message "; The app's REPL didn't wake up; try Run again.")

(defn probe-result
  ":awake when the probe's replies carry the value \"3\"; :asleep when
  they carry no value or the printed nil, with no err, no eval-error and no
  lost connection (the fork's connect skill: the first eval after a hot
  restart can answer nil, and evaluating again wakes it); :lost for a lost
  connection; :failed otherwise."
  [msgs]
  (let [st (ops/statuses msgs)
        vals (vec (keep :value msgs))]
    (cond
      (some #{"3"} vals) :awake
      (st ops/disconnected) :lost
      (and (contains? #{[] ["nil"]} vals) (not (some :err msgs)) (not (st "eval-error"))) :asleep
      :else :failed)))

(defn eval-result
  "jonquil.eval's result shape for an eval's replies, mirroring load-result:
  Stop plus a lost connection reads as :interrupted; a lost connection
  alone reads as the app's own sentence; otherwise ops/eval-result decides,
  never told Stop was requested. A cljd Stop never sends interrupt, so an
  eval-error in the app's own replies is never caused by Stop."
  [msgs stop-requested?]
  (let [st (ops/statuses msgs)]
    (cond
      (and stop-requested? (st ops/disconnected)) {:status :interrupted}
      (st ops/disconnected) {:status :error :error-text lost-connection}
      :else (ops/eval-result msgs false))))

(defn load-result
  "A load-file's replies as one result, quiet: a :notice line on success
  instead of a value per form. ns-name names what was loaded (the buffer's
  ns form, else its file name). The form count is the number of values
  received, so a stop names the form after the last value (measured:
  format.cljd answered nine values and failed on its tenth form)."
  [msgs ns-name stop-requested?]
  (let [st (ops/statuses msgs)
        n (count (keep :value msgs))]
    (cond
      (and stop-requested? (st ops/disconnected)) {:status :interrupted}
      (st ops/disconnected) {:status :error :error-text lost-connection}
      (st "eval-error") {:status :error :error-text (str "Load stopped at form " (inc n) " of " ns-name)}
      :else {:status :notice
             :text (str "; Loaded " ns-name " (" n (if (= 1 n) " form)" " forms)"))})))

;; --- quick doc ---------------------------------------------------------------

(def no-lookup-doc
  "No docs for ClojureDart symbols yet (the app's ClojureDart fork doesn't answer lookup)")

(def not-attached-doc "Not attached; Run first")

(def lookup-timeout-doc "No docs: the app didn't answer in time")

(defn no-docs
  "A doc-info map that jonquil.syntax/format-autocomplete-doc shows as sym,
  a rule, then text. Its :status is neither :found nor :not-found, so jump
  to definition treats it as no external definition."
  [sym text]
  {:status :no-docs :symbol sym :name sym :doc text})

(defn doc-result
  "Quick doc's doc-info for a lookup's replies (fork 685e56de and later
  answer {:info {\"ns\" \"name\" \"doc\" \"arglists\" \"arglists-str\"
  \"macro\"}}): the var's doc when found, \"No docs for sym\" for no-info,
  and the older-fork sentence for lookup-error, unknown-op or anything
  else."
  [sym msgs]
  (let [st (ops/statuses msgs)
        info (some :info msgs)]
    (cond
      (st "no-info") (no-docs sym (str "No docs for " sym))
      :else (if-let [d (ops/doc-info sym msgs)]
              (cond-> d (= "true" (or (get info "macro") (get info :macro))) (assoc :macro? true))
              (no-docs sym no-lookup-doc)))))

;; --- offline completion ------------------------------------------------------

(defn buffer-aliases
  "The aliases the buffer's ns form declares with :as or :as-alias, in
  order, without repeats. [] when there's no ns form."
  [text]
  (if-let [form (:text (ns-form text))]
    (let [toks (filterv (fn [[t]] (not= :comment t)) (syntax/tokenize form))
          word (fn [[_ s e]] (subs form s e))]
      (vec (distinct
            (keep (fn [[a b]]
                    (when (and (= :keyword (first a)) (#{":as" ":as-alias"} (word a))
                               (#{:symbol :special-form :builtin} (first b)))
                      (word b)))
                  (partition 2 1 toks)))))
    []))

(defn- ns-ref-tokens
  "form's tokens without comments, each as [type word]; a string's quotes
  are dropped from its word."
  [form]
  (->> (syntax/tokenize form)
       (filterv (fn [[t]] (not= :comment t)))
       (mapv (fn [[t s e]] (let [w (subs form s e)]
                             [t (if (= :string t) (str/replace w "\"" "") w)])))))

(defn- ns-ref-lib-after
  "The lib word of the vector that opens at token index i: its first symbol
  or string, skipping metatags. nil when something else comes first."
  [toks i]
  (loop [j (inc i)]
    (when-let [[t] (get toks j)]
      (cond (#{:symbol :special-form :builtin :string} t) (second (get toks j))
            (= :metatag t) (recur (inc j))
            :else nil))))

(defn- ns-ref-alias-at
  "The alias word when token i is :as or :as-alias, in a vector with a lib,
  followed by a symbol. nil otherwise."
  [toks i lib]
  (let [[t w] (get toks i)]
    (when (and lib (= :keyword t) (#{":as" ":as-alias"} w))
      (let [[t2 w2] (get toks (inc i))]
        (when (#{:symbol :special-form :builtin} t2) w2)))))

(defn- ns-ref-refer-at?
  "True when token i is :refer, in a vector with a lib, followed by a [."
  [toks i lib]
  (let [[t w] (get toks i)
        [t2 w2] (get toks (inc i))]
    (boolean (and lib (= :keyword t) (= ":refer" w)
                  (= :bracket t2) (= "[" w2)))))

(defn- ns-ref-collect-refers
  "Walks the :refer list from token index j, mapping each name to lib in
  acc. Returns [next-index acc]; next-index is just past the closing ]."
  [toks [j lib acc]]
  (loop [j j acc acc]
    (if-let [[t2 w2] (get toks j)]
      (if (and (= :bracket t2) (= "]" w2))
        [(inc j) acc]
        (recur (inc j)
               (if (#{:symbol :special-form :builtin} t2)
                 (assoc-in acc [:refers w2] lib)
                 acc)))
      [j acc])))

(defn- ns-ref-step
  "One step of buffer-ns-refs' walk over toks at index i, with the walk's
  [i lib acc]. Returns the next [i lib acc]."
  [toks [i lib acc]]
  (let [[t w] (get toks i)
        alias-word (ns-ref-alias-at toks i lib)]
    (cond
      ;; a vector opens: its first symbol or string is the lib
      (and (= :bracket t) (= "[" w))
      [(inc i) (ns-ref-lib-after toks i) acc]
      (and (= :bracket t) (= "]" w))
      [(inc i) nil acc]
      (some? alias-word)
      [(+ i 2) lib (assoc-in acc [:aliases alias-word] lib)]
      ;; :refer [a b]: each name maps to lib; :refer :all is skipped
      (ns-ref-refer-at? toks i lib)
      (let [[j acc] (ns-ref-collect-refers toks [(+ i 2) lib acc])]
        [(long j) lib acc])
      :else [(inc i) lib acc])))

(defn buffer-ns-refs
  "The buffer's ns-form require map: {:aliases {alias-str ns-str}
  :refers {name-str ns-str}} from each [lib :as a], [lib :as-alias a] and
  [lib :refer [x y]] vector (:refer :all contributes nothing; a string lib
  such as \"dart:io\" counts without its quotes). {:aliases {} :refers {}}
  when there's no ns form, and junk never throws."
  [text]
  (let [empty-refs {:aliases {} :refers {}}]
    (try
      (if-let [form (:text (ns-form text))]
        (let [toks (ns-ref-tokens form)
              n (count toks)]
          (loop [i 0 lib nil acc empty-refs]
            (if (>= i n)
              acc
              (let [[i lib acc] (ns-ref-step toks [i lib acc])]
                (recur i lib acc)))))
        empty-refs)
      (catch Throwable _ empty-refs))))

(defn candidate-sort-key
  "candidates' sort key for cand under the typed prefix: exact-prefix
  matches first, then category (:user, :special, :builtin, :core for a
  core name only the analyzer knows, anything else last), then the name,
  case-insensitively. :core and :remote rank after :builtin on purpose:
  the analyzer's names append to today's offline order, never reorder it."
  [prefix {:keys [symbol category]}]
  [(if (str/starts-with? symbol prefix) 0 1)
   (case category :user 0 :special 1 :builtin 2 :core 3 4)
   (str/lower-case symbol)
   symbol])

(defn candidates
  "Offline completion for a ClojureDart buffer, in jonquil.docs/candidates'
  shape ([{:symbol :category}]): the buffer's own definitions and symbols,
  those in extra-context (the REPL's history, a string) too, each alias of
  the buffer's ns form followed by /, and profile's :special-forms and
  :builtins. It never asks the in-process Jolt runtime (no ns-publics, no
  jonquil.docs), so nothing Jolt has and ClojureDart lacks shows up.
  Sorted the way jonquil.syntax/get-autocomplete-candidates sorts a
  prefix: exact-case matches first, then buffer names, special forms and
  builtins."
  [profile prefix text _pos extra-context]
  (let [prefix (or prefix "")
        lower (str/lower-case prefix)
        match? (fn [s] (str/starts-with? (str/lower-case s) lower))
        {:keys [special-forms builtins]} profile
        data (syntax/get-cached-or-compute-buffer-symbols (or text ""))
        extra (when (string? extra-context) (syntax/get-cached-or-compute-buffer-symbols extra-context))
        user (-> #{}
                 (into (:all-tokens data)) (into (:defs data))
                 (into (:all-tokens extra)) (into (:defs extra))
                 (into (map (fn [a] (str a "/")) (buffer-aliases text)))
                 (disj prefix))
        cands (concat
               (for [s user :when (and (match? s) (not (contains? special-forms s)) (not (contains? builtins s)))]
                 {:symbol s :category :user})
               (for [s special-forms :when (match? s)] {:symbol s :category :special})
               (for [s builtins :when (and (match? s) (not (contains? special-forms s)))]
                 {:symbol s :category :builtin}))]
    (vec (sort-by (fn [c] (candidate-sort-key prefix c)) cands))))
