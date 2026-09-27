(ns jonquil.cljd
  "Pure decisions for the ClojureDart attach dialect: the console's
  sentences, the buffer's ns form, NREPL.lock's port, the fresh-session
  wake-up probe, and a load's one-line summary. No sockets and no GTK:
  jonquil.nrepl.attach hands in text and reply vectors (jonquil.nrepl.client
  messages, top-level keys keywords).

  Reply shapes are the C0 spike's, measured against the nrepl-poc fork on
  2026-09-28: a load-file answers one :value per top-level form, an :ns
  message after each, and stops at the first form that fails with :err and
  an eval-error status."
  (:require [clojure.string :as str]
            [jonquil.nrepl.ops :as ops]
            [jonquil.syntax :as syntax]))

(def default-ns
  ;; A fresh session starts here: every spike probe's first reply named it.
  "cljd.user")

;; --- the console's sentences (spec sections 2 and 3) -------------------------

(def not-running-message
  "; ClojureDart app isn't running here. Start it with `bb dev:macos` (or `clj -M:cljd flutter`).")

(defn refused-message [port]
  (str "; NREPL.lock says port " port ", but nothing answers there. The app may have quit; restart it."))

(defn not-cljd-message [port]
  (str "; Port " port " isn't a ClojureDart nREPL server."))

(def bad-port-file-message
  "; NREPL.lock doesn't hold a port number, so Jonquil can't reach the app.")

(def no-project-message
  "; This file isn't inside a ClojureDart project (no pubspec.yaml above it), so there's no NREPL.lock to read.")

(defn connecting-message [port]
  (str "; Connecting to the app on port " port "..."))

(def still-waiting-message "; Still waiting on the app...")

(def detached-message
  "; Detached from the app. The app wasn't touched; a change already sent may still land.")

(def lost-connection "Lost the connection to the app.")

(def save-first-message
  "Save the file first: the app's error messages and its watcher both go by the file's path.")

(def experimental-message
  "; ClojureDart support is experimental. Jonquil connects to the running app on the first Run.")

;; --- the buffer's namespace ------------------------------------------------

(defn- discard-or-quoted?
  "True when the nearest non-whitespace text immediately before position
  pos in text is a #_ discard or a reader prefix (' ` ~ @), so a top-level
  (...) there reads as data, not as a candidate ns form. tokenize has no
  token for #, ' or `, so those prefixes are otherwise invisible to
  ns-form's scan."
  [^String text pos]
  (let [i (loop [i (dec (long pos))]
            (if (and (>= i 0) (Character/isWhitespace (.charAt text i)))
              (recur (dec i))
              i))]
    (and (>= i 0)
         (let [ch (.charAt text i)]
           (or (contains? #{\' \` \~ \@} ch)
               (and (= \_ ch) (pos? i) (= \# (.charAt text (dec i)))))))))

(defn ns-form
  "The buffer's first top-level (ns ...) form as {:name str :text str}, or
  nil when there's none, or it's never closed. Brackets inside strings and
  comments don't count, and metadata before the name is skipped. A
  discarded (#_) or quoted/reader-prefixed ('/`/~/@) top-level form is
  skipped, since it isn't the buffer's real ns form."
  [text]
  (when-not (str/blank? text)
    (let [toks (filterv (fn [[t]] (not= :comment t)) (syntax/tokenize text))
          {:keys [matches bracket-tokens]} (syntax/compute-brackets text)
          top-opens (into #{} (keep (fn [[s _ depth kind]]
                                      (when (and (= :open kind) (zero? depth) (= \( (.charAt ^String text (int s))))
                                        (long s))))
                          bracket-tokens)
          word (fn [i] (when-let [[_ s e] (get toks i)] (subs text s e)))]
      (some (fn [i]
              (let [[t s] (nth toks i)]
                (when (and (= :bracket t) (contains? top-opens (long s)) (= "ns" (word (inc i)))
                           (not (discard-or-quoted? text (long s))))
                  (when-let [close (get matches (long s))]
                    (let [name-i (first (drop-while (fn [j] (= :metatag (first (get toks j))))
                                                    (iterate inc (+ i 2))))
                          [nt ns-start ns-end] (get toks name-i)]
                      (when (#{:symbol :special-form :builtin} nt)
                        {:name (subs text ns-start ns-end)
                         :text (subs text s (inc close))}))))))
            (range (count toks))))))

(defn target-ns
  "What Run Selection and Run Form evaluate in: the buffer's ns form, or
  (ns cljd.user) when the buffer has none. {:name str :text str}."
  [text]
  (or (ns-form text) {:name default-ns :text (str "(ns " default-ns ")")}))

;; --- NREPL.lock ------------------------------------------------------------

(defn parse-port
  "NREPL.lock's text as a port number, or nil when it doesn't hold one. The
  fork writes the port and a newline (cljd.nrepl/start-nrepl-server)."
  [text]
  (let [n (try (some-> text str/trim parse-long) (catch Exception _ nil))]
    (when (and n (< 0 n 65536)) n)))

;; --- replies ---------------------------------------------------------------

(def probe-code
  ;; Jonquil's own first eval on a fresh session: never the user's code, so
  ;; nothing the user wrote is ever sent twice.
  "(+ 1 2)")

(def not-awake-message "; The app's REPL didn't wake up; try Run again.")

(defn probe-result
  ":awake when the probe's replies carry the value \"3\"; :asleep when
  they carry no value or the printed nil, with no err, no eval-error and no
  lost connection (the fork's connect skill: the first eval after a hot
  restart can answer nil, and evaluating again wakes it); :lost for a lost
  connection; :failed otherwise."
  [msgs]
  (let [st (ops/statuses msgs)
        vals (vec (keep :value msgs))]
    (cond
      (some #{"3"} vals) :awake
      (st ops/disconnected) :lost
      (and (contains? #{[] ["nil"]} vals) (not (some :err msgs)) (not (st "eval-error"))) :asleep
      :else :failed)))

(defn eval-result
  "jonquil.eval's result shape for an eval's replies: ops/eval-result, except
  that a lost connection (without Stop) reads as the app's own sentence."
  [msgs stop-requested?]
  (if (and ((ops/statuses msgs) ops/disconnected) (not stop-requested?))
    {:status :error :error-text lost-connection}
    (ops/eval-result msgs stop-requested?)))

(defn load-result
  "A load-file's replies as one result, quiet: a :notice line on success
  instead of a value per form. ns-name names what was loaded (the buffer's
  ns form, else its file name). The form count is the number of values
  received, so a stop names the form after the last value (measured:
  format.cljd answered nine values and failed on its tenth form)."
  [msgs ns-name stop-requested?]
  (let [st (ops/statuses msgs)
        n (count (keep :value msgs))]
    (cond
      (and stop-requested? (st ops/disconnected)) {:status :interrupted}
      (st ops/disconnected) {:status :error :error-text lost-connection}
      (st "eval-error") {:status :error :error-text (str "Load stopped at form " (inc n) " of " ns-name)}
      :else {:status :notice
             :text (str "; Loaded " ns-name " (" n (if (= 1 n) " form)" " forms)"))})))
