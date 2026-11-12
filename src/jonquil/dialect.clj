(ns jonquil.dialect
  "Dialect profiles and the per-project choice between them. A profile is
  plain data: what a dialect's files look like, how to paint them, and which
  evaluator runs them. jonquil.syntax reads the Jolt profile's
  :special-forms and :builtins, so the profile is where those sets live.

  Selection is per project. The project is the nearest directory at or
  above a file holding .jonquil.edn or a profile's marker. Its
  .jonquil.edn {:dialect k :runtime k} wins; otherwise a single matching
  marker decides, but only when its profile claims the file's extension or
  the file is untitled; otherwise the file's extension breaks the tie (a
  loose file has no markers); otherwise the settings default; otherwise the
  first profile.
  A malformed .jonquil.edn never throws: it becomes a warning and the rest
  of the rules decide."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def jolt
  {:id :jolt
   :name "Jolt"
   :extensions #{"clj" "cljc"}
   ;; measured 2026-09-27 on jolt v0.8.12-60: #?(:jolt ..) and #?(:clj ..)
   ;; both select, #?(:cljs ..) falls to :default.
   :reader-features #{:jolt :clj}
   ;; Moved verbatim from jonquil.syntax (upstream DrClojure 41c56b8), which
   ;; reads them from here.
   :special-forms
   #{"def" "defn" "defn-" "defmacro" "defmulti" "defmethod" "defprotocol"
     "defrecord" "deftype" "definterface" "defonce"
     "let" "loop" "recur" "binding" "with-open" "with-local-vars" "with-redefs"
     "with-out-str" "with-in-str"
     "if" "if-not" "if-let" "when" "when-not" "when-let" "when-first"
     "when-some" "if-some" "cond" "condp" "case" "do"
     "fn" "fn*"
     "doseq" "dotimes" "for" "while"
     "try" "catch" "finally" "throw"
     "ns" "in-ns" "require" "use" "import" "refer"
     "quote" "var" "set!"}
   :builtins
   #{"println" "print" "prn" "pr" "printf" "read-line" "read-string" "slurp" "spit"
     "map" "filter" "reduce" "remove" "take" "drop" "take-while" "drop-while"
     "concat" "into" "assoc" "dissoc" "get" "get-in" "assoc-in" "update" "update-in"
     "conj" "disj" "contains?" "count" "empty?" "seq" "first" "rest" "next" "last"
     "nth" "peek" "pop" "cons" "vec" "set" "hash-map" "vector" "list"
     "keys" "vals" "select-keys" "zipmap" "merge" "merge-with" "sort" "sort-by"
     "group-by" "partition" "partition-by" "flatten" "distinct" "reverse"
     "interleave" "interpose" "keep"
     "nil?" "some?" "true?" "false?" "string?" "number?" "integer?" "float?"
     "boolean?" "keyword?" "symbol?" "fn?" "vector?" "map?" "set?" "seq?" "list?"
     "coll?" "pos?" "neg?" "zero?" "even?" "odd?"
     "+" "-" "*" "/" "inc" "dec" "max" "min" "quot" "rem" "mod" "abs"
     "=" "==" "not=" "<" "<=" ">" ">=" "not" "and" "or"
     "atom" "deref" "swap!" "reset!" "swap-vals!" "reset-vals!"
     "future" "promise" "deliver" "realized?" "delay" "force"
     "apply" "partial" "comp" "constantly" "identity" "juxt" "memoize" "complement"
     "doc" "source" "apropos" "dir" "find-doc" "format" "str"}
   :project-markers ["deps.edn"]
   ;; The evaluator a window gets when .jonquil.edn names no :runtime, and
   ;; the runtimes it may name. Inside a project holding one of this
   ;; profile's own markers, :project-evaluator wins instead: Jolt runs out
   ;; of process there, so a program's System/exit, shutdown-agents or
   ;; redefined clojure.core can't reach Jonquil itself (measured
   ;; 2026-10-18). A loose file or untitled buffer has no project folder to
   ;; run a child in, so it stays in process.
   :evaluator :in-process
   :project-evaluator :nrepl
   :runtimes #{:in-process :nrepl}})

