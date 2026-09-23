(ns jonquil.eval
  "Run, stdin, output and Stop for Jonquil. Reworks DrClojure.eval (41c56b8).
   Output writers bound directly, load-string under run-interruptible, *ns*
   always bound, and Stop that lets the worker's own exit path go idle."
  (:require [clojure.string :as str])
  (:import (java.io Reader StringReader Writer)
           (java.util.concurrent LinkedBlockingQueue)
           (clojure.lang LineNumberingPushbackReader)))

(defn create-writer
  "Creates a java.io.Writer that forwards character chunks to (on-chunk str)."
  [on-chunk]
  (proxy [Writer] []
    (close [] nil)
    (flush [] nil)
    (write
      ([x]
       (cond
         (string? x) (on-chunk x)
         (integer? x) (on-chunk (str (char x)))
         :else (on-chunk (str x))))
      ([x off len]
       (on-chunk (subs (str x) (int off) (+ (int off) (int len))))))))

(defn create-stdin-reader
  "Upstream's queue-fed Reader, unchanged."
  [queue current-rdr-atom closed-atom on-wait-input]
  (proxy [Reader] []
    (close []
      (reset! closed-atom true)
      (.offer queue ::eof))
    (read
      ([]
       (let [buf (make-array Character/TYPE 1)
             n (.read ^Reader this buf 0 1)]
         (if (neg? n) -1 (int (aget ^chars buf 0)))))
      ([cbuf off len]
       (if @closed-atom
         -1
         (loop []
           (let [n (.read ^StringReader @current-rdr-atom cbuf off len)]
             (if (neg? n)
               (do
                 (when on-wait-input (on-wait-input true))
                 (let [item (try (.take queue) (catch InterruptedException _ ::eof))]
                   (when on-wait-input (on-wait-input false))
                   (if (or (identical? item ::eof) (nil? item) @closed-atom)
                     (do (reset! closed-atom true) -1)
                     (do (reset! current-rdr-atom (StringReader. (str item)))
                         (recur)))))
               n))))))))

(defn find-root-cause [^Throwable t]
  (loop [cause t]
    (if-let [next (.getCause cause)] (recur next) cause)))

(defn interrupted?
  "True when e, or anything in its cause chain, is Jolt's interrupt signal."
  [e]
  (loop [c e]
    (cond (nil? c) false
          (:jolt/interrupted (ex-data c)) true
          :else (recur (.getCause ^Throwable c)))))

(defn format-error [t]
  (let [root (find-root-cause t)
        data (or (ex-data t) (ex-data root))
        msg (or (ex-message root) (ex-message t) (str root))
        type-name (.getName (class root))
        line (:clojure.error/line data)
        column (or (:clojure.error/column data) (:column data))]
    (str "Error [" type-name "]: " msg
         (when line
           (str "\nLocation: line " line (when column (str ", col " column)))))))

(defn format-value [v] (pr-str v))

;; --- REPL Command History (upstream, verbatim) ---

(defn create-history [] {:items [] :index 0 :draft ""})

(defn history-add [hist cmd]
  (let [trimmed (str/trim (or cmd ""))]
    (if (and (not (str/blank? trimmed))
             (not= (last (:items hist)) trimmed))
      (let [new-items (conj (:items hist) trimmed)]
        {:items new-items :index (count new-items) :draft ""})
      (assoc hist :index (count (:items hist)) :draft ""))))

(defn history-prev [hist current-text]
  (let [items (:items hist) total (count items)]
    (if (zero? total)
      [hist current-text]
      (let [cur-idx (:index hist)
            new-draft (if (= cur-idx total) current-text (:draft hist))
            new-idx (max 0 (dec cur-idx))]
        [(assoc hist :index new-idx :draft new-draft) (nth items new-idx)]))))

(defn history-next [hist]
  (let [items (:items hist) total (count items)]
    (if (zero? total)
      [hist ""]
      (let [cur-idx (:index hist)]
        (if (>= cur-idx total)
          [hist (:draft hist)]
          (let [new-idx (inc cur-idx)]
            (if (>= new-idx total)
              [(assoc hist :index total) (:draft hist)]
              [(assoc hist :index new-idx) (nth items new-idx)])))))))

