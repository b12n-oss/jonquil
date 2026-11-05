(ns jonquil.session-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jonquil.debugger :as debugger]
            [jonquil.eval :as eval]
            [jonquil.evaluator :as ev]
            [jonquil.session :as session]
            [jonquil.source :as source]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(defn- run
  ([f s code] (run f s code {}))
  ([f s code opts]
   (let [done (promise)]
     (f s code (assoc opts :on-complete (fn [r] (deliver done r))))
     (deref done 5000 :timeout))))

(defn- run-sync [s code] (run session/run-definitions! s code))
(defn- repl-sync [s code] (run session/submit-repl! s code))

(defn- inspect-sync [s op args]
  (let [p (promise)]
    (ev/inspect! s op args (fn [r] (deliver p r)))
    (deref p 2000 :timeout)))

(deftest namespaces-created-test
  (is (= #{'b} (session/namespaces-created ['a] ['a 'b 'lib] ['lib])))
  (is (= #{} (session/namespaces-created ['a] ['a] []))))

(deftest reset-mode-test
  (let [s (session/make-session)]
    (is (= :hi (:value (run session/run-definitions! s "(defn greet [] :hi) (greet)"))))
    (testing "a namespace the program created, and one a REPL entry created"
      (run session/run-definitions! s "(ns scratch.p2a) (def q 1)")
      (run session/submit-repl! s "(ns scratch.p2r) (def y 1)")
      (is (some? (find-ns 'scratch.p2a)))
      (is (some? (find-ns 'scratch.p2r))))
    (testing "Run resets: user's greet and both created namespaces are gone"
      (let [r (run session/run-definitions! s "(greet)")]
        (is (= :error (:status r))))
      (is (nil? (find-ns 'scratch.p2a)))
      (is (nil? (find-ns 'scratch.p2r))))
    (testing "user exists again as soon as run-definitions! returns"
      (session/run-definitions! s "(+ 1 2)")
      (is (some? (find-ns 'user))))))

(deftest library-survives-reset-test
  (let [s (session/make-session)]
    (run session/run-definitions! s "(require 'clojure.data) :loaded")
    (let [r (run session/run-definitions! s "(clojure.data/diff [1 2] [1 3])")]
      (is (= :ok (:status r)))
      (is (= "[[nil 2] [nil 3] [1]]" (:printed r))))))

(deftest accumulate-mode-test
  (let [s (session/make-session)]
    (session/set-mode! s :accumulate)
    (run session/run-definitions! s "(defn greet [] :hi)")
    (is (= :hi (:value (run session/run-definitions! s "(greet)"))))))

(deftest repl-follows-run-test
  (let [s (session/make-session)]
    (run session/run-definitions! s "(def x 41)")
    (is (= 42 (:value (run session/submit-repl! s "(inc x)"))))
    (is (= ["(inc x)"] (:items @(:history (:ctx s)))))
    (testing "the prompt follows an (ns ...) the Run ends in"
      (run session/run-definitions! s "(ns scratch.p2p) (def z 7)")
      (is (= 7 (:value (run session/submit-repl! s "z")))))))

(deftest run-while-running-resets-nothing-test
  (let [s (session/make-session)
        done (promise)]
    (session/run-definitions! s "(def k 1) (Thread/sleep 400) k"
                              {:on-complete (fn [r] (deliver done r))})
    (Thread/sleep 50)
    (is (string? (session/run-definitions! s "(+ 1 2)")))
    (testing "the refused Run did not remove user under the running program"
      (let [r (deref done 3000 :timeout)]
        (is (= :ok (:status r)))
        (is (= 1 (:value r)))))))

(deftest owner-reset-keeps-other-owners-test
  (let [s (session/make-session)]
    (run session/run-definitions! s "(ns tabs.a) (defn hello [] :hi)" {:owner 1})
    (run session/run-definitions! s "(ns tabs.b) (def x 1)" {:owner 2})
    (is (= #{'tabs.a} (get (:created @(:state s)) 1)))
    (is (= #{'tabs.b} (get (:created @(:state s)) 2)))
    (testing "a reset Run for owner 2 leaves owner 1's namespace"
      (run session/run-definitions! s "(ns tabs.b) (def x 2)" {:owner 2})
      (is (some? (find-ns 'tabs.a)))
      (is (= :hi (:value (run session/submit-repl! s "(tabs.a/hello)")))))
    (testing "owner 1's own Run removes and recreates tabs.a"
      (run session/run-definitions! s "(ns tabs.a) (def only 1)" {:owner 1})
      (is (nil? (ns-resolve 'tabs.a 'hello))))))

(deftest owner-repl-namespaces-go-with-any-run-test
  (let [s (session/make-session)]
    (run session/submit-repl! s "(create-ns 'tabs.scratch)")
    (is (= #{'tabs.scratch} (get (:created @(:state s)) :repl)))
    (run session/run-definitions! s "(+ 1 2)" {:owner 7})
    (is (nil? (find-ns 'tabs.scratch)))))

(deftest owner-run-while-running-test
  (let [s (session/make-session)
        done (promise)]
    (run session/run-definitions! s "(ns tabs.keep) (def v 1)" {:owner 1})
    (session/run-definitions! s "(Thread/sleep 400) :done"
                              {:owner 2 :on-complete (fn [r] (deliver done r))})
    (Thread/sleep 50)
    (is (string? (session/run-definitions! s "(+ 1 2)" {:owner 1})))
    (deref done 3000 :timeout)
    (is (some? (find-ns 'tabs.keep)) "the refused Run removed nothing")))

(deftest release-owner-test
  ;; a closed tab's namespaces would otherwise be
  ;; owned by nobody that ever Runs again, so a reopened file kept stale defs.
  (let [s (session/make-session)]
    (run session/run-definitions! s "(ns orphan.b) (defn old-fn [] 1)" {:owner 2})
    (ev/release-owner! s 2)
    (is (= #{'orphan.b} (get (:created @(:state s)) :repl)))
    (is (nil? (get (:created @(:state s)) 2)))
    (run session/run-definitions! s "(ns orphan.b) (def x 1)" {:owner 3})
    (is (nil? (ns-resolve 'orphan.b 'old-fn)))))

(deftest last-value-test
  (let [s (session/make-session)]
    (run-sync s "{:a [1 2]}")
    (is (= "PersistentArrayMap · 1 items" (:info (inspect-sync s :start {:rows 10 :cols 40}))))
    (repl-sync s "(throw (ex-info \"no\" {}))")
    (is (= "PersistentArrayMap · 1 items" (:info (inspect-sync s :start {:rows 10 :cols 40})))
        "an error leaves the last value")
    (repl-sync s "[7 8 9]")
    (is (= "*1 / 1" (:path (do (inspect-sync s :start {:rows 10 :cols 40})
                               (inspect-sync s :in {:idx 1})))))))

(deftest inspect-def-test
  (let [s (session/make-session)]
    (repl-sync s "(ns scratch.inspect)")
    (repl-sync s "{:k 42}")
    (inspect-sync s :start {:rows 10 :cols 40})
    (inspect-sync s :in {:idx 0})
    (is (= "#'scratch.inspect/answer" (:defd (inspect-sync s :def {:name "answer"}))))
    (is (= 42 @(ns-resolve 'scratch.inspect 'answer)))))

(deftest inspect-throw-test
  ;; a value whose datafy throws answers with a :note, and
  ;; on-view still fires exactly once.
  (let [s (session/make-session) calls (atom 0) p (promise)]
    (repl-sync s "(reify clojure.core.protocols/Datafiable (datafy [_] (throw (ex-info \"boom\" {}))))")
    (ev/inspect! s :start {:rows 10 :cols 40} (fn [r] (swap! calls inc) (deliver p r)))
    (is (= "boom" (:note (deref p 2000 :timeout))))
    (Thread/sleep 100)
    (is (= 1 @calls))))

(deftest inspect-outer-catch-test
  ;; A def whose prompt namespace no longer exists fails before run-op: the
  ;; outer catch answers {:error ...} with a non-nil message, exactly once.
  (let [s (session/make-session)]
    (swap! (:state s) assoc :prompt-ns 'jonquil.no-such-ns-for-inspect)
    (let [r (inspect-sync s :def {:name "x"})]
      (is (string? (:error r))))))

(defn- saved! [rel text]
  (let [f (io/file (temp-dirs/temp-dir! "jonquil-runfile-") rel)]
    (.mkdirs (.getParentFile f))
    (spit f text)
    (.getCanonicalPath f)))

(defn- var-meta [sym] (select-keys (meta (resolve sym)) [:file :line]))

(deftest a-run-of-a-saved-file-gives-its-vars-the-file-test
  ;; Before, a Run's vars had a line and no file (load-string), so F12 from
  ;; another tab showed the docs instead of jumping.
  (let [text "(ns runfile.a)\n\n(defn helper\n  \"Helps.\"\n  [x]\n  (inc x))\n\n(def answer 42)\n"
        path (saved! "runfile/a.clj" text)
        s (session/make-session)]
    (try
      (is (= :ok (:status (run session/run-definitions! s text {:path path}))))
      (is (= {:file path :line 3} (var-meta 'runfile.a/helper)))
      (is (= {:file path :line 8} (var-meta 'runfile.a/answer)))
      (testing "a lookup from another tab's text finds the file, and the source panel can open it"
        (let [d (ev/lookup s "runfile.a/helper" "(runfile.a/helper 1)" 3)]
          (is (= path (:file d)))
          (is (= {:path path :line 3} (select-keys (source/location d {}) [:path :line])))))
      (finally (remove-ns 'runfile.a)))))

(deftest a-run-of-a-saved-file-is-evaluated-as-that-file-test
  ;; on a Jolt whose eval-string takes a source path (jolt-lang/jolt#1476)
  ;; the Run passes it, as a load of the file would: *file* is the path
  (if (eval/source-path?)
    (let [text "(ns runfile.c)\n\n(def seen *file*)\n"
          path (saved! "runfile/c.clj" text)
          s (session/make-session)]
      (try
        (is (= :ok (:status (run session/run-definitions! s text {:path path}))))
        (is (= path @(resolve 'runfile.c/seen)))
        (is (= {:file path :line 3} (var-meta 'runfile.c/seen)))
        (finally (remove-ns 'runfile.c))))
    (println "SKIP a-run-of-a-saved-file-is-evaluated-as-that-file-test: this Jolt's eval-string takes no source path")))

(deftest no-saved-file-no-file-test
  (let [s (session/make-session)]
    (try
      (testing "an untitled buffer"
        (run session/run-definitions! s "(ns runfile.b) (defn f [] 1)")
        (is (nil? (:file (var-meta 'runfile.b/f)))))
      (testing "a path that isn't on disk (never saved)"
        (run session/run-definitions! s "(ns runfile.b) (defn f [] 1)" {:path "/no/such/dir/b.clj"})
        (is (nil? (:file (var-meta 'runfile.b/f)))))
      (finally (remove-ns 'runfile.b)))))

(deftest unsaved-edits-are-not-the-file-test
  ;; Run sends the buffer's text; when that isn't what's on disk the vars'
  ;; lines would point into the wrong text there.
  (let [path (saved! "runfile/d.clj" "(ns runfile.d)\n(defn f [] 1)\n")
        s (session/make-session)]
    (try
      (run session/run-definitions! s "(ns runfile.d)\n\n(defn f [] 1)\n" {:path path})
      (is (= {:line 3} (var-meta 'runfile.d/f)) "no file: the buffer has an edit the file doesn't")
      (run session/run-definitions! s "(ns runfile.d)\n(defn f [] 1)\n" {:path path})
      (is (= {:file path :line 2} (var-meta 'runfile.d/f)) "saved again, marked")
      (finally (remove-ns 'runfile.d)))))

(deftest only-what-the-run-defined-is-marked-test
  (let [path (saved! "runfile/c.clj" "(ns runfile.c)\n(defn from-run [] 2)\n")
        s (session/make-session)]
    (session/set-mode! s :accumulate)
    (try
      (run session/submit-repl! s "(ns runfile.c) (defn from-repl [] 1)")
      (run session/run-definitions! s "(ns runfile.c)\n(defn from-run [] 2)\n" {:path path})
      (is (= {:file path :line 2} (var-meta 'runfile.c/from-run)))
      (is (nil? (:file (var-meta 'runfile.c/from-repl))) "a REPL entry's var isn't the file's")
      (testing "a second Run redefines, and the var is marked again at its new line"
        (spit path "(ns runfile.c)\n\n\n(defn from-run [] 3)\n")
        (run session/run-definitions! s "(ns runfile.c)\n\n\n(defn from-run [] 3)\n" {:path path})
        (is (= {:file path :line 4} (var-meta 'runfile.c/from-run))))
      (finally (remove-ns 'runfile.c)))))

(deftest quiet-eval-keeps-history-test
  ;; a :quiet eval leaves the REPL as it was
  (let [s (session/make-session)
        quiet (fn [code] (run ev/eval! s code {:quiet "T"}))]
    (doseq [c ["1" "2" "3"]] (repl-sync s c))
    (repl-sync s "(throw (ex-info \"e1\" {}))")
    (let [prompt (ev/prompt-ns s)
          items (:items @(ev/history s))
          r (quiet "(+ 40 2)")]
      (is (= :ok (:status r)))
      (is (= "42" (:printed r)))
      (is (= :error (:status (quiet "(throw (ex-info \"e2\" {}))"))))
      (is (= :ok (:status (quiet "(in-ns 'jonquil.quiet-moved)"))))
      (is (= items (:items @(ev/history s))) "no history entry")
      (is (= prompt (ev/prompt-ns s)) "the prompt namespace didn't move")
      (is (= "[3 2 1 \"e1\"]" (:printed (repl-sync s "[*1 *2 *3 (ex-message *e)]")))))))

(deftest quiet-eval-runs-in-the-buffer-namespace-test
  (let [s (session/make-session)]
    (repl-sync s "(ns jonquil.quiet-buf) (def here :buf)")
    (repl-sync s "(in-ns 'user)")
    (is (= ":buf" (:printed (run ev/eval! s "here" {:quiet "T" :ns-source "(ns jonquil.quiet-buf)\n(+ 1 2)"}))))
    (is (= :ok (:status (run ev/eval! s "(+ 1 2)" {:quiet "T" :ns-source "(ns no.such.quiet)"})))
        "a buffer namespace that doesn't exist yet falls back to the prompt's")
    (remove-ns 'jonquil.quiet-buf)))

(deftest quiet-eval-auto-require-reaches-the-console-test
  ;; a quiet eval that's the first in a project window still
  ;; tells the person about the auto-require, as Jonquil's own line
  (let [s (session/make-session)
        dir (str (temp-dirs/temp-dir! "jonquil-quiet-ar-"))
        out (atom [])]
    (swap! (:state s) assoc :project-dir dir :auto-require '[clojure.set] :auto-require-dir dir)
    (is (= :ok (:status (run ev/eval! s "1" {:quiet "T" :on-output (fn [k t] (swap! out conj [k t]))}))))
    (is (some (fn [[k t]] (and (= :system k) (re-find #"auto-require: clojure.set" t))) @out) (pr-str @out))
    (is (not-any? (fn [[k t]] (and (= :out k) (re-find #"auto-require" t))) @out))))

(defn- frames-sync [s]
  (let [p (promise)]
    (ev/error-frames! s (fn [d] (deliver p d)))
    (deref p 2000 :timeout)))

(deftest error-frames-test
  (let [s (session/make-session)]
    (testing "no error yet"
      (is (= {:note "No error yet."} (frames-sync s))))
    (testing "a REPL throw is the last error"
      (repl-sync s "(/ 1 0)")
      (is (= "ArithmeticException: Divide by zero" (:title (frames-sync s)))))
    (testing "a quiet throw leaves it"
      (run ev/eval! s "(throw (ex-info \"quiet\" {}))" {:quiet true})
      (is (= "ArithmeticException: Divide by zero" (:title (frames-sync s)))))))

(deftest debug-mode-test
  (let [s (session/make-session)
        toggle (fn [] (let [p (promise)] (ev/toggle-debug-mode! s (fn [r] (deliver p r))) (deref p 2000 :timeout)))]
    (if (debugger/available?)
      (try
        (is (= {:on? true} (toggle)))
        (is (= {:on? false} (toggle)) "a second toggle reads the runtime's mode and turns it off")
        (finally (debugger/set-debug-mode! false)))
      (do (is (= {:error "Frames need a Jolt with jolt.debug (newer than this one)."} (toggle)))
          ;; a Jolt with jolt.profile (some builds have none): line counting stays off
          (when-let [instrumenting? (try (requiring-resolve 'jolt.profile/instrumenting?) (catch Throwable _ nil))]
            (is (false? (instrumenting?)) "line counting stays off"))))))

(deftest start-local-test
  (let [s (session/make-session)]
    (repl-sync s "(/ 1 0)")
    (binding [debugger/*frames-fn* (fn [_] [{:fn 'f :file nil :line 1 :locals {'xs [1 2]}}])]
      (is (= 2 (:total (inspect-sync s :start-local {:frame 0 :local "xs" :rows 10 :cols 40}))))
      (is (= "xs" (:path (inspect-sync s :start-local {:frame 0 :local "xs" :label "xs" :rows 10 :cols 40})))
          "a local is labelled by its name")
      (is (= {:error "That local is gone."} (inspect-sync s :start-local {:frame 0 :local "zz" :rows 10 :cols 40}))))))

(defn- frame-eval-sync [s i code error-id]
  (let [p (promise)]
    (ev/eval-in-frame! s i code error-id (fn [r] (deliver p r)))
    (deref p 3000 :timeout)))

(deftest eval-in-frame-test
  (let [s (session/make-session)]
    (is (= {:error "No error yet."} (frame-eval-sync s 0 "1" nil)))
    (repl-sync s "(/ 1 0)")
    (binding [debugger/*frames-fn* (fn [_] [{:fn 'demo.core/inner :file nil :line 1 :locals {'x 21 'y 42}}])]
      (let [id (:error-id (frames-sync s))]
        (is (= {:out "" :text "63"} (frame-eval-sync s 0 "(+ x y)" id)))
        (is (= {:error "The panel shows an older error: show the frames again."} (frame-eval-sync s 0 "(+ x y)" (str id "x"))) "a stale panel is refused")
        (is (= {:error "The panel shows an older error: show the frames again."} (inspect-sync s :start-local {:frame 0 :local "x" :rows 10 :cols 40 :error-id (str id "x")})))
        (is (= {:error "ArithmeticException: Divide by zero"} (frame-eval-sync s 0 "(/ x 0)" id)))
        (is (= "ArithmeticException: Divide by zero" (:title (frames-sync s))) "the form's own throw isn't the last error")))))

(deftest eval-in-frame-stop-test
  (let [s (session/make-session)]
    (repl-sync s "(/ 1 0)")
    (binding [debugger/*frames-fn* (fn [_] [{:fn 'user/f :file nil :line 1 :locals {}}])]
      (let [p (promise)]
        (ev/eval-in-frame! s 0 "(loop [] (recur))" nil (fn [r] (deliver p r)))
        (Thread/sleep 300)
        (ev/interrupt! s)
        (is (= {:error "Stopped."} (deref p 5000 :timeout)) "Stop reaches a frame evaluation")))))

;; --- Break on error ----------------------------------------------------------

(defn- fake-break [handler thunk]
  (try (thunk) (catch Throwable t (handler t []) (throw t))))

(defn- toggle-break-sync [s]
  (let [p (promise)]
    (ev/toggle-break! s (fn [r] (deliver p r)))
    (deref p 2000 :timeout)))

(deftest break-on-error-test
  (with-redefs [debugger/*break-fn* fake-break]
    (let [s (session/make-session)
          paused (atom (promise))
          resumed (atom 0)]
      (ev/set-break-handlers! s {:on-pause (fn [d] (deliver @paused d))
                                 :on-resume (fn [_] (swap! resumed inc))})
      (testing "off by default"
        (is (= :error (:status (repl-sync s "(/ 1 0)"))))
        (is (not (realized? @paused))))
      (is (= {:on? true} (toggle-break-sync s)))
      (testing "an error pauses until Continue, then completes as the error"
        (let [done (promise)]
          (session/submit-repl! s "(/ 1 0)" {:on-complete (fn [r] (deliver done r))})
          (is (= "ArithmeticException: Divide by zero" (:title (deref @paused 3000 nil))))
          (is (not (realized? done)))
          (is (true? (ev/continue-break! s)))
          (is (= :error (:status (deref done 3000 :timeout))))
          (is (= 1 @resumed))))
      (testing "a quiet evaluation never pauses"
        (reset! paused (promise))
        (is (= :error (:status (run session/submit-repl! s "(/ 1 0)" {:quiet true}))))
        (is (not (realized? @paused))))
      (testing "nothing paused"
        (is (false? (ev/continue-break! s))))
      (is (= {:on? false} (toggle-break-sync s)))
      (testing "off again"
        (is (= :error (:status (repl-sync s "(/ 1 0)"))))
        (is (not (realized? @paused)))))))

;; --- trace (LM4) -------------------------------------------------------------

(defn- trace-sync [s op args]
  (let [p (promise)]
    (ev/trace-op! s op args (fn [r] (deliver p r)))
    (deref p 2000 :timeout)))

(deftest trace-in-process-test
  (let [s (session/make-session)]
    (try
      (repl-sync s "(defn ttf [x] (* 10 x))")
      (is (= {:traced "user/ttf"} (trace-sync s :trace {:sym "ttf" :ns "user"})))
      (is (= "20" (:printed (repl-sync s "(ttf 2)"))))
      (let [{:keys [entries]} (trace-sync s :log {:since -1})
            id (:id (first entries))]
        (is (= ["(user/ttf 2)"] (mapv :args entries)))
        (is (= ["20"] (mapv :ret entries)))
        (is (re-find #"\b20\b" (pr-str (inspect-sync s :start-trace {:id id :part :ret :rows 10 :cols 40})))))
      (is (= {:count 1} (trace-sync s :untrace-all {})))
      (finally (trace-sync s :untrace-all {}) (trace-sync s :clear {})))))

;; --- live results (LM1) ------------------------------------------------------

(deftest results-keep-a-ref-test
  (let [s (session/make-session)
        ok (repl-sync s "(+ 1 2)")
        err (repl-sync s "(/ 1 0)")
        quiet (run session/submit-repl! s "(+ 4 5)" {:quiet true})]
    (is (= :value (:kind (:ref ok))))
    (is (= :exception (:kind (:ref err))))
    (is (nil? (:ref quiet)) "a quiet evaluation keeps nothing")
    (repl-sync s "(+ 10 10)")
    (is (re-find #"\b3\b" (pr-str (inspect-sync s :start-result {:id (:id (:ref ok)) :epoch (:epoch (:ref ok)) :rows 10 :cols 40})))
        "the first result, by its ref, after later ones")
    (is (= "3" (:path (inspect-sync s :start-result {:id (:id (:ref ok)) :epoch (:epoch (:ref ok)) :label "3" :rows 10 :cols 40})))
        "a clicked result is labelled by its line, not *1")))

;; --- the break handlers hear each evaluation start and end -----------------------

(defn- handler-events
  "Break handlers that log [:start id] and [:end id completed?] to events,
  completed? being whether the evaluation's own on-complete ran first."
  [events completed]
  {:on-eval-start (fn [id] (swap! events conj [:start id]))
   :on-eval-end (fn [id] (swap! events conj [:end id @completed]))})

(deftest eval-start-and-end-handlers-test
  (let [s (session/make-session)
        events (atom [])
        completed (atom false)
        repl! (fn [code opts]
                (reset! completed false)
                (let [done (promise)]
                  (session/submit-repl! s code (assoc opts :on-complete (fn [r] (reset! completed true) (deliver done r))))
                  (let [r (deref done 5000 :timeout)]
                    ;; the end handler runs just after on-complete
                    (loop [n 0] (when (and (< n 100) (not= :end (first (peek @events)))) (Thread/sleep 20) (recur (inc n))))
                    r)))]
    (ev/set-break-handlers! s (handler-events events completed))
    (repl! "(+ 1 2)" {})
    (let [[[k1 id1] [k2 id2 done?]] @events]
      (is (= [:start :end] [k1 k2]))
      (is (and (some? id1) (identical? id1 id2)) "the same evaluation's id")
      (is (true? done?) "the end comes after the evaluation's own on-complete"))
    (let [first-id (second (first @events))]
      (reset! events [])
      (session/submit-repl! s "(+ 4 5)" {:quiet true :on-complete (fn [_])})
      (Thread/sleep 300)
      (is (= [] @events) "a quiet evaluation calls neither")
      (repl! "(+ 1 2)" {})
      (is (not (identical? first-id (second (first @events)))) "each evaluation has its own id"))
    (ev/set-break-handlers! s {:on-eval-start (fn [_] (throw (ex-info "boom" {})))
                               :on-eval-end (fn [_] (throw (ex-info "boom" {})))})
    (is (= "3" (:printed (repl! "(+ 1 2)" {}))) "throwing handlers don't stop the evaluation")))

(deftest line-counts-test
  (let [s (session/make-session)
        call (fn [f & args] (let [p (promise)] (apply f s (concat args [(fn [r] (deliver p r))])) (deref p 5000 :timeout)))
        mode (try ((requiring-resolve 'jolt.profile/instrument-mode)) (catch Throwable _ nil))
        f (io/file (temp-dirs/temp-dir! "jonquil-session-lc-") "lc.clj")]
    (if-not (requiring-resolve 'jonquil.line-counts/available?)
      (is false "jonquil.line-counts loads")
      (try
        (spit f "(ns jonquil.session-lc)\n(defn twice [] (dotimes [_ 2] (inc 1)))\n(twice)\n")
        (let [on (call ev/toggle-line-counts!)]
          (if (:error on)
            (is (= {:error "Line counts need a Jolt with jolt.profile."} on))
            (do (is (= {:on? true} on))
                (run-sync s (str "(load-file " (pr-str (.getPath f)) ")"))
                (is (= 1 (get-in (call ev/line-counts! (.getPath f)) [:counts 3])) "the call on line 3 ran once")
                (is (= {:ok true} (call ev/clear-line-counts!)))
                (is (= {:counts {}} (call ev/line-counts! (.getPath f))))
                (is (= {:on? false} (call ev/toggle-line-counts!))))))
        (finally (when mode ((requiring-resolve 'jolt.profile/instrument!) mode))
                 (when-not mode (try ((requiring-resolve 'jolt.profile/instrument!) false) (catch Throwable _ nil))))))))
