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
  ["TOKEN" "SECRET" "PASSWORD" "PASSWD" "CREDENTIAL" "AUTH" "COOKIE" "SESSION"])

(defn- credential-name? [n]
  (let [u (str/upper-case n)]
    (boolean (or (some #(str/includes? u %) credential-words)
                 (= "KEY" u)
                 (str/ends-with? u "_KEY")))))

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

(defn- result? [v]
  (and (map? v) (boolean? (:ok? v))
       (or (not (contains? v :problems))
           (and (vector? (:problems v)) (every? string? (:problems v))))))

(defn- tail-lines [out n]
  (str/join "\n" (take-last n (remove str/blank? (str/split-lines (or out ""))))))

(defn parse-result
  "run-protocol's result from a child that printed out (its stdout and
  stderr together, or nil) and exited with exit, or was stopped at the
  deadline (timed-out?): {:ok? :problems :warnings :timings :plugin
  :definition nil}. Reads the last marker pair, so a pair the candidate
  printed earlier loses. Every other outcome is a failed check with one
  sentence. Never throws."
  [{:keys [out exit timed-out?]}]
  (if timed-out?
    (failed (str "the check didn't finish in " (quot CHECK-DEADLINE-MS 1000) " s, so it was stopped"))
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
