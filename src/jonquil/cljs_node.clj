(ns jonquil.cljs-node
  "Pure helpers for ClojureScript on node: the console's sentences, the
  launch arguments, the readiness line, and readers for the prepl replies
  (EDN lines from cljs.repl's prepl). No sockets, no processes and no GTK:
  receives text and maps."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [jonquil.cljd :as cljd]))

;; --- the console's sentences ---

(defn starting-message [clojure-path]
  (str "; Starting ClojureScript (" clojure-path ")..."))

(def no-clojure-message
  "; ClojureScript on node needs the Clojure CLI (clojure). Install it or set JONQUIL_CLOJURE to its path.")

(defn start-failed-message [text]
  (str "; ClojureScript didn't start: " text))

(def reset-message
  "; Reset on Run: ClojureScript restarts, so this Run starts clean.")

(def stopped-message
  "; Stopped. ClojureScript restarted, so earlier definitions are gone.")

(def limitations
  ["Jonquil starts and stops its own ClojureScript process."
   "Stop restarts it, so earlier definitions are gone afterwards."
   "read-line waits for a line typed at this prompt; Cmd/Ctrl+D ends input for one read. No inspector; jump to definition stays in this file."
   "Completion offers this file's names, Clojure core's and, once something ran, every loaded namespace's."])

(defn browser-limitations
  "The browser target's limitation lines; console? is the selection's
  :cljs-console? (false swaps the forwarding line for where the output
  actually goes)."
  [console?]
  ["Jonquil starts and stops its own ClojureScript process; your code runs in the browser tab it opened."
   "Stop restarts ClojureScript; the tab reloads itself. A loop that never ends freezes the tab until you reload it by hand."
   (if console?
     "println and js/console.log output reach this console, even after an evaluation finished; console.log still logs in the browser too."
     "println output reaches this console, even after an evaluation finished; js/console.log stays in the browser (forward-console? is off).")
   "No stdin and no inspector; jump to definition stays in this file."])

(defn opened-message [url]
  (str "; Opened " url " in your browser. Evaluations wait until the page connects."))

(defn reload-message [url]
  (str "; ClojureScript restarted: the browser tab at " url
       " reloads itself (reload by hand if it was frozen or closed,"
       " or stopped checking after an idle hour)."))

(defn no-page-message
  "Shown when a setup waited the page bound and no page connected."
  [url]
  (str "; No page has connected yet. Open " url " in a browser, then Run again."))

(defn waiting-line?
  "True for the :out reply ClojureScript prints while it waits for a page."
  [reply]
  (and (= :out (:tag reply))
       (str/starts-with? (str (:val reply)) "Waiting for browser to connect to ")))

;; --- launch ---

(def default-deps
  "{:deps {org.clojure/clojurescript {:mvn/version \"1.12.42\"}}}")

(defn needs-cljs-dep?
  "True when the deps.edn text is missing, unreadable, or has no
  org.clojure/clojurescript dependency."
  [deps-edn-text]
  (let [edn-map (when deps-edn-text
                  (try (edn/read-string deps-edn-text) (catch Exception _ nil)))]
    (nil? (get-in edn-map [:deps 'org.clojure/clojurescript]))))

(defn launch-args
  "The argv that starts the ClojureScript launcher. The -Sdeps pair is
  added only when add-dep? is true (the project has no ClojureScript).
  A :browser target appends `browser <http-port>` for the launcher, plus
  `no-console` when :console? is false ({:cljs {:forward-console? false}}),
  which keeps the page's console out of Jonquil, plus `token:<uuid>` when
  :page-token is given (the window's token; the launcher gates stale
  tabs with it). Node ignores both."
  [{:keys [clojure launcher add-dep? target http-port console? page-token]}]
  (vec (concat [clojure]
               (when add-dep? ["-Sdeps" default-deps])
               ["-M" "-i" launcher "-m" "jonquil.cljs-node-launch"]
               (when (= :browser target)
                 (cond-> ["browser" (str http-port)]
                   (false? console?) (conj "no-console")
                   (and (string? page-token) (not (str/blank? page-token)))
                   (conj (str "token:" page-token)))))))

(defn page-url
  "The address of a browser window's page: the token names the window, so
  a stale tab's reload against a port a NEW window recycled is turned
  away (the launcher's gate), while the same window's restarts keep
  serving it."
  [http-port token]
  (str "http://127.0.0.1:" http-port "/?t=" token))

(defn ready-port
  "The port from the last line that announces `jonquil-cljs-prepl <port>`,
  or nil when no line does."
  [log-text]
  (some->> (str/split-lines (str log-text))
           (keep #(second (re-find #"jonquil-cljs-prepl (\d+)" %)))
           last
           parse-long))

(defn ready-url
  "The http URL from the last `jonquil-cljs-prepl <port> <url>` line, or nil
  when no line carries one (node mode prints the port only)."
  [log-text]
  (some->> (str/split-lines (str log-text))
           (keep #(second (re-find #"jonquil-cljs-prepl \d+ (http://\S+)" %)))
           last))

(def setup-form
  "(require 'cljs.repl)")

;; --- read-line over the launcher's FIFO (stdin design, 2026-10-23) --------

(def need-input-marker
  "Printed via *print-err-fn* just before read-line blocks; dispatch!
  strips it and flips waiting-input? (never a raw fd write: the node
  child's fd 2 is pumped nowhere, measured)."
  "\u0001jonquil:need-input\u0001")

(def got-input-marker
  "Printed via *print-err-fn* right after a read returns."
  "\u0001jonquil:got-input\u0001")

(def input-eof-marker
  "The line send-eof! writes: the reader answers nil for it, freeing ONE
  read (Jolt parity), while the relay pipe stays open for later reads."
  "\u0001jonquil:eof\u0001")

(defn ready-stdin
  "The stdin token of the last ready line: the FIFO's absolute path,
  :none when the launcher couldn't make one, nil when the line carries no
  token (an older launcher, or the browser line)."
  [log-text]
  (some->> (str/split-lines (str log-text))
           (keep #(second (re-find #"jonquil-cljs-prepl \d+.* stdin:(\S+)" %)))
           last
           (#(if (= "none" %) :none %))))

(defn reader-setup-text
  "The forms (newline-joined, ending with sentinel-form) that define
  cljs.core/read-line as a blocking FIFO read bracketed by the markers.
  The in-ns/declare pair makes plain (read-line) compile everywhere with
  no warning (spike finding 3)."
  [fifo-path sentinel-form]
  (str/join "\n"
            ["(in-ns 'cljs.core)"
             "(declare read-line)"
             "(in-ns 'cljs.user)"
             (str "(set! cljs.core/read-line"
                  " (fn []"
                  " (let [fs (js/require \"fs\")]"
                  " (*print-err-fn* " (pr-str (str need-input-marker "\n")) ")"
                  " (let [fd (.openSync fs " (pr-str fifo-path) " \"r\")"
                  " buf (js/Buffer.alloc 65536)"
                  " n (.readSync fs fd buf 0 65536 nil)"
                  " _ (.closeSync fs fd)"
                  ;; cut at indexOf, never split-lines: cljs split-lines
                  ;; pops the trailing "" of "\n", so an empty line would
                  ;; answer nil, as Cmd/Ctrl+D does (review I1, measured
                  ;; against ClojureScript 1.12.42 and live node)
                  " s (.toString buf \"utf8\" 0 n)"
                  " i (.indexOf s \"\\n\")"
                  " line (if (neg? i) s (.substring s 0 i))]"
                  " (*print-err-fn* " (pr-str (str got-input-marker "\n")) ")"
                  " (when-not (or (zero? n) (= line " (pr-str input-eof-marker) "))"
                  " line)))))")
             sentinel-form]))

(defn reader-unavailable-text
  "The same framing, but read-line throws a clear message: the session
  works, and a call site fails loudly instead of a ReferenceError."
  [reason sentinel-form]
  (str/join "\n"
            ["(in-ns 'cljs.core)"
             "(declare read-line)"
             "(in-ns 'cljs.user)"
             (str "(set! cljs.core/read-line"
                  " (fn [] (throw (js/Error. "
                  (pr-str (str "read-line isn't available: " reason)) "))))")
             sentinel-form]))

(defn strip-input-markers
  "[clean-text events] for an :err chunk: every marker followed by its
  newline is removed wherever it sits in the chunk (not only as a whole
  line: a chunk may coalesce a marker with user stderr), events is the
  markers' order (:need-input, :got-input), and everything else is kept
  byte-for-byte. User text containing a marker and newline is swallowed
  the same way, the accepted cost of U+0001 framing."
  [s]
  (let [need (str need-input-marker "\n")
        got (str got-input-marker "\n")]
    (loop [s (str s) events []]
      (let [ni (str/index-of s need)
            gi (str/index-of s got)]
        (cond
          (and ni (or (nil? gi) (< ni gi)))
          (recur (str (subs s 0 ni) (subs s (+ ni (count need))))
                 (conj events :need-input))
          gi
          (recur (str (subs s 0 gi) (subs s (+ gi (count got))))
                 (conj events :got-input))
          :else [s events])))))

(defn sentinel
  "The form that ends an evaluation. Its ret marks where the buffer's output ends."
  [n]
  (str ":jonquil.eval/end-" n))

;; --- replies ---

(defn- read-edn-val [s]
  (try (edn/read-string {:default (fn [_tag v] v)} s)
       (catch Exception _ nil)))

(defn parse-reply
  "A prepl reply line as a map with :tag, or nil when the line doesn't read
  or isn't a map with :tag."
  [line]
  (let [v (read-edn-val line)]
    (when (and (map? v) (contains? v :tag))
      v)))

(defn- last-line [s]
  (let [line (last (remove str/blank? (str/split-lines (str s))))]
    (some-> line str/trim)))

(defn error-line
  "The one-line message for a failing ret. Reads its :val (the exception as
  EDN) and takes the :cause, else the last :via entry's :message, and keeps
  that message's last non-blank line."
  [ret]
  (let [v (read-edn-val (:val ret))
        msg (or (:cause v) (:message (last (:via v))))]
    (if-let [line (and msg (last-line msg))]
      (str "Error: " line)
      "Error: evaluation failed")))

(defn fold-replies
  "Folds one evaluation's prepl replies (parsed maps, in arrival order) into
  jonquil.eval's result shape. Only :ret replies count. Rets up to the
  sentinel's ret are the evaluated forms; the sentinel's ret ends them and is
  never the printed value. A failing form makes an :error result whose text is
  the first failure's error-line. :ns comes from the last ret that has one."
  [replies sentinel-form]
  (let [rets (filter #(= :ret (:tag %)) replies)
        body (take-while #(not= sentinel-form (:form %)) rets)
        cur-ns (some->> (keep :ns rets) last symbol)]
    (if-let [failing (first (filter :exception body))]
      (cond-> {:status :error :error-text (error-line failing)}
        cur-ns (assoc :ns cur-ns))
      (cond-> {:status :ok
               :printed (if (seq body) (:val (last body)) "nil")}
        cur-ns (assoc :ns cur-ns)))))

(defn output-of
  "[kind text] for a reply that prints: :out and :err as they are, :tap as
  a \"tap> \" line on stdout. Nil for every other reply."
  [reply]
  (case (:tag reply)
    :out [:out (:val reply)]
    :err [:err (:val reply)]
    :tap [:out (str "tap> " (:val reply) "\n")]
    nil))

;; --- analyzer completion (the compiler side knows every loaded ns) -------

(def apropos-form
  "Asks the compiler for every public var: cljs.repl/apropos is a macro, so
  the analyzer answers at compile time and the runtime returns a literal.
  Measured 2026-10-22: 1,302 symbols, 28,189 chars printed, 23-44 ms."
  "(cljs.repl/apropos #\"\")")

(def ^:private syms-cap
  "The most analyzer symbols kept from one fetch. estimate: 15x a fresh
  session's 1,302."
  20000)

(defn parse-apropos
  "The apropos ret's :val as a vector of symbols, capped at syms-cap;
  nil for anything else (unreadable, not a sequence, non-symbols)."
  [val]
  (when (string? val)
    (let [v (try (edn/read-string val) (catch Throwable _ nil))]
      (when (and (sequential? v) (seq v) (every? symbol? v))
        (vec (take syms-cap v))))))

(defn analyzer-candidates
  "Completion candidates from the analyzer's qualified symbols (syms),
  spelled the way the buffer can use them: a short name for prompt-ns's
  own (:user) and cljs.core's (:core, ranking after the offline builtins,
  so a core name the offline list lacks can't outrank it: typing ma must
  still land on map), alias/name
  for a namespace the buffer's ns form aliases (:user), the bare name the
  buffer refers (:user), and the full qualified name only when prefix
  contains / or . (:remote, so it sorts after everything offline). Case-insensitive prefix match, the
  prefix itself dropped, distinct by :symbol, unsorted. Never throws."
  [{:keys [syms prompt-ns text prefix]}]
  (let [prefix (or prefix "")
        lower (str/lower-case prefix)
        match? (fn [s] (and (not= s prefix) (str/starts-with? (str/lower-case s) lower)))
        {:keys [aliases refers]} (cljd/buffer-ns-refs text)
        ns->alias (reduce-kv (fn [m a n] (assoc m n a)) {} aliases)
        qualified-ok? (or (str/includes? prefix "/") (str/includes? prefix "."))
        pns (str (or prompt-ns ""))]
    (->> (for [sym syms
               :let [ns-str (namespace sym) nm (name sym)]
               :when ns-str
               [s cat] (cond-> []
                         (= ns-str pns) (conj [nm :user])
                         (and (= ns-str "cljs.core") (not= ns-str pns)) (conj [nm :core])
                         (contains? ns->alias ns-str) (conj [(str (get ns->alias ns-str) "/" nm) :user])
                         (= ns-str (get refers nm)) (conj [nm :user])
                         qualified-ok? (conj [(str sym) :remote]))
               :when (match? s)]
           {:symbol s :category cat})
         (reduce (fn [[seen out] c]
                   (if (contains? seen (:symbol c))
                     [seen out]
                     [(conj seen (:symbol c)) (conj out c)]))
                 [#{} []])
         second)))
