(ns jonquil.plugin-isolated
  "The parent's half of an isolated check (plugins design, Part 2, \"The
  isolated check\"; slices P8 and P8b): the request.edn text the child
  reads, the environment it gets, parse-result, which turns what the child
  printed into run-protocol's result, and check!, which runs the child.
  check! is the protocol's :isolated seam: the child runs run-protocol
  :in-process itself, so the two can't drift in what they check."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.process :as p]
            [jonquil.login-path :as login-path]
            [jonquil.nrepl.leftovers :as leftovers]
            [jonquil.plugin-checker :as checker]
            [jonquil.plugin-checker-files :as checker-files]
            [jonquil.tool-path :as tool-path]))

(def CHECK-DEADLINE-MS
  ;; estimate: a cold jolt start with the checker's namespaces measured
  ;; 1.05 s (0.16 s warm), plus the 10 s load budget, plus room for a soak
  ;; of several commands at up to 2 s per fixture
  30000)

(defn request-text
  "The request.edn text for a check of file-id against request (a validate
  request, jonquil.plugin-check/request-from-registry's shape), with the
  in-process budgets unless given."
  [{:keys [request file-id load-ms soak-ms]}]
  (pr-str {:request request :file-id file-id :load-ms (or load-ms 10000) :soak-ms (or soak-ms 2000)}))

(def ^:private credential-words
  ["TOKEN" "SECRET" "PASSWORD" "PASSWD" "PASSPHRASE" "CREDENTIAL" "AUTH" "COOKIE" "SESSION"
   "APIKEY" "ACCESS_KEY" "PRIVATE"])

(def ^:private credential-suffixes ["_KEY" "_PASS" "_PAT"])

