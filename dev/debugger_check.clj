;; Runs the debugger's tests on a Jolt that has jolt.debug, which the
;; released Jolt (v0.8.23) lacks: on it the frames, Break on error and
;; evaluate-in-a-frame tests take their "not available" path and pass
;; without testing anything. Run via `jolt test:debugger`.
;;
;; The Jolt is JONQUIL_JOLT_DEBUG when set, else `jolt-debug` on PATH, else
;; the `jolt` on PATH once it has jolt.debug; each is asked to require
;; jolt.debug before it's used. With none, it says so and exits 0, as the
;; other tool-dependent tasks do. With one, every step must pass and must
;; really run: the live child test fails if its break test skipped, and
;; the window self-test fails if a debugger row took the not-available
;; path or didn't pass. `--no-window` leaves the window self-test out.
(ns debugger-check
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(defn- has-debug? [jolt]
  (try
    (str/includes? (:out (p/shell {:out :string :err :string :continue true}
                                  jolt "-e" "(do (require 'jolt.debug) (println :jolt-debug-ok))"))
                   ":jolt-debug-ok")
    (catch Exception _ false)))

(defn- find-jolt []
  (let [env (System/getenv "JONQUIL_JOLT_DEBUG")]
    (first (filter has-debug?
                   (cond-> []
                     (seq env) (conj env)
                     (fs/which "jolt-debug") (conj (str (fs/which "jolt-debug")))
                     (fs/which "jolt") (conj (str (fs/which "jolt"))))))))

(defn- run
  "Runs args with extra env, echoing the output; [exit output]."
  [env & args]
  (println (str "\n== " (str/join " " args)))
  (let [{:keys [exit out err]} (apply p/shell {:out :string :err :string :continue true
                                               :extra-env env}
                                      args)
        text (str out err)]
    (print text)
    (flush)
    [exit text]))

(def ^:private debugger-rows
  ;; the window self-test's debugger rows: each must PASS on this Jolt
  ["frames-after-error" "break-pauses" "break-stop" "break-stop-blocked" "break-off-by-default"
   "break-flag-ends-with-its-eval" "break-flag-survives-next-start"])

(defn- window-ok? [text]
  (and (not (str/includes? text ":available false"))
       (every? #(str/includes? text (str "] " % " PASS")) debugger-rows)))

(defn -main [& args]
  (if-let [jolt (find-jolt)]
    (let [_ (println "test:debugger: using" jolt)
          [u _] (run {} jolt "-M:test" "jonquil.debugger-test" "jonquil.session-test" "jonquil.eval-test"
                     "jonquil.nrepl.break-middleware-test")
          [c ctext] (run {"JONQUIL_JOLT" jolt} jolt "-M:test" "jonquil.nrepl.evaluator-test")
          child-ran? (not (str/includes? ctext "SKIP break-in-the-child-test"))
          [w wtext] (if (some #{"--no-window"} args)
                      [0 nil]
                      (run {} jolt "-M:selftest-window"))
          window-ran? (or (nil? wtext) (window-ok? wtext))
          ok? (and (zero? u) (zero? c) child-ran? (zero? w) window-ran?)]
      (println)
      (println (str "test:debugger: unit " (if (zero? u) "ok" "FAILED")
                    ", child " (cond (not child-ran?) "SKIPPED (counts as a failure)" (zero? c) "ok" :else "FAILED")
                    ", window " (cond (nil? wtext) "left out" (not window-ran?) "debugger rows not run or not passing"
                                      (zero? w) "ok" :else "FAILED")))
      (System/exit (if ok? 0 1)))
    (do (println "test:debugger: SKIP, no Jolt with jolt.debug (set JONQUIL_JOLT_DEBUG, or put jolt-debug on PATH)")
        (System/exit 0))))

(apply -main *command-line-args*)
