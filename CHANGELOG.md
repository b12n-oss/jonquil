# Changelog

All notable changes to this project are documented here.

## [Unreleased]

### Added

- Initial public release preparation.
- ClojureScript dialect (experimental): Jonquil attaches to a running shadow-cljs nREPL server, selects the build from `.jonquil.edn` or `shadow-cljs.edn`, and Runs a `.cljs` buffer in the JS runtime that has the build loaded; see the ClojureScript section of the using guide. `bb smoke:cljs` drives it against a live app.

### Changed

- Jonquil now needs Jolt v0.8.23 or later, and `pp` is referred at the prompt next to `pprint`, so `(pp)` pretty-prints `*1` in process and out of process.
- A `.cljs` file outside a shadow-cljs project (a loose file, or a figwheel or `cljs.main` project with no `shadow-cljs.edn`) now selects ClojureScript instead of falling back to in-process Jolt, and Run explains that there is no shadow-cljs project or build.
