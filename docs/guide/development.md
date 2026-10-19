# Development

`bb info` (or `jolt info`, since jolt reads the same `bb.edn`) prints the
full task list grouped by what it's for. This page walks through the same
groups with the detail the cheat-sheet has no room for: what each harness
drives, and the traps that catch anyone running them for the first
time.

## Verify: check, test, and the pre-commit gate

`bb check` requires every project namespace headlessly and exits 0; it
currently loads 65 namespaces in well under a second, and it's the fastest
way to catch a namespace that doesn't compile before running anything
slower. `bb test` runs the full unit suite (715 tests, 4050 assertions
on macOS) with nothing outside the process, or pass namespace
names to run a subset for the TDD loop: `bb test jonquil.edit-test`. That
count now includes both attach evaluators' own tests, run against an
in-process fake server for each (ClojureDart's, the JVM's and
ClojureScript's, the last one `dev/fake_shadow_nrepl.clj`, which
`jonquil.nrepl.shadow-test` and the window self-test share), so there's
still no child process and nothing outside the repo. Some asserts are gated by
OS, `desktop-file-validate` and `plutil` among them, so a Linux (arm64) run
reports fewer assertions than macOS (715 tests and 4019 assertions, 31
fewer, measured 2026-10-19).

<details>
<summary><strong>The live suites: <code>test:nrepl</code>, <code>test:clj</code>, <code>test:jank</code>, <code>test:bb</code> and <code>test:basilisp</code></strong></summary>

