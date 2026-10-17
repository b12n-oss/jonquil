(ns jonquil.cljs
  "Pure decisions for the ClojureScript attach dialect (shadow-cljs): the
  console's sentences, the build choice, and reply decisions.
  No sockets and no GTK: receives text and values."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

;; --- the console's sentences ---

(def experimental-message
  "; ClojureScript support is experimental. Jonquil connects to the shadow-cljs nREPL server on the first Run.")

(def detached-message
  "; Detached from the shadow-cljs server. The watch and the app weren't touched.")

(def no-project-message
  "; This file isn't in a project, so there's no shadow-cljs.edn to read. Save it inside the project that has .jonquil.edn.")

(defn not-running-message [dir]
  (str "; No shadow-cljs server here. Start one in " dir " with npx shadow-cljs watch <build>."))

(def bad-port-file-message
  "; .shadow-cljs/nrepl.port doesn't hold a port number, so Jonquil can't reach the server.")

(defn refused-message [port]
  (str "; .shadow-cljs/nrepl.port says port " port ", but nothing answers there. The server may have stopped."))

(defn not-server-message [port]
  (str "; Port " port " isn't a shadow-cljs nREPL server Jonquil can use."))

(defn connecting-message [port]
  (str "; Connecting to the shadow-cljs nREPL server on port " port "..."))

(def no-runtime-message
  "; The build isn't loaded in any JS runtime. Open the app (or run the node script) and Run again.")

(def quit-value ":repl/quit")

;; --- picking the build ---

(defn select-code
  "The form that switches the session to the build (a keyword)."
  [build]
  (str "(shadow.cljs.devtools.api/nrepl-select " build ")"))

(defn selected-value?
  "True when v is the value nrepl-select answers with for build."
  [build v]
  (= (str "[:selected " build "]") v))

(defn- first-line-after-header
  "The first non-blank line after the last line starting with
  'Execution error', or nil when there's no such header or nothing after it."
  [err-text]
  (let [lines (str/split-lines (or err-text ""))
        idx (last (keep-indexed (fn [i l] (when (str/starts-with? l "Execution error") i)) lines))]
    (when idx
      (first (remove str/blank? (drop (inc idx) lines))))))

(defn select-failed-message
  "One console line for a failed nrepl-select: the error's message line
  (the first non-blank line after the Execution error header, else the
  first non-blank line) and how to start the build."
  [err-text build]
  (let [line (or (first-line-after-header err-text)
                 (first (remove str/blank? (map str/trim (str/split-lines (or err-text ""))))))]
    (str "; " (str/replace (str/trim (or line "")) #"\.+$" "")
         ". Start it with npx shadow-cljs watch " (if (keyword? build) (name build) build) ".")))

;; --- replies ---

(defn exception-value?
  "True for the :value a ClojureScript eval answers with when it threw."
  [v]
  (= ":repl/exception!" v))

(defn error-line
  "The one-line error for a measured :err, like 'Error: boom': the type
  from the last Execution error header and the first non-blank line after
  it. Without a header, the whole trimmed text."
  [err-text]
  (let [text (or err-text "")
        lines (str/split-lines text)
        idx (last (keep-indexed (fn [i l] (when (str/starts-with? l "Execution error") i)) lines))]
    (if-let [msg (when idx (first (remove str/blank? (drop (inc idx) lines))))]
      (if-let [[_ t] (re-find #"^Execution error \(([^)]*)\)" (nth lines idx))]
        (str t ": " (str/trim msg))
        (str/trim msg))
      (str/trim text))))

(defn no-runtime?
  "True when err-text says the build has no JS runtime connected."
  [err-text]
  (str/starts-with? (or err-text "") "No available JS runtime."))

(defn- plural [n word]
  (str n " " word (when (not= 1 n) "s")))

(defn run-summary
  "The line after a Run: how many forms ran and how many threw. ns-name
  may be nil, which reads as 'the buffer'."
  [ns-name values]
  (let [n (count values)
        m (count (filter exception-value? values))]
    (str "; Ran " (or ns-name "the buffer") " ("
         (plural n "form")
         (when (pos? m)
           (str ", " (plural m "error") "; an error doesn't stop the rest of the file"))
         ")")))

;; --- the build choice ---

(def ^:private no-build-notice
  "; No build found in shadow-cljs.edn. Name one with {:cljs {:shadow-build :app}} in .jonquil.edn.")

(defn shadow-build
  "Which shadow-cljs build to use: {:build kw}, or {:notice s} when there's
  no single answer. config-cljs is .jonquil.edn's :cljs value (map or nil);
  its keyword :shadow-build wins, else the only build in shadow-edn-text."
  [config-cljs shadow-edn-text]
  (let [configured (:shadow-build config-cljs)]
    (cond
      (keyword? configured) {:build configured}
      (some? configured) {:notice "; .jonquil.edn :cljs :shadow-build should be a keyword, like :app."}
      :else
      (let [parsed (try (edn/read-string {:default (fn [_tag v] v)} (or shadow-edn-text ""))
                        (catch Exception _ nil))
            builds (when (map? parsed) (:builds parsed))
            ks (when (map? builds) (keys builds))]
        (case (count ks)
          0 {:notice no-build-notice}
          1 {:build (first ks)}
          {:notice (str "; Several shadow-cljs builds: " (str/join " " (sort ks))
                        ". Pick one with {:cljs {:shadow-build :app}} in .jonquil.edn.")})))))
