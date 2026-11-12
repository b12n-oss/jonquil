(ns jonquil.file-refs
  "file:line references in console text (LM1's file:line kind), pure: where
  one is in a line, the namespace a stack frame names, and which project
  file a reference means. jonquil.gtk.parts.live-results opens the place a
  click or the console's Open This File and Line command lands on."
  (:require [clojure.string :as str]))

(def ^:private ref-re
  ;; a Clojure-family file name, with or without folders (or a drive), then
  ;; :line and an optional :column, as runtimes print them in errors and
  ;; stack frames: src/a/core.clj:12:3, (core.clj:12), /tmp/a.cljc:4:1.
  ;; A match starts only where a path can (not mid-word, so file:x.clj:3
  ;; is x.clj, not e:x.clj), and the greedy body backtracks once per start,
  ;; so a long unbroken line stays linear.
  #"(?<![\w.~/\\-])((?:[A-Za-z]:(?=[\\/]))?[\w.~/\\-]*[\w-]\.(?:clj|cljc|cljs|cljd|jank|bb|edn)):(\d+)(?::(\d+))?")

(def ^:private WINDOW
  ;; how far either side of the click a reference is looked for: a path
  ;; and line fit, and a megabyte-long line costs no more than a short one
  300)

(defn- url-path?
  "Whether the match starting at s in line is the path part of a URL
  (https://host/x.clj:80), not a file."
  [^String line s]
  (and (>= s 1) (= \: (.charAt line (int (dec s)))) (str/starts-with? (subs line s) "//")))

(defn ref-at
  "The file:line reference in line that col (0-based) falls in, as {:file
  :line :col :start :end} (:col nil when none was printed), or nil. Only
  the text within WINDOW characters of col is read."
  [^String line col]
  (let [from (max 0 (- col WINDOW))
        to (min (count line) (+ col WINDOW))
        part (subs line from to)
        c (- col from)]
    (when (re-find #":\d" part)
      (let [m (re-matcher ref-re part)]
        (loop []
          (when (.find m)
            (let [s (.start m) e (.end m)]
              (cond
                (> s c) nil
                (and (< c e) (not (url-path? part s)))
                {:file (.group m 1)
                 :line (parse-long (.group m 2))
                 :col (some-> (.group m 3) parse-long)
                 :start (+ from s) :end (+ from e)}
                :else (recur)))))))))

(defn ns-hint
  "The namespace a JVM stack frame in line names, as a path without its
  extension (demo.core$f.invoke gives demo/core, my-app.util gives
  my_app/util), or nil. Only a name that starts a word counts."
  [line]
  (when-let [[_ ns'] (re-find #"(?<![\w.$])([a-z][\w.-]*)\$" line)]
    (-> ns' (str/replace "." "/") (str/replace "-" "_"))))

(defn- strip-ext [p] (str/replace p #"\.[^./]+$" ""))

(defn- absolute? [p] (boolean (re-find #"^(?:/|[A-Za-z]:/)" p)))

(defn- ending-with
  "The files that end with /suffix."
  [suffix files]
  (filterv #(str/ends-with? % (str "/" suffix)) files))

(defn- tail-candidates
  "For an absolute path that doesn't exist here (one printed on another
  machine): the project files ending with its longest tail of at least two
  segments (src/a.clj, never just a.clj, which would be a guess)."
  [path files]
  (let [segs (vec (remove str/blank? (str/split path #"/")))]
    (or (some (fn [i]
                (let [cs (ending-with (str/join "/" (subvec segs i)) files)]
                  (when (seq cs) cs)))
              (range (max 0 (- (count segs) 1))))
        [])))

(defn resolve-file
  "Which file a reference's file text means: {:path p}, {:candidates [p
  ...]} when several project files could be it, or {:none true}. opts is
  {:root :files :exists? :ns-hint}: the project folder, its files
  (canonical paths, or a delay of them, walked only when needed), a
  file-exists check and ns-hint's answer for the line. An absolute path
  that exists is itself, and a path under root is that; else the project
  files ending with all of a relative path, or with the longest tail of at
  least two segments of an absolute one (so a path from another machine
  finds the project's own file), narrowed by ns-hint when several do."
  [file {:keys [root files exists? ns-hint]}]
  (let [file (str/replace (str/replace file "\\" "/") #"^\./" "")
        under-root (when (and root (not (absolute? file))) (str root "/" file))]
    (cond
      (and (absolute? file) (exists? file)) {:path file}
      (and under-root (exists? under-root)) {:path under-root}
      :else
      (let [cands (if (absolute? file)
                    (tail-candidates file (force files))
                    ;; relative: the files ending with all of it, so
                    ;; clojure/core.clj never opens the project's own core.clj
                    (ending-with file (force files)))
            hinted (when ns-hint (filterv #(str/ends-with? (strip-ext %) (str "/" ns-hint)) cands))]
        (cond
          (= 1 (count cands)) {:path (first cands)}
          (= 1 (count hinted)) {:path (first hinted)}
          (seq cands) {:candidates cands}
          :else {:none true})))))
