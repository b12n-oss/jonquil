(ns jonquil.debugger
  "The last error's frames and locals, as data for the Frames panel. Jolt's
  jolt.debug/frames reads them from a throwable even after it was caught,
  innermost first, as {:fn :file :line :locals}. A Jolt without jolt.debug
  has no frames, so every use goes through frames-fn, looked up when
  called. The out-of-process Jolt child loads this namespace too, from
  Jonquil's embedded middleware files, so it requires nothing but
  clojure.string. No GTK here."
  (:require [clojure.string :as str]))

(def ^:private TEXT-MAX
  ;; a choice: one readable panel row
  100)

(defn frames-fn
  "jolt.debug/frames, or nil on a Jolt without it."
  []
  (try (requiring-resolve 'jolt.debug/frames) (catch Throwable _ nil)))

(defn available?
  "True when this Jolt has jolt.debug."
  []
  (some? (frames-fn)))

(defn value-text
  "v as one short line: pr-str with *print-length* 8 and *print-level* 3,
  cut to TEXT-MAX characters, or <unprintable: Class> when printing it
  throws."
  [v]
  (let [s (try (binding [*print-length* 8 *print-level* 3] (pr-str v))
               (catch Throwable _ (str "<unprintable: " (.getSimpleName (class v)) ">")))]
    (if (> (count s) TEXT-MAX) (str (subs s 0 TEXT-MAX) "…") s)))

(defn- title [e]
  (str (.getSimpleName (class e)) ": " (ex-message e)
       (when-let [d (ex-data e)] (str " " (value-text d)))))

(def ^:private NO-API "Frames need a Jolt with jolt.debug (newer than this one).")

(defn frames-data
  "e's frames as {:title :frames [{:fn :file :line :locals [{:name :text}]}]},
  locals sorted by name. opts: :frames-fn (jolt.debug/frames, or nil for
  no frames API, which gives a :note instead) and :resolve-file, applied to
  each frame's :file when there is one."
  [e {:keys [frames-fn resolve-file]}]
  (if-not frames-fn
    {:title (title e) :frames [] :note NO-API}
    {:title (title e)
     :frames (mapv (fn [{f :fn :keys [file line locals]}]
                     {:fn (str f)
                      :file (if (and file resolve-file) (resolve-file file) file)
                      :line line
                      :locals (->> locals
                                   (map (fn [[k v]] {:name (str k) :text (value-text v)}))
                                   (sort-by :name)
                                   vec)})
                   (frames-fn e))}))

(defn- file-name [f] (last (str/split f #"/")))

(defn rows
  "The panel's rows for data: a :frame row per frame (its fn and file:line,
  or typed at the prompt), then a :local row per local; a :note row when
  there is a note or no frames."
  [{:keys [frames note]}]
  (cond
    note [{:kind :note :text note}]
    (empty? frames) [{:kind :note :text "No frames: the error came from code compiled without them."}]
    :else
    (vec (mapcat (fn [i {f :fn :keys [file line locals]}]
                   (cons {:kind :frame :frame i
                          :text (str f "  " (if file
                                              (str (file-name file) (when line (str ":" line)))
                                              "(typed at the prompt)"))}
                         (for [{:keys [name text]} locals]
                           {:kind :local :frame i :name name :text (str "    " name " = " text)})))
                 (range) frames))))

(defn local-value
  "The live value of the local named local-name in e's frame i, or
  ::missing."
  [e i local-name {:keys [frames-fn]}]
  (if-let [fr (when frames-fn (get (vec (frames-fn e)) i))]
    (if-let [[_ v] (find (:locals fr) (symbol local-name))] v ::missing)
    ::missing))
