(ns jonquil.live.plugin-isolated-live-test
  "The isolated check against real jolt children: each test runs check! on
  a candidate, with the checker's files and the check folder under temp
  folders. Run with `jolt test:plugins`; it skips when no jolt is on the
  login PATH."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [jonquil.plugin-history :as history]
            [jonquil.plugin-isolated :as isolated]
            [jonquil.plugin-list :as plugin-list]
            [jonquil.plugin-loader :as loader]
            [jonquil.plugin-registry :as reg]
            [temp-dirs]))

(use-fixtures :once temp-dirs/fixture)

(defn- source
  "A plugin file for id: one :edit command, bound to Cmd+Alt+J or to
  command, with top-level forms extra before the defplugin."
  [id & {:keys [command extra] :or {extra ""}}]
  (str "(ns jonquil.plugins." id "\n  (:require [jonquil.plugin :as p]))\n" extra "\n"
       "(p/defplugin\n"
       "  {:id :" id " :doc \"A test plugin.\" :effects #{:console}\n"
       "   :commands [{:id :" id "/wrap :doc \"Wrap it.\" :scope :editor\n"
       "               :edit (fn [s] (p/wrap-form s \"(when \" \")\"))}]\n"
       "   :bindings [{:key \"j\" :mods #{:cmd :alt} :command " (or command (str ":" id "/wrap")) "}]})\n"))

(def ^:private cache (delay (temp-dirs/temp-dir! "jonquil-isolated-cache-")))

(defn- check
  "check! of id's source in a fresh temp-base: [result temp-base]."
  [id text & {:as more}]
  (let [tmp (temp-dirs/temp-dir! "jonquil-isolated-live-")]
    [(isolated/check! (merge {:source text :file-id id :cache-base @cache :temp-base tmp
                              :request (#'loader/request-for (reg/empty-registry) id "Linux")}
                             more))
     tmp]))

(defn- check-folders [base]
  (filter #(str/starts-with? % "jonquil-plugin-check-") (.list (io/file base))))

(defn- gone?
  "Whether pid is not running within 2 s."
  [pid]
  (loop [n 40]
    (let [h (java.lang.ProcessHandle/of pid)]
      (cond (not (and (.isPresent h) (.isAlive ^java.lang.ProcessHandle (.get h)))) true
            (zero? n) false
            :else (do (Thread/sleep 50) (recur (dec n)))))))

(deftest good-plugin-passes-test
  (let [[r tmp] (check "lgood" (source "lgood"))]
    (is (true? (:ok? r)) (pr-str r))
    (is (= :lgood (get-in r [:plugin :id])))
    (is (nil? (:log r)))
    (is (empty? (check-folders tmp)))))

(deftest a-plugin-with-mcp-tools-passes-test
  ;; the real child's summary carries :mcp-tools, and it's a result
  (let [text (str/replace (source "ltools") "   :commands"
                          "   :mcp-tools [{:name \"echo\" :doc \"Echo it.\" :run (fn [_ a] \"x\")}]\n   :commands")
        [r _] (check "ltools" text)]
    (is (:ok? r) (pr-str (:problems r)))
    (is (= [{:name "echo" :doc "Echo it."}] (get-in r [:plugin :mcp-tools])))))

(deftest bad-plugin-fails-with-its-problem-test
  (let [[r tmp] (check "lbad" (source "lbad" :command ":nope/x"))]
    (is (false? (:ok? r)))
    (is (some #(str/includes? % ":nope/x") (:problems r)) (pr-str r))
    (is (nil? (:log r)) "the checker reported, so nothing is kept")
    (is (empty? (check-folders tmp)))))

(deftest top-level-loop-is-killed-at-the-deadline-test
  (let [[r _] (check "lloop" (source "lloop" :extra (str "(spit \"kid.pid\" (str (.pid (.start (ProcessBuilder. [\"sleep\" \"300\"])))))\n"
                                                         "(loop [] (recur))"))
                     :deadline-ms 3000)
        dir (some-> (:log r) io/file .getParentFile)]
    (is (= ["the check didn't finish in 3 s, so it was stopped"] (:problems r)))
    (is (and dir (.exists (io/file (:log r)))) (pr-str r))
    (when dir
      (is (gone? (parse-long (str/trim (slurp (io/file dir "pid"))))) "the child")
      (is (gone? (parse-long (str/trim (slurp (io/file dir "kid.pid"))))) "what it started"))))

(deftest an-overrun-proposal-records-its-log-test
  (let [tmp (temp-dirs/temp-dir! "jonquil-isolated-live-")
        d (temp-dirs/temp-dir! "jonquil-isolated-plugins-")
        text (source "lrec" :extra "(loop [] (recur))")
        result (loader/propose-check {:registry (reg/empty-registry) :id "lrec" :source text :os "Linux"
                                      :check #(isolated/check! (assoc % :cache-base @cache :temp-base tmp
                                                                      :deadline-ms 3000))})
        r (loader/propose-step! {:dir d :os "Linux" :disabled #{} :id "lrec" :source text :reason "tidy"
                                 :result result})
        entries (:entries (history/read-entries (history/history-dir d "lrec")))
        log (:check-log (first entries))]
    (is (= :rejected-by-checks (:status r)) (pr-str r))
    (is (and log (.isFile (io/file log))) (pr-str (first entries)))
    (is (= log (:log r)))
    (is (str/ends-with? (plugin-list/history-line entries 1) (str "; check log: " log)))))

(deftest junk-output-doesnt-hide-the-result-test
  (let [[r _] (check "ljunk" (source "ljunk" :extra "(println \"junk\")\n(println \"__JONQUIL_CHECK__\")\n(println \"{:not\")"))]
    (is (true? (:ok? r)) (pr-str r))))

(deftest the-code-runs-in-the-child-not-here-test
  (let [marker (io/file (temp-dirs/temp-dir! "jonquil-isolated-marker-") "ran.txt")
        [r _] (check "lmark" (source "lmark" :extra (str "(spit " (pr-str (.getPath marker)) " \"ran\")\n(def leaked 1)")))]
    (is (true? (:ok? r)) (pr-str r))
    (is (= "ran" (slurp marker)))
    (is (nil? (find-ns 'jonquil.plugins.lmark)))))

(deftest a-hook-cant-start-a-process-at-the-kill-test
  ;; a shutdown hook SIGTERM would run starts a process after
  ;; the descendants were taken; the overrun kill gives it no chance
  (let [[r _] (check "lhook" (source "lhook" :extra (str "(.addShutdownHook (Runtime/getRuntime) (Thread. (fn [] (spit \"late.pid\" (str (.pid (.start (ProcessBuilder. [\"sleep\" \"300\"]))))))))\n"
                                                         "(loop [] (recur))"))
                     :deadline-ms 3000)
        late (some-> (:log r) io/file .getParentFile (io/file "late.pid"))]
    (is (:log r) (pr-str r))
    (is (or (nil? late) (not (.exists late)) (gone? (parse-long (str/trim (slurp late)))))
        "the hook's process isn't left running")))

(deftest a-kept-log-is-cut-to-its-tail-test
  ;; a candidate printing until the deadline doesn't leave a log
  ;; of hundreds of MB behind for a day
  (let [[r _] (check "lspam" (source "lspam" :extra "(let [l (apply str (repeat 1000 \"x\"))] (loop [] (println l) (recur)))")
                     :deadline-ms 3000)]
    (is (:log r) (pr-str r))
    (is (<= (.length (io/file (:log r))) isolated/OUTPUT-MAX-BYTES))))
