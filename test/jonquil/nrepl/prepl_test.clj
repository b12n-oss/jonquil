(ns jonquil.nrepl.prepl-test
  (:require [clojure.test :refer [deftest is]]
            [fake-cljs-prepl :as fake]
            [jonquil.nrepl.prepl :as prepl]))

(defn- wait-for
  "Polls pred until true or ms pass; the final value of pred."
  [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond (pred) true
            (< (System/currentTimeMillis) deadline) (do (Thread/sleep 10) (recur))
            :else (boolean (pred))))))

(defn- with-conn [on-reply-wrap f]
  (let [srv (fake/start!)
        got (atom [])
        on-reply (on-reply-wrap (fn [m] (swap! got conj m)))
        conn (prepl/connect! "127.0.0.1" (:port srv) on-reply)]
    (try (f conn got)
         (finally (prepl/close! conn) (fake/stop! srv)))))

(defn- closed-count [got] (count (filter #(= :closed (:tag %)) @got)))

(deftest send-delivers-rets-in-order-then-close-once
  (with-conn identity
    (fn [conn got]
      (is (prepl/open? conn))
      (prepl/send! conn "(+ 1 2) :done")
      (is (wait-for #(= 2 (count @got)) 3000))
      (is (= [["3" "cljs.user"] [":done" "cljs.user"]]
             (mapv (juxt :val :ns) @got)))
      (is (every? #(= :ret (:tag %)) @got))
      (Thread/sleep 150)
      (is (= 2 (count @got)) "nothing more arrives")
      (prepl/close! conn)
      (is (not (prepl/open? conn)))
      (is (wait-for #(= 1 (closed-count got)) 3000))
      (prepl/close! conn)
      (Thread/sleep 100)
      (is (= 1 (closed-count got)) "close! is idempotent, :closed once")
      (is (= {:tag :closed} (last @got))))))

(deftest send-after-close-throws-and-writes-nothing
  (let [srv (fake/start!)
        conn (prepl/connect! "127.0.0.1" (:port srv) (fn [_]))]
    (try
      (prepl/close! conn)
      (is (thrown? java.io.IOException (prepl/send! conn ":late")))
      (Thread/sleep 100)
      (is (= [] (fake/received-forms srv)))
      (finally (fake/stop! srv)))))

(deftest a-throwing-on-reply-does-not-kill-the-reader
  (let [calls (atom 0)]
    (with-conn (fn [record]
                 (fn [m]
                   (swap! calls inc)
                   (record m)
                   (when (= :ret (:tag m)) (throw (ex-info "boom" {})))))
      (fn [conn got]
        (prepl/send! conn "1 2 3")
        (is (wait-for #(= 3 (count @got)) 3000))
        (is (= ["1" "2" "3"] (mapv :val @got)))))))

(deftest peer-going-away-delivers-closed-once
  (let [srv (fake/start!)
        got (atom [])
        conn (prepl/connect! "127.0.0.1" (:port srv) #(swap! got conj %))]
    (try
      (fake/stop! srv)
      (is (wait-for #(= 1 (closed-count got)) 3000))
      (is (not (prepl/open? conn)))
      (prepl/close! conn)
      (Thread/sleep 100)
      (is (= 1 (closed-count got)))
      (finally (prepl/close! conn)))))

(deftest connect-failure-throws
  (let [ss (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))
        port (.getLocalPort ss)]
    (.close ss)
    (is (thrown? java.io.IOException (prepl/connect! "127.0.0.1" port (fn [_]))))))

(deftest closed-is-last-and-delivery-never-overlaps
  (let [ss (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))
        writer (future
                 (try
                   (let [s (.accept ss)
                         w (java.io.OutputStreamWriter. (.getOutputStream s) "UTF-8")]
                     (dotimes [i 3000]
                       (.write w (str "{:tag :out :val \"" i "\"}\n")))
                     (.flush w)
                     (Thread/sleep 500)
                     (.close s))
                   (catch Throwable _ nil)))
        got (atom [])
        in-flight (atom 0)
        max-in-flight (atom 0)
        conn (prepl/connect! "127.0.0.1" (.getLocalPort ss)
                             (fn [m]
                               (let [n (swap! in-flight inc)]
                                 (swap! max-in-flight max n)
                                 (Thread/sleep 1)
                                 (swap! got conj m)
                                 (swap! in-flight dec))))]
    (try
      (is (wait-for #(>= (count @got) 5) 3000))
      (prepl/close! conn)
      (is (= {:tag :closed} (last @got)) "closed is the last delivered reply at close! return")
      (let [n (count @got)]
        (Thread/sleep 300)
        (is (= n (count @got)) "nothing delivered after :closed"))
      (is (= 1 (closed-count got)))
      (is (= 1 @max-in-flight) "on-reply never ran concurrently")
      (finally (prepl/close! conn) (.close ss) (deref writer 2000 nil)))))

(deftest throwing-on-reply-on-closed-is-swallowed
  (let [calls (atom [])
        srv (fake/start!)
        conn (prepl/connect! "127.0.0.1" (:port srv)
                             (fn [m] (swap! calls conj m) (throw (ex-info "boom" {}))))]
    (try
      (prepl/close! conn)
      (prepl/close! conn)
      (Thread/sleep 100)
      (is (= [{:tag :closed}] @calls))
      (finally (fake/stop! srv)))))
