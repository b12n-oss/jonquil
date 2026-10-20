(ns jonquil.cljs-node
  "Pure helpers for ClojureScript on node: the console's sentences, the
  launch arguments, the readiness line, and readers for the prepl replies
  (EDN lines from cljs.repl's prepl). No sockets, no processes and no GTK:
  receives text and maps."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

;; --- the console's sentences ---

(defn starting-message [clojure-path]
  (str "; Starting ClojureScript on node (" clojure-path ")..."))

(def no-clojure-message
  "; ClojureScript on node needs the Clojure CLI (clojure). Install it or set JONQUIL_CLOJURE to its path.")

(defn start-failed-message [text]
  (str "; ClojureScript on node didn't start: " text))

(def reset-message
  "; Reset on Run: ClojureScript restarts, so this Run starts clean.")

(def stopped-message
  "; Stopped. ClojureScript restarted, so earlier definitions are gone.")

(def limitations
  ["Jonquil starts and stops its own ClojureScript process."
   "Stop restarts it, so earlier definitions are gone afterwards."
   "No stdin and no inspector; jump to definition stays in this file."
   "Completion uses this file's names and Clojure core's."])

;; --- launch ---

(def default-deps
  "{:deps {org.clojure/clojurescript {:mvn/version \"1.12.42\"}}}")

(defn needs-cljs-dep?
  "True when the deps.edn text is missing, unreadable, or has no
  org.clojure/clojurescript dependency."
  [deps-edn-text]
  (let [edn-map (when deps-edn-text
                  (try (edn/read-string deps-edn-text) (catch Exception _ nil)))]
    (nil? (get-in edn-map [:deps 'org.clojure/clojurescript]))))

(defn launch-args
  "The argv that starts the ClojureScript launcher. The -Sdeps pair is
  added only when add-dep? is true (the project has no ClojureScript)."
  [{:keys [clojure launcher add-dep?]}]
  (vec (concat [clojure]
               (when add-dep? ["-Sdeps" default-deps])
               ["-M" "-i" launcher "-m" "jonquil.cljs-node-launch"])))

(defn ready-port
  "The port from the last line that announces `jonquil-cljs-prepl <port>`,
  or nil when no line does."
  [log-text]
  (some->> (str/split-lines (str log-text))
           (keep #(second (re-find #"jonquil-cljs-prepl (\d+)" %)))
           last
           parse-long))

(def setup-form
  "(require 'cljs.repl)")

(defn sentinel
  "The form that ends an evaluation. Its ret marks where the buffer's output ends."
  [n]
  (str ":jonquil.eval/end-" n))

;; --- replies ---

(defn- read-edn-val [s]
  (try (edn/read-string {:default (fn [_tag v] v)} s)
       (catch Exception _ nil)))

(defn parse-reply
  "A prepl reply line as a map with :tag, or nil when the line doesn't read
  or isn't a map with :tag."
  [line]
  (let [v (read-edn-val line)]
    (when (and (map? v) (contains? v :tag))
      v)))

(defn- last-line [s]
  (let [line (last (remove str/blank? (str/split-lines (str s))))]
    (some-> line str/trim)))

(defn error-line
  "The one-line message for a failing ret. Reads its :val (the exception as
  EDN) and takes the :cause, else the last :via entry's :message, and keeps
  that message's last non-blank line."
  [ret]
  (let [v (read-edn-val (:val ret))
        msg (or (:cause v) (:message (last (:via v))))]
    (if-let [line (and msg (last-line msg))]
      (str "Error: " line)
      "Error: evaluation failed")))

(defn fold-replies
  "Folds one evaluation's prepl replies (parsed maps, in arrival order) into
  jonquil.eval's result shape. Only :ret replies count. Rets up to the
  sentinel's ret are the evaluated forms; the sentinel's ret ends them and is
  never the printed value. A failing form makes an :error result whose text is
  the first failure's error-line. :ns comes from the last ret that has one."
  [replies sentinel-form]
  (let [rets (filter #(= :ret (:tag %)) replies)
        body (take-while #(not= sentinel-form (:form %)) rets)
        cur-ns (some->> (keep :ns rets) last symbol)]
    (if-let [failing (first (filter :exception body))]
      (cond-> {:status :error :error-text (error-line failing)}
        cur-ns (assoc :ns cur-ns))
      (cond-> {:status :ok
               :printed (if (seq body) (:val (last body)) "nil")}
        cur-ns (assoc :ns cur-ns)))))

(defn output-of
  "[kind text] for a reply that prints: :out and :err as they are, :tap as
  a \"tap> \" line on stdout. Nil for every other reply."
  [reply]
  (case (:tag reply)
    :out [:out (:val reply)]
    :err [:err (:val reply)]
    :tap [:out (str "tap> " (:val reply) "\n")]
    nil))
