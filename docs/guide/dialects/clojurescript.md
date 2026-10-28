# ClojureScript (experimental)

Part of [Dialects and runtimes](index.md). Jonquil runs ClojureScript three ways: on node, in a browser tab, or attached to a shadow-cljs build you started.

## ClojureScript (experimental): running on node

A `.cljs` file outside a shadow-cljs project (see
[Choosing a dialect](index.md#choosing-a-dialect)) runs on node. Like jank, Jonquil
starts the runtime itself, on the window's first Run, so there's no build
to start and nothing to open in a browser. The status bar reads
`ClojureScript (node) (not running)` until then, and `(running)` after.
ClojureScript support is experimental, and the console says so once when
the window opens.

![A ClojureScript window on node after Run: the console starts ClojureScript, prints the program's line, then answers two REPL entries in the file's namespace, the second one node's version](../../images/cljs-node.png)

You need two tools on the login shell's `PATH`: the Clojure CLI
(`clojure`, or point `JONQUIL_CLOJURE` at it) and `node`. If `clojure` is
missing, Run says so and names `JONQUIL_CLOJURE`. Jonquil adds ClojureScript
1.12.42 to the command line with `-Sdeps`, but only when the project's
`deps.edn` doesn't already name `org.clojure/clojurescript`; a project that
does gets its own version. The first start on a machine can download the
Closure Compiler and ClojureScript, so Jonquil gives it 60 seconds before
it gives up. The child runs in the project directory when there is one, so
the project's `deps.edn` and paths apply.

Run evaluates every top-level form in the buffer, in order, and output
streams to the console as it happens. A JavaScript exception prints as one
error line and the session carries on, so the next form (and the next Run)
still runs in the same process. Input that ends inside a form, such as an
unterminated string or a trailing `#_`, `'` or `@`, is refused with an error
instead of being sent, because the process would wait for the rest of the
form until you pressed Stop.

Stop and Reset on Run both restart the process, because nothing in this
runtime can interrupt a running evaluation. Earlier definitions are gone
afterwards, and the console says so. Since a restart takes a few seconds,
Reset on Run starts unticked in a ClojureScript-on-node window, so your
definitions carry from one Run to the next; tick it when you want each Run
to start clean. Switching the window back to another dialect puts your
earlier choice back. Closing the window or quitting kills
the Clojure process and node with it. If Jonquil itself crashes, the
child's stdin ends, its JVM exits, and node goes down with it.

Two listeners make this work, and both are loopback-only and
unauthenticated. The ClojureScript process serves a prepl on `127.0.0.1`
at a port the OS picks, and the node process it drives listens on a second
socket. ClojureScript's stock node script (`cljs/repl/node_repl.js`)
listens on every network interface, so Jonquil's launcher rewrites that one
line at run time, in a copy it writes to a temporary folder and removes on
exit, and the socket then listens on `127.0.0.1` only, on a port node gets
from the OS and reports back, so no other program can take it first. If a ClojureScript
version changes that line, the launcher refuses to start rather than leave
the socket open, and the console says why. Neither listener checks who's
connecting, which is the same exposure as Jonquil's own nREPL children: while
the window is open, any user on this machine could send code to the process.

Completion offers the buffer's names and Clojure core's, and once
something ran, the publics of every namespace the session loaded, asked
from the compiler's analyzer. Names arrive spelled the way this buffer can
use them: `str/upper-case` when the ns form aliases `clojure.string` as
`str`, bare names for the current namespace and anything referred, and a
full `some.ns/name` once the prefix contains a `.` or `/`. The list
refreshes on the first completion after each evaluation, and today's
candidates keep their order: the analyzer's extras append (rows labelled
`core` for cljs.core names, `remote` for fully-qualified ones). Quick
documentation comes from `cljs.repl/doc` while the process is idle. F12
jumps to a definition in the same buffer only. `read-line` works the way
it does on Jolt: the status label switches to `Waiting for input`, the
next line you type at the prompt answers the read instead of being
evaluated, Cmd/Ctrl+D ends input for that one read (`read-line` answers
nil and a later read waits again), and Stop frees a waiting read by
restarting the process. Under the hood the launcher relays Jonquil's
lines into a pipe in its own temp folder and the session defines
`cljs.core/read-line` to read it, so plain `(read-line)` compiles with no
warning in any namespace. There is no inspector. Output the program
prints after an evaluation has answered (a
timer that fires later, say) still reaches the console, above the prompt,
including while quick docs are being fetched.

## ClojureScript (experimental): running in the browser

Node stays the default. To run your ClojureScript in a browser tab instead,
put `{:cljs {:target :browser}}` in the project's `.jonquil.edn`. It needs a
project, so a loose `.cljs` file keeps running on node. The status bar then
reads `ClojureScript (browser) (not running)` until the first Run, and
`(running)` after. In a project with a `shadow-cljs.edn` the `:target` is
ignored with a warning, since Jonquil attaches there (unless
`{:runtime :launch}` is set too). An unknown value, such as `:target :foo`,
warns and uses node. The tools are the same as for node, except that you
need a browser in place of `node`.

![A ClojureScript window in the browser after Run: the console starts ClojureScript, opens the page at a 127.0.0.1 URL, prints the program's line, then answers two REPL entries, the second one the page's own host](../../images/cljs-browser.png)

On the first Run, Jonquil starts ClojureScript and opens the page in your
default browser: `open` on macOS, `xdg-open` on Linux, or the command named
by `JONQUIL_OPEN_URL`, which gets the URL as its only argument. Then it waits
up to 60 seconds for the page to connect. If none does (the opener failed,
or you use another browser), the console tells you the URL and that you can
open it yourself. The process keeps running, so the next Run carries on as
soon as a page has connected. Evaluation, errors and output work as they do
on node, with your code running in the tab.

Each window gets one URL, `http://127.0.0.1:` with a port Jonquil picks the
first time and keeps, plus a `?t=` token naming the window, so a leftover
tab from an older session can't wander into a new one that reuses the
port: it gets a plain page saying the session is over and to close the
tab. (Retyping the address by hand without the token still works.) Stop
and Reset on Run restart ClojureScript, because
nothing can interrupt the page, and the tab reloads itself once the new
process is listening: the page pings the server every five seconds while
healthy (once a second for the first half minute after it stops answering)
and reloads when a different process answers, giving up after about an
hour without one. A tab that can't run its ping, frozen in an endless
loop or closed, still needs a reload or reopen by hand, as does one whose
server stayed gone past that hour. Reset on Run starts unticked, as it does on node. If something else took that port meanwhile, Jonquil
picks another and says so. A loop that never ends freezes the tab until you
reload it. An evaluation sent after you closed the tab fails within a couple
of seconds with an error that names the URL to reload, and works again once
the page is back. A tab closed while a long evaluation is still running
leaves that evaluation waiting until you press Stop, because the page can't
say it's leaving until its code finishes.

`println` output reaches Jonquil's console, including output printed after an
evaluation finished (a timer that fires later), as on node. So does
`js/console.log` (and `info`, `warn`, `error`, `debug`): it still logs in
the browser's devtools, and its arguments also print to Jonquil's console,
joined by spaces, with a non-string printed as `pr-str` would. It arrives
in batches a tenth of a second apart, at most 100,000 characters each (a
note counts what was dropped). Format strings such as `%s` aren't applied
on Jonquil's side, and in devtools each log's source link now points at
Jonquil's wrapper instead of the line that logged. When that gets in the
way of debugging through devtools, `:forward-console? false` in
`.jonquil.edn`'s `:cljs` map (`{:cljs {:target :browser :forward-console? false}}`)
turns the forwarding off: `console.log` then stays in the
browser with its real source links, and `println` still reaches Jonquil. As with node, there is no
stdin and no inspector, F12 stays in the buffer, and completion and quick
docs work the same way.

Two listeners make this work, and both are loopback-only and
unauthenticated: the prepl, and the HTTP server that serves the page and
carries the browser connection. ClojureScript's browser REPL server binds
every network interface by default and ignores `:host` for listening, so
Jonquil's launcher binds the port itself, to `127.0.0.1`, before ClojureScript
starts, and replaces the server's start function with one that uses that
socket. If a ClojureScript version changes that function's shape, the
launcher refuses to start. While the window is open, any user on this
machine could send code to the page's REPL, the same exposure as the node
runtime.

## ClojureScript (experimental): attaching to a shadow-cljs build

ClojureScript support is experimental. It attaches the way ClojureDart and
JVM Clojure do: Jonquil never starts shadow-cljs, never starts a JS runtime,
and never signals either one. You start the watch yourself, and Jonquil
connects to the nREPL server it already runs.

Start `npx shadow-cljs watch <build>` in the project, then load the build in
a JS runtime: open the app in a browser, or run the node script the build
writes. Shadow-cljs writes its port to `.shadow-cljs/nrepl.port`, which is
how Jonquil finds the server. Open a `.cljs` file under the project and
press Run. Jonquil connects on the first Run, or on any evaluation while it
holds no live connection, prints "; Connecting to the shadow-cljs nREPL
server on port N..." while it does, and then selects the build with
`nrepl-select`, which moves the session from Clojure to ClojureScript.
Typing `:cljs/quit` or `:repl/quit` at the prompt leaves the ClojureScript
session; the next entry selects the build again.

![A ClojureScript window attached to a shadow-cljs build after Run: the console connects to the shadow-cljs nREPL server, reports the three forms it ran, then answers two REPL entries in node, the second one node's version](../../images/cljs-shadow.png)

A `.cljs` file selects this dialect by its extension and by the
`shadow-cljs.edn` project marker, and `{:dialect :cljs}` in `.jonquil.edn`
forces it (see [Choosing a dialect](index.md#choosing-a-dialect), which also covers
the one behavior change that came with this dialect).

Jonquil picks the build in this order:

1. `{:cljs {:shadow-build :app}}` in the project's `.jonquil.edn`.
2. The only build in `shadow-cljs.edn`.

```clojure
;; .jonquil.edn
{:cljs {:shadow-build :app}}
```

When `.jonquil.edn` names none and `shadow-cljs.edn` has several builds, or
none, the Run ends with a notice that names them: "; Several shadow-cljs
builds: :app :test. Pick one with {:cljs {:shadow-build :app}} in
.jonquil.edn." `shadow-cljs.edn` is read again on every connect, so an edit
there takes effect on the next one, while a `.jonquil.edn` edit takes effect
when the file is reopened.

<details>
<summary><strong>What the console says when it can't connect</strong></summary>

If nothing's listening yet, the console says so: "; No shadow-cljs server
here. Start one in `<dir>` with npx shadow-cljs watch app." A stale
`.shadow-cljs/nrepl.port` naming a port nothing answers gets
"; .shadow-cljs/nrepl.port says port N, but nothing answers there. The
server may have stopped.", and a port that answers but isn't a shadow-cljs
nREPL server gets "; Port N isn't a shadow-cljs nREPL server Jonquil can
use." A file with no project around it, when you've forced
`{:runtime :attach}`, gets "; This file isn't in a project, so there's no
shadow-cljs.edn to read. Save it inside the project that has
shadow-cljs.edn." Jonquil only ever reads
`.shadow-cljs/nrepl.port`; it never writes to the file or deletes it.

</details>

When the server is up but no JS runtime has loaded the build, Run says so:
"; The build isn't loaded in any JS runtime. Open the app (or run the node
script) and Run again."

Run sends the whole buffer as one evaluation, so every form runs, and the
console ends with one line, "; Ran <ns> (N forms)". An error in one form
doesn't stop the rest of the file: the errors stream to the console as they
happen, and the summary then reads "; Ran <ns> (N forms, M errors; an error
doesn't stop the rest of the file)". Run Selection and Run Form
(Cmd/Ctrl+E and Cmd/Ctrl+Enter) evaluate in the buffer's own namespace.

Completion and quick documentation come from the server's cider ops when it
has them (shadow-cljs bundles cider-nrepl), and from Jonquil's offline
tables when it doesn't. Jump to definition stays inside the buffer.

Stop detaches rather than interrupting anything. Shadow-cljs's interrupt
stops nothing, because the work is running in the JS runtime, so Jonquil
closes the connection and prints "; Detached from the shadow-cljs server.
The watch and the app weren't touched." The runtime keeps running whatever
it already had, and the next evaluation reconnects and selects the build
again. Reset on Run is unticked and greyed out, as for the other attach
dialects.

Two things behave differently from what a JVM habit suggests. First,
`js/console.log` output goes to the JS runtime's own console (the browser's
devtools, or the node process's stdout), not to Jonquil's; `println` does
reach Jonquil. Second, requiring a namespace that the watched build doesn't
include compiles but never loads into the runtime, so its first use throws a
ReferenceError. Keep the requires of a file you Run within the build.
Namespaces the buffer itself defines are fine.
