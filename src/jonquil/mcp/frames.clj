(ns jonquil.mcp.frames
  "The last error's frames for an agent (UF5): jonquil_frames' definition
  and answers, and the answers of jonquil_eval with a frame. Pure: the
  headless table (jonquil.mcp.tools) and the window's (jonquil.mcp.window)
  call the evaluator's jonquil.evaluator/Frames and hand what it gives
  here. Requires no evaluator and no jonquil.gtk namespace."
  (:require [clojure.string :as str]
            [jonquil.debugger :as debugger]
            [jonquil.describe-run :as describe]
            [jonquil.mcp.result :as result]))

(defn- prop [type description] {"type" type "description" description})

(def frames-def
  {"name" "jonquil_frames"
   "description" (str "The last error's call stack in the project's runtime, innermost first: each frame's fn, "
                      "file, line and locals (short printed values), and the error's id for jonquil_eval's frame. "
                      "Reads only: the console and the Frames panel stay as they were. Jolt and JVM Clojure.")
   "inputSchema" {"type" "object"
                  "properties" {"path" (prop "string" "A file or directory selecting the project.")
                                "timeout_ms" (prop "integer" "Deadline in milliseconds. Default 30000.")}
                  "required" []}})

(def frame-prop
  {"type" "object"
   "description" (str "Run code in a frame of the last error instead, with that frame's locals bound, "
                      "in its namespace (Jolt): index and error_id from jonquil_frames.")
   "properties" {"index" (prop "integer" "The frame's index, 0 the innermost.")
                 "error_id" (prop "string" "The error's id from jonquil_frames.")}
   "required" ["index" "error_id"]})

;; --- arguments ---------------------------------------------------------------

(defn frame-arg
  "nil when args has no \"frame\", {:frame-i n :error-id s} when it is well
  formed; throws naming both parts otherwise."
  [args]
  (when (contains? args "frame")
    (let [f (get args "frame")
          i (when (map? f) (get f "index"))
          id (when (map? f) (get f "error_id"))]
      (if (and (number? i) (>= i 0) (== i (Math/floor (double i)))
               (string? id) (not (str/blank? id)))
        {:frame-i (long i) :error-id id}
        (throw (ex-info "frame needs index (a number) and error_id (from jonquil_frames)." {}))))))

;; --- jonquil_frames ----------------------------------------------------------

(def ^:private whole-value-line
  "Evaluate a local's name in its frame (jonquil_eval with frame) to see its whole value.")

(def ^:private debug-hint
  "Run the code again with debug mode on (Debug Mode in the Run menu) to keep every local.")

(def ^:private no-frames-text "No frames: the error came from code compiled without them.")

(defn- place [{:keys [file line]}]
  (cond file (str file (when line (str ":" line)))
        line (str "line " line " (no file)")
        :else "(no file)"))

(defn- frame-lines [i fr]
  (cons (str i "  " (:fn fr) "  " (place fr))
        (map (fn [{:keys [name value]}] (str "     " name " = " value)) (:locals fr))))

(defn- shown-frames
  "data's frames as the answer shows them: :locals' :text as :value, :file
  relative to the project when it is in it."
  [frames opts]
  (mapv (fn [{:keys [file locals] :as fr}]
          (assoc fr
                 :file (describe/shown-file file opts)
                 :locals (mapv (fn [{:keys [name text]}] {:name name :value text}) locals)))
        frames))

(defn- frames-data-of [title error-id frames]
  {"status" "ok" "title" title "error_id" error-id
   "frames" (vec (map-indexed (fn [i {f :fn :keys [file line locals]}]
                                {"index" i "fn" f "file" file "line" line
                                 "locals" (mapv (fn [{:keys [name value]}] {"name" name "value" value}) locals)})
                              frames))})

