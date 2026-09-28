;; P4.T3 automated: drives the BUILT Jonquil binary, via cgevent --pid, to
;; smoke the ClojureDart attach dialect against the user's REAL, running
;; a-sibling-cljd-app app (`bb dev:macos`). Run via `bb smoke:cljd` (see
;; bb.edn); a plain babashka script, not a jolt namespace, exactly like
;; dev/smoke/binary_smoke.clj -- it never touches the app or Jonquil from
;; the inside, only from outside via cgevent, a raw TCP connect check, and
;; the filesystem (read-only against the app's own files).
;;
;; This file automates the P4.T3 human walkthrough
;; (.superpowers/sdd/cljd/p4t3-brief.md); .superpowers/sdd/cljd/
;; smoke-cljd-brief.md is this task's own scope and is authoritative where
;; the two differ (p4t3-brief is a reference for expected text, written for
;; a human's own keystrokes -- e.g. its two-line Run Form snippet, typed
;; here the same way but positioned by search rather than by hand).
;;
;; Mechanisms copied from binary_smoke.clj (not required -- its helpers are
;; private, and it is never refactored by this task): cgevent! with its own
;; deadline, cg-key!/char->chord/type-text! (extended here with ! [ ] : --
;; measured live against this build before being relied on, see the report),
;; windows-edn!/window-for, ocr-text!/ocr-rows!/screenshot!, wait-for!/
;; wait-for-ocr!, launch!/stop!/ensure-window!, and the kill-all-launched!
;; try/finally shape.
;;
;; What is NOT copied: binary_smoke's "fresh process per step" convention.
;; This script drives ONE continuous Jonquil session across open/run/where/
;; next!-prev!/run-form/help (steps 1-5), matching the human walkthrough's
;; own single sitting, then a detach/reconnect within that SAME session
;; (step 6), then closes it (step 9). A fresh process per step would mean a
;; fresh nREPL attach cycle per step against the REAL app, which the hard
;; rules ask this script to keep to a minimum, and would also cost more
;; wall-clock time reconnecting than the caret bookkeeping it would save.
;;
;; Caret placement (p4t3-brief: "place the caret by the text search you'd
;; use for Find, or by line and column through the editor's go-to; pick the
;; most robust mechanism binary_smoke already uses"): this file leans on
;; Cmd+F (find-text!, below). jonquil.gtk.find-bar's own open!/recompute-
;; matches! select the match nearest the CURRENT caret on every keystroke
;; (commands/next-match: the first match at or after the caret, else wrap to
;; the first) -- so typing a query with NO trailing Return leaves the
;; editor's selection on exactly that match (gtk_text_buffer_select_range
;; with start<end puts the caret, the "insert" mark, at the match's START,
;; jonquil.gtk.find_bar.clj select-match!), and Escape (find-bar/close!)
;; strips only the search-match TAG, never the buffer's own selection. A
;; trailing Return would call find-next! an extra time, landing one match
;; past the one just selected -- so find-text! below never presses it.
;; jonquil.commands/run-selection-text sends the selection when one exists
;; (Cmd+E, "where"), and run-form (Cmd+Enter, "next!"/"prev!"/inside the
;; typed let) resolves the top-level form around the caret regardless of
;; any selection -- a caret at a match's start offset sits inside (or at
;; the very start of) that form either way (jonquil.commands/form-around).
;;
;; Search strings were checked against the live file's own line numbers
;; (this task's session, `grep -n`) for uniqueness or safe wrap-to-first
;; behaviour before being relied on:
;;   "(where)"    -- 8 occurrences (re-counted in fix round 2; a first
;;                   pass missed retitle-live!'s and restore-live!'s own
;;                   bodies, one of them followed by FIVE closing parens
;;                   on the SAME line -- see step-next-prev!'s own
;;                   docstring for what that cost). Caret starts at buffer
;;                   END on a fresh open (editor.clj's set-text!), so the
;;                   FIRST find of this query (no match at/after caret)
;;                   wraps to the FIRST occurrence in the file, the
;;                   (comment (where) ...) block -- exactly the target for
;;                   step "where", and safe every other time this file
;;                   searches it too (see below): each later search either
;;                   wraps to that same first occurrence again (caret past
;;                   all eight by then) or is the last one in the reveal
;;                   block, alone on its own line with nothing trailing.
;;                   The one search that could land on retitle-live!'s or
;;                   restore-live!'s own occurrence, from a caret in
;;                   between, was removed rather than made safe -- see
;;                   step-next-prev!'s docstring.
;;   "(next!)"    -- 4 occurrences (a bare call); only the "Move through
;;                   it" comment block's is reached with the caret left
;;                   where "(where)"'s find just put it (an earlier offset
;;                   than every other occurrence).
;;   "(prev!)"    -- exactly ONE occurrence (defn prev!'s own body never
;;                   calls itself), unambiguous regardless of caret.
;;   "  nil)"     -- exactly ONE occurrence with these two leading spaces
;;                   (line ~173, the reveal comment block's own closing
;;                   nil); "nil)" alone is not unique (also *before-edit's
;;                   init and restore-live!'s reset!), so this file always
;;                   searches the two-space form.
;; Every one of these is re-derived, not hard-coded from a remembered line
;; number: this script never assumes drive.cljd's line numbers, only that
;; each search string's uniqueness (or wrap-to-first safety) holds, which a
;; step's own OCR check (the echoed form, the printed result) falsifies if
;; it doesn't.
(ns cljd-smoke
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private out-dir "target/smoke")
(def ^:private binary-path "./jonquil")
(def ^:private app-dir (str (System/getProperty "user.home") "/dev-apps/a-sibling-cljd-app"))
(def ^:private drive-file (str app-dir "/dev/demo/deck/drive.cljd"))
(def ^:private nrepl-lock (str app-dir "/NREPL.lock"))
(def ^:private min-fork-sha "199d173d")

;; TH.T5-style isolation (binary_smoke.clj's own smoke-xdg-config-home): one
;; XDG_CONFIG_HOME temp dir for this run, handed to launch! via :extra-env,
;; so this script's own jonquil process never reads or writes the user's
;; real ~/.config/jonquil.
(def ^:private smoke-xdg-config-home
  (str (System/getProperty "java.io.tmpdir")
       "/jonquil-cljd-smoke-xdg-" (System/currentTimeMillis) "-" (System/nanoTime)))

;; --- process bookkeeping (binary_smoke.clj's own shape) --------------------

(defonce ^:private launched (atom []))

(defn- kill-all-launched!
  "Destroys every process this run started. Safe to call more than once."
  []
  (doseq [{:keys [proc pid]} @launched]
    (try
      (when (p/alive? proc) (p/destroy-tree proc))
      (catch Exception e
        (binding [*out* *err*]
          (println "[cljd-smoke] warning: couldn't kill pid" pid (ex-message e))))))
  (reset! launched []))

;; --- results -----------------------------------------------------------

(defonce ^:private results (atom []))

(defn- check! [step ok detail]
  (swap! results conj {:step step :ok (boolean ok) :detail detail})
  (println (str "[cljd-smoke] " (name step) " " (if ok "PASS" "FAIL") " " (pr-str detail))))

(defn- skip! [step reason]
  (swap! results conj {:step step :ok :skip :detail {:reason reason}})
  (println (str "[cljd-smoke] " (name step) " SKIP " reason)))

;; --- cgevent wrappers (binary_smoke.clj's own shape) ------------------------

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
    (throw (ex-info "cljd_smoke: refusing to post a key event with no pid" {:chord chord})))
  (let [{:keys [exit err]} (cgevent! "key" "--pid" (str pid) chord)]
    (when-not (zero? exit)
      (throw (ex-info (str "cgevent key failed: " chord) {:pid pid :chord chord :err err})))))

;; char->chord: binary_smoke.clj's own map, plus ! [ ] : -- measured live
;; against this build before this script relied on them (session probe:
;; select-all, delete, then "shift + 1" "[" "]" "shift + ;" in a row read
;; back via cgevent's OWN window/OCR path as exactly `![]:`, no stray
;; characters -- see the report for the transcript).
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
    \! "shift + 1"
    \[ "["
    \] "]"
    \: "shift + ;"}))

