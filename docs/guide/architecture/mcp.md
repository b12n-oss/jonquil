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
- `jonquil.mcp.tools` holds the fifteen tool definitions and `make-tools`. It
  keeps one evaluator per project selection (the same `.jonquil.edn` choice
  the window makes) and registers each with `jonquil.runtime-registry`, so
  `registry/stop-all!` stops them. An evaluation that passes its deadline is
  interrupted, and a dead one can't wedge its project.
- `jonquil.test-run` is what `jonquil_test` and the window's Run Tests share.
  It builds the code that runs a namespace's `clojure.test` tests in the
  project's runtime with `report` rebound, reads the run back as data, and
  makes the report, its text and its structured form. It starts no evaluator.
- `jonquil.describe-run` is the same for `jonquil_namespaces`, `jonquil_vars`
  and `jonquil_apropos`: the query code, which reads `all-ns` and
  `ns-interns` in the project's runtime, and the answers made from its value.
  The window runs it through `win-run/mcp-query!`, which makes no console
  entry.
- `jonquil.mcp.frames` makes the answers of `jonquil_frames` and of
  `jonquil_eval` with a `frame` from what the evaluator's
  `jonquil.evaluator/Frames` gives (`error-frames!`, `eval-in-frame!`), plus
  the refusals (busy, paused, unsupported). It calls no evaluator itself.
  Both tables call `Frames` and hand it the result, and neither call
  records an error or touches `*1`.
- `jonquil.mcp.changed` makes the answers of `jonquil_changed` and
  `jonquil_run_changed` for both tables, from `jonquil.changed` and
  `change-sets/from-changed`: the forms, the code to send and the
  `never-ran` and `no-changes` answers. Headless, `make-tools` keeps a record
  per file in its env, `{path {:e :ran :last-run}}`. `jonquil_run_file`
  writes it after a run that ends `ok`, `jonquil_run_changed` adds the keys
  of the forms it sent, and the record only counts while `:e` is still the
  project's cached evaluator, so an evicted runtime reads as `never-ran`.

- `jonquil.mcp.world` makes the answers of `jonquil_world_save` and
  `jonquil_world_restore` for both tables. The steps around the runtime's
  world op (write, record and prune a save; save what's there before a
  restore) live in `jonquil.world-ops`, which the window's worlds part
  uses too. In `jonquil.world` an agent's saves are the `:agent` kind and
  the saves its restores make first are `:agent-before-restore`; each kind
  is pruned apart from the person's. Headless, the world folder is
  `world/world-dir` of the server's settings path and `world-root`, the
  folder `file-tree/root-for` gives a window opened on that path, so a
  headless server and that window share one history.

**Window mode.** `jonquil.mcp.window` builds the twenty-nine tools for
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
The frames tools go through `:mcp-frames!*` and `:mcp-frame-eval!*`, which
call the window evaluator's `Frames` directly, so the Frames panel and the
console stay as they were. The debugger part adds `:mcp-paused?*`, read on
the GTK thread, so they refuse while an evaluation is paused there.
The changed-forms tools use two more keys. `:mcp-changed*` gives a tab's
text, its ran set and its last-run text, read on the GTK thread, and
`:mcp-run-changed!*` runs `win-run/mcp-run-changed!`, Run Changed Forms over
any tab with a `notify`. Its `{:unchanged {:status :text}}` notify, for a tab
that never ran or has nothing changed, maps to a result that isn't an error.
The world tools go through the worlds part's `:mcp-world!*`, fired on the
GTK thread, which runs the same `jonquil.world-ops` steps as Save World and
Restore World and reports to the person the same way.

**Listener and bridge.** `jonquil.mcp.listen` lets a window with
`:mcp-listen? true` serve those twenty-nine tools to a socket client. It binds
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