`bb test:nrepl` runs a second, separate suite (54 tests, 196 assertions,
about 50 s): every test in it spawns a real Jolt child over
`jonquil.nrepl.child` and talks to it over a socket, so it needs the
jolt-lang/nrepl checkout described in [Using Jonquil](using-jonquil.md).
`bb test:clj` runs a third, separate suite (15 tests, 57 assertions)
against a real `clojure -M:serve`, started fresh for the suite by
`dev/clj_server.clj` (mirroring `jonquil.nrepl.child`'s shape for a Jolt
child, but for the real JVM CLI): it needs Java 17+ and the `clojure` CLI,
and the network on a first run that hasn't cached clojure-cli.repl-server
yet. `bb test:jank` runs a fourth suite against a real jank: it launches a
`jank repl` through the same launcher the app uses. It honours
`JONQUIL_JANK`, then looks for `jank` the way the app does, and when it
finds none it prints a message and skips, exiting 0, so it is safe on a
machine without jank. `bb test:bb` runs a fifth (5 tests, 19 assertions,
measured 2026-10-18): each test starts a real `bb nrepl-server` on a free
port in its own temp project and drives the nREPL dialect against it (Run,
the error line, completion, lookup, Stop by disconnecting, a read that
can't be answered, the port file, and a refused port), stopping that server
after. It skips, exiting 0, when no `bb` is on `PATH`. `bb test:basilisp`
runs a sixth (5 tests, 21 assertions, measured 2026-10-18) the same way
against a real `basilisp nrepl-server`: the port file Basilisp writes, the
error lines for a runtime error, a compile error and `ex-info`, quick docs
through `info` (also from a buffer namespace the server hasn't loaded),
completion, the limitations, a read with the server's stdin closed, and
Stop by disconnecting. It uses `JONQUIL_BASILISP` when that's set, else a
`basilisp` on `PATH`, and skips, exiting 0, with neither. `bb test` never touches a child process or a real server and stays
that self-contained on purpose, which is why the live suites are separate
tasks rather than tags inside the same one.

</details>

`bb verify` is the pre-commit
gate: clj-kondo in report mode, then `bb test` followed by `bb
test:nrepl`, both of which must pass.

<details>
<summary><strong><code>bb mcp:smoke</code>: what it checks, headless, with <code>--window</code> and with <code>--attach</code></strong></summary>

`bb mcp:smoke` smokes `jonquil --mcp` over real stdio: it starts the
server from the checkout (`jolt -M -m jonquil.main --mcp DIR`), speaks
JSON-RPC to it from babashka, and prints one PASS or FAIL line per check:
the handshake, six tools listed, an evaluation, a background `println` that
must not reach stdout, a bracket check, then a second project with
`.jonquil.edn` `{:runtime :nrepl}` that starts a real Jolt child, and a clean
exit with no child left once stdin closes. Every response is read with a
deadline, so a hang fails a check instead of blocking. It prints 11
checks. `bb mcp:smoke --bin
./jonquil` runs the same checks against the binary `bb app:build` made, which
is what proves `jolt build` links the MCP namespaces. It takes about 3 s from
a checkout. Its nREPL step uses a jolt-lang/nrepl checkout when there is one,
and otherwise fetches v0.4.0 as a git dependency (git and the network, the
first time).

`bb mcp:smoke --window` smokes `jonquil --mcp --window` the same way, but it
opens a real window, so don't type while it runs. It checks the handshake,
exactly the fifteen window tools, opening a file, Run, a replace that leaves
the tab dirty, a save that writes the new bytes, Run again, an evaluation, the
console since mark 0, a runaway evaluation that must time out as an error
within 10 s and leave the window usable, and then the EOF path: with an
unsaved edit pending, closing stdin must end the process within 20 s, leave no
Jolt child, and leave the file on disk unchanged. Every stdout line must parse
as JSON. It prints 16 checks, and `--bin PATH` works with it too.

`bb mcp:smoke --attach` smokes the other half: it starts a real window with
`:mcp-listen? true` under a temp `XDG_CONFIG_HOME`, so your own settings are
untouched (don't type while it runs), waits for its registry entry, and runs
`jonquil --mcp-attach` against it over real stdio. It checks the handshake,
exactly the fifteen window tools, an evaluation and the console mark, that
closing stdin ends the bridge within 10 s with the window still running, and
that a second bridge then attaches and evaluates. It ends by sending SIGTERM
to the window, which must exit within 20 s and remove its registry file. Every
bridge stdout line must parse as JSON. It prints 16 checks.

</details>

## Self-test and docs: nine windows, one binary, one camera

Three harnesses drive the real GTK front end, and each covers ground the
others can't.

**`bb gtk:selftest`** opens nine real GTK windows in turn and runs the
front-end self-test rows against them, calling the same button and key
handlers a person would use rather than the internal functions directly.

<details>
<summary><strong>The nine groups, and what each one covers</strong></summary>

The first window covers the Definitions editor and the Interactions console
(`dev/gtk_selftest.clj`, 48 rows); the second covers the full application
window (`dev/gtk_selftest_window.clj`, 308 rows), with its rows split by
feature area under `dev/gtk_selftest_window/`: `support`, `run_form`,
`run_repl`, `files`, `find_replace`, `close_prerun`, `window_keys`,
`themes`, `popovers_quickdoc`, `completion`, `rename_menus`,
`console_layout`, `dialects`, `mcp` (the window hooks, the fifteen tools and the listener driven in process), `contained` (a forced throw in each find-bar,
toolbar and file-dialog handler is reported and the app carries on),
`prompt` (the namespace prompt following a
REPL line, a Run and a slow evaluation), `app_open` (Open With and Dock drops: two files through a real
`g_application_open`, a folder, a missing path, a file already open and a
non-local URI), `inspector` (the panel widget in
isolation, then the real window's own panel: opening, in/out, paging
across a page edge, table/meta toggles, def, Run Form, and Escape),
`source_pane` (the source panel widget in isolation), `tab_widget` (one
tab's widgets in isolation), `tabs` (new, open, switch and close, Run per
tab, the close alerts), `session_tabs` (saving and reopening the last
session's tabs), `file_tree_pane` (the tree widget in isolation) and
`file_tree` (the tree in the real window, including its file operations). The
third (`dev/gtk_selftest_nrepl.clj`,
19 rows) opens against a throwaway project whose `.jonquil.edn` asks for
`{:runtime :nrepl}`, so a real Jolt child runs behind it, and its rows
cover starting, running, the inspector on the child's `*1`, a `read-line`
round trip and Stop during a read, help, F12 into another of the project's
files (the child's `jonquil-info` op), a loose file running in the
child, a runtime switch (and where focus lands after it), and closing over
that live connection; it needs the jolt-lang/nrepl checkout the same way
`bb test:nrepl` does, and records a single failing row rather than
opening anything if that checkout isn't there. The fourth
(`dev/gtk_selftest_cljd.clj`, 15 rows) opens against a throwaway
ClojureDart app directory whose `NREPL.lock` names a fake fork server
running in this same process (`dev/fake_cljd_nrepl.clj`), so it needs no
fork checkout and no real app; its rows cover connecting, a quiet load,
Run Selection and Run Form switching the session's namespace, quick doc,
completion, Stop's detach, and the inspector's unavailable sentence, over
that fake connection. The fifth
(`dev/gtk_selftest_clj.clj`, 36 rows) opens against a throwaway JVM Clojure project whose
`.jonquil.edn` says `{:dialect :clj}` and whose `.nrepl-port` names a
fake JVM nREPL server running in this same process
(`dev/fake_jvm_nrepl.clj`), so it needs no real `clojure -M:serve`; its
rows cover connecting and Run moving the prompt, a selection against a
namespace the server hasn't loaded, Stop's interrupt, stdin, reconnecting
after the server restarts and the prompt going back to `user`, quick doc, jump to an external definition,
full docs and its Escape, the inspector before and after a Run, in and
out, a lost connection and reconnecting while idle, Reset on Run's tick
through a switch to a Jolt file and back, and a close that never sends
the server a `close` op. Five rows slow the fake down (its `:delays` atom
makes completions or lookup answer after 800 ms) and check that the
handlers return at once, that the late answers land, that one for a symbol
the caret has left is dropped, that a jump within the buffer doesn't wait,
and that a tab closed mid-request is left alone. Its source rows open definitions from a fixture
jar and from files inside and outside the project, follow and go back,
open a project file in the window, and share the slot with the inspector.

The sixth group (`dev/gtk_selftest_tree.clj`, 4 rows) launches on a
directory whose `sessions.edn` remembers two of its files and one that's
gone. The tree is rooted there and showing, the window runs that
directory's project, and the one tab is Untitled when the window is first
shown. The remembered tabs then open in order, with the right one selected
and the gone file named on the console, and `sessions.edn` isn't
rewritten until a tab switch after the reopen saves it.

The seventh group (`dev/gtk_selftest_jank.clj`, 13 rows) opens a window on a
throwaway `.jank` project. `bb gtk:selftest` sets `JONQUIL_JANK` to
`dev/fake-jank` for it, a small script that ignores its arguments and
serves a fake jank nREPL (`dev/fake_jank_nrepl.clj`) in its working
directory, writing `.nrepl-port` there as jank does. The launcher still
starts it as a real child process with a held-open stdin and a private run
directory, so rows can check the pid and the directory on disk, and nothing
needs a jank install. To run the group alone, set `JONQUIL_JANK` to the
absolute path of `dev/fake-jank` and run `jolt -M:selftest-jank`.

The eighth group (`dev/gtk_selftest_args.clj`, 5 rows) launches the way a
terminal does, with `jonquil.main/gtk-argv` turning a folder and two files
into absolute paths for `g_application_run`. GApplication then emits `open`
(and not `activate`), and the rows check that the tree is rooted at the folder
argument, that a folder launch saves no tree visibility, that the two files
open as tabs, that the active tab is the last file, and that the program name
is `net.b12n.jonquil` on Linux (macOS keeps `jonquil`). The ninth group (`dev/gtk_selftest_cljs.clj`, 10 rows) opens against a
throwaway shadow-cljs project whose `.shadow-cljs/nrepl.port` names a fake
shadow-cljs nREPL server running in this same process
(`dev/fake_shadow_nrepl.clj`), so it needs no node, no shadow-cljs and no
browser. A full run reports 48, 308, 19, 15, 36, 4, 13, 5 and 10 rows
passing, in group order.

</details>

<details>
<summary><strong>How the rows run: go blocks, timeouts and the GTK thread</strong></summary>

Each row is a core.async go block that reads top to bottom instead of a
chain of nested callbacks. `gtk-async/run-rows` drives the rows strictly in
order, and a per-row timeout records a FAIL rather than letting one stuck
row hang the whole run. The one hard rule underneath every harness: the
GTK thread never parks. A go block runs on its own OS thread, the default
`:thread` backend, so every GTK, editor, or console call goes through
`on-gtk` or an `await-until` predicate from `dev/gtk_async.clj`, both of
which hop onto the GTK thread and park the go block until the call
finishes; a row's body never calls GTK directly. When a row throws,
`go-row` catches it, records a FAIL, and the run moves on to the next row.
In the window group, the driver clears the editor and the console's input
before each row and waits for the engine to go idle, so one row's
leftovers don't leak into the next. Every driver closes its window through
`gtk-async/close-window-bounded!`, which asks the window to close and then
waits a bounded time for it to go away. If it's still open, the run
records a FAIL and exits 1 instead of hanging.

</details>

**`bb smoke:binary`** is the one harness that runs outside the GTK process
under test. It builds `target/smoke/jonquil` (skip the build with `--no-build`),
launches it as a real subprocess, and drives it with `cgevent key --pid`,
routing every keystroke to that process's own pid so nothing leaks to the
user's other windows and nothing is ever posted `--global`. It reads results
back three ways: real file bytes on disk, real window titles, and real
screen pixels through OCR (`cgevent ocr-inspect`) plus a per-pixel colour
check for the theme's text and prompt colours. Every jonquil process the
script starts is killed on every exit path, success, failure, or Ctrl-C.

**`bb smoke:cljd`** drives a built `./jonquil` against a real, running
ClojureDart app instead, so it needs one up first, built with nREPL
support. The gitignored `dev/smoke/cljd_app.local.edn` names it, with `:dir`
(the app's absolute path) and `:main-ns` (the namespace Run loads); an
optional `:fork-sha` pins a prefix the app's own `deps.edn` `:git/sha` must
start with, and without the key the version check is skipped. Without
that file the task prints how to create it and exits 1. If the
app isn't up, or the screen is locked, every step SKIPs and the task exits 0.

<details>
<summary><strong>What it does, step by step</strong></summary>

One Jonquil process stays open for the whole run, since every step shares
the same attached session. In order, it:

1. opens the app's `drive.cljd` and checks the label and the experimental line
2. runs it and waits for `Loaded <main-ns>`
3. reads `(where)`
4. sends `(next!)` once and `(prev!)` once, checking the deck moved exactly one step and came back
5. types a two-line `let` for Run Form, then undoes it
6. checks quick doc and completion
7. presses Stop during a slow eval, then reconnects
8. closes the window

The `(prev!)` only goes out after a second `(where)` confirms the `(next!)`
really moved the deck, so a `(next!)` that never landed can't leave the
deck a step behind. The buffer only counts as clean when the window title
reads exactly `drive.cljd - Jonquil`, and the script closes nothing that
isn't clean. It never saves the app's file and never quits, signals or
restarts the app. At the end it compares the app's `git status` with what
it recorded at the start. Quitting and restarting the app stay manual
checks, so those two steps always SKIP. If a close runs past 3 s, it saves
a screenshot and a stack sample under `target/smoke/`.

</details>

**`bb smoke:cljs`** drives a built `./jonquil` against a real, running
shadow-cljs app, so it needs the watch and a JS runtime up first (`npx
shadow-cljs watch <build>`, then the browser tab or the node script). The
gitignored `dev/smoke/cljs_app.local.edn` names the app with
`{:dir "..." :build :dev}`. It never starts or stops the watch or the
runtime. When the app has several builds it writes a temporary untracked
`.jonquil.edn` into the app directory to name one, and removes it on every
exit path, Ctrl-C included. It prints 18 checks. Without the config file
the task prints how to create it.

**`bb docs:shots`** self-captures every `docs/images/*.png` and the one GIF
the guide pages embed. Rather than one shared window walking through every
scene, it launches a fresh `jolt -M:shots <scene-id>` process per scene, so
each screenshot shows only its own scene's console history instead of every
earlier scene's leftover output. `bb docs:shots` regenerates every scene in
one pass; to retake just one, run `jolt -M:shots <scene-id>` directly, the
same call the task makes per scene, so an unrelated screenshot doesn't shift
underneath an unrelated review.

<details>
<summary><strong>The jank and JVM Clojure scenes</strong></summary>

The `jank` scene runs against a real jank, found the way the app finds it
(`JONQUIL_JANK`, `PATH`, the usual install folders). With none installed it
prints that and exits 0 without writing an image. Retake it with
`jolt -M:shots jank`.

The five JVM Clojure scenes (`clj-attach`, `clj-quick-doc`, `clj-full-doc`,
`clj-inspector` and `clj-source`) run against a real `clojure -M:serve`, started through
`dev/clj_server.clj` in a temp project the same way `bb test:clj` starts
one, and stopped once the window closes. So they need Java 17+ and the
`clojure` CLI, and `clj-full-doc`'s first run waits while the server
downloads the ClojureDocs export into its temp config directory. The
`inspector` scene (in-process Jolt) needs none of that: a REPL entry,
Cmd/Ctrl+I, then in on one of its rows so the panel's table view shows.

</details>

Every harness isolates the user's own settings from the app it's
driving. The window self-tests and `docs:shots` pass a temp `:settings-path`
into `jonquil.main/start!`, and the smokes point `XDG_CONFIG_HOME` at a
fresh temp directory for every process they launch. Neither the self-tests
nor the smoke ever reads or writes `~/.config/jonquil/settings.edn`.

Those temp directories don't outlive the run. Suites and self-test
drivers make them through `dev/temp_dirs.clj`, which records each one and
deletes it once the namespace's tests (or the driver) finish, so `bb test`,
`bb test:nrepl`, `bb test:clj` and `bb gtk:selftest` leave nothing behind
in `$TMPDIR`. `bb clean:tmp` removes what older runs left, and only
names these harnesses make; `bb clean:tmp --dry-run` lists them first.

### Traps

- **Don't type while a self-test window has focus.** `bb gtk:selftest`,
  `bb docs:shots` and the smokes open real windows that take real keyboard focus; a stray
  keystroke lands in the window under test and fails a row that has nothing
  to do with what was typed.
- **A phantom second display can attach at scale 1.0**, unrelated to any
  code change, and it breaks both `smoke:binary`'s OCR and `docs:shots`'
  screenshots (the images come back at 1x instead of the usual Retina 2x).
  Run `system_profiler SPDisplaysDataType` first if a smoke run fails steps
  it shouldn't, or a screenshot looks lower-resolution than expected; the
  extra display tends to clear on its own between runs.
- Never synthesize keystrokes with `cgevent type` against this app: GTK's
  macOS backend reads the key code cgevent sends, not the Unicode string it
  attaches, so typed text arrives mangled.
- **An interrupted `bb test:nrepl` or `bb gtk:selftest` run can leave a
  Jolt child behind.** Both kill their own children on every exit path in
  the ordinary case, but a Ctrl-C or a killed process midway through can
  still skip that cleanup. Run `pgrep -fl "nrepl-server 79"` afterward;
  anything it finds is a leftover from the run you just interrupted, so
  kill only those pids, never anything already there before you started.
- **An interrupted `bb test:clj` can leave a JVM behind.** Its fixture
  sends SIGTERM to the server it started on every ordinary exit path, but
  a Ctrl-C midway can skip that. Run `pgrep -fl clojure-cli.repl.server`
  afterward, and kill only a pid that run started, never a server of your
  own.

## The macOS app: bundle, install, icon

`bb app:bundle` rebuilds `./jonquil` and then runs `dev/app_bundle.clj` to
make `./Jonquil.app`, printing a `check` line for each of three post-build
checks: `plutil -lint` on the `Info.plist`, the executable bit on the
binary, and `codesign -dv` reporting the linker's ad-hoc signature. A failed
check exits non-zero naming it. `bb app:install` runs the bundle task, then
moves the result to `~/Applications/Jonquil.app` (it replaces an existing
one only when its plist names `net.b12n.jonquil`), runs `lsregister -f`
on it. The checkout's `./Jonquil.app` is unregistered and removed, so Open
With lists Jonquil once. `bb app:icon` regenerates `resources/Jonquil.icns`, and the 256 px
`resources/icon/jonquil-256.png` the Linux install uses, from
`resources/icon/icon.swift`, so it needs Swift; the `.icns` is committed, so
you only run it after changing the icon.

The bundle is deliberately not re-signed. `jolt build` appends the program
image after the launcher's Mach-O, and `codesign --force` then fails strict
validation. The launcher's own ad-hoc signature from the linker is what
macOS checks, so the task only verifies it's there.

On Linux, `bb app:install` runs `bb app:build` instead and copies the
binary, a launcher, the icon and the MIME types under `~/.local/share`
(`dev/app_linux.clj`, tested by `jonquil.app-linux-test`). `bb app:uninstall`
removes those files again. On macOS it only says to drag the app to the
Trash.

The unit suite covers this without touching your Applications folder:
`jonquil.app-bundle-test` (the `Info.plist` text and the replace-only-Jonquil
rule), `jonquil.tool-path-test` (the pure `PATH` decisions, including the
passwd lookup by uid) and `jonquil.login-path-test` (the lookup against fake
shells, covering success, timeout, failure and no `PATH`). The `app_open` rows
of `bb gtk:selftest` cover the open handler, as listed above, and the
`args` group covers the command line. `jonquil.main-test` pins `gtk-argv`,
including a file named like an option, and `jonquil.gtk.editor-test` pins that
the macOS keycode fallback for the Alt chords is macOS-only.

## Linux checks in Docker

Every Linux check runs in a local Docker container, so a Mac can verify the
Linux build without a Linux machine. There is no CI for it: the workflows in
`.github/` stay manual. Docker is the only requirement beyond the usual
tools, and the numbers below come from Docker Desktop on an Apple Silicon Mac.

| Task | What it does | Measured |
|---|---|---|
| `bb linux:image` | builds `jonquil-linux:<arch>` (Ubuntu 26.04, GTK 4, Xvfb, Jolt v0.8.23, babashka) | arm64 11 min 30 s, 1.76 GB; amd64 6 min 36 s, 1.75 GB |
| `bb linux:test [ns...]` | `bb test` on a copy of the checkout | arm64 47 s; amd64 64 s |
| `bb linux:selftest` | `bb gtk:selftest` under Xvfb | arm64 70 to 86 s; amd64 106 to 110 s |
| `bb linux:build` | `bb app:build`; the binary lands in `target/linux/<arch>/` | arm64 about 23 s; amd64 46 s |
| `bb linux:run -- cmd...` | any command in the copied tree | |
| `bb linux:vnc` | a desktop in the browser for the hand check | |
| `bb linux:clean` | removes both images and `target/linux` | |

The image task takes `--arch arm64|amd64` (the host's architecture by
default), as do the others except `linux:clean`. The arm64 image compiles
Jolt and Chez Scheme from source, because Jolt publishes no Linux arm64
binary. The amd64 image uses the pinned
release tarball, but its `apt` step runs under emulation and takes 349 s of
the 6 minutes 36. Pulling the base image needs Docker Hub, so run the first
`bb linux:image` from a normal terminal.

The checkout is mounted read-only and copied to `~/jonquil` inside the
container, so no task writes into your tree except `target/linux/<arch>/`.
The copy skips `jonquil`, `jonquil.build`, `Jonquil.app`, `_site` and
`target`, which are the Mac's own build products.

An arch other than the host's runs under emulation, several times slower.
`bb linux:selftest` sets `JONQUIL_SELFTEST_EMULATED=1` in that case, which
widens the paint-async row's budget from 150 to 750 ms. The self-tests also
wait 20 s for a Jolt nREPL child to start, and that wait can run out under
emulation on a busy host (parked in the ROADMAP).

### The noVNC hand check

`bb linux:vnc` starts a container with Xvfb, openbox, pcmanfm, x11vnc and
noVNC, installs Jonquil into it with `bb app:install`, and prints a URL such
as `http://localhost:6080/vnc.html?autoconnect=true&resize=scale`. The
port is the first free one from 6080 (found by trying to bind it);
`--port P` asks for one and is refused when busy. Open the URL, and pcmanfm
shows a `~/smoke` folder holding `hello.clj`, `hello.jank` and an
`nrepl-project`. Check that right-clicking a file offers Jonquil, that the
file opens in it, and that selecting `hello.clj` and `hello.jank` together
opens one window with both as tabs. The session has no terminal of its own,
so run anything else from the Mac with `docker exec jonquil-vnc-<P> ...`
(add `-e DISPLAY=:1` for anything that opens a window). Ctrl-C stops the
container and frees the port.

## Quality: lint, format, and the git hooks

`bb lint` runs clj-kondo over `src`, `test`, and `dev` in report mode;
`bb lint:strict` is the same lint but exits non-zero on any finding (2 for
warnings, 3 for errors), which is what the manual CI workflow gates on.
The git hooks block only on clj-kondo errors, so warnings don't stop a
commit.
`bb lsp:format` and `bb lsp:format-check` reformat (or dry-run check) with
clojure-lsp, and `bb lsp:clean-ns`/`bb lsp:clean-ns-check` do the same for
`ns` forms; `bb lsp:check` runs both dry-run checks together. All four LSP
tasks scope themselves to `src`, `test`, and every real file under `dev`,
skipping `dev/smoke/fixtures/*.clj` and `dev/smoke/PixelAt.java`: the
fixtures are deliberately invalid or minimal Clojure, fed to the built
binary to exercise unclosed-paren recovery, and clojure-lsp's own parser
aborts the whole run the moment it meets one. They also skip the two
files lifted from DrClojure-Neo, `src/jonquil/syntax.clj` and
`test/jonquil/upstream/syntax_test.clj`, so a later re-sync still diffs
cleanly against upstream.

`bb hooks:install` writes a fast pre-commit hook (clj-kondo errors plus a
format dry-run, a couple of seconds); `bb hooks:install:full` adds the full
test suite on top, slower but complete; `bb hooks:uninstall` removes
whichever one is there. Installing either hook first runs the same checks
it's about to gate on and warns if the tree doesn't pass them yet, so the
warning arrives at install time instead of at the first commit afterward.

### The manual lint workflow

`.github/workflows/lint.yml` runs `bb lint:strict` and `bb lsp:format-check`
on GitHub Actions, on `ubuntu-latest`, with no Jolt involved: clj-kondo lints
source text with its own analysis, and clojure-lsp reformats source text
with `rewrite-clj`, so neither one needs the compiler at all. It has no
push, pull request, or schedule trigger; Jonquil is a private repo for now,
so the workflow only runs when triggered by hand, from the Actions tab or
`gh workflow run lint.yml`.

## Benchmark

`bb bench:edit [sizes...]` times what typing costs in a large file. For each
size (default 200, 2000, 8000 and 20000 lines of Jonquil's own source,
cycled) it prints one `bench <measure> lines=<n> ms=<x>` line per measure:
`plan` (the median `paint-plan` run), `stall-edit` and `stall-quote` (the
longest single main-loop iteration in the 2 seconds after typing a letter, or
a double quote, one character into the first `defn` name in the first 40
lines of the pinned text; the quote flips string parity from there on),
`settle-stall-edit` and `settle-stall-quote` (the time until the repaint has landed; a
stall row whose repaint did not settle prints `VOID` instead of a number),
and `jump` (the median jump-to-definition). It
opens and closes a real GTK window per size, so leave the keyboard alone
while it runs.

Measured on 2026-10-15 on macOS arm64, all times in milliseconds. "Before" is
the same harness run against commit d9011d1, the last commit without the
background repaint. The old code had no background repaint, so the settle
rows have no "before".

| Lines  | Measure           | Before | After |
|--------|-------------------|-------:|------:|
| 200    | plan              |   11.8 |   6.5 |
| 200    | stall-edit        |   16.5 |   4.7 |
| 200    | stall-quote       |   11.4 |   3.9 |
| 200    | jump              |   16.1 |   6.0 |
| 200    | settle-stall-edit |      - |  82.6 |
| 200    | settle-stall-quote|      - |  78.7 |
| 2,000  | plan              |   67.4 |  38.0 |
| 2,000  | stall-edit        |  109.1 |  10.1 |
| 2,000  | stall-quote       |   61.6 |   8.9 |
| 2,000  | jump              |  130.7 |  40.9 |
| 2,000  | settle-stall-edit |      - | 149.4 |
| 2,000  | settle-stall-quote|      - | 129.1 |
| 8,000  | plan              |  266.0 | 152.6 |
| 8,000  | stall-edit        |  385.1 |  24.8 |
| 8,000  | stall-quote       |  340.0 |  21.5 |
| 8,000  | jump              |  505.3 | 155.3 |
| 8,000  | settle-stall-edit |      - | 307.3 |
| 8,000  | settle-stall-quote|      - | 298.0 |
| 20,000 | plan              |  661.3 | 382.7 |
| 20,000 | stall-edit        | 1021.7 |  68.6 |
| 20,000 | stall-quote       |  964.3 |  55.9 |
| 20,000 | jump              | 1233.4 | 388.1 |
| 20,000 | settle-stall-edit |      - | 662.8 |
| 20,000 | settle-stall-quote|      - | 669.9 |

A few things to keep in mind when reading it. The stall-quote row is not a
worst case, because a quote flips string parity and the repaint after it has
the same amount of work to do as after a letter. The plan row now times work
that runs on a background thread, so it is no longer a stall, only the cost of
one tokenise and plan. Each timed run gets a fresh copy of the text, because
the tokenizer caches by string identity and a reused string would time a cache
hit. What remains of the stall at 20,000 lines is the copy of the buffer text
on the GTK thread, about 50 ms.

## Upstream

`bb upstream:diff` lists the upstream DrClojure commits since `UPSTREAM_PIN`
that touch `src` or `test`, against a checkout at `$DRCLOJURE_DIR` (default
`~/dev/b12n-oss/kimtg--DrClojure-Neo`). See
[Porting and attribution](porting-and-attribution.md) for what that pin
means and how syncing against it works.

## Docs

`bb site:build`, `bb site:serve [port]`, and `bb site:clean` build and
preview this documentation site: the pages under `docs/guide/`, including
this one. They shell out to a sibling checkout of
[jlt-commons/docs-engine](https://github.com/jlt-commons/docs-engine),
looked up as `$JLT_DOCS_ENGINE`, then `~/dev/jlt-commons/docs-engine`,
then `../docs-engine` (a sibling there can be an unrelated older fork), and print where to `git clone` it if none
of those exist. There's no workflow and no GitHub Pages site behind this:
it's a local preview until the project is open-sourced.
