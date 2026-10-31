(ns jonquil.debugger
  "The last error's frames and locals, as data for the Frames panel. Jolt's
  jolt.debug/frames reads them from a throwable even after it was caught,
  innermost first, as {:fn :file :line :locals}. A Jolt without jolt.debug
  has no frames, so every use goes through frames-fn, looked up when
  called. The out-of-process Jolt child loads this namespace too, from
  Jonquil's embedded middleware files, so it requires nothing but
  clojure.string. No GTK here."
  (:require [clojure.string :as str]))

(def ^:private TEXT-MAX
  ;; a choice: one readable panel row
  100)

(def ^:dynamic *frames-fn*
  "When bound, what frames-fn answers in place of jolt.debug/frames: the
  tests' fake frames. It never makes available? true."
  nil)

(defn- real-frames-fn []
  (try (requiring-resolve 'jolt.debug/frames) (catch Throwable _ nil)))

(defn frames-fn
  "*frames-fn* when bound, else jolt.debug/frames, or nil on a Jolt
  without it."
  []
  (or *frames-fn* (real-frames-fn)))

(defn available?
  "True when this Jolt has jolt.debug."
  []
  (some? (real-frames-fn)))

(def ^:private UNREALIZED "<lazy seq, not realized>")

(defn- unrealized?
  "True for a lazy seq nothing has realized yet: printing it would run the
  program's own code (a (map save! rows) saving rows again), off the eval
  thread, as slowly as that code is."
  [x]
  (and (instance? clojure.lang.LazySeq x) (not (realized? x))))

(defn- guard-lazy
  "v with an unrealized lazy seq inside it, one level down in a vector, map
  or set, shown as UNREALIZED (a symbol prints as its name). Only the first
  9 entries are kept, which is all *print-length* 8 shows; a record is left
  as it is."
  [v]
  (let [mark (fn [x] (if (unrealized? x) (symbol UNREALIZED) x))]
    (cond
      (record? v) v
      (map? v) (into (empty v) (map (fn [[k x]] [k (mark x)])) (take 9 v))
      (vector? v) (mapv mark (take 9 v))
      (set? v) (into (empty v) (map mark) (take 9 v))
      :else v)))

(defn value-text
  "v as one short line: pr-str with *print-length* 8 and *print-level* 3,
  cut to TEXT-MAX characters, or <unprintable: Class> when printing it
  throws. An unrealized lazy seq is never realized here (UNREALIZED): the
  inspector realizes it when asked."
  [v]
  (let [s (try (if (unrealized? v)
                 UNREALIZED
                 (binding [*print-length* 8 *print-level* 3] (pr-str (guard-lazy v))))
               (catch Throwable _ (str "<unprintable: " (.getSimpleName (class v)) ">")))]
    (if (> (count s) TEXT-MAX) (str (subs s 0 TEXT-MAX) "…") s)))

(defn- title [e]
  (str (.getSimpleName (class e)) ": " (ex-message e)
       (when-let [d (ex-data e)] (str " " (value-text d)))))

(def ^:private HARNESS
  ;; where the evaluator's own frames start, under the user's code: the
  ;; in-process engine, and the Jolt child's nREPL server
  ["jonquil.eval/" "jonquil.session/" "jonquil.nrepl." "nrepl." "cider." "jolt.nrepl" "jolt.host/"])

(defn- harness? [frame]
  (let [f (str (:fn frame))] (some #(str/starts-with? f %) HARNESS)))

(defn- user-frames
  "frames up to the first of the evaluator's own; all of them when the
  first frame already is one (a fault inside the harness)."
  [frames]
  (let [kept (take-while (complement harness?) frames)]
    (if (seq kept) kept frames)))

(def ^:private NO-API "Frames need a Jolt with jolt.debug (newer than this one).")

(defn frames-data
  "e's frames as {:title :frames [{:fn :file :line :locals [{:name :text}]}]},
  locals sorted by name, the list stopping where the evaluator's own frames
  start. opts: :frames-fn (jolt.debug/frames, or nil for
  no frames API, which gives a :note instead) and :resolve-file, applied to
  each frame's :file when there is one."
  [e {:keys [frames-fn resolve-file]}]
  (if-not frames-fn
    {:title (title e) :frames [] :note NO-API}
    {:title (title e)
     :frames (mapv (fn [{f :fn :keys [file line locals]}]
                     {:fn (str f)
                      :file (if (and file resolve-file) (resolve-file file) file)
                      :line line
                      :locals (->> locals
                                   (map (fn [[k v]] {:name (str k) :text (value-text v)}))
                                   (sort-by :name)
                                   vec)})
                   (user-frames (frames-fn e)))}))

(defn- jolt-error [e] {:error (str "Debug mode: " (or (ex-message e) (str (class e))))})

(defn set-debug-mode!
  "Turns Jolt's debug mode on or off: code compiled from then on keeps
  every local. {:on? b}, or {:error s} on a Jolt without jolt.debug, where
  jolt.profile/instrument! is never called (it would read :debug as true
  and turn line counting on), or when it throws."
  [on?]
  (if (available?)
    (try ((requiring-resolve 'jolt.profile/instrument!) (if on? :debug false))
         {:on? (boolean on?)}
         (catch Throwable e (jolt-error e)))
    {:error NO-API}))

(defn toggle-debug-mode!
  "Flips the runtime's own debug mode, read from jolt.profile/instrument-mode
  (on any Jolt with jolt.debug), so a restarted runtime or another window
  that changed it is never contradicted: on unless it is :debug now, off
  when it is. {:on? b} or {:error s}, as set-debug-mode!."
  []
  (if (available?)
    (try (set-debug-mode! (not= :debug ((requiring-resolve 'jolt.profile/instrument-mode))))
         (catch Throwable e (jolt-error e)))
    {:error NO-API}))

(defn- file-name [f] (last (str/split f #"/")))

(defn rows
  "The panel's rows for data: a :frame row per frame (its fn and file:line;
  code run from the editor or typed at the prompt has no file, so its line,
  counted in that text), then a :local row per local; a :note row when
  there is a note or no frames."
  [{:keys [frames note]}]
  (cond
    note [{:kind :note :text note}]
    (empty? frames) [{:kind :note :text "No frames: the error came from code compiled without them."}]
    :else
    (vec (mapcat (fn [i {f :fn :keys [file line locals]}]
                   (cons {:kind :frame :frame i
                          :text (str f "  " (if file
                                              (str (file-name file) (when line (str ":" line)))
                                              (str (when line (str "line " line " ")) "(no file)")))}
                         (for [{:keys [name text]} locals]
                           {:kind :local :frame i :name name :text (str "    " name " = " text)})))
                 (range) frames))))

(defn local-value
  "The live value of the local named local-name in e's frame i, or
  ::missing."
  [e i local-name {:keys [frames-fn]}]
  (if-let [fr (when frames-fn (get (vec (frames-fn e)) i))]
    (if-let [[_ v] (find (:locals fr) (symbol local-name))] v ::missing)
    ::missing))
