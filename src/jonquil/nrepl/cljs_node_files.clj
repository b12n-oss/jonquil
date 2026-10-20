(ns jonquil.nrepl.cljs-node-files
  "The launcher the ClojureScript-on-node child runs, carried inside Jonquil
  as text and written to a cache folder the child loads it from. Embedded at
  compile time for the same reason as jonquil.nrepl.middleware-files: the
  installed app has no source tree."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defmacro ^:private embedded-text
  "The text of classpath file path, read when this form is compiled."
  [path]
  (slurp (io/resource path)))

(def launcher-text
  "The source of jonquil.cljs-node-launch (JVM Clojure, never loaded by Jolt)."
  (embedded-text "jonquil/cljs_node_launch.clj"))

(defn cache-base
  "$XDG_CACHE_HOME/jonquil/cljs-node, or ~/.cache/jonquil/cljs-node when that
  is unset, blank or relative (the XDG rule middleware-files follows)."
  [env home]
  (let [xdg (get env "XDG_CACHE_HOME")]
    (str (if (and xdg (str/starts-with? xdg "/")) xdg (str home "/.cache"))
         "/jonquil/cljs-node")))

(defn- folder-name []
  (format "%08x" (bit-and (hash launcher-text) 0xffffffff)))

(defn ensure-launcher!
  "The absolute path of the launcher file under base, written first when it
  isn't there or its text differs. It goes into a temp sibling that is then
  renamed over the target, so the child never reads half a file, and an
  identical file is left alone. The folder is named by a hash of the text and
  the file sits in jonquil/, a folder named for its namespace. Throws when
  it can't be written."
  ([] (ensure-launcher! (cache-base (System/getenv) (System/getProperty "user.home"))))
  ([base]
   (let [dir (io/file base (folder-name) "jonquil")
         f (io/file dir "cljs_node_launch.clj")]
     (when-not (and (.exists f) (= launcher-text (slurp f)))
       (try
         (io/make-parents f)
         (let [tmp (io/file dir (str "cljs_node_launch.clj.tmp-" (System/nanoTime)))]
           (try
             (spit tmp launcher-text)
             (when-not (.renameTo tmp f)
               (throw (ex-info "rename failed" {})))
             (finally (.delete tmp))))
         (catch Exception e
           (throw (ex-info (str "couldn't write " (.getPath f)) {:path (.getPath f)} e)))))
     (.getCanonicalPath f))))
