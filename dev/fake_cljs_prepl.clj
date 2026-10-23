(ns fake-cljs-prepl
  "A fake ClojureScript prepl for the cljs-node tests and the window
  self-test, usable in-process (start!) and as the process
  dev/fake-cljs-clojure runs (-main). It imitates the prepl of
  ClojureScript 1.12.42 as measured for the cljs-node arc:

  - Line-oriented EDN replies on a loopback socket. Each top-level form
    read off the stream gets exactly one {:tag :ret :val :ns :ms :form}
    line, in order, :val a pr-str'd string and :form the form's source
    text. *out* text during a form arrives first as {:tag :out :val s}.
  - An exception gives a ret with :exception true and :val the pr-str of
    {:via [{:type t :message m}] :cause m}.
  - The real bug, on request (:wedge-on-throw-without-setup?): a throw
    before (require 'cljs.repl) was read on the connection leaves the
    connection silent for good.

  Forms run in Jolt, in a sandbox namespace per connection (cljs.user, or
  whatever (ns X) / (in-ns 'X) last named). (require 'cljs.repl) is a
  no-op. -main prints `jonquil-cljs-prepl <port>`, flushes, and exits 0
  once stdin reaches EOF; FAKE_CLJS_SLOW_MS sets :slow-ms."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]))

;; The stdin-dance protocol constants, spelled out rather than required
;; from jonquil.cljs-node: the :fake-cljs alias runs this file alone, and
;; the evaluator unit tests prove the spellings agree end to end.
(def ^:private need-input-marker "\u0001jonquil:need-input\u0001")
(def ^:private got-input-marker "\u0001jonquil:got-input\u0001")
(def ^:private input-eof-marker "\u0001jonquil:eof\u0001")

(defn- cljs-repl-require? [x]
  (and (seq? x) (= 'require (first x))
       (some (fn [a] (and (seq? a) (= 'quote (first a)) (= 'cljs.repl (second a))))
             (rest x))))

(defn- strip-setup
  "form with every (require 'cljs.repl ...) sub-form replaced by nil."
  [form]
  (walk/prewalk (fn [x] (if (cljs-repl-require? x) nil x)) form))

(defn- send! [^java.io.Writer w lock m]
  (locking lock
    (.write w (str (pr-str m) "\n"))
    (.flush w)))

(defn- error-val [e]
  (let [m (or (ex-message e) (str e))]
    (pr-str {:via [{:type (str (class e)) :message m}] :cause m})))

(defn- eval-form
  "Evaluates form in the sandbox ns. Returns {:out s} plus :val, or
  :exception true with :val the error map's pr-str. With in-text, *in* is
  bound to it, so read-line inside the form reads that (the stdin dance)."
  ([ns-obj form] (eval-form ns-obj form nil))
  ([ns-obj form in-text]
   (let [sw (java.io.StringWriter.)
         rdr (java.io.BufferedReader. (java.io.StringReader. (str in-text)))]
     (try
       (let [v (binding [*ns* ns-obj *out* sw *in* rdr] (eval form))]
         {:out (str sw) :val (pr-str v)})
       (catch Throwable e
         {:out (str sw) :exception true :val (error-val e)})))))

;; --- the stdin dance (read-line over the child's stdin, 2026-10-23) -------

(defn- read-line-set!?
  "True for the reader setup's own form, (set! cljs.core/read-line F): the
  real prepl compiles it, this sandbox can't (set! needs a thread-bound
  var), so it's answered without evaluating."
  [form]
  (and (seq? form) (= 'set! (first form)) (= 'cljs.core/read-line (second form))))

(defn- thrower-message
  "The unavailable-reader's message when form carries one, else nil (the
  FIFO reader)."
  [form]
  (->> (tree-seq coll? seq form)
       (filter #(and (string? %) (str/starts-with? % "read-line isn't available")))
       first))

(defn- take-input-line!
  "The next line for a read-line dance: the head of :stdin-queue (the
  :stdin-script lines first, then whatever the stdin reader thread added),
  or nil (EOF) when the stream ended, the server stopped, or there is no
  stream to wait on. Blocks by polling, as the real FIFO read blocks."
  [{:keys [opts stdin-queue stdin-eof? stopped?]}]
  (loop []
    (let [[old _] (swap-vals! stdin-queue (fn [q] (if (seq q) (subvec q 1) q)))]
      (cond
        (seq old) (first old)
        (or @stopped?
            (nil? (:stdin-in opts))
            (realized? stdin-eof?)) nil
        :else (do (Thread/sleep 10) (recur))))))

(defn- ns-after
  "The ns name to report once form has run: the one an (ns X) or
  (in-ns 'X) names, else current."
  [form current]
  (if (and (seq? form) (#{'ns 'in-ns} (first form)))
    (let [a (second form)]
      (str (if (and (seq? a) (= 'quote (first a))) (second a) a)))
    current))

(defn- next-form
  "[form text rest-of-buffer] for the first complete form in buf, or nil
  when buf holds no complete form yet."
  [buf]
  (let [r (java.io.PushbackReader. (java.io.StringReader. buf))]
    (try
      (let [[form text] (read+string r)
            end (+ (str/index-of buf text) (count text))]
        [form text (subs buf end)])
      (catch Throwable _ nil))))

(defn- serve-connection! [{:keys [opts forms stopped?] :as srv} ^java.net.Socket sock]
  ;; Lines are read with readLine and forms cut from the buffer: on Jolt a
  ;; reader over the socket blocks until it has more than one line.
  (let [in (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream sock) "UTF-8"))
        w (java.io.OutputStreamWriter. (.getOutputStream sock) "UTF-8")
        lock (Object.)
        ;; set when the reader setup defined read-line as a thrower
        thrower (atom nil)]
    (try
      (loop [buf "" current "cljs.user" setup? false]
        (if-let [[form text more] (next-form buf)]
          (do
            (swap! forms conj text)
            (let [setup? (or setup? (boolean (re-find #"\(require\s+'cljs\.repl" text)))]
              (when (and (:hold-setup-ms opts) (re-find #"\(require\s+'cljs\.repl" text))
                (Thread/sleep (long (:hold-setup-ms opts))))
              (when (and (:slow-ms opts) (str/includes? text "slow"))
                (Thread/sleep (long (:slow-ms opts))))
              (when (and (:hold-apropos-ms opts) (str/includes? text "cljs.repl/apropos"))
                (Thread/sleep (long (:hold-apropos-ms opts))))
              (cond
                (and (:apropos-val opts) (str/includes? text "cljs.repl/apropos"))
                ;; the real prepl expands the macro on the compiler side;
                ;; this sandbox can't, so the answer is canned
                (do (send! w lock {:tag :ret :val (:apropos-val opts) :ns current :ms 0 :form text})
                    (recur more current setup?))

                (read-line-set!? form)
                (do (reset! thrower (thrower-message form))
                    (send! w lock {:tag :ret :val "nil" :ns current :ms 0 :form text})
                    (recur more current setup?))

                (and @thrower (re-find #"\(read-line\)" text))
                (let [msg @thrower]
                  (send! w lock {:tag :ret :exception true :ns current :ms 0 :form text
                                 :val (pr-str {:via [{:type "Error" :message msg}] :cause msg})})
                  (recur more current setup?))

                (and (:stdin-dance? opts) (re-find #"\(read-line\)" text))
                (do (send! w lock {:tag :err :val (str need-input-marker "\n")})
                    (let [line (take-input-line! srv)
                          eof? (or (nil? line) (= line input-eof-marker))
                          result (eval-form (create-ns (symbol current)) (strip-setup form)
                                            (if eof? "" (str line "\n")))
                          new-ns (ns-after form current)]
                      (send! w lock {:tag :err :val (str got-input-marker "\n")})
                      (when (seq (:out result))
                        (send! w lock {:tag :out :val (:out result)}))
                      (send! w lock (merge {:tag :ret :val (:val result) :ns new-ns :ms 0 :form text}
                                           (when (:exception result) {:exception true})))
                      (recur more new-ns setup?)))

                :else
                (let [result (if (and (:fail-setup? opts) (re-find #"\(require\s+'cljs\.repl" text))
                               {:out "" :exception true :val (error-val (ex-info "no page" {}))}
                               (eval-form (create-ns (symbol current)) (strip-setup form)))]
                  (if (and (:exception result) (:wedge-on-throw-without-setup? opts) (not setup?))
                    ;; the real bug: never answer again, just drain until EOF
                    (loop [] (when (and (.readLine in) (not @stopped?)) (recur)))
                    (let [new-ns (ns-after form current)]
                      (when (seq (:out result))
                        (send! w lock {:tag :out :val (:out result)}))
                      (send! w lock (merge {:tag :ret :val (:val result) :ns new-ns :ms 0 :form text}
                                           (when (:exception result) {:exception true})))
                      (recur more new-ns setup?)))))))
          (when-let [line (and (not @stopped?) (.readLine in))]
            (recur (str buf line "\n") current setup?))))
      (catch Throwable _ nil)
      (finally (try (.close sock) (catch Throwable _ nil))))))

(defn start!
  "Starts a fake prepl on a free 127.0.0.1 port and returns the server.
  opts:
  :wedge-on-throw-without-setup?  reproduce the real prepl bug (see ns doc)
  :slow-ms  sleep this long before answering a form whose source holds
            \"slow\"
  :hold-setup-ms  sleep this long before answering (require 'cljs.repl),
            as the browser prepl does until a page connects
  :fail-setup?  answer (require 'cljs.repl) with an exception (after any
            :hold-setup-ms), as a browser setup that fails late would
  :apropos-val  answer a form holding cljs.repl/apropos with this string
            as :val, without evaluating (this sandbox can't expand the
            macro); the form is still recorded
  :hold-apropos-ms  sleep this long before answering such a form
  :stdin-dance?  a form whose text holds (read-line) answers as the real
            reader does: an :err need-input marker, one input line (from
            :stdin-script first, then :stdin-in), an :err got-input
            marker, then the form's ret with read-line bound to that line
            (nil for the EOF marker or a closed stream)
  :stdin-script  input lines handed to the dance in order, before :stdin-in
  :stdin-in  an InputStream a reader thread feeds the dance from (the
            child's own stdin in -main); stop! closes it"
  ([] (start! {}))
  ([opts]
   (let [ss (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))
         srv {:opts opts :server ss :port (.getLocalPort ss)
              :forms (atom []) :conns (atom []) :futures (atom [])
              :stdin-queue (atom (vec (:stdin-script opts)))
              :stdin-eof? (promise)
              :stopped? (atom false)}]
     (when-let [^java.io.InputStream stdin (:stdin-in opts)]
       (future
         (try
           (let [r (java.io.BufferedReader. (java.io.InputStreamReader. stdin "UTF-8"))]
             (loop []
               (when-let [line (.readLine r)]
                 (swap! (:stdin-queue srv) conj line)
                 (recur))))
           (catch Throwable _ nil)
           (finally (deliver (:stdin-eof? srv) true)))))
     (future
       (try
         (loop []
           (let [sock (.accept ss)]
             (if @(:stopped? srv)
               (.close sock)
               (do (swap! (:conns srv) conj sock)
                   (swap! (:futures srv) conj (future (serve-connection! srv sock)))
                   (recur)))))
         (catch Throwable _ nil)))
     srv)))

(defn received-forms
  "The source text of every form the server has read, in arrival order."
  [srv]
  @(:forms srv))

(defn stop!
  "Stops accepting, closes every connection and waits (bounded) for the
  connection threads."
  [srv]
  (reset! (:stopped? srv) true)
  (when-let [in (:stdin-in (:opts srv))]
    (try (.close ^java.io.InputStream in) (catch Throwable _ nil)))
  (try (.close (java.net.Socket. "127.0.0.1" (int (:port srv)))) (catch Throwable _ nil))
  (try (.close ^java.net.ServerSocket (:server srv)) (catch Throwable _ nil))
  (doseq [s @(:conns srv)] (try (.close ^java.net.Socket s) (catch Throwable _ nil)))
  (let [deadline (+ (System/currentTimeMillis) 1000)]
    ;; A connection thread mid-read throws SocketException once its socket
    ;; closes, and deref rethrows that: wait for each, whatever it ended in
    ;; (this made stop! itself throw now and then on a loaded Linux run).
    (doseq [f @(:futures srv)]
      (try (deref f (max 0 (- deadline (System/currentTimeMillis))) nil)
           (catch Throwable _ nil))))
  nil)

(defn -main [& _]
  (let [slow (System/getenv "FAKE_CLJS_SLOW_MS")
        ;; The dance reads the process's own stdin: the lines Jonquil's
        ;; send-input! writes, as the real launcher's relay does. The
        ;; announced token is openly fake; the reader setup it provokes is
        ;; intercepted (read-line-set!?), never opened as a path.
        srv (start! (cond-> {:stdin-dance? true :stdin-in System/in}
                      slow (assoc :slow-ms (parse-long slow))))]
    (println (str "jonquil-cljs-prepl " (:port srv) " stdin:fake"))
    (flush)
    ;; stdin EOF still means Jonquil is gone: the reader thread sees it.
    @(:stdin-eof? srv)
    (stop! srv)
    (System/exit 0)))
