# Dialects and runtimes

Jonquil evaluates through a dialect profile: what a language's files look
like, how they're painted, and which evaluator runs them. Six profiles
exist today: Jolt, ClojureDart (experimental), JVM Clojure, ClojureScript
(experimental), jank (experimental) and nREPL, which attaches to any nREPL
server you started.

The status bar's dialect label shows which one a window uses and whether
its runtime is up:

| Dialect | Label |
|---|---|
| Jolt | `Jolt`, or `Jolt (nREPL)` when it runs out of process |
| ClojureDart | `ClojureDart (attached)` / `ClojureDart (not running)` |
| JVM Clojure | `Clojure (JVM) (attached)` / `Clojure (JVM) (not running)` |
| ClojureScript, shadow-cljs | `ClojureScript (shadow-cljs) (attached)` / `(not running)` |
| ClojureScript, own runtime | `ClojureScript (node) (running)` / `(not running)`, or `ClojureScript (browser)` with the same endings |
| jank | `jank (running)` / `jank (not running)` (until the first evaluation starts one) |
| nREPL | `nREPL (attached)` / `nREPL (not running)` |

## Choosing a dialect

Jonquil picks a dialect and a runtime for each window, in this order:

1. `.jonquil.edn` in the project, if it names one.
2. A single profile marker in the project (Jolt's is `deps.edn`).
3. The open file's extension.
4. The `:dialect` saved in `settings.edn`.
5. Jolt, when none of the above decides.

The project is the nearest directory, at or above the open file, holding
`.jonquil.edn` or a profile marker. A loose file or an unsaved window has
no project, so only the extension and the settings default are left.

<details>
<summary><strong>How each dialect gets picked</strong></summary>

- **ClojureDart**: marker `pubspec.yaml`; `.cljd` is ClojureDart even when
  a `deps.edn` is also present, while `.clj`, `.cljc` and anything else stay
  Jolt's.
- **JVM Clojure**: only `{:dialect :clj}` picks it inside a project. A
  settings default of JVM Clojure applies to loose or unsaved files only.
- **ClojureScript**: marker `shadow-cljs.edn` attaches to your shadow-cljs
  server; a `.cljs` file elsewhere runs on node. `{:runtime :launch}` or
  `{:runtime :attach}` forces the runtime (see
  [ClojureScript](clojurescript.md)).
- **jank**: `.jank` files, or `{:dialect :jank}`.
- **nREPL**: only `{:dialect :nrepl}`. A `bb.edn` or Basilisp project stays
  Jolt's until you say so (see [Any nREPL server](other-nrepl.md)).

</details>

## `.jonquil.edn`

A `.jonquil.edn` at the project root pins the dialect and, for Jolt,
chooses the runtime:

```clojure
{:dialect :jolt :runtime :in-process}
```

Both keys default independently, so either can be left out. A file that
isn't valid EDN, or isn't a map, never blocks anything: the console prints
a line like `; project: .jonquil.edn isn't valid EDN, so it is ignored`
and selection falls through to the next rule.

- `{:dialect :jank}` pins jank (see [jank](jank.md)).
- `{:dialect :nrepl}` attaches to any nREPL server. `:nrepl` says where:
  `{:port 1667}`, `{:host "box" :port 1667}` or `{:port-file
  "path/to/port"}` (see [Any nREPL server](other-nrepl.md)).
- `{:dialect :clj}` is the only way to reach JVM Clojure: the project
  attaches to its own nREPL server instead of running under Jolt (see
  [JVM Clojure](jvm-clojure.md)).
- `:auto-require` lists namespaces to load as Jonquil connects. It works
  for JVM Clojure and Jolt; other dialects ignore it and the console says
  so (see [Auto-require](jvm-clojure.md#auto-require)).
- `:parinfer` sets [Parinfer](../using-jonquil.md#parinfer) for the
  project's tabs, overriding the global setting either way: `:smart`,
  `:indent` or `:paren` turns it on here, and `:off` keeps it off here
  even when the setting is on.
- `:format-style` pins what Format All and Format Selection produce for
  the project's tabs, so the repo formats one way for everyone whatever
  each person's Parinfer mode: `:paren-stable` always runs the Paren
  pass over the formatter's output, `:plain` never does. Left out, a
  format follows the tab's mode (see
  [Parinfer](../using-jonquil.md#parinfer)).

## Running Jolt out of process

In a project with a `deps.edn`, Jolt runs out of process. Jonquil starts a
separate Jolt nREPL server in the project directory and talks to it over a
socket. A program that calls `System/exit` or `shutdown-agents`, redefines
something in `clojure.core`, or otherwise takes Jolt down takes down that
child, not the window. The project's `deps.edn` is the child's classpath.

`{:runtime :in-process}` in `.jonquil.edn` keeps a project inside
Jonquil's process instead:

- Its `deps.edn` is resolved as the `jolt` command does, so files can
  require the project's other files and dependencies, and F12 opens either.
- A git or Maven dependency is fetched on first need ("Resolving
  deps.edn..."; Stop cancels). On failure the Run goes ahead on `:paths`
  alone and the console says why.
- Edits to the project's `deps.edn` re-resolve; edits to a `:local/root`
  dependency's own do not (touch the project's file).

`{:runtime :nrepl}` asks for a child in a folder without a `deps.edn`. A
file outside any project always runs in process. An untitled tab follows
the window's folder: out of process in a `deps.edn` window, in process in a
window with no folder.

<details>
<summary><strong>Start lines, Stop, switching projects, and stdin</strong></summary>

- The console reports the child's pid, port and project directory at start.
- Stop interrupts first; if the child doesn't answer in about a second, it
  kills and restarts the child and says so.
- Opening a file from a different project switches the session; the old
  evaluator is stopped and the console says so.
- stdin, the inspector and jump to definition work through Jonquil's own
  middleware, written to `~/.cache/jonquil/nrepl-middleware` (or under
  `$XDG_CACHE_HOME`). If that can't be written or loaded, Jolt starts
  without them and the start lines say why; `{:runtime :in-process}` is the
  way back. A plain nREPL server gets no stdin (`read-line` returns nil).
- Output streams as it prints with jolt-lang/nrepl v0.4.0 or later.

</details>

## Where the nREPL middleware comes from

Interrupt needs Jonquil to inject jolt-lang/nrepl's middleware. It uses,
in order:

1. A checkout at `$JONQUIL_JOLT_NREPL` or `~/dev/jolt-lang/nrepl` (keep it
   at v0.4.0 or later).
2. jolt-lang/nrepl v0.4.0 as a git dependency. The first start clones it
   into `~/.gitlibs` (about 3 seconds; needs `git` and a network).
3. If the fetch fails, a plain nREPL server, and the console says so. It
   has no `interrupt`, so Stop kills and restarts the child, and
   `read-line` has no stdin. The next start retries the fetch.

## `.nrepl-port`

Jonquil's Jolt child writes no `.nrepl-port`, and never touches one your
project has, so another editor or a JVM Clojure window can't mistake the
child for your server. Your own `jolt nrepl-server` writes the file as
usual. The child uses Jonquil's middleware list, so a `:nrepl/middleware`
in your `deps.edn` isn't loaded there.
