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
  :exception true with :val the error map's pr-str."
  [ns-obj form]
  (let [sw (java.io.StringWriter.)]
    (try
      (let [v (binding [*ns* ns-obj *out* sw] (eval form))]
        {:out (str sw) :val (pr-str v)})
      (catch Throwable e
        {:out (str sw) :exception true :val (error-val e)}))))

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

(defn- serve-connection! [{:keys [opts forms stopped?]} ^java.net.Socket sock]
  ;; Lines are read with readLine and forms cut from the buffer: on Jolt a
  ;; reader over the socket blocks until it has more than one line.
  (let [in (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream sock) "UTF-8"))
        w (java.io.OutputStreamWriter. (.getOutputStream sock) "UTF-8")
        lock (Object.)]
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
              (if (and (:apropos-val opts) (str/includes? text "cljs.repl/apropos"))
                ;; the real prepl expands the macro on the compiler side;
                ;; this sandbox can't, so the answer is canned
                (do (send! w lock {:tag :ret :val (:apropos-val opts) :ns current :ms 0 :form text})
                    (recur more current setup?))
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
  :hold-apropos-ms  sleep this long before answering such a form"
  ([] (start! {}))
  ([opts]
   (let [ss (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))
         srv {:opts opts :server ss :port (.getLocalPort ss)
              :forms (atom []) :conns (atom []) :futures (atom [])
              :stopped? (atom false)}]
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
        srv (start! (cond-> {} slow (assoc :slow-ms (parse-long slow))))]
    (println (str "jonquil-cljs-prepl " (:port srv)))
    (flush)
    (while (not= -1 (.read System/in)))
    (stop! srv)
    (System/exit 0)))
