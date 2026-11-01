(ns jonquil.nrepl.jvm-frames
  "The last error's frames for a JVM Clojure window, built on the server:
  one eval in the user's session (where *e lives) reads the stack trace,
  names each frame with clojure.lang.Compiler/demunge and finds its source
  on the server's classpath, then prints the Frames panel's data
  (jonquil.debugger/frames-data's shape, locals empty: the JVM keeps none)
  after a token on *out*. It leaves *1 *2 *3 as they were once nREPL's own
  post-eval shift runs, and catches everything, so *e stays the user's."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def ^:private template
  ;; One top-level do: require runs before the let is compiled. History: o1
  ;; o2 o3 saved, *2 and *1 set so nREPL's shift (*3 <- *2, *2 <- *1, *1 <-
  ;; value) puts back o3 o2 o1, and the value is o1 (the request asks nREPL
  ;; not to print it: printing *1 again could rerun the user's toString and
  ;; replace *e). The title is the root cause's; the frames are the deepest
  ;; cause's that has any (nREPL wraps read and print failures, a future's
  ;; deref wraps its cause). Frames stop at the REPL's own machinery
  ;; (Compiler/eval, nrepl.*, clojure.main) and keep classes of a loaded
  ;; namespace (a fn's ns$name, or a record's or type's ns.Name), one per fn.
  "(do
  (clojure.core/require 'clojure.string 'clojure.java.io)
  (clojure.core/let [o1 clojure.core/*1 o2 clojure.core/*2 o3 clojure.core/*3
        data (try
               (clojure.core/let [e clojure.core/*e]
                 (if (clojure.core/nil? e)
                   {:note \"No error yet.\"}
                   (clojure.core/let
                     [ns-of (clojure.core/fn [^String cls]
                              (clojure.core/if-let [i (clojure.string/index-of cls \"$\")]
                                (clojure.core/subs cls 0 i)
                                (clojure.core/when-let [i (clojure.string/last-index-of cls \".\")] (clojure.core/subs cls 0 i))))
                      source-of (clojure.core/fn [n]
                                  (clojure.core/when n
                                    (clojure.core/let [base (.replace ^String n \".\" \"/\")]
                                      (clojure.core/some-> (clojure.core/or (clojure.java.io/resource (clojure.core/str base \".clj\"))
                                                                            (clojure.java.io/resource (clojure.core/str base \".cljc\")))
                                                           clojure.core/str))))
                      clojure-frame? (clojure.core/fn [^String cls]
                                       (clojure.core/let [n (ns-of cls)]
                                         (clojure.core/boolean
                                          (clojure.core/and n (clojure.core/not (clojure.string/starts-with? cls \"clojure.lang.\"))
                                                            (clojure.core/find-ns (clojure.core/symbol (clojure.lang.Compiler/demunge n)))))))
                      harness? (clojure.core/fn [^StackTraceElement el]
                                 (clojure.core/let [c (.getClassName el)]
                                   (clojure.core/or (clojure.core/and (clojure.core/= \"clojure.lang.Compiler\" c) (clojure.core/= \"eval\" (.getMethodName el)))
                                                    (clojure.string/starts-with? c \"nrepl.\")
                                                    (clojure.string/starts-with? c \"clojure.main\"))))
                      frames-of (clojure.core/fn [^Throwable t]
                                  (clojure.core/->> (.getStackTrace t)
                                    (clojure.core/take-while (clojure.core/complement harness?))
                                    (clojure.core/filter (clojure.core/fn [^StackTraceElement el] (clojure-frame? (.getClassName el))))
                                    (clojure.core/partition-by (clojure.core/fn [^StackTraceElement el] (.getClassName el)))
                                    (clojure.core/map clojure.core/first)
                                    (clojure.core/mapv (clojure.core/fn [^StackTraceElement el]
                                                         (clojure.core/let [c (.getClassName el) l (.getLineNumber el)]
                                                           {:fn (if (clojure.string/includes? c \"$\")
                                                                  (clojure.lang.Compiler/demunge c)
                                                                  (clojure.core/str (clojure.lang.Compiler/demunge c) \".\" (.getMethodName el)))
                                                            :file (source-of (ns-of c))
                                                            :line (clojure.core/when (clojure.core/pos? l) l) :locals []})))))
                      chain (clojure.core/take 20 (clojure.core/take-while clojure.core/some? (clojure.core/iterate (clojure.core/fn [^Throwable t] (.getCause t)) e)))
                      root (clojure.core/last chain)
                      deepest (clojure.core/last (clojure.core/filter (clojure.core/fn [t] (clojure.core/seq (frames-of t))) chain))
                      fr (if deepest (frames-of deepest) [])
                      cls-name (clojure.core/let [k (clojure.core/class root) s (.getSimpleName k)] (if (clojure.core/= \"\" s) (.getName k) s))
                      d (clojure.core/ex-data root)
                      title (clojure.core/str cls-name \": \" (clojure.core/or (.getMessage ^Throwable root) \"\")
                                              (clojure.core/when d (clojure.core/str \" \" (clojure.core/binding [clojure.core/*print-length* 8 clojure.core/*print-level* 3] (clojure.core/pr-str d)))))
                      base {:title title :frames fr :error-id (clojure.core/str \"jvm:\" (System/identityHashCode e))}]
                     (if (clojure.core/empty? fr)
                       (clojure.core/assoc base :note \"No frames in your code: the error came from the REPL's own reading or compiling.\")
                       base))))
               (catch Throwable t {:error (clojure.core/str \"Frames: \" (clojure.core/or (.getMessage t) (clojure.core/str (clojure.core/class t))))}))]
    (set! clojure.core/*2 o3)
    (set! clojure.core/*1 o2)
    (clojure.core/print (clojure.core/str \"\\n\" TOKEN
                                          (clojure.core/binding [clojure.core/*print-length* nil clojure.core/*print-level* nil
                                                                 clojure.core/*print-dup* false clojure.core/*print-readably* true
                                                                 clojure.core/*print-meta* false]
                                            (clojure.core/pr-str data))))
    (clojure.core/flush)
    o1))")

(defn code
  "The eval's code, with token marking where its data starts on *out*."
  [token]
  (str/replace template "TOKEN" (pr-str token)))

(defn parse
  "The data printed after the last token in out, or an error map."
  [out token]
  (let [i (when out (str/last-index-of out token))
        d (when i (try (edn/read-string (subs out (+ i (count token)))) (catch Throwable _ nil)))]
    (if (map? d) d {:error "The server didn't send the frames."})))
