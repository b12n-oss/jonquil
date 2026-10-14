(ns jonquil.live.clj-attach-test
  "Drives jonquil.nrepl.jvm against a REAL clojure -M:serve nREPL server
  (clojure-cli.repl-server 0.1.0 on nREPL 1.7.0), started once for this
  namespace by dev/clj_server.clj, plus one test (eof-live-1-8) on its own
  nREPL 1.8.0 server. Run with `bb test:clj`, not `bb test`
  (ruling 12): it needs Java 17+, the clojure CLI and, on a first run
  that hasn't cached clojure-cli.repl-server yet, the network.

  clj-server plays the human who already has a server running, the way
  jonquil.nrepl.child plays the human for the :nrepl dialect's own live
  suite: Jonquil's own evaluator code never starts, stops or signals
  this JVM.

  Every test but the two that start their own server shares one server
  (the :once fixture) and makes its own fresh Jvm evaluator against it,
  so each gets its own clone session; a namespace loaded by one test's
  Run stays loaded for the whole server (real Clojure semantics), which
  is why not-loaded-live uses a namespace ('demo.never') nothing else
  ever touches. Two tests start and stop their own server instead:
  eof-live-1-8 needs nREPL 1.8.0 (the shared server runs 1.7.0), and
  server-stopped-leaves-no-port-file-live stops its server, which would
  break the shared one for the tests after it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [clj-server :as clj-server]
            [jonquil.clj :as clj]
            [jonquil.dialect :as dialect]
            [jonquil.evaluator :as ev]
            [jonquil.nrepl.jvm :as jvm]
            [jonquil.source :as source]))

(def ^:dynamic *srv* nil)

(defn- once-fixture [f]
  (let [srv (clj-server/start!)]
    (try
      (binding [*srv* srv] (f))
      (finally (clj-server/stop! srv)))))

(use-fixtures :once once-fixture)

(defn- make []
  (jvm/make-jvm {:profile dialect/clj :runtime :attach :project-dir (:dir *srv*)}))

(defn- run
  "Evaluates and waits. {:result result :out output :waits on-wait-input
  calls}."
  [e code opts]
  (let [done (promise) out (atom []) waits (atom [])]
    (ev/eval! e code (assoc opts
                            :on-output (fn [k s] (swap! out conj [k s]))
                            :on-wait-input (fn [w] (swap! waits conj w))
                            :on-complete (fn [r] (deliver done r))))
    {:result (deref done 15000 :timeout) :out @out :waits @waits}))

(defn- wait-until!
  "Polls (pred) every 20ms, up to ~5s, for a background worker to reach a
  point a test needs to synchronize on (waiting-for-input? becoming
  true)."
  [pred]
  (loop [n 0]
    (when (and (< n 250) (not (pred)))
      (Thread/sleep 20)
      (recur (inc n)))))

