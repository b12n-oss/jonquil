# Development

`bb info` (or `jolt info`, since jolt reads the same `bb.edn`) prints the
full task list grouped by purpose. This page adds what each harness drives
and the traps that catch first-time runners.

## Verify: check, test, and the pre-commit gate

- `bb check` requires every project namespace headlessly and exits 0. It's
  the fastest way to catch a namespace that doesn't compile.
- `bb test` runs the unit suite: 1253 tests, 7520 assertions on macOS, 7489
  on Linux (some asserts are gated by OS, such as `desktop-file-validate`
  and `plutil`). Nothing outside the process runs; the attach evaluators
  use in-process fake servers. Pass namespaces for a subset:
  `bb test jonquil.edit-test`.
- `bb verify` is the pre-commit gate: clj-kondo in report mode, then
  `bb test` and `bb test:nrepl`, both of which must pass.

<details>
<summary><strong>The live suites: <code>test:nrepl</code> (and <code>test:nightly</code>), <code>test:clj</code>, <code>test:jank</code>, <code>test:bb</code>, <code>test:basilisp</code>, <code>test:plugins</code> and <code>test:cljs-node</code></strong></summary>

`bb test` never touches a child process or a real server, on purpose, so
the live suites are separate tasks. `test:jank`, `test:bb`, `test:basilisp`,
`test:plugins` and `test:cljs-node` print a message and skip, exiting 0,
when their tool is missing.