(def ^:private cljd-special-forms
  ;; ClojureDart's own forms, added to Jolt's set for the :cljd profile.
  ;; Read from the ClojureDart nREPL fork (compiler sources at
  ;; 573781a2), 2026-09-28:
  ;; - clj/src/cljd/compiler.cljc, `emit`'s special-form `case`: every
  ;;   user-facing special Jolt's set lacks (the starred internals like
  ;;   let* and case* are left out).
  ;; - clj/src/cljd/core.cljd: the public macros clojure.core doesn't have
  ;;   (the -map-lit/-set-lit/-vec-lit reader targets are left out).
  ;; - clj/src/cljd/flutter.cljd: its public macros, bare and under the
  ;;   `f` alias, because every one of the reference deck app's 10 files that
  ;;   requires cljd.flutter writes [cljd.flutter :as f].
  #{"." "new" "letfn"
    "dart/is?" "dart/await" "dart/async-barrier" "dart/run-zoned" "dart/assert" "dart/type-like"
    "await" "quick-bench" "ensure-hash"
    "widget" "build" "run" "$" "retriable" "with-notifier" "doto-layer" "doto-image-canvas"
    "f/widget" "f/build" "f/run" "f/$" "f/retriable" "f/with-notifier" "f/doto-layer" "f/doto-image-canvas"})

(def cljd
  {:id :cljd
   :name "ClojureDart"
   ;; .cljc stays Jolt's.
   :extensions #{"cljd"}
   :reader-features #{:cljd}
   :special-forms (into (:special-forms jolt) cljd-special-forms)
   :builtins (:builtins jolt)
   :project-markers ["pubspec.yaml"]
   ;; Attach-only: Jonquil connects to the app's own nREPL server, found
   ;; through :port-file in the project directory, and never starts it.
   :evaluator :attach
   :runtimes #{:attach}
   :port-file "NREPL.lock"
   :experimental true
   ;; The same text as jonquil.cljd/experimental-message (dialect can't
   ;; require cljd); a test keeps the two equal.
   :experimental-message "; ClojureDart support is experimental. Jonquil connects to the running app on the first Run."
   :reset-tooltip "Hot reload can't unload a namespace, so every Run adds to the running app."})

(def ^:private jank-special-forms
  ;; jank's own forms, added to Jolt's set for the :jank profile. Read from
  ;; `special-forms` in jank.nrepl.server.handler.completions
  ;; (compiler+runtime/src/jank/jank/nrepl/server/handler/completions.jank
  ;; at jank 38a6382be, noted there as from jank/read/parse.cpp). Its
  ;; def, fn*, recur, do, if, quote, var, throw and try are already in
  ;; Jolt's set; these are the ones Jolt lacks.
  #{"let*" "loop*" "case*" "letfn*"
    "cpp/raw" "cpp/dsl" "cpp/value" "cpp/cast" "cpp/unsafe-cast"
    "cpp/box" "cpp/unbox" "cpp/new" "cpp/delete"})

(def jank
  {:id :jank
   :name "jank"
   :extensions #{"jank"}
   ;; A guess: jank's reader-conditional feature keyword hasn't been measured.
   :reader-features #{:jank}
   :special-forms (into (:special-forms jolt) jank-special-forms)
   :builtins (:builtins jolt)
   ;; No markers: a .jank file selects it by extension.
   :project-markers []
   ;; Jonquil starts its own jank process on the first Run.
   :evaluator :launch
   :runtimes #{:launch}
   :experimental true
   :experimental-message "; jank support is experimental. Jonquil starts its own jank process on the first Run."})

(def clj
  {:id :clj
   :name "Clojure (JVM)"
   :extensions #{"clj" "cljc"}
   :reader-features #{:clj}
   :special-forms (:special-forms jolt)
   :builtins (:builtins jolt)
   ;; No markers: only .jonquil.edn {:dialect :clj} selects it.
   :project-markers []
   :evaluator :attach
   :runtimes #{:attach}
   :port-file ".nrepl-port"
   :reset-tooltip "This is your own JVM, so Jonquil doesn't unload namespaces in it."})

