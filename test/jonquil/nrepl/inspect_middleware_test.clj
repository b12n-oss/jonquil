(ns jonquil.nrepl.inspect-middleware-test
  "The inspector middleware's pure handler, run in this process. In a Jolt
  child the same code answers the inspect-* ops (jonquil.nrepl.evaluator)."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [jonquil.debugger :as debugger]
            [jonquil.nrepl.inspect-middleware :as mw]))

(defn- view [state request]
  (let [r (mw/handle state request)]
    (is (= ["done"] (get r "status")))
    (edn/read-string (get r "view"))))

(deftest only-inspect-ops-are-handled
  (is (nil? (mw/handle (atom nil) {"op" "eval" "code" "1"})))
  (is (nil? (mw/handle (atom nil) {"op" "describe"}))))

(deftest start-takes-star-1-and-the-ops-navigate
  (let [state (atom nil)
        data {:a [1 2 3] :rows [{:id 1 :n "a"} {:id 2 :n "b"}]}]
    (binding [*1 data]
      (let [v (view state {"op" "inspect-start" "rows" 10 "cols" 60})]
        (is (= "*1" (:path v)))
        (is (= 2 (:total v)))))
    (testing "in, table, out, page"
      (let [v (view state {"op" "inspect-in" "idx" 1})]
        (is (= "*1 / :rows" (:path v)))
        (is (= "list" (:alt-view v)) "uniform maps open as a table"))
      (is (= "table" (:alt-view (view state {"op" "inspect-toggle-table"}))))
      (let [v (view state {"op" "inspect-out"})]
        (is (= "*1" (:path v)))
        (is (= 1 (:cursor v))))
      (is (= 0 (:start (view state {"op" "inspect-page" "delta" 1}))) "one page: paging stays put"))
    (is (= "no metadata" (:note (view state {"op" "inspect-toggle-meta" "cursor" 0}))))))

(deftest numbers-may-arrive-as-strings
  (let [state (atom nil)]
    (binding [*1 [10 20 30]]
      (view state {"op" "inspect-start" "rows" "2" "cols" "40"}))
    (is (= 2 (:start (view state {"op" "inspect-page" "delta" "1"}))))))

