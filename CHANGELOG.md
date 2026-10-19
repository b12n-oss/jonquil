# Changelog

All notable changes to this project are documented here.

## [Unreleased]

### Added

- Jump to definition (F12) leaves the buffer for Jolt running out of process: a function from another file in the project, or from a jar dependency, opens in the source panel on its definition. Functions built into Jolt have no source file, so they show their documentation as before.

- Initial public release preparation.
- An nREPL dialect: `.jonquil.edn {:dialect :nrepl :nrepl {:port 1667}}` attaches to any nREPL server you started (babashka, a non-JVM Clojure, one on another host with `:host`). Features follow the ops the server offers, and the console names the ones it lacks. Checked live against babashka (`bb test:bb`).
- Quick docs ask cider's `info` when a server has no `lookup` op (Basilisp has only `info`), for the nREPL dialect and JVM Clojure alike.
- `bb test:basilisp` runs the nREPL dialect against a real `basilisp nrepl-server`.
- JVM Clojure asks cider's `complete` for completions when the server has no builtin `completions` (a pre-1.0 nREPL with cider-nrepl), instead of offering only the buffer's names.
- `read-line` and `read` work for Jolt running out of process: Jonquil's own nREPL middleware in the Jolt child asks for each line at the prompt, Cmd/Ctrl+D ends input for that read, and Stop frees a waiting read. A plain nREPL server (the fallback when jolt-lang/nrepl can't be fetched), or a child that couldn't load Jonquil's middleware, still has no stdin.
- The inspector (Cmd/Ctrl+I) works for Jolt running out of process: Jonquil loads its inspector code into the Jolt child as nREPL middleware, written under `~/.cache/jonquil/nrepl-middleware`, so stepping through a value leaves `*1`, `*2` and `*3` alone, on a plain nREPL server too.
- ClojureScript dialect (experimental): Jonquil attaches to a running shadow-cljs nREPL server, selects the build from `.jonquil.edn` or `shadow-cljs.edn`, and Runs a `.cljs` buffer in the JS runtime that has the build loaded; see the ClojureScript section of the using guide. `bb smoke:cljs` drives it against a live app.

### Fixed

- A project kept in process (`{:runtime :in-process}`) can require its own namespaces: its `deps.edn` `:paths` are Jolt's source folders there now, so F12 also opens those files. `:deps` libraries are still only loaded out of process.
- With Jolt in process, F12 on a function another tab's Run defined opens that tab's file on the definition, once the file is saved, instead of showing the docs.
- Jump to definition keeps working on Jolt newer than v0.8.23, which records a required namespace's file by its classpath name (`demo/util.clj`) rather than a path: Jonquil now looks that name up on the classpath, in process and out.

- The completion list is as tall as its names, up to eight rows, rather than always eight; a name the buffer defines is labelled `buffer`, not `user`; the quick-doc popover fits its text, up to the width it always had; solarized's selected completion row is visible; and the status bar no longer runs into the window's right edge.

- The nREPL dialect's error line against Basilisp shows the exception (`ZeroDivisionError: Fraction(1, 0)`) or a compile error's message, not `Traceback (most recent call last):`.
- The nREPL dialect's stdin notice no longer says every read waits until Stop: Basilisp's `read-line` reads the server's own terminal.
- Open With offers Jonquil for `.cljs` files too, on macOS (the app's document types) and Linux (the `text/x-clojure` MIME globs), now that there's a ClojureScript dialect.
- JVM Clojure: output from another thread while `read-line` waits no longer takes the console out of input mode.
- Find and replace: Return on a focused Replace or Replace All button presses it, instead of moving to the next match.
- Format All and Format Selection keep the caret on the same code and scroll it into view.
- The inspector's `d` does nothing while the panel's actions are disabled, and a runtime switch that hides a focused inspector or source panel moves focus to the editor.
- F12 inside the source panel on a definition in the window's own file moves the window's caret there, instead of loading that file into the panel.
- `--mcp-attach DIR` follows a listening window after Open Folder re-roots it.
- Old `~/.cache/jonquil/nrepl-middleware` folders from other Jonquil versions are pruned after a day unused.

### Changed

- Completion, its doc preview, quick doc and jump to definition no longer hold up typing against a slow server (JVM Clojure, ClojureScript, ClojureDart, any nREPL server, Jolt out of process, jank). Jonquil waits about 25 ms for the answer, then opens the popup on the names it already knows and merges the server's in when they land; quick doc and jump say "Looking up X..." meanwhile. A jump to a definition in the file never waits for the server.

- A Jolt file outside any project runs in the window's own Jolt, out of process in a `deps.edn` project window, instead of refusing to run there while other tabs are open; closing tabs down to that file, or saving the window's only tab as one, no longer switches the window to in-process Jolt.
- Jolt now runs out of process by default in a project with a `deps.edn`, so a program that calls `System/exit`, `shutdown-agents` or redefines `clojure.core` takes down a separate Jolt child rather than Jonquil. A loose file still runs in process, and `.jonquil.edn` `{:runtime :in-process}` keeps a project there. With no jolt-lang/nrepl checkout, Jonquil fetches nREPL v0.4.0 as a git dependency, and starts a plain nREPL server if that fails. The out-of-process start no longer lists "Output appears when an evaluation finishes" as a limit, since nREPL v0.4.0 streams output.
- Jonquil now needs Jolt v0.8.23 or later, and `pp` is referred at the prompt next to `pprint`, so `(pp)` pretty-prints `*1` in process and out of process.
- A `.cljs` file outside a shadow-cljs project (a loose file, or a figwheel or `cljs.main` project with no `shadow-cljs.edn`) now selects ClojureScript instead of falling back to in-process Jolt, and Run explains that there is no shadow-cljs project or build.
