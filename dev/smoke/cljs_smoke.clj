;; Drives the BUILT Jonquil binary, via cgevent --pid, to smoke the
;; ClojureScript (shadow-cljs) attach dialect against the user's REAL,
;; already-running shadow-cljs app (named by dev/smoke/cljs_app.local.edn).
;; Run via `jolt smoke:cljs` (see bb.edn); a plain jolt script, not a Jonquil
;; namespace, shaped like dev/smoke/cljd_smoke.clj: its helpers are private
;; there, so the ones needed here are copied rather than shared.
;;
;; The smoke never starts or stops anything. The user runs
;; `npx shadow-cljs watch <build>` and the built node script themselves;
;; pre-flight only checks that <dir>/.shadow-cljs/nrepl.port exists and that
;; its port answers an nREPL describe.
;;
;; Two scratch buffers live under <dir>/target/jonquil-smoke/, a directory
;; the app's .gitignore covers (pre-flight asks `git check-ignore` and
;; refuses to write otherwise). They have to sit under <dir> because the
;; project is found by walking up from the file to shadow-cljs.edn. When the
;; config names a :build, <dir>/.jonquil.edn is also needed to pick it (an
;; app with several builds has no other way); it is written only if absent
;; and removed again at the end, so `git status` is the same before and
;; after. Both scratch files declare ns jonquil.smoke.scratch.
;;
;; One continuous Jonquil session (process 1) covers open, Run, println
;; output, a REPL entry in the buffer's ns, Stop, and a second Run that
;; reconnects. A second process (2) covers a buffer with a throw.
;;
;; Stop is driven by Escape during a bounded busy-loop eval (6s of blocked
;; node), not while idle: Escape is only consumed while something is
;; evaluating, and pid-routed cgevent clicks can't reach the Stop button.
(ns cljs-smoke
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private out-dir "target/smoke")
(def ^:private binary-path "./jonquil")
(def ^:private app-config-path "dev/smoke/cljs_app.local.edn")

(defn- read-app-config
  "Reads {:dir <abs path to the app> :build <kw, optional>} from the
  gitignored app-config-path, or nil when it is absent or unusable."
  []
  (let [cfg (try (edn/read-string (slurp app-config-path)) (catch Exception _ nil))]
    (when (and (map? cfg) (string? (:dir cfg))) cfg)))

(def ^:private app-config (read-app-config))

(when-not app-config
  (println (str "[cljs-smoke] SKIP: create " app-config-path " holding {:dir \"<abs path to your shadow-cljs app>\" :build :dev} to run this smoke."))
  (System/exit 0))

(def ^:private app-dir (:dir app-config))
(def ^:private build-kw (:build app-config))
(def ^:private port-file (str app-dir "/.shadow-cljs/nrepl.port"))
(def ^:private scratch-dir (str app-dir "/target/jonquil-smoke"))
(def ^:private scratch-ok (str scratch-dir "/scratch.cljs"))
(def ^:private scratch-err (str scratch-dir "/scratch_err.cljs"))
(def ^:private jonquil-edn (str app-dir "/.jonquil.edn"))
(def ^:private scratch-ns "jonquil.smoke.scratch")

