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
   ;; now reads them from here.
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
   ;; The evaluator a project gets when .jonquil.edn names no :runtime, and
   ;; the runtimes it may name.
   :evaluator :in-process
   :runtimes #{:in-process :nrepl}})

(def ^:private cljd-special-forms
  ;; ClojureDart's own forms, added to Jolt's set for the :cljd profile.
  ;; Read from the nrepl-poc fork (a private ClojureDart fork at
  ;; 573781a2), 2026-09-28:
  ;; - clj/src/cljd/compiler.cljc, `emit`'s special-form `case`: every
  ;;   user-facing special Jolt's set lacks (the starred internals like
  ;;   let* and case* are left out).
  ;; - clj/src/cljd/core.cljd: the public macros clojure.core doesn't have
  ;;   (the -map-lit/-set-lit/-vec-lit reader targets are left out).
  ;; - clj/src/cljd/flutter.cljd: its public macros, bare and under the
  ;;   `f` alias, because every one of a-sibling-cljd-app's 10 files that
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
   :reset-tooltip "Hot reload can't unload a namespace, so every Run adds to the running app."})

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

(def profiles
  "Every known profile. Order matters only as the last resort in select:
  Jolt first, so a file neither profile's extension claims (bb.edn in a
  ClojureDart app) stays Jolt's.

  clj joined this list in P2.T4, once the window required jonquil.nrepl.jvm
  and could reach make-attached :clj (ruling 3)."
  [jolt cljd clj])

(defn profile
  "The profile with id k in profiles (default: every known one), or nil."
  ([k] (profile profiles k))
  ([profiles k] (some (fn [p] (when (= k (:id p)) p)) profiles)))

(def config-name ".jonquil.edn")

(defn- marker-names [profiles]
  (into #{config-name} (mapcat :project-markers) profiles))

(defn find-project
  "The nearest directory at or above path's own directory holding
  .jonquil.edn or any profile's marker, as {:dir abs-path :markers #{name}
  :config text-or-nil :config-error msg-or-nil}. nil for a nil path or when
  no directory up to the root has one. :dir is canonical and absolute
  (a relative spawn :dir fails silently, measured in the dialects spike)."
  ([path] (find-project profiles path))
  ([profiles path]
   (when path
     (let [names (marker-names profiles)]
       (loop [dir (.getParentFile (io/file (.getCanonicalPath (io/file path))))]
         (when dir
           (let [found (into #{} (filter (fn [n] (.exists (io/file dir n)))) names)]
             (if (seq found)
               (let [cfg (io/file dir config-name)
                     [text err] (if (.exists cfg)
                                  (try [(slurp cfg) nil]
                                       (catch Exception e [nil (ex-message e)]))
                                  [nil nil])]
                 {:dir (.getPath dir)
                  :markers (disj found config-name)
                  :config text
                  :config-error err})
               (recur (.getParentFile dir))))))))))

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

(defn parse-config
  "{:dialect k? :runtime k? :auto-require [sym]? :warnings [str]} from
  .jonquil.edn's text. Never throws: unreadable text, a non-map, a
  non-keyword value or a malformed :auto-require is a warning, and the bad
  part is ignored. :auto-require is present only when it names at least
  one namespace."
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
          :else
          (let [kw-or-warn (fn [k]
                             (let [v (get m k)]
                               (cond (nil? v) [nil nil]
                                     (keyword? v) [v nil]
                                     :else [nil (str ".jonquil.edn " k " should be a keyword, not " (pr-str v))])))
                [d dw] (kw-or-warn :dialect)
                [r rw] (kw-or-warn :runtime)
                [ar arw] (auto-require-part (get m :auto-require))]
            (cond-> {:dialect d :runtime r :warnings (vec (remove nil? [dw rw arw]))}
              ar (assoc :auto-require ar))))))))

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

