(ns jonquil.nrepl.evaluator-test
  "The nREPL evaluator against real Jolt children. Each test stops its own
  evaluator, which kills only the child that evaluator spawned."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jonquil.console :as console]
            [jonquil.dialect :as dialect]
            [jonquil.evaluator :as ev]
            [jonquil.inspect :as inspect]
            [jonquil.nrepl.child :as child]
            [jonquil.nrepl.child-test :refer [temp-project]]
            [jonquil.nrepl.client :as client]
            [jonquil.nrepl.evaluator :as nrepl]
            [jonquil.nrepl.middleware-files :as middleware-files]
            [jonquil.tool-path :as tool-path]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(defn- make [dir]
  (ev/make {:profile dialect/jolt :runtime :nrepl :project-dir dir}))

(defn- run
  "Evaluates and waits. [result output] where output is every on-output
  call as [kind text]."
  ([e code run?] (run e code run? nil))
  ([e code run? path]
   (let [done (promise) out (atom [])]
     (ev/eval! e code {:run? run? :path path
                       :on-output (fn [k s] (swap! out conj [k s]))
                       :on-complete (fn [r] (deliver done r))})
     [(deref done 20000 :timeout) @out])))

(defn- run-owned [e code owner]
  (let [done (promise) out (atom [])]
    (ev/eval! e code {:run? true :owner owner
                      :on-output (fn [k s] (swap! out conj [k s]))
                      :on-complete (fn [r] (deliver done r))})
    [(deref done 20000 :timeout) @out]))

(defn- wait-until [pred ms]
  (loop [n 0] (cond (pred) true (> (* n 20) ms) false :else (do (Thread/sleep 20) (recur (inc n))))))

(defn- wait-for-eval-id
  "Polls (:state e)'s [:running :id] every 10 ms, up to about 5000 ms,
  standing in for 'the eval request is out' instead of a fixed sleep: a
  guessed sleep could still fire before send-main! records :id (evaluator.
  clj's own send-main!/interrupt!), under load, sending the caller's next
  step (an interrupt or a kill signal) too early. Fails the test with a
  clear message on timeout rather than silently letting the next step run
  against a request that was never sent."
  [e]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop []
      (if-let [id (get-in @(:state e) [:running :id])]
        id
        (if (< (System/currentTimeMillis) deadline)
          (do (Thread/sleep 10) (recur))
          (do (is false "the eval request's :id never appeared in :running within 5000 ms")
              nil))))))

(defn- alive? [pid] (str/includes? (:out (shell/sh "ps" "-p" (str pid) "-o" "pid=")) (str pid)))

(deftest make-dispatches-on-runtime-test
  (let [e (make (temp-project))]
    (is (instance? jonquil.nrepl.evaluator.NRepl e))
    (is (nil? (nrepl/child-pid e)) "not started until asked")
    (is (= [] (ev/limitations e)) "the jump, stdin and inspector lines only when a live child lacks them")))

(deftest a-run-of-an-unclosed-form-gets-the-eof-hint-test
  ;; jonquil.gtk.window.run's Run hint keys on "EOF while reading" in the
  ;; result's text,
  ;; so this pins that a child's reader error still says so over nREPL.
  (let [e (make (temp-project))
        code "(defn f [x]\n  (+ x 1)"]
    (try
      (let [[r] (run e code true)]
        (is (= :error (:status r)))
        (is (str/includes? (console/error-text r) "EOF while reading") (pr-str r))
        (is (= "The ( on line 1 is never closed." (console/unclosed-bracket-message code))))
      (finally (ev/stop! e)))))