;; OCR reads the dots in the ns as commas or spaces ("Ran jonquil,smoke
;; scratch", measured), so summary lines are matched up to the first segment.
(def ^:private summary-needle "Ran jonquil")

;; Strings built with str so the OCR needles below (jq-printed, jq-boom,
;; jq-after, 4187) appear only in the console output, never in the source
;; text that stays visible in the editor pane.
(def ^:private ok-source
  (str "(ns " scratch-ns ")\n\n"
       "(println (str \"jq-\" \"printed\"))\n\n"
       "(def answer (+ 4000 187))\n\n"
       "(def other :ok)\n"))

(def ^:private err-source
  (str "(ns " scratch-ns ")\n\n"
       "(throw (js/Error. (str \"jq-\" \"boom\")))\n\n"
       "(println (str \"jq-\" \"after\"))\n"))

(def ^:private clean-title-ok "scratch.cljs - Jonquil")
(def ^:private clean-title-err "scratch_err.cljs - Jonquil")

(def ^:private smoke-xdg-config-home
  (str (System/getProperty "java.io.tmpdir")
       "/jonquil-cljs-smoke-xdg-" (System/currentTimeMillis) "-" (System/nanoTime)))

;; --- process bookkeeping -----------------------------------------------------

(defonce ^:private launched (atom []))

(defn- kill-all-launched!
  "Destroys every process this run started. Safe to call more than once."
  []
  (doseq [{:keys [proc pid]} @launched]
    (try
      (when (p/alive? proc) (p/destroy-tree proc))
      (catch Exception e
        (binding [*out* *err*]
          (println "[cljs-smoke] warning: couldn't kill pid" pid (ex-message e))))))
  (reset! launched []))

;; --- results -------------------------------------------------------------

(defonce ^:private results (atom []))

(defn- check! [step ok detail]
  (swap! results conj {:step step :ok (boolean ok) :detail detail})
  (println (str "[cljs-smoke] " (name step) " " (if ok "PASS" "FAIL") " " (pr-str detail))))

;; --- cgevent wrappers (cljd_smoke.clj's shape) -----------------------------

(def ^:private cgevent-timeout-ms 10000)

(defn- cgevent!
  [& args]
  (let [proc (p/process (into ["cgevent"] args) {:out :string :err :string})
        result (deref proc cgevent-timeout-ms ::timeout)]
    (if (= ::timeout result)
      (do (try (p/destroy-tree proc) (catch Exception _ nil))
          {:exit -1 :out ""
           :err (str "cgevent " (str/join " " args) " timed out after " cgevent-timeout-ms "ms")})
      {:exit (:exit result) :out (:out result) :err (:err result)})))

(defn- cg-key!
  [pid chord]
  (when-not pid
    (throw (ex-info "cljs_smoke: refusing to post a key event with no pid" {:chord chord})))
  (let [{:keys [exit err]} (cgevent! "key" "--pid" (str pid) chord)]
    (when-not (zero? exit)
      (throw (ex-info (str "cgevent key failed: " chord) {:pid pid :chord chord :err err})))))

;; Same map as cljd_smoke.clj, plus / (the unshifted slash key). Only the
;; REPL text this file types needs it, for `js/Date.now`; it is not measured
;; live here, so a failure in :stop-detaches may be this chord.
(def ^:private char->chord
  (merge
   (into {} (map (fn [c] [c (str c)])) "abcdefghijklmnopqrstuvwxyz")
   (into {} (map (fn [c] [c (str c)])) "0123456789")
   (into {} (map (fn [c] [c (str "shift + " (Character/toLowerCase ^char c))]))
         "ABCDEFGHIJKLMNOPQRSTUVWXYZ")
   {\space "space"
    \( "shift + 9"
    \) "shift + 0"
    \" "shift + '"
    \+ "shift + ="
    \- "-"
    \. "."
    \/ "/"
    \! "shift + 1"
    \[ "["
    \] "]"
    \: "shift + ;"}))

(defn- type-text!
  [pid s]
  (doseq [c s]
    (if-let [chord (get char->chord c)]
      (cg-key! pid chord)
      (throw (ex-info (str "cljs_smoke: no chord mapped for character " (pr-str c))
                      {:char c :s s})))))

(defn- windows-edn! [pid]
  (let [{:keys [out]} (cgevent! "windows" "--pid" (str pid) "--edn")]
    (try (edn/read-string out) (catch Exception _ []))))

(defn- window-for [pid]
  (first (windows-edn! pid)))

(defn- ocr-rows! [pid]
  (try
    (let [tmp (str out-dir "/.ocr-" pid "-" (System/nanoTime) ".edn")
          _ (cgevent! "ocr-inspect" "--pid" (str pid) "-o" tmp)
          rows (edn/read-string (slurp tmp))]
      (io/delete-file tmp true)
      rows)
    (catch Exception _ [])))

(defn- ocr-text! [pid]
  (str/join "\n" (keep :text (ocr-rows! pid))))

(defn- screenshot! [pid name]
  (try (cgevent! "screenshot" (str out-dir "/" name ".png") "--pid" (str pid))
       (catch Exception _ nil)))

(defn- wait-for! [deadline-ms interval-ms pred?]
  (let [t0 (System/currentTimeMillis)]
    (loop []
      (cond
        (pred?) true
        (>= (- (System/currentTimeMillis) t0) deadline-ms) false
        :else (do (Thread/sleep (long interval-ms)) (recur))))))

(defn- ocr-matches?
  "A String needle is checked against the newline-joined OCR text and the
  space-joined one (a long console line can wrap across two rows); a
  Pattern only against the former."
  [rows joined needle]
  (if (instance? java.util.regex.Pattern needle)
    (boolean (re-find needle joined))
    (or (str/includes? joined needle)
        (str/includes? (str/join " " (keep :text rows)) needle))))

(defn- wait-for-ocr!
  [pid deadline-ms needle]
  (let [last (atom "")
        ok (wait-for! deadline-ms 300
                      (fn []
                        (let [rows (ocr-rows! pid)
                              joined (str/join "\n" (keep :text rows))]
                          (reset! last joined)
                          (ocr-matches? rows joined needle))))]
    {:ok ok :last @last}))

(defn- count-of [text needle]
  (count (re-seq (re-pattern (java.util.regex.Pattern/quote needle))
                 (str/replace text "\n" " "))))

;; --- launching and stopping the binary under test --------------------------

(defn- launch!
  [file]
  (let [log (str out-dir "/.launch-cljs-" (System/nanoTime) ".log")
        proc (p/process [binary-path file]
                        {:out (io/file log) :err (io/file log) :shutdown p/destroy-tree
                         :extra-env {"XDG_CONFIG_HOME" smoke-xdg-config-home}})
        pid (.pid ^java.lang.Process (:proc proc))]
    (swap! launched conj {:proc proc :pid pid})
    {:proc proc :pid pid :log log}))

(defn- forget! [pid]
  (swap! launched (fn [v] (vec (remove (fn [m] (= pid (:pid m))) v)))))

(defn- ensure-window! [pid deadline-ms]
  (when-not (wait-for! deadline-ms 200 (fn [] (seq (windows-edn! pid))))
    (throw (ex-info "jonquil did not open a window" {:pid pid :deadline-ms deadline-ms}))))

;; --- cljs-specific helpers -------------------------------------------------

(defn- tcp-listening?
  "True when something accepts a TCP connect on 127.0.0.1:port within 2s."
  [port]
  (try
    (with-open [s (java.net.Socket.)]
      (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) 2000)
      true)
    (catch Exception _ false)))

(defn- nrepl-describe?
  "True when the server on 127.0.0.1:port answers a bencoded describe with
  something that looks like a describe reply."
  [port]
  (try
    (with-open [s (java.net.Socket.)]
      (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) 2000)
      (.setSoTimeout s 3000)
      (let [out (.getOutputStream s)]
        (.write out (.getBytes "d2:op8:describee" "UTF-8"))
        (.flush out))
      (let [buf (byte-array 8192)
            n (.read (.getInputStream s) buf)]
        (and (pos? n)
             (let [txt (String. buf 0 (int n) "UTF-8")]
               (or (str/includes? txt "ops") (str/includes? txt "versions"))))))
    (catch Exception _ false)))