(deftest run-then-private-call-live
  (let [e (make)
        file (.getCanonicalPath (io/file (:dir *srv*) "src/demo/core.clj"))
        {:keys [result]} (run e clj-server/fixture-core-text {:run? true :path file})]
    (is (= :ok (:status result)))
    (is (= 'demo.core (ev/prompt-ns e)) "the prompt moves to the buffer's own namespace")
    (let [{:keys [result]} (run e "(secret)" {:run? false :ns-source clj-server/fixture-core-text})]
      (is (= :ok (:status result)))
      (is (= ":private-ok" (:printed result))))))

(deftest auto-require-live
  ;; .jonquil.edn's :auto-require on a real server: demo.auto is written
  ;; here (nothing else loads it), required on connect, and usable from
  ;; the first REPL line without a require of its own.
  (let [f (io/file (:dir *srv*) "src/demo/auto.clj")]
    (spit f "(ns demo.auto)\n\n(def loaded :yes)\n")
    (try
      (let [e (jvm/make-jvm {:profile dialect/clj :runtime :attach :project-dir (:dir *srv*)
                             :auto-require '[demo.auto]})
            {:keys [result out]} (run e "demo.auto/loaded" {:run? false})]
        (is (= :ok (:status result)))
        (is (= ":yes" (:printed result)))
        (is (some #{(str (clj/auto-require-message '[demo.auto]) "\n")} (keep (fn [[k s]] (when (= :system k) s)) out))))
      (finally (io/delete-file f true)))))

(deftest auto-require-missing-live
  ;; A namespace the server can't find: its own error streams, Jonquil says
  ;; the require failed, and the user's line still answers.
  (let [e (jvm/make-jvm {:profile dialect/clj :runtime :attach :project-dir (:dir *srv*)
                         :auto-require '[demo.nowhere]})
        {:keys [result out]} (run e "(+ 1 2)" {:run? false})
        texts (fn [kind] (keep (fn [[k s]] (when (= kind k) s)) out))]
    (is (= :ok (:status result)))
    (is (= "3" (:printed result)))
    (is (some #(str/includes? % "demo/nowhere") (texts :err)) (pr-str out))
    (is (some #{(str clj/auto-require-failed-message "\n")} (texts :system)))))

(deftest not-loaded-live
  (let [e (make)
        {:keys [result]} (run e "(+ 1 2)" {:run? false :ns-source "(ns demo.never)\n(+ 1 2)"})]
    (is (= {:status :notice :text (clj/not-loaded-message "demo.never")} result))))

(def never-loaded-map-text
  ;; demo.never is never Run by any test in this namespace, so lookup and
  ;; full-doc's own buffer-ns attempt always errors here and both fall
  ;; back to the prompt namespace (user), where clojure.core/map resolves.
  "(ns demo.never)\n(map inc [1])")

(deftest lookup-not-loaded-live
  (let [e (make)]
    (run e "1" {:run? false})
    (let [d (ev/lookup e "map" never-loaded-map-text 0)]
      (is (= :found (:status d)))
      (is (= "clojure.core" (:ns d))))))

(deftest full-doc-not-loaded-live
  ;; MEASURED (not the prescribed 2000 ms): the real server's own doc op
  ;; has widely variable latency on its first few calls in a fresh JVM --
  ;; measured single doc requests here took 3.8 s, 7.0 s and over 10 s
  ;; (once even a 12 s wait), regardless of which ns answered. That's the
  ;; server warming up its own doc machinery, not this fix wave's own
  ;; resolution (two lookup requests, each bounded by :help-timeout-ms,
  ;; which answer in low milliseconds every time -- see
  ;; lookup-not-loaded-live above). The first doc call downloads the
  ;; ClojureDocs export into this run's fresh CLJ_CONFIG, so this uses the
  ;; default :full-doc-timeout-ms, which allows for that, and asserts the
  ;; functional outcome: full-doc resolves via the prompt ns to a real
  ;; :ok, never :not-loaded, rather than racing a tight deadline against
  ;; server-side warm-up latency this fix wave doesn't control.
  (let [e (jvm/make-jvm {:profile dialect/clj :runtime :attach :project-dir (:dir *srv*)})]
    (run e "1" {:run? false})
    (let [done (promise)]
      (ev/full-doc e "map" never-loaded-map-text (fn [r] (deliver done r)))
      (let [result (deref done 35000 :timeout)]
        (is (= :ok (:status result)) (str "result: " result))))))

(deftest output-streams-live
  (let [e (make)
        events (atom [])
        done (promise)]
    (ev/eval! e "(do (println :a) (flush) (Thread/sleep 1000) (println :b) (flush) 42)"
              {:run? false
               :on-output (fn [k s] (swap! events conj [(System/currentTimeMillis) k s]))
               :on-complete (fn [r] (deliver done r))})
    (let [result (deref done 15000 :timeout)
          at (fn [text] (first (keep (fn [[t k s]] (when (and (= :out k) (= text s)) t)) @events)))
          a-at (at ":a\n")
          b-at (at ":b\n")]
      (is (= :ok (:status result)))
      (is (= "42" (:printed result)))
      (is (some? a-at) "the :a output arrived")
      (is (some? b-at) "the :b output arrived")
      (is (>= (- b-at a-at) 900) (str "b arrived " (- b-at a-at) " ms after a")))))

(deftest stop-busy-loop-live
  (let [e (make)
        done (promise)]
    (ev/eval! e "(loop [i 0] (recur (inc i)))" {:run? false :on-complete (fn [r] (deliver done r))})
    (Thread/sleep 300)
    (let [start (System/nanoTime)]
      (ev/interrupt! e)
      (is (= :interrupted (:status (deref done 1500 :timeout))))
      (is (< (/ (- (System/nanoTime) start) 1e6) 1500.0) "interrupted well under 1500 ms"))
    (is (= {:status :ok :printed "3" :ns 'user} (:result (run e "(+ 1 2)" {:run? false})))
        "the next eval answers on the same connection")))

(deftest stop-during-read-line-live
  (let [e (make)
        done (promise)]
    (ev/eval! e "(read-line)" {:run? false :on-complete (fn [r] (deliver done r))})
    (wait-until! (fn [] (ev/waiting-for-input? e)))
    (is (true? (ev/waiting-for-input? e)))
    (ev/interrupt! e)
    (is (= :interrupted (:status (deref done 2000 :timeout))))
    (is (false? (ev/waiting-for-input? e)) "the console leaves its input-waiting state")))

(deftest stdin-live
  (let [e (make)
        waits (atom [])
        done (promise)]
    (ev/eval! e "(read-line)" {:run? false
                               :on-wait-input (fn [w] (swap! waits conj w))
                               :on-complete (fn [r] (deliver done r))})
    (wait-until! (fn [] (ev/waiting-for-input? e)))
    (is (true? (ev/waiting-for-input? e)))
    (is (nil? (ev/send-input! e "typed")))
    (is (= {:status :ok :printed "\"typed\"" :ns 'user} (deref done 2000 :timeout)))
    (is (false? (ev/waiting-for-input? e)))
    (is (= [1 7] (:nrepl-version @(:state e))))))

(deftest eof-live-1-8
  ;; Cmd/Ctrl+D on a real nREPL 1.8.0 server (its own, not the shared 1.7.0
  ;; one): EOF ends a waiting read-line with nil, leaves no stale EOF
  ;; queued for the next read, and is a no-op while idle.
  (let [srv (clj-server/start! {:nrepl "1.8.0"})]
    (try
      (let [e (jvm/make-jvm {:profile dialect/clj :runtime :attach :project-dir (:dir srv)})
            start-read (fn []
                         (let [done (promise)]
                           (ev/eval! e "(read-line)" {:run? false :on-complete (fn [r] (deliver done r))})
                           (wait-until! (fn [] (ev/waiting-for-input? e)))
                           done))]
        (let [done (start-read)]
          (is (true? (ev/waiting-for-input? e)))
          (ev/send-eof! e)
          (is (= {:status :ok :printed "nil" :ns 'user} (deref done 5000 :timeout)))
          (is (false? (ev/waiting-for-input? e))))
        (let [done (start-read)]
          (is (true? (ev/waiting-for-input? e)))
          (is (nil? (ev/send-input! e "hello")))
          (is (= "\"hello\"" (:printed (deref done 5000 :timeout)))))
        (ev/send-eof! e)
        (let [done (start-read)]
          (is (true? (ev/waiting-for-input? e)) "no stale EOF was queued")
          (is (nil? (ev/send-input! e "again")))
          (is (= "\"again\"" (:printed (deref done 5000 :timeout)))))
        ;; The version lands on first connect, so only after the reads above.
        (is (= [1 8] (:nrepl-version @(:state e)))))
      (finally (clj-server/stop! srv)))))

(deftest inspect-after-first-run-live
  ;; review focus 1: Cmd/Ctrl+I right after the first Run of a JVM file
  ;; must never show nil -- register-inspect!'s own connect-time eval of
  ;; nil registers the session before the Run's own load-file runs. A
  ;; fresh jvm/make-jvm (not the shared `make` helper above, though it
  ;; does the same thing) makes the ordering explicit: this evaluator's
  ;; connection has sent nothing else before the Run. :inspect-timeout-ms
  ;; is widened past the 5000 ms default: inspect-start's own datafy/
  ;; pprint path is untested against the real server before this task,
  ;; and full-doc-not-loaded-live's own comment measured a real first-call
  ;; warm-up elsewhere in this same server process.
  (let [e (jvm/make-jvm {:profile dialect/clj :runtime :attach :project-dir (:dir *srv*)}
                        {:inspect-timeout-ms 20000})
        file (.getCanonicalPath (io/file (:dir *srv*) "src/demo/core.clj"))
        {:keys [result]} (run e clj-server/fixture-core-text {:run? true :path file})
        done (promise)]
    (is (= :ok (:status result)))
    (ev/inspect! e :start {:rows 10 :cols 40} (fn [v] (deliver done v)))
    (let [view (deref done 20000 :timeout)]
      (is (nil? (:error view)) (str "view: " view))
      (is (not= "nil" (:info view)) (str "view: " view)))))

(deftest inspect-in-out-def-live
  ;; A REPL map, in on :a, then :def while STILL on that frame --
  ;; jonquil.inspect.view/def-as interns (:value (peek stack)), the
  ;; CURRENT top-of-stack value, so :def has to run before :out or it
  ;; would intern the whole map instead of [1 2] and the closing eval
  ;; below would fail. (Verified against def-as's own body, not the
  ;; brief's plain comma order, which lists :out ahead of :def.)
  (let [e (jvm/make-jvm {:profile dialect/clj :runtime :attach :project-dir (:dir *srv*)}
                        {:inspect-timeout-ms 20000})
        {:keys [result]} (run e "{:a [1 2]}" {:run? false})]
    (is (= :ok (:status result)))
    (let [start-p (promise)]
      (ev/inspect! e :start {:rows 10 :cols 40} (fn [v] (deliver start-p v)))
      (let [view (deref start-p 20000 :timeout)]
        (is (nil? (:error view)) (str "start: " view))))
    (let [in-p (promise)]
      (ev/inspect! e :in {:idx 0} (fn [v] (deliver in-p v)))
      (let [view (deref in-p 5000 :timeout)]
        (is (= "*1 / :a" (:path view)) (str "in: " view))))
    (let [def-p (promise)]
      (ev/inspect! e :def {:name "picked"} (fn [v] (deliver def-p v)))
      (let [view (deref def-p 5000 :timeout)]
        (is (= "#'user/picked" (:defd view)) (str "def: " view))))
    (let [out-p (promise)]
      (ev/inspect! e :out {} (fn [v] (deliver out-p v)))
      (let [view (deref out-p 5000 :timeout)]
        (is (= "*1" (:path view)) (str "out: " view))))
    (let [{:keys [result]} (run e "picked" {:run? false})]
      (is (= :ok (:status result)))
      (is (= "[1 2]" (:printed result))))))

(deftest source-location-live
  ;; The real server's lookup for clojure.string/join names a jar entry
  ;; in ~/.m2; jonquil.source reads it locally and line :line is the defn.
  (let [e (make)]
    (is (= :ok (:status (:result (run e "(+ 1 2)" {:run? false})))))
    (let [text "(clojure.string/join \",\" [])"
          doc (ev/lookup e "clojure.string/join" text 1)
          loc (source/location doc {:project-dir (:dir *srv*)})
          {read-text :text} (source/read-text loc)]
      (is (:jar loc) (str "a jar entry: " (pr-str doc)))
      (is (str/starts-with? (:label loc) "string.clj in clojure-") (:label loc))
      (is (false? (:editable? loc)))
      ;; MEASURED (not the brief's literal "(defn join"): clojure-1.12.5's
      ;; string.clj hints join's return type, so the real line reads
      ;; "(defn ^String join". The regex allows that optional hint so the
      ;; assertion still fails if :line ever points at the wrong defn.
      (let [defn-line (nth (str/split-lines read-text) (dec (:line loc)))]
        (is (re-find #"^\(defn(?: \^\S+)? join\b" defn-line) defn-line)))))

(deftest server-stopped-leaves-no-port-file-live
  (let [srv (clj-server/start!)]
    (clj-server/stop! srv)
    (is (not (.exists (io/file (:dir srv) ".nrepl-port"))))))
