(ns jonquil.world-rt
  "Save World and Restore World where the code runs (LM7), over
  jolt.image: the data vars of a set of namespaces written to a file and
  rebound from it later. Functions aren't written, since the restoring
  session has the code. Runs in process and in the Jolt child
  (jonquil.nrepl.middleware-files embeds it), so it requires only
  jolt.image and clojure.*. No GTK here.

  Measured 2026-11-04 on Jolt 1b055271: jolt.image/scan-world names a
  problem by its path through the graph (\"1 -> cdr -> car\"), not by var,
  so the save scans each var with jolt.image/scan to name it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.image :as img]))

(defn var-name [v]
  (let [m (meta v)] (str (ns-name (:ns m)) "/" (:name m))))

(defn data-vars
  "The bound, non-fn, non-macro vars of the loaded namespaces among
  ns-names (strings), the ones a world holds."
  [ns-names]
  (into []
        (comp (keep #(find-ns (symbol %)))
              (mapcat #(vals (ns-interns %)))
              (filter (fn [v] (and (bound? v) (not (:macro (meta v))) (not (fn? @v))))))
        ns-names))

(defn- kind
  "What a scan entry's object is, in a few words: its class for an
  #object[...] print, else its printed text, cut short."
  [{:keys [object]}]
  (let [s (str object)]
    (or (second (re-find #"^#object\[(\S+)" s))
        (if (> (count s) 60) (str (subs s 0 60) "...") s))))

(defn- problems
  "[{:var :kind :disposition}] for each data var whose value a strict dump
  would refuse: :would-stub when it can be written as a placeholder,
  :unwritable when nothing can be."
  [vars]
  (into []
        (keep (fn [v]
                (when-let [ps (seq (img/scan @v))]
                  {:var (var-name v)
                   :kind (kind (first ps))
                   :disposition (if (some #(= :unwritable (:disposition %)) ps) :unwritable :would-stub)})))
        vars))

(defonce ^:private preloaded
  ;; the namespaces loaded before this one: in the Jolt child that is the
  ;; runtime and its tooling, loaded with the middleware at the server's
  ;; start, before any of the user's code (measured 2026-11-04: a
  ;; prefix list missed grenadine.*, Jolt's deps resolver)
  (set (map (comp str ns-name) (all-ns))))

(defn child-namespaces
  "The Jolt child's namespaces that are the user's: user, and every one
  loaded after this namespace was, but never the language's (clojure.*,
  jolt.*) or the tooling's (jonquil.*, nrepl.*)."
  []
  (into ["user"]
        (comp (map (comp str ns-name))
              (remove #(or (= "user" %) (contains? preloaded %)
                           (re-find #"^(clojure|jolt|jonquil|nrepl)\." %))))
        (all-ns)))

(defn- save-op
  "The :save answer for path: what was saved and the placeholders, or
  {:error :unwritable} with nothing written. nil namespaces means
  child-namespaces."
  [path namespaces]
  (let [nss (or namespaces (child-namespaces))
        vars (data-vars nss)
        ps (problems vars)
        bad (filterv #(= :unwritable (:disposition %)) ps)]
    (if (seq bad)
      {:error (str "Nothing was saved: " (str/join ", " (map :var bad)) " can't be written, even as a placeholder.")
       :unwritable (mapv #(select-keys % [:var :kind]) bad)}
      (do (io/make-parents (io/file path))
          (img/dump-world! path nss)
          {:saved (count vars)
           :placeholders (mapv #(select-keys % [:var :kind]) ps)}))))

(defn- restore-op
  "The :restore answer for the saved world at path: how many vars came back
  and the placeholders, or {:missing true} without the file."
  [path]
  (if-not (.isFile (io/file path))
    {:missing true}
    (let [n (img/restore-world! path)]
      {:restored n
       :placeholders (mapv (fn [s] {:var (:var s) :kind (:kind s) :description (:description s)})
                           (img/stubs))})))

(defn op
  "One world op, answered as plain data:
  :save {:path :namespaces} -> {:saved n :placeholders [...]}, or
    {:error s :unwritable [...]} with nothing written when a var holds
    something that can't even be a placeholder;
  :restore {:path} -> {:restored n :placeholders [...]}, or {:missing true}.
  :namespaces nil means child-namespaces."
  [op {:keys [path namespaces]}]
  (case op
    :save (save-op path namespaces)
    :restore (restore-op path)))