(defn- type-text!
  [pid s]
  (doseq [c s]
    (if-let [chord (get char->chord c)]
      (cg-key! pid chord)
      (throw (ex-info (str "cljd_smoke: no chord mapped for character " (pr-str c))
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

(defn- ocr-rows->loose-text [rows]
  (str/join " " (keep :text rows)))

(defn- ocr-matches? [rows joined needle]
  (if (instance? java.util.regex.Pattern needle)
    (boolean (re-find needle joined))
    (or (str/includes? joined needle)
        (str/includes? (ocr-rows->loose-text rows) needle))))

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

;; --- launching and stopping the binary under test (binary_smoke.clj's shape) --

(defn- launch!
  [& [file]]
  (let [log (str out-dir "/.launch-cljd-" (System/nanoTime) ".log")
        args (cond-> [binary-path] file (conj file))
        proc (p/process args {:out (io/file log) :err (io/file log) :shutdown p/destroy-tree
                              :extra-env {"XDG_CONFIG_HOME" smoke-xdg-config-home}})
        pid (.pid ^java.lang.Process (:proc proc))]
    (swap! launched conj {:proc proc :pid pid})
    {:proc proc :pid pid :log log}))

(defn- stop! [{:keys [proc pid]}]
  (try
    (when (p/alive? proc) (p/destroy-tree proc))
    (catch Exception _ nil))
  (swap! launched (fn [v] (vec (remove (fn [m] (= pid (:pid m))) v)))))

(defn- wait-for-window! [pid deadline-ms]
  (wait-for! deadline-ms 200 (fn [] (seq (windows-edn! pid)))))

(defn- ensure-window! [pid deadline-ms]
  (when-not (wait-for-window! pid deadline-ms)
    (throw (ex-info "jonquil did not open a window" {:pid pid :deadline-ms deadline-ms}))))

(defn- title-clean?
  "True once this pid's window title no longer carries the \"* \" dirty
  marker (editor.clj: dirty? is a live compare against the last-saved
  text, so a full undo clears this on its own -- see the ns docstring)."
  [pid]
  (let [w (window-for pid)] (and w (not (str/starts-with? (or (:name w) "") "*")))))

;; --- cljd-specific helpers ---------------------------------------------------

(defn- tcp-listening?
  "True when something accepts a TCP connect on 127.0.0.1:port within 2s.
  Connects and closes immediately -- no bytes sent or read, so this never
  speaks nREPL (the pre-flight's own hard rule)."
  [port]
  (try
    (with-open [s (java.net.Socket.)]
      (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) 2000)
      true)
    (catch Exception _ false)))

(defn- find-text!
  "Cmd+F, types text into the query with NO trailing Return (see the ns
  docstring for why), then Escape, then Left, then a short settle. Leaves
  the editor with a plain CARET at the match's own start -- no selection
  -- on whichever match commands/next-match picked from the caret before
  Cmd+F was pressed (the nearest one at or after it, else the first in
  the buffer).

  Fix round 1 (live run, this task's session) added the settle alone
  (250ms after Escape), on the theory that the very first character typed
  right after find-text! was racing editor/focus!'s own return of input
  focus. Fix round 2, after a live probe against a throwaway scratch file
  (never the app -- see the report), found the REAL mechanism and it
  isn't a race: close! (jonquil.gtk.find_bar.clj) leaves the MATCH ITSELF
  selected (gtk_text_buffer_select_range with start<end), not just a
  caret, and jonquil.edit/insert-open treats an open-bracket typed onto an
  active selection completely differently from one typed onto a bare
  caret -- it WRAPS the selection in that bracket and its closer, rather
  than inserting an empty pair. Typing `(let [w (where)]` onto a selected
  `\"  nil)\"` therefore never inserted a fresh `(let ...)` at all: probed
  directly (scratch file `(foo bar baz)`, find \"bar\", type one
  character with the selection still active) -- `q` typed onto a
  selection REPLACES it outright (`\"(foo bar baz)\"` -> `\"(foo q
  baz)\"`), matching a Cmd+Enter/type sequence that starts with a
  non-bracket character just as much as one starting with `(`. Left,
  by contrast, collapses the selection to its START with no other effect
  (probed the same way: `\"(foo bar baz)\"`, find \"bar\", Left, type
  `q` -> `\"(foo qbar baz)\"`, `q` landing exactly at the match's own
  start) -- so this now presses it before returning, every time, turning
  every call site's selection into the caret-only case every check in
  this file was already written to assume. Cmd+E (Run Selection) on a
  bare caret with no selection falls back to sending the caret's own
  LINE (jonquil.commands/run-selection-text) -- confirmed harmless for
  this file's own Cmd+E use (on `(where)`, which sits alone on its own
  line): a fourth probe found it sends and evaluates the same line
  correctly either way."
  [pid text]
  (cg-key! pid "command + f")
  (type-text! pid text)
  (cg-key! pid "escape")
  (cg-key! pid "left")
  (Thread/sleep 250))

(defn- parse-where
  "{:slide n :of n :step n}, tolerant of OCR noise between the keyword and
  its number (a dropped colon, an extra/missing comma), or nil when any of
  the three can't be found. `text` is the newline-joined OCR text. :title
  is deliberately left out of this map -- it feeds an exact `=` comparison
  in step-next-prev! (has (next!)/(prev!) landed on the very same
  position?), and a quoted title string is far more likely than a bare
  number to OCR slightly differently between two separate reads of the
  same on-screen text. See parse-title below for that one, used only where
  an exact title match isn't what's being asked.

  Fix round 1: takes the LAST match of each keyword in `text`, not the
  first. drive.cljd's own first comment block carries a literal worked
  example, `;; => {:slide 1, :of 54, :step 1, :title \"Production
  ClojureDart...\"}`, which sits in the editor pane above the console and
  is visible in OCR for most of this run -- re-find (first match) grabbed
  THAT static example instead of the console's own live result below it,
  every time. The live result is always the most recently PRINTED one, so
  it's always the last match in top-to-bottom OCR text; the example line
  never moves and is always first."
  [text]
  (let [grab (fn [re] (some-> (seq (re-seq re text)) last second parse-long))
        slide (grab #"(?i)slide\D{0,4}(\d+)")
        of (grab #"(?i)\bof\D{0,4}(\d+)")
        step (grab #"(?i)step\D{0,4}(\d+)")]
    (when (and slide of step)
      {:slide slide :of of :step step})))

(defn- parse-title
  "The quoted string following :title in `text` (the newline-joined OCR
  text of a (where) result), or nil. Best-effort only -- see parse-where's
  docstring for why this is kept separate from it, and for why this takes
  the LAST match, not the first.

  The capture group excludes newlines ([^\"\\n]*, not [^\"]*): a title is
  one line, and without this a stray \" anywhere later in the buffer's own
  SOURCE TEXT (visible above the console, e.g. inside a docstring) let the
  first version of this regex swallow everything from `where`'s own
  :title key, through several unrelated lines of source, up to that
  distant stray quote -- measured live this session, :expect-title came
  back as several lines of `where`'s own defn body."
  [text]
  (some-> (seq (re-seq #"(?i)title\D{0,4}\"([^\"\n]*)\"" text)) last second))

;; --- steps -------------------------------------------------------------

(defn- step-preflight!
  "Step 0. Everything after this depends on its result: {:ok bool}. Never
  sends an nREPL message -- only a bare TCP connect (tcp-listening?, no
  bytes) and read-only filesystem/`git status` reads."
  []
  (let [console (cgevent! "on-console" "--edn")
        console-info (try (edn/read-string (:out console)) (catch Exception _ {}))
        locked? (:screen-locked? console-info true)
        lock-exists (fs/exists? nrepl-lock)
        port (when lock-exists (try (parse-long (str/trim (slurp nrepl-lock))) (catch Exception _ nil)))
        listening (and port (tcp-listening? port))
        app-up (and lock-exists port listening)
        sha-match (re-find #":git/sha \"([0-9a-f]+)\"" (slurp (str app-dir "/deps.edn")))
        sha (second sha-match)
        fork-ok (and sha (str/starts-with? sha min-fork-sha))
        pre-git (:out (p/shell {:out :string :err :string :continue true}
                               "git" "-C" app-dir "status" "--short"))]
    (check! :preflight-display (not locked?)
            {:screen-locked locked? :on-console (:on-console? console-info)})
    (check! :preflight-app-up app-up
            {:lock-exists lock-exists :port port :listening listening})
    (check! :preflight-fork-version fork-ok {:sha sha :want-prefix min-fork-sha})
    {:ok (and app-up fork-ok (not locked?))
     :pre-git-status pre-git}))

(defn- step-open!
  [pid]
  (let [{:keys [ok last]} (wait-for-ocr! pid 8000 "ClojureDart")
        label-ok (and ok (str/includes? last "not running"))
        {exp-ok :ok exp-last :last} (wait-for-ocr! pid 3000 "ClojureDart support is experimental")]
    (screenshot! pid "step1-open")
    (check! :open-label label-ok {:console last})
    (check! :open-experimental-line exp-ok {:console exp-last})
    (skip! :open-reset-on-run-checkbox
           "cgevent OCR reports visible text only, not GtkCheckButton sensitivity (grey-out); no accessibility-state introspection available here")))

(defn- step-run!
  "Step 2. The loaded-message check is a String needle (not a hand-built
  regex spanning \"Loaded ... drive (N forms)\"), because a String needle
  is checked by wait-for-ocr! against BOTH the newline-joined and the
  loose (space-joined) OCR text -- a regex needle only ever checks the
  former, and this line is long enough that a narrower console pane could
  wrap it across two OCR rows. The \"(N forms)\" half is a separate,
  looser substring check for the same reason."
  [pid]
  (cg-key! pid "f5")
  (let [{:keys [ok last]} (wait-for-ocr! pid 60000 "Loaded demo.deck.drive")
        has-form-count (boolean (re-find #"\(\d+ forms?\)" (str/replace last "\n" " ")))
        {attached-ok :ok attached-last :last} (wait-for-ocr! pid 5000 "attached")
        no-closure (not (str/includes? (ocr-text! pid) "Closure"))]
    (screenshot! pid "step2-run")
    (check! :run-loaded-message (and ok has-form-count) {:console last})
    (check! :run-label-attached attached-ok {:console attached-last})
    (check! :run-no-closure-text no-closure {})))

(defn- step-where!
  "Step 3. Returns {:pos parsed-or-nil :title parsed-title-or-nil}: :pos
  feeds step-next-prev!'s exact position comparisons, :title feeds
  step-run-form!'s (best-effort, see parse-title) result check. :of isn't
  pinned to a literal 54 here: the deck's own slide count is real data,
  not this script's to assume (plan-authoring.md's own \"don't invent
  expected numbers\"), so this only requires a fully-parsed position, and
  records whatever :of actually reads."
  [pid]
  (find-text! pid "(where)")
  (cg-key! pid "command + e")
  (let [{:keys [ok last]} (wait-for-ocr! pid 5000 #"(?i)slide\D{0,3}\d")
        parsed (parse-where last)
        title (parse-title last)]
    (screenshot! pid "step3-where")
    (check! :where-position (and ok (some? parsed)) {:console last :parsed parsed :title title})
    {:pos parsed :title title}))

(defn- step-next-prev!
  "Step 3b, rewritten per the coordinator's own safety ruling after a live
  run moved the deck without restoring it (see the report): (next!) is
  read back BEFORE (prev!) is ever sent, not just before/after as a pair
  of independent checks. (prev!) is sent ONLY when that read shows the
  deck moved by exactly one step, per the deck's own semantics (the same
  slide's step +1, or the next slide, any step) -- (next!) itself is
  still sent exactly once either way (the hard rule), but a FAIL here
  means this fn sends nothing else at all: no retry of (next!), no
  (prev!) either, since (prev!) alone (with no real (next!) behind it)
  is itself a real, undo-worthy deck move, not a safe no-op -- exactly
  what happened live this session before this rewrite. `before` is
  step-where!'s own parsed position. The restore is confirmed from
  (prev!)'s OWN echoed return value, which IS a real (where) call
  (drive.cljd: `(defn prev! [] (state/go-prev!) (where))`) -- not a
  THIRD find-text!/Cmd+E round trip for a fresh one.

  A third round trip was tried first and dropped: `\"(where)\"` has
  SEVEN occurrences in drive.cljd, not the six this file's own ns
  docstring originally counted -- retitle-live! and restore-live! both
  end their body in `(where)`, one of them followed by FIVE closing
  parens on the very same line (closing several nested forms at once).
  With the selection collapsed to a caret (fix round 2's own find-text!
  change), Cmd+E with no selection falls back to sending the CARET'S
  OWN LINE, so landing on one of those two occurrences sent the whole
  line -- `(where)))))`, unbalanced -- to the REPL, which answered a
  reader exception instead of a position. Measured live this session:
  harmless (a reader error, not an eval, so nothing about the deck
  moved or was touched), but real -- the search-string uniqueness
  analysis this file's own ns docstring keeps just wasn't complete for
  a third `\"(where)\"` search from a caret position this far into the
  file. Reusing (prev!)'s own answer sidesteps needing that analysis at
  all: it already forced this exact fn to type-check `(prev!)`'s
  result as a real (where) map before ever reaching this checkpoint."
  [pid before]
  (find-text! pid "(next!)")
  (cg-key! pid "command + return")
  (let [{:keys [ok last]} (wait-for-ocr! pid 8000 #"(?i)slide\D{0,3}\d")
        after (parse-where last)
        moved-one-step? (and before after
                             (or (and (= (:slide before) (:slide after))
                                      (= (inc (:step before)) (:step after)))
                                 (= (inc (:slide before)) (:slide after))))]
    (screenshot! pid "step3b-next")
    (check! :next-advances-one-step (and ok (some? after) moved-one-step?)
            {:console last :before before :after after})
    (if-not (and ok (some? after) moved-one-step?)
      (check! :prev-restores-position false
              {:reason (str "(next!) did not show a clean, parseable one-step move; "
                            "(prev!) was never sent -- the deck may need checking by hand")
               :before before :after after})
      (do
        (find-text! pid "(prev!)")
        (cg-key! pid "command + return")
        (let [{:keys [ok last]} (wait-for-ocr! pid 8000 #"(?i)slide\D{0,3}\d")
              restored (parse-where last)]
          (screenshot! pid "step3b-prev")
          (check! :prev-restores-position (and ok (= before restored))
                  {:console last :before before :restored restored}))))))

(defn- step-run-form!
  "Step 4. Types the two-line let from p4t3-brief right before the last
  comment block's own \"  nil)\" (found via find-text!, unique -- see the
  ns docstring), moves the caret onto :title with 4 Lefts (measured against
  the literal typed text \"(:title w))\", 11 chars, landing right after
  \"title\"), Cmd+Enter, then undoes until the title loses its \"*\" (a
  generous 15s/200ms bound -- measured live this session, a real full
  undo here took as few as 14 presses, well under what 6s would have
  allowed, but flaked once at that shorter bound, so this is deliberately
  more headroom than the measured number, not a tight fit to it).
  find-text! now returns a plain CARET (fix round 2, see its own
  docstring), so jonquil.edit/insert-open auto-pairs every ( and [ this
  types the ordinary way (an empty pair at the caret, not a wrap of
  stale selected text), and insert-close steps over the matching pair
  instead of duplicating it -- the buffer ends up with exactly the
  intended text. Confirmed live against a throwaway scratch file before
  trusting it here (see the report): the echoed form now reads the WHOLE
  let, both lines, and the result is the real title, not an \"Unknown
  symbol: w\" error from evaluating `(:title w)` on its own.

  expect-title (from step-where!'s own OCR read) is used as the needle
  itself when it's known -- the specific title text appearing IS the
  falsifiable check (a broken Run Form, resolving to the wrong top-level
  form or none at all, can't produce this exact string by accident), and
  a String needle here (unlike a hand-built \"=>\\s*\\\"...\\\"\" regex)
  goes through wait-for-ocr!'s already-tolerant str/includes? against both
  the newline-joined AND loose (space-joined) OCR text -- binary_smoke.clj
  measured OCR splitting a short result like \"=> 2\" across two adjacent
  rows, and a regex needle only ever checks the newline-joined form. Falls
  back to the bare \"=>\" only when step-where! itself couldn't parse a
  title (already flagged there as a :where-position FAIL)."
  [pid expect-title]
  (find-text! pid "  nil)")
  (type-text! pid "(let [w (where)]")
  (cg-key! pid "return")
  (type-text! pid "(:title w))")
  (dotimes [_ 4] (cg-key! pid "left"))
  (cg-key! pid "command + return")
  (let [needle (or expect-title "=>")
        {:keys [ok last]} (wait-for-ocr! pid 5000 needle)
        got-title (parse-title last)]
    (screenshot! pid "step4-run-form")
    (check! :run-form-result ok {:console last :expect-title expect-title :got-title got-title})
    (let [undone (wait-for! 15000 200
                            (fn []
                              (when-not (title-clean? pid) (cg-key! pid "command + z"))
                              (title-clean? pid)))]
      (screenshot! pid "step4-run-form-undone")
      (check! :run-form-undo-clean undone {:title (:name (window-for pid))}))))

(defn- step-help!
  [pid]
  (find-text! pid "(where)")
  (dotimes [_ 2] (cg-key! pid "right"))
  (cg-key! pid "control + q")
  (let [{:keys [ok last]} (wait-for-ocr! pid 5000 "Where the deck is now")]
    (screenshot! pid "step5-help-where")
    (check! :help-docstring ok {:console last})
    (cg-key! pid "escape"))
  (find-text! pid "idx")
  (cg-key! pid "right")
  (cg-key! pid "control + q")
  (let [{:keys [ok last]} (wait-for-ocr! pid 5000 "No docs for idx")]
    (screenshot! pid "step5-help-idx")
    (check! :help-no-docs-local ok {:console last})
    (cg-key! pid "escape"))
  (find-text! pid "  nil)")
  (cg-key! pid "end")
  (cg-key! pid "return")
  (type-text! pid "(nex")
  (cg-key! pid "control + space")
  (let [{:keys [ok last]} (wait-for-ocr! pid 5000 "next!")]
    (screenshot! pid "step5-help-completion")
    (check! :help-completion-next ok {:console last}))
  (let [undone (wait-for! 15000 200
                          (fn []
                            (when-not (title-clean? pid) (cg-key! pid "command + z"))
                            (title-clean? pid)))]
    (check! :help-completion-undo-clean undone {:title (:name (window-for pid))})))

(defn- step-stop!
  [pid]
  (cg-key! pid "f6")
  (type-text! pid "(reduce + (range 30000000))")
  (cg-key! pid "return")
  (cg-key! pid "escape")
  (let [{:keys [ok last]} (wait-for-ocr! pid 5000 "Detached from the app")
        {label-ok :ok label-last :last} (wait-for-ocr! pid 3000 "not running")]
    (screenshot! pid "step6-stop-detached")
    (check! :stop-detaches (and ok label-ok) {:console last :label label-last}))
  (find-text! pid "(where)")
  (cg-key! pid "command + e")
  (let [{:keys [ok last]} (wait-for-ocr! pid 90000 #"(?i)slide\D{0,3}\d")]
    (screenshot! pid "step6-stop-reconnect")
    (check! :stop-reconnects (and ok (some? (parse-where last))) {:console last})))

(defn- capture-close-stuck-diagnostics!
  "Coordinator-requested (controller probes: Jonquil's own close finished in
  ~0.3s eight times, live, so a slow close here would be something about
  THIS run, not Jonquil in general): a full-SCREEN screenshot (screencapture
  -x, not the pid-scoped screenshot! helper -- a stray dialog, find bar or
  popover can be its own top-level window, invisible to a --pid capture), a
  `sample <pid> 3` of the main thread's stack while it's stuck, and a ps
  snapshot plus the Process handle's own .isAlive. Never throws: a
  diagnostic that fails to capture must not itself fail the step -- this
  runs only after the process has ALREADY missed the first checkpoint, so
  losing a diagnostic here must not also lose the eventual close-exits
  result.

  Root cause, found live with these diagnostics (2026-09-28 run, see the
  report): close-stuck.png shows a real, native \"Save changes to
  drive.cljd before closing?\" alert (Save / Don't Save / Cancel) sitting
  in front of the editor -- Jonquil's own close-request-fn, doing exactly
  what it does for any dirty buffer (the SAME alert binary_smoke.clj's own
  close-with-unsaved step already exercises for the Jolt dialect). The
  sample confirms the process is otherwise completely idle: the main
  thread's entire stack is inside AppKit/HIToolbox/CoreFoundation's own
  event-loop wait (_BlockUntilNextEventMatchingListInMode -> mach_msg),
  i.e. genuinely blocked on the NEXT UI EVENT, not stuck in any Jonquil
  code, not deadlocked, not computing -- every other thread is similarly
  parked on its own semaphore/select/condvar. So the process not exiting
  isn't Jonquil being slow at all; it's this tool waiting on a dialog it
  never answers, because the buffer was ALREADY dirty (\"* drive.cljd -
  Jonquil\", visible in the close-before capture taken moments earlier)
  by the time Cmd+W ran. `title-clean?` can't tell a genuinely clean
  title from a momentarily EMPTY one read off `windows --edn` (both
  satisfy `(not (str/starts-with? \"\" \"*\"))`) -- this run's own
  `:help-completion-undo-clean` result recorded `:title \"\"`, a strong
  match for exactly that gap letting step-help!'s own undo loop stop
  early, one Cmd+Z short of clean. Named here, not fixed: the
  coordinator's own instructions for this dispatch were diagnose and
  report, not touch `src/` or `title-clean?`."
  [pid proc]
  (let [alive? (p/alive? proc)
        ps-out (try (:out (p/shell {:out :string :err :string :continue true}
                                   "ps" "-o" "pid,stat,etime,command" "-p" (str pid)))
                    (catch Exception e (str "ps failed: " (ex-message e))))]
    (println "[cljd-smoke] close: still running 3s after Cmd+W -- capturing diagnostics")
    (println "[cljd-smoke] close: Process .isAlive =" alive?)
    (println "[cljd-smoke] close: ps -o pid,stat,etime,command -p" pid)
    (println ps-out)
    (try (p/shell {:continue true} "screencapture" "-x" (str out-dir "/close-stuck.png"))
         (catch Exception e (println "[cljd-smoke] close: screencapture -x failed:" (ex-message e))))
    (println "[cljd-smoke] close: sampling pid" pid "for 3s...")
    (try
      (let [{:keys [out err]} (p/shell {:out :string :err :string :continue true}
                                       "sample" (str pid) "3")]
        (spit (str out-dir "/close-sample.txt") (str out "\n" err)))
      (catch Exception e
        (spit (str out-dir "/close-sample.txt") (str "sample failed: " (ex-message e)))))
    {:alive-at-3s alive? :ps ps-out}))

(defn- step-close!
  "Step 9. 15s, not binary_smoke.clj's usual 5s/8s bound for a plain Cmd+W
  exit: measured live this session, this window (a longer session, one
  real attach/detach/reattach cycle and several evals behind it by now)
  took longer than 8s to exit on a CLEAN buffer (run-form-undo-clean and
  help-completion-undo-clean both PASSing beforehand, ruling out the
  unsaved-changes alert as the cause) -- 15s is generous headroom over
  that, not a tight fit to it. If the buffer is dirty at this point
  regardless (a future regression, or this bound still not being enough),
  the fallback stays the safe one either way: force-kill this pid
  (destroy-tree, never a keystroke that could hit the close alert's own
  default \"Save\" button) and report :close-exits FAIL -- never a save.

  Coordinator-requested diagnostics (this run): a screenshot + OCR of THIS
  pid's own window right before Cmd+W (close-before.png -- what has focus:
  the find bar, a popover, completion, or the console), split into a 3s
  checkpoint (capture-close-stuck-diagnostics!, only if still running) and
  the remaining 12s of the same 15s total bound, with the elapsed time from
  the Cmd+W keystroke to the real exit (or the force-kill) printed either
  way, so a slow EXIT reads differently from this tool's own deref waiting
  on something else."
  [pid proc pre-git]
  (screenshot! pid "close-before")
  (println "[cljd-smoke] close: OCR just before Cmd+W:")
  (println (ocr-text! pid))
  (cg-key! pid "command + w")
  (let [t0 (System/currentTimeMillis)
        first-wait (deref proc 3000 ::timeout)]
    (if (not= ::timeout first-wait)
      (let [elapsed (- (System/currentTimeMillis) t0)]
        (println (str "[cljd-smoke] close: exited within 3s, elapsed=" elapsed "ms"))
        (check! :close-exits (zero? (:exit first-wait)) {:exit (:exit first-wait) :elapsed-ms elapsed})
        (swap! launched (fn [v] (vec (remove (fn [m] (= pid (:pid m))) v)))))
      (do
        (capture-close-stuck-diagnostics! pid proc)
        (let [;; capture-close-stuck-diagnostics! itself takes several real
              ;; seconds (`sample` alone runs for 3s), so the remaining wait
              ;; is whatever's left of the 15s TOTAL bound from t0, not a
              ;; flat 12s on top of it -- otherwise the real bound quietly
              ;; grows past 15s by however long the diagnostics took.
              remaining-ms (max 1000 (- 15000 (- (System/currentTimeMillis) t0)))
              second-wait (deref proc remaining-ms ::timeout)
              elapsed (- (System/currentTimeMillis) t0)]
          (if (not= ::timeout second-wait)
            (do
              (println (str "[cljd-smoke] close: exited at elapsed=" elapsed "ms (exit "
                            (:exit second-wait) ")"))
              (check! :close-exits (zero? (:exit second-wait)) {:exit (:exit second-wait) :elapsed-ms elapsed})
              (swap! launched (fn [v] (vec (remove (fn [m] (= pid (:pid m))) v)))))
            (do
              (println (str "[cljd-smoke] close: still not exited after 15000ms total "
                            "(elapsed=" elapsed "ms) -- force-killing"))
              (check! :close-exits false {:reason "process did not exit within 15000ms" :elapsed-ms elapsed})
              (stop! {:proc proc :pid pid})))))))
  (let [post-git (:out (p/shell {:out :string :err :string :continue true}
                                "git" "-C" app-dir "status" "--short"))]
    (check! :close-git-unchanged (= pre-git post-git) {:pre pre-git :post post-git})))

;; --- driver --------------------------------------------------------------

(defn- build!
  []
  (println "[cljd-smoke] building" binary-path "...")
  (let [{:keys [exit err]} (p/shell {:out :string :err :string :continue true}
                                    "jolt" "build" "-m" "jonquil.main" "-o" binary-path)]
    (when-not (zero? exit)
      (binding [*out* *err*] (println "[cljd-smoke] build FAILED:" err))
      (System/exit 1))))

(defn -main [& args]
  (let [t0 (System/currentTimeMillis)
        no-build (some #{"--no-build"} args)
        preflight-ok (atom true)]
    (fs/create-dirs out-dir)
    (if no-build
      (when-not (fs/exists? binary-path)
        (binding [*out* *err*]
          (println "[cljd-smoke]" binary-path "doesn't exist and --no-build was given"))
        (System/exit 1))
      (build!))
    (println "[cljd-smoke] this drives your OWN, freshly-launched Jonquil window against")
    (println "[cljd-smoke] your running a-sibling-cljd-app app. Please don't type for the")
    (println "[cljd-smoke] next few minutes -- a stray keystroke can land in the wrong window.")
    (Thread/sleep 3000)
    (try
      (let [{:keys [ok pre-git-status]} (step-preflight!)]
        (reset! preflight-ok ok)
        (if-not ok
          (do
            (println "[cljd-smoke] pre-flight failed -- skipping the rest of the run.")
            (doseq [s [:open :run :where :next-prev :run-form :help :stop :close]]
              (skip! s "pre-flight failed; see :preflight-* checks above")))
          (do
            (skip! :quit-app "SKIP human-only -- the tool never quits, signals or restarts the app")
            (skip! :restart-app "SKIP human-only -- the tool never quits, signals or restarts the app")
            (let [{:keys [pid proc]} (launch! drive-file)]
              (ensure-window! pid 8000)
              (step-open! pid)
              (step-run! pid)
              (let [{before-pos :pos before-title :title} (step-where! pid)]
                (step-next-prev! pid before-pos)
                (step-run-form! pid before-title))
              (step-help! pid)
              (step-stop! pid)
              (step-close! pid proc pre-git-status)))))
      (catch Exception e
        (check! :unexpected-exception false {:exception (ex-message e) :data (ex-data e)}))
      (finally
        (kill-all-launched!)))
    (let [scored (remove #(= :skip (:ok %)) @results)
          passed (count (filter :ok scored))
          total (count scored)
          wall-ms (- (System/currentTimeMillis) t0)]
      (println (str "SUMMARY " passed " of " total " passed (" wall-ms "ms)"))
      ;; A failed pre-flight (the app isn't up, the fork is too old, or the
      ;; screen is locked) is "nothing to test right now", not a tool
      ;; failure -- exit 0 regardless of its own checks' pass/fail count
      ;; (brief: "If the app isn't up, SKIP everything and exit 0").
      (System/exit (if (and @preflight-ok (not= passed total)) 1 0)))))

(apply -main *command-line-args*)