(defn- git-status [dir]
  (:out (p/shell {:out :string :err :string :continue true}
                 "git" "-C" dir "status" "--porcelain")))

(defn- git-ignored? [dir rel]
  (zero? (:exit (p/shell {:out :string :err :string :continue true}
                         "git" "-C" dir "check-ignore" "-q" rel))))

;; --- steps -----------------------------------------------------------------

(defn- step-preflight!
  "Everything after this depends on its result. Reads only: the port file,
  an nREPL describe, `git check-ignore` and `git status`."
  []
  (let [console (cgevent! "on-console" "--edn")
        console-info (try (edn/read-string (:out console)) (catch Exception _ {}))
        locked? (:screen-locked? console-info true)
        port-exists (fs/exists? port-file)
        port (when port-exists (try (parse-long (str/trim (slurp port-file))) (catch Exception _ nil)))
        answers (boolean (and port (nrepl-describe? port)))
        ignored (git-ignored? app-dir "target/jonquil-smoke/scratch.cljs")]
    (check! :preflight-display (not locked?)
            {:screen-locked locked? :on-console (:on-console? console-info)})
    (check! :preflight-server-up answers
            {:port-file port-file :exists port-exists :port port :describe-answered answers
             :hint (when-not answers
                     "start the watch (npx shadow-cljs watch <build>) and the node script first, then run this again")})
    (check! :preflight-scratch-ignored ignored
            {:path "target/jonquil-smoke/scratch.cljs"
             :hint (when-not ignored "the app's .gitignore must cover target/; refusing to write scratch files there")})
    {:ok (and answers ignored (not locked?))
     :port port
     :pre-git (git-status app-dir)}))

(defn- step-open!
  [pid]
  (let [{:keys [ok last]} (wait-for-ocr! pid 8000 "ClojureScript support is experimental")]
    (screenshot! pid "cljs-step1-open")
    (check! :open-experimental-line ok {:console last})))

