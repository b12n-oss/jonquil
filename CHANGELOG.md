# Changelog

All notable changes to this project are documented here.

## [Unreleased]

### Added

- Initial public release preparation.
- The inspector (Cmd/Ctrl+I) works for Jolt running out of process: Jonquil loads its inspector code into the Jolt child as nREPL middleware, written under `~/.cache/jonquil/nrepl-inspect`, so stepping through a value leaves `*1`, `*2` and `*3` alone, on a plain nREPL server too.
- ClojureScript dialect (experimental): Jonquil attaches to a running shadow-cljs nREPL server, selects the build from `.jonquil.edn` or `shadow-cljs.edn`, and Runs a `.cljs` buffer in the JS runtime that has the build loaded; see the ClojureScript section of the using guide. `bb smoke:cljs` drives it against a live app.

### Changed

- A Jolt file outside any project runs in the window's own Jolt, out of process in a `deps.edn` project window, instead of refusing to run there while other tabs are open; closing tabs down to that file, or saving the window's only tab as one, no longer switches the window to in-process Jolt.
- Jolt now runs out of process by default in a project with a `deps.edn`, so a program that calls `System/exit`, `shutdown-agents` or redefines `clojure.core` takes down a separate Jolt child rather than Jonquil. A loose file still runs in process, and `.jonquil.edn` `{:runtime :in-process}` keeps a project there, for `read-line`. With no jolt-lang/nrepl checkout, Jonquil fetches nREPL v0.4.0 as a git dependency, and starts a plain nREPL server if that fails. The out-of-process start no longer lists "Output appears when an evaluation finishes" as a limit, since nREPL v0.4.0 streams output.
- Jonquil now needs Jolt v0.8.23 or later, and `pp` is referred at the prompt next to `pprint`, so `(pp)` pretty-prints `*1` in process and out of process.
- A `.cljs` file outside a shadow-cljs project (a loose file, or a figwheel or `cljs.main` project with no `shadow-cljs.edn`) now selects ClojureScript instead of falling back to in-process Jolt, and Run explains that there is no shadow-cljs project or build.
