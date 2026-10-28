# ClojureDart (experimental): attaching to a running app

Part of [Dialects and runtimes](index.md).

ClojureDart support is experimental. Where the out-of-process Jolt
runtime starts and owns its own child, this dialect works the other way
round: Jonquil attaches to a ClojureDart app you already have running,
the way CIDER attaches to a live Clojure process. Connect, load the
file, then evaluate forms one at a time from a `(comment ...)` block.
Jonquil never starts the app, stops it, or sends it any signal at all.

Attaching needs the app running under `bb dev:macos` (or
`clj -M:cljd flutter`), on a ClojureDart fork that adds an nREPL
server. That fork writes its port to `NREPL.lock` in the app
directory, which is how Jonquil finds it; upstream ClojureDart
carries no nREPL server of its own, so there's nothing there for
Jonquil to attach to. The fork isn't published yet.

<details>
<summary><strong>Connecting, Run, Stop, Reset on Run and docs</strong></summary>

Jonquil connects the first time you Run, or on any evaluation while it
holds no live connection, and prints "; Connecting to the app on port
N..." while it does. A fresh session's first answer takes a few seconds
and brings the ClojureDart banner along with it. If nothing's running
yet, the console says so instead: "; ClojureDart app isn't running
here. Start it with `bb dev:macos` (or `clj -M:cljd flutter`)." A port
that refuses the connection answers "; NREPL.lock says port N, but
nothing answers there. The app may have quit; restart it.", and a port
that answers but isn't a ClojureDart server at all gets "; Port N isn't
a ClojureDart nREPL server." Jonquil only ever reads `NREPL.lock`: it
never writes to the file or deletes it.

Run loads the buffer's own text, unsaved edits and all, the way
CIDER's C-c C-k loads a file, and leaves the session sitting in the
file's namespace afterward. Rather than a value per form, it prints one
quiet line when it's done, "; Loaded <ns> (<n> forms)", or, if a form
fails, "Load stopped at form <n> of <ns>" with the app's own error in
place of it. An untitled buffer has no path for the app's error
messages or its watcher to follow, so Run refuses first: "Save the file
first: the app's error messages and its watcher both go by the file's
path."

Cmd/Ctrl+E and Cmd/Ctrl+Enter (Run Form) both run in the buffer's own
namespace. Since the app's nREPL server has no `in-ns`, Jonquil
evaluates the buffer's `ns` form first whenever the session is sitting
somewhere else, then sends your code. A REPL entry typed at the console
prompt runs wherever the session already happens to be.

Stop detaches rather than interrupting anything: it closes the
connection at once, even one that's still mid-handshake, and prints
"; Detached from the app. The app wasn't touched; a change already sent
may still land." The next evaluation reconnects with a fresh session.
An evaluation that's still waiting past 30 seconds gets one more line,
"; Still waiting on the app...", and after that Jonquil just keeps
waiting: there's no timeout of its own.

Reset on Run is unticked and greyed out for this dialect, with a tooltip
that says why: "Hot reload can't unload a namespace, so every Run adds to
the running app." Every Run builds on what's already there instead of
clearing it first. Your own tick isn't lost: open a Jolt file in the same
window and the box comes back the way you left it.

Completion stays offline here: candidates come from the buffer's own
definitions, the aliases its `ns` form declares, and ClojureDart's own
special forms and built-ins, never anything the running app itself
knows about. Quick documentation does ask the app, and answers one of
four ways: the symbol's own docs when the app has them, "Not attached;
Run first" with no live connection, "No docs: the app didn't answer in
time" once its own budget runs out, or, only on a fork build that
predates symbol lookup, "No docs for ClojureDart symbols yet (the app's
ClojureDart fork doesn't answer lookup)".

</details>
