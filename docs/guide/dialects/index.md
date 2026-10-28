# Dialects and runtimes

Jonquil evaluates through a dialect profile: what a language's files look
like, how they're painted, and which evaluator runs them. Six profiles
exist today: Jolt, ClojureDart (experimental), JVM Clojure, ClojureScript
(experimental), jank (experimental) and nREPL, which attaches to any nREPL
server you started. The way Jonquil picks a profile, and the evaluator
protocol under it, are general enough to take more. The status
bar's dialect label reads `Jolt` most of the time, or `Jolt (nREPL)` when
this window's Jolt is running out of process rather than inside Jonquil
itself. For ClojureDart it reads `ClojureDart (attached)` while Jonquil
holds a live connection to the app, and `ClojureDart (not running)` the
rest of the time. JVM Clojure reads the same way, `Clojure (JVM) (attached)`
or `Clojure (JVM) (not running)`, and ClojureScript reads
`ClojureScript (shadow-cljs) (attached)` or `ClojureScript (shadow-cljs)
(not running)`, or `ClojureScript (node) (running)` and `ClojureScript (node)
(not running)` when Jonquil runs its own ClojureScript on node (or
`ClojureScript (browser)` with the same two endings, for the browser). For jank it
reads `jank (running)` while Jonquil's own jank process is
up, and `jank (not running)` until the first evaluation starts one. The
nREPL dialect reads `nREPL (attached)` or `nREPL (not running)`.

## Choosing a dialect

Jonquil picks a dialect and a runtime for each window, in this order:

