# MCP: `jonquil --mcp`

Part of the [architecture notes](index.md).

Three namespaces for the headless server, none of which requires a `jonquil.gtk` one, so the headless
entry never opens a window:

- `jonquil.mcp.json` is the JSON codec (`decode`, `encode`, string keys both
  ways) over `org.clojure/data.json`, plus the small pre-pass that reads
  surrogate-pair escapes.
- `jonquil.mcp` is the protocol. `handle-message` turns one decoded request
  into a response map or nil, and `serve!` loops over a line reader and a line
  writer it is handed, skipping blank lines and guarding every message so one
  bad request never ends the loop or mislabels its error.
- `jonquil.mcp.tools` holds the six tool definitions and `make-tools`, which
  keeps one evaluator per project selection (the same `.jonquil.edn` choice the
  window makes) and registers each with `jonquil.runtime-registry`, so
  `registry/stop-all!` stops them. An evaluation that passes its deadline is
  interrupted, and a dead one can't wedge its project.

**Window mode.** `jonquil.mcp.window` sits beside these and builds the nineteen
tools for `jonquil --mcp --window`. It never requires `jonquil.gtk.window`:
it receives the window's `ui` map (the `:on-ready` map) and touches it only
through `gui-call`, a thunk handed to the GTK thread with a bounded deref, so
the serve thread never calls GTK itself. The window gives it three notifying
hooks, `do-run!` (with an optional `notify`), `mcp-eval!` and
`mcp-run-selection!`. Each takes a `notify` function and calls it exactly once
with `{:refused msg}`, `{:busy warning}`, `{:problem text}`, `{:none s}` or,
from the eval worker, `{:done {:result r :output s}}`, and returns a reader
`(fn [] output-so-far)`. A `notify` callback only delivers a promise, so a
slow or abandoned call can't block the worker. The window keeps its one
evaluator; the tools never stop or swap it. The stdout rule below holds in
window mode unchanged.

**Listener and bridge.** `jonquil.mcp.listen` lets a window that has
`:mcp-listen? true` serve those same nineteen tools to a socket client. It binds
127.0.0.1 on an OS-picked port, accepts one connection at a time and runs
`serve!` over it, and it records the port in a per-window registry file
(`<settings-dir>/mcp/<pid>.edn`) that `stop!` removes and that startup
housekeeping reaps when the pid is dead. `jonquil --mcp-attach [DIR]` is the
other half: `discover` picks the live entry whose folder matches `DIR` (or the
only live one), and `bridge!` copies lines between stdio and the socket without
re-encoding them. Neither side touches `jonquil.gtk`.

**The stdout rule.** JSON-RPC owns the real stdout, and nothing else may write
to it. `jonquil.main/-main` branches on `mcp-args` before `start!`, saves the
real `*out*`, then points the root `*out*` at `*err*` with `alter-var-root`, so
a `println` from any runtime thread lands on stderr. Only the `:write-line`
function passed to `serve!` rebinds `*out*` to the saved writer, prints one
line and flushes. The shutdown hook that calls `registry/stop-all!` covers this
path as well as the window's.
