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
   "No stdin and no inspector; jump to definition stays in this file."
   "Completion offers this file's names, Clojure core's and, once something ran, every loaded namespace's."])

(def browser-limitations
  ["Jonquil starts and stops its own ClojureScript process; your code runs in the browser tab it opened."
   "Stop restarts ClojureScript; reload the tab afterwards. A loop that never ends freezes the tab until you reload it."
   "println and js/console.log output reach this console, even after an evaluation finished; console.log still logs in the browser too."
   "No stdin and no inspector; jump to definition stays in this file."])

(defn opened-message [url]
  (str "; Opened " url " in your browser. Evaluations wait until the page connects."))

(defn reload-message [url]
  (str "; ClojureScript restarted: reload the browser tab at " url "."))

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
  A :browser target appends `browser <http-port>` for the launcher."
  [{:keys [clojure launcher add-dep? target http-port]}]
  (vec (concat [clojure]
               (when add-dep? ["-Sdeps" default-deps])
               ["-M" "-i" launcher "-m" "jonquil.cljs-node-launch"]
               (when (= :browser target) ["browser" (str http-port)]))))

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
  own and cljs.core's (:user), alias/name for a namespace the buffer's ns
  form aliases (:user), the bare name the buffer refers (:user), and the
  full qualified name only when prefix contains / or . (:remote, so it
  sorts after everything offline). Case-insensitive prefix match, the
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
                         (or (= ns-str pns) (= ns-str "cljs.core")) (conj [nm :user])
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
