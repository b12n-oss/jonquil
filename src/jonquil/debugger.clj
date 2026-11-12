(ns jonquil.debugger
  "The last error's frames and locals, as data for the Frames panel. Jolt's
  jolt.debug/frames reads them from a throwable even after it was caught,
  innermost first, as {:fn :file :line :locals}. A Jolt without jolt.debug
  has no frames, so every use goes through frames-fn, looked up when
  called. The out-of-process Jolt child loads this namespace too, from
  Jonquil's embedded middleware files, so it requires nothing but
  clojure.string and jonquil.instrument-lock, which is embedded too. No GTK
  here."
  (:require [clojure.string :as str]
            [jonquil.instrument-lock :as instrument-lock])
  (:import (java.util.concurrent LinkedBlockingQueue)))

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
  "frames up to the first of the evaluator's own: none when the first
  already is one, which is what a top-level expression gives (it keeps no
  frame of its own), and the panel says so (NO-USER-FRAMES) rather than
  list the evaluator's internals."
  [frames]
  (take-while (complement harness?) frames))

(def ^:private NO-USER-FRAMES
  (str "No frames from your code: a top-level expression keeps no frame of its own, "
       "so call a function to see its locals."))

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

(defn- frames-data** [e all resolve-file]
  {:title (title e)
   :frames (mapv (fn [{f :fn :keys [file line locals]}]
                   {:fn (str f)
                    :file (if (and file resolve-file) (resolve-file file) file)
                    :line line
                    :locals (->> locals
                                 (map (fn [[k v]] {:name (str k) :text (value-text v)}))
                                 (sort-by :name)
                                 vec)})
                 (user-frames all))})