(def cljs
  {:id :cljs
   :name "ClojureScript"
   :runtime-names {:attach "ClojureScript (shadow-cljs)" :launch "ClojureScript (node)"}
   :extensions #{"cljs"}
   :reader-features #{:cljs}
   :special-forms (:special-forms jolt)
   :builtins (:builtins jolt)
   :project-markers ["shadow-cljs.edn"]
   ;; Two runtimes. :attach connects to the shadow-cljs nREPL server, found
   ;; through :port-file in the project directory, and never starts it; a
   ;; project with shadow-cljs.edn defaults to it (:project-evaluator).
   ;; :launch starts ClojureScript on node, which everything else gets.
   :evaluator :launch
   :project-evaluator :attach
   :runtimes #{:attach :launch}
   :port-file ".shadow-cljs/nrepl.port"
   :experimental true
   ;; The same text as jonquil.cljs/experimental-message (dialect can't
   ;; require cljs); a test keeps the two equal.
   :experimental-message "; ClojureScript support is experimental."
   :reset-tooltip "Hot reload can't unload a namespace, so every Run adds to the running app."
   ;; A runtime whose Reset on Run starts unticked, though it can reset: on
   ;; node a reset restarts the ClojureScript process, which takes seconds.
   :reset-tooltips {:launch "Reset on Run restarts ClojureScript, which takes a few seconds, so it starts off here. Tick it to start every Run clean."}})

(def nrepl
  {:id :nrepl
   :name "nREPL"
   ;; Any nREPL server you started: babashka, a non-JVM Clojure, one on
   ;; another host. Clojure highlighting; nothing JVM-specific assumed.
   :extensions #{}
   :reader-features #{:clj}
   :special-forms (:special-forms jolt)
   :builtins (:builtins jolt)
   ;; No markers and no extension: only .jonquil.edn {:dialect :nrepl}
   ;; selects it (a bb project needs a server Jonquil can't start).
   :project-markers []
   :evaluator :attach
   :runtimes #{:attach}
   ;; Where the port is found when .jonquil.edn's :nrepl names none.
   ;; babashka writes no port file (measured 2026-10-18), so a project
   ;; usually gives {:nrepl {:port N}}.
   :port-file ".nrepl-port"
   :reset-tooltip "This is a server you started, so Jonquil doesn't unload namespaces in it."})

(def profiles
  "Every known profile. Order matters only as the last resort in select:
  Jolt first, so a file neither profile's extension claims (bb.edn in a
  ClojureDart app) stays Jolt's.

  cljs goes last: nothing else claims .cljs."
  [jolt cljd clj jank cljs nrepl])

(defn profile
  "The profile with id k in profiles (default: every known one), or nil."
  ([k] (profile profiles k))
  ([profiles k] (some (fn [p] (when (= k (:id p)) p)) profiles)))

(def config-name ".jonquil.edn")

