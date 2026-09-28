# Development

`bb info` (or `jolt info`, since jolt reads the same `bb.edn`) prints the
full task list grouped by what it's for. This page walks through the same
groups with the detail the cheat-sheet has no room for: what each harness
actually drives, and the traps that catch anyone running them for the first
time.

## Verify: check, test, and the pre-commit gate

`bb check` requires every project namespace headlessly and exits 0; it
currently loads 32 namespaces in under half a second, and it's the fastest
way to catch a namespace that doesn't compile before running anything
slower. `bb test` runs the full unit suite (185 tests, 1223 assertions
today, about 27 s) with nothing outside the process, or pass namespace
names to run a subset for the TDD loop: `bb test jonquil.edit-test`. That
count now includes the attach evaluator's own tests, run against an
in-process fake ClojureDart server rather than a real one, so there's
still no child process and nothing outside the repo.

`bb test:nrepl` runs a second, separate suite (28 tests, 97 assertions,
about 32 s): every test in it spawns a real Jolt child over
`jonquil.nrepl.child` and talks to it over a socket, so it needs the
jolt-lang/nrepl checkout described in [Using Jonquil](using-jonquil.md).
`bb test` never touches a child process and stays that self-contained on
purpose, which is why the live tests are a separate task rather than a
tag inside the same suite. `bb verify` is the pre-commit gate: clj-kondo
in report mode, then both suites, `bb test` followed by `bb test:nrepl`,
both of which must pass; end to end it runs in about 60 s.

## Self-test and docs: four windows, one binary, one camera

Three harnesses drive the real GTK front end, and each covers ground the
others can't.

**`bb gtk:selftest`** opens four real GTK windows in turn and runs the
front-end self-test rows against them, calling the same button and key
handlers a person would use rather than the internal functions directly.
The first window covers the Definitions editor and the Interactions console
(`dev/gtk_selftest.clj`, 35 rows); the second covers the full application
window (`dev/gtk_selftest_window.clj`, 146 rows), with its rows split by
feature area under `dev/gtk_selftest_window/`: `support`, `run_form`,
`run_repl`, `files`, `find_replace`, `close_prerun`, `window_keys`,
`themes`, `popovers_quickdoc`, `completion`, `rename_menus`,
`console_layout`, and `dialects`. The third (`dev/gtk_selftest_nrepl.clj`,
15 rows) opens against a throwaway project whose `.jonquil.edn` asks for
`{:runtime :nrepl}`, so a real Jolt child runs behind it, and its rows
cover starting, running, Stop, help, and closing over that live
connection; it needs the jolt-lang/nrepl checkout the same way
`bb test:nrepl` does, and records a single failing row rather than
opening anything if that checkout isn't there. The fourth
(`dev/gtk_selftest_cljd.clj`, 13 rows) opens against a throwaway
ClojureDart app directory whose `NREPL.lock` names a fake fork server
running in this same process (`dev/fake_cljd_nrepl.clj`), so it needs no
fork checkout and no real app; its rows cover connecting, a quiet load,
Run Selection and Run Form switching the session's namespace, quick doc,
completion, and Stop's detach, over that fake connection.

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

**`bb smoke:binary`** is the one harness that runs outside the GTK process
under test. It builds `./jonquil` (skip the build with `--no-build`),
launches it as a real subprocess, and drives it with `cgevent key --pid`,
routing every keystroke to that process's own pid so nothing leaks to the
user's other windows and nothing is ever posted `--global`. It reads results
back three ways: real file bytes on disk, real window titles, and real
screen pixels through OCR (`cgevent ocr-inspect`) plus a per-pixel colour
check for the theme's text and prompt colours. Every jonquil process the
script starts is killed on every exit path, success, failure, or Ctrl-C.

**`bb docs:shots`** self-captures every `docs/images/*.png` and the one GIF
the guide pages embed. Rather than one shared window walking through every
scene, it launches a fresh `jolt -M:shots <scene-id>` process per scene, so
each screenshot shows only its own scene's console history instead of every
earlier scene's leftover output. `bb docs:shots` regenerates every scene in
one pass; to retake just one, run `jolt -M:shots <scene-id>` directly, the
same call the task makes per scene, so an unrelated screenshot doesn't shift
underneath an unrelated review.

All three harnesses isolate the user's own settings from the app they're
driving. The window self-tests and `docs:shots` pass a temp `:settings-path`
into `jonquil.main/start!`, and `smoke:binary` points `XDG_CONFIG_HOME` at a
fresh temp directory for every process it launches. Neither the self-tests
nor the smoke ever reads or writes `~/.config/jonquil/settings.edn`.

### Traps

- **Don't type while a self-test window has focus.** `bb gtk:selftest` and
  `bb docs:shots` open real windows that take real keyboard focus; a stray
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

## Quality: lint, format, and the git hooks

`bb lint` runs clj-kondo over `src`, `test`, and `dev` in report mode;
`bb lint:strict` is the same lint but exits non-zero on any finding (2 for
warnings, 3 for errors), which is what CI and the hooks actually gate on.
`bb lsp:format` and `bb lsp:format-check` reformat (or dry-run check) with
clojure-lsp, and `bb lsp:clean-ns`/`bb lsp:clean-ns-check` do the same for
`ns` forms; `bb lsp:check` runs both dry-run checks together. All four LSP
tasks scope themselves to `src`, `test`, and every real file under `dev`,
skipping `dev/smoke/fixtures/*.clj` and `dev/smoke/PixelAt.java`: the
fixtures are deliberately invalid or minimal Clojure, fed to the built
binary to exercise unclosed-paren recovery, and clojure-lsp's own parser
aborts the whole run the moment it meets one.

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
looked up as `$JLT_DOCS_ENGINE`, then `../docs-engine`, then
`~/dev/jlt-commons/docs-engine`, and print where to `git clone` it if none
of those exist. There's no workflow and no GitHub Pages site behind this:
it's a local preview until the project is open-sourced.
