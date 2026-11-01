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

(defonce RUNTIME-ID
  ;; Jolt's identity hashes come from a fixed-seed sequence, the same in
  ;; every fresh process (measured: the first throwable hashed is 1392991556
  ;; each time), so an error id also names the process it was made in
  (str (random-uuid)))

(defn error-id
  "e's identity for the panel: this process's RUNTIME-ID and e's hash."
  [e]
  (str RUNTIME-ID ":" (hash e)))

(def ^:private OUT-OF-STACK
  ;; measured 2026-10-31 on jolt v0.8.23-230: in debug mode, recursion
  ;; without recur ends as an OutOfMemoryError (GC overhead) with no frames;
  ;; without debug mode the same code's StackOverflowError keeps 398
  (str "No frames: Jolt ran out of stack or memory and kept none. "
       "With debug mode on, deep recursion that doesn't use recur ends this way."))

(defn- out-of-stack? [e]
  (contains? #{"OutOfMemoryError" "StackOverflowError"} (.getSimpleName (class e))))

(defn- frames-data* [e frames-fn resolve-file]
  {:title (title e)
   :frames (mapv (fn [{f :fn :keys [file line locals]}]
                   {:fn (str f)
                    :file (if (and file resolve-file) (resolve-file file) file)
                    :line line
                    :locals (->> locals
                                 (map (fn [[k v]] {:name (str k) :text (value-text v)}))
                                 (sort-by :name)
                                 vec)})
                 (user-frames (frames-fn e)))})

(defn frames-data
  "e's frames as {:title :frames [{:fn :file :line :locals [{:name :text}]}]},
  locals sorted by name, the list stopping where the evaluator's own frames
  start. opts: :frames-fn (jolt.debug/frames, or nil for
  no frames API, which gives a :note instead) and :resolve-file, applied to
  each frame's :file when there is one. Running out of stack or memory with
  no frames kept gets a :note saying so."
  [e {:keys [frames-fn resolve-file]}]
  (assoc (if-not frames-fn
           {:title (title e) :frames [] :note NO-API}
           (let [d (frames-data* e frames-fn resolve-file)]
             (cond-> d
               (and (empty? (:frames d)) (out-of-stack? e)) (assoc :note OUT-OF-STACK))))
         ;; which error this is: a panel showing an older one is refused
         ;; when it asks for a local or evaluates (stale?)
         :error-id (error-id e)))



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

(def STALE
  "What a panel showing an older error gets when it asks for a local or
  evaluates in a frame."
  "The panel shows an older error: show the frames again.")

(defn stale?
  "True when id (a frames-data :error-id) names an error other than e, the
  last one now, in this process or another; nil names none and is never
  stale."
  [e id]
  (boolean (and id (not= id (error-id e)))))

(defn label-ns
  "A frame label's namespace as a symbol: the text before the first /
  (demo.core/inner, demo.shapes/area[:sq], demo.core/inner/fn--3), or user
  when there is none."
  [label]
  (let [i (str/index-of label "/")]
    (symbol (if (and i (pos? i)) (subs label 0 i) "user"))))

(defn- param?
  "True for a local that can be a fn parameter: unqualified, no dot, not a
  special form (if, &, ...)."
  [sym]
  (and (symbol? sym) (nil? (namespace sym))
       (not (str/includes? (name sym) "."))
       (not (special-symbol? sym))))

(def ^:private OUT-MAX
  ;; a choice: what a frame form prints is kept in memory until it returns
  10000)

(defn make-token
  "A fresh interrupt token for eval-in-frame's :token."
  []
  ((resolve 'jolt.host/make-interrupt)))

(defn interrupt-token!
  "Stops the eval-in-frame running under token."
  [token]
  ((resolve 'jolt.host/interrupt!) token))

(defn- interrupted? [t]
  (loop [c t]
    (cond (nil? c) false
          (:jolt/interrupted (ex-data c)) true
          :else (recur (.getCause ^Throwable c)))))

(defn- capped-writer
  "A Writer appending to sb up to OUT-MAX characters, then noting the cut
  once and dropping the rest."
  [^StringBuilder sb]
  (let [cut? (atom false)
        add! (fn [^String s]
               (let [room (- OUT-MAX (.length sb))]
                 (cond
                   (<= (count s) room) (.append sb s)
                   (not @cut?) (do (reset! cut? true)
                                   (.append sb (subs s 0 (max 0 room)))
                                   (.append sb (str "\n; output cut at " OUT-MAX " characters\n"))))))]
    (proxy [java.io.Writer] []
      (close [] nil)
      (flush [] nil)
      (write
        ([x]
         (cond (string? x) (add! x)
               (instance? (Class/forName "[C") x) (add! (String. ^chars x))
               (integer? x) (add! (str (char x)))
               :else (add! (str x))))
        ([x off len]
         (if (instance? (Class/forName "[C") x)
           (add! (String. ^chars x (int off) (int len)))
           (add! (subs (str x) (int off) (+ (int off) (int len))))))))))

(defn- read-all
  "Every form in code, in order; a read error throws."
  [code]
  (let [r (java.io.PushbackReader. (java.io.StringReader. code))]
    (loop [acc []]
      (let [f (read {:eof ::eof} r)]
        (if (= ::eof f) acc (recur (conj acc f)))))))

(defn eval-in-frame
  "Evaluates the forms in code, in order, with frame i's locals bound,
  compiled in the frame's namespace (user when that isn't loaded): {:out
  printed :text last-value}, {:error \"Class: message\"} for a throw or
  code that doesn't read, {:error \"Stopped.\"} when :token was
  interrupted (interrupt-token!), or {:error \"That frame is gone.\"}.
  Locals that can't be parameters are left out. What the forms print,
  realizing the value included, is captured up to OUT-MAX characters.
  Nothing unwinds or resumes; the forms' own side effects happen."
  [e i code {:keys [frames-fn token]}]
  (if-let [fr (when frames-fn (get (vec (frames-fn e)) i))]
    (let [syms (->> (keys (:locals fr)) (filter param?) sort vec)
          target (or (find-ns (label-ns (str (:fn fr)))) (the-ns 'user))
          sb (StringBuilder.)
          run (if token
                (let [ri (resolve 'jolt.host/run-interruptible)] (fn [thunk] (ri token thunk)))
                (fn [thunk] (thunk)))]
      (try
        (let [f (binding [*ns* target]
                  ;; clojure.core/fn, not fn: the frame's namespace may exclude it
                  (eval (list* 'clojure.core/fn syms (read-all code))))
              text (binding [*out* (capped-writer sb)]
                     (run (fn []
                            (let [v (apply f (map (:locals fr) syms))]
                              ;; inside the capture: printing a lazy value runs it
                              (binding [*print-length* 100 *print-level* 10] (pr-str v))))))]
          {:out (str sb) :text text})
        (catch Throwable t (if (interrupted? t) {:error "Stopped."} {:error (title t)}))))
    {:error "That frame is gone."}))

(defn local-value
  "The live value of the local named local-name in e's frame i, or
  ::missing."
  [e i local-name {:keys [frames-fn]}]
  (if-let [fr (when frames-fn (get (vec (frames-fn e)) i))]
    (if-let [[_ v] (find (:locals fr) (symbol local-name))] v ::missing)
    ::missing))