(def parinfer-modes
  "The values :parinfer may hold, in .jonquil.edn and in settings.edn
  (jonquil.settings reuses this set). :off is everywhere the default."
  #{:off :smart :indent :paren})

(def format-styles
  "The values :format-style may hold in .jonquil.edn, pinning what Format
  All and Format Selection produce for the project's tabs whatever the
  Parinfer mode: :paren-stable always runs the Paren pass over the
  formatter's output, :plain never does. Absent, a format follows the
  tab's Parinfer mode, so the pin exists to keep one repo in one style
  when its people differ on the mode."
  #{:paren-stable :plain})

(defn- marker-names [profiles]
  (into #{config-name} (mapcat :project-markers) profiles))

(defn- read-config
  "[text-or-nil error-message-or-nil] for the .jonquil.edn in dir."
  [dir]
  (let [cfg (io/file dir config-name)]
    (if (.exists cfg)
      (try [(slurp cfg) nil]
           (catch Exception e [nil (ex-message e)]))
      [nil nil])))

(defn- project-in
  "find-project's map for dir when it holds .jonquil.edn or a marker named
  in names, else nil."
  [dir names]
  (let [found (into #{} (filter (fn [n] (.exists (io/file dir n)))) names)]
    (when (seq found)
      (let [[text err] (read-config dir)]
        {:dir (.getPath dir)
         :markers (disj found config-name)
         :config text
         :config-error err}))))

(defn find-project
  "The nearest directory at or above path's own directory holding
  .jonquil.edn or any profile's marker, as {:dir abs-path :markers #{name}
  :config text-or-nil :config-error msg-or-nil}. nil for a nil path or when
  no directory up to the root has one. :dir is canonical and absolute
  (a relative spawn :dir fails silently, measured)."
  ([path] (find-project profiles path))
  ([profiles path]
   (when path
     (let [names (marker-names profiles)]
       (loop [dir (.getParentFile (io/file (.getCanonicalPath (io/file path))))]
         (when dir
           (or (project-in dir names)
               (recur (.getParentFile dir)))))))))

(defn- auto-require-part
  "[symbols-or-nil warning-or-nil] for .jonquil.edn's :auto-require: a
  non-empty vector of symbols, else nil (absent or [] says nothing; any
  other shape is a warning)."
  [v]
  (cond
    (and (vector? v) (every? symbol? v)) [(not-empty v) nil]
    (nil? v) [nil nil]
    :else [nil (str ".jonquil.edn :auto-require should be a vector of namespace symbols, like [my.app.core], not "
                    (pr-str v) ", so it is ignored")]))

(defn- host-ok?
  "True for a bare host name or address: not blank, and either no colon or
  an IPv6 literal (two or more)."
  [host]
  (and (string? host) (not (str/blank? host))
       ;; a port goes in :port; an IPv6 literal is fine
       (or (not (str/includes? host ":")) (>= (count (re-seq #":" host)) 2))))

(defn- relative-path-ok?
  "True for a non-blank string that isn't an absolute path."
  [path]
  (and (string? path) (not (str/blank? path))
       (not (str/starts-with? path "/"))))

(defn- nrepl-checks
  "[[key value ok? message]] for the keys of a :nrepl map."
  [{:keys [host port port-file]}]
  [[:host host (host-ok? host)
    ":host should be a host name or address without a port, like \"localhost\" (the port goes in :port)"]
   [:port port (and (integer? port) (<= 1 port 65535))
    ":port should be a port number from 1 to 65535"]
   [:port-file port-file (relative-path-ok? port-file)
    ":port-file should be a path relative to the project, like \".nrepl-port\""]])

(defn- nrepl-check-step
  "reduce step over nrepl-checks: keeps a valid value, warns for a bad one,
  skips an absent one."
  [[m ws] [k val ok? msg]]
  (cond (nil? val) [m ws]
        ok? [(assoc m k val) ws]
        :else [m (conj ws (str ".jonquil.edn :nrepl " msg ", so it is ignored"))]))

(defn- nrepl-part
  "[map-or-nil warnings] for .jonquil.edn's :nrepl: {:host :port
  :port-file}, every key optional. A bad value is a warning and only that
  key is dropped; a non-map is a warning and nil."
  [v]
  (cond
    (nil? v) [nil []]
    (not (map? v)) [nil [".jonquil.edn :nrepl should be a map, like {:port 1667} or {:host \"box\" :port 1667}"]]
    :else (reduce nrepl-check-step [{} []] (nrepl-checks v))))

(defn- kw-or-warn
  "[keyword-or-nil warning-or-nil] for key k of the config map m."
  [m k]
  (let [v (get m k)]
    (cond (nil? v) [nil nil]
          (keyword? v) [v nil]
          :else [nil (str ".jonquil.edn " k " should be a keyword, not " (pr-str v))])))

(defn- parinfer-warning [pi]
  (when-not (or (nil? pi) (contains? parinfer-modes pi))
    (str ".jonquil.edn :parinfer should be one of :off :smart :indent :paren, not "
         (pr-str pi) ", so it is ignored")))

(defn- format-style-warning [fs]
  (when-not (or (nil? fs) (contains? format-styles fs))
    (str ".jonquil.edn :format-style should be :paren-stable or :plain, not "
         (pr-str fs) ", so it is ignored")))

(defn- remember-session-warning [rs]
  (when-not (or (nil? rs) (boolean? rs))
    (str ".jonquil.edn :remember-session? should be true or false, not "
         (pr-str rs) ", so it is ignored")))

(defn- cljs-warning [cl]
  (when-not (or (nil? cl) (map? cl))
    ".jonquil.edn :cljs should be a map, like {:shadow-build :app}"))

(defn- accepted-keys
  "The keys config-from-map adds when the value is usable: :auto-require,
  :cljs, :nrepl, :parinfer, :format-style, :remember-session?."
  [{:keys [auto-require cljs nrepl parinfer format-style remember-session?]}]
  (cond-> {}
    auto-require (assoc :auto-require auto-require)
    (map? cljs) (assoc :cljs cljs)
    nrepl (assoc :nrepl nrepl)
    (contains? parinfer-modes parinfer) (assoc :parinfer parinfer)
    (contains? format-styles format-style) (assoc :format-style format-style)
    (boolean? remember-session?) (assoc :remember-session? remember-session?)))

(defn- config-from-map
  "parse-config's result for the config map m."
  [m]
  (let [[d dw] (kw-or-warn m :dialect)
        [r rw] (kw-or-warn m :runtime)
        [ar arw] (auto-require-part (get m :auto-require))
        cl (get m :cljs)
        clw (cljs-warning cl)
        [nr nrw] (nrepl-part (get m :nrepl))
        pi (get m :parinfer)
        piw (parinfer-warning pi)
        fs (get m :format-style)
        fsw (format-style-warning fs)
        rs (get m :remember-session?)
        rsw (remember-session-warning rs)]
    (merge {:dialect d :runtime r :warnings (into (vec (remove nil? [dw rw arw clw piw fsw rsw])) nrw)}
           (accepted-keys {:auto-require ar :cljs cl :nrepl nr :parinfer pi
                           :format-style fs :remember-session? rs}))))

(defn parse-config
  "{:dialect k? :runtime k? :auto-require [sym]? :cljs map? :nrepl map?
  :remember-session? bool? :warnings [str]}
  from .jonquil.edn's text. Never throws: unreadable text, a non-map, a
  non-keyword value, a malformed :auto-require or a non-map :cljs is a
  warning, and the bad part is ignored. :auto-require is present only when
  it names at least one namespace. :cljs is present only when it is a map
  (an empty one counts)."
  [text read-error]
  (let [bad (fn [msg] {:warnings [(str ".jonquil.edn " msg ", so it is ignored")]})]
    (cond
      read-error (bad (str "couldn't be read (" read-error ")"))
      (nil? text) {:warnings []}
      :else
      (let [m (try (edn/read-string text) (catch Exception _ ::unreadable))]
        (cond
          (= ::unreadable m) (bad "isn't valid EDN")
          (nil? m) {:warnings []}
          (not (map? m)) (bad "should hold a map, like {:dialect :jolt}")
          :else (config-from-map m))))))

(defn project-remember-session
  "The :remember-session? that the project holding the folder root asks
  for in its .jonquil.edn, or nil when it names none (or an unusable one),
  so the settings file decides. The project is the nearest one at or above
  root, found as a file inside root would find it."
  [root]
  (when root
    (when-let [p (find-project profiles (str (io/file root "_")))]
      (:remember-session? (parse-config (:config p) (:config-error p))))))

(defn extension
  "The file's extension, lower case, without the dot; nil for none."
  [path]
  (when path
    (let [n (.getName (io/file path))
          i (str/last-index-of n ".")]
      (when (and i (pos? i)) (str/lower-case (subs n (inc i)))))))

(defn runtime-name
  "A runtime keyword as the user reads it."
  [k]
  (get {:in-process "in-process" :nrepl "nREPL"} k (name k)))

(defn- marker-decides?
  "True when exactly one profile (hits) matched a project marker and it
  claims path's extension, or there is no path."
  [hits path]
  (and (= 1 (count hits))
       (or (nil? path) (contains? (:extensions (first hits)) (extension path)))))

(defn- default-hit
  "The profile in pool that the settings default names, or nil. A default
  naming a marker-less profile (:clj is the only one today) is a real hit
  only for a file with no project (in practice an untitled buffer). Inside a
  project, a marker-less default is ignored and selection falls through to
  :fallback, same as if no default were set at all."
  [pool project default]
  (let [d (when default (some (fn [p] (when (= default (:id p)) p)) pool))]
    (when (and d (or (nil? project) (seq (:project-markers d)))) d)))

(defn- choose-profile
  "[profile source] for select: config, then a deciding marker, then the
  extension, the settings default and the fallback."
  [{:keys [profiles project path default by-config markers]}]
  (let [hits (filterv (fn [p] (some markers (:project-markers p))) profiles)
        decides? (marker-decides? hits path)
        ;; A single marker whose profile doesn't claim path's extension isn't
        ;; a real hit after all: the pool widens back to every profile, so
        ;; extension, settings and fallback can look past it.
        pool (if (or (> (count hits) 1) decides?) hits profiles)
        ext (extension path)
        by-ext (first (filter (fn [p] (contains? (:extensions p) ext)) pool))
        by-default (default-hit pool project default)]
    (cond by-config [by-config :config]
          decides? [(first hits) :marker]
          by-ext [by-ext :extension]
          by-default [by-default :settings]
          :else [(first pool) :fallback])))

(defn- default-runtime [p markers]
  (if (and (:project-evaluator p) (some markers (:project-markers p)))
    (:project-evaluator p)
    (:evaluator p)))

(defn- runtime-warnings [runtime rt-ok? p default-rt]
  (if (and runtime (not rt-ok?))
    [(str ".jonquil.edn asks for runtime " runtime ", which " (:name p)
          " doesn't offer here; using " (runtime-name default-rt))]
    []))

(defn- scope-warnings
  "Warnings for config keys that the chosen profile p ignores."
  [p {:keys [auto-require cljs nrepl]}]
  (let [;; JVM Clojure and Jolt read :auto-require (once per connection,
        ;; session or child start); elsewhere it's ignored.
        auto-require? (contains? #{:clj :jolt} (:id p))
        ;; Only ClojureScript reads :cljs (the build choice); elsewhere it's
        ;; ignored.
        cljs? (= :cljs (:id p))
        nrepl? (= :nrepl (:id p))]
    (cond-> []
      (and auto-require (not auto-require?))
      (conj (str ".jonquil.edn :auto-require only applies to JVM Clojure and Jolt, not " (:name p) ", so it is ignored"))
      (and cljs (not cljs?))
      (conj (str ".jonquil.edn :cljs only applies to ClojureScript, not " (:name p) ", so it is ignored"))
      (and nrepl (not nrepl?))
      (conj (str ".jonquil.edn :nrepl only applies to the nREPL dialect, not " (:name p) ", so it is ignored")))))

(defn- launch-target
  "The target ClojureScript that Jonquil starts will use, :node or
  :browser, or nil when the selection is not a cljs launch."
  [cljs? rt target]
  (when (and cljs? (= :launch rt))
    (if (= :browser target) :browser :node)))

(defn- target-warnings
  "Warnings for a :cljs :target that the selection ignores or can't use."
  [cljs? rt target]
  (cond
    (and cljs? (= :launch rt) (some? target) (not (#{:node :browser} target)))
    [(str ".jonquil.edn :cljs :target should be :node or :browser, not " (pr-str target) "; using :node")]
    (and cljs? (= :attach rt) (some? target))
    [".jonquil.edn :cljs :target applies only to ClojureScript that Jonquil starts, not shadow-cljs, so it is ignored"]
    :else []))

(defn- console-warnings
  "Warning for a :cljs :forward-console? that isn't a boolean, browser only."
  [cljs-target fc]
  (if (and (= :browser cljs-target) (some? fc) (not (boolean? fc)))
    [(str ".jonquil.edn :cljs :forward-console? should be true or false, not "
          (pr-str fc) "; forwarding stays on")]
    []))

(defn- cljs-launch
  "{:cljs-target k? :cljs-console? b? :warnings [str]} for a ClojureScript
  selection on runtime rt with the config's :cljs map."
  [p rt cljs]
  (let [cljs? (= :cljs (:id p))
        ;; :cljs :target picks node or the browser for ClojureScript that
        ;; Jonquil starts; shadow-cljs (attach) has its own targets.
        target (when cljs? (get cljs :target))
        cljs-target (launch-target cljs? rt target)
        ;; :cljs :forward-console? turns the browser page's console hook
        ;; off; node has no hook, so the key is browser-only
        fc (get cljs :forward-console?)]
    {:cljs-target cljs-target
     :cljs-console? (when (= :browser cljs-target) (not (false? fc)))
     :warnings (into (target-warnings cljs? rt target) (console-warnings cljs-target fc))}))

(defn- project-config
  "parse-config's result for the project's .jonquil.edn, or no warnings
  when there is no project."
  [project]
  (if project
    (parse-config (:config project) (:config-error project))
    {:warnings []}))

(defn- unknown-dialect-warnings
  "Warning when .jonquil.edn names a dialect that no profile has."
  [dialect by-config]
  (if (and dialect (not by-config))
    [(str ".jonquil.edn names " dialect ", which isn't a known dialect")]
    []))

(defn- dialect-scoped-config
  "The config keys that only some dialects read, kept when the chosen
  profile p is one of them: :auto-require (:clj, :jolt), :cljs, :nrepl."
  [p {:keys [auto-require cljs nrepl]}]
  (cond-> {}
    (and auto-require (contains? #{:clj :jolt} (:id p))) (assoc :auto-require auto-require)
    (and cljs (= :cljs (:id p))) (assoc :cljs cljs)
    (and nrepl (= :nrepl (:id p))) (assoc :nrepl nrepl)))

(defn- launch-config
  "The :cljs-target and :cljs-console? that cljs-launch chose, when set."
  [{:keys [cljs-target cljs-console?]}]
  (cond-> {}
    cljs-target (assoc :cljs-target cljs-target)
    (some? cljs-console?) (assoc :cljs-console? cljs-console?)))

(defn- editor-config
  "The editor's own config keys. Parinfer is the editor's, not a runtime's,
  so no dialect gates it; the format-style pin rides the same way."
  [{:keys [parinfer format-style]}]
  (cond-> {}
    parinfer (assoc :parinfer parinfer)
    format-style (assoc :format-style format-style)))

(defn- selection-config
  "The config-derived keys of select's result for the chosen profile p."
  [p config launch]
  (merge (dialect-scoped-config p config)
         (launch-config launch)
         (editor-config config)))

(defn select
  "Chooses the dialect and runtime for a window.

  project is find-project's map or nil (a loose or unsaved file); path is
  the file's path or nil; default is the settings :dialect or nil.
  Returns {:profile p :runtime k :project-dir dir-or-nil :source k
  :warnings [str]}, plus :auto-require [sym] when the project's config
  names one and the dialect is :clj, plus :cljs {map} when it names one and
  the dialect is :cljs, plus :cljs-target (:node or :browser, only for a
  cljs selection that launches its own process), :source one of :config
  :marker :extension :settings :fallback. A settings default naming a marker-less profile (in practice
  :clj) only wins when there's no project; inside one, it's skipped and
  selection falls through to :fallback."
  [profiles project path default]
  (let [{:keys [dialect runtime cljs warnings] :as config} (project-config project)
        by-config (when dialect (profile profiles dialect))
        markers (:markers project #{})
        [p source] (choose-profile {:profiles profiles :project project :path path
                                    :default default :by-config by-config :markers markers})
        rt-ok? (and runtime (contains? (:runtimes p) runtime))
        default-rt (default-runtime p markers)
        rt (if rt-ok? runtime default-rt)
        launch (cljs-launch p rt cljs)]
    (merge {:profile p :runtime rt :project-dir (:dir project) :source source
            :warnings (-> (vec warnings)
                          (into (unknown-dialect-warnings dialect by-config))
                          (into (runtime-warnings runtime rt-ok? p default-rt))
                          (into (scope-warnings p config))
                          (into (:warnings launch)))}
           (selection-config p config launch))))

(defn selection-key
  "What makes two selections need different evaluators: the dialect, the
  runtime, and for an out-of-process runtime the project it runs in, plus
  :cljs-target for a cljs launch (node and the browser differ), and the
  browser's :cljs-console?, so an edited :forward-console? rebuilds the
  evaluator instead of being silently dropped. Equal keys keep the
  window's evaluator when another file opens. The nREPL dialect's :nrepl
  endpoint is part of it, so a changed host or port gets a new
  connection."
  [{:keys [profile runtime project-dir nrepl cljs-target cljs-console?]}]
  (cond-> [(:id profile) runtime (when (not= :in-process runtime) project-dir)]
    cljs-target (conj cljs-target)
    (some? cljs-console?) (conj cljs-console?)
    (= :nrepl (:id profile)) (conj nrepl)))

(defn reset-toggle
  "How the Reset on Run checkbox looks for selection sel. state is the
  window's own memory of the tick, {} or {:saved active?}, and active? is
  the box's current tick. An attach runtime can't reset (the JVM or app is
  the user's own), so its box is unticked, disabled, and carries the
  profile's :reset-tooltip; the tick it took down goes into :saved, so a
  later switch back to a runtime that can reset puts it up again. A
  switch from one attach runtime to another keeps the first saved tick.
  A runtime named in the profile's :reset-tooltips (ClojureScript on node)
  can reset but starts unticked: the tick it took down is saved the same
  way, the box stays clickable, and a tick the person gives it there
  stands until the window leaves that runtime.
  Returns {:state s :active? b :sensitive? b :tooltip s-or-nil}."
  [state sel active?]
  (let [off-tip (get-in sel [:profile :reset-tooltips (:runtime sel)])]
    (cond
      (= :attach (:runtime sel))
      {:state (if (contains? state :saved) state {:saved active?})
       :active? false :sensitive? false :tooltip (:reset-tooltip (:profile sel))}

      off-tip
      (if (contains? state :saved)
        {:state state :active? active? :sensitive? true :tooltip off-tip}
        {:state {:saved active?} :active? false :sensitive? true :tooltip off-tip})

      :else
      {:state {}
       :active? (if (contains? state :saved) (:saved state) active?)
       :sensitive? true :tooltip nil})))

(defn label
  "The status bar's dialect text: the profile's name, plus the runtime when
  it isn't the profile's default evaluator. An attach runtime always says
  whether it's connected: connection is :attached while the evaluator
  holds a live connection, anything else reads as not running."
  ([selection] (label selection nil))
  ([{:keys [profile runtime cljs-target]} connection]
   (cond
     (and (= :launch runtime) (= :browser cljs-target))
     (str "ClojureScript (browser)" (if (= :attached connection) " (running)" " (not running)"))
     (= :attach runtime) (str (get-in profile [:runtime-names :attach] (:name profile)) (if (= :attached connection) " (attached)" " (not running)"))
     (= :launch runtime) (str (get-in profile [:runtime-names :launch] (:name profile)) (if (= :attached connection) " (running)" " (not running)"))
     (= runtime (:evaluator profile)) (:name profile)
     :else (str (:name profile) " (" (runtime-name runtime) ")"))))
