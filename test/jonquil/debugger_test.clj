(ns jonquil.debugger-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jonquil.debugger :as debugger]))

(def ^:private fake-frames
  [{:fn 'demo.core/inner :file "demo/core.clj" :line 2 :locals {'y 42 'x 21}}
   {:fn 'user/fn--2 :file nil :line 1 :locals {}}])

(defn- fake [_] fake-frames)

(defn- caught [f] (try (f) nil (catch Throwable e e)))

(def ^:private div-zero (caught #(/ 1 0)))

(deftest title-test
  (is (= "ArithmeticException: Divide by zero"
         (:title (debugger/frames-data div-zero {:frames-fn fake}))))
  (is (= "ExceptionInfo: boom {:k 1}"
         (:title (debugger/frames-data (ex-info "boom" {:k 1}) {:frames-fn fake})))))

(deftest frames-and-locals-test
  (let [d (debugger/frames-data div-zero {:frames-fn fake})]
    (is (= ["demo.core/inner" "user/fn--2"] (mapv :fn (:frames d))))
    (is (= [{:name "x" :text "21"} {:name "y" :text "42"}] (:locals (first (:frames d)))))
    (is (= [nil 1] [(:file (second (:frames d))) (:line (second (:frames d)))]))))

(deftest rows-test
  (is (= ["demo.core/inner  core.clj:2" "    x = 21" "    y = 42" "user/fn--2  line 1 (no file)"]
         (mapv :text (debugger/rows (debugger/frames-data div-zero {:frames-fn fake})))))
  (is (= [:frame :local :local :frame]
         (mapv :kind (debugger/rows (debugger/frames-data div-zero {:frames-fn fake})))))
  (is (= {:kind :local :frame 0 :name "y" :text "    y = 42"}
         (nth (debugger/rows (debugger/frames-data div-zero {:frames-fn fake})) 2))))

(deftest no-file-no-line-test
  (is (= "f  (no file)"
         (:text (first (debugger/rows {:title "t" :frames [{:fn "f" :file nil :line nil :locals []}]}))))))

(deftest harness-frames-cut-test
  (testing "the list stops at the evaluator's own frames"
    (is (= ["user/dbg-inner" "user/fn--3"]
           (mapv :fn (:frames (debugger/frames-data
                               div-zero
                               {:frames-fn (constantly [{:fn 'user/dbg-inner :locals {}} {:fn 'user/fn--3 :locals {}}
                                                        {:fn "jonquil.eval/eval-async/fn--7" :locals {}}
                                                        {:fn 'user/after :locals {}}])})))))
    (is (= ["user/x"]
           (mapv :fn (:frames (debugger/frames-data
                               div-zero
                               {:frames-fn (constantly [{:fn 'user/x :locals {}} {:fn 'nrepl.middleware.session/run-eval :locals {}}
                                                        {:fn 'jolt.nrepl/evaluate :locals {}}])}))))))
  (testing "a fault inside the harness itself keeps every frame"
    (is (= ["jonquil.eval/eval-async/fn--7" "user/y"]
           (mapv :fn (:frames (debugger/frames-data
                               div-zero
                               {:frames-fn (constantly [{:fn "jonquil.eval/eval-async/fn--7" :locals {}}
                                                        {:fn 'user/y :locals {}}])})))))))

(deftest resolve-file-test
  (let [d (debugger/frames-data div-zero {:frames-fn fake :resolve-file #(str "/abs/" %)})]
    (is (= "/abs/demo/core.clj" (:file (first (:frames d)))))
    (is (nil? (:file (second (:frames d)))) "no file stays nil")))

(deftest no-frames-api-test
  (let [d (debugger/frames-data div-zero {:frames-fn nil})]
    (is (= "ArithmeticException: Divide by zero" (:title d)))
    (is (= [{:kind :note :text "Frames need a Jolt with jolt.debug (newer than this one)."}]
           (debugger/rows d)))))

(deftest no-frames-test
  (is (= [{:kind :note :text "No frames: the error came from code compiled without them."}]
         (debugger/rows (debugger/frames-data div-zero {:frames-fn (constantly [])}))))
  (is (= [{:kind :note :text "No error yet."}] (debugger/rows {:note "No error yet."}))))

(deftype Unprintable [])
(defmethod print-method Unprintable [_ _] (throw (Exception. "no printing")))

(deftest value-text-test
  (testing "an infinite seq prints bounded"
    (let [t (debugger/value-text (range))]
      (is (< (count t) 120) t)
      (is (str/ends-with? t "...)") t)))
  (testing "a long string is cut to 100 characters"
    (let [t (debugger/value-text (apply str (repeat 500 "a")))]
      (is (= 101 (count t)))
      (is (str/ends-with? t "…"))))
  (testing "a value whose printing throws"
    (is (str/starts-with? (debugger/value-text (Unprintable.)) "<unprintable: "))))

(deftest local-value-test
  (is (= 21 (debugger/local-value div-zero 0 "x" {:frames-fn fake})))
  (is (= ::debugger/missing (debugger/local-value div-zero 0 "z" {:frames-fn fake})))
  (is (= ::debugger/missing (debugger/local-value div-zero 9 "x" {:frames-fn fake}))))

(deftest real-frames-test
  (if-not (debugger/available?)
    (is true "skipped: this Jolt has no jolt.debug")
    (let [instrument! (requiring-resolve 'jolt.profile/instrument!)]
      (instrument! :debug)
      (try
        (let [f (eval (read-string "(fn [q] (let [r (str q)] (/ (count r) 0)))"))
              e (caught #(f :abc))
              d (debugger/frames-data e {:frames-fn (debugger/frames-fn)})]
          (is (some (fn [fr] (some #(= "q" (:name %)) (:locals fr))) (:frames d)) (pr-str d)))
        (finally (instrument! false))))))

(deftest lazy-locals-stay-unrealized-test
  (let [calls (atom 0)
        lazy (map (fn [x] (swap! calls inc) x) [1 2 3])]
    (is (= "<lazy seq, not realized>" (debugger/value-text lazy)))
    (is (= "[1 <lazy seq, not realized>]" (debugger/value-text [1 lazy])))
    (is (= "{:xs <lazy seq, not realized>}" (debugger/value-text {:xs lazy})))
    (is (= 0 @calls) "opening the panel ran none of the program's code")
    (doall lazy)
    (is (= "(1 2 3)" (debugger/value-text lazy)) "a realized one prints")))

(deftest toggle-debug-mode-test
  (if (debugger/available?)
    (try
      (is (= {:on? true} (debugger/toggle-debug-mode!)))
      (is (= {:on? false} (debugger/toggle-debug-mode!)))
      (finally (debugger/set-debug-mode! false)))
    (is (= {:error "Frames need a Jolt with jolt.debug (newer than this one)."} (debugger/toggle-debug-mode!)))))

(deftest out-of-stack-note-test
  (testing "no frames from running out of stack or memory says why, not 'compiled without them'"
    (doseq [e [(java.lang.OutOfMemoryError. "jolt: out of memory") (java.lang.StackOverflowError. "stack limit")]]
      (is (= [{:kind :note :text "No frames: Jolt ran out of stack or memory and kept none. With debug mode on, deep recursion that doesn't use recur ends this way."}]
             (debugger/rows (debugger/frames-data e {:frames-fn (constantly [])})))
          (str (class e)))))
  (testing "a stack overflow that kept its frames lists them"
    (is (= :frame (:kind (first (debugger/rows (debugger/frames-data (java.lang.StackOverflowError. "s")
                                                                     {:frames-fn (constantly [{:fn 'user/f :line 1 :locals {}}])}))))))))

;; --- evaluate in a frame ---------------------------------------------------

(deftest label-ns-test
  (is (= 'demo.core (debugger/label-ns "demo.core/inner")))
  (is (= 'demo.shapes (debugger/label-ns "demo.shapes/area[:sq]")))
  (is (= 'demo.core (debugger/label-ns "demo.core/inner/fn--3")))
  (is (= 'user (debugger/label-ns "fn--2"))))

(deftest eval-in-frame-test
  (testing "the frame's locals are bound; an unloaded frame namespace falls back to user"
    (is (= {:out "" :text "63"} (debugger/eval-in-frame div-zero 0 "(+ x y)" {:frames-fn fake}))))
  (testing "what the form prints comes back beside its value"
    (is (= {:out ":hi\n" :text "21"} (debugger/eval-in-frame div-zero 0 "(do (println :hi) x)" {:frames-fn fake}))))
  (testing "a throw from the form"
    (is (= {:error "ArithmeticException: Divide by zero"} (debugger/eval-in-frame div-zero 0 "(/ x 0)" {:frames-fn fake}))))
  (testing "a form that doesn't read"
    (let [{:keys [error]} (debugger/eval-in-frame div-zero 0 "(+ x" {:frames-fn fake})]
      (is (some #(str/starts-with? (str error) %) ["RuntimeException" "ExceptionInfo" "EOFException"]) (pr-str error))))
  (testing "no such frame"
    (is (= {:error "That frame is gone."} (debugger/eval-in-frame div-zero 9 "x" {:frames-fn fake}))))
  (testing "locals that can't be parameters are left out, the rest still bind"
    (let [odd (fn [_] [{:fn 'demo.core/inner :file nil :line 1 :locals {'x 21 'if 1 '& 2 'a.b 3 'fn 4}}])]
      (is (= {:out "" :text "21"} (debugger/eval-in-frame div-zero 0 "x" {:frames-fn odd})))
      (is (= {:out "" :text "4"} (debugger/eval-in-frame div-zero 0 "fn" {:frames-fn odd})) "fn is a macro, a local can shadow it")))
  (testing "a loaded frame namespace resolves its own names"
    (let [here (fn [_] [{:fn 'jonquil.debugger-test/f :file nil :line 1 :locals {}}])]
      (is (= {:out "" :text "2"} (debugger/eval-in-frame div-zero 0 "(:line (first fake-frames))" {:frames-fn here}))
          "fake-frames is private to this namespace"))))

(deftest error-id-test
  ;; Jolt's identity hashes repeat in every fresh process, so the id carries
  ;; one made per process: a restarted child's first error never matches
  (let [id (debugger/error-id div-zero)]
    (is (= id (:error-id (debugger/frames-data div-zero {:frames-fn fake}))))
    (is (= id (:error-id (debugger/frames-data div-zero {:frames-fn nil}))))
    (is (str/starts-with? id (str debugger/RUNTIME-ID ":")))
    (is (= 36 (count debugger/RUNTIME-ID)))
    (is (false? (debugger/stale? div-zero id)))
    (is (false? (debugger/stale? div-zero nil)))
    (is (true? (debugger/stale? div-zero (str "another-process:" (hash div-zero)))))))

(deftest eval-in-frame-every-form-test
  (is (= {:out "" :text "42"} (debugger/eval-in-frame div-zero 0 "x y" {:frames-fn fake})) "the last form's value")
  (is (= {:out "1\n" :text "63"} (debugger/eval-in-frame div-zero 0 "(println 1) (+ x y)" {:frames-fn fake}))))

(deftest eval-in-frame-lazy-result-output-test
  (is (= {:out ":side 0\n:side 1\n" :text "(0 1)"}
         (debugger/eval-in-frame div-zero 0 "(map (fn [i] (println :side i) i) (range 2))" {:frames-fn fake}))
      "realizing the value prints into the captured output, not the process's"))

(deftest eval-in-frame-output-cap-test
  (let [{:keys [out text]} (debugger/eval-in-frame div-zero 0 "(dotimes [_ 5000] (print \"abcdefghij\"))" {:frames-fn fake})]
    (is (= "nil" text))
    (is (str/ends-with? out "\n; output cut at 10000 characters\n") (subs out (max 0 (- (count out) 60))))
    (is (< (count out) 10100))))

(deftest eval-in-frame-stops-test
  (let [t (debugger/make-token)
        f (future (debugger/eval-in-frame div-zero 0 "(loop [] (recur))" {:frames-fn fake :token t}))]
    (Thread/sleep 300)
    (debugger/interrupt-token! t)
    (is (= {:error "Stopped."} (deref f 5000 :timeout)))))

;; --- the break (Break on error) ---------------------------------------------

(defn- fake-break
  "jolt.debug/call-with-break-handler's contract over fake-frames: the
  handler runs on the throwing thread, then the throw goes on."
  [handler thunk]
  (try (thunk) (catch Throwable t (handler t fake-frames) (throw t))))

(defn- start-break
  "thunk on a future under a break; {:brk :fut :paused :resumed :thread}."
  [thunk]
  (let [brk (debugger/make-break)
        paused (promise)
        resumed (atom 0)
        thread (promise)
        wrap (debugger/break-wrapper brk {:on-pause (fn [d] (deliver paused d))
                                          :on-resume (fn [] (swap! resumed inc))})
        fut (future (binding [debugger/*break-fn* fake-break]
                      (deliver thread (Thread/currentThread))
                      (try (wrap thunk) (catch Throwable t t))))]
    {:brk brk :fut fut :paused paused :resumed resumed :thread thread}))

(defn- ask [brk i code]
  (let [p (promise)]
    (debugger/break-eval! brk i code (fn [r] (deliver p r)))
    (deref p 3000 :no-reply)))

(deftest break-pauses-evaluates-and-continues-test
  (let [boom (ex-info "boom" {})
        {:keys [brk fut paused resumed thread]} (start-break (fn [] (throw boom)))
        d (deref paused 3000 nil)]
    (is (= "ExceptionInfo: boom {}" (:title d)))
    (is (= [{:name "x" :text "21"} {:name "y" :text "42"}] (:locals (first (:frames d)))))
    (is (= {:out "" :text "63"} (ask brk 0 "(+ x y)")))
    (testing "the form runs on the paused thread"
      (is (= (pr-str (.getName ^Thread @thread)) (:text (ask brk 0 "(.getName (Thread/currentThread))")))))
    (testing "a form that throws is answered and the pause holds"
      (is (= "ArithmeticException: Divide by zero" (:error (ask brk 0 "(/ x 0)"))))
      (is (= {:out "" :text "21"} (ask brk 0 "x"))))
    (is (= 21 (debugger/paused-local brk (:error-id d) 0 "x")))
    (is (= ::debugger/missing (debugger/paused-local brk "other" 0 "x")))
    (is (true? (debugger/continue! brk)))
    (is (identical? boom (deref fut 3000 :blocked)))
    (is (= 1 @resumed))
    (is (false? (debugger/continue! brk)))
    (is (= {:error "Not paused any more."} (ask brk 0 "x")))
    (is (= ::debugger/missing (debugger/paused-local brk (:error-id d) 0 "x")))))

(deftest break-interrupt-ends-the-pause-test
  (let [{:keys [brk fut paused resumed thread]} (start-break (fn [] (/ 1 0)))]
    (is (some? (deref paused 3000 nil)))
    (.interrupt ^Thread @thread)
    (is (instance? InterruptedException (deref fut 3000 :blocked)))
    (is (= 1 @resumed))
    (is (false? (debugger/continue! brk)))))

(deftest break-no-throw-no-pause-test
  (let [{:keys [fut paused resumed]} (start-break (fn [] 5))]
    (is (= 5 (deref fut 3000 :blocked)))
    (is (not (realized? paused)))
    (is (= 0 @resumed))))
