(ns jonquil.nrepl.attach-test
  "The attach evaluator against dev/fake_cljd_nrepl.clj's fake fork server.
  Every test owns its temp project and its fake server, and stops both."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fake-cljd-nrepl :as fake]
            [jonquil.cljd :as cljd]
            [jonquil.dialect :as dialect]
            [jonquil.evaluator :as ev]
            [jonquil.nrepl.attach :as attach]))

(def drive-text
  "(ns demo.drive\n  (:require [cljd.string :as str]))\n\n(defn where [] 1)\n\n(comment\n  (where))\n\n(defonce state (atom nil))\n")

(defn- temp-app
  "A throwaway app directory: pubspec.yaml, dev/demo/drive.cljd, and an
  NREPL.lock holding port-text (none when nil). [dir file]."
  [port-text]
  (let [d (io/file (System/getProperty "java.io.tmpdir") (str "jonquil-attach-test-" (System/currentTimeMillis) "-" (random-uuid)))
        f (io/file d "dev/demo/drive.cljd")]
    (.mkdirs (.getParentFile f))
    (spit (io/file d "pubspec.yaml") "name: demo\n")
    (spit f drive-text)
    (when port-text (spit (io/file d "NREPL.lock") port-text))
    [(.getCanonicalPath d) (.getCanonicalPath f)]))

(defn- delete-tree! [dir]
  (doseq [f (reverse (file-seq (io/file dir)))] (.delete ^java.io.File f)))

(defn- lock-state
  "NREPL.lock's bytes and mtime, and whether a REPL.lock exists: the files
  Jonquil must never write, delete or create."
  [dir]
  (let [f (io/file dir "NREPL.lock")]
    [(when (.exists f) (slurp f)) (.lastModified f) (.exists (io/file dir "REPL.lock"))]))

(defn- make [dir]
  (attach/make-attach {:profile dialect/cljd :runtime :attach :project-dir dir}
                      {:describe-timeout-ms 500 :clone-timeout-ms 500 :still-waiting-ms 200}))

(defn- run
  "Evaluates and waits. [result output] where output is every on-output
  call as [kind text]."
  [e code opts]
  (let [done (promise) out (atom [])]
    (ev/eval! e code (assoc opts
                            :on-output (fn [k s] (swap! out conj [k s]))
                            :on-complete (fn [r] (deliver done r))))
    [(deref done 10000 :timeout) @out]))

(defn- texts [out kind] (keep (fn [[k s]] (when (= k kind) s)) out))

(defn- with-app
  "Runs (f srv dir file) against a fake server (opts) whose port is in the
  app's NREPL.lock, then stops the server and deletes the app."
  [opts f]
  (let [srv (fake/start! opts)
        [dir file] (temp-app (str (:port srv) "\n"))]
    (try (f srv dir file)
         (finally (fake/stop! srv) (delete-tree! dir)))))

