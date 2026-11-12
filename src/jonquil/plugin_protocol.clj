(ns jonquil.plugin-protocol
  "The plugin protocol: every change to the live
  plugin set goes through run-protocol, which loads a candidate file,
  validates it (jonquil.plugin-check) and soaks its :edit commands on
  fixture snapshots, answering {:ok? :problems :warnings :timings
  :definition}. No GTK; nothing here goes live, and a caller (the user's
  reload, Accept, a revert) swaps the registry on a pass."
  (:require [clojure.string :as str]
            [jonquil.eval :as eval]
            [jonquil.plugin :as p]
            [jonquil.plugin-check :as plugin-check]))

(defn- load-problem
  "One sentence for a throw while loading: with its line when the reader or
  compiler gave one."
  [e]
  (let [line (:clojure.error/line (ex-data e))
        msg (or (ex-message e) (str e))]
    (if line
      (str "line " line ": " msg)
      (str "loading the file threw: " msg))))

(defn load-candidate
  "Loads source, a plugin file's text, with defplugin's collector bound and
  *ns* restored afterwards: {:definitions [...] :problems [...]}. Never
  throws; a load error is a problem with its line when it has one, and a
  file that doesn't end in jonquil.plugins.<file-id> is a problem too. The
  load runs in a future given load-ms (default 10000): a file that loops at
  top level is a problem then, so the caller goes on (it may keep running;
  future-cancel can't stop CPU-bound code). The namespace the file made
  stays loaded."
  [{:keys [source file-id load-ms] :or {load-ms 10000}}]
  (let [wanted (str "jonquil.plugins." file-id)
        f (future
            (binding [*ns* *ns* p/*definitions* (atom [])]
              ;; eval-string, not load-string: a Jolt with jolt-lang/jolt #1460
              ;; binds *ns* around load-string, and ns-now is read after it
              (let [threw (try (eval/eval-string source) nil (catch Throwable e e))
                    defs @p/*definitions*
                    ns-now (str (ns-name *ns*))]
                {:definitions defs
                 :problems (cond
                             threw [(load-problem threw)]
                             (not= wanted ns-now)
                             [(str "the file's namespace is " ns-now "; a plugin file's is " wanted)]
                             :else [])})))
        v (deref f load-ms ::timeout)]
    (if (= ::timeout v)
      (do (future-cancel f)
          {:definitions []
           :problems [(str "loading the file didn't finish in " load-ms " ms (a loop at top level, or a "
                           "slow first compile of what it requires, which the next start may get past); "
                           "it may keep running in this process until Jonquil quits")]})
      v)))

(def ^:private form-text "(defn f [x] (inc x))")

(defn- fixture [text caret & [a b]]
  {:text text :caret caret :sel-start (or a caret) :sel-end (or b caret)})

(def FIXTURES
  "The snapshots every :edit command is soaked on, [name snapshot]: the
  an empty buffer, a small form with the caret at its start, middle and
  end, a selection, and two forms (the likeliest bug: assuming one)."
  [["empty" (fixture "" 0)]
   ["form, caret at start" (fixture form-text 0)]
   ["form, caret in the middle" (fixture form-text 12)]
   ["form, caret at end" (fixture form-text (count form-text))]
   ["selection" (fixture form-text 5 1 5)]
   ["two forms" (fixture "(a)\n(b)" 4)]])

(defn- offset? [n limit] (and (integer? n) (<= 0 n limit)))

(defn- caret-problem
  "edit-result-problem's check of result's :caret and :sel against the
  edited text's length, from len: nil or one sentence."
  [{:keys [edits caret sel]} len]
  ;; the edited length arithmetically: rebuilding the text per edit is
  ;; O(edits x text), too slow on the GTK thread for a big buffer
  (let [new-len (+ len (reduce + 0 (map (fn [[a b r]] (- (count r) (- b a))) edits)))]
    (cond
      (not (offset? caret new-len)) (str ":caret " (pr-str caret) " isn't inside the edited text (0.." new-len ")")
      (and (some? sel) (not (and (vector? sel) (= 2 (count sel)) (every? #(offset? % new-len) sel))))
      (str ":sel " (pr-str sel) " isn't two offsets inside the edited text")
      :else nil)))

(defn edit-result-problem
  "nil when result is nil (the command doesn't apply here) or a well-formed
  jonquil.edit op for snapshot's text: {:edits [[start end string] ...]
  :caret n}, edits in bounds, ascending and not overlapping, the caret (and
  :sel, when given) inside the edited text. Otherwise one sentence."
  [{:keys [text]} result]
  (let [len (count text)
        edits (when (map? result) (:edits result))]
    (cond
      (nil? result) nil
      (not (map? result)) (str "returned " (pr-str result) ", not an edit op map")
      (not (and (vector? edits)
                (every? #(and (vector? %) (= 3 (count %)) (string? (nth % 2))) edits)))
      ":edits isn't a vector of [start end string]"
      ;; apply-op! refuses such an op silently (GTK C
      ;; strings would truncate the insert), so the refusal is named here,
      ;; on the loud path the author already gets.
      (some (fn [[_ _ r]] (str/includes? r "\u0000")) edits)
      "an edit's text holds a NUL byte, which Jonquil's editor can't keep"
      (not (every? (fn [[a b]] (and (offset? a len) (offset? b len) (<= a b))) edits))
      (str "an edit is outside the text (0.." len ")")
      (not (every? (fn [[[_ e1] [s2]]] (<= e1 s2)) (partition 2 1 edits)))
      "its edits overlap or aren't in ascending order"
      :else (caret-problem result len))))

(defn- throw-message [e]
  (or (some-> e .getCause ex-message) (ex-message e) (str e)))

(defn- soak-one
  "Calls edit on snap in a future, with the shape check inside it too (a
  lazy result is realized there, under the same timeout) and printing
  bounded, so even an infinite seq gets a short sentence. Answers
  [:timeout], [:threw e] or [:ok problem-or-nil] within soak-ms."
  [edit snap soak-ms]
  (let [f (future (binding [*print-length* 10 *print-level* 3]
                    (try [:ok (edit-result-problem snap (edit snap))]
                         (catch Throwable e [:threw e]))))
        v (deref f soak-ms ::timeout)]
    (if (= ::timeout v)
      (do (future-cancel f) [:timeout])
      v)))

(defn- soak-fixture
  "soak-command's step over one fixture [fname snap]: acc with the run's
  time and any problem added, reduced on a timeout so the command's other
  fixtures aren't tried."
  [acc {:keys [id edit]} soak-ms [fname snap]]
  (let [t0 (System/nanoTime)
        [kind x] (soak-one edit snap soak-ms)
        ms (quot (- (System/nanoTime) t0) 1000000)
        acc (update-in acc [:timings id] (fnil max 0) ms)
        label (str "command " (pr-str id) " on " fname)]
    (case kind
      :timeout (reduced
                (update acc :problems conj
                        (str label ": still running after " soak-ms " ms; its other fixtures weren't "
                             "tried, and it may keep running in this process until Jonquil quits")))
      :threw (update acc :problems conj (str label " threw: " (throw-message x)))
      (if x (update acc :problems conj (str label ": " x)) acc))))

(defn- soak-command
  "One :edit command over every fixture, into acc."
  [acc command soak-ms]
  (reduce #(soak-fixture %1 command soak-ms %2) acc FIXTURES))

(defn soak
  "Each :edit command of definition on each of FIXTURES, given soak-ms
  (default 2000) per fixture: {:problems [...] :timings {command-id
  max-ms}}. A throw or a bad result shape is a problem naming the command
  and the fixture. A command that doesn't answer in time is one problem,
  and its other fixtures aren't tried: it may keep running (future-cancel
  can't stop CPU-bound code, which is why an agent's proposal is soaked in
  a separate process), so each command leaves at most one such thread and
  the soak takes at most commands x soak-ms more. :run commands aren't
  invoked. Never throws."
  [{:keys [definition soak-ms] :or {soak-ms 2000}}]
  (reduce
   (fn [acc command] (soak-command acc command soak-ms))
   {:problems [] :timings {}}
   (filter #(fn? (:edit %)) (:commands definition))))

(defn run-protocol
  "The one function every change to the live plugin set goes through:
  {:ok? :problems :warnings :timings :definition}. :in-process loads
  source (load-candidate), validates it against request
  (jonquil.plugin-check/validate, with the definitions and file-id), and
  soaks it only when both found nothing wrong (load-ms and soak-ms, when
  given, are load-candidate's and soak's budgets); :definition is the plugin's
  definition on a pass. The agent's path, a throwaway Jolt process that
  runs this function :in-process, is jonquil.plugin-isolated/check!.
  Blocks for up to commands x fixtures x soak-ms, so it's called off the
  GTK thread."
  [{:keys [where source file-id request soak-ms load-ms]}]
  (case where
    :in-process
    (let [{:keys [definitions problems]} (load-candidate (cond-> {:source source :file-id file-id}
                                                           load-ms (assoc :load-ms load-ms)))
          checked (when (empty? problems)
                    (plugin-check/validate (assoc request :definitions definitions :file-id file-id)))
          problems (into problems (:problems checked))
          soaked (when (empty? problems)
                   (soak (cond-> {:definition (first definitions)} soak-ms (assoc :soak-ms soak-ms))))
          problems (into problems (:problems soaked))]
      {:ok? (empty? problems) :problems problems :warnings (vec (:warnings checked))
       :timings (or (:timings soaked) {})
       :definition (when (empty? problems) (first definitions))})))