;; --- Evaluation Context ---

(def idle-state {:status :idle})

(defn make-eval-context []
  {:queue (LinkedBlockingQueue.)
   :current-rdr (atom (StringReader. ""))
   :closed? (atom false)
   :waiting-input? (atom false)
   :history (atom (create-history))
   :running-state (atom idle-state)
   ;; Guards every running-state transition together with the
   ;; on-status-change notification it implies, so Stop's read of :thread
   ;; and a worker's write of :thread can never miss each other (fix #2),
   ;; and a finishing worker's :idle notification can never be overtaken by
   ;; the next run's :running notification (fix #3). See fix-round-1 notes
   ;; on eval-async and stop-eval! below.
   :lock (Object.)})

(defn push-stdin! [{:keys [queue]} text] (.offer queue (str text "\n")))
(defn push-stdin-eof! [{:keys [queue]}] (.offer queue ::eof))
(defn waiting-for-input? [{:keys [waiting-input?]}] @waiting-input?)
(defn evaluating? [{:keys [running-state]}] (= :running (:status @running-state)))

(defn stop-eval!
  "Asks the running evaluation to stop. Never marks the context idle itself:
   the worker does that on its way out. Returns the worker's done promise,
   or nil when nothing was running.

   Runs under the context's lock so its read of :thread can never race the
   worker's own registration of :thread (fix #2): whichever of the two runs
   first is guaranteed visible to whichever runs second, so at least one of
   them always delivers a real thread interrupt. The same lock also keeps
   this EOF offer from landing in a different run's freshly cleared queue.

   Caller rule for the lock: this whole body runs while the context's lock
   is held. The lock is reentrant on the same thread, so calling stop-eval!
   from inside an eval-async callback on the same thread (for instance from
   on-status-change) is safe -- it re-enters immediately. It is NOT safe
   across threads: Jolt's locking blocks a different thread trying to
   acquire the same lock, so if a locked callback synchronously waits on
   another thread that then calls stop-eval! (or eval-async) on this same
   context, both sides deadlock. See eval-async's docstring for which of
   its callbacks run under the lock."
  [{:keys [queue running-state lock]}]
  (locking lock
    (let [{:keys [status token thread done stop-requested]} @running-state]
      (when (= :running status)
        (reset! stop-requested true)
        (jolt.host/interrupt! token)
        (when thread (try (.interrupt ^Thread thread) (catch :default _ nil)))
        (.offer queue ::eof)
        done))))