(defn- ok-answer [{:keys [title error-id frames]} {:keys [jvm?] :as opts}]
  (let [shown (shown-frames frames opts)
        hint (cond jvm? "JVM Clojure keeps no locals."
                   (some (comp empty? :locals) shown) debug-hint)]
    {:text (str/join "\n" (concat [title (str "error_id: " error-id)]
                                  (mapcat frame-lines (range) shown)
                                  (when hint [hint])
                                  [whole-value-line]))
     :error? false
     :data (frames-data-of title error-id shown)}))

(defn frames-answer
  "jonquil_frames' {:text :error? :data} for d, an evaluator's error-frames!
  answer: jonquil.debugger/frames-data's map, {:note s} or {:error s}.
  opts: :root and :locate (as describe-run/shown-file takes), :jvm? for
  JVM Clojure, which keeps no locals."
  [{:keys [title error-id frames note error] :as d} opts]
  (cond
    error {:text error :error? true :data {"status" "error" "error" {"message" error}}}
    (= "No error yet." note) {:text note :error? false :data {"status" "no-error"}}
    (or note (empty? frames))
    (let [text (or note no-frames-text)]
      {:text (str/join "\n" (remove nil? [title text]))
       :error? false
       :data (cond-> {"status" "no-frames"} title (assoc "title" title) error-id (assoc "error_id" error-id))})
    :else (ok-answer d opts)))

;; --- jonquil_eval with a frame -----------------------------------------------

(def ^:private stale-text "That error_id names an older error. Call jonquil_frames again.")

(defn frame-eval-answer
  "jonquil_eval's {:text :error? :data} for r, an evaluator's
  eval-in-frame! answer ({:out :text} or {:error s}, jonquil.debugger/STALE
  for an older error's id). :max-output is how many characters of output
  are kept, the last ones; :ms the wall time."
  [r {:keys [max-output ms]}]
  (cond
    (= debugger/STALE (:error r))
    {:text stale-text :error? true :data (result/eval-data {:status :stale :output "" :ms ms})}

    (:error r)
    {:text (:error r) :error? true
     :data (result/eval-data {:status :error :output "" :ms ms :error-text (:error r)})}

    :else
    (let [{:keys [output dropped]} (result/clip (:out r) max-output)
          sep (if (or (str/blank? output) (str/ends-with? output "\n")) "" "\n")]
      {:text (str (result/dropped-line dropped) output sep "=> " (:text r))
       :error? false
       :data (result/eval-data {:status :ok :output output :dropped dropped :ms ms :value (:text r)})})))

;; --- refusals ----------------------------------------------------------------

(def busy-result
  {:text "An evaluation is running. Call again when it finishes, or stop it with jonquil_stop."
   :error? true :data {"status" "busy"}})

(def paused-result
  {:text "An evaluation is paused in the Frames panel; the person at the window can Continue or Stop it."
   :error? true :data {"status" "paused"}})

(def jvm-frame-result
  {:text "JVM Clojure keeps no locals, so code can't run in a frame."
   :error? true :data {"status" "unsupported"}})

(defn unsupported-result
  "The answer for a project whose runtime has no frames."
  [sel]
  {:text (str "jonquil_frames answers for Jolt and JVM Clojure. This project is " (:name (:profile sel)) ".")
   :error? true :data {"status" "unsupported"}})

(defn frames-timeout-result
  "jonquil_frames' answer when the runtime didn't answer in timeout-ms."
  [timeout-ms]
  (let [m (str "The runtime didn't answer with its frames in " timeout-ms " ms.")]
    {:text m :error? true :data {"status" "error" "error" {"message" m}}}))

(defn frame-eval-timeout-result
  "jonquil_eval's answer for a frame evaluation past its deadline: sentence
  says what became of it, ms is the wall time."
  [sentence ms]
  {:text sentence :error? true :data (result/eval-data {:status :timeout :output "" :ms ms})})

;; --- waiting -----------------------------------------------------------------

(defn await-callback
  "Calls (start! deliver!) and waits up to timeout-ms for deliver!'s
  argument: that value, or ::timeout."
  [start! timeout-ms]
  (let [p (promise)]
    (start! (fn [v] (deliver p v)))
    (deref p timeout-ms ::timeout)))