(defn select
  "Chooses the dialect and runtime for a window.

  project is find-project's map or nil (a loose or unsaved file); path is
  the file's path or nil; default is the settings :dialect or nil.
  Returns {:profile p :runtime k :project-dir dir-or-nil :source k
  :warnings [str]}, plus :auto-require [sym] when the project's config
  names one and the dialect is :clj, :source one of :config :marker :extension :settings
  :fallback. A settings default naming a marker-less profile (in practice
  :clj) only wins when there's no project; inside one, it's skipped and
  selection falls through to :fallback (I2)."
  [profiles project path default]
  (let [{:keys [dialect runtime auto-require warnings]} (if project
                                             (parse-config (:config project) (:config-error project))
                                             {:warnings []})
        by-config (when dialect (profile profiles dialect))
        w1 (if (and dialect (not by-config))
             [(str ".jonquil.edn names " dialect ", which isn't a known dialect")]
             [])
        markers (:markers project #{})
        hits (filterv (fn [p] (some markers (:project-markers p))) profiles)
        ext (extension path)
        marker-decides? (and (= 1 (count hits))
                             (let [only (first hits)]
                               (or (nil? path) (contains? (:extensions only) ext))))
        ;; A single marker whose profile doesn't claim path's extension isn't
        ;; a real hit after all: the pool widens back to every profile, so
        ;; extension, settings and fallback can look past it.
        pool (cond (> (count hits) 1) hits
                   marker-decides? hits
                   :else profiles)
        by-ext (first (filter (fn [p] (contains? (:extensions p) ext)) pool))
        ;; I2: a settings default naming a marker-less profile (:clj is the
        ;; only one today) is a real hit only for a file with no project (in
        ;; practice an untitled buffer, spec section 1). Inside a project, a
        ;; marker-less default is ignored and selection falls through to
        ;; :fallback, same as if no default were set at all.
        by-default (when-let [d (when default (some (fn [p] (when (= default (:id p)) p)) pool))]
                     (when (or (nil? project) (seq (:project-markers d))) d))
        [p source] (cond by-config [by-config :config]
                         marker-decides? [(first hits) :marker]
                         by-ext [by-ext :extension]
                         by-default [by-default :settings]
                         :else [(first pool) :fallback])
        rt-ok? (and runtime (contains? (:runtimes p) runtime))
        w2 (if (and runtime (not rt-ok?))
             [(str ".jonquil.edn asks for runtime " runtime ", which " (:name p)
                   " doesn't offer here; using " (runtime-name (:evaluator p)))]
             [])
        ;; Only JVM Clojure sends :auto-require (the attach evaluator
        ;; requires the list once per connection); elsewhere it's ignored.
        clj? (= :clj (:id p))
        w3 (if (and auto-require (not clj?))
             [(str ".jonquil.edn :auto-require only applies to JVM Clojure, not " (:name p) ", so it is ignored")]
             [])]
    (cond-> {:profile p
             :runtime (if rt-ok? runtime (:evaluator p))
             :project-dir (:dir project)
             :source source
             :warnings (-> (vec warnings) (into w1) (into w2) (into w3))}
      (and auto-require clj?) (assoc :auto-require auto-require))))

(defn selection-key
  "What makes two selections need different evaluators: the dialect, the
  runtime, and for an out-of-process runtime the project it runs in. Equal
  keys keep the window's evaluator when another file opens."
  [{:keys [profile runtime project-dir]}]
  [(:id profile) runtime (when (not= :in-process runtime) project-dir)])

(defn reset-toggle
  "How the Reset on Run checkbox looks for selection sel. state is the
  window's own memory of the tick, {} or {:saved active?}, and active? is
  the box's current tick. An attach runtime can't reset (the JVM or app is
  the user's own), so its box is unticked, disabled, and carries the
  profile's :reset-tooltip; the tick it took down goes into :saved, so a
  later switch back to a runtime that can reset puts it up again. A
  switch from one attach runtime to another keeps the first saved tick.
  Returns {:state s :active? b :sensitive? b :tooltip s-or-nil}."
  [state sel active?]
  (if (= :attach (:runtime sel))
    {:state (if (contains? state :saved) state {:saved active?})
     :active? false :sensitive? false :tooltip (:reset-tooltip (:profile sel))}
    {:state {}
     :active? (if (contains? state :saved) (:saved state) active?)
     :sensitive? true :tooltip nil}))

(defn label
  "The status bar's dialect text: the profile's name, plus the runtime when
  it isn't the profile's default evaluator. An attach runtime always says
  whether it's connected: connection is :attached while the evaluator
  holds a live connection, anything else reads as not running."
  ([selection] (label selection nil))
  ([{:keys [profile runtime]} connection]
   (cond
     (= :attach runtime) (str (:name profile) (if (= :attached connection) " (attached)" " (not running)"))
     (= runtime (:evaluator profile)) (:name profile)
     :else (str (:name profile) " (" (runtime-name runtime) ")"))))