(deftest run-repl-and-help-test
  (let [dir (temp-project) e (make dir)]
    (try
      (is (nil? (ev/start! e)))
      (let [[r out] (run e "(ns demo.run)\n(defn greet \"Says hi.\" [n] (println \"hi\" n) :hi)\n(greet 1)" true (str dir "/src/demo/run.clj"))]
        ;; :ref is the live-results handle, checked in results-in-the-child-test
        (is (= {:status :ok :printed ":hi" :ns 'demo.run} (dissoc r :ref)))
        (is (= [[:out "hi 1\n"]] out) "output arrives once, when the evaluation finishes")
        (is (= 'demo.run (ev/prompt-ns e))))
      (is (= ":hi" (:printed (first (run e "(greet 2)" false)))) "the REPL follows the Run's namespace")
      (is (= ["(greet 2)"] (:items @(ev/history e))))
      (testing "an error is text, not a Throwable"
        (is (= {:status :error :error-text "Error: Divide by zero"}
               (dissoc (first (run e "(/ 1 0)" false)) :ns :ref))))
      (testing "help: the server's names merged with offline ones"
        (is (some #{"greet"} (map :symbol (ev/complete e {:prefix "gre" :text "(gre" :pos 4 :extra-context nil}))))
        (is (= "Says hi." (:doc (ev/lookup e "greet" "(greet 1)" 1))))
        (is (nil? (:file (ev/lookup e "greet" "(greet 1)" 1))) "the Run's text isn't on disk, so no file")
        (is (= :found (:status (ev/lookup e "if" "(if x y)" 1))) "a special form falls back to the offline doc"))
      (testing "busy"
        (let [done (promise)]
          (ev/eval! e "(Thread/sleep 500)" {:run? false :on-complete (fn [r] (deliver done r))})
          (is (= nrepl/busy-warning (ev/eval! e "1" {:run? false})))
          (is (ev/evaluating? e))
          (deref done 5000 :timeout)))
      (finally (ev/stop! e)))))

(deftest jump-to-definition-in-another-file-test
  ;; jonquil-info, Jonquil's own middleware: a var from a namespace file the
  ;; child loaded comes with that file's absolute path and the var's line.
  (let [dir (temp-project) e (make dir)
        util (io/file dir "src/demo/util.clj")]
    (.mkdirs (.getParentFile util))
    ;; unique per run: Jolt v0.8.23 caches compiled code by its text, and an
    ;; identical file compiled elsewhere reports that place's :file
    ;; (jolt-lang/jolt#1404)
    (spit util (str "(ns demo.util)\n\n(defn helper\n  \"Helps.\"\n  [x]\n  (inc x))\n;; " (System/nanoTime) "\n"))
    (try
      (is (nil? (ev/start! e)))
      (is (= :ok (:status (first (run e "(require 'demo.util)" false)))))
      (let [d (ev/lookup e "demo.util/helper" "(demo.util/helper 1)" 1)]
        (is (= "Helps." (:doc d)))
        (is (= (.getCanonicalPath util) (:file d)))
        (is (= 3 (:line d))))
      (testing "a var built into Jolt has no file to jump to"
        (let [d (ev/lookup e "map" "(map inc [1])" 1)]
          (is (= "clojure.core" (:ns d)))
          (is (nil? (:file d)))))
      (finally (ev/stop! e)))))

(deftest a-run-sends-its-file-path-only-for-the-saved-file-test
  ;; the child records a load-file's file-path as the vars' :file (Jolt
  ;; #1476 with a middleware that passes it on), so only a buffer that is
  ;; exactly its file on disk may send one: an unsaved buffer's vars must not
  ;; point at untitled.clj, nor a dirty buffer's at lines its file lacks
  (let [dir (temp-project) e (make dir)
        path (str dir "/src/demo/pf.clj")
        file-of (fn [sym] (:printed (first (run e (str "(:file (meta (var " sym ")))") false))))]
    (try
      (is (nil? (ev/start! e)))
      (is (= :ok (:status (first (run e "1" false)))))
      ;; whether this child records a path at all, asked with a raw load-file
      ;; that names one, so a Run that stops sending it fails instead of skipping
      (let [{:keys [conn session]} (#'nrepl/runtime e)
            _ (client/request-sync conn {:op "load-file" :session session
                                         :file "(ns demo.fp-probe) (def p 1)"
                                         :file-name "fp_probe.clj" :file-path "/fp/demo/fp_probe.clj"}
                                   10000)
            records? (= "\"/fp/demo/fp_probe.clj\"" (file-of "demo.fp-probe/p"))]
        (if-not records?
          (println "SKIP a-run-sends-its-file-path-only-for-the-saved-file-test: this child doesn't record a load-file's path")
          (do
            (testing "an unsaved buffer"
              (is (= :ok (:status (first (run e "(ns demo.pu)\n(defn g [] 1)\n" true nil)))))
              (is (= "nil" (file-of "demo.pu/g"))))
            (testing "a buffer with unsaved edits"
              (spit path "(ns demo.pf)\n(defn g [] 1)\n")
              (is (= :ok (:status (first (run e "(ns demo.pf)\n\n\n(defn g [] 2)\n" true path)))))
              (is (= "nil" (file-of "demo.pf/g"))))
            (testing "the saved file"
              (is (= :ok (:status (first (run e "(ns demo.pf)\n(defn g [] 1)\n" true path)))))
              (is (= (pr-str path) (file-of "demo.pf/g")))))))
      (finally (ev/stop! e)))))

(deftest reset-on-run-clears-the-child-test
  (let [dir (temp-project) e (make dir)]
    (try
      (run e "(def kept 1) (ns demo.a) (def in-a 2)" true)
      ;; The REPL follows the Run into demo.a, as it does in-process.
      (run e "(def repl-def 3)" false)
      (is (= "[1 3]" (:printed (first (run e "[user/kept demo.a/repl-def]" false)))))
      (run e "(+ 1 1)" true)
      (is (= "[nil nil]"
             (:printed (first (run e "[(resolve 'user/kept) (find-ns 'demo.a)]" false))))
          "user defs, and the Run's namespace with its REPL def, are gone")
      (testing ":accumulate keeps them"
        (ev/set-mode! e :accumulate)
        (run e "(def kept 1)" true)
        (run e "(+ 1 1)" true)
        (is (= "1" (:printed (first (run e "user/kept" false))))))
      (finally (ev/stop! e)))))

(deftest stop-interrupts-test
  (let [e (make (temp-project)) done (promise)]
    (try
      (ev/start! e)
      (let [pid (nrepl/child-pid e)]
        (ev/eval! e "(def before 1) (loop [] (recur))" {:run? false :on-complete (fn [r] (deliver done r))})
        (wait-for-eval-id e)
        (ev/interrupt! e)
        (is (= :interrupted (:status (deref done 5000 :timeout))))
        (is (= pid (nrepl/child-pid e)) "interrupt, not a restart")
        (is (= "1" (:printed (first (run e "before" false)))) "the session keeps its state"))
      (finally (ev/stop! e)))))

(deftest stop-while-unresponsive-restarts-test
  (let [e (make (temp-project)) done (promise) out (atom [])]
    (try
      (ev/start! e)
      (let [pid (nrepl/child-pid e)]
        (ev/eval! e "(loop [] (recur))" {:run? false
                                         :on-output (fn [k s] (swap! out conj [k s]))
                                         :on-complete (fn [r] (deliver done r))})
        (wait-for-eval-id e)
        (shell/sh "kill" "-STOP" (str pid))
        (ev/interrupt! e)
        (is (= :interrupted (:status (deref done 10000 :timeout))))
        (is (wait-until (fn [] (some? (nrepl/child-pid e))) 10000) "a new child")
        (is (not= pid (nrepl/child-pid e)))
        (is (not (alive? pid)) "the stopped child was killed")
        (is (wait-until (fn [] (some (fn [[_ s]] (str/includes? s "Stop restarted the Jolt runtime")) @out)) 5000))
        (is (= "3" (:printed (first (run e "(+ 1 2)" false)))) "the new child answers"))
      (finally (ev/stop! e)))))

(deftest child-dies-mid-eval-test
  (let [e (make (temp-project)) done (promise)]
    (try
      (ev/start! e)
      (let [pid (nrepl/child-pid e)]
        (ev/eval! e "(Thread/sleep 30000)" {:run? false :on-complete (fn [r] (deliver done r))})
        (wait-for-eval-id e)
        (shell/sh "kill" "-9" (str pid))
        (is (= {:status :error :error-text "The Jolt runtime exited before this evaluation finished."}
               (deref done 5000 :timeout)))
        (is (not (ev/evaluating? e)))
        (let [[r out] (run e "(+ 1 2)" false)]
          (is (= "3" (:printed r)) "the next evaluation starts a new child")
          (is (some (fn [[k s]] (and (= :system k) (str/includes? s "had exited"))) out))))
      (finally (ev/stop! e)))))

(deftest stop-kills-its-own-child-only-test
  (let [dir (temp-project)
        _ (spit (io/file dir ".nrepl-port") "4242\n")
        e (make dir)
        other (make (temp-project))]
    (ev/start! e)
    (ev/start! other)
    (let [pid (nrepl/child-pid e) other-pid (nrepl/child-pid other)]
      (ev/stop! e)
      (is (not (alive? pid)))
      (is (alive? other-pid) "another evaluator's child is untouched")
      (is (= "4242\n" (slurp (io/file dir ".nrepl-port"))))
      (is (nil? (nrepl/child-pid e)))
      (is (nil? (ev/stop! e)) "twice is fine")
      (ev/stop! other)
      (is (not (alive? other-pid))))))

(deftest no-middleware-means-stop-by-restart-test
  (with-redefs [child/middleware-source (constantly nil)]
    (let [e (make (temp-project)) done (promise)]
      (try
        (ev/start! e)
        (is (some #(str/includes? % "no interrupt op") (ev/limitations e)))
        (let [pid (nrepl/child-pid e)]
          (ev/eval! e "(loop [] (recur))" {:run? false :on-complete (fn [r] (deliver done r))})
          (wait-for-eval-id e)
          (ev/interrupt! e)
          (is (= :interrupted (:status (deref done 5000 :timeout))))
          (is (wait-until (fn [] (let [p (nrepl/child-pid e)] (and p (not= p pid)))) 10000) "restarted"))
        (finally (ev/stop! e))))))

(deftest unfetchable-git-middleware-falls-back-to-a-plain-server-test
  (with-redefs [child/middleware-source
                (constantly {:git {:git/url "file:///nonexistent/jonquil-nrepl.git"
                                   :git/tag "v0.4.2" :git/sha "927dc28"}})]
    (let [e (make (temp-project))]
      (try
        (is (nil? (ev/start! e)) "the retry without middleware starts")
        (is (some #(str/includes? % "couldn't be fetched") (ev/limitations e)))
        (is (some #(str/includes? % "Jolt said: git dep: could not list file:///nonexistent") (ev/limitations e))
            "jolt's own line, not a guess")
        (is (some #(str/includes? % "no interrupt op") (ev/limitations e)))
        (is (= "3" (:printed (first (run e "(+ 1 2)" false)))))
        (finally (ev/stop! e))))))

(def ^:private newer-jolt
  ;; a Jolt with jolt-lang/jolt #1460 when one is installed beside the
  ;; release (its load-file and a REPL eval without :ns don't switch), else nil
  (let [f (io/file (System/getProperty "user.home") ".local/bin/jolt-debug")]
    (when (.isFile f) (.getPath f))))

(deftest plain-server-prompt-follows-ns-test
  ;; the plain server (no jolt-lang/nrepl middleware) answers with Jolt's
  ;; built-in handler, which on #1460 neither switches on load-file nor keeps
  ;; a REPL ns across evals sent without :ns
  (doseq [program (cond-> [nil] newer-jolt (conj newer-jolt))]
    (with-redefs [child/middleware-source
                  (constantly {:git {:git/url "file:///nonexistent/jonquil-nrepl.git"
                                     :git/tag "v0.4.2" :git/sha "927dc28"}})
                  tool-path/resolve-program (let [real tool-path/resolve-program]
                                              (fn [n p ok?] (if (and program (= n "jolt")) program (real n p ok?))))]
      (let [e (make (temp-project))]
        (try
          (is (nil? (ev/start! e)) (str program))
          (run e "(ns demo.plainrun) (defn f [] 41)" true)
          (is (= 'demo.plainrun (:prompt-ns @(:state e))) (str "after Run, " program))
          (is (= "41" (:printed (first (run e "(f)" false)))) (str "a REPL entry runs in the Run's ns, " program))
          (run e "(ns demo.plainrepl)" false)
          (is (= "\"demo.plainrepl\"" (:printed (first (run e "(str *ns*)" false))))
              (str "a REPL ns switch sticks, " program))
          (finally (ev/stop! e)))))))

(defn- fetch-failure
  "An ex-info shaped like child/start!'s for a child that exited, whose log
  holds jolt's git-dependency failure line."
  []
  (let [log (io/file (temp-dirs/temp-dir! "jonquil-nrepl-test-") "child.log")]
    (spit log "Unhandled exception: git dep: could not list https://x.invalid/n.git (git ls-remote exited 128 after 3 attempts: fatal: unable to access)\n  trace:\n")
    (ex-info "jolt nrepl-server exited: ..." {:log (.getPath log)})))

(deftest fetch-error-line-test
  (is (= "git dep: could not list https://x.invalid/n.git (git ls-remote exited 128 after 3 attempts: fatal: unable to access)"
         (nrepl/fetch-error-line (fetch-failure))))
  (is (nil? (nrepl/fetch-error-line (ex-info "no log" {}))))
  (let [log (io/file (temp-dirs/temp-dir! "jonquil-nrepl-test-") "child.log")]
    (spit log "jolt nrepl-server: bind() failed\n")
    (is (nil? (nrepl/fetch-error-line (ex-info "x" {:log (.getPath log)}))) "another failure isn't a fetch failure")))

(deftest only-a-fetch-failure-falls-back-test
  (let [sources (atom [])]
    (with-redefs [child/middleware-source (constantly {:git child/nrepl-git-coord})
                  child/start! (fn [_ source] (swap! sources conj source)
                                 (throw (ex-info "jolt nrepl-server didn't answer within 15000 ms" {})))]
      (let [e (make (temp-project))]
        (is (str/starts-with? (ev/start! e) "Couldn't start Jolt over nREPL: jolt nrepl-server didn't answer"))
        (is (= [{:git child/nrepl-git-coord}] (map #(dissoc % :jonquil) @sources))
            "a timeout isn't retried, as a plain server or without the inspector")
        (ev/stop! e)))))

(deftest a-failed-start-names-its-log-test
  (let [dir (temp-dirs/temp-dir! "jonquil-nrepl-test-")
        log (io/file dir "child.log")
        logs (atom [])]
    ;; Each attempt writes its own log, as child/start! does, so the
    ;; fallback's deleting an earlier one leaves the last to be named.
    (with-redefs [child/start! (fn [_ _]
                                 (let [f (io/file dir (str "child-" (count @logs) ".log"))]
                                   (spit f "Unhandled exception: boom\n")
                                   (swap! logs conj f)
                                   (throw (ex-info "jolt nrepl-server exited: boom" {:log (.getPath f)}))))]
      (let [e (make (temp-project))
            msg (ev/start! e)]
        (is (str/starts-with? msg "Couldn't start Jolt over nREPL: jolt nrepl-server exited: boom"))
        (is (str/ends-with? msg (str "\nThe child's log: " (.getPath (last @logs)))))
        (is (= [false true] (map #(.exists %) @logs))
            "the attempt with Jonquil's middleware fell back, and only the last log is left")
        (ev/stop! e)))
    (testing "no log line when the log is gone, or was never named"
      (with-redefs [child/start! (fn [_ _] (throw (ex-info "jolt nrepl-server exited: boom" {:log (str (.getPath log) ".gone")})))]
        (let [e (make (temp-project))]
          (is (not (str/includes? (ev/start! e) "child's log")))
          (ev/stop! e)))
      (with-redefs [child/start! (fn [_ _] (throw (ex-info "no free port in 7900-7999" {})))]
        (let [e (make (temp-project))]
          (is (= "Couldn't start Jolt over nREPL: no free port in 7900-7999" (ev/start! e)))
          (ev/stop! e))))))

(deftest a-fallback-deletes-the-earlier-attempts-log-test
  (let [first-e (fetch-failure)
        first-log (:log (ex-data first-e))
        last-log (io/file (temp-dirs/temp-dir! "jonquil-nrepl-test-") "child.log")
        calls (atom 0)]
    (spit last-log "still starting\n")
    (with-redefs [child/middleware-source (constantly {:git child/nrepl-git-coord})
                  child/start! (fn [_ _]
                                 (if (= 1 (swap! calls inc))
                                   (throw first-e)
                                   (throw (ex-info "jolt nrepl-server didn't answer within 15000 ms"
                                                   {:log (.getPath last-log)}))))]
      (let [e (make (temp-project))
            msg (ev/start! e)]
        (is (= 2 @calls) "the fetch failure fell back to a plain server")
        (is (not (.exists (io/file first-log))) "the fetch attempt's log is gone")
        (is (.exists last-log) "the last attempt's log is kept")
        (is (str/ends-with? msg (str "\nThe child's log: " (.getPath last-log))))
        (ev/stop! e)))))

(deftest no-fallback-after-stop-test
  (let [sources (atom []) e (make (temp-project))]
    (with-redefs [child/middleware-source (constantly {:git child/nrepl-git-coord})
                  child/start! (fn [_ source] (swap! sources conj source)
                                 (swap! (:state e) assoc :stopped? true)
                                 (throw (fetch-failure)))]
      (is (string? (ev/start! e)))
      (is (= 1 (count @sources)) "stop! during the fetch: no plain-server retry"))))

(deftest a-failed-fetch-is-remembered-test
  (let [sources (atom [])
        real-start child/start!]
    (with-redefs [child/middleware-source (constantly {:git child/nrepl-git-coord})
                  child/start! (fn [d source] (swap! sources conj (dissoc source :jonquil))
                                 (if (:git source) (throw (fetch-failure)) (real-start d source)))]
      (let [e (make (temp-project))]
        (try
          (is (nil? (ev/start! e)))
          (is (= [{:git child/nrepl-git-coord} {}] @sources) "then a plain server, with the inspector")
          (let [pid (nrepl/child-pid e) done (promise)]
            (ev/eval! e "(loop [] (recur))" {:run? false :on-complete (fn [r] (deliver done r))})
            (wait-for-eval-id e)
            (ev/interrupt! e)
            (is (= :interrupted (:status (deref done 5000 :timeout))))
            (is (wait-until (fn [] (let [p (nrepl/child-pid e)] (and p (not= p pid)))) 10000) "Stop restarted the plain server"))
          (is (= [{:git child/nrepl-git-coord} {} {}] @sources) "the restart skipped the fetch")
          (is (some #(str/includes? % "Jolt said: git dep:") (ev/limitations e)) "the line survives the restart")
          (finally (ev/stop! e)))))))

(defn- inspect [e op args]
  (let [p (promise)]
    (ev/inspect! e op args (fn [v] (deliver p v)))
    (deref p 10000 :timeout)))

(deftest inspector-over-nrepl-test
  (let [e (make (temp-project))]
    (try
      (is (ev/inspectable? e) "true before the child is live")
      (is (= {:error nrepl/inspect-not-running} (inspect e :start {:rows 10 :cols 60})))
      (ev/start! e)
      (is (ev/inspectable? e))
      (is (not-any? #(str/includes? % "inspector") (ev/limitations e)))
      (run e "(def data {:a [1 2 3] :rows [{:id 1} {:id 2}]})" false)
      (run e "data" false)
      (let [v (inspect e :start {:rows 10 :cols 60})]
        (is (= ["*1" 2] [(:path v) (:total v)])))
      (is (= "*1 / :rows" (:path (inspect e :in {:idx 1}))))
      (is (= "*1" (:path (inspect e :out {}))))
      (is (= "#'user/picked" (:defd (inspect e :def {:name "picked"}))) "into the prompt namespace")
      (is (= "true" (:printed (first (run e "(= *1 data picked)" false)))) "the ops left *1 alone")
      (finally (ev/stop! e)))))

(deftest a-plain-server-still-has-the-inspector-test
  (with-redefs [child/middleware-source (constantly nil)]
    (let [e (make (temp-project))]
      (try
        (ev/start! e)
        (is (ev/inspectable? e))
        (run e "[:x :y]" false)
        (is (= 2 (:total (inspect e :start {:rows 10 :cols 40}))))
        (finally (ev/stop! e))))))

(deftest an-inspector-that-wont-load-still-starts-jolt-test
  (let [broken (io/file (temp-dirs/temp-dir! "jonquil-nrepl-test-") "ins")
        sources (atom [])
        real-start child/start!]
    (.mkdirs (io/file broken "src/jonquil/nrepl"))
    (spit (io/file broken "deps.edn") "{:paths [\"src\"]}")
    ;; the files loaded before the inspector (stdin's, Break on error's, the
    ;; debugger it requires and the debugger's instrument lock) are the real
    ;; ones, so the failure jolt reports is the inspector's planted throw.
    (doseq [f ["jonquil/nrepl/stdin_middleware.clj" "jonquil/nrepl/break_middleware.clj" "jonquil/debugger.clj"
               "jonquil/instrument_lock.clj"]]
      (spit (io/file broken "src" f) (get middleware-files/sources f)))
    (spit (io/file broken "src/jonquil/nrepl/inspect_middleware.clj")
          "(ns jonquil.nrepl.inspect-middleware) (throw (ex-info \"broken on purpose\" {}))")
    (with-redefs [child/jonquil-root (constantly (.getCanonicalPath broken))
                  child/start! (fn [d source] (swap! sources conj (some? (:jonquil source))) (real-start d source))]
      (let [e (make (temp-project))]
        (try
          (is (nil? (ev/start! e)) "Jolt starts without the inspector")
          (is (= [true false] @sources))
          (is (= "3" (:printed (first (run e "(+ 1 2)" false)))))
          (is (not (ev/inspectable? e)))
          (is (some #(and (str/includes? % "couldn't load Jonquil's nREPL middleware")
                          (str/includes? % "broken on purpose"))
                    (ev/limitations e))
              "names jolt's own error")
          (finally (ev/stop! e)))))))

(deftest an-unwritable-inspector-folder-is-named-test
  (with-redefs [child/jonquil-root (fn [] (throw (ex-info "couldn't write /nope/nrepl-middleware/x" {})))]
    (let [e (make (temp-project))]
      (try
        (is (nil? (ev/start! e)))
        (is (some #(str/includes? % "couldn't write Jonquil's nREPL middleware files: couldn't write /nope") (ev/limitations e)))
        (finally (ev/stop! e))))))

(deftest a-dead-child-is-lost-test
  (let [e (make (temp-project))]
    (try
      (ev/start! e)
      (run e "[1 2]" false)
      (is (= 2 (:total (inspect e :start {:rows 10 :cols 40}))))
      (shell/sh "kill" "-9" (str (nrepl/child-pid e)))
      (is (wait-until (fn [] (not (child/alive? (:child (:runtime @(:state e)))))) 5000))
      (is (= {:error nrepl/inspect-lost :lost? true} (inspect e :in {:idx 0})))
      (finally (ev/stop! e)))))

(deftest no-inspector-files-means-unavailable-test
  (with-redefs [child/jonquil-root (constantly nil)]
    (let [e (make (temp-project))]
      (try
        (ev/start! e)
        (is (not (ev/inspectable? e)))
        (is (= {:error inspect/unavailable-message} (inspect e :start {:rows 10 :cols 40})))
        (is (some #(str/includes? % "No inspector") (ev/limitations e)))
        (finally (ev/stop! e))))))

(deftest limitations-with-the-full-middleware-test
  (let [e (make (temp-project))]
    (try
      (ev/start! e)
      (is (= [] (ev/limitations e))
          "streaming, stdin, the inspector and jump to definition all work")
      (finally (ev/stop! e)))))

(defn- eval-with-input
  "Evaluates code and, each time it asks for input, answers with the next
  of answers (a string for send-input!, :eof for send-eof!). [result
  wait-calls], wait-calls being every on-wait-input argument in order."
  [e code answers]
  (let [done (promise) waits (atom []) answers (atom answers)]
    (ev/eval! e code {:run? false :on-output (fn [_ _])
                      :on-wait-input (fn [w] (swap! waits conj w)
                                       (when w
                                         (let [[a & more] @answers]
                                           (reset! answers more)
                                           (future (case a
                                                     :eof (ev/send-eof! e)
                                                     ;; answer once the ticks are done and the wait survived them
                                                     :after-ticks (do (Thread/sleep 800)
                                                                      (when (ev/waiting-for-input? e) (ev/send-input! e "late")))
                                                     (ev/send-input! e a))))))
                      :on-complete (fn [r] (deliver done r))})
    [(deref done 10000 :timeout) @waits]))

(deftest stdin-over-nrepl-test
  (let [e (make (temp-project))]
    (try
      (ev/start! e)
      (testing "read-line waits, then gets the typed line"
        (let [[r waits] (eval-with-input e "(read-line)" ["hello"])]
          (is (= "\"hello\"" (:printed r)))
          (is (= [true false] waits))))
      (testing "two reads ask twice"
        (is (= "[\"a\" \"b\"]" (:printed (first (eval-with-input e "[(read-line) (read-line)]" ["a" "b"]))))))
      (testing "end of input"
        (is (= "nil" (:printed (first (eval-with-input e "(read-line)" [:eof])))))
        (is (= ":the-end" (:printed (first (eval-with-input e "(read {:eof :the-end} *in*)" [:eof]))))))
      (is (not (ev/waiting-for-input? e)) "nothing waits once it finished")
      (testing "output from another thread doesn't end the wait"
        (let [[r waits] (eval-with-input e "(future (dotimes [_ 6] (println \"tick\") (Thread/sleep 100))) (Thread/sleep 50) (read-line)"
                                         [:after-ticks])]
          (is (= [true false] waits) "one wait, ended by the answer, not by the ticks")
          (is (= "\"late\"" (:printed r)))))
      (testing "Stop frees a waiting read"
        (let [done (promise)]
          (ev/eval! e "(read-line)" {:run? false :on-complete (fn [r] (deliver done r))})
          (is (wait-until #(ev/waiting-for-input? e) 5000))
          (ev/interrupt! e)
          (is (= :interrupted (:status (deref done 5000 :timeout))))
          (is (not (ev/waiting-for-input? e)))
          (is (= "3" (:printed (first (run e "(+ 1 2)" false)))) "the session answers again")))
      (finally (ev/stop! e)))))

(deftest a-plain-server-has-no-stdin-test
  (with-redefs [child/middleware-source (constantly nil)]
    (let [e (make (temp-project))]
      (try
        (ev/start! e)
        (is (some #(str/includes? % "No stdin") (ev/limitations e)))
        (let [[r out] (run e "(read-line)" false)]
          (is (= "nil" (:printed r)) "returns nil at once, never waiting")
          (is (some #(str/includes? (second %) "no stdin") out) "the console says so"))
        (finally (ev/stop! e))))))

(deftest stop-during-start-leaves-no-child-test
  (let [dir (temp-project) e (make dir)
        release (promise)
        real-start child/start!
        captured (atom nil)]
    (try
      (with-redefs [child/start! (fn [d root]
                                   ;; Blocks the spawn step so the test can call
                                   ;; stop! while start-runtime! is still inside it.
                                   (deref release 5000 nil)
                                   (let [c (real-start d root)]
                                     (reset! captured c)
                                     c))]
        (let [start-fut (future (ev/start! e))]
          (Thread/sleep 300) ;; start-runtime! is now blocked in child/start!, holding start-lock
          (let [stop-fut (future (ev/stop! e))]
            (Thread/sleep 200) ;; stop! has set its stopping flag and is now blocked on start-lock
            (deliver release true)
            @start-fut
            @stop-fut))
        (is (wait-until (fn [] (some? @captured)) 5000) "a child was spawned")
        (is (wait-until (fn [] (not (child/alive? @captured))) 3000) "and it's dead, not orphaned")
        (is (nil? (nrepl/child-pid e))))
      (finally
        (when (and @captured (child/alive? @captured)) (child/kill! @captured))))))

(deftest stop-during-cold-start-returns-fast-test
  (let [dir (temp-project) e (make dir)
        real-start child/start!
        captured (atom nil)]
    (try
      (with-redefs [child/start! (fn [d root]
                                   ;; A cold start (child/start! itself, not
                                   ;; just the connect/clone after it) takes
                                   ;; 5s here, well past start-lock's own
                                   ;; ready-deadline-ms window in spirit --
                                   ;; long enough that a blocking stop! would
                                   ;; freeze the GTK thread for real.
                                   (Thread/sleep 5000)
                                   (let [c (real-start d root)]
                                     (reset! captured c)
                                     c))]
        (let [start-fut (future (ev/start! e))]
          (Thread/sleep 500) ;; start-runtime! is now blocked in child/start!, holding start-lock
          (let [t0 (System/currentTimeMillis)]
            (ev/stop! e)
            (let [elapsed (- (System/currentTimeMillis) t0)]
              (is (< elapsed 200) (str "stop! during a cold start took " elapsed " ms"))))
          ;; Eventually-waits, so generous: the redef sleeps 5 s and a Jolt
          ;; run from source takes about 6 s more to start (measured
          ;; 2026-10-19), past the 8 s these used to allow.
          (is (wait-until (fn [] (some? @captured)) 30000) "the delayed start eventually spawned a child")
          (is (wait-until (fn [] (not (child/alive? @captured))) 30000) "and it's dead, not orphaned")
          (is (nil? (nrepl/child-pid e)))
          @start-fut))
      (finally
        (when (and @captured (child/alive? @captured)) (child/kill! @captured))))))

(deftest no-spawn-after-stop-test
  ;; start-runtime! must check :stopped? before
  ;; spawning: otherwise a queued eval! (or a direct start!) reached after stop!
  ;; still spawned a doomed child. Counts real spawns with a redef and
  ;; asserts eval! after stop! never triggers one, and completes instead of
  ;; hanging.
  (let [e (make (temp-project))
        real-start child/start!
        spawn-count (atom 0)]
    (with-redefs [child/start! (fn [d root] (swap! spawn-count inc) (real-start d root))]
      (ev/stop! e)
      (let [[r _] (run e "(+ 1 2)" false)]
        (is (not= :timeout r) "the eval completed, it didn't hang")
        (is (contains? #{:error :interrupted} (:status r)) (str "no successful eval after stop!: " r)))
      (is (zero? @spawn-count) "start-runtime! spawned nothing once :stopped? was set"))))

(deftest double-stop-restarts-once-test
  (let [e (make (temp-project)) done (promise) out (atom [])
        complete-count (atom 0)]
    (try
      (ev/start! e)
      (let [pid (nrepl/child-pid e)]
        (ev/eval! e "(loop [] (recur))" {:run? false
                                         :on-output (fn [k s] (swap! out conj [k s]))
                                         :on-complete (fn [r] (swap! complete-count inc) (deliver done r))})
        (wait-for-eval-id e)
        (shell/sh "kill" "-STOP" (str pid))
        (ev/interrupt! e)
        (ev/interrupt! e)
        (ev/interrupt! e)
        (is (= :interrupted (:status (deref done 10000 :timeout))))
        (is (wait-until (fn [] (some? (nrepl/child-pid e))) 10000) "a new child")
        (Thread/sleep 300)
        (is (= 1 (count (filter (fn [[_ s]] (str/includes? s "Stop restarted the Jolt runtime")) @out)))
            "exactly one restart line for three presses")
        (is (= 1 @complete-count) "on-complete fires once"))
      (finally (ev/stop! e)))))

(deftest stop-racing-finish-never-sticks-test
  ;; interrupt! must not read :running and write
  ;; it back in a separate step: a finish! landing in between would recreate
  ;; :running out of nil, and eval! would then refuse every later Run as busy
  ;; for good. A presser future hammers interrupt! against no
  ;; child (so :running never gets an :id) while this thread repeatedly
  ;; sets and clears :running, the shape finish! uses. This loop found 921
  ;; stuck states in 200000 cycles on the pre-fix code.
  (let [e (make (temp-project))
        state (:state e)
        stop? (atom false)
        presser (future (loop [] (when-not @stop? (ev/interrupt! e) (recur))))]
    (dotimes [_ 20000]
      (swap! state assoc :running {:opts {}})
      (swap! state assoc :running nil))
    (reset! stop? true)
    @presser
    (is (nil? (:running @state)) "finish!'s clear always wins; interrupt! never recreates :running")
    (is (not (ev/evaluating? e)))
    (try
      (is (not= nrepl/busy-warning (ev/eval! e "1" {:run? false})) "the next eval! isn't refused as busy")
      (finally (ev/stop! e)))))

(deftest stop-before-send-is-honoured-test
  ;; a Stop landing after run-worker!'s own check
  ;; but before send-main! records the request's :id must not be lost:
  ;; interrupt! has nothing to act on yet, and nothing looked at the flag
  ;; again once the id landed. Redefines client/request! to call
  ;; interrupt! right after the real send goes out (send! writes and
  ;; flushes synchronously inside request!, so the write can't be undone)
  ;; but before send-main! records the id, exactly that gap. Only
  ;; the eval/load-file send is intercepted, not the interrupt op the fix
  ;; itself goes on to send.
  (let [dir (temp-project) e (make dir)
        real-request client/request!]
    (try
      (ev/start! e)
      (let [done (promise) out (atom [])]
        (with-redefs [client/request! (fn [conn msg on-msg]
                                        (let [id (real-request conn msg on-msg)]
                                          (when (contains? #{"eval" "load-file"} (:op msg))
                                            (ev/interrupt! e))
                                          id))]
          (ev/eval! e "(loop [] (recur))" {:run? false
                                           :on-output (fn [k s] (swap! out conj [k s]))
                                           :on-complete (fn [r] (deliver done r))})
          (is (= :interrupted (:status (deref done 10000 :timeout))))
          (is (= [] @out) "the loop never produced output")))
      (finally (ev/stop! e)))))

(deftest failed-restart-is-reported-test
  ;; restart-for-stop! must not print the
  ;; success line when the restart failed. Forces start-runtime! to
  ;; fail during the restart and checks the console shows the failure
  ;; line instead of the "restarted" line.
  (let [e (make (temp-project)) done (promise) out (atom [])]
    (try
      (ev/start! e)
      (let [pid (nrepl/child-pid e)]
        (ev/eval! e "(loop [] (recur))" {:run? false
                                         :on-output (fn [k s] (swap! out conj [k s]))
                                         :on-complete (fn [r] (deliver done r))})
        (wait-for-eval-id e)
        (shell/sh "kill" "-STOP" (str pid))
        (with-redefs [child/start! (fn [_ _] (throw (ex-info "boom" {})))]
          (ev/interrupt! e)
          (is (= :interrupted (:status (deref done 10000 :timeout))))
          (is (wait-until (fn [] (some (fn [[k s]] (and (= :error k)
                                                        (str/includes? s "couldn't restart the Jolt runtime")))
                                       @out))
                          5000)
              "the console shows the failed restart")
          (is (not (some (fn [[_ s]] (str/includes? s "Stop restarted the Jolt runtime")) @out))
              "and not the success line")))
      (finally (ev/stop! e)))))

(deftest owner-reset-over-nrepl-test
  (let [e (make (temp-project))]
    (try
      (is (= :ok (:status (first (run-owned e "(ns tabs.a) (defn hello [] :hi)" 1)))))
      (is (= :ok (:status (first (run-owned e "(ns tabs.b) (def x 1)" 2)))))
      (testing "a reset Run for owner 1 leaves tabs.b and recreates tabs.a"
        (is (= :ok (:status (first (run-owned e "(ns tabs.a) (def only 1)" 1)))))
        (is (= "1" (:printed (first (run e "tabs.b/x" false)))))
        (is (= "nil" (:printed (first (run e "(ns-resolve 'tabs.a 'hello)" false))))))
      (testing "the owner snapshots don't move the session out of the Run's namespace"
        (run-owned e "(ns tabs.c) (def here 3)" 3)
        (is (= "3" (:printed (first (run e "here" false))))))
      (finally (ev/stop! e)))))

(deftest run-without-reset-over-nrepl-test
  (let [e (make (temp-project))]
    (try
      (run e "(def kept 1)" false)
      (let [done (promise)]
        (ev/eval! e "(def other 2)" {:run? true :owner :repl :reset? false
                                     :on-complete (fn [r] (deliver done r))})
        (is (= :ok (:status (deref done 20000 :timeout)))))
      (is (= "1" (:printed (first (run e "kept" false)))))
      (is (= "2" (:printed (first (run e "other" false)))))
      (finally (ev/stop! e)))))

(deftest history-vars-survive-owner-snapshots-test
  ;; the owner snapshots are evals on the user's
  ;; session, and the server shifts *1/*2/*3 on every successful eval.
  (let [e (make (temp-project))]
    (try
      (run e "(+ 1 2)" false)
      (run e "(+ 10 10)" false)
      (is (= "[20 3]" (:printed (first (run e "[*1 *2]" false)))))
      (run-owned e "(ns tabs.h) (def v 5) v" 1)
      (is (= "5" (:printed (first (run e "*1" false)))) "a Run's value is *1 afterwards")
      (finally (ev/stop! e)))))

(deftest history-vars-survive-reset-on-run-test
  ;; Reset on Run's cleanup is one more eval on the user's session, and the
  ;; server shifts *1/*2/*3 for it too.
  (let [e (make (temp-project))]
    (try
      (run e "(throw (ex-info \"boom\" {}))" false)
      (doseq [c ["1" "2" "3"]] (run e c false))
      (run e "(+ 10 20)" true)
      (is (= "[30 3 2]" (:printed (first (run e "[*1 *2 *3]" false)))))
      (is (= "\"boom\"" (:printed (first (run e "(ex-message *e)" false)))) "the cleanup leaves *e alone")
      (finally (ev/stop! e)))))

(deftest repl-helpers-referred-test
  (let [e (make (temp-project))]
    (try
      (is (= [[:out "{:a 1}\n"]] (second (run e "(pprint {:a 1})" true))) "in a Run")
      (is (str/includes? (apply str (map second (second (run e "(doc inc)" false)))) "clojure.core/inc")
          "at the prompt")
      (is (= ":mine" (:printed (first (run e "(defn dir [& _] :mine) (dir)" false))))
          "a program's own definition wins")
      (run e "{:a 1}" false)
      (is (= [[:out "{:a 1}\n"]] (second (run e "(pp)" false))) "pp prints *1")
      (finally (ev/stop! e)))))

(deftest release-owner-over-nrepl-test
  ;; a closed tab's namespaces go to :repl, so the
  ;; next Run clears them.
  (let [e (make (temp-project))]
    (try
      (run-owned e "(ns orphan.a) (defn old-fn [] 1)" 2)
      (ev/release-owner! e 2)
      (run-owned e "(ns orphan.a) (def x 1)" 3)
      (is (= "nil" (:printed (first (run e "(ns-resolve 'orphan.a 'old-fn)" false)))))
      (finally (ev/stop! e)))))

(deftest auto-require-out-of-process-test
  ;; .jonquil.edn :auto-require for Jolt out of process: once per child,
  ;; before its first evaluation, and again after a restart.
  (let [dir (temp-project)
        lib (io/file dir "src/arlib2/core.clj")
        e (ev/make {:profile dialect/jolt :runtime :nrepl :project-dir dir :auto-require '[arlib2.core]})]
    (.mkdirs (.getParentFile lib))
    (spit lib (str "(ns arlib2.core)\n(def loaded :yes)\n;; " (System/nanoTime) "\n"))
    (try
      (let [[r out] (run e "(some? (find-ns 'arlib2.core))" false)]
        (is (= "true" (:printed r)) "required before the first evaluation")
        (is (some #(= [:system "; auto-require: arlib2.core\n"] %) out)))
      (testing "once per child"
        (let [[_ out] (run e "1" false)]
          (is (not-any? #(str/includes? (second %) "auto-require") out))))
      (testing "a restarted child requires it again"
        (child/kill! (:child (#'nrepl/runtime e)))
        (wait-until #(not (child/alive? (:child (#'nrepl/runtime e)))) 5000)
        (let [[r out] (run e "(some? (find-ns 'arlib2.core))" false)]
          (is (= "true" (:printed r)))
          (is (some #(= [:system "; auto-require: arlib2.core\n"] %) out))))
      (finally (ev/stop! e)))))

(deftest stop-during-auto-require-out-of-process-test
  ;; Stop while the list loads: interrupted, the user's request not sent,
  ;; and the next evaluation tries the list again and then runs normally
  ;; (no stale request id, no silently skipped list).
  (let [dir (temp-project)
        lib (io/file dir "src/arslow2/core.clj")
        e (ev/make {:profile dialect/jolt :runtime :nrepl :project-dir dir :auto-require '[arslow2.core]})]
    (.mkdirs (.getParentFile lib))
    ;; a loop, which interrupts cleanly (a sleep makes Stop restart the
    ;; child, and a new child requires the list again whatever the flag)
    (spit lib (str "(ns arslow2.core)\n(loop [] (recur))\n;; " (System/nanoTime) "\n"))
    (try
      (is (nil? (ev/start! e)))
      (let [done (promise) pid (nrepl/child-pid e)]
        (ev/eval! e "(def sent :yes)" {:on-output (fn [_ _]) :on-complete #(deliver done %)})
        (wait-for-eval-id e)
        (Thread/sleep 300)
        (ev/interrupt! e)
        (is (= :interrupted (:status (deref done 15000 :timeout))))
        (is (= pid (nrepl/child-pid e)) "an interrupt, not a restart"))
      (spit lib (str "(ns arslow2.core)\n(def loaded :yes)\n;; " (System/nanoTime) "\n"))
      (let [[r out] (run e "[(some? (find-ns 'arslow2.core)) (some? (resolve 'user/sent))]" false)]
        (is (= "[true false]" (:printed r)) "the list loaded this time; the stopped request never ran")
        (is (some #(= [:system "; auto-require: arslow2.core\n"] %) out) "tried again"))
      (finally (ev/stop! e)))))

(defn- run-quiet [e code ns-source]
  (let [done (promise) out (atom [])]
    (ev/eval! e code {:quiet "jonquil-quiet-test:" :ns-source ns-source
                      :on-output (fn [k s] (swap! out conj [k s]))
                      :on-complete (fn [r] (deliver done r))})
    [(deref done 20000 :timeout) @out]))

(deftest quiet-eval-keeps-history-live-test
  ;; a :quiet eval on a Jolt child leaves the REPL as it was
  (let [dir (temp-project) e (make dir)]
    (try
      (is (nil? (ev/start! e)))
      (is (satisfies? ev/QuietEval e))
      (run e "(ns demo.quiet) (def here :q)" false)
      (run e "(in-ns 'user)" false)
      (doseq [c ["1" "2" "3"]] (run e c false))
      (run e "(throw (ex-info \"e1\" {}))" false)
      (let [items (:items @(ev/history e))
            prompt (ev/prompt-ns e)
            [r out] (run-quiet e "(println \"side\") (+ 40 2)" nil)]
        (is (= :ok (:status r)))
        (is (= "42" (:printed r)))
        (is (= [[:out "side\n"]] out) "the token line never reaches on-output")
        (is (= {:status :error :error-text "Error: e2"}
               (select-keys (first (run-quiet e "(throw (ex-info \"e2\" {}))" nil)) [:status :error-text])))
        (is (= ":q" (:printed (first (run-quiet e "here" "(ns demo.quiet)")))) "the buffer's namespace")
        (is (= items (:items @(ev/history e))) "no history entry")
        (is (= prompt (ev/prompt-ns e)) "the prompt namespace didn't move")
        (is (= "[3 2 1 \"e1\"]" (:printed (first (run e "[*1 *2 *3 (ex-message *e)]" false))))))
      (finally (ev/stop! e)))))

(deftest frames-reply-test
  (is (= {:note "No error yet."} (nrepl/frames-reply [{:data "{:note \"No error yet.\"}"} {:status ["done"]}])))
  (is (= {:error "frames broke"} (nrepl/frames-reply [{:error "frames broke" :status ["done"]}])))
  (is (= {:error "The Jolt child answered without its frames."}
         (nrepl/frames-reply [{:status ["done" "error"] :view "{}"}]))))

(deftest toggle-reply-test
  (is (= {:on? true} (nrepl/toggle-reply [{:on "true" :status ["done"]}])))
  (is (= {:on? false} (nrepl/toggle-reply [{:on "false"}])))
  (is (= {:error "no profile"} (nrepl/toggle-reply [{:error "no profile"}])))
  (is (= {:error "The Jolt child didn't say whether debug mode is on."}
         (nrepl/toggle-reply [{:status ["done" "error"] :view "{}"}]))))

(deftest frame-eval-reply-test
  (is (= {:out "hi\n" :text "63"} (nrepl/frame-eval-reply [{:frame-out "hi\n" :text "63" :status ["done"]}])))
  (is (= {:out "" :text "1"} (nrepl/frame-eval-reply [{:text "1"}])))
  (is (= {:error "boom"} (nrepl/frame-eval-reply [{:error "boom"}])))
  (is (= {:error "The Jolt child answered without a value."} (nrepl/frame-eval-reply [{:status ["done" "error"]}]))))