(deftest def-interns-into-the-named-namespace
  (let [state (atom nil)
        target (create-ns 'jonquil.nrepl.inspect-middleware-test.target)]
    (binding [*1 {:k 42}]
      (view state {"op" "inspect-start" "rows" 10 "cols" 40}))
    (is (= "#'jonquil.nrepl.inspect-middleware-test.target/picked"
           (:defd (view state {"op" "inspect-def" "name" "picked"
                               "ns" "jonquil.nrepl.inspect-middleware-test.target"}))))
    (is (= {:k 42} @(ns-resolve target 'picked)))
    (remove-ns 'jonquil.nrepl.inspect-middleware-test.target)))

(deftest answers-off-the-callers-thread
  ;; A *1 that never finishes realising: the caller (jolt's reader thread)
  ;; gets control back at once, and the reply never comes.
  (let [state (atom nil)
        replies (atom [])
        req {"op" "inspect-start" "rows" 10 "cols" 40
             :reply (fn [m] (swap! replies conj m))}
        t0 (System/currentTimeMillis)
        f (binding [*1 (lazy-seq (Thread/sleep 3000) [1])] (mw/answer! state req))]
    (is (< (- (System/currentTimeMillis) t0) 1000) "answer! returned at once")
    (is (= [] @replies))
    (future-cancel f)))

(deftest answer-replies-through-the-request
  (let [replies (promise)
        f (binding [*1 [1 2]]
            (mw/answer! (atom nil) {"op" "inspect-start" "rows" 10 "cols" 40
                                    :reply (fn [m] (deliver replies m))}))]
    @f
    (is (= 2 (:total (edn/read-string (get (deref replies 5000 {}) "view")))))))

(deftest def-without-a-known-ns-uses-user
  (let [state (atom nil)]
    (binding [*1 7]
      (view state {"op" "inspect-start" "rows" 10 "cols" 40}))
    (is (= "#'user/jonquil-mw-test-picked"
           (:defd (view state {"op" "inspect-def" "name" "jonquil-mw-test-picked" "ns" "no.such.ns"}))))
    (ns-unmap 'user 'jonquil-mw-test-picked)))

(deftest earlier-and-later-step-the-history
  (let [state (atom nil)]
    (binding [*1 [1]] (view state {"op" "inspect-start" "rows" 10 "cols" 40}))
    (binding [*1 [1 2]] (view state {"op" "inspect-start" "rows" 10 "cols" 40}))
    (let [v (view state {"op" "inspect-earlier"})]
      (is (= 1 (:total v)))
      (is (= {:at 1 :count 2} (:history v))))
    (is (= 2 (:total (view state {"op" "inspect-later"}))))
    (is (some #{"inspect-earlier"} mw/ops))
    (is (some #{"inspect-later"} mw/ops))))

(defn- frames-reply [request]
  (let [r (mw/handle (atom nil) request)]
    (cond-> r (get r "data") (update "data" edn/read-string))))

(deftest frames-of-the-last-error
  (binding [debugger/*frames-fn* (fn [_] [{:fn 'demo.core/inner :file "no/such/file.clj" :line 2 :locals {'x 21 'xs [1 2]}}])]
    (binding [*e nil]
      (is (= {:note "No error yet."} (get (frames-reply {"op" "jonquil-frames"}) "data"))))
    (binding [*e (try (/ 1 0) (catch Throwable e e))]
      (let [d (get (frames-reply {"op" "jonquil-frames"}) "data")]
        (is (= "ArithmeticException: Divide by zero" (:title d)))
        (is (= {:name "x" :text "21"} (first (:locals (first (:frames d))))))
        (is (= "no/such/file.clj" (:file (first (:frames d)))) "a name the classpath lacks stays as it is"))
      (is (= 2 (:total (view (atom nil) {"op" "inspect-local" "frame" 0 "local" "xs" "rows" 10 "cols" 40}))))
      (is (= {:error "That local is gone."}
             (edn/read-string (get (mw/handle (atom nil) {"op" "inspect-local" "frame" 0 "local" "zz" "rows" 10 "cols" 40}) "view"))))))
  (is (some #{"jonquil-frames"} mw/ops))
  (is (some #{"inspect-local"} mw/ops))
  (is (some #{"jonquil-debug-mode"} mw/ops)))

(deftest debug-mode-op
  (if (debugger/available?)
    (try
      (is (= "true" (get (mw/handle (atom nil) {"op" "jonquil-debug-mode"}) "on")))
      (is (= "false" (get (mw/handle (atom nil) {"op" "jonquil-debug-mode"}) "on")))
      (finally (debugger/set-debug-mode! false)))
    (is (= "Frames need a Jolt with jolt.debug (newer than this one)."
           (get (mw/handle (atom nil) {"op" "jonquil-debug-mode"}) "error")))))

(deftest frames-op-throw-is-an-error
  (binding [debugger/*frames-fn* (fn [_] (throw (Exception. "frames broke")))
            *e (try (/ 1 0) (catch Throwable e e))]
    (let [r (mw/handle (atom nil) {"op" "jonquil-frames"})]
      (is (= "frames broke" (get r "error")))
      (is (nil? (get r "data"))))))

(deftest frame-eval-op
  (let [e (try (/ 1 0) (catch Throwable t t))]
    (binding [debugger/*frames-fn* (fn [_] [{:fn 'demo.core/inner :file nil :line 1 :locals {'x 21 'y 42}}])
              *e e]
      (is (= {"status" ["done"] "frame-out" "" "text" "63"}
             (mw/handle (atom nil) {"op" "jonquil-frame-eval" "frame" 0 "code" "(+ x y)" "error-id" (debugger/error-id e)})))
      (is (= "The panel shows an older error: show the frames again."
             (get (mw/handle (atom nil) {"op" "jonquil-frame-eval" "frame" 0 "code" "x" "error-id" (str (debugger/error-id e) "x")}) "error")))
      (is (= {:error "The panel shows an older error: show the frames again."}
             (edn/read-string (get (mw/handle (atom nil) {"op" "inspect-local" "frame" 0 "local" "x" "rows" 10 "cols" 40
                                                          "error-id" (str (debugger/error-id e) "x")}) "view"))))))
  (is (some #{"jonquil-frame-eval"} mw/ops)))

(deftest frame-eval-never-holds-the-inspector
  ;; a slow form evaluated in a frame runs outside the inspector's lock, so
  ;; an inspect-start sent after it is answered first
  (let [state (atom nil) order (atom []) e (try (/ 1 0) (catch Throwable t t))
        slow-done (promise) view-done (promise)]
    (binding [debugger/*frames-fn* (fn [_] [{:fn 'user/f :file nil :line 1 :locals {}}])
              *e e *1 [1 2]]
      (mw/answer! state {"op" "jonquil-frame-eval" "frame" 0 "code" "(do (Thread/sleep 1500) 1)" "error-id" (debugger/error-id e)
                         :reply (fn [m] (swap! order conj :frame-eval) (deliver slow-done m))})
      (Thread/sleep 100)
      (mw/answer! state {"op" "inspect-start" "rows" 10 "cols" 40
                         :reply (fn [m] (swap! order conj :inspect) (deliver view-done m))})
      (deref view-done 5000 nil)
      (deref slow-done 5000 nil)
      (is (= [:inspect :frame-eval] @order)))))

(deftest frame-interrupt-op
  (let [e (try (/ 1 0) (catch Throwable t t)) reply (promise)]
    (binding [debugger/*frames-fn* (fn [_] [{:fn 'user/f :file nil :line 1 :locals {}}])
              *e e]
      (mw/answer! (atom nil) {"op" "jonquil-frame-eval" "frame" 0 "code" "(loop [] (recur))" "error-id" (debugger/error-id e)
                              :reply (fn [m] (deliver reply m))})
      (Thread/sleep 300)
      (is (= {"status" ["done"]} (mw/handle (atom nil) {"op" "jonquil-frame-interrupt"})))
      (is (= "Stopped." (get (deref reply 5000 {}) "error")))
      (is (some #{"jonquil-frame-interrupt"} mw/ops)))))

(defn- data-of [request]
  (let [r (mw/handle (atom nil) request)]
    (is (= ["done"] (get r "status")))
    (cond-> r (get r "data") (update "data" edn/read-string))))

(deftest line-counts-op-actions
  (testing "an unknown action answers an error map as data"
    (is (= {"status" ["done"] "data" {:error "jonquil-line-counts: no action \"nope\""}}
           (data-of {"op" "jonquil-line-counts" "action" "nope"}))))
  (is (some #{"jonquil-line-counts"} mw/ops)))

(deftest keep-result-and-inspect-result-ops
  (let [kept (binding [*1 [1 2 3]]
               (get (data-of {"op" "jonquil-keep-result" "what" "value"}) "data"))
        ek (binding [*e (ex-info "boom" {})]
             (get (data-of {"op" "jonquil-keep-result" "what" "error"}) "data"))]
    (is (= :value (:kind kept)))
    (is (= :exception (:kind ek)))
    (let [r (mw/handle (atom nil) {"op" "inspect-result" "result-id" (str (:id kept)) "result-epoch" (:epoch kept)
                                   "rows" 10 "cols" 40})]
      (is (= ["done"] (get r "status")))
      (is (= 3 (:total (edn/read-string (get r "view"))))))
    (is (= {"status" ["done"] "view" (pr-str {:error "That result is gone."})}
           (mw/handle (atom nil) {"op" "inspect-result" "result-id" "999999999" "result-epoch" (:epoch kept)
                                  "rows" 10 "cols" 40})))))

(deftest inspect-trace-op-gone
  (is (= {"status" ["done"] "view" (pr-str {:error "That call is gone."})}
         (mw/handle (atom nil) {"op" "inspect-trace" "trace-id" "999999999" "part" "ret" "rows" 10 "cols" 40}))))

(deftest trace-fibers-world-ops-answer-data-or-an-error
  (testing "an unknown action is a done reply with an error and no data"
    (doseq [op ["jonquil-trace" "jonquil-world"]]
      (let [r (mw/handle (atom nil) {"op" op "action" "no-such-action"})]
        (is (= ["done"] (get r "status")) op)
        (is (string? (get r "error")) op)
        (is (nil? (get r "data")) op))))
  (testing "a trace log answers its entries as data"
    (let [r (mw/handle (atom nil) {"op" "jonquil-trace" "action" "log" "since" "-1"})]
      (is (= ["done"] (get r "status")))
      (is (contains? (edn/read-string (get r "data")) :entries))))
  (testing "fibers answer data or an error, always done"
    (let [r (mw/handle (atom nil) {"op" "jonquil-fibers" "action" "no-such-action"})]
      (is (= ["done"] (get r "status")))
      (is (or (string? (get r "error")) (string? (get r "data")))))))