(defn- credential-name? [n]
  (let [u (str/upper-case n)]
    (boolean (or (some #(str/includes? u %) credential-words)
                 (= "KEY" u)
                 (some #(str/ends-with? u %) credential-suffixes)))))

(defn scrub-env
  "env (name -> value) without the variables whose names look like
  credentials. A scrub of the obvious names, not a guarantee: PATH, HOME
  and the rest stay, since jolt needs them to start."
  [env]
  (into {} (remove (fn [[k _]] (credential-name? k))) env))

(defn- failed [sentence]
  {:ok? false :problems [sentence] :warnings [] :timings {} :plugin nil :definition nil})

(defn- last-pair
  "The text between out's last MARK-START line and the MARK-END after it,
  or nil."
  [out]
  (when-let [i (some-> out (str/last-index-of (str checker/MARK-START "\n")))]
    (let [from (+ i (count checker/MARK-START) 1)]
      (when-let [j (str/index-of out (str "\n" checker/MARK-END) (dec from))]
        (subs out from (max from j))))))

(defn- strings? [x] (and (vector? x) (every? string? x)))

(defn- summary?
  "checker/summary's shape, every key present."
  [p]
  (and (map? p) (= #{:id :doc :effects :commands :bindings :hooks} (set (keys p)))
       (keyword? (:id p)) (or (nil? (:doc p)) (string? (:doc p))) (set? (:effects p))
       (vector? (:commands p)) (every? map? (:commands p))
       (vector? (:bindings p)) (every? map? (:bindings p))
       (vector? (:hooks p)) (every? keyword? (:hooks p))))

(defn- result?
  "A whole result (review I2): a pass carries no problems and a full
  :plugin summary, a fail no :plugin, and :problems, :warnings and
  :timings have their shapes when present."
  [v]
  (and (map? v) (boolean? (:ok? v))
       (or (not (contains? v :problems)) (strings? (:problems v)))
       (or (not (contains? v :warnings)) (strings? (:warnings v)))
       (or (not (contains? v :timings)) (map? (:timings v)))
       (if (:ok? v)
         (and (empty? (:problems v)) (summary? (:plugin v)))
         (nil? (:plugin v)))))

(def OUTPUT-MAX-BYTES
  ;; estimate: P8's request measured 6209 chars and a result is smaller, so
  ;; this is room for about 150; a candidate printing in a loop until the
  ;; deadline can write far more, and only the last marker pair matters
  (* 1024 1024))

(defn read-output
  "The text of path's last OUTPUT-MAX-BYTES bytes (all of it when shorter),
  or \"\" when it can't be read. A character cut at the start is before any
  marker, so it doesn't matter. Never throws."
  [path]
  (try
    (with-open [f (java.io.RandomAccessFile. (str path) "r")]
      (let [n (.length f)
            from (max 0 (- n OUTPUT-MAX-BYTES))
            buf (byte-array (- n from))]
        (.seek f from)
        (.readFully f buf)
        (String. buf "UTF-8")))
    (catch Throwable _ "")))

(defn- tail-lines [out n]
  (str/join "\n" (take-last n (remove str/blank? (str/split-lines (or out ""))))))

(defn parse-result
  "run-protocol's result from a child that printed out (its stdout and
  stderr together, or nil) and exited with exit, or was stopped at the
  deadline (timed-out?; deadline-ms, CHECK-DEADLINE-MS unless given):
  {:ok? :problems :warnings :timings :plugin :definition nil}. Reads the last marker pair, so a pair the candidate
  printed before the checker's own loses. Every other outcome is a failed
  check with one sentence. Never throws.

  The result is advisory (review I1): the candidate runs in the same
  process as the checker, so it can print a pair of its own and exit, or
  rebind the checker's vars, and a forged pass parses like a real one. It
  keeps agent code out of Jonquil until Accept; it can't vouch for what
  the code is. Accept runs the check again in process (P9), and that's
  the result that counts."
  [{:keys [out exit timed-out? deadline-ms]}]
  (if timed-out?
    (failed (str "the check didn't finish in " (quot (or deadline-ms CHECK-DEADLINE-MS) 1000) " s, so it was stopped"))
    (if-let [body (last-pair out)]
      (let [v (try (edn/read-string body) (catch Throwable _ ::unreadable))]
        (cond
          (not (result? v))
          (failed (str "the check printed a result that isn't one: " (subs body 0 (min 200 (count body)))))
          (not= 0 exit)
          (failed (str "the check exited with " exit " after printing its result"))
          :else
          (merge {:problems [] :warnings [] :timings {} :plugin nil}
                 (select-keys v [:ok? :problems :warnings :timings :plugin])
                 {:definition nil})))
      (failed (str "the check printed no result (exit " exit "): " (tail-lines out 3))))))

(defn jolt-program
  "jolt's absolute path on the login PATH (the lookup the out-of-process
  Jolt runtime uses), or nil."
  []
  (tool-path/resolve-program "jolt" (login-path/path) login-path/executable-file?))

(def TERM-GRACE-MS
  ;; estimate: as nrepl.child/term-grace-ms, SIGTERM ended a looping child
  ;; and one with a hung shutdown hook within it (probed, P8b)
  1000)

(defn- stop!
  "Ends proc and what it started: the descendants are taken first, since
  after the child dies they're reparented and a candidate's own process
  (probed: a sleep it started) would outlive it. SIGTERM, then SIGKILL
  after TERM-GRACE-MS. Returns the exit code, or nil."
  [proc]
  (let [kids (try (vec (iterator-seq (.iterator (.descendants (.toHandle ^java.lang.Process (:proc proc))))))
                  (catch Throwable _ []))]
    (p/destroy proc)
    (let [r (deref proc TERM-GRACE-MS ::alive)
          exit (if (= ::alive r)
                 (do (.destroyForcibly ^java.lang.Process (:proc proc))
                     (:exit (deref proc TERM-GRACE-MS nil)))
                 (:exit r))]
      (doseq [^java.lang.ProcessHandle k kids]
        (try (when (.isAlive k) (.destroyForcibly k)) (catch Throwable _ nil)))
      exit)))

(defn- delete-tree! [f]
  (doseq [x (reverse (file-seq (io/file f)))] (.delete ^java.io.File x)))

(defn- reported?
  "Whether out holds a marker pair that is a result and the child exited
  0: the checker reported, so its problems say everything and the folder
  isn't worth keeping."
  [out exit]
  (boolean (and (= 0 exit)
                (when-let [body (last-pair out)]
                  (result? (try (edn/read-string body) (catch Throwable _ nil)))))))

(defn- env-for-child []
  (let [path (login-path/path)]
    (cond-> (scrub-env (into {} (System/getenv)))
      path (assoc "PATH" path))))

(defn check!
  "Runs the isolated check of source (file-id's plugin) against request in
  a throwaway jolt: {:ok? :problems :warnings :timings :plugin :definition
  nil}, plus :log when the child misbehaved (overran, printed no result or
  garbage, exited non-zero) and its folder was kept for a post-mortem.
  With no jolt (program nil and none on the login PATH) nothing is written
  and the result is {:ok? false :unchecked? true ...}. The folder is a
  fresh owner-only one under temp-base (java.io.tmpdir unless given), the
  child's environment is scrub-env's, and it's stopped with what it
  started at deadline-ms (CHECK-DEADLINE-MS unless given). The result is
  advisory (see parse-result). Never throws."
  [{:keys [source file-id request program cache-base temp-base deadline-ms]}]
  (if-let [program (or program (jolt-program))]
    (let [deadline-ms (or deadline-ms CHECK-DEADLINE-MS)
          root (try (checker-files/ensure-root!
                     (or cache-base (checker-files/cache-base (System/getenv) (System/getProperty "user.home"))))
                    (catch Throwable t {:error (str "the checker's files couldn't be written: " (ex-message t))}))]
      (if-let [e (:error root)]
        (failed e)
        (let [_ (leftovers/prune!)
              dir (.getCanonicalPath
                   (.toFile (java.nio.file.Files/createTempDirectory
                             (.toPath (io/file (or temp-base (System/getProperty "java.io.tmpdir"))))
                             "jonquil-plugin-check-"
                             (make-array java.nio.file.attribute.FileAttribute 0))))
              log (str dir "/check.log")
              proc (atom nil)
              keep? (atom false)]
          (try
            (spit (io/file dir "request.edn") (request-text {:request request :file-id file-id}))
            (spit (io/file dir "candidate.clj") source)
            (let [spawned (try (p/process {:dir dir :env (env-for-child) :out (io/file log) :err :out}
                                          program "-Sdeps"
                                          (str "{:deps {" checker-files/lib " {:local/root " (pr-str root) "}}}")
                                          "-e" "(require 'jonquil.plugin-checker) (jonquil.plugin-checker/-main)")
                               (catch Throwable t {:spawn-error (str "couldn't start jolt: " (ex-message t))}))]
              (if-let [e (:spawn-error spawned)]
                (failed e)
                (do
                  (reset! proc spawned)
                  (leftovers/write-pid! dir (.pid ^java.lang.Process (:proc spawned)))
                  (let [r (deref spawned deadline-ms ::overrun)
                        timed-out? (= ::overrun r)
                        exit (if timed-out? (stop! spawned) (:exit r))
                        _ (reset! proc nil)
                        out (read-output log)
                        result (parse-result {:out out :exit exit :timed-out? timed-out? :deadline-ms deadline-ms})]
                    (if (or timed-out? (not (reported? out exit)))
                      (do (reset! keep? true) (assoc result :log log))
                      result)))))
            (catch Throwable t
              (failed (str "the isolated check couldn't run: " (ex-message t))))
            (finally
              (when-let [pr @proc] (try (stop! pr) (catch Throwable _ nil)))
              (when-not @keep? (delete-tree! dir)))))))
    {:ok? false :unchecked? true :problems ["there's no jolt to check it in, so it wasn't checked"]
     :warnings [] :timings {} :plugin nil :definition nil}))
