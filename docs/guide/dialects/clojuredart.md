# ClojureDart (experimental): attaching to a running app

Part of [Dialects and runtimes](index.md).

Jonquil attaches to a ClojureDart app you already have running, like CIDER
to a live process. It never starts, stops or signals the app.

- Run the app under `bb dev:macos` (or `clj -M:cljd flutter`) on the
  ClojureDart fork that adds an nREPL server. It writes its port to
  `NREPL.lock` in the app directory, which Jonquil reads (never writes).
  Upstream has no nREPL server, and the fork is private for now.
- Open a file and Run. The first answer takes a few seconds and brings the
  ClojureDart banner. Then evaluate forms one at a time from a
  `(comment ...)` block.

<details>
<summary><strong>Connecting, Run, Stop, Reset on Run and docs</strong></summary>

- **Can't connect**: the console says the app isn't running, that
  `NREPL.lock` names a dead port, or that the port isn't a ClojureDart
  server.
- **Run** loads the buffer's text (unsaved edits included) and leaves the
  session in the file's namespace. It prints "; Loaded <ns> (<n> forms)",
  or "Load stopped at form <n>" with the app's error. An untitled buffer
  is refused: save it first.
- **Run Selection and Run Form** (Cmd/Ctrl+E, Cmd/Ctrl+Enter) run in the buffer's namespace, evaluating its `ns` form
  first if the session is elsewhere. REPL entries run wherever the session
  is.
- **Stop** detaches, leaving the app untouched; a change already sent may
  still land. The next evaluation reconnects. After 30 seconds you see "Still
  waiting on the app..."; Jonquil has no timeout of its own.
- **Reset on Run** is greyed out: hot reload can't unload a namespace, so
  every Run adds to the app.
- **Completion** is offline (buffer, `ns` aliases, built-ins). **Quick
  documentation** asks the app and says if it isn't attached, timed out, or
  the fork can't look symbols up.

</details>

More in [Known limits](../known-limits.md).