| Task | Runs against | Needs |
|---|---|---|
| `bb test:nrepl` | a real Jolt child per test, over `jonquil.nrepl.child` | the jolt-lang/nrepl checkout ([where it comes from](dialects/index.md#where-the-nrepl-middleware-comes-from)) |
| `bb test:nightly` | the same suite on Jolt's `vnightly` build | `curl`; macOS (arm64, x86_64) or Linux x86_64 |
| `bb test:clj` | a real `clojure -M:serve`, started by `dev/clj_server.clj` | Java 17+, `clojure` CLI, network on a first run |
| `bb test:jank` | a real `jank repl` via the app's launcher | `JONQUIL_JANK`, else `jank` found as the app does; skips without |
| `bb test:bb` | a real `bb nrepl-server` in a temp project | `bb` on `PATH`; skips without |
| `bb test:basilisp` | a real `basilisp nrepl-server` | `JONQUIL_BASILISP`, else `basilisp` on `PATH`; skips without |
| `bb test:plugins` | real isolated plugin checks in throwaway `jolt` children | `jolt` on the login `PATH`; skips without |
| `bb test:cljs-node` | a real `clojure` plus `node`, via the app's launcher | `clojure` and `node`; skips without; a first run can download ClojureScript and the Closure Compiler |

`bb test:nightly` unpacks the build under
`$XDG_CACHE_HOME/jonquil/jolt-nightly` (or `~/.cache/jonquil/jolt-nightly`),
checks it against the published `.sha256`, and leaves the `jolt` on `PATH`
alone. Later runs download again only when the `.sha256` changes, and
`--offline` reuses the cache. The nightly goes first on `PATH`, so the Jolt
children use it too.

`bb test:cljs-node` has one test that opens a real browser tab; it runs
only with `JONQUIL_BROWSER_LIVE=1 bb test:cljs-node`.

</details>

<details>
<summary><strong><code>bb mcp:smoke</code>: what it checks, headless, with <code>--window</code> and with <code>--attach</code></strong></summary>

`bb mcp:smoke` runs `jonquil --mcp` over real stdio from the checkout,
speaks JSON-RPC from babashka, and prints a PASS or FAIL line per check
(11): handshake, tools, an evaluation, a background `println` that must not
reach stdout, a bracket check, a `{:runtime :nrepl}` project starting a
real Jolt child, and a clean exit once stdin closes. Responses have
deadlines, so a hang fails a check. `--bin ./jonquil` runs it against the
binary `bb app:build` made, which proves `jolt build` links the MCP
namespaces. The nREPL step uses a jolt-lang/nrepl checkout if present,
otherwise fetches v0.4.0 as a git dependency (git and network, first time).

`--window` opens a real window (don't type while it runs) and checks the
handshake and `listChanged`, the nineteen window tools plus a startup
plugin's tool, open/Run/edit/save, a runaway evaluation that must time out
within 10 s, the plugin tools, and the EOF path: closing stdin with an
unsaved edit must end the process within 20 s, leave no Jolt child, and
leave the file unchanged. 25 checks; `--bin PATH` works too.

`--attach` starts a real window with `:mcp-listen? true` under a temp
`XDG_CONFIG_HOME` (don't type) and runs `jonquil --mcp-attach` against it.
It checks the nineteen window tools, an evaluation, that closing stdin ends
the bridge within 10 s with the window still running, and that a second
bridge attaches. It ends with SIGTERM to the window, which must exit within
20 s and remove its registry file. 17 checks.

</details>

## Self-test and docs: ten windows, one binary, one camera

Five harnesses drive the real GTK front end.

**`bb gtk:selftest`** opens ten real GTK windows in turn and runs the rows
against them, calling the same button and key handlers a person would use.
The full application window alone has 399 rows.

<details>
<summary><strong>The ten groups, and what each one covers</strong></summary>

Fakes stand in for real toolchains (in process, or as a small script
started as a child for jank and node), so only the nREPL group needs a
real runtime.

| # | File (`dev/`) | Covers | Needs |
|---|---|---|---|
| 1 | `gtk_selftest.clj` | Definitions editor and Interactions console | |
| 2 | `gtk_selftest_window.clj` | the full window, rows split by feature under `gtk_selftest_window/` | |
| 3 | `gtk_selftest_nrepl.clj` | a `{:runtime :nrepl}` project with a real Jolt child | the jolt-lang/nrepl checkout (one failing row without it) |
| 4 | `gtk_selftest_cljd.clj` | ClojureDart attach | fake server `fake_cljd_nrepl.clj` named by `NREPL.lock` |
| 5 | `gtk_selftest_clj.clj` | JVM Clojure attach, including slow-answer rows (its `:delays` atom) | fake server `fake_jvm_nrepl.clj` named by `.nrepl-port` |
| 6 | `gtk_selftest_tree.clj` | session restore from `sessions.edn` (two files plus one that's gone) | |
| 7 | `gtk_selftest_jank.clj` | a `.jank` project | `JONQUIL_JANK` set to `dev/fake-jank` (done by the task) |
| 8 | `gtk_selftest_args.clj` | launching like a terminal, via `jonquil.main/gtk-argv` | |
| 9 | `gtk_selftest_cljs.clj` | shadow-cljs attach | fake server `fake_shadow_nrepl.clj` named by `.shadow-cljs/nrepl.port` |
| 10 | `gtk_selftest_cljs_node.clj` | ClojureScript on node, and the browser target | `JONQUIL_CLOJURE` = `dev/fake-cljs-clojure`; `JONQUIL_OPEN_URL` = `dev/fake-open-url`, which logs to `FAKE_OPEN_URL_LOG` |

To run the jank group alone, set `JONQUIL_JANK` to the absolute path of
`dev/fake-jank` and run `jolt -M:selftest-jank`.

</details>

<details>
<summary><strong>How the rows run: go blocks, timeouts and the GTK thread</strong></summary>

Each row is a core.async go block. `gtk-async/run-rows` runs them in order,
and a per-row timeout records a FAIL instead of hanging the run. An overdue
row is abandoned at its next harness call, so it can't act into later rows.

The hard rule: the GTK thread never parks. Every GTK, editor or console
call goes through `on-gtk` or an `await-until` predicate from
`dev/gtk_async.clj`; a row's body never calls GTK directly. A row that
throws is recorded as a FAIL by `go-row` and the run moves on.

Before each row the drivers clear the editor and console input and wait for
the engine to go idle. If it's still busy, `gtk-async/ready-for-row?`
presses Stop and waits once more; if that fails the row is skipped with a
`:row-skipped-busy` FAIL (where Stop can't interrupt, as with shadow-cljs,
every later row is skipped). Drivers close their window through
`gtk-async/close-window-bounded!`, which records a FAIL and exits 1 if the
window stays open.

</details>

**`bb smoke:binary`** builds `target/smoke/jonquil` (skip with
`--no-build`), launches it as a subprocess, and drives it with
`cgevent key --pid`, so no keystroke goes to other windows or `--global`.
It checks file bytes, window titles, and screen pixels (OCR through
`cgevent ocr-inspect`, plus a colour check of the theme's text and prompt).
Every process it starts is killed on every exit path.

**`bb smoke:cljd`** drives a built `./jonquil` against a real, running
ClojureDart app built with nREPL support. The gitignored
`dev/smoke/cljd_app.local.edn` names it: `:dir` (absolute path), `:main-ns`
(the namespace Run loads), and optionally `:fork-sha` (a prefix the app's
`deps.edn` `:git/sha` must start with). Without the file the task prints
how to create it and exits 1. If the app isn't up or the screen is locked,
every step SKIPs and it exits 0.

<details>
<summary><strong>What it does, step by step</strong></summary>

One Jonquil process stays open: open `drive.cljd`, Run and wait for
`Loaded <main-ns>`, read `(where)`, send `(next!)` and `(prev!)` (the
second only after `(where)` confirms the first landed), Run Form on a typed
`let` then undo, quick doc and completion, Stop during a slow eval and
reconnect, close. It never saves the app's file and never quits, signals or
restarts the app, and it compares the app's `git status` before and after.
It treats the buffer as clean only when the title reads exactly
`drive.cljd - Jonquil`. Quit and restart stay manual checks, so those two
steps always SKIP. A close running past 3 s saves a screenshot and stack
sample under `target/smoke/`.

</details>

**`bb smoke:cljs`** drives a built `./jonquil` against a real, running
shadow-cljs app. Start the watch and a JS runtime first (`npx shadow-cljs
watch <build>`, then the browser tab or node script); the task never starts
or stops them. The gitignored `dev/smoke/cljs_app.local.edn` holds
`{:dir "..." :build :dev}`, and without it the task prints how to create
it. With several builds it writes a temporary untracked `.jonquil.edn` into
the app directory to name one, removed on every exit path. 18 checks.

**`bb docs:shots`** self-captures every `docs/images/*.png` and the GIFs
the guide pages embed, launching a fresh `jolt -M:shots <scene-id>` per
scene (32 scenes). Retake one image with that command directly. First runs
can download dependencies (ClojureScript and shadow-cljs scenes, and the
ClojureDocs export for `clj-full-doc`). A scene whose tools are missing
prints a line and exits 0 without an image.

<details>
<summary><strong>The jank, ClojureScript, shadow-cljs, nREPL and JVM Clojure scenes</strong></summary>

| Scene | Needs |
|---|---|
| `jank` | a real jank (`JONQUIL_JANK`, `PATH`, the usual install folders) |
| `cljs-node`, `cljs-browser` | `clojure` CLI (`JONQUIL_CLOJURE` or login `PATH`); `cljs-node` also `node`; `cljs-browser` writes `{:cljs {:target :browser}}` and opens a real tab |
| `nrepl-bb` | `bb` on the login `PATH`; starts `bb nrepl-server` on a free loopback port and names it in `.jonquil.edn` as `{:dialect :nrepl}` |
| `cljs-shadow` | `clojure` CLI, `node`, `npm`; runs a real shadow-cljs 3.2.0 watch with a `:node-script` build, after `npm install ws` |
| `clj-attach`, `clj-quick-doc`, `clj-full-doc`, `clj-inspector`, `clj-source` | Java 17+ and `clojure`; a real `clojure -M:serve` started as `bb test:clj` does |

`bb docs:shots` sets `JONQUIL_CLOJURE` to the bare name `clojure` for the
two cljs scenes (unless you set it) so no home directory lands in the
image. Retake one with `JONQUIL_CLOJURE=clojure jolt -M:shots cljs-node`.

</details>

Every harness isolates your settings. The window self-tests and
`docs:shots` pass a temp `:settings-path` to `jonquil.main/start!`, and the
smokes point `XDG_CONFIG_HOME` at a fresh temp directory per process.
Nothing touches `~/.config/jonquil/settings.edn`.

Temp directories come from `dev/temp_dirs.clj`, which deletes them when the
tests or driver finish. `bb clean:tmp` removes what older runs left (only
names these harnesses make); `bb clean:tmp --dry-run` lists them first.

### Traps

- **Don't type while a self-test or smoke window has focus.** A stray
  keystroke fails an unrelated row.
- **A phantom second display at scale 1.0** breaks `smoke:binary`'s OCR and
  makes `docs:shots` images 1x instead of Retina 2x. Check
  `system_profiler SPDisplaysDataType`.
- Never use `cgevent type` against this app: GTK's macOS backend reads the
  key code, not the Unicode string, so text arrives mangled.
- **An interrupted `bb test:nrepl` or `bb gtk:selftest` can leave a Jolt
  child.** Check `pgrep -fl "jolt.nrepl/start 79"` and kill only pids from
  your interrupted run.
- **An interrupted `bb test:clj` can leave a JVM.** Check
  `pgrep -fl clojure-cli.repl.server`, and kill only a pid that run started.

## The macOS app: bundle, install, icon

- `bb app:bundle` rebuilds `./jonquil` and makes `./Jonquil.app`, printing
  a `check` line for `plutil -lint` on the `Info.plist`, the executable
  bit, and `codesign -dv` showing the linker's ad-hoc signature. A failed
  check exits non-zero naming it.
- `bb app:install` bundles, moves the result to
  `~/Applications/Jonquil.app` (replacing an existing one only if its plist
  names `net.b12n.jonquil`), and runs `lsregister -f`. The checkout's copy
  is unregistered and removed, so Open With lists Jonquil once.
- `bb app:icon` regenerates `resources/Jonquil.icns` and
  `resources/icon/jonquil-256.png` from `resources/icon/icon.swift`. It
  needs Swift, and you run it only after changing the icon.

The bundle is deliberately not re-signed: `jolt build` appends the program
image after the launcher's Mach-O, so `codesign --force` fails strict
validation. macOS checks the linker's ad-hoc signature, which the task
verifies.

On Linux, `bb app:install` runs `bb app:build` and copies the binary, a
launcher, the icon and the MIME types under `~/.local/share`
(`dev/app_linux.clj`); `bb app:uninstall` removes them. On macOS it only
says to drag the app to the Trash.

## Linux checks in Docker

Every Linux check runs in a local Docker container, so a Mac can verify the
Linux build. There is no CI for it: the workflows in `.github/` stay
manual. Docker is the only requirement beyond the usual tools.

| Task | What it does |
|---|---|
| `bb linux:image` | builds `jonquil-linux:<arch>` (Ubuntu 26.04, GTK 4, Xvfb, Jolt v0.8.23, babashka) |
| `bb linux:test [ns...]` | `bb test` on a copy of the checkout |
| `bb linux:selftest` | `bb gtk:selftest` under Xvfb |
| `bb linux:build` | `bb app:build`; the binary lands in `target/linux/<arch>/` |
| `bb linux:run -- cmd...` | any command in the copied tree |
| `bb linux:vnc` | a desktop in the browser for the hand check |
| `bb linux:clean` | removes both images and `target/linux` |

Tasks take `--arch arm64|amd64` (default: the host's), except
`linux:clean`. The arm64 image compiles Jolt and Chez Scheme from source
because Jolt publishes no Linux arm64 binary, so it's slow to build (about
11 minutes); amd64 uses the pinned release tarball but still takes
about 6.5 minutes, its `apt` step emulated. Pulling the base image
needs Docker Hub, so run the first `bb linux:image` from a normal terminal.

The checkout is mounted read-only and copied to `~/jonquil` in the
container, skipping `jonquil`, `jonquil.build`, `Jonquil.app`, `_site` and
`target`. Only `target/linux/<arch>/` is written in your tree.

A non-host arch runs under emulation, several times slower.
`bb linux:selftest` then sets `JONQUIL_SELFTEST_EMULATED=1`, widening the
paint-async row's budget from 150 to 750 ms. The 20 s wait for a Jolt nREPL
child can still run out on a busy host (parked in the ROADMAP).

### The noVNC hand check

`bb linux:vnc` starts a container with Xvfb, openbox, pcmanfm, x11vnc and
noVNC, installs Jonquil with `bb app:install`, and prints a URL such as
`http://localhost:6080/vnc.html?autoconnect=true&resize=scale`. The port is
the first free one from 6080; `--port P` asks for one and is refused when
busy. In pcmanfm, open `~/smoke` (`hello.clj`, `hello.jank`,
`nrepl-project`) and check that right-clicking a file offers Jonquil, that
it opens, and that selecting both hello files opens one window with two
tabs. For anything else use `docker exec jonquil-vnc-<P> ...` (add
`-e DISPLAY=:1` for windows). Ctrl-C stops the container and frees the
port.

## Quality: lint, format, and the git hooks

- `bb lint` runs clj-kondo over `src`, `test` and `dev` in report mode.
  `bb lint:strict` exits non-zero on any finding (2 for warnings, 3 for
  errors); the manual CI workflow gates on it. The git hooks block only on
  clj-kondo errors.
- `bb lsp:format` / `bb lsp:format-check` and `bb lsp:clean-ns` /
  `bb lsp:clean-ns-check` reformat (or dry-run) code and `ns` forms with
  clojure-lsp; `bb lsp:check` runs both dry-run checks.
- The LSP tasks cover `src`, `test` and `dev`, skipping
  `dev/smoke/fixtures/*.clj` (deliberately invalid Clojure that aborts
  clojure-lsp's parser), `dev/smoke/PixelAt.java`, and the two files lifted
  from DrClojure-Neo (`src/jonquil/syntax.clj`,
  `test/jonquil/upstream/syntax_test.clj`) so re-syncs diff cleanly.
- `bb hooks:install` writes a fast pre-commit hook (clj-kondo errors plus a
  format dry-run). `bb hooks:install:full` adds the full test suite.
  `bb hooks:uninstall` removes either. Installing runs the checks first and
  warns if the tree doesn't pass yet.

### The manual lint workflow

`.github/workflows/lint.yml` runs `bb lint:strict` and `bb lsp:format-check`
on `ubuntu-latest` with no Jolt involved. It has no push, pull request or
schedule trigger (Jonquil is private for now); run it from the Actions tab
or with `gh workflow run lint.yml`.

## Benchmark

`bb bench:edit [sizes...]` times what typing costs in a large file. For
each size (default 200, 2000, 8000 and 20000 lines of Jonquil's own source)
it opens a real GTK window, so leave the keyboard alone. It prints
`bench <measure> lines=<n> ms=<x>` lines:

- `plan`: the median `paint-plan` run (background thread, so not a stall).
- `stall-edit`, `stall-quote`: the longest main-loop iteration in the 2 s
  after typing a letter, or a double quote (which flips string parity),
  into the first `defn` name in the first 40 lines.
- `settle-stall-edit`, `settle-stall-quote`: time until the repaint has
  landed; `VOID` if it didn't settle.
- `jump`: the median jump-to-definition.

Each timed run gets a fresh copy of the text, because the tokenizer caches
by string identity. What remains of the stall at 20,000 lines is the copy
of the buffer text on the GTK thread, about 50 ms.

## Upstream

`bb upstream:diff` lists upstream DrClojure-Neo commits since
`UPSTREAM_PIN` that touch `src` or `test`. It needs no setup: it keeps
its own clone under `~/.cache/jonquil/`. See
[Porting and attribution](porting-and-attribution.md#staying-in-sync).

## Docs

`bb site:build`, `bb site:serve [port]` and `bb site:clean` build and
preview this documentation site (`docs/guide/`) using a sibling checkout of
[jlt-commons/docs-engine](https://github.com/jlt-commons/docs-engine),
looked up as `$JLT_DOCS_ENGINE`, then `~/dev/jlt-commons/docs-engine`, then
`../docs-engine` (a sibling there can be an unrelated older fork). If none
exists, the task prints where to `git clone` it. There's no workflow or
GitHub Pages site behind this: it's a local preview until the project is
open-sourced.
