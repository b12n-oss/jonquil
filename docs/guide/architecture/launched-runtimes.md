# Launched runtimes

Part of the [architecture notes](index.md): the runtimes Jonquil starts itself, ClojureScript on node or in a browser tab, and jank.

The full design record, with the reasoning and what was ruled out, is kept in `docs/history/architecture/launched-runtimes.md`.

## ClojureScript on node: `jonquil.cljs-node` and friends

`.cljs` outside a shadow-cljs project is a `:launch` runtime (with
`shadow-cljs.edn` the profile attaches through `jonquil.nrepl.shadow`
instead; `.jonquil.edn {:runtime ...}` overrides either way).
`ev/make-launched` dispatches on the profile id.

- `jonquil.cljs-node` is the pure half: console sentences, the `clojure`
  arguments (ClojureScript is added with `-Sdeps` only when `deps.edn`
  doesn't name it), the ready line and the prepl reply readers.
- `jonquil.cljs-node-launch` is JVM Clojure that runs inside the child. Jolt
  never loads it: `jonquil.nrepl.cljs-node-files` embeds its text at compile
  time and writes it under the cache folder. It serves a prepl on a
  loopback port, prints `jonquil-cljs-prepl <port> stdin:<path>`, and exits
  when its stdin ends, so a crashed Jonquil takes the child down. The
  `stdin:` token names a FIFO that relays input for `read-line`
  (`stdin:none` if it couldn't be made).
- The launcher serves a patched `node_repl.js` that listens on `127.0.0.1`
  port 0 and reports the port through a file, so no port is picked in one
  process and bound in another. It exits with an error if a ClojureScript
  version lacks the line it patches.
- `jonquil.nrepl.cljs-child` finds `clojure` (`JONQUIL_CLOJURE`, else the
  login shell's `PATH`), starts the launcher with stdin held open, waits
  for the ready line (60 s, since a first run downloads) and kills the
  process tree on stop.
- `jonquil.nrepl.prepl` is the prepl client: line-delimited EDN over a
  socket, one reader thread.
- `jonquil.nrepl.leftovers` prunes run dirs and logs that children leave in
  the temp folder. A dir whose `pid` is still running is never removed, and
  symlinks are never touched.
- `jonquil.nrepl.cljs-node` is the `Evaluator`.

Evaluator invariants:

- A prepl has no request ids, so each evaluation sends its forms plus a
  sentinel form and reads replies until the sentinel's `:ret`. Input that
  ends inside a form is refused before sending.
- Nothing can interrupt the child, so Stop, Reset on Run and a close kill it.
- Completion merges offline candidates with the analyzer's. An evaluation
  only marks that cache stale and cancels any help request in flight, so a
  Run never waits for help. Help runs only while idle.
- `read-line` rides the FIFO. `send-input!` and `send-eof!` are gated by one
  swap on `:waiting-input?`, so a line that lost the race against completion
  is dropped, as in Jolt.
- A setup that fails or times out kills the child with `:keep-log? true`, so
  the "The log: <path>" in the message points at a file that still exists.

The prepl and node's socket are unauthenticated and loopback-only, the same
exposure as the Jolt nREPL child.

### Browser mode

`.jonquil.edn {:cljs {:target :browser}}` makes a launched ClojureScript use
a browser tab (an unknown value warns and uses node; ignored with a warning
when the runtime attaches). The same evaluator, child and launcher serve
both; the launcher gets `browser <http-port>`.

- Everything binds to `127.0.0.1`. The launcher pre-binds the HTTP port and
  exits 3 (`jonquil-cljs-http-port-taken`) if busy, and the evaluator
  retries once on another port.
- A dead tab is detected, not waited on. ClojureScript only checks
  `.isClosed` on the page's held connection, so the launcher drops queued
  connections whose read ends, and a dead page fails the evaluation with
  "the page is gone ... reload <url>" instead of hanging until Stop.
- The page says goodbye itself, because Chrome keeps a departed page's
  connection for about a minute. A `pagehide` listener beacons
  `{:type :jonquil/bye :gen N}`; a bye for the current page fails any
  waiting evaluation, and a late bye from an earlier page is ignored. A
  page busy running a form can't fire it, so a tab closed mid-evaluation
  still waits until Stop.
- Console forwarding (off with `{:cljs {:forward-console? false}}`): the
  page's `console.*` calls still reach the browser, and are also batched
  into `repl-print` so they show in the console. Batching (100 ms, 100,000
  characters) keeps the post ordering the server depends on.
- A keep-fresh ping carries a per-process generation. If a different
  launcher now owns the port, the page reloads itself; an answer without
  the `jonquil-gen:` prefix ends the loop.
- The page URL carries a per-window token (`/?t=<uuid>`). A request bearing
  another window's token gets a "close this tab" page, so an orphan tab
  can't join a new session on a recycled port. A bare URL still serves.
- On launch the evaluator opens the page (`open`, `xdg-open` or
  `JONQUIL_OPEN_URL`) before the setup form, because setup blocks until a
  page connects. The wait is bounded by `:page-timeout-ms` (60 s). On
  timeout the console names the URL and the next Run waits for it.
- The HTTP port is picked once per window and kept across restarts. Stop
  and Reset on Run kill the child, wait for the kill (`await-kills!`, so
  the port is free) and ask for a reload.
- `jonquil.cljs-node` holds `browser-limitations`; the dialect label reads
  `ClojureScript (browser)`.

## jank: `jonquil.jank`, `jonquil.nrepl.jank-child` and `jonquil.nrepl.jank`

jank is a `:launch` runtime that shares nothing with `jonquil.nrepl.child`.

- `jonquil.jank` is pure: console sentences, the code strings for the
  find-ns check and docs, the connect policy and reply readers.
- `jonquil.nrepl.jank-child` finds the binary (`JONQUIL_JANK`, `PATH`,
  `/opt/homebrew/bin`, `~/.local/bin`) and spawns
  `jank --module-path <dir> repl` in a private temp run directory, then
  waits for the `.nrepl-port` jank writes there. Nothing is read or written
  in the project. Stdin stays open, because EOF ends `jank repl`. `kill!`
  signals the whole process tree before closing stdin (the other order
  orphans descendants), then deletes the run directory.
- `jonquil.nrepl.jank` is the evaluator, built on
  `jonquil.nrepl.connection` like `jonquil.nrepl.jvm`, with a `:port-file`
  policy function because the port file lives in the evaluator's run
  directory. The child starts lazily. jank has no interrupt, so Stop and
  Reset on Run kill it in the background and the next evaluation starts a
  fresh one; `:killing` counts kills in flight and quitting waits for them.
  Docs come from the var's metadata, never the `lookup` op, which hangs on
  current jank. Help is skipped while an evaluation runs, since jank serves
  one request at a time.

`jonquil.runtime-registry` waits at quit for a launch still in flight, so a
window closed while jank starts leaves no process or run directory behind.
