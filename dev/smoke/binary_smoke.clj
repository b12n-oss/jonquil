;; P2.T10: automated smoke of the BUILT Jonquil binary, driven by cgevent
;; against a real macOS process -- everything dev/gtk_selftest_window.clj
;; and dev/demo_shots.clj can't cover, because both of those run IN the
;; GTK process under test and call its own handler functions directly.
;; This script is external: it launches ./jonquil as a real subprocess,
;; drives it with real key events routed by pid, and reads back real file
;; bytes, real window titles and real screen pixels.
;;
;; Run via `bb smoke:binary` (see bb.edn); this file is a plain babashka
;; script, not a jolt namespace -- it never touches the jolt/GTK process
;; from the inside, only from outside via cgevent and the filesystem.
;;
;; Hard safety rules (p2t10-brief.md, this task's own instructions):
;;   - every input event carries --pid; cg-key! below takes pid as a
;;     required (not optional) argument and throws if it's nil, so there
;;     is no code path in this file that can post without one.
;;   - never --global, never click/drag/move, never `cgevent type` (probed
;;     mangled against GTK -- p2t10-probe.md: "(+ 1 2)" arrived as "((" +
;;     5 spaces, because GTK's macOS backend reads the key code, not the
;;     Unicode string cgevent attaches).
;;   - the binary under test always launches from target/smoke/ (gitignored
;;     via the existing top-level /target/ rule -- confirmed with
;;     `git check-ignore -v target/smoke/probe.txt` before relying on it,
;;     so .gitignore itself needed no edit), with its own scratch copies of
;;     the checked-in dev/smoke/fixtures/*.clj files, never the fixtures
;;     themselves as the live file argument.
;;   - every jonquil process this script starts is killed on every exit
;;     path, including a thrown exception (an outer try/finally) or Ctrl-C
;;     (each launch! passes :shutdown p/destroy-tree, babashka.process's
;;     own per-process JVM shutdown hook); `pgrep -fl jonquil` after a run
;;     should show nothing of this script's own.
;;
;; cgevent's coordinate systems, measured live against this build
;; (2026-09-23, session probe, not carried over from p2t10-probe.md):
;;   `cgevent ocr-inspect` reports boxes in SCREEN points (matches
;;   `cgevent windows --edn`'s :x/:y, not window-relative) -- confirmed by
;;   comparing a window at x=900 against an OCR row's x=909 (inside it) for
;;   a line far down the window, whose y (548) is nowhere near the
;;   window's own y (31). `cgevent screenshot` writes NATIVE PIXELS, so
;;   mapping an OCR box onto a screenshot needs the window's own :scale
;;   (`cgevent windows --pid N --edn`) applied to (ocr-x - window-x) and
;;   (ocr-y - window-y), not to the raw ocr coordinates.
;;
;; The colour spot check (window.clj's `:out`/`:prompt` colours,
;; jonquil.console/styles) needs one more measured correction: a PLAIN
;; average of every pixel in an OCR'd line's box came back 202,202,202 --
;; close to white -- because most of a text line's bounding box is
;; background, not ink; the box's DARKEST pixel came back 40,40,40, an
;; exact match for :out's "#282828". See dev/smoke/PixelAt.java's own
;; docstring for the full measurement and the darkest-cluster fix.
;; babashka itself can't do this sampling -- `bb -e '(import (javax.imageio
;; ImageIO)) ...)'` fails with "Unable to resolve classname", because
;; babashka is a GraalVM native image with a curated class list that
;; doesn't include AWT/ImageIO -- so this shells out to a full JDK's
;; `java` instead (JEP 330 single-file source-code programs, JDK 11+; a
;; JDK is on PATH here, confirmed via `java -version`). sips, this task's
;; other suggested option, has no per-pixel query, only whole-image
;; property/profile queries, so it can't do this job at all.
(ns binary-smoke
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private out-dir "target/smoke")
(def ^:private fixtures-dir "dev/smoke/fixtures")
(def ^:private binary-path (str out-dir "/jonquil"))
(def ^:private pixel-at-jar "dev/smoke/PixelAt.java")

;; TH.T5 isolation: one XDG_CONFIG_HOME temp dir for this ENTIRE smoke run
;; (a top-level def, evaluated once when this script loads), handed to
;; every launch! below via :extra-env -- so no jonquil process this script
;; starts can ever read or write the user's real $XDG_CONFIG_HOME/jonquil
;; (or ~/.config/jonquil). The dir need not exist up front: jonquil.settings
;; itself creates its parents on save and treats a missing file as the
;; default theme on load.
(def ^:private smoke-xdg-config-home
  (str (System/getProperty "java.io.tmpdir")
       "/jonquil-smoke-xdg-" (System/currentTimeMillis) "-" (System/nanoTime)))

;; --- process bookkeeping: every jonquil pid this run launches, killed on
;; every exit path (a set of {:proc :pid}, never just a bare pid, since
;; killing needs the babashka.process handle) ---------------------------

(defonce ^:private launched (atom []))

(defn- kill-all-launched!
  "Destroys every process this run started (destroy-tree, since babashka.
  process falls back to plain destroy pre-JDK9 but this project's own JDK
  is 25 -- verified via `java -version` earlier this session). Safe to
  call more than once -- destroying an already-dead process is a no-op in
  babashka.process (measured: alive? false after destroy, no exception on
  a second destroy). Backed up per-process by :shutdown p/destroy-tree at
  launch! (below), which babashka.process registers as its own JVM
  shutdown hook -- so a Ctrl-C is covered even if this fn never runs."
  []
  (doseq [{:keys [proc pid]} @launched]
    (try
      (when (p/alive? proc) (p/destroy-tree proc))
      (catch Exception e
        (binding [*out* *err*]
          (println "[binary-smoke] warning: couldn't kill pid" pid (ex-message e))))))
  (reset! launched []))

;; --- results -------------------------------------------------------------

(defonce ^:private results (atom []))

(defn- check! [step ok detail]
  (swap! results conj {:step step :ok (boolean ok) :detail detail})
  (println (str "[binary-smoke] " (name step) " " (if ok "PASS" "FAIL") " " (pr-str detail))))

(defn- skip! [step reason]
  (swap! results conj {:step step :ok :skip :detail {:reason reason}})
  (println (str "[binary-smoke] " (name step) " SKIP " reason)))

;; --- cgevent wrappers: every one of these requires a real pid ------------

(def ^:private cgevent-timeout-ms
  ;; Fix round 1, minor #4: p/shell has no deadline of its own, so one hung
  ;; cgevent subcommand (ocr-inspect, windows, screenshot, key) used to be
  ;; able to stall a step -- and everything after it -- forever. Every
  ;; cgevent call in this file now goes through cgevent! below, which bounds
  ;; it. 10s is generous, not measured precisely: every live cgevent call
  ;; this session (probes and real runs alike) completed in well under 1s.
  10000)

(defn- cgevent!
  "Runs `cgevent <args>` with its own deadline: p/process + a timeout
  deref, destroying the subprocess tree on timeout so it can't linger past
  it. Returns the same shape p/shell's :out/:err :string mode does
  ({:exit :out :err}); a timeout reports :exit -1 (never a real cgevent
  exit code, so every existing (zero? exit) check in this file still works
  with no special case) and an :err message naming the command and the
  deadline."
  [& args]
  (let [proc (p/process (into ["cgevent"] args) {:out :string :err :string})
        result (deref proc cgevent-timeout-ms ::timeout)]
    (if (= ::timeout result)
      (do (try (p/destroy-tree proc) (catch Exception _ nil))
          {:exit -1 :out ""
           :err (str "cgevent " (str/join " " args) " timed out after " cgevent-timeout-ms "ms")})
      {:exit (:exit result) :out (:out result) :err (:err result)})))

(defn- cg-key!
  "Posts one chord to pid via CGEventPostToPid (never --global). Refuses
  outright when pid is nil -- this is the one function every key event in
  this file goes through, so that refusal is the hard safety rule, not a
  convention someone could forget at a call site."
  [pid chord]
  (when-not pid
    (throw (ex-info "binary_smoke: refusing to post a key event with no pid" {:chord chord})))
  (let [{:keys [exit err]} (cgevent! "key" "--pid" (str pid) chord)]
    (when-not (zero? exit)
      (throw (ex-info (str "cgevent key failed: " chord) {:pid pid :chord chord :err err})))))

;; letters, digits, space and the punctuation this smoke run's fixtures
;; and typed REPL/stdin answers need. cgevent names keys by their literal
;; character (p2t10-probe.md); an unmapped character is a driver bug, not
;; something to skip silently -- type-text! throws rather than dropping
;; it. "(" / ")" were measured in p2t10-probe.md; shift+= for "+", shift+'
;; for '"', "-" and "." were measured live against this build in this
;; task's own session (probe: select-all, delete, then those four chords
;; in a row read back via OCR as exactly `+"-.`, with no stray spaces).
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
    \. "."}))

(defn- type-text!
  "Types s one character at a time via char->chord -- never `cgevent
  type`, which p2t10-probe.md measured mangled against GTK (the buffer
  got \"((\" plus 5 spaces for the literal text \"(+ 1 2)\", because GTK's
  macOS backend reads the key code, not the Unicode string cgevent
  attaches)."
  [pid s]
  (doseq [c s]
    (if-let [chord (get char->chord c)]
      (cg-key! pid chord)
      (throw (ex-info (str "binary_smoke: no chord mapped for character " (pr-str c))
                      {:char c :s s})))))

(defn- windows-edn!
  "This pid's on-screen windows, parsed. Normally exactly one for jonquil
  (M6: G_APPLICATION_NON_UNIQUE, one window per process) -- callers that
  care about count (row-open-refuses-dirty's \"opens no panel\" check)
  read (count (windows-edn! pid)) directly rather than assuming 1."
  [pid]
  (let [{:keys [out]} (cgevent! "windows" "--pid" (str pid) "--edn")]
    (try (edn/read-string out) (catch Exception _ []))))

(defn- window-for [pid]
  (first (windows-edn! pid)))

(defn- ocr-text!
  "This pid's visible window text, OCR'd and joined into one string for
  loose substring matching (p2t10-probe.md: OCR drops the space after
  \"(\" and mangles \"---\", so callers match distinctive words, never
  punctuation runs). Never throws -- a transient OCR/subprocess hiccup
  reads as \"no match yet\" to a poller, not a crash of the whole run."
  [pid]
  (try
    (let [tmp (str out-dir "/.ocr-" pid "-" (System/nanoTime) ".edn")
          _ (cgevent! "ocr-inspect" "--pid" (str pid) "-o" tmp)
          rows (edn/read-string (slurp tmp))]
      (io/delete-file tmp true)
      (str/join "\n" (keep :text rows)))
    (catch Exception _ "")))

(defn- ocr-rows!
  "Like ocr-text!, but returns the parsed rows (with :x/:y/:w/:h in SCREEN
  points -- measured live this session, see this file's own header) for
  callers that need a specific line's bounds, not just its text."
  [pid]
  (try
    (let [tmp (str out-dir "/.ocr-" pid "-" (System/nanoTime) ".edn")
          _ (cgevent! "ocr-inspect" "--pid" (str pid) "-o" tmp)
          rows (edn/read-string (slurp tmp))]
      (io/delete-file tmp true)
      rows)
    (catch Exception _ [])))

(defn- screenshot!
  "Saves a screenshot to out-dir/<name>.png, best-effort (a failed capture
  must never take down the rest of the run -- it only costs a diagnostic
  image)."
  [pid name]
  (try (cgevent! "screenshot" (str out-dir "/" name ".png") "--pid" (str pid))
       (catch Exception _ nil)))

;; --- generic poll: never a fixed sleep in place of a real wait -----------

(defn- wait-for!
  "Polls (pred?) every interval-ms, up to a total of deadline-ms, sleeping
  between checks -- global.md/AGENTS.md's own rule for GTK self-tests,
  applied here to an external process instead of an in-process poll."
  [deadline-ms interval-ms pred?]
  (let [t0 (System/currentTimeMillis)]
    (loop []
      (cond
        (pred?) true
        (>= (- (System/currentTimeMillis) t0) deadline-ms) false
        :else (do (Thread/sleep (long interval-ms)) (recur))))))

(defn- wait-for-ocr!
  "Polls OCR until it contains needle or deadline-ms elapses. Returns
  {:ok bool :last last-ocr-text} -- \"prints what OCR saw\" on a failed
  wait, per this task's own design."
  [pid deadline-ms needle]
  (let [last (atom "")]
    (let [ok (wait-for! deadline-ms 300
                        (fn [] (let [t (ocr-text! pid)] (reset! last t) (str/includes? t needle))))]
      {:ok ok :last @last})))

(defn- ocr-rows->loose-text
  "rows' own :text values joined by a single SPACE (P3.T6), not ocr-text!'s
  \"\\n\": measured live this session, cgevent sometimes renders a short
  result like \"=> 2\" as one OCR row and sometimes splits it into \"=>\"
  and \"2\" as two adjacent rows -- same console content, different OCR
  passes, not something a caller can rely on being stable run to run. A
  newline join can leave a caller's literal \"=> 2\" substring search
  spanning exactly that split unmatched; a single space reconstitutes the
  same text whichever way cgevent happened to segment it this time,
  without assuming any row order beyond what cgevent already returns."
  [rows]
  (str/join " " (keep :text rows)))

(defn- wait-for-ocr-count!
  "Polls until re matches at least n times (non-overlapping, via re-seq)
  against ocr-rows->loose-text's own join of ocr-rows!, or deadline-ms
  elapses. Returns {:ok bool :count n :last last-loose-text}, the same
  \"prints what OCR saw\" shape wait-for-ocr! uses. Used where a step needs
  to count repeated occurrences (P3.T6 step 1's two \"=> 2\" round trips),
  where a plain wait-for-ocr! could only ever prove \"at least once\"."
  [pid deadline-ms re n]
  (let [last-count (atom 0) last-text (atom "")]
    (let [ok (wait-for! deadline-ms 300
                        (fn []
                          (let [t (ocr-rows->loose-text (ocr-rows! pid))
                                c (count (re-seq re t))]
                            (reset! last-count c)
                            (reset! last-text t)
                            (>= c n))))]
      {:ok ok :count @last-count :last @last-text})))

;; --- launching and stopping the binary under test ------------------------

(defn- scratch-copy!
  "Copies a checked-in fixture into out-dir under a fresh name, so the
  live file argument handed to jonquil is always a scratch file this run
  owns -- the checked-in dev/smoke/fixtures/*.clj sources are never
  touched, even by a step (Save) that writes to its file argument."
  [fixture-name]
  (let [src (io/file fixtures-dir fixture-name)
        dst (io/file out-dir (str (System/nanoTime) "-" fixture-name))]
    (fs/copy src dst)
    (.getPath dst)))

(defn- launch!
  "Starts ./jonquil (built into out-dir) as a real subprocess, optionally
  opening file. Registers it in `launched` immediately (before returning)
  so a thrown exception between here and the caller's own bookkeeping
  can't strand an unkillable process. Its own stdout/stderr go to a log
  file in out-dir rather than this script's own -- jonquil prints GLib/
  Gtk/macOS IME noise on stderr (smoke-results.md), harmless but not
  worth interleaving with this script's own [binary-smoke] lines. The log
  path comes back as :log so a step that specifically cares what landed on
  stderr (step-close-while-running!, checking for a GTK critical/crash)
  can read it directly.

  TH.T5: :extra-env sets XDG_CONFIG_HOME to smoke-xdg-config-home (merged
  onto the inherited environment, never replacing it -- babashka.process's
  own :env/:extra-env distinction), so every jonquil process this script
  starts reads and writes settings.edn under this run's own temp dir, never
  the user's real one."
  [& [file]]
  (let [log (str out-dir "/.launch-" (System/nanoTime) ".log")
        args (cond-> [binary-path] file (conj file))
        proc (p/process args {:out (io/file log) :err (io/file log) :shutdown p/destroy-tree
                              :extra-env {"XDG_CONFIG_HOME" smoke-xdg-config-home}})
        pid (.pid ^java.lang.Process (:proc proc))]
    (swap! launched conj {:proc proc :pid pid})
    {:proc proc :pid pid :log log}))

(defn- stop!
  "Kills one launched process (if still alive) and drops it from
  `launched` -- used by steps that end their own process mid-run (rather
  than via the product's own Cmd+W path, which is step-command-chord-close's
  own job) so a FAILED step's process doesn't linger until the whole run's
  final cleanup."
  [{:keys [proc pid]}]
  (try
    (when (p/alive? proc) (p/destroy-tree proc))
    (catch Exception _ nil))
  (swap! launched (fn [v] (vec (remove (fn [m] (= pid (:pid m))) v)))))

(defn- wait-for-window!
  "Polls cgevent windows --pid until it reports at least one window (the
  toolbar's own OCR text is a slower, heavier check than this, so ready-
  to-receive-keys and ready-to-OCR are checked separately)."
  [pid deadline-ms]
  (wait-for! deadline-ms 200 (fn [] (seq (windows-edn! pid)))))

(defn- ensure-window!
  "Fix round 1, minor #3: wait-for-window!'s result used to be ignored at
  every call site, so a binary that never opened a window (a crash on
  launch, a missing library, a hung splash) fell through into the rest of
  the step anyway -- every subsequent OCR read and key event just silently
  went nowhere, and the step failed later with a confusing timeout that
  named the wrong symptom. This throws instead, with the real reason,
  which -main's own per-step catch turns into a FAIL naming it directly."
  [pid deadline-ms]
  (when-not (wait-for-window! pid deadline-ms)
    (throw (ex-info "binary did not open a window" {:pid pid :deadline-ms deadline-ms}))))

(defn- format-code-via-jolt!
  "The text jonquil.syntax/format-code produces for whatever is on disk at
  path right now, computed by shelling a real `jolt -M -e` (AGENTS.md:
  `jolt -e` ignores deps.edn; `-M` loads the project classpath) -- never
  hand-written, per this task's own instructions, so a future change to
  the formatter's own rules can't silently desync this step from the code
  it claims to check. Throws on a non-zero exit, naming jolt's own stderr:
  a step that silently compared against a blank/garbage expected string
  would be worse than no check at all."
  [path]
  (let [code (str "(require (quote [jonquil.syntax :as syntax])) "
                  "(print (syntax/format-code (slurp " (pr-str path) ")))")
        {:keys [exit out err]} (p/shell {:out :string :err :string :continue true}
                                        "jolt" "-M" "-e" code)]
    (when-not (zero? exit)
      (throw (ex-info "jolt -M -e (format-code) failed" {:exit exit :err err})))
    out))

(defn- format-selection-via-jolt!
  "The text jonquil.syntax/format-selection-text produces for the LAST
  line of whatever is on disk at path right now -- this step's fixture
  puts the caret at end-of-buffer by default right after launch
  (step-run-selection!'s own measured behaviour), landing on the
  fixture's last line with no selection, so commands/format-selection-op
  formats exactly that line. start/end are computed with the exact
  formula jonquil.commands/line-bounds uses (the last \\n before the
  caret, or 0; the next \\n at/after the caret, or the text's own
  length) -- not itself the formatter under test, just locating where
  that one line starts and ends; the real formatting comes from a live
  `jolt -M -e` call into jonquil.syntax/format-selection-text (AGENTS.md:
  `jolt -e` ignores deps.edn; `-M` loads the project classpath), never
  hand-written, same reasoning as format-code-via-jolt!. Throws on a
  non-zero exit, naming jolt's own stderr."
  [path]
  (let [code (str "(require (quote [jonquil.syntax :as syntax])) "
                  "(let [text (slurp " (pr-str path) ") "
                  "      caret (count text) "
                  "      nl (when (pos? caret) (clojure.string/last-index-of text \"\\n\" (dec caret))) "
                  "      s (if nl (inc nl) 0) "
                  "      e (or (clojure.string/index-of text \"\\n\" caret) (count text))] "
                  "(print (syntax/format-selection-text text s e)))")
        {:keys [exit out err]} (p/shell {:out :string :err :string :continue true}
                                        "jolt" "-M" "-e" code)]
    (when-not (zero? exit)
      (throw (ex-info "jolt -M -e (format-selection-text) failed" {:exit exit :err err})))
    out))

;; --- steps ----------------------------------------------------------------
;; Each step launches its own fresh process (dev/demo_shots.clj's own
;; hard-won lesson: a shared process/window let one scene's leftover
;; console history bleed into the next one's screenshot; a fresh process
;; per step means a fresh window and an empty console every time, so an
;; OCR/pixel assertion never depends on what an earlier step left behind).

(defn- step-run-and-colour!
  "Brief step 2 (Run shows output and => nil, status Ready) plus the
  colour spot check (:out, not :prompt blue) on the same output line,
  since the check needs exactly the line step 2 already produces."
  []
  (let [path (scratch-copy! "run.clj")
        {:keys [proc pid]} (launch! path)]
    (ensure-window! pid 8000)
    (cg-key! pid "f5")
    (let [{:keys [ok last]} (wait-for-ocr! pid 8000 "smoke-run")
          ok-nil (str/includes? last "=> nil")
          ok-ready (wait-for-ocr! pid 3000 "Ready")
          win (window-for pid)
          scale (or (:scale win) 2.0)
          wx (:x win) wy (:y win)
          out-row (some (fn [r] (when (= "smoke-run" (:text r)) r)) (ocr-rows! pid))]
      (screenshot! pid "step2-run-output")
      (check! :run-shows-output (and ok ok-nil (:ok ok-ready))
              {:console last :ready (:ok ok-ready)})
      (if (and out-row wx wy)
        (let [px (int (* scale (- (:x out-row) wx)))
              py (int (* scale (- (:y out-row) wy)))
              pw (max 10 (int (* scale (:w out-row))))
              ph (max 6 (int (* scale (:h out-row))))
              shot (str out-dir "/step2-run-output.png")
              {:keys [out exit err]} (p/shell {:out :string :err :string :continue true}
                                              "java" pixel-at-jar shot
                                              (str px) (str py) (str pw) (str ph))]
          (if (zero? exit)
            (let [[r g b] (map #(Long/parseLong %) (str/split (str/trim out) #","))
                  dist-out (+ (Math/abs (- r 0x28)) (Math/abs (- g 0x28)) (Math/abs (- b 0x28)))
                  dist-prompt (+ (Math/abs (- r 0x00)) (Math/abs (- g 0x64)) (Math/abs (- b 0xc8)))]
              (check! :colour-spot-check (< dist-out dist-prompt)
                      {:sampled-rgb [r g b] :dist-out dist-out :dist-prompt dist-prompt
                       :rect [px py pw ph]}))
            (check! :colour-spot-check false {:pixel-at-exit exit :pixel-at-err err :rect [px py pw ph]})))
        (check! :colour-spot-check false {:reason "no OCR row for smoke-run" :window win})))
    (stop! {:proc proc :pid pid})))

(defn- step-repl-history!
  "Brief step 1 (P3.T6): un-skips P2's own step 3. F6 (P3.T3,
  do-toggle-focus!) now gives the console a keyboard-reachable focus path
  that didn't exist when this step's earlier SKIP (P2.T10, see git
  history) was written -- F6, type \"(inc 1)\" by chords, Return, then Up
  recalls it from history (console-view/handle-history!) and a second
  Return resubmits the SAME text, so the result line has to appear twice.

  Counted with wait-for-ocr-count! against #\"=>\\s*2\", not a plain
  wait-for-ocr! substring check: measured live this session, the SAME
  console content read back split (\"=>\" then \"2\" as two OCR rows) on
  one OCR pass and unified (\"=> 2\", one row) on the next, so a literal
  substring search on ocr-text!'s newline join can miss an occurrence
  that's really there -- see ocr-rows->loose-text's own docstring."
  []
  (let [{:keys [proc pid]} (launch! nil)]
    (ensure-window! pid 8000)
    (cg-key! pid "f6")
    (type-text! pid "(inc 1)")
    (cg-key! pid "return")
    (let [{:keys [ok count]} (wait-for-ocr-count! pid 5000 #"=>\s*2" 1)]
      (when-not ok
        (check! :repl-history-first-eval false {:reason "no => 2 after the first Return" :count count})))
    (cg-key! pid "up")
    (cg-key! pid "return")
    (let [{:keys [ok count last]} (wait-for-ocr-count! pid 5000 #"=>\s*2" 2)]
      (screenshot! pid "step1-repl-history")
      (check! :repl-history (and ok (>= count 2)) {:count count :console last}))
    (stop! {:proc proc :pid pid})))

(defn- step-read-line!
  "Brief step 2 (P3.T6): completes P2's own step 4. F6 (P3.T3) closes the
  console-focus gap that used to make read-line's own answer unreachable
  (this fn's earlier docstring, and step-repl-history!'s, cover why) -- so
  this now answers read-line for real instead of only interrupting it:
  once the status shows Waiting for input, F6 focuses the console, \"kim\"
  is typed by chords, and Return submits it as :send-stdin
  (console/input-action, waiting? true), which greet.clj's own
  `(read-line)` picks up and prints \"Hi kim\". This replaces the earlier
  Escape-interrupt-while-waiting check (that path is still exercised by
  step-stop-infinite-loop!/step-stop-print-forever!'s own Escape-from-
  :running case; only the read-line-specific interrupt regression drops
  out here, per the brief's own step 2 wording)."
  []
  (let [path (scratch-copy! "greet.clj")
        {:keys [proc pid]} (launch! path)]
    (ensure-window! pid 8000)
    (cg-key! pid "f5")
    (let [{:keys [ok]} (wait-for-ocr! pid 5000 "Waiting for input")]
      (when-not ok (check! :read-line-waiting false {:reason "status never showed Waiting for input"})))
    (cg-key! pid "f6")
    (type-text! pid "kim")
    (cg-key! pid "return")
    (let [{:keys [ok last]} (wait-for-ocr! pid 5000 "Hi kim")]
      (screenshot! pid "step2-read-line-answered")
      (check! :read-line-answered ok {:console last}))
    (stop! {:proc proc :pid pid})))

(defn- step-run-selection!
  "Brief step 3: runsel.clj is two lines, each a self-contained expression
  (no line depends on the other, unlike run.clj/greet.clj which run
  through session/run-definitions! first) -- Run Selection (Cmd+E) sends
  straight to the REPL, so a fixture that needed a prior Run would read
  as broken here for the wrong reason. The caret sits at the end of the
  buffer right after launch: measured live this session against this
  exact fixture shape (Ln 2, Col 9 after launch, exactly the last line's
  own length, no trailing newline in the fixture) -- the brief's own
  expectation, confirmed rather than assumed. With no selection,
  commands/run-selection-text (P3.T1) sends the caret's own line, so
  Cmd+E must show the LAST line's value, 42, not the first line's (3)."
  []
  (let [path (scratch-copy! "runsel.clj")
        {:keys [proc pid]} (launch! path)]
    (ensure-window! pid 8000)
    (cg-key! pid "command + e")
    (let [{:keys [ok last]} (wait-for-ocr! pid 5000 "=> 42")]
      (screenshot! pid "step3-run-selection")
      (check! :run-selection ok {:console last}))
    (stop! {:proc proc :pid pid})))

(defn- step-toggle-comment!
  "Brief step 4: select all (Cmd+A), toggle comment (Cmd+/,
  edit/toggle-comment, P3.T2), Save (Cmd+S). Every non-blank line's own
  bytes on disk must start with \"; \" (edit/toggle-comment's own leading
  comment marker)."
  []
  (let [path (scratch-copy! "comment.clj")
        {:keys [proc pid]} (launch! path)]
    (ensure-window! pid 8000)
    (cg-key! pid "command + a")
    (cg-key! pid "command + /")
    (cg-key! pid "command + s")
    (let [title-ok (wait-for! 5000 200
                              (fn [] (let [w (window-for pid)]
                                       (and w (not (str/starts-with? (or (:name w) "") "*"))))))
          on-disk (try (slurp path) (catch Exception _ nil))
          lines (when (seq on-disk) (str/split-lines on-disk))
          all-commented? (and (seq lines) (every? #(str/starts-with? % "; ") lines))]
      (screenshot! pid "step4-toggle-comment")
      (check! :toggle-comment (and title-ok all-commented?) {:title-lost-star title-ok :on-disk on-disk}))
    (stop! {:proc proc :pid pid})))

(defn- step-format-all!
  "Brief step 5: a mis-indented fixture, Cmd+Shift+F
  (commands/format-all-op, P3.T2), Cmd+S. The file's own bytes on disk
  must equal jonquil.syntax/format-code's real output for the ORIGINAL
  fixture text (format-code-via-jolt!, a live `jolt -M -e` call, per this
  task's own instructions -- never hand-written). The extra (not=
  original expected) assertion is this step's own falsifiability check
  (subagent-prompt-conventions.md: \"each PASS would fail if the feature
  broke\") -- without it, a fixture that happened to already be correctly
  formatted would let a completely broken format-all-op pass this step by
  doing nothing at all."
  []
  (let [path (scratch-copy! "format.clj")
        original (slurp path)
        expected (format-code-via-jolt! path)
        {:keys [proc pid]} (launch! path)]
    (ensure-window! pid 8000)
    (cg-key! pid "command + shift + f")
    (cg-key! pid "command + s")
    (let [title-ok (wait-for! 5000 200
                              (fn [] (let [w (window-for pid)]
                                       (and w (not (str/starts-with? (or (:name w) "") "*"))))))
          on-disk (try (slurp path) (catch Exception _ nil))]
      (screenshot! pid "step5-format-all")
      (check! :format-all (and title-ok (not= original expected) (= expected on-disk))
              {:title-lost-star title-ok :expected expected :on-disk on-disk}))
    (stop! {:proc proc :pid pid})))

(defn- step-format-selection!
  "Cmd+Option+F bug fix's own smoke step (the bug this whole dispatch was
  for: Cmd+Option+F/L did nothing in the built macOS app). A mis-indented
  fixture whose last line is out of indent, Cmd+Option+F, Cmd+S. On
  macOS, GDK reports the Option-translated character as keyval (0x8f6,
  GDK_KEY_function, for Option+F) instead of the plain letter, so
  editor.clj's alt-chord-f-or-l? falls back to the physical key's
  hardware keycode (3, matching kVK_ANSI_F) when the keyval doesn't
  match -- see that function's own docstring for the measured values.
  The file's own bytes on disk must equal
  jonquil.syntax/format-selection-text's real output for the ORIGINAL
  fixture text over the last line's bounds
  (format-selection-via-jolt!, a live `jolt -M -e` call -- never
  hand-written). The extra (not= original expected) assertion is this
  step's own falsifiability check, same reasoning as step-format-all!'s
  own comment: without it, a fixture that happened to already be
  correctly formatted would let a completely broken Cmd+Option+F pass
  this step by doing nothing at all. This step FAILS on the pre-fix
  code (measured this task's own session, both via
  dev/gtk_selftest.clj's new macOS rows and a live pid-driven probe
  against the built binary): the old dispatch only ever compared keyval
  against #{KEY-F KEY-L}, and Option's translated keyval never matches
  either member of that set."
  []
  (let [path (scratch-copy! "format_selection.clj")
        original (slurp path)
        expected (format-selection-via-jolt! path)
        {:keys [proc pid]} (launch! path)]
    (ensure-window! pid 8000)
    (cg-key! pid "command + option + f")
    (cg-key! pid "command + s")
    (let [title-ok (wait-for! 5000 200
                              (fn [] (let [w (window-for pid)]
                                       (and w (not (str/starts-with? (or (:name w) "") "*"))))))
          on-disk (try (slurp path) (catch Exception _ nil))]
      (screenshot! pid "step-format-selection")
      (check! :format-selection (and title-ok (not= original expected) (= expected on-disk))
              {:title-lost-star title-ok :expected expected :on-disk on-disk}))
    (stop! {:proc proc :pid pid})))

(defn- step-find!
  "Brief step 6: Cmd+F opens the bar already focused on the query entry
  (find-bar/open!, P3.T4), types \"findme\" by chords, Return. The title
  must stay unchanged (the find bar never dirties the buffer), and at
  least one match's own highlighted pixels must sample close to the
  search-match tag's colour, #fff2a8 (jonquil.gtk.find-bar/SEARCH-MATCH-BG).

  \"At least one\" rather than \"whichever Return leaves selected\":
  measured live this session against this exact two-line fixture, Return's
  own find-next! (P3.T4) leaves ONE match as the buffer's text selection,
  and GTK renders a SELECTED match's highlight visibly dimmer (198,191,155
  measured) than an unselected one's (255,242,168 measured -- an exact
  match) -- the tag and the selection paint blend. Sampling every OCR row
  that contains the query text, not only the current match, is what makes
  this assertion true regardless of which of the two ends up selected.

  Pixel sampling reuses PixelAt.java's darkest/lightest-cluster idea
  (step-run-and-colour!) but adds a \"near\" mode (this task): a plain
  lightest-cluster read plain WHITE background ahead of the yellow
  highlight, because #fff2a8's own mean channel value (221) is lower than
  white's (255) -- measured live, see PixelAt.java's own comment. \"near\"
  sorts by distance to the target colour instead, so it finds the fill
  regardless of which cluster has the higher luminance. Window position
  is re-read fresh right before ocr-rows!/screenshot! (not from an earlier
  call): measured live this session that this window's reported x/y drifts
  between separate cgevent calls after key events land, the same reason
  step-run-and-colour! already reads window-for immediately before using
  it."
  []
  (let [path (scratch-copy! "find.clj")
        {:keys [proc pid]} (launch! path)]
    (ensure-window! pid 8000)
    (cg-key! pid "command + f")
    (type-text! pid "findme")
    (cg-key! pid "return")
    (let [{:keys [ok]} (wait-for-ocr! pid 5000 "findme")]
      (when-not ok (check! :find-setup false {:reason "\"findme\" never showed in OCR after Return"})))
    (let [win (window-for pid)
          scale (or (:scale win) 2.0)
          wx (:x win) wy (:y win)
          rows (filter (fn [r] (str/includes? (or (:text r) "") "findme")) (ocr-rows! pid))
          shot (str out-dir "/step6-find.png")
          _ (screenshot! pid "step6-find")
          title-ok (not (str/starts-with? (or (:name win) "") "*"))
          samples (when (and wx wy (seq rows))
                    (keep (fn [row]
                            (let [px (int (* scale (- (:x row) wx)))
                                  py (int (* scale (- (:y row) wy)))
                                  pw (max 10 (int (* scale (:w row))))
                                  ph (max 6 (int (* scale (:h row))))
                                  {:keys [out exit]}
                                  (p/shell {:out :string :err :string :continue true}
                                           "java" pixel-at-jar shot
                                           (str px) (str py) (str pw) (str ph)
                                           "near" "255" "242" "168")]
                              (when (zero? exit)
                                (let [[r g b] (map #(Long/parseLong %) (str/split (str/trim out) #","))]
                                  {:rgb [r g b]
                                   :dist (+ (Math/abs (- r 0xff)) (Math/abs (- g 0xf2)) (Math/abs (- b 0xa8)))}))))
                          rows))
          close-enough? (boolean (some #(< (:dist %) 40) samples))]
      (check! :find (and title-ok close-enough?)
              {:title-ok title-ok :row-count (count rows) :samples samples}))
    (stop! {:proc proc :pid pid})))

(defn- step-pre-run-check!
  "Brief step 7: the (undefined-fn)\\n(defn g [y] fixture (unclosed.clj),
  F5. commands/pre-run-problem (P3.T1) must catch the unclosed \"(\" on
  line 2 BEFORE anything evaluates, so OCR shows \"never closed\" and
  never \"resolve\" (the unresolved-symbol error the first form would
  otherwise throw, if the bracket check didn't run first) -- the same
  regression dev/gtk_selftest_window.clj's own row-pre-run-hidden-by-error
  guards in-process; this drives the same behaviour from outside it."
  []
  (let [path (scratch-copy! "unclosed.clj")
        {:keys [proc pid]} (launch! path)]
    (ensure-window! pid 8000)
    (cg-key! pid "f5")
    (let [{:keys [ok last]} (wait-for-ocr! pid 5000 "never closed")]
      (screenshot! pid "step7-pre-run-check")
      (check! :pre-run-check (and ok (not (str/includes? last "resolve"))) {:console last}))
    (stop! {:proc proc :pid pid})))

(defn- dirty-title?
  "True once this pid's window title carries the \"* \" dirty marker
  (update-title!, P2/P3.T5), polled rather than read once -- typed chords
  land asynchronously and the dirty tick (editor.clj's own 20ms debounce)
  needs a moment to catch up. Shared by step-close-with-unsaved! and
  step-quit-with-unsaved!, both of which need a genuinely dirty buffer
  before sending their own chord."
  [pid]
  (wait-for! 3000 200 (fn [] (let [w (window-for pid)] (and w (str/starts-with? (or (:name w) "") "*"))))))

(defn- step-close-with-unsaved!
  "Brief step 8: typing makes the editor dirty (title gains \"* \"), then
  Cmd+W must open the close-confirmation alert (close-request-fn, P3.T5) as
  a SECOND top-level window for this pid -- checked via `cgevent windows
  --pid`, since a real GtkAlertDialog renders as its own window, never a
  native macOS sheet (P3 probe finding #3) -- with the process still alive
  (the alert only SUSPENDS the close, close-request-fn returns 1; it never
  quits on its own). This is a real check! (:close-with-unsaved-alert-
  appears): fix round 1 (task review) found the earlier version SKIPped
  here instead, so a total regression of the close-confirmation feature
  (Cmd+W just closes/quits with no prompt at all) read as SKIP and dropped
  out of -main's scored denominator -- SUMMARY still said \"N of N passed\"
  while unsaved text was being silently discarded. No alert, or the
  process already gone, is now :close-with-unsaved FAIL, not a skip.

  Escape by pid is then tried as the alert's own Cancel (index 2,
  close-choice!): measured live this session (not assumed from the brief)
  that it DOES reach the alert -- closes the SECOND window, back down to
  one, process still alive, title still carrying \"* \" (close-choice!'s
  own index-2 branch touches nothing). The SKIP is now narrow and only
  fires here: the alert appeared, Escape was sent, and NOTHING changed --
  still 2 windows, process still alive -- which is what \"the alert
  doesn't take pid-routed keys\" (the brief's own SKIP wording) actually
  means. If Escape instead closes the main window too, or the process
  dies, that is not \"didn't take the key\" -- it took it and did the
  wrong (data-losing) thing, so it FAILs :close-with-unsaved rather than
  skipping."
  []
  (let [{:keys [proc pid]} (launch! nil)]
    (ensure-window! pid 8000)
    (type-text! pid "1")
    (when-not (dirty-title? pid)
      (check! :close-with-unsaved-setup false {:reason "title never gained * after typing"}))
    (cg-key! pid "command + w")
    (let [alert-appeared? (wait-for! 4000 200 (fn [] (= 2 (count (windows-edn! pid)))))
          alive-after-w? (p/alive? proc)]
      (screenshot! pid "step8-close-alert")
      (check! :close-with-unsaved-alert-appears
              (and alert-appeared? alive-after-w?)
              {:alert-appeared alert-appeared? :alive-after-w alive-after-w?})
      (if-not (and alert-appeared? alive-after-w?)
        (check! :close-with-unsaved false
                {:reason "no alert (or the process died) after command+w -- see close-with-unsaved-alert-appears"
                 :alert-appeared alert-appeared? :alive-after-w alive-after-w?})
        (do
          (cg-key! pid "escape")
          (wait-for! 4000 200 (fn [] (not= 2 (count (windows-edn! pid)))))
          (let [windows-after (windows-edn! pid)
                window-count (count windows-after)
                still-alive? (p/alive? proc)
                title (:name (first windows-after))]
            (screenshot! pid "step8-close-cancelled")
            (if (and (= 2 window-count) still-alive?)
              (skip! :close-with-unsaved
                     "the alert stayed open after a pid-routed Escape (still 2 windows, process alive) -- it doesn't take pid-routed keys, can't verify Cancel without a click")
              (check! :close-with-unsaved
                      (and still-alive? (= 1 window-count) (str/starts-with? (or title "") "*"))
                      {:window-count window-count :still-alive still-alive? :title title}))))))
    (stop! {:proc proc :pid pid})))

(defn- step-quit-with-unsaved!
  "Controller-added step 9 (task-6-brief.md, not in the store plan);
  corrected by task-7a's own controller ruling after the P3 phase-close
  fix wave. This step's earlier docstring (git history) said Cmd/Ctrl+Q
  was unbound and reached nothing in this codebase -- that was true when
  it was written, but P3 fix wave C3 (window.clj, `quit-action`) gave the
  GtkApplication a \"quit\" action wired to `(gtk-window-close win)`, which
  is exactly the accelerator GTK's macOS delegate takes for Cmd+Q. A
  dirty, unconfirmed close re-enters close-request-fn the same way Cmd+W
  does (step-close-with-unsaved!'s own path), so Cmd+Q on a dirty buffer
  now shows the same real GtkAlertDialog instead of doing nothing.

  PASS needs the alert to appear as a SECOND top-level window for this
  pid (the same `cgevent windows --pid` check step-close-with-unsaved!
  uses -- a real GtkAlertDialog is its own window, never a native macOS
  sheet, P3 probe finding #3) AND the process to stay alive throughout.
  ANY process exit here is a FAIL outright (unsaved text silently
  discarded), checked immediately after the alert-appeared wait rather
  than assumed -- a process that died mid-wait reads as alert-appeared?
  false / alive-after-q? false, the same FAIL shape as no alert at all.
  Once the alert is confirmed, a pid-routed Escape cancels it -- measured
  live in step-close-with-unsaved! that Escape reaches the alert's own
  Cancel (index 2, close-choice!) -- and the title must still carry the
  \"* \" dirty marker afterward, with exactly one window left and the
  process still alive: Cancel touches nothing, so losing any of those
  three would mean Escape hit the wrong window or something else closed
  behind it."
  []
  (let [{:keys [proc pid]} (launch! nil)]
    (ensure-window! pid 8000)
    (type-text! pid "1")
    (when-not (dirty-title? pid)
      (check! :quit-with-unsaved-setup false {:reason "title never gained * after typing"}))
    (cg-key! pid "command + q")
    (let [alert-appeared? (wait-for! 4000 200 (fn [] (= 2 (count (windows-edn! pid)))))
          alive-after-q? (p/alive? proc)]
      (screenshot! pid "step9-quit-alert")
      (check! :quit-with-unsaved-alert-appears
              (and alert-appeared? alive-after-q?)
              {:alert-appeared alert-appeared? :alive-after-q alive-after-q?})
      (if-not (and alert-appeared? alive-after-q?)
        (check! :quit-with-unsaved false
                {:reason "no alert (or the process exited) after command+q -- unsaved text may have been discarded"
                 :alert-appeared alert-appeared? :alive-after-q alive-after-q?})
        (do
          (cg-key! pid "escape")
          (wait-for! 4000 200 (fn [] (not= 2 (count (windows-edn! pid)))))
          (let [windows-after (windows-edn! pid)
                window-count (count windows-after)
                still-alive? (p/alive? proc)
                title (:name (first windows-after))]
            (screenshot! pid "step9-quit-cancelled")
            (check! :quit-with-unsaved
                    (and still-alive? (= 1 window-count) (str/starts-with? (or title "") "*"))
                    {:window-count window-count :still-alive still-alive? :title title}))))
      (stop! {:proc proc :pid pid}))))

(defn- step-stop-infinite-loop!
  "Brief step 5: an infinite (silent) loop, stopped by Escape."
  []
  (let [path (scratch-copy! "loop.clj")
        {:keys [proc pid]} (launch! path)]
    (ensure-window! pid 8000)
    (cg-key! pid "f5")
    (let [{:keys [ok]} (wait-for-ocr! pid 3000 "Running")]
      (when-not ok (check! :loop-started false {:reason "status never showed Running"})))
    (cg-key! pid "escape")
    (let [{:keys [ok last]} (wait-for-ocr! pid 5000 "interrupted")
          stopped (wait-for-ocr! pid 3000 "Stopped")]
      (screenshot! pid "step5-stop-infinite-loop")
      (check! :stop-infinite-loop (and ok (:ok stopped)) {:console last}))
    (stop! {:proc proc :pid pid})))

(defn- step-stop-print-forever!
  "Brief step 9: a print-forever loop, stopped by Escape, window still
  responsive. Measured live: this fixture prints fast enough that by the
  time OCR runs, \"; interrupted\" itself can already have scrolled out of
  the console's visible viewport, pushed up by more \"tick\" lines that
  were already queued (AGENTS.md's own GTK fact: \"the result line is
  queued after the status flips\", so a burst of already-buffered output
  can still land after it) -- a first version of this step asserted on
  seeing \"interrupted\" in the OCR text and failed on exactly that,
  correctly-behaving run. The status label (a separate widget, never part
  of the scrolling console, always fully visible) doesn't have this
  problem, so this asserts on it reaching \"Stopped\" instead -- the same
  durable signal step-stop-infinite-loop! also checks."
  []
  (let [path (scratch-copy! "forever.clj")
        {:keys [proc pid]} (launch! path)]
    (ensure-window! pid 8000)
    (cg-key! pid "f5")
    (let [{:keys [ok]} (wait-for-ocr! pid 4000 "tick")]
      (when-not ok (check! :forever-started false {:reason "no tick output seen"})))
    (cg-key! pid "escape")
    (let [{:keys [ok last]} (wait-for-ocr! pid 5000 "Stopped")
          responsive (seq (windows-edn! pid))]
      (screenshot! pid "step9-stop-print-forever")
      (check! :stop-print-forever (and ok responsive) {:console last :responsive (boolean responsive)}))
    (stop! {:proc proc :pid pid})))

(defn- step-save!
  "Brief step 7: Cmd+S; the file bytes on disk equal what the editor held,
  and the title loses \"* \". The exact edit is select-all + delete + a
  freshly typed replacement (\"(+ 1 2)\") rather than a literal append at
  the fixture's end -- probed live this session that select-all+delete+
  retype lands byte-for-byte as typed (four chords typed back to back read
  back via OCR as exactly `+\"-.`, no stray characters), which this step
  reuses directly for a robust, fully-verified path; positioning the caret
  at end-of-buffer to append instead was never itself measured against
  this build, so it isn't the one this step depends on."
  []
  (let [path (scratch-copy! "save.clj")
        {:keys [proc pid]} (launch! path)]
    (ensure-window! pid 8000)
    (cg-key! pid "command + a")
    (cg-key! pid "delete")
    (type-text! pid "(+ 1 2)")
    (cg-key! pid "command + s")
    (let [title-ok (wait-for! 5000 200
                              (fn [] (let [w (window-for pid)]
                                       (and w (not (str/starts-with? (or (:name w) "") "*"))))))
          on-disk (try (slurp path) (catch Exception _ nil))]
      (screenshot! pid "step7-save")
      (check! :save (and title-ok (= "(+ 1 2)" on-disk))
              {:title-lost-star title-ok :on-disk on-disk :window-name (:name (window-for pid))}))
    (stop! {:proc proc :pid pid})))

(defn- step-open-refuses-dirty!
  "Brief step 10 (first half): with a dirty buffer, command+o prints the
  refusal line and opens no panel -- checked here as \"still exactly one
  window for this pid\" (a real GtkFileDialog would add a second
  top-level surface), since pid-routed keys can't drive a native panel's
  buttons anyway even if one did open."
  []
  (let [{:keys [proc pid]} (launch! nil)]
    (ensure-window! pid 8000)
    (type-text! pid "1")
    (cg-key! pid "command + o")
    (let [{:keys [ok last]} (wait-for-ocr! pid 4000 "Unsaved changes")
          window-count (count (windows-edn! pid))]
      (screenshot! pid "step10a-open-refuses-dirty")
      (check! :open-refuses-dirty (and ok (= 1 window-count)) {:console last :window-count window-count}))
    (stop! {:proc proc :pid pid})))

(defn- write-latin1-fixture!
  "A file with one stray non-UTF-8 byte (0xE9, the same byte
  gtk_selftest_window.clj's own row-open-invalid-utf8 uses), generated at
  runtime into out-dir rather than checked in -- an intentionally-broken-
  encoding file has no business living beside the real fixtures, the same
  reason that self-test row builds its own instead of a static file."
  []
  (let [path (str out-dir "/" (System/nanoTime) "-latin1.clj")]
    (with-open [os (java.io.FileOutputStream. ^String path)]
      (.write os (byte-array [(byte 0x28) (unchecked-byte 0xE9) (byte 0x29)])))
    path))

(defn- step-latin1-warning!
  "Brief step 10 (second half): the Latin-1 warning, checked by launching
  a SECOND instance with the Latin-1 fixture as its file argument (opening
  through the native panel would need clicks, which pid-routing can't
  deliver -- p2t10-brief.md is explicit about this). Also checks the
  file's bytes are unchanged on disk, since Jonquil must never rewrite a
  file it only opened."
  []
  (let [path (write-latin1-fixture!)
        before (fs/read-all-bytes path)
        {:keys [proc pid]} (launch! path)]
    (ensure-window! pid 8000)
    (let [{:keys [ok last]} (wait-for-ocr! pid 5000 "valid UTF-8")
          after (fs/read-all-bytes path)]
      (screenshot! pid "step10b-latin1-warning")
      (check! :latin1-warning (and ok (= (seq before) (seq after)))
              {:console last :bytes-before (seq before) :bytes-after (seq after)}))
    (stop! {:proc proc :pid pid})))

(defn- step-command-chord-close!
  "Brief step 11: the command chord plus W; the process exits 0. This
  step's own launch! process is deref'd for its REAL exit code (not just
  \"pid no longer in pgrep\"), which is the one thing this smoke test can
  check that the in-process self-test (P2.T10's other commit) cannot --
  that self-test calls the key handler directly inside the same process
  it's testing, so it can prove the handler returns 1 but never that the
  OS process this handler's caller lives in actually terminates with
  exit 0."
  []
  (let [{:keys [proc pid]} (launch! nil)]
    (ensure-window! pid 8000)
    (cg-key! pid "command + w")
    (let [exit (deref proc 5000 ::timeout)]
      (if (= ::timeout exit)
        (do (check! :command-chord-close-exits false {:reason "process did not exit within 5000ms"})
            (stop! {:proc proc :pid pid}))
        (do (check! :command-chord-close-exits (zero? (:exit exit)) {:exit (:exit exit)})
            (swap! launched (fn [v] (vec (remove (fn [m] (= pid (:pid m))) v)))))))))

(defn- step-close-while-running!
  "Fix round 1, Important #1: closing while a program is running was
  untested. window.clj's Cmd/Ctrl+W branch calls gtk_window_close with no
  evaluating? guard, and the pre-existing close-request handler calls
  g_application_quit unconditionally -- the eval worker may still call
  on-gui! (g_idle_add) after the window and widgets are gone, and so may
  the red close button, which predates this task. forever.clj floods
  cview/append! (itself on-gui!-based) from the eval worker thread far
  faster than a silent loop would, which is exactly the shape of race that
  would matter if there were one.

  Measured live against the built binary, outside this driver (bash +
  cgevent directly, not this script), before writing this step: 10 runs
  across two timing profiles (a normal ~1s gap between F5 and the close
  chord, and a zero-gap back-to-back send) all exited 0 with no stderr at
  all, except one run's single, already-documented-benign GLib warning
  (\"Trying to snapshot GtkGizmo ... without a current allocation\", the
  same pane-resize noise smoke-results.md's human walkthrough already
  saw). Zero crashes, zero GTK criticals, in 10 for 10. This step encodes
  that same probe as a permanent regression check: real exit code, real
  stderr, read from the actual log launch! writes to, not inferred.

  A GTK critical/warning about invalid objects would name the class it's
  complaining about, and \"CRITICAL\" is the log-level token GLib itself
  prints for exactly that -- distinct from the ordinary WARNING-level
  pane-resize noise, so matching on it doesn't false-positive on already-
  known-benign chatter."
  []
  (let [path (scratch-copy! "forever.clj")
        {:keys [proc pid log]} (launch! path)]
    (ensure-window! pid 8000)
    (cg-key! pid "f5")
    (let [{:keys [ok]} (wait-for-ocr! pid 4000 "tick")]
      (when-not ok (check! :close-while-running-setup false {:reason "no tick output before closing"})))
    (cg-key! pid "command + w")
    (let [exit (deref proc 5000 ::timeout)
          log-text (try (slurp log) (catch Exception _ ""))
          critical? (str/includes? log-text "CRITICAL")]
      (if (= ::timeout exit)
        (do (check! :close-while-running false
                    {:reason "process did not exit within 5000ms while running" :log log-text})
            (stop! {:proc proc :pid pid}))
        (do (check! :close-while-running (and (zero? (:exit exit)) (not critical?))
                    {:exit (:exit exit) :critical critical? :log log-text})
            (swap! launched (fn [v] (vec (remove (fn [m] (= pid (:pid m))) v)))))))))

;; --- driver ----------------------------------------------------------------

(defn- build!
  "Builds ./jonquil into out-dir (never the repo root -- app:build's own
  `jolt build -m jonquil.main -o jonquil` task writes there, so this
  shells the same underlying jolt command with a different -o instead of
  reusing that task verbatim)."
  []
  (fs/create-dirs out-dir)
  (println "[binary-smoke] building" binary-path "...")
  (let [{:keys [exit err]} (p/shell {:out :string :err :string :continue true}
                                    "jolt" "build" "-m" "jonquil.main" "-o" binary-path)]
    (when-not (zero? exit)
      (binding [*out* *err*] (println "[binary-smoke] build FAILED:" err))
      (System/exit 1))))

(def ^:private steps
  [["run-and-colour" step-run-and-colour!]
   ["repl-history" step-repl-history!]
   ["read-line" step-read-line!]
   ["run-selection" step-run-selection!]
   ["toggle-comment" step-toggle-comment!]
   ["format-all" step-format-all!]
   ["format-selection" step-format-selection!]
   ["find" step-find!]
   ["pre-run-check" step-pre-run-check!]
   ["close-with-unsaved" step-close-with-unsaved!]
   ["quit-with-unsaved" step-quit-with-unsaved!]
   ["stop-infinite-loop" step-stop-infinite-loop!]
   ["stop-print-forever" step-stop-print-forever!]
   ["save" step-save!]
   ["open-refuses-dirty" step-open-refuses-dirty!]
   ["latin1-warning" step-latin1-warning!]
   ["command-chord-close" step-command-chord-close!]
   ["close-while-running" step-close-while-running!]])

(defn -main [& args]
  (let [t0 (System/currentTimeMillis)
        no-build (some #{"--no-build"} args)]
    (fs/create-dirs out-dir)
    (if no-build
      (when-not (fs/exists? binary-path)
        (binding [*out* *err*]
          (println "[binary-smoke] --no-build given but" binary-path "doesn't exist"))
        (System/exit 1))
      (build!))
    (try
      (doseq [[name step-fn] steps]
        (try
          (step-fn)
          (catch Exception e
            (check! (keyword name) false {:exception (ex-message e) :data (ex-data e)}))))
      (skip! :ime-editor-return "IME composition (Hangul/Thai) needs a real input method; human-only")
      (skip! :ime-console-return "same as above, console pane; human-only")
      (finally
        (kill-all-launched!)))
    (let [scored (remove #(= :skip (:ok %)) @results)
          passed (count (filter :ok scored))
          total (count scored)
          wall-ms (- (System/currentTimeMillis) t0)]
      (println (str "SUMMARY " passed " of " total " passed (" wall-ms "ms)"))
      (System/exit (if (= passed total) 0 1)))))

(apply -main *command-line-args*)
