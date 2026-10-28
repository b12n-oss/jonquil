# Known limits

A few rough edges are already known and parked for a later phase, rather
than being surprises you'd otherwise have to rediscover:

<details>
<summary><strong>The window and editing</strong> (3)</summary>

- Return during an IME composition commits the syllable and inserts a
  plain newline rather than acting on it; a second Return does the rest.
  See the Unicode section above.
- Closing the window, or Cmd/Ctrl+W, while a program runs quits Jonquil
  at once, which ends the program too. It isn't a Stop: nothing is printed,
  and the program gets no chance to finish what it was doing.
- A keystroke in a very large Definitions pane can stall briefly. At
  20,000 lines the worst stall measured about 69 ms, and the colours catch
  up within about 0.7 s. At 2,000 lines it's about 10 ms. The benchmark is
  in [Development](development.md#benchmark).

</details>

<details>
<summary><strong>Jolt, in or out of process</strong> (6)</summary>

- An unresolved symbol evaluates to an `Unbound` var rather than an error,
  when Jonquil is running Jolt out of process.
- Exiting can take a while when an out-of-process Jolt child is still
  starting: stop waits up to about 16 seconds for each start in flight.
  Ordinary Quit, Cmd/Ctrl+W, closing the window, and SIGTERM, SIGHUP or
  SIGINT stop the child first; after a crash or a SIGKILL of Jonquil the
  child notices its parent is gone and exits on its own within a few
  seconds.
- A wedged out-of-process child no longer holds a key: completion and
  quick doc go on after about 25 ms with Jonquil's own offline answer, and
  give up on the child's after 3 seconds.
- Stop against an unresponsive out-of-process child takes about 2.2
  seconds: a second waiting for it to answer the interrupt, a second more
  for it to exit once killed, and a little overhead either side.
- A project kept in process (`{:runtime :in-process}`) shares Jonquil's
  process, so a library Jonquil itself has already loaded (`clojure.data.json`,
  say) stays at Jonquil's version whatever `:deps` asks for. Run it out of
  process when the version matters.
- With Jolt v0.8.23 and earlier, two projects holding a byte-for-byte
  identical namespace file can make F12 open the other project's copy:
  Jolt caches compiled code by its text, and a cached file keeps the place
  it was first loaded from (jolt-lang/jolt#1404). Jolt's main branch
  fixed it on 2026-10-19, after v0.8.23; no release has the fix yet.

</details>

<details>
<summary><strong>ClojureDart</strong> (9)</summary>

- Stop against a ClojureDart app detaches instead of interrupting: the
  app keeps whatever it already has.
- No stdin, for the ClojureDart dialect.
- ClojureDart needs the fork that adds an nREPL server; upstream
  ClojureDart carries none to attach to.
- `println` output arrives for ClojureDart too, but is lost once the form
  that printed it throws.
- Jonquil never re-sends what you ran against ClojureDart: only
  its own `(+ 1 2)` wake-up probe repeats, and if the app's REPL still
  doesn't answer it, Jonquil says so instead of sending your code.
- No server-side completion for ClojureDart: only this buffer's own
  symbols and ClojureDart's own forms.
- Jump to definition for ClojureDart stays inside the buffer: the fork's
  `lookup` carries no file or line to jump to.
- Reset on Run isn't available for ClojureDart: hot reload can't unload a
  namespace, so every Run adds to the app instead of replacing it.
- A ClojureDart load stops at the first form the app's REPL compiler
  rejects, even though the app's own watcher still compiles the saved
  file.

</details>

<details>
<summary><strong>JVM Clojure</strong> (5)</summary>

- JVM Clojure attaches to a server you started, and never starts or stops
  the JVM itself.
- Reset on Run isn't available for JVM Clojure either: it's your own JVM,
  so Jonquil doesn't unload namespaces in it.
- Stdin closes only on nREPL 1.8.0 or later: on older servers a read
  waits for a line.
- Output JVM Clojure prints from a thread other than the one an
  evaluation ran on, after that evaluation finishes, isn't shown.
- Stop can't end a JVM Clojure loop that never checks for interruption,
  such as `(loop [] (recur))`, on nREPL 1.7 with JDK 20 or later. The
  server answers that it was interrupted and the console prints
  `; interrupted`, but the loop keeps running in your JVM. A sleep,
  blocking I/O and `read-line` do stop. Jonquil never kills the JVM, so
  the way out is to restart the server yourself.

</details>

<details>
<summary><strong>ClojureScript on node</strong> (5)</summary>

- Jonquil starts and stops its own process, which needs the Clojure CLI
  and `node` on the login shell's `PATH`. Its prepl and node socket are
  unauthenticated loopback listeners.
- Stop and Reset on Run restart the process, so earlier definitions are
  gone afterwards.
- `read-line` waits for a line typed at the console prompt; Cmd/Ctrl+D
  ends input for one read. No inspector.
- F12 stays in the buffer.
- Completion offers the buffer and Clojure core names and, once something
  ran, every loaded namespace's publics, spelled by the buffer's ns form;
  quick docs come from `cljs.repl/doc` while the process is idle.

</details>

<details>
<summary><strong>ClojureScript in the browser</strong> (6)</summary>

- Jonquil starts and stops its own process, which needs the Clojure CLI on
  the login shell's `PATH`, and your code runs in a browser tab it opens
  through `open`, `xdg-open` or `JONQUIL_OPEN_URL`. Its prepl and page
  server are unauthenticated loopback listeners.
- Stop and Reset on Run restart ClojureScript, so earlier definitions are
  gone; the tab reloads itself once the new process is listening (a frozen
  or closed tab still needs a hand). A tab from an older session that
  finds its port reused by a new one is turned away with a "close this
  tab" page instead of joining it.
- A loop that never ends freezes the tab until you reload it, and an
  evaluation sent after the tab closed fails until you reload it. One
  still running when the tab closed waits until Stop.
- `println` and `js/console.log` output reach Jonquil's console, even after
  an evaluation finished; `console.log` still logs in the browser too
  (`:forward-console? false` in the `:cljs` map keeps it there alone, with
  devtools' real source links).
- No stdin and no inspector; F12 stays in the buffer.
- Completion offers the buffer and Clojure core names and, once something
  ran, every loaded namespace's publics, spelled by the buffer's ns form;
  quick docs come from `cljs.repl/doc` while the process is idle.

</details>

<details>
<summary><strong>ClojureScript with shadow-cljs</strong> (7)</summary>

- ClojureScript attaches to a shadow-cljs server you started, and never
  starts or stops the watch or the JS runtime.
- Stop against ClojureScript detaches instead of interrupting: the
  shadow-cljs interrupt stops nothing, since the work is in the JS runtime,
  and the runtime keeps running what it has.
- No stdin, for the ClojureScript dialect.
- Reset on Run isn't available for ClojureScript attached to shadow-cljs.
- `js/console.log` output goes to the JS runtime's own console, not
  Jonquil's. `println` does reach Jonquil.
- A namespace the watched build doesn't include can be required and
  compiles, but never loads into the runtime, so its first use throws a
  ReferenceError. Keep a Run'd file's requires within the build; namespaces
  the buffer itself defines are fine.
- With no JS runtime connected, Run says the build isn't loaded in any JS
  runtime.

</details>

<details>
<summary><strong>The nREPL dialect (babashka, Basilisp, other servers)</strong> (1)</summary>

- The nREPL dialect does what the server offers: with no `interrupt` op,
  Stop disconnects and leaves the evaluation running in the server; with
  no `stdin` op, a read waits until Stop on babashka, and reads the server's
  own terminal on Basilisp. Reset on Run isn't available, and
  jump to definition follows only a file that exists on this machine.

</details>

<details>
<summary><strong>jank</strong> (4)</summary>

- Stop restarts jank, so earlier definitions are gone afterwards.
- Programs can't read input, jump to definition works only inside the
  buffer, and the inspector isn't available.
- Output is held until the evaluation ends, so a `println` in a long loop
  shows nothing while it runs, and a Stopped evaluation never shows its
  output.
- Jonquil can't attach to a jank you started yourself, and some loops
  don't compile yet (a bare `recur`, or one after a `when`); see jank (experimental),
  above.

</details>

<details>
<summary><strong>The inspector</strong> (5)</summary>

- The inspector panel never refreshes itself: a later evaluation leaves
  it showing whatever it had until you open it again.
- A collection in the inspector panel shows at most 1000 entries.
- On JVM Clojure, the server keeps one inspector view per connection. If
  another client inspects on the same server, it replaces Jonquil's own
  view, and Jonquil's next move in the panel continues from whatever
  that other client left.
- Resizing the inspector panel doesn't reflow the rows already on
  screen; the new width only takes effect the next time you open it.
- No inspector for ClojureDart or ClojureScript, in this version.

</details>

<details>
<summary><strong>MCP</strong> (2)</summary>

- An MCP eval, run or run-selection against a window that is already
  evaluating answers busy instead of queueing. `jonquil_run_selection`
  still moves the visible selection first, by design.
- A listening window (`:mcp-listen?`) serves one MCP client at a time:
  a second attach waits until the first detaches.

</details>