(defn- frames-data* [e frames-fn resolve-file]
  (let [all (frames-fn e)
        d (frames-data** e all resolve-file)]
    (cond-> d
      (and (empty? (:frames d)) (seq all)) (assoc :note NO-USER-FRAMES))))

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
    (try (locking instrument-lock/lock
           ((requiring-resolve 'jolt.profile/instrument!) (if on? :debug false)))
         {:on? (boolean on?)}
         (catch Throwable e (jolt-error e)))
    {:error NO-API}))

(defn toggle-debug-mode!
  "Flips the runtime's own debug mode, read from jolt.profile/instrument-mode
  (on any Jolt with jolt.debug), so a restarted runtime or another window
  that changed it is never contradicted: on unless it is :debug now, off
  when it is. {:on? b} or {:error s}, as set-debug-mode!. The read and the
  set hold jonquil.instrument-lock's lock, so two toggles at once flip it
  twice."
  []
  (if (available?)
    (try (locking instrument-lock/lock
           (set-debug-mode! (not= :debug ((requiring-resolve 'jolt.profile/instrument-mode)))))
         (catch Throwable e (jolt-error e)))
    {:error NO-API}))

(defn- file-name [f] (last (str/split f #"/")))

(defn- frame-rows
  "Frame i's :frame row (its fn and file:line, or its line and \"(no
  file)\") and a :local row per local."
  [i {f :fn :keys [file line locals]}]
  (cons {:kind :frame :frame i
         :text (str f "  " (if file
                             (str (file-name file) (when line (str ":" line)))
                             (str (when line (str "line " line " ")) "(no file)")))}
        (for [{:keys [name text]} locals]
          {:kind :local :frame i :name name :text (str "    " name " = " text)})))

(defn rows
  "The panel's rows for data: a :frame row per frame (its fn and file:line;
  code typed at the prompt or run from an unsaved buffer has no file, so
  its line, counted in that text), then a :local row per local; a :note row when
  there is a note or no frames."
  [{:keys [frames note]}]
  (cond
    note [{:kind :note :text note}]
    (empty? frames) [{:kind :note :text "No frames: the error came from code compiled without them."}]
    :else
    (vec (mapcat frame-rows (range) frames))))

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

(defn- interrupted?
  "True when t, or a cause of it, is a Stop: the token's interrupt, or the
  thread's (an InterruptedException from a blocking call)."
  [t]
  (loop [c t]
    (cond (nil? c) false
          (:jolt/interrupted (ex-data c)) true
          (instance? InterruptedException c) true
          :else (recur (.getCause ^Throwable c)))))

(defn- capped-append!
  "Appends s to sb while it fits within OUT-MAX characters. The first s that
  doesn't fit gets its fitting head and a note that the output was cut (cut?
  is the atom remembering that); later ones are dropped."
  [^StringBuilder sb cut? ^String s]
  (let [room (- OUT-MAX (.length sb))]
    (cond
      (<= (count s) room) (.append sb s)
      (not @cut?) (do (reset! cut? true)
                      (.append sb (subs s 0 (max 0 room)))
                      (.append sb (str "\n; output cut at " OUT-MAX " characters\n"))))))

(defn- capped-writer
  "A Writer appending to sb up to OUT-MAX characters, then noting the cut
  once and dropping the rest."
  [^StringBuilder sb]
  (let [cut? (atom false)
        add! (fn [s] (capped-append! sb cut? s))]
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

;; --- the break: an error paused before the stack unwinds ------------------

(def ^:dynamic *break-fn*
  "When bound, what break-wrapper calls in place of
  jolt.debug/call-with-break-handler: the tests' fake break."
  nil)

(defn- break-fn []
  (or *break-fn*
      (try (requiring-resolve 'jolt.debug/call-with-break-handler) (catch Throwable _ nil))))

(defn break-available?
  "True when this Jolt can break on error (jolt.debug), or a test faked it."
  []
  (some? (break-fn)))

(def ^:private NOT-PAUSED "Not paused any more.")

(defn make-break
  "A break's state: the queue the paused thread serves and the pause, nil
  when nothing is paused."
  []
  {:queue (LinkedBlockingQueue.) :paused (atom nil)})

(defn- reject-eval!
  "Answers m, if it is an {:eval [i code reply]} request, with NOT-PAUSED.
  A reply that throws is ignored."
  [m]
  (when-let [[_ _ reply] (:eval m)]
    (try (reply {:error NOT-PAUSED}) (catch Throwable _ nil))))

(defn- drain!
  "Answers every request still queued with NOT-PAUSED: one that arrived as
  a pause ended, or before the next began."
  [^LinkedBlockingQueue q]
  (loop []
    (when-let [m (.poll q)]
      (reject-eval! m)
      (recur))))

(defn- serve-eval!
  "Answers one {:eval [i code reply]} request, evaluated on this thread with
  frame i's locals bound; other requests are ignored."
  [m t frames]
  (when-let [[i code reply] (:eval m)]
    (let [r (try (eval-in-frame t i code {:frames-fn (fn [_] frames)})
                 (catch Throwable e {:error (title e)}))]
      (try (reply r) (catch Throwable _ nil)))))

(defn- serve!
  "The paused thread's loop: each {:eval [i code reply]} is evaluated here,
  on the throwing thread, with frame i's locals bound, until :continue.
  Stop interrupts this thread, and .take then throws; it also queues ::stop
  (break-stop!), since a form evaluated here can use up the interrupt."
  [^LinkedBlockingQueue q t frames]
  (loop []
    (let [m (.take q)]
      (when (= ::stop m) (throw (InterruptedException. "Stopped while paused.")))
      (when-not (= :continue m)
        (serve-eval! m t frames)
        (recur)))))

(defn- serve-pause
  "Runs a published pause: (on-pause d), then serves the paused thread until
  continue!, and whatever way that ends clears the pause, drains the queue
  and calls (on-resume {:continued? b}). The callbacks' own throws are dropped."
  [{:keys [brk callbacks t frames d]}]
  (let [{:keys [queue paused]} brk
        {:keys [on-pause on-resume]} callbacks
        continued? (volatile! false)]
    (try
      (try (when on-pause (on-pause d)) (catch Throwable _ nil))
      (serve! queue t frames)
      (vreset! continued? true)
      (finally
        (reset! paused nil)
        (drain! queue)
        (try (when on-resume (on-resume {:continued? @continued?})) (catch Throwable _ nil))))))

(defn- claim-pause!
  "Takes brk's pause: true when nothing was paused. One pause at a time, an
  error's or a breakpoint's (LM5). The claim's :continued is already true,
  so a Continue before the pause is published finds nothing to continue."
  [{:keys [paused]}]
  (compare-and-set! paused nil {:claim true :continued (atom true)}))

(defn- publish-pause!
  "Publishes the claimed pause of thread on t with frames and data d."
  [{:keys [queue paused]} t frames d]
  (drain! queue)
  (reset! paused {:error t :frames frames :data d :continued (atom false)
                  :thread (Thread/currentThread)}))

(defn- pause-on-error
  "The break handler's body, on the throwing thread: publishes the pause for
  error t and serves it. A Stop's InterruptedException is never paused on,
  and while another pause holds (a breakpoint on another thread) the error
  goes on unpaused."
  [{:keys [brk callbacks t frames]}]
  ;; a Stop is never an error to pause on: a body blocked in a
  ;; sleep, a deref or a .take throws InterruptedException
  (when (and (not (interrupted? t)) (claim-pause! brk))
    (let [d (try (frames-data t {:frames-fn (fn [_] frames) :resolve-file (:resolve-file callbacks)})
                 (catch Throwable e (reset! (:paused brk) nil) (throw e)))]
      (publish-pause! brk t frames d)
      (serve-pause {:brk brk :callbacks callbacks :t t :frames frames :d d}))))

(defn break-wrapper
  "A fn of a thunk that runs it under jolt.debug's break handler. A throw
  escaping the thunk pauses on the throwing thread before anything unwinds:
  the pause is published, (on-pause data) gets its frames-data, and the
  thread serves break-eval! until continue! (or a Stop's interrupt), then
  (on-resume {:continued? b}) and the throw goes on (b false: the Stop's
  InterruptedException goes on in its place). On a Jolt without jolt.debug the thunk
  just runs. The callbacks' own throws are dropped: they must not replace
  the error."
  {:arglists '([{:keys [queue paused]} {:keys [on-pause on-resume resolve-file]}])}
  [brk callbacks]
  (fn [thunk]
    (if-let [call (break-fn)]
      (call (fn [t frames] (pause-on-error {:brk brk :callbacks callbacks :t t :frames frames}))
            thunk)
      (thunk))))

(defn break-eval!
  "Asks the paused thread to evaluate code in frame i; (reply r) gets
  eval-in-frame's map, or {:error \"Not paused any more.\"}."
  [{:keys [^LinkedBlockingQueue queue paused]} i code reply]
  (if @paused
    (.put queue {:eval [i code reply]})
    (reply {:error NOT-PAUSED})))

(defn continue!
  "Lets the paused error unwind: true when a pause was waiting, false when
  there was none or it was already continued."
  [{:keys [^LinkedBlockingQueue queue paused]}]
  (let [p @paused]
    (boolean (when (and p (compare-and-set! (:continued p) false true))
               (.put queue :continue)
               true))))

(defn break-stop!
  "Stop's half for a pause: the paused thread takes ::stop and throws, ending
  the evaluation even when a form it ran used up the thread's interrupt."
  [{:keys [^LinkedBlockingQueue queue paused]}]
  (when @paused (.put queue ::stop))
  nil)

(defn- own-pause?
  "True when pause p was published by this thread, or by none that is
  still alive (an older pause, or a thread gone)."
  [p]
  (let [^Thread t (:thread p)]
    (or (nil? t) (identical? t (Thread/currentThread)) (not (.isAlive t)))))

(defn end-stale-pause!
  "The evaluation has ended, on its own thread: a pause it published that is
  still there lost its own cleanup (a Stop's interrupt landing inside the
  handler's finally), so this ends it as a Stop would: cleared, requests
  answered, (on-resume {:continued? false}). A pause another live thread
  holds (a breakpoint in a future) is left alone, or with opts
  {:other-thread :wake} continued, for a client that can't reach it once
  the evaluation is over. A claim not yet published is left alone."
  ([brk callbacks] (end-stale-pause! brk callbacks {:other-thread :leave}))
  ([{:keys [queue paused] :as brk} {:keys [on-resume]} {:keys [other-thread]}]
   (when-let [p @paused]
     (cond
       (:claim p) nil
       (own-pause? p)
       (do (reset! paused nil)
           (drain! queue)
           (try (when on-resume (on-resume {:continued? false})) (catch Throwable _ nil)))
       (= :wake other-thread) (continue! brk)))
   nil))

(defn paused-local
  "The value of local-name in the paused error's frame i: ::missing when
  that frame has no such local, ::not-paused when nothing is paused or
  error-id names another error (the caller then asks the last error)."
  [{:keys [paused]} error-id i local-name]
  (let [p @paused]
    (if (and p (= error-id (:error-id (:data p))))
      (local-value (:error p) i local-name {:frames-fn (fn [_] (:frames p))})
      ::not-paused)))

;; --- breakpoints (LM5): a pause where the person chose, nothing wrong -------

(defonce breakpoint-hook
  ;; What jonquil.breakpoints/breakpoint-form calls with the throwable and
  ;; frames when its throw reaches its own break handler: nil (the form
  ;; does nothing) or a fn of [t frames]. An atom, not a dynamic var, so a
  ;; call on any thread (a future, a test runner) reaches it.
  (atom nil))

(defn breakpoint-line
  "The line a breakpoint throw names (its ex-data's :jonquil/breakpoint),
  or nil for any other throwable."
  [t]
  (let [d (ex-data t)] (when (map? d) (:jonquil/breakpoint d))))

(defn breakpoint-data
  "frames-data for breakpoint throw t: titled \"Breakpoint at line N\",
  with :breakpoint N."
  [t frames resolve-file]
  (let [n (breakpoint-line t)]
    (assoc (frames-data t {:frames-fn (fn [_] frames) :resolve-file resolve-file})
           :title (str "Breakpoint at line " n) :breakpoint n)))

(defn pause-at-breakpoint
  "The breakpoint hook's body, on the thread that reached the breakpoint:
  pauses there through brk as Break on error does, unless something is
  paused already (that thread then runs on). frames' first entry is
  jolt.debug/with-break-handler's own thunk, which has no locals, so it
  is dropped. (can-pause?), when given, is asked again once the pause is
  published: false means the evaluation that allowed it ended while it was
  being claimed, and its end may have missed the claim, so the pause is
  let go at once rather than wait for a Continue nobody can send. Answers
  true when it paused."
  [{:keys [brk callbacks t frames can-pause?]}]
  (let [{:keys [paused]} brk
        frames (vec (rest frames))]
    (when (claim-pause! brk)
      (let [d (try (breakpoint-data t frames (:resolve-file callbacks))
                   (catch Throwable e (reset! paused nil) (throw e)))]
        (publish-pause! brk t frames d)
        (when (and can-pause? (not (can-pause?))) (continue! brk))
        (serve-pause {:brk brk :callbacks callbacks :t t :frames frames :d d})
        true))))

(defn breakpoint-handler
  "A breakpoint hook pausing through brk with the callbacks (callbacks-fn)
  answers at pause time. (can-pause?) false lets a breakpoint run on, as
  does a throwable that isn't a breakpoint's."
  [brk callbacks-fn can-pause?]
  (fn [t frames]
    (when (and (breakpoint-line t) (can-pause?))
      (pause-at-breakpoint {:brk brk :callbacks (callbacks-fn) :t t :frames frames
                            :can-pause? can-pause?}))))

(defn arm-breakpoints!
  "Installs hook as breakpoint-hook, after turning debug mode on when it's
  off (a breakpoint's frame needs its locals). {:on? true :debug-turned-on?
  b}, or {:error s} on a Jolt without jolt.debug, where nothing is
  installed. Loads jolt.debug, which the inserted form names."
  [hook]
  (if-not (and (available?) (break-available?))
    {:error NO-API}
    (try
      (let [was (locking instrument-lock/lock ((requiring-resolve 'jolt.profile/instrument-mode)))
            r (when (not= :debug was) (set-debug-mode! true))]
        (or (when (:error r) r)
            (do (reset! breakpoint-hook hook)
                {:on? true :debug-turned-on? (not= :debug was)})))
      (catch Throwable e (jolt-error e)))))

(defn disarm-breakpoints!
  "Empties breakpoint-hook when it still holds hook. {:on? false}."
  [hook]
  (compare-and-set! breakpoint-hook hook nil)
  {:on? false})

(defn macro-heads
  "What each symbol text in syms names in namespace ns-name, for
  jonquil.breakpoints' opts: {:heads {sym info} :ns-found? b}. info is
  {:var \"ns/name\" :macro true} for a macro, {:var \"ns/name\"} for any
  other var, nil for nothing (a local, a special form, interop, a class,
  or a symbol that throws when resolved). A namespace not loaded yet (the
  buffer before its first Run) is resolved in user, with :ns-found? false:
  what its :refer brings in can't be known then."
  [ns-name syms]
  (let [found (some-> ns-name str symbol find-ns)
        n (or found (the-ns 'user))]
    {:heads (into {} (map (fn [s]
                            [s (try (let [v (ns-resolve n (symbol s))]
                                      (when (var? v)
                                        (cond-> {:var (str (symbol v))} (:macro (meta v)) (assoc :macro true))))
                                    (catch Throwable _ nil))]))
                  syms)
     :ns-found? (some? found)}))
