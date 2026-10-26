(ns jonquil.plugin-isolated
  "The parent's half of an isolated check (plugins design, Part 2, \"The
  isolated check\"; slice P8), pure: the request.edn text the child reads,
  the environment it gets, and parse-result, which turns what the child
  printed into run-protocol's result. Starting and stopping the child is
  slice P8b."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [jonquil.plugin-checker :as checker]))

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
