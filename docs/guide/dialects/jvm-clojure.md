# JVM Clojure: attaching to your own nREPL server

Part of [Dialects and runtimes](index.md).

JVM Clojure attaches the same way ClojureDart does: Jonquil never starts
anything, only connects to a server you already have running. Start one
yourself, most simply `clojure -M:serve` from
[clojure-cli.repl](https://github.com/clojure/clojure-cli.repl)'s own
`:serve` alias, or any nREPL server that writes its port to `.nrepl-port`
in the project directory. Then add `{:dialect :clj}` to that project's
`.jonquil.edn` and open a `.clj` or `.cljc` file under it; nothing else
picks this dialect (see [Choosing a dialect](index.md#choosing-a-dialect)).

If the project doesn't have a server alias yet, this is the one Jonquil's
own `bb test:clj` uses. It needs Java 17 or later and the `clojure` CLI:

```clojure
;; deps.edn
{:paths ["src"]
 :aliases {:serve {:extra-deps {io.github.clojure/clojure-cli.repl-server {:mvn/version "0.1.0"}}
                   :main-opts ["-m" "clojure-cli.repl.server"]}}}
```

```clojure
;; .jonquil.edn
{:dialect :clj}
```

Run `clojure -M:serve` in the project directory and wait for
`nREPL server listening on port N`, then open a source file in Jonquil
and press Run. Stopping the server with Ctrl+C removes its
`.nrepl-port`, and the status bar drops back to `(not running)`; start it
again and the next Run reconnects.

![A JVM Clojure window after Run: the console connects to the server, loads the file, then answers two REPL entries in its namespace](../../images/clj-attach.png)

<details>
<summary><strong>What the console says when it can't connect</strong></summary>

Jonquil connects the first time you Run, or on any evaluation while it
holds no live connection, printing "; Connecting to the nREPL server on
port N..." while it does. If nothing's listening yet, the console says
so instead: "; No nREPL server here. Start one in `<dir>` with
`clojure -M:serve` (clojure-cli.repl), or any nREPL server that writes
`.nrepl-port`." A stale `.nrepl-port` naming a port nothing answers gets
"; .nrepl-port says port N, but nothing answers there. The server may
have stopped.", and a port that answers but isn't nREPL at all gets
"; Port N isn't an nREPL server Jonquil can use." Jonquil only ever
reads `.nrepl-port`: since it never started the server that owns the
file, it never writes to it or deletes it either.

</details>

## Auto-require

A project's usual namespaces can load on their own, so the first line you
type at the prompt can call into them without a `require` first. List them
in the project's `.jonquil.edn`:

```clojure
;; .jonquil.edn
{:dialect :clj
 :auto-require [my.app.core my.app.dev]}
```

Each time Jonquil connects, it prints `; auto-require: my.app.core
my.app.dev` and sends one `(require 'my.app.core 'my.app.dev)` on its
session before whatever you asked to run. That means the first Run or REPL
entry, and again after a reconnect, because a restarted server has
forgotten everything. It isn't sent twice on the same connection.

A Jolt project takes the same key, without the `:dialect` line (any
project Jonquil runs under Jolt, which a `deps.edn` makes the default). Out of
process, the list is required in the Jolt child before its first
evaluation, and again whenever that child restarts. In process, it's
required once per window, before the first evaluation and after the
project's `:deps` are loaded, so a dependency's namespace can be in it.
Reset on Run keeps those namespaces, since they're libraries rather than
something your program defined.

<details>
<summary><strong>When the require fails, and the list's format</strong></summary>

If the require fails, a namespace that isn't on the classpath for example,
the server's own error shows in the console, followed by "; auto-require
failed (the error is above), so carrying on without it." and when it will
run again: on the next connect for JVM Clojure, when the Jolt runtime
restarts out of process, and in a new window in process. Your own Run or
REPL line still goes out after it.
Stop during a slow require ends that evaluation as usual, and your request
isn't sent; for Jolt, the next evaluation then tries the list again.

The list has to be a vector of namespace symbols. Anything else is
ignored, with a line like `; project: .jonquil.edn :auto-require should be
a vector of namespace symbols, like [my.app.core], not my.app.core, so it
is ignored`. Jonquil reads `.jonquil.edn` when a window chooses its
dialect, so an edit to the list takes effect in a new window.

</details>

## Run, Stop, stdin and docs

Run sends the whole Definitions pane as a `load-file`, without naming a
namespace itself: the file's own `ns` form is what decides where the
server evaluates it, the same as any nREPL client's load-file. A
successful Run then moves the prompt to that namespace afterward. A REPL
entry typed at the prompt evaluates in the prompt's own current
namespace, and the prompt moves again if the reply itself names a
different one, after an `in-ns` call, say.

Cmd/Ctrl+E and Cmd/Ctrl+Enter (Run Form) both evaluate in the buffer's
own namespace instead, sent explicitly alongside the code rather than by
switching the session there first: Jonquil never sends a buffer's `ns`
form on its own. When the server hasn't loaded that namespace yet,
typically because the file hasn't been Run, the console says so instead
of guessing: "; demo.core isn't loaded in the server yet. Run the file
first." Running the file first, so the server knows the
namespace, clears it.

Stop sends the server's own `interrupt` and waits up to a second for the
evaluation to end. When it does, the result reads
`; interrupted`, the same as every other runtime. When it doesn't,
Jonquil falls back the way ClojureDart's Stop always works: "; The
server didn't stop the evaluation, so Jonquil detached. It may still be
running." The next evaluation reconnects with a fresh session. Switching
to a different file, or closing the window, while an evaluation is
running sends the same interrupt first, best effort.

`read-line` and other stdin reads work the way they do everywhere else
in Jonquil: type at the prompt while the status bar reads "Waiting for
input", and the line goes straight to the program. Cmd/Ctrl+D at that
prompt sends end-of-input when the server runs nREPL 1.8.0 or later, so
`read-line` returns nil. It sends only while a read is waiting, because
1.8.0 queues an early EOF and would end your next read with it. The EOF
doesn't stick: the next `read-line` waits for a line again.

<details>
<summary><strong>End-of-input on an older nREPL</strong></summary>

On an older server nothing is sent and the read keeps waiting for an
actual line. The console says why, once per connection, the first time
you press Cmd/Ctrl+D. nREPL 1.7.0 fails an empty `stdin` op rather than
answering EOF, and the `clojure-cli.repl-server` 0.1.0 release brings in
1.7.0. To get 1.8.0 from it, add nREPL to the `:serve` alias:

```clojure
:serve {:extra-deps {io.github.clojure/clojure-cli.repl-server {:mvn/version "0.1.0"}
                     nrepl/nrepl {:mvn/version "1.8.0"}}
        :main-opts ["-m" "clojure-cli.repl.server"]}
```

</details>

Completion and quick documentation both ask the server, not just this
buffer. Ctrl+Space merges the server's own candidates in with the
offline ones, and Ctrl+Q or Shift+F1 shows a symbol's real docstring and
arglists, wherever it's defined. A definition outside the
buffer ends with a `Defined in` line naming the file and line number,
and F12 opens it in the source panel (see Jump to definition). The line
reads `Defined in clojure-1.12.5.jar!/clojure/core.clj:2748` for something
inside a jar, and a plain path and line for project code.
Before the buffer's own namespace has been loaded, quick doc and
completion still answer for names the prompt's own namespace already
knows, `clojure.core` functions before the file's first Run, say;
anything only the buffer's (not yet loaded) namespace would resolve
says so instead of guessing: "No docs: demo.core isn't loaded in the
server yet. Run the file first."

![Quick doc on clojure.core's map in a JVM Clojure window, ending with a Defined in line inside the Clojure jar](../../images/clj-quick-doc.png)

<details>
<summary><strong>Full documentation from ClojureDocs</strong></summary>

Press Ctrl+Q or Shift+F1 a second time on the same popover, and Jonquil
asks the server for full documentation instead of the short quick-doc
text: clojure-cli.repl's own `doc` op, which returns ClojureDocs' full
entry, examples included, for a symbol that has one. The status bar
reads "Loading full docs for X..." while it waits, off the GTK thread,
so typing elsewhere isn't blocked. The very first full-doc request
downloads ClojureDocs' whole export, a few megabytes, into the server's
own config directory, which can take several seconds; every later
request, in that session or a later one, answers in milliseconds once
the server already has it. Full docs are only ever fetched for a symbol
quick doc already found, so pressing it twice on a name Jonquil couldn't
resolve tells you so again in the status bar, rather than asking the
server for a doc it already knows nothing useful about.

![Full documentation for map after a second quick-doc press, with ClojureDocs' examples](../../images/clj-full-doc.png)

</details>
