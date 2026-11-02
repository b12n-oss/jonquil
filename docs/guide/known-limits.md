# Known limits

Known rough edges, parked for later:

<details>
<summary><strong>The window and editing</strong> (6)</summary>

- Text can't contain a NUL byte: a file holding one is refused at open,
  and an agent's or plugin's replacement text holding one is refused with
  an error, rather than either being silently truncated.
- Return during an IME composition commits the syllable and inserts a plain
  newline; a second Return does the rest.
- Closing the window or Cmd/Ctrl+W while a program runs quits at once and
  ends the program. It isn't a Stop.
- A keystroke in a very large Definitions pane can stall briefly (see
  [Development](development.md#benchmark)).
- Parinfer leaves an edit alone once it reaches past 300 lines, one long
  form or a long paste alike; the status bar says "over 300 lines here,
  not adjusted". With Parinfer on, a keystroke in a 4,500-line file costs
  about 4 ms while typing in one form, about 13 ms for the first
  keystroke after a reload, an agent edit or an undo, and about 5 ms
  after a redo (each measured 2026-10-29). Format All in the same file
  adds a Paren pass over every form, about 0.4 s on top of the format's
  own 1.5 s (measured 2026-10-30).
- Parinfer normalises what it touches: in an edited form, tabs become two
  spaces and every line ending follows the form's first.

</details>

<details>
<summary><strong>Jolt, in or out of process</strong> (6)</summary>

- Out of process, an unresolved symbol evaluates to an `Unbound` var, not an
  error.
- Exiting while an out-of-process child is still starting can take up to
  about 16 s per start.
- A wedged child doesn't block completion or quick doc: the offline answer
  shows after about 25 ms, and Jonquil gives up on the child's after 3 s.
- Stop on an unresponsive child takes about 2 s.
- A project kept in process shares Jonquil's libraries, so a library Jonquil
  already loaded stays at its version. Run out of process when the version
  matters.
- Jolt v0.8.23 and earlier: two projects with an identical namespace file
  can make F12 open the other project's copy (jolt-lang/jolt#1404). Fixed on
  Jolt's main branch, not yet released.

</details>

<details>
<summary><strong>The debugger, tracing and live results</strong> (7)</summary>

- Tracing is for Jolt only, and doesn't record a traced function's call to
  itself.
- Live console results are for Jolt only; the last 100 are kept.
- Frames, their locals and Break on error need a Jolt with `jolt.debug`,
  which is on Jolt's main branch and not yet in a release.
- A top-level expression typed at the prompt keeps no frame of its own: its
  error shows frames only inside a function it called.
- Debug mode (Keep every local) keeps tail calls only for `recur`, so deep
  recursion that isn't `recur` can run out of stack while it's on.
- Nothing resumes a paused error or changes a local: Continue lets it
  unwind, and the restarts run the evaluation again.
- JVM Clojure frames come from the stack trace and keep no locals, and a
  JVM evaluation can't pause before it unwinds.

</details>

<details>
<summary><strong>ClojureDart</strong> (7)</summary>

- Stop detaches instead of interrupting, and there is no stdin.
- It needs the fork that adds an nREPL server, which is private for now.
- `println` output is lost once the form that printed it throws.
- Jonquil never re-sends your code; if the REPL doesn't answer its wake-up
  probe, it says so.
- Completion is buffer-only, and jump to definition stays in the buffer.
- No Reset on Run: every Run adds to the app.
- A load stops at the first form the REPL compiler rejects.

</details>

<details>
<summary><strong>JVM Clojure</strong> (5)</summary>

- It attaches to a server you started and never starts or stops the JVM.
- No Reset on Run.
- Stdin closes only on nREPL 1.8.0 or later.
- Output printed from another thread after the evaluation ends isn't shown.
- On nREPL 1.7 with JDK 20 or later, Stop can't end a loop that never checks
  for interruption (`(loop [] (recur))`); restart the server yourself.

</details>

<details>
<summary><strong>ClojureScript on node</strong> (4)</summary>

- Needs the Clojure CLI and `node` on the login shell's `PATH`. Its
  listeners are unauthenticated loopback sockets.
- Stop and Reset on Run restart the process, so definitions are gone.
- Cmd/Ctrl+D ends one `read-line`. No inspector, and F12 stays in the
  buffer.
- Completion covers the buffer, Clojure core and, once something ran,
  loaded namespaces; quick docs work while the process is idle.

</details>

<details>
<summary><strong>ClojureScript in the browser</strong> (6)</summary>

- Needs the Clojure CLI on the login shell's `PATH`; your code runs in a
  browser tab opened through `open`, `xdg-open` or `JONQUIL_OPEN_URL`. Its
  listeners are unauthenticated loopback sockets.
- Stop and Reset on Run restart ClojureScript; the tab reloads itself
  (a frozen or closed tab needs a hand).
- A never-ending loop freezes the tab until you reload it, and an evaluation
  sent after the tab closed fails until you reload.
- `println` and `js/console.log` reach Jonquil's console;
  `:forward-console? false` in the `:cljs` map keeps `console.log` in the
  browser only.
- No stdin, no inspector; F12 stays in the buffer.
- Completion and quick docs behave as on node.

</details>

<details>
<summary><strong>ClojureScript with shadow-cljs</strong> (5)</summary>

- It attaches to a shadow-cljs server you started.
- Stop detaches, since the work runs in the JS runtime. No stdin and no
  Reset on Run.
- `js/console.log` goes to the JS runtime's console; `println` reaches
  Jonquil.
- A namespace outside the watched build compiles but never loads (a
  ReferenceError on first use). Keep a Run'd file's requires within the
  build.
- With no JS runtime connected, Run says the build isn't loaded in any JS
  runtime.

</details>

<details>
<summary><strong>The nREPL dialect (babashka, Basilisp, other servers)</strong> (1)</summary>

- It does what the server offers. With no `interrupt` op, Stop disconnects
  and the evaluation keeps running; with no `stdin` op, a read waits until
  Stop on babashka and reads the server's terminal on Basilisp. No Reset on
  Run; jump to definition follows only files on this machine.

</details>

<details>
<summary><strong>jank</strong> (4)</summary>

- Stop restarts jank, so definitions are gone.
- No input, no inspector, and jump to definition stays in the buffer.
- Output is held until the evaluation ends; a Stopped evaluation shows none.
- Jonquil can't attach to a jank you started, and some loops (a bare
  `recur`, or one after a `when`) don't compile yet.

</details>

<details>
<summary><strong>The inspector</strong> (7)</summary>

- The panel never refreshes itself; reopen it after a later evaluation.
- A collection shows at most 1000 entries.
- On JVM Clojure, another client inspecting on the same server replaces
  Jonquil's view.
- On JVM Clojure, Earlier and Later can't go back to another object: the
  server's inspector keeps only the one it shows.
- Resizing the panel doesn't reflow rows already shown.
- No inspector for ClojureDart or ClojureScript.
- JVM Clojure's inspector needs your server to offer clojure-cli.repl's
  inspector; without it, Cmd/Ctrl+I is refused.

</details>

<details>
<summary><strong>MCP</strong> (2)</summary>

- An MCP eval, run or run-selection against a busy window answers busy
  instead of queueing (`jonquil_run_selection` still moves the selection).
- A listening window (`:mcp-listen?`) serves one MCP client at a time.

</details>
