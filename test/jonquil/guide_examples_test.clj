(ns jonquil.guide-examples-test
  "Every clojure and edn block in the README and the guide is read by the
  code that reads the real thing: a settings.edn example by
  jonquil.settings, a keys.edn example by jonquil.user-keys, a
  .jonquil.edn example by jonquil.dialect, and a plugin by the plugin
  check. So a page can't show a file Jonquil would warn about or refuse.

  kinds says what each block is, by file and its place among that file's
  blocks. A new block fails the-table-names-every-block-test until it has
  a kind here; :shape is for one that isn't a whole file (it holds ... or
  is a fragment), with the reason beside it."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jonquil.command-registry :as registry]
            [jonquil.dialect :as dialect]
            [jonquil.plugin-loader :as loader]
            [jonquil.plugin-protocol :as protocol]
            [jonquil.plugin-registry :as plugin-registry]
            [jonquil.settings :as settings]
            [jonquil.user-keys :as user-keys]))

(def ^:private kinds
  {["docs/guide/architecture/index.md" 0] [:shape "the shape of a snapshot and an edit op, with ... in it"]
   ["docs/guide/dialects/clojurescript.md" 0] [:project]
   ["docs/guide/dialects/clojurescript.md" 1] [:project]
   ["docs/guide/dialects/index.md" 0] [:project]
   ["docs/guide/dialects/jvm-clojure.md" 0] [:project]
   ["docs/guide/dialects/other-nrepl.md" 0] [:project]
   ["docs/guide/getting-started.md" 0] [:settings]
   ["docs/guide/keys.md" 0] [:keys]
   ["docs/guide/mcp.md" 0] [:shape "one key of a defplugin map, not a file"]
   ["docs/guide/mcp.md" 1] [:settings]
   ["docs/guide/plugins.md" 0] [:plugin "wrap-when"]
   ["docs/guide/plugins.md" 1] [:plugin "shout"]
   ["docs/guide/plugins.md" 2] [:plugin "no-todo"]
   ["docs/guide/plugins.md" 3] [:settings]})

(defn- pages
  "The README and every guide page, as paths from the repository's root."
  []
  (into ["README.md"]
        (sort (for [^java.io.File f (file-seq (io/file "docs/guide"))
                    :when (str/ends-with? (.getName f) ".md")]
                (.getPath f)))))

(defn- blocks
  "{[page place] text} for each clojure or edn block, place counting from 0
  within its page."
  []
  (into {}
        (for [page (pages)
              [i [_ text]] (map-indexed vector
                                        (re-seq #"(?ms)^```(?:clojure|edn)[^\n]*\n(.*?)^```" (slurp page)))]
          [[page i] text])))

(defn- forms
  "Every EDN form in text."
  [text]
  (let [r (java.io.PushbackReader. (java.io.StringReader. text))]
    (loop [out []]
      (let [f (edn/read {:eof ::eof} r)]
        (if (= ::eof f) out (recur (conj out f)))))))

(defn- settings-problem
  "Why Jonquil wouldn't take text as a settings.edn, or nil: it warns, or
  it drops one of the keys the example sets."
  [text]
  (let [f (java.io.File/createTempFile "jonquil-guide-settings" ".edn")]
    (try
      (spit f text)
      (let [loaded (settings/load-settings (.getPath f))
            dropped (remove #(contains? loaded %) (keys (edn/read-string text)))]
        (cond
          (:warning loaded) (:warning loaded)
          (seq dropped) (str "settings.edn doesn't keep " (pr-str (vec dropped)))))
      (finally (.delete f)))))

(defn- keys-problems
  "What jonquil.user-keys says against text as a keys.edn."
  [text]
  (:problems (user-keys/read-keys
              text {:commands registry/commands
                    :conditions {:window (set (keys registry/window-conditions))
                                 :editor (set (keys registry/editor-conditions))}})))

(defn- project-warnings
  "What jonquil.dialect warns about text as a .jonquil.edn. A block may
  show several files, one map each."
  [text]
  (mapcat #(:warnings (dialect/parse-config (pr-str %) nil)) (forms text)))

(defn- plugin-result
  "The plugin check's answer for text as the plugin file id, against a
  Jonquil with no other plugin."
  [id text]
  (loader/propose-check {:registry (plugin-registry/empty-registry) :id id :source text :os "Mac OS X"
                         :check (fn [m] (protocol/run-protocol (assoc m :where :in-process)))}))

(deftest the-table-names-every-block-test
  (is (= (set (keys kinds)) (set (keys (blocks))))
      "each clojure or edn block in the README and the guide has a kind in kinds, and kinds names no block that is gone"))

(deftest every-example-is-one-jonquil-takes-test
  (doseq [[[page i :as k] text] (blocks)
          :let [[kind arg] (get kinds k)
                where (str page " block " i)]]
    (case kind
      :settings (is (nil? (settings-problem text)) where)
      :keys (is (= [] (keys-problems text)) where)
      :project (is (= [] (project-warnings text)) where)
      :plugin (let [r (plugin-result arg text)]
                (is (:ok? r) (str where ": " (pr-str (:problems r))))
                (is (= [] (:warnings r)) where))
      :shape (is (string? arg) (str where " says why it isn't checked"))
      (is false (str where " has no kind")))))