(deftest make-dispatches-on-runtime-test
  (let [e (ev/make {:profile dialect/cljd :runtime :attach :project-dir "/nowhere"})]
    (is (instance? jonquil.nrepl.attach.Attach e))
    (is (not (attach/connected? e)) "nothing connects until an evaluation needs it")
    (is (nil? (ev/start! e)))
    (is (= 'cljd.user (ev/prompt-ns e)))
    (is (= 5 (count (ev/limitations e))))))

(deftest connect-rules-test
  (testing "no NREPL.lock: the not-running notice, and nothing written"
    (let [[dir file] (temp-app nil) e (make dir)]
      (try
        (is (= {:status :notice :text cljd/not-running-message} (first (run e "(+ 1 2)" {:run? false}))))
        (is (= {:status :notice :text cljd/not-running-message} (first (run e drive-text {:run? true :path file}))))
        (is (not (attach/connected? e)))
        (is (= [nil 0 false] (lock-state dir)))
        (finally (delete-tree! dir)))))
  (testing "no project at all"
    (let [e (attach/make-attach {:profile dialect/cljd :runtime :attach :project-dir nil})]
      (is (= {:status :notice :text cljd/no-project-message} (first (run e "1" {:run? false}))))))
  (testing "NREPL.lock without a port"
    (let [[dir _] (temp-app "not a port\n") e (make dir) before (lock-state dir)]
      (try
        (is (= {:status :notice :text cljd/bad-port-file-message} (first (run e "1" {:run? false}))))
        (is (= before (lock-state dir)))
        (finally (delete-tree! dir)))))
  (testing "a port nothing answers: the refused notice, after the connecting line"
    (let [dead (doto (fake/start!) fake/stop!)
          [dir _] (temp-app (str (:port dead) "\n")) e (make dir) before (lock-state dir)
          [r out] (run e "1" {:run? false})]
      (try
        (is (= {:status :notice :text (cljd/refused-message (:port dead))} r))
        (is (= [(str (cljd/connecting-message (:port dead)) "\n")] (texts out :system)))
        (is (= before (lock-state dir)) "the stale file is never deleted or rewritten")
        (finally (delete-tree! dir)))))
  (testing "a server without eval and load-file: not ClojureDart, and no code is sent"
    (with-app {:ops ["describe" "clone"]}
      (fn [srv dir _]
        (let [e (make dir)]
          (is (= {:status :notice :text (cljd/not-cljd-message (:port srv))} (first (run e "1" {:run? false}))))
          (is (= ["describe"] (fake/ops-seen srv)))
          (is (not (attach/connected? e)))))))
  (testing "a listener that never answers describe: not ClojureDart, bounded by the describe timeout"
    (with-app {:mute? true}
      (fn [srv dir _]
        (is (= {:status :notice :text (cljd/not-cljd-message (:port srv))} (first (run (make dir) "1" {:run? false}))))))))

(deftest load-run-test
  (with-app {}
    (fn [srv dir file]
      (let [e (make dir) seen (atom []) before (lock-state dir)]
        (attach/on-connection! e (fn [c] (swap! seen conj c)))
        (let [[r out] (run e (str drive-text "(where)\n") {:run? true :path file})]
          (testing "quiet: one summary line, not a value per form"
            (is (= {:status :notice :text "; Loaded demo.drive (4 forms)"} r)))
          (is (= [fake/banner] (texts out :out)) "the banner, once")
          (is (str/starts-with? (first (texts out :system)) "; Connecting to the app on port ")))
        (testing "load-file carries the buffer's text (unsaved edits count), the absolute path and the name"
          (let [req (last (fake/requests srv))]
            (is (= ["load-file" (str drive-text "(where)\n") file "drive.cljd"]
                   [(get req "op") (get req "file") (get req "file-path") (get req "file-name")]))))
        (is (= 'demo.drive (ev/prompt-ns e)) "the session ends in the file's namespace")
        (is (attach/connected? e))
        (is (= [true] @seen))
        (testing "a second Run reuses the session: no second clone, no second banner"
          (let [[r out] (run e drive-text {:run? true :path file})]
            (is (= "; Loaded demo.drive (3 forms)" (:text r)))
            (is (empty? (texts out :out)))
            (is (= 1 (count (filter #{"clone"} (fake/ops-seen srv)))))))
        (is (= before (lock-state dir)))))))

(deftest load-stops-and-untitled-test
  (with-app {:fail-on "boom"}
    (fn [srv dir file]
      (let [e (make dir)
            [r out] (run e "(ns demo.drive)\n(defn a [])\n(defn b [])\n(boom)\n(defn c [])" {:run? true :path file})]
        (is (= {:status :error :error-text "Load stopped at form 3 of demo.drive"} r))
        (is (= [fake/fail-err] (texts out :err)) "the err is shown as it arrives")
        (testing "an untitled buffer: refused before anything is sent"
          (let [n (count (fake/ops-seen srv))]
            (is (= {:status :error :error-text cljd/save-first-message} (first (run e drive-text {:run? true :path nil}))))
            (is (= n (count (fake/ops-seen srv))))))))))

(deftest namespace-switch-test
  (with-app {}
    (fn [srv dir file]
      (let [e (make dir)
            codes (fn [] (keep (fn [m] (get m "code")) (fake/requests srv)))]
        (run e drive-text {:run? true :path file})
        (testing "the session is already in the buffer's namespace: only the selection goes"
          (is (= :ok (:status (first (run e "(where)" {:run? false :ns-source drive-text})))))
          (is (= [cljd/probe-code "(where)"] (codes))))
        (testing "another buffer's namespace: its ns form first, then the selection"
          (run e "(g)" {:run? false :ns-source "(ns other.place)\n(defn g [] 2)"})
          (is (= [cljd/probe-code "(where)" "(ns other.place)" "(g)"] (codes)))
          (is (= 'other.place (ev/prompt-ns e))))
        (testing "a buffer with no ns form evaluates in cljd.user"
          (run e "(+ 1 2)" {:run? false :ns-source "(+ 1 2)"})
          (is (= ["(ns cljd.user)" "(+ 1 2)"] (take-last 2 (codes)))))
        (testing "a REPL entry (no :ns-source) stays where the session is"
          (run e "(h)" {:run? false})
          (is (= "(h)" (last (codes))))
          (is (= 'cljd.user (ev/prompt-ns e))))
        (is (= ["(where)" "(g)" "(+ 1 2)" "(h)"] (:items @(ev/history e)))
            "every eval is REPL history; the ns switches aren't")))))

(deftest wake-up-probe-test
  (let [codes (fn [srv] (vec (keep (fn [m] (get m "code")) (fake/requests srv))))]
    (testing "a fresh session's first request is Jonquil's own probe; the banner shows once, the probe's value never"
      (with-app {:answers {"(where)" "{:slide 21, :of 54}"}}
        (fn [srv dir _]
          (let [[r out] (run (make dir) "(where)" {:run? false})]
            (is (= {:status :ok :printed "{:slide 21, :of 54}" :ns 'cljd.user} r))
            (is (= [fake/banner] (texts out :out)))
            (is (= ["describe" "clone" "eval" "eval"] (fake/ops-seen srv)))
            (is (= [cljd/probe-code "(where)"] (codes srv)))))))
    (testing "a probe answered nil is sent once more; the user's code still goes once"
      (with-app {:asleep-for 1}
        (fn [srv dir _]
          (is (= :ok (:status (first (run (make dir) "(where)" {:run? false})))))
          (is (= [cljd/probe-code cljd/probe-code "(where)"] (codes srv))))))
    (testing "two nil probes: say so, and never send the user's code; the next Run probes again"
      (with-app {:asleep-for 2}
        (fn [srv dir file]
          (let [e (make dir)]
            (is (= {:status :notice :text cljd/not-awake-message} (first (run e drive-text {:run? true :path file}))))
            (is (= [cljd/probe-code cljd/probe-code] (codes srv)))
            (is (not-any? #{"load-file"} (fake/ops-seen srv)))
            (is (= :notice (:status (first (run e drive-text {:run? true :path file})))))
            (is (= [cljd/probe-code cljd/probe-code cljd/probe-code] (codes srv)) "same session, still fresh")
            (is (= 1 (count (filter #{"load-file"} (fake/ops-seen srv)))) "sent once the probe answered")))))
    (testing "a user form that returns nil is sent exactly once, fresh session or not"
      (with-app {:answers {"(next!)" "nil"}}
        (fn [srv dir _]
          (let [e (make dir)]
            (is (= "nil" (:printed (first (run e "(next!)" {:run? false})))))
            (is (= "nil" (:printed (first (run e "(next!)" {:run? false})))))
            (is (= [cljd/probe-code "(next!)" "(next!)"] (codes srv)))))))
    (testing "a println's out (fork 8aaa4628: out before value) is shown, and the form runs once"
      (with-app {:prints {"println" ":side-effect\n"} :answers {"(println :side-effect)" "nil"}}
        (fn [srv dir _]
          (let [[r out] (run (make dir) "(println :side-effect)" {:run? false})]
            (is (= "nil" (:printed r)))
            (is (= [fake/banner ":side-effect\n"] (texts out :out)))
            (is (= [cljd/probe-code "(println :side-effect)"] (codes srv)))))))))

(deftest still-waiting-test
  (with-app {:slow {"(slow)" 500}}
    (fn [_ dir _]
      (let [[r out] (run (make dir) "(slow)" {:run? false})]
        (is (= :ok (:status r)) "no client-side timeout: the answer still lands")
        (is (= 1 (count (filter #{(str cljd/still-waiting-message "\n")} (texts out :system)))))))))

(deftest stop-detaches-test
  (with-app {:slow {"(slow)" 3000}}
    (fn [srv dir _]
      (let [e (make dir) seen (atom []) done (promise) out (atom [])
            before (lock-state dir)]
        (attach/on-connection! e (fn [c] (swap! seen conj c)))
        (run e "1" {:run? false})
        (ev/eval! e "(slow)" {:run? false :on-output (fn [k s] (swap! out conj [k s]))
                              :on-complete (fn [r] (deliver done r))})
        (loop [n 0] (when (and (< n 250) (not (some #{"(slow)"} (keep (fn [m] (get m "code")) (fake/requests srv)))))
                      (Thread/sleep 20) (recur (inc n))))
        (ev/interrupt! e)
        (is (= {:status :interrupted} (deref done 1000 :timeout)) "Stop stops waiting at once")
        (is (= [(str cljd/detached-message "\n")] (texts @out :system)))
        (is (not (attach/connected? e)))
        (is (= [true false] @seen))
        (is (not (ev/evaluating? e)))
        (testing "the next evaluation reconnects with a fresh clone"
          (let [[r out2] (run e "(+ 1 2)" {:run? false})]
            (is (= :ok (:status r)))
            (is (= [fake/banner] (texts out2 :out)))
            (is (= 2 (count (filter #{"clone"} (fake/ops-seen srv)))))))
        (testing "stop! closes the socket and sends nothing"
          (ev/stop! e)
          (is (not (attach/connected? e))))
        (Thread/sleep 100)
        (is (not-any? #{"interrupt" "close"} (fake/ops-seen srv)) "never interrupt, never close")
        (is (= before (lock-state dir)))))))

(deftest dropped-connection-test
  (with-app {:drop-on "(quit-app)"}
    (fn [srv dir _]
      (let [e (make dir) seen (atom [])]
        (attach/on-connection! e (fn [c] (swap! seen conj c)))
        (is (= {:status :error :error-text cljd/lost-connection} (first (run e "(quit-app)" {:run? false}))))
        (is (not (attach/connected? e)))
        (is (= [true false] @seen))
        (is (= :ok (:status (first (run e "(+ 1 2)" {:run? false})))) "the next Run reconnects")
        (is (= 2 (count (filter #{"clone"} (fake/ops-seen srv)))))))))

(deftest busy-test
  (with-app {:slow {"(slow)" 400}}
    (fn [_ dir _]
      (let [e (make dir) done (promise)]
        (ev/eval! e "(slow)" {:run? false :on-complete (fn [r] (deliver done r))})
        (is (= attach/busy-warning (ev/eval! e "1" {:run? false})))
        (is (ev/evaluating? e))
        (is (= :ok (:status (deref done 5000 :timeout))))))))