(defn- run-and-wait!
  "F5, then waits (up to 60s: the first Run connects) for the summary line.
  Returns {:ok :last}."
  [pid]
  (cg-key! pid "f5")
  (wait-for-ocr! pid 60000 summary-needle))

(defn- step-run!
  [pid]
  (let [{:keys [ok last]} (run-and-wait! pid)
        flat (str/replace last "\n" " ")
        has-count (boolean (re-find #"\(\d+ forms?\)" flat))
        {conn-ok :ok conn-last :last} (wait-for-ocr! pid 3000 "Connecting to the shadow-cljs nREPL server on port")
        {print-ok :ok print-last :last} (wait-for-ocr! pid 8000 "jq-printed")]
    (screenshot! pid "cljs-step2-run")
    (check! :run-connecting-line conn-ok {:console conn-last})
    (check! :run-summary (and ok has-count)
            {:console last :match (re-find #"Ran [^()]*\(\d+ forms?\)" flat)})
    (check! :run-println-output print-ok {:console print-last})))

(defn- step-repl-entry!
  "F6 moves focus to the console input. `answer` is defined by the buffer, so
  it only resolves when the REPL evaluates in the buffer's ns. 4187 appears
  nowhere in the source text."
  [pid]
  (cg-key! pid "f6")
  (type-text! pid "answer")
  (cg-key! pid "return")
  (let [{:keys [ok last]} (wait-for-ocr! pid 10000 "4187")]
    (screenshot! pid "cljs-step3-repl")
    (check! :repl-entry-in-buffer-ns ok {:console last})))

(defn- step-stop!
  "A 6s blocking loop on the node app (bounded: the loop ends by itself),
  then Escape. Stop detaches rather than interrupting, so the app finishes
  the loop on its own."
  [pid]
  (type-text! pid "(let [t (js/Date.now)] (loop [] (case (quot (- (js/Date.now) t) 6000) 0 (recur) nil)))")
  (cg-key! pid "return")
  (Thread/sleep 500)
  (cg-key! pid "escape")
  (let [{:keys [ok last]} (wait-for-ocr! pid 8000 "Detached from the shadow-cljs server")]
    (screenshot! pid "cljs-step4-stop")
    (check! :stop-detaches ok {:console last})))

(defn- step-rerun!
  "After Stop the node app is still busy for a few seconds; the second Run
  waits for its summary line (up to 60s), which only appears after a fresh
  connect. The count of summary lines must go up."
  [pid]
  (let [before (count-of (ocr-text! pid) summary-needle)
        _ (cg-key! pid "f5")
        grew? (wait-for! 60000 500
                         (fn []
                           (> (count-of (ocr-text! pid) summary-needle) before)))
        {:keys [last]} (wait-for-ocr! pid 1000 "Connecting")]
    (screenshot! pid "cljs-step5-rerun")
    (check! :rerun-reconnects grew?
            {:summaries-before before :summaries-after (count-of last summary-needle)
             :console last})))

(defn- step-error-buffer!
  [pid]
  (cg-key! pid "f5")
  (let [{:keys [ok last]} (wait-for-ocr! pid 60000 summary-needle)
        {err-ok :ok err-last :last} (wait-for-ocr! pid 5000 "jq-boom")
        {sum-ok :ok sum-last :last} (wait-for-ocr! pid 5000 "1 error; an error doesn't stop the rest of the file")
        {aft-ok :ok aft-last :last} (wait-for-ocr! pid 5000 "jq-after")]
    (screenshot! pid "cljs-step6-error")
    (check! :error-run-finishes ok {:console last})
    (check! :error-line err-ok {:console err-last})
    (check! :error-summary sum-ok {:console sum-last})
    (check! :error-does-not-stop-rest aft-ok {:console aft-last})))

(defn- step-close!
  "Cmd+W only when the title reads exactly as a clean buffer (otherwise a
  save alert would wait on a click this tool can't make); force-kills on
  any failure."
  [{:keys [pid proc]} clean-title tag]
  (screenshot! pid (str "cljs-close-" tag))
  (if-not (wait-for! 2000 200 (fn [] (= clean-title (:name (window-for pid)))))
    (do (check! (keyword (str "close-exits-" tag)) false
                {:reason "title never read as a clean buffer; refusing to send Cmd+W"
                 :title (:name (window-for pid))})
        (try (when (p/alive? proc) (p/destroy-tree proc)) (catch Exception _ nil))
        (forget! pid))
    (do
      (cg-key! pid "command + w")
      (let [t0 (System/currentTimeMillis)
            res (deref proc 15000 ::timeout)
            elapsed (- (System/currentTimeMillis) t0)]
        (if (= ::timeout res)
          (do (check! (keyword (str "close-exits-" tag)) false
                      {:reason "process did not exit within 15000ms" :elapsed-ms elapsed})
              (try (p/destroy-tree proc) (catch Exception _ nil)))
          (check! (keyword (str "close-exits-" tag)) (zero? (:exit res))
                  {:exit (:exit res) :elapsed-ms elapsed}))
        (forget! pid)))))

;; --- driver -----------------------------------------------------------------

(defn- build!
  []
  (println "[cljs-smoke] building" binary-path "...")
  (let [{:keys [exit err]} (p/shell {:out :string :err :string :continue true}
                                    "jolt" "build" "-m" "jonquil.main" "-o" binary-path)]
    (when-not (zero? exit)
      (binding [*out* *err*] (println "[cljs-smoke] build FAILED:" err))
      (System/exit 1))))

(defn- run-session!
  [{:keys [pre-git]}]
  (fs/create-dirs scratch-dir)
  (spit scratch-ok ok-source)
  (spit scratch-err err-source)
  (let [made-config? (and build-kw (not (fs/exists? jonquil-edn)))]
    (when made-config?
      (spit jonquil-edn (pr-str {:cljs {:shadow-build build-kw}})))
    ;; A SIGINT/SIGTERM to jolt skips the finally below, so the shutdown
    ;; hook removes the same files (deleting one that's gone is a no-op).
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (doseq [f [scratch-ok scratch-err]] (io/delete-file f true))
                                 (when made-config? (io/delete-file jonquil-edn true)))))
    (try
      (let [s1 (launch! scratch-ok)]
        (ensure-window! (:pid s1) 8000)
        (step-open! (:pid s1))
        (step-run! (:pid s1))
        (step-repl-entry! (:pid s1))
        (step-stop! (:pid s1))
        (step-rerun! (:pid s1))
        (step-close! s1 clean-title-ok "session1"))
      (let [s2 (launch! scratch-err)]
        (ensure-window! (:pid s2) 8000)
        (step-error-buffer! (:pid s2))
        (step-close! s2 clean-title-err "session2"))
      (finally
        (kill-all-launched!)
        (doseq [f [scratch-ok scratch-err]] (io/delete-file f true))
        (when made-config? (io/delete-file jonquil-edn true))))
    (let [post-git (git-status app-dir)]
      (check! :close-git-unchanged (= pre-git post-git) {:pre pre-git :post post-git}))))

(defn -main [& args]
  (let [t0 (System/currentTimeMillis)
        no-build (some #{"--no-build"} args)
        preflight-ok (atom true)]
    (fs/create-dirs out-dir)
    (if no-build
      (when-not (fs/exists? binary-path)
        (binding [*out* *err*]
          (println "[cljs-smoke]" binary-path "doesn't exist and --no-build was given"))
        (System/exit 1))
      (build!))
    (println "[cljs-smoke] this drives your OWN, freshly-launched Jonquil windows against")
    (println "[cljs-smoke] your running shadow-cljs app. Please don't type for the next")
    (println "[cljs-smoke] few minutes -- a stray keystroke can land in the wrong window.")
    (try
      (let [{:keys [ok port] :as pre} (step-preflight!)]
        (reset! preflight-ok ok)
        (if-not ok
          (println "[cljs-smoke] pre-flight failed: start the watch and the node script first (see the :preflight-* lines above); nothing else was run.")
          (do
            (Thread/sleep 3000)
            (run-session! pre)
            (check! :close-shadow-still-listening (tcp-listening? port) {:port port}))))
      (catch Exception e
        (check! :unexpected-exception false {:exception (ex-message e) :data (ex-data e)}))
      (finally
        (kill-all-launched!)))
    (let [scored (remove #(= :skip (:ok %)) @results)
          passed (count (filter :ok scored))
          total (count scored)
          wall-ms (- (System/currentTimeMillis) t0)]
      (println (str "SUMMARY " passed " of " total " passed (" wall-ms "ms)"))
      (System/exit (if (= passed total) 0 1)))))

(apply -main *command-line-args*)
