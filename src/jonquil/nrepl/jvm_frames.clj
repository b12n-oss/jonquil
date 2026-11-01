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
  ;; value) puts back o3 o2 o1, and the value is o1. Frames stop at the
  ;; REPL's own Compiler/eval, keep Clojure fns (a $ in the class, not
  ;; clojure.lang.*), one per fn (invokeStatic and invoke are two elements).
  "(do
  (clojure.core/require 'clojure.string 'clojure.java.io)
  (clojure.core/let [o1 clojure.core/*1 o2 clojure.core/*2 o3 clojure.core/*3
        data (try
               (clojure.core/let [e clojure.core/*e]
                 (if (clojure.core/nil? e)
                   {:note \"No error yet.\"}
                   (clojure.core/let
                     [source-of (clojure.core/fn [cls]
                                  (clojure.core/let [base (.replace ^String (clojure.core/first (clojure.string/split cls #\"\\$\")) \".\" \"/\")]
                                    (clojure.core/some-> (clojure.core/or (clojure.java.io/resource (clojure.core/str base \".clj\"))
                                                                          (clojure.java.io/resource (clojure.core/str base \".cljc\")))
                                                         clojure.core/str)))
                      frames-of (clojure.core/fn [^Throwable t]
                                  (clojure.core/->> (.getStackTrace t)
                                    (clojure.core/take-while (clojure.core/fn [^StackTraceElement el]
                                                               (clojure.core/not (clojure.core/and (clojure.core/= \"clojure.lang.Compiler\" (.getClassName el))
                                                                                                   (clojure.core/= \"eval\" (.getMethodName el))))))
                                    (clojure.core/filter (clojure.core/fn [^StackTraceElement el]
                                                           (clojure.core/let [c (.getClassName el)]
                                                             (clojure.core/and (clojure.string/includes? c \"$\")
                                                                               (clojure.core/not (clojure.string/starts-with? c \"clojure.lang.\"))))))
                                    (clojure.core/partition-by (clojure.core/fn [^StackTraceElement el] (.getClassName el)))
                                    (clojure.core/map clojure.core/first)
                                    (clojure.core/mapv (clojure.core/fn [^StackTraceElement el]
                                                         (clojure.core/let [c (.getClassName el) l (.getLineNumber el)]
                                                           {:fn (clojure.lang.Compiler/demunge c) :file (source-of c)
                                                            :line (clojure.core/when (clojure.core/pos? l) l) :locals []})))))
                      fr (frames-of e)
                      fr (if (clojure.core/and (clojure.core/empty? fr) (.getCause ^Throwable e)) (frames-of (.getCause ^Throwable e)) fr)
                      d (clojure.core/ex-data e)
                      title (clojure.core/str (.getSimpleName (clojure.core/class e)) \": \" (clojure.core/or (.getMessage ^Throwable e) \"\")
                                              (clojure.core/when d (clojure.core/str \" \" (clojure.core/binding [clojure.core/*print-length* 8 clojure.core/*print-level* 3] (clojure.core/pr-str d)))))
                      base {:title title :frames fr :error-id (clojure.core/str \"jvm:\" (System/identityHashCode e))}]
                     (if (clojure.core/empty? fr)
                       (clojure.core/assoc base :note \"No frames in your code: the error came from the REPL's own reading or compiling.\")
                       base))))
               (catch Throwable t {:error (clojure.core/str \"Frames: \" (clojure.core/or (.getMessage t) (clojure.core/str (clojure.core/class t))))}))]
    (set! clojure.core/*2 o3)
    (set! clojure.core/*1 o2)
    (clojure.core/print (clojure.core/str \"\\n\" TOKEN (clojure.core/binding [clojure.core/*print-length* nil clojure.core/*print-level* nil] (clojure.core/pr-str data))))
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
