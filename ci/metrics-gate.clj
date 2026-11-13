;; SPDX-License-Identifier: EPL-2.0
;;
;; Adapted from jolt-lang/jolt's ci/metrics-gate.clj, so this file, unlike the
;; rest of Jonquil (EPL-1.0), is under the Eclipse Public License 2.0, as
;; Jolt's is. Jonquil's additions: it refuses an allowlist without
;; :critical-free or a report missing the sections it reads (either would
;; gate nothing and pass), requires a :why on every allowlist entry, compares
;; a required lib as text, and names an unused require's refers.
;;
;;   jolt ci/metrics-gate.clj <report.edn> <ci/metrics-allow.edn>
;;
;; The report is jolt-metrics' --format edn output. Two checks:
;;
;; * Critical findings. Under each path prefix in :critical-free, a CRITICAL
;;   finding in the refactoring queue fails the gate (a function or file past
;;   a hard limit: complexity, length, nesting, parameters, coupling or
;;   maintainability; in a test path only helpers and the file are queued,
;;   not deftest bodies), and so does a file jolt-metrics couldn't read. There
;;   is no allowlist for these: fix the code.
;;
;; * Unused code. The allowlist names each unused require, unused private
;;   function and unreferenced public function that stays on purpose, with a
;;   :why. A finding not on it fails the gate, and so does an entry that no
;;   longer matches a finding, so the list can't go stale.
(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(def ^:private kinds [:unused-requires :unused-private :unused-public])

(defn- finding-key [kind f]
  (case kind
    :unused-requires [(:path f) (str (:lib f))]
    (:unused-private :unused-public) [(:path f) (str (:name f))]))

(defn- under? [prefixes path]
  (some #(str/starts-with? (str path) %) prefixes))

(defn- setup-problems
  "What would make the gate check nothing and pass: an allowlist without
  :critical-free prefixes, or a report missing a section the gate reads."
  [report allow allow-path]
  (concat
   (let [cf (:critical-free allow)]
     (when-not (and (vector? cf) (seq cf) (every? string? cf))
       [(str allow-path " has no :critical-free vector of path prefixes, so nothing would be gated")]))
   (when-not (and (map? report) (contains? report :queue) (contains? report :errors) (contains? report :cleanup))
     ["the report isn't a jolt-metrics report (no :queue, :errors or :cleanup), so nothing would be checked"])))

(defn- critical-problems [report prefixes]
  (concat
   (for [f (:queue report)
         :when (and (= :critical (:severity f)) (under? prefixes (:path f)))]
     (str "critical " (name (or (:kind f) :finding)) " " (:path f) ":" (:line f) " " (:name f) ": "
          (str/join ", " (for [v (:violations f) :when (= :critical (:band v))]
                           (str (name (:rule v)) " " (:value v) " > " (:limit v))))))
   (for [e (:errors report) :when (under? prefixes (:path e))]
     (str "unreadable " (:path e) ": " (:error e)))))

(defn- unused-problems [cleanup allow allow-path]
  (for [kind kinds
        :let [found (into {} (map (fn [f] [(finding-key kind f) f]) (get cleanup kind)))
              allowed (into {} (map (fn [a] [(finding-key kind a) a]) (get allow kind)))]
        p (concat
           (for [[k f] (sort-by key found) :when (not (contains? allowed k))]
             (str "new " (name kind) ": " (str/join " " k)
                  (when (seq (:refers f)) (str " (refers " (str/join ", " (:refers f)) ")"))
                  ": remove it, or add it to " allow-path " with the :why it stays"))
           (for [[k _] (sort-by key allowed) :when (not (contains? found k))]
             (str "stale " (name kind) " entry " (str/join " " k)
                  ": it is no longer reported; remove it from " allow-path))
           (for [[k a] (sort-by key allowed) :when (str/blank? (str (:why a)))]
             (str (name kind) " entry " (str/join " " k) " in " allow-path " has no :why")))]
    p))

(defn -main [report-path allow-path]
  (let [report (edn/read-string (slurp report-path))
        allow (edn/read-string (slurp allow-path))
        setup (setup-problems report allow allow-path)
        problems (if (seq setup)
                   setup
                   (concat (critical-problems report (:critical-free allow))
                           (unused-problems (:cleanup report) allow allow-path)))]
    (doseq [p problems] (println p))
    (if (seq problems)
      (do (println (str "metrics gate: " (count problems) " problem(s)")) (System/exit 1))
      (println (str "metrics gate: no critical findings under "
                    (str/join ", " (:critical-free allow))
                    " and no unused code beyond the allowlist")))))

(apply -main *command-line-args*)
