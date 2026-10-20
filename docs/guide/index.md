# Jonquil guide

Jonquil is a DrRacket-style Clojure IDE written in Jolt: a Definitions
editor, an Interactions console, Run and a Stop that really stops. Start
with the `README.md` in the repository root for a quick look at the window
and a one-line build and run; these pages go into what the README and the
code don't cover on their own.

## Read in this order

1. **[Getting started](getting-started.md)**: install Jolt and GTK4,
   build and run Jonquil, and find where its saved settings live.
2. **[Using Jonquil](using-jonquil.md)**: the window, Run versus the REPL
   prompt, stdin, Stop, Reset on Run, editing, find and replace, Open and
   Save, tabs, the file tree, themes, the help features, the inspector, and
   the full key table. Its "Dialects and runtimes" part covers each language
   Jonquil runs: Jolt in process or out of process, ClojureDart, JVM
   Clojure and ClojureScript by attaching to a server you started (with
   auto-require for JVM and Jolt projects, and a shadow-cljs build for
   ClojureScript), any other nREPL server (babashka, Basilisp, other
   hosts), and jank and ClojureScript on node, which Jonquil launches
   itself.
3. **[Development](development.md)**: the task groups `bb info` prints,
   the unit suite, the self-test and binary-smoke harnesses, screenshots,
   and lint, format, and the git hooks.
4. **[Architecture](architecture.md)**: the namespace layers, why GTK is
   only ever required from `jonquil.main`, the eval engine, and the
   decisions behind the P4 help features.
5. **[Porting and attribution](porting-and-attribution.md)**: what came
   from DrClojure, what Jonquil rebuilt instead, and the Jolt fixes this
   project needed along the way.
6. **[Contributing](contributing.md)**: the local setup a change needs,
   the gates it must pass, and how to send it.

`ROADMAP.md` holds the phase-by-phase status and the parked items for each
one. `UPSTREAM.md` holds the file-by-file provenance table that Porting and
attribution summarizes.
