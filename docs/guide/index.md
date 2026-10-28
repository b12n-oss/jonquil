# Jonquil guide

Jonquil is a DrRacket-style Clojure IDE written in Jolt: a Definitions
editor, an Interactions console, Run and a Stop that really stops. Start
with the `README.md` in the repository root for a quick look at the window
and a one-line build and run; these pages go into what the README and the
code don't cover on their own.

## Read in this order

1. **[Getting started](getting-started.md)**: install Jolt and GTK4,
   build and run Jonquil, and find where its saved settings live.
2. **Using Jonquil**, in these pages:
   - [Using Jonquil](using-jonquil.md): the window, Run versus the REPL
     prompt, stdin, Stop, Reset on Run, editing, find and replace, themes.
   - [Files and tabs](files-and-tabs.md): Open and Save, tabs, Finder and
     the Dock, the file tree.
   - [Help and the inspector](help-and-inspector.md): completion, quick
     doc, jump, rename, the palette, and inspecting values.
   - [Dialects and runtimes](dialects/index.md): choosing a dialect and
     running Jolt out of process, then a page each for
     [ClojureDart](dialects/clojuredart.md),
     [JVM Clojure](dialects/jvm-clojure.md),
     [ClojureScript](dialects/clojurescript.md),
     [any other nREPL server](dialects/other-nrepl.md) and
     [jank](dialects/jank.md).
   - [Plugins](plugins.md), [Driving Jonquil from an agent](mcp.md),
     [Keys](keys.md) and [Known limits](known-limits.md).
3. **[Development](development.md)**: the task groups `bb info` prints,
   the unit suite, the self-test and binary-smoke harnesses, screenshots,
   and lint, format, and the git hooks.
4. **[Architecture](architecture/index.md)**: the namespace layers, why GTK is
   only ever required from `jonquil.main`, the inspector and the eval
   engine, with pages for [commands, keys and plugins](architecture/commands-keys-plugins.md),
   [MCP](architecture/mcp.md), [dialects](architecture/dialects.md),
   [attached](architecture/attached-runtimes.md) and
   [launched](architecture/launched-runtimes.md) runtimes, and
   [macOS and Linux](architecture/macos-and-linux.md).
5. **[Porting and attribution](porting-and-attribution.md)**: what came
   from DrClojure, what Jonquil rebuilt instead, and the Jolt fixes this
   project needed along the way.
6. **[Contributing](contributing.md)**: the local setup a change needs,
   the gates it must pass, and how to send it.

`ROADMAP.md` holds the phase-by-phase status and the parked items for each
one. `UPSTREAM.md` holds the file-by-file provenance table that Porting and
attribution summarizes.
