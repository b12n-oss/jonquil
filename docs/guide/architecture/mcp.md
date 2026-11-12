# MCP: `jonquil --mcp`

Part of the [architecture notes](index.md).

The full design record, with the reasoning and what was ruled out, is kept in `docs/history/architecture/mcp.md`.

Three namespaces serve the headless server. None requires a `jonquil.gtk`
one, so the headless entry never opens a window:

- `jonquil.mcp.json` is the JSON codec (`decode`, `encode`, string keys both
  ways) over `org.clojure/data.json`, plus a small pre-pass that reads
  surrogate-pair escapes.
- `jonquil.mcp` is the protocol. `handle-message` turns one decoded request
  into a response map or nil. `serve!` loops over a line reader and a line
  writer it is handed, skips blank lines and guards every message, so one bad
  request never ends the loop or mislabels its error.
- `jonquil.mcp.tools` holds the six tool definitions and `make-tools`. It
  keeps one evaluator per project selection (the same `.jonquil.edn` choice
  the window makes) and registers each with `jonquil.runtime-registry`, so
  `registry/stop-all!` stops them. An evaluation that passes its deadline is
  interrupted, and a dead one can't wedge its project.

**Window mode.** `jonquil.mcp.window` builds the twenty tools for
`jonquil --mcp --window`. It never requires `jonquil.gtk.window`. It receives
the window's `ui` map (the `:on-ready` map) and touches it only through
`gui-call`, a thunk handed to the GTK thread with a bounded deref, so the
serve thread never calls GTK itself.

The window supplies three notifying hooks: `do-run!` (with an optional
`notify`), `mcp-eval!` and `mcp-run-selection!`. Each calls its `notify` once
with `{:refused msg}`, `{:busy warning}`, `{:problem text}`, `{:none s}` or,
from the eval worker, `{:done {:result r :output s}}`, and returns a reader
`(fn [] output-so-far)`. A `notify` only delivers a promise, so a slow or
abandoned call can't block the worker. The window keeps its one evaluator;
the tools never stop or swap it. `jonquil_change_set_propose` saves through
the ui's `:change-set-propose!*` hook (the change sets part, with the
client's name from `make-window-tools`' `:client-fn`) and applies nothing.

**Listener and bridge.** `jonquil.mcp.listen` lets a window with
`:mcp-listen? true` serve those twenty tools to a socket client. It binds
127.0.0.1 on an OS-picked port, accepts one connection at a time and runs
`serve!` over it. It records the port in a per-window registry file
(`<settings-dir>/mcp/<pid>.edn`), which `stop!` removes and startup
housekeeping reaps when the pid is dead.

`jonquil --mcp-attach [DIR]` is the other half: `discover` picks the live
entry whose folder matches `DIR` (or the only live one), and `bridge!` copies
lines between stdio and the socket without re-encoding them. Neither side
touches `jonquil.gtk`.

**The stdout rule.** JSON-RPC owns the real stdout, and nothing else may write
to it, in window mode too. `jonquil.main/-main` branches on `mcp-args` before
`start!`, saves the real `*out*`, then points the root `*out*` at `*err*` with
`alter-var-root`, so a `println` from any runtime thread lands on stderr. Only
the `:write-line` function passed to `serve!` rebinds `*out*` to the saved
writer, prints one line and flushes. The shutdown hook that calls
`registry/stop-all!` covers this path as well as the window's.
