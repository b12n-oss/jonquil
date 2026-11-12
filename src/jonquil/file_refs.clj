(ns jonquil.file-refs
  "file:line references in console text (LM1's file:line kind), pure: where
  one is in a line, the namespace a stack frame names, and which project
  file a reference means. jonquil.gtk.parts.live-results opens the place a
  click or the console's Open This File and Line command lands on."
  (:require [clojure.string :as str]))

(def ^:private ref-re
  ;; a Clojure-family file name, with or without folders (or a drive), then
  ;; :line and an optional :column, as runtimes print them in errors and
  ;; stack frames: src/a/core.clj:12:3, (core.clj:12), /tmp/a.cljc:4:1
  #"((?:[A-Za-z]:)?[\w.~/\\-]*?[\w-]+\.(?:clj|cljc|cljs|cljd|jank|bb|edn)):(\d+)(?::(\d+))?")

(defn ref-at
  "The file:line reference in line that col (0-based) falls in, as {:file
  :line :col :start :end} (:col nil when none was printed), or nil."
  [^String line col]
  (let [m (re-matcher ref-re line)]
    (loop []
      (when (.find m)
        (let [s (.start m) e (.end m)]
          (if (and (<= s col) (< col e))
            {:file (.group m 1)
             :line (parse-long (.group m 2))
             :col (some-> (.group m 3) parse-long)
             :start s :end e}
            (recur)))))))

(defn ns-hint
  "The namespace a JVM stack frame in line names, as a path without its
  extension (demo.core$f.invoke gives demo/core, my-app.util gives
  my_app/util), or nil."
  [line]
  (when-let [[_ ns'] (re-find #"([a-z][\w.-]*)\$" line)]
    (-> ns' (str/replace "." "/") (str/replace "-" "_"))))

(defn- strip-ext [p] (str/replace p #"\.[^./]+$" ""))

(defn resolve-file
  "Which file a reference's file text means: {:path p}, {:candidates [p
  ...]} when several project files could be it, or {:none true}. opts is
  {:root :files :exists? :ns-hint}: the project folder, its files
  (canonical paths), a file-exists check and ns-hint's answer for the
  line. An absolute path that exists is itself; a path under root is
  that; else the project files ending in /file, narrowed by ns-hint when
  several do."
  [file {:keys [root files exists? ns-hint]}]
  (let [file (str/replace file "\\" "/")
        under-root (when root (str root "/" file))
        suffix (str "/" (str/replace file #"^\./" ""))
        cands (vec (filter #(str/ends-with? % suffix) files))
        hinted (when ns-hint (filterv #(str/ends-with? (strip-ext %) (str "/" ns-hint)) cands))]
    (cond
      (str/starts-with? file "/") (if (exists? file) {:path file} {:none true})
      (and under-root (exists? under-root)) {:path under-root}
      (= 1 (count cands)) {:path (first cands)}
      (= 1 (count hinted)) {:path (first hinted)}
      (seq cands) {:candidates cands}
      :else {:none true})))
