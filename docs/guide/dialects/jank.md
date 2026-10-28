# jank (experimental)

Part of [Dialects and runtimes](index.md).

Unlike ClojureDart and JVM Clojure, you start nothing: Jonquil launches a
`jank repl` per window, on the first evaluation.

- A window picks jank for a `.jank` file, or when `.jonquil.edn` says
  `{:dialect :jank}`.
- Install jank with `brew install jank-lang/jank/jank`, or set
  `JONQUIL_JANK` to a binary. Lookup order: `JONQUIL_JANK`, `jank` on
  `PATH`, `/opt/homebrew/bin/jank`, `~/.local/bin/jank`. With none, the
  first Run says so.
- The status bar reads `jank (not running)`, then `jank (running)`.

![A jank window after Run: the console starts jank, prints the program's line, then answers a REPL entry in the file's namespace](../../images/jank.png)

## How it runs

jank runs from a private temporary folder, so your project's `.nrepl-port`
is never touched. The module path is `<project>/src` (or the project
folder). For a loose file it's the root its `ns` implies, so a sibling
namespace loads; if `ns` doesn't match the path, it's the file's folder.
Closing the window removes the folder and stops jank.

- **Run** sends a `load-file` and moves the prompt to the file's namespace.
  Run Selection and Run Form use the buffer's namespace, and ask you to
  Run the file first if it isn't loaded.
- **Stop** kills and restarts jank, since it has no interrupt; earlier
  definitions are gone.
- **Reset on Run** is on by default: a Run restarts jank first (except for
  the first request).
- For a loose `.jank` file, start with Run. If a REPL line or Run Selection
  starts jank first, it lacks the module path and a sibling `require`
  fails until a Run restarts it.

Quick documentation reads var metadata, and there are no docs while an
evaluation runs ("No docs while jank is running."). Completion merges
jank's names with the offline ones.

## Limits

- Programs can't read input.
- Jump to definition works only inside the buffer, and there's no inspector.
- You can't attach to a jank you started yourself.
- Output appears only when the evaluation ends, and a Stopped evaluation
  never shows its output. Watch for "Running..." before pressing Stop.
- `(loop [] (recur))` and a `recur` after a `when` fail with
  `codegen/internal-failure`. Put every `recur` in an `if` branch:
  `(loop [i 0] (if (< i n) (recur (inc i)) i))`.

More in [Known limits](../known-limits.md).
