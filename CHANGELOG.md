# Changelog

All notable changes to this project are documented here.

## [Unreleased]

### Added

- Initial public release preparation.
- An nREPL dialect: `.jonquil.edn {:dialect :nrepl :nrepl {:port 1667}}` attaches to any nREPL server you started (babashka, a non-JVM Clojure, one on another host with `:host`). Features follow the ops the server offers, and the console names the ones it lacks. Checked live against babashka (`bb test:bb`).
- JVM Clojure asks cider's `complete` for completions when the server has no builtin `completions` (a pre-1.0 nREPL with cider-nrepl), instead of offering only the buffer's names.
- `read-line` and `read` work for Jolt running out of process: Jonquil's own nREPL middleware in the Jolt child asks for each line at the prompt, Cmd/Ctrl+D ends input for that read, and Stop frees a waiting read. A plain nREPL server (the fallback when jolt-lang/nrepl can't be fetched), or a child that couldn't load Jonquil's middleware, still has no stdin.
- The inspector (Cmd/Ctrl+I) works for Jolt running out of process: Jonquil loads its inspector code into the Jolt child as nREPL middleware, written under `~/.cache/jonquil/nrepl-middleware`, so stepping through a value leaves `*1`, `*2` and `*3` alone, on a plain nREPL server too.
- ClojureScript dialect (experimental): Jonquil attaches to a running shadow-cljs nREPL server, selects the build from `.jonquil.edn` or `shadow-cljs.edn`, and Runs a `.cljs` buffer in the JS runtime that has the build loaded; see the ClojureScript section of the using guide. `bb smoke:cljs` drives it against a live app.

### Fixed

- Open With offers Jonquil for `.cljs` files too, on macOS (the app's document types) and Linux (the `text/x-clojure` MIME globs), now that there's a ClojureScript dialect.
- JVM Clojure: output from another thread while `read-line` waits no longer takes the console out of input mode.
- Find and replace: Return on a focused Replace or Replace All button presses it, instead of moving to the next match.
- Format All and Format Selection keep the caret on the same code and scroll it into view.
- The inspector's `d` does nothing while the panel's actions are disabled, and a runtime switch that hides a focused inspector or source panel moves focus to the editor.
- F12 inside the source panel on a definition in the window's own file moves the window's caret there, instead of loading that file into the panel.
- `--mcp-attach DIR` follows a listening window after Open Folder re-roots it.
- Old `~/.cache/jonquil/nrepl-middleware` folders from other Jonquil versions are pruned after a day unused.

### Changed

- A Jolt file outside any project runs in the window's own Jolt, out of process in a `deps.edn` project window, instead of refusing to run there while other tabs are open; closing tabs down to that file, or saving the window's only tab as one, no longer switches the window to in-process Jolt.
- Jolt now runs out of process by default in a project with a `deps.edn`, so a program that calls `System/exit`, `shutdown-agents` or redefines `clojure.core` takes down a separate Jolt child rather than Jonquil. A loose file still runs in process, and `.jonquil.edn` `{:runtime :in-process}` keeps a project there. With no jolt-lang/nrepl checkout, Jonquil fetches nREPL v0.4.0 as a git dependency, and starts a plain nREPL server if that fails. The out-of-process start no longer lists "Output appears when an evaluation finishes" as a limit, since nREPL v0.4.0 streams output.
- Jonquil now needs Jolt v0.8.23 or later, and `pp` is referred at the prompt next to `pprint`, so `(pp)` pretty-prints `*1` in process and out of process.
- A `.cljs` file outside a shadow-cljs project (a loose file, or a figwheel or `cljs.main` project with no `shadow-cljs.edn`) now selects ClojureScript instead of falling back to in-process Jolt, and Run explains that there is no shadow-cljs project or build.