1. `.jonquil.edn` in the project, if it names one.
2. A single profile marker in the project (Jolt's is `deps.edn`).
3. The open file's extension.
4. The `:dialect` saved in `settings.edn`.
5. Jolt, when none of the above decides.

The project is the nearest directory, at or above the open file, holding
`.jonquil.edn` or a profile marker. A loose file with nothing like that
above it, or an unsaved window, has no project, so only the extension and
the settings default are left to decide.

<details>
<summary><strong>How each dialect gets picked: ClojureDart, JVM Clojure, ClojureScript, jank and nREPL</strong></summary>

ClojureDart's own marker is `pubspec.yaml`. A ClojureDart app commonly
carries a `deps.edn` too, for `bb` tasks, say, so both markers can be
present in the same project at once: rule 2 only decides when exactly one
marker hits, so the file's extension breaks the tie instead. `.cljd`
opens as ClojureDart, `.clj` and `.cljc` stay Jolt's, and a file neither
extension claims, `bb.edn` say, falls back to Jolt.

JVM Clojure has no marker of its own: its `.clj` and `.cljc` extensions,
and the `deps.edn` a JVM project usually has too, both already belong to
Jolt. So only rule 1 ever picks it inside a project, by naming
`{:dialect :clj}` in that project's own `.jonquil.edn`. Rule 4's settings
default can still choose JVM Clojure, but only for a loose or unsaved
file with no project at all; inside a project, a marker-less default like
this one is skipped, and selection falls through to whichever rule comes
next.

ClojureScript's marker is `shadow-cljs.edn`, and a `.cljs` file selects the
dialect by its extension as well. Which runtime it gets depends on the
project. A project with a `shadow-cljs.edn` attaches to your shadow-cljs
server, as it always did. A `.cljs` file in any other project, or a loose
`.cljs` file with no project at all, runs "ClojureScript (node)": Jonquil
starts ClojureScript on node itself. `{:dialect :cljs}` in a
project's `.jonquil.edn` forces the dialect for files with other
extensions, and `{:runtime :launch}` or `{:runtime :attach}` there forces
the runtime, say to launch node inside a shadow-cljs project. A project
without `shadow-cljs.edn` can run the launched ClojureScript in a browser
instead of node with `{:cljs {:target :browser}}`; see
[running in the browser](clojurescript.md#clojurescript-experimental-running-in-the-browser).

jank has no project marker either. A `.jank` file opens as jank by its
extension, and a project can name it with `{:dialect :jank}` in its
`.jonquil.edn`, the same way as JVM Clojure.

The nREPL dialect has neither a marker nor an extension, so only
`{:dialect :nrepl}` in a project's `.jonquil.edn` picks it. A babashka
project with a `bb.edn`, or a Basilisp one, stays Jolt's until you say so, since the dialect
needs a server you start yourself (see
[Any nREPL server](other-nrepl.md)).

</details>

## `.jonquil.edn`

A `.jonquil.edn` at the project root pins the dialect and, for Jolt,
chooses the runtime:

```clojure
{:dialect :jolt :runtime :in-process}
```

Both keys default independently, so either can be left out. A file that
isn't valid EDN, or that doesn't hold a map, never blocks Jonquil from
opening anything: the console prints a line like `; project: .jonquil.edn
isn't valid EDN, so it is ignored`, and selection falls through to the
next rule as though the file weren't there at all.

`{:dialect :jank}` pins jank for a project whose files aren't all `.jank`
(see [jank](jank.md)).

`{:dialect :nrepl}` attaches to any nREPL server, and `:nrepl` says where
it is: `{:port 1667}`, `{:host "box" :port 1667}`, or `{:port-file
"path/to/port"}` (see
[Any nREPL server](other-nrepl.md)).

`{:dialect :clj}` is the only way to reach JVM Clojure: a project with
just that one line in its `.jonquil.edn` attaches to its own nREPL server
instead of running under Jolt (see [JVM Clojure](jvm-clojure.md)). A JVM project can also list
namespaces under `:auto-require` for Jonquil to load as it connects (see
[Auto-require](jvm-clojure.md#auto-require)), and a Jolt project can too. Any other
dialect ignores that key, and the console says so.

## Running Jolt out of process

In a project with a `deps.edn`, Jolt runs out of process: Jonquil starts a
separate Jolt nREPL server in the project directory and talks to it over
a socket instead of evaluating inline. A program that calls `System/exit`,
calls `shutdown-agents`, redefines something in `clojure.core` or
otherwise takes Jolt down with it takes down that child, not the window
running it, and the project's own `deps.edn` is the child's classpath.
`.jonquil.edn` `{:runtime :in-process}` keeps a project inside Jonquil's
process instead. Its `deps.edn` is resolved there the way the `jolt`
command resolves it, `:paths` (`src` when it names none) and the
libraries under `:deps` alike, so a file can require another of the
project's files or a dependency, and F12 opens either. A git or Maven
dependency is fetched the first time a Run or REPL entry needs it: the
console says "Resolving deps.edn..." and the evaluation shows as running,
and Stop stops it. If it can't be fetched, the Run goes ahead on `:paths`
alone, the console says why, and a Run half a minute later tries again.
Jonquil resolves again after an edit to the project's `deps.edn`, but not
after one to a `:local/root` dependency's own; touch the project's file
for that. Jonquil and your
program share that process, so a library Jonquil has already loaded
keeps Jonquil's copy. `{:runtime :nrepl}` asks for a child in a folder
without a `deps.edn`. A file outside any project folder has nowhere to
start a child, so it always runs in process. An untitled tab follows the
window's folder: in a window opened on a `deps.edn` project it runs out of
process with the rest of that project, and in a window with no folder it
runs in process.

<details>
<summary><strong>Start lines, Stop, switching projects, and stdin and the inspector</strong></summary>

At start, the console reports the child's pid, its port, and which
project directory it's running in, then lists what this particular
runtime can't do (three of those are below). Stop still interrupts a
running evaluation first; if the child doesn't answer within about a
second, Stop kills and restarts it instead, and says so, rather than
leaving you to notice your definitions vanished for no visible reason.
Reset on Run works the same way it does in-process, clearing earlier Run
and REPL definitions before evaluating fresh, just by sending a reset form
over the wire instead of removing namespaces locally.

Opening a file from a different project switches the whole session: the
old evaluator is interrupted and stopped off to the side, a new one
starts for the new project, and the console says something like
`Switched to Jolt (nREPL). The previous session is gone.` The same switch
happens if the new file's project asks for a different runtime, or none
at all.

stdin, the inspector, and jump to definition into the project's other
files and its jar dependencies all work, through Jonquil's own nREPL
middleware: Jonquil
writes it under `~/.cache/jonquil/nrepl-middleware` (or
`$XDG_CACHE_HOME/jonquil/nrepl-middleware`) and the child loads it from
there. A plain nREPL server, the fallback when jolt-lang/nrepl couldn't be
fetched, gets the inspector and jump to definition but not stdin, since it runs each evaluation
on the thread that would have to read your input; `read-line` there
returns nil and the console says so. If that folder can't be written, or
the child can't load what's in it, Jolt starts without either and the
start lines say why; `{:runtime :in-process}` is the way back.
Output streams as the program prints, with jolt-lang/nrepl v0.4.0 or
later; an older checkout sends it all at once when the evaluation
finishes, so a program that prints once a second for ten seconds and
then shows everything in one burst is the sign to update the checkout.

</details>

## Where the nREPL middleware comes from

Interrupt needs Jonquil to inject jolt-lang/nrepl's middleware when it
starts the child. A checkout comes first, found at
`$JONQUIL_JOLT_NREPL`, or `~/dev/jolt-lang/nrepl` when that variable isn't
set; keep it at v0.4.0 or later so output streams as it prints. With no
checkout, Jonquil asks Jolt for jolt-lang/nrepl v0.4.0 as a git
dependency. The first start clones it into `~/.gitlibs`, which took about
3 seconds when we measured it, and needs `git` on `PATH` and a network connection; later
starts take about a third of a second, the same as a checkout. If that
fetch fails, Jonquil starts a plain nREPL server instead and says so in
the console. A plain server has no `interrupt`, so Stop falls back to
killing and restarting the child, even on a program that would otherwise
have interrupted cleanly. Quick doc, jump to definition and the inspector
still work there, through Jonquil's own middleware, but `read-line` has no
stdin. The next
start tries the fetch again.

## `.nrepl-port`

Jonquil's Jolt child writes no `.nrepl-port`. Jonquil picks the child's
port itself, so nothing needs the file, and a `.nrepl-port` your project
already has (from your own server, say) is never touched. It also means
another nREPL-aware editor, or a JVM Clojure window on the same project,
can't mistake Jonquil's child for your project's server. Running your own
`jolt nrepl-server` beside it works as usual: that one writes the file.
The child uses Jonquil's own middleware list, so a `:nrepl/middleware`
in your `deps.edn` isn't loaded there; it wasn't before either, since
Jonquil's list replaced it.
