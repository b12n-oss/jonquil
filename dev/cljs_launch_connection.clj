;; A plain JVM Clojure script, run by jonquil.live.cljs-node-live-test (bb test:cljs-node):
;;   clojure -M this.clj <path to cljs_node_launch.clj> scenarios|mismatch|missing
;; It stands in fake cljs.repl.server vars (lock, connq, promiseq, connection)
;; and real loopback sockets, loads the launcher, runs its private
;; live-connection! and prints one EDN map per scenario.
(ns user
  (:import (java.net InetAddress ServerSocket Socket)))

(def server-lock (Object.))
(def connq (java.util.LinkedList.))
(def promiseq (java.util.LinkedList.))
(create-ns 'cljs.repl.server)
(intern 'cljs.repl.server 'lock server-lock)
(intern 'cljs.repl.server 'connq connq)
(intern 'cljs.repl.server 'promiseq promiseq)

(def launcher (first *command-line-args*))
(def mode (second *command-line-args*))

(defn- nsv [s] @(ns-resolve 'jonquil.cljs-node-launch s))

(defn- install-connection!
  "Defines the stock connection fn the launcher replaces, with arglists."
  [arglists]
  (intern 'cljs.repl.server (with-meta 'connection {:arglists arglists}) (fn [& _] :orig)))

(defn- pair
  "A connected loopback pair [server-socket client server-side]."
  []
  (let [ss (ServerSocket. 0 50 (InetAddress/getByName "127.0.0.1"))
        c (Socket. "127.0.0.1" (.getLocalPort ss))]
    [ss c (.accept ss)]))

(defn- queues [] [(.size connq) (.size promiseq)])

(defn- say [label m] (prn (assoc m :scenario label)))

(defn- scenarios []
  (let [f @(resolve 'cljs.repl.server/connection)
        gone! #(reset! (nsv 'page-gone?) %)]
    (let [p (f)]
      (say :empty {:realized? (realized? p) :queues (queues) :gone? @(nsv 'page-gone?)}))
    (.clear promiseq)
    (let [[_ _ s] (pair)]
      (.offer connq s)
      (let [p (f)]
        (say :live {:realized? (realized? p) :same? (identical? s @p) :queues (queues)
                    :gone? @(nsv 'page-gone?)})))
    (let [[_ c s] (pair)]
      (.close c)
      (Thread/sleep 100)
      (.offer connq s)
      (let [t0 (System/currentTimeMillis)
            r (try (f) (catch Throwable e (ex-message e)))]
        (say :dead {:message (when (string? r) r) :queues (queues) :closed? (.isClosed s)
                    :gone? @(nsv 'page-gone?)
                    :waited-grace? (> (- (System/currentTimeMillis) t0) 1800)})))
    (gone! true)
    (let [[_ _ s] (pair)
          fut (future (Thread/sleep 300)
                      (locking server-lock (deliver (.poll promiseq) s)))
          p (f)]
      @fut
      (say :late-page {:realized? (realized? p) :same? (identical? s @p) :queues (queues)
                       :gone? @(nsv 'page-gone?)}))
    (gone! true)
    (say :gone {:message (try (f) (catch Throwable e (ex-message e))) :queues (queues)})
    (gone! true)
    (let [[_ c dead] (pair)
          [_ _ live] (pair)]
      (.close c)
      (Thread/sleep 100)
      (.offer connq dead)
      (.offer connq live)
      (let [p (f)]
        (say :dead-then-live {:realized? (realized? p) :same? (identical? live @p)
                              :dead-closed? (.isClosed dead) :queues (queues)
                              :gone? @(nsv 'page-gone?)})))))

(case mode
  "scenarios" (do (install-connection! '([])) (load-file launcher) ((nsv 'live-connection!))
                  (scenarios))
  ;; the stock fn has other arglists: the launcher names it and exits 2
  "mismatch" (do (install-connection! '([x])) (load-file launcher) ((nsv 'live-connection!)))
  ;; the stock fn doesn't exist at all
  "missing" (do (load-file launcher) ((nsv 'live-connection!))))
(shutdown-agents)
(System/exit 0)