(defn eval-async
  "Evaluates code on a worker. Options:
     :on-output (fn [kind s])  kind is :out or :err
     :on-wait-input (fn [bool])
     :on-status-change (fn [status])  :running, :waiting-input, :idle
     :on-complete (fn [result])  {:status :ok|:error|:interrupted, :ns sym, ...}
     :ns-name symbol (default 'user)
   on-output must never print: it runs on the worker thread, where *out* is
   already bound to the capture writer, so a print inside it would feed back
   into itself.

   Caller rule for the context's lock: on-status-change runs WHILE the
   context's lock is held when it reports :running (worker registration)
   and :idle (worker exit); it runs WITHOUT the lock for the mid-run
   :waiting-input/:running toggle fired while the program blocks on stdin.
   on-output, on-wait-input and on-complete never run under the lock. The
   lock is reentrant on the same thread, so it's safe for a locked
   on-status-change call to itself call eval-async or stop-eval! on this
   same context from the same thread (e.g. to chain a run). It is NOT
   safe across threads: on-status-change must never synchronously block
   waiting on another thread that may itself call eval-async or
   stop-eval! on this context, or the two threads deadlock -- a GUI
   should enqueue that work for later and return, not invoke-and-wait."
  [{:keys [queue current-rdr closed? waiting-input? running-state lock] :as ctx}
   code
   {:keys [on-output on-wait-input on-status-change on-complete] :as opts}]
  (let [ns-sym (or (:ns-name opts) 'user)]
    (locking lock
      (if (evaluating? ctx)
        "; [Warning: Evaluation already in progress. Press Stop to cancel.]\n"
        (let [token (jolt.host/make-interrupt)
              done (promise)
              stop-requested (atom false)
              emit (fn [kind] (fn [chunk] (when on-output (on-output kind chunk))))
              out-w (create-writer (emit :out))
              err-w (create-writer (emit :err))
              in-callback (fn [waiting?]
                            (reset! waiting-input? waiting?)
                            (when on-wait-input (on-wait-input waiting?))
                            (when on-status-change
                              (on-status-change (if waiting? :waiting-input :running))))
              in-rdr (LineNumberingPushbackReader.
                      (create-stdin-reader queue current-rdr closed? in-callback))]
          ;; Clearing the queue under the same lock as stop-eval!'s EOF offer
          ;; means the two can never interleave, so a Stop meant for the
          ;; previous run can't land in this run's freshly cleared queue.
          (.clear queue)
          (reset! current-rdr (StringReader. ""))
          (reset! closed? false)
          (reset! waiting-input? false)
          ;; :running is set before the worker exists, so a fast worker's own
          ;; reset to idle can never be overwritten by this thread.
          (reset! running-state {:status :running :token token :done done :stop-requested stop-requested})
          (future
            (let [result
                  (try
                    ;; Registration happens under the lock (fix #2): either
                    ;; stop-eval! already ran and set stop-requested, in
                    ;; which case we self-interrupt right here before doing
                    ;; any work, or it hasn't, in which case it will see our
                    ;; :thread once it acquires the lock and interrupt us
                    ;; directly. The :running notification is folded into
                    ;; this same critical section so it can never fire
                    ;; before a still-draining previous run's :idle
                    ;; notification finishes (fix #3): both use this lock,
                    ;; so whichever finishes its section first is fully
                    ;; visible before the other's section can begin.
                    (locking lock
                      (swap! running-state
                             (fn [s] (if (identical? (:token s) token)
                                       (assoc s :thread (Thread/currentThread))
                                       s)))
                      (when @stop-requested
                        (try (.interrupt (Thread/currentThread)) (catch :default _ nil)))
                      (when on-status-change (on-status-change :running)))
                    (let [target (or (find-ns ns-sym) (create-ns ns-sym))]
                      (binding [*out* out-w *err* err-w *in* in-rdr *ns* target]
                        (let [r (try
                                  (when-not (ns-resolve *ns* 'map) (refer-clojure))
                                  {:status :ok
                                   :value (jolt.host/run-interruptible
                                           token (fn [] (load-string (str code "\n"))))}
                                  (catch :default e
                                    (if (interrupted? e)
                                      {:status :interrupted}
                                      {:status :error :error e})))]
                          (cond-> (assoc r :ns (ns-name *ns*))
                            ;; Stop was pressed: whatever the thread interrupt
                            ;; surfaced as (InterruptedException, EOF on stdin, a
                            ;; normal return), the user asked for a stop.
                            @stop-requested (-> (assoc :status :interrupted)
                                                (dissoc :value :error))))))
                    (catch :default e
                      {:status :error :error e :ns ns-sym}))]
              ;; Fix #3: reset-to-idle and its notification are one locked
              ;; unit, so a new eval-async's own registration (locked, above)
              ;; can never squeeze its :running notification in before this
              ;; :idle notification finishes.
              (locking lock
                (reset! waiting-input? false)
                (reset! running-state idle-state)
                ;; Fix #1: on-status-change is isolated in its own try so a
                ;; throwing caller can never prevent the deliver/on-complete
                ;; below from running.
                (try
                  (when on-status-change (on-status-change :idle))
                  (catch :default _ nil)))
              ;; Fix #1: every exit path reaches here exactly once, whatever
              ;; on-status-change did above.
              (deliver done result)
              (try
                (when on-complete (on-complete result))
                (catch :default _ nil))
              result))
          nil)))))
