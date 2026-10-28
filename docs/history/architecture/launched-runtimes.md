# Launched runtimes

> **Archive.** This is the full design record for this part of Jonquil, as it stood on 2026-10-29: the reasoning behind each choice, how it was checked, and what was ruled out, step by step. The current, shorter description is [docs/guide/architecture/launched-runtimes.md](../../guide/architecture/launched-runtimes.md). This copy isn't kept up to date, and it isn't part of the docs site.

Part of the [architecture notes](index.md): the runtimes Jonquil starts itself, ClojureScript on node or in a browser tab, and jank.

## ClojureScript on node: `jonquil.cljs-node` and friends

`.cljs` outside a shadow-cljs project is the second `:launch` runtime. The
dialect profile has two runtimes: `:attach` (`jonquil.nrepl.shadow`), the
default in a project with `shadow-cljs.edn` through the profile's
`:project-evaluator`, and `:launch` for everything else, which
`.jonquil.edn {:runtime ...}` can override. `ev/make-launched` dispatches on
the profile id, so `jonquil.nrepl.cljs-node` registers `:cljs` beside jank.

Five namespaces and one launcher make it up:

- `jonquil.cljs-node` is the pure half: the console's sentences, the
  `clojure` arguments (ClojureScript 1.12.42 is added with `-Sdeps` only when
  `deps.edn` doesn't name `org.clojure/clojurescript`), the ready line and
  the readers for prepl replies.
- `jonquil.cljs-node-launch` is JVM Clojure that runs inside the child. Jolt
  never loads it: `jonquil.nrepl.cljs-node-files` embeds its text when Jonquil
  is compiled and writes it under the cache folder. It serves a prepl
  (`cljs.server.node/prepl`) on a loopback port the OS picks, prints
  `jonquil-cljs-prepl <port> stdin:<path>`, and exits when its stdin ends,
  which is how a crashed Jonquil takes the child down. The `stdin:` token
  names a FIFO (`mkfifo`) the launcher makes in its own temp folder
  (`stdin:none` when that failed), and the stdin watcher doubles as the
  relay: each line Jonquil writes goes into the FIFO, through a `"rw"`
  open, which never blocks on a FIFO where a write-only open would wait
  for `read-line`'s first reader. ClojureScript's `node_repl.js` listens
  on every interface, so the launcher serves a patched copy of it through a
  classloader: the one line `.listen(PORT);` becomes a listen on port 0 at
  `127.0.0.1`, and if a ClojureScript version doesn't contain that line the
  launcher exits with an error. node then writes the port the OS gave it to
  `port-<PORT>` in the copy's folder (through a rename), with `PORT` now a
  per-env token rather than a port. The launcher replaces
  `cljs.repl.node/create-socket`, which `setup` calls every 50 ms until node
  answers, with one that reads that file, connects to the port in it and
  deletes it; a missing file throws like a refused connect, so `setup` just
  tries again. ClojureScript's own way, a port picked in the JVM and bound
  later by node, left a gap in which another process could take the port, and
  `setup` would then connect to that process. The function's arglists are
  checked first, as for the browser's `cljs.repl.server/start`. The copy
  lives in a temp folder that `deleteOnExit` removes.
- `jonquil.nrepl.cljs-child` finds `clojure` (`JONQUIL_CLOJURE`, else the
  login shell's `PATH`), starts the launcher in the project directory with
  stdin held open, waits for the ready line (60 s, since a first run
  downloads), and kills the process tree on stop.
- `jonquil.nrepl.prepl` is the client for ClojureScript's prepl: line-
  delimited EDN over a socket, one reader thread, replies delivered to a
  handler that drops anything after `:closed`.
- `jonquil.nrepl.leftovers` removes what children leave in the temp folder:
  a run dir kept for its log (`jonquil-cljs-run-`), a Jolt start's kept log
  (`jonquil-nrepl-<n>.log`), and whatever a SIGKILL left behind
  (`jonquil-cljs-`, `jonquil-jank-run-`). Every Jolt, ClojureScript and jank
  start calls `prune!`, which deletes the ones untouched for a day, the same
  age as `prune-old!`'s. With no project a child runs inside its run dir, so
  each run dir holds a `pid` file (the launcher writes its own) and a folder
  whose pid is still running is skipped at any age. A folder with no `pid`
  file (an older Jonquil's) waits a week instead, since its compile output
  sits in subfolders the age check doesn't read. Symlinks are never
  touched.
- `jonquil.nrepl.cljs-node` is the `Evaluator`. A prepl has no request ids,
  so each evaluation sends its forms followed by a sentinel form and reads
  replies until the sentinel's `:ret`. Input that ends inside a form is
  refused before sending, since the reader would wait for the rest. Stop,
  Reset on Run and a close kill the child, because nothing can interrupt it.
  Completion merges the offline candidates with the analyzer's: the first
  completion after an evaluation sends `(cljs.repl/apropos #"")` through
  the same exchange (a macro, so the compiler answers and the runtime
  returns a literal; 1,302 symbols, 28 KB, 23-44 ms measured), capped at
  20,000 symbols, under the quick-doc budget and the same `:helping`
  guard. The evaluation itself only marks the cache stale: a fetch riding
  after an evaluation held up the browser's closed-tab fail-fast (8 s
  measured) and made quick docs answer busy. An evaluation arriving while
  a fetch or doc lookup is in flight cancels it (the exchange's sentinel
  drains as on a timeout), so a Run never waits a help budget out. Pure code
  (`cljs-node/analyzer-candidates`, `cljd/buffer-ns-refs`) spells each
  qualified name the way the buffer can use it.
  `read-line` rides the FIFO: after the `(require 'cljs.repl)` setup, a
  second sentinel exchange defines `cljs.core/read-line` (via `in-ns` +
  `declare`, so plain `(read-line)` compiles warning-free anywhere) as a
  blocking FIFO read bracketed by two `*print-err-fn*` marker lines, which
  the prepl tags `:err` and `dispatch!` strips while flipping
  `:waiting-input?` (a raw fd 2 write vanishes: the node child's stderr is
  pumped nowhere, measured). `send-input!` and `send-eof!` are gated: one
  swap turning `:waiting-input?` false picks a single writer, so a line
  that lost the race against completion is dropped, as Jolt's middleware
  drops one. EOF is a dedicated marker line the reader answers nil for,
  freeing one read while the pipe stays open. When the launcher announces
  `stdin:none`, or the reader setup fails, `read-line` is defined to throw
  a clear message and the console says so once; the session is otherwise
  unchanged.
  A setup that fails or times out kills the child with `:keep-log? true`,
  which leaves the run dir, so the message's "The log: <path>" still points
  at a file; every other kill removes it. A kept run dir, like a failed
  start's, stays until `jonquil.nrepl.leftovers` prunes it (below). Help evaluates `cljs.repl/doc` only while idle; a help that times out
  leaves its sentinel in `:drain`, and replies up to that `:ret` are dropped
  before the next evaluation's are read.

Both listeners (the prepl and node's socket) are unauthenticated and
loopback-only, the same exposure as the Jolt nREPL child.

### Browser mode

`.jonquil.edn {:cljs {:target :browser}}` makes `jonquil.dialect` return
`:cljs-target :browser` for a launched ClojureScript (node otherwise; an
unknown value warns and uses node, and the key is ignored with a warning when
the runtime attaches). The same evaluator, child and launcher serve both:

- The launcher takes `browser <http-port>`. It pre-binds that port on
  `127.0.0.1` and exits 3 with a `jonquil-cljs-http-port-taken` marker if it
  is busy (the evaluator then picks another port once and retries), and exits
  2 with a usage line when the port argument is missing. ClojureScript's
  `cljs.repl.server/start` listens on every interface, so the launcher
  replaces it with the same body bound to loopback, taking the pre-bound
  socket the first time, and exits 2 if the function's arglists differ. The
  prepl is `cljs.server.browser/prepl` with `:launch-browser false`, and the
  ready line carries the page URL.
- The page holds a connection open that the server answers with the next
  form. `cljs.repl.server/connection` checks only `.isClosed`, which the
  page closing its end doesn't change, so after the tab closed an evaluation
  went into a dead socket and waited until Stop. The launcher replaces it
  (arglists checked the same way) with one that drops a queued connection
  whose read ends or fails instead of timing out after 1 ms. When that
  leaves none, it waits 2 s for a page, since a Run pressed right after a
  reload races the reloaded page's first post, then throws "the page is
  gone ... reload <url>", which the prepl answers as an error. With nothing
  queued from the start, as during the setup, it waits as before.
- That socket check alone isn't enough in Chrome, which keeps a page's open
  connection for about a minute after the page leaves (measured: 56 s from
  a navigation to `about:blank` until the socket reached `CLOSE_WAIT`). So
  the page says goodbye itself. The launcher wraps the `:ready` handler,
  which each page that connects (a reload included) posts first, and
  `send-for-eval`, so the setup that handler sends carries a `pagehide`
  listener. The same setup, unless the launcher was started with
  `no-console` (`{:cljs {:forward-console? false}}`), wraps the page's
  `console.log`, `info`, `warn`, `error` and `debug`: each still calls the browser's own, then queues its
  arguments, joined by spaces, for `clojure.browser.repl/repl-print`, the
  path `println` takes to the prepl's `:out`. The queue goes out as one
  `repl-print` every 100 ms, at most 100,000 characters of it, with a note
  counting the rest. Every print is its own numbered post, and after
  `:ready` the server starts from whichever number arrives first, so a
  print that beat the setup's own `:result` would leave that result, and
  the page's connection, unhandled until a reload; the delay keeps the
  first console send well behind it. Batching also keeps a page that logs
  in a loop to a few posts a second, since a post that fails all of the
  client's 10 retries leaves a gap the server's ordering never gets past.
  `println` has both races in ClojureScript's own client, unchanged here. A
  `busy` flag stops a console call made synchronously while forwarding
  (inside `pr-str`, say) from forwarding again. On `pagehide` (tab closed, reloaded or navigated away) the
  listener sends `navigator.sendBeacon` with `{:type :jonquil/bye :gen N}`,
  N being that page's number. A `:jonquil/bye` handler for the current page
  closes the queued connections, marks the page gone, and completes an
  evaluation still waiting for that page's answer with the same error. A
  goodbye from an earlier page is ignored, so a reload's late beacon can't
  close the new page's connection. The same setup carries a keep-fresh
  ping: the page POSTs `{:type :jonquil/ping}` (every five seconds while
  healthy, once a second for the first thirty after the server stops
  answering, giving up after ~750 failures, about an hour in a foreground
  tab; a non-200 answer counts as a failure, so a later owner rejecting
  POST / doesn't keep the loop alive), a handler
  answers the process's generation (a UUID picked at launcher start,
  prefixed `jonquil-gen:`), and a different prefixed answer means a new
  process owns the port, so the page reloads itself; the fresh page
  carries the new generation, so there is no loop, and an unprefixed 200
  (another program owns the port now) ends the loop. The page URL carries
  a per-window token (`/?t=<uuid>`, minted once per window by the
  evaluator, passed as the launcher's `token:` argument): the launcher
  prepends one `:get` handler to `cljs.repl.server/handlers` that answers
  a request for `/` or `/index.html` bearing ANOTHER window's `t` with a
  script-free "close this tab" page, so an orphan tab's reload can't join
  a new session on its recycled port, while a bare request (a hand-typed
  URL) falls through and serves. A launcher started without a `token:`
  argument installs no gate. This closes the reload path; the client's
  own bounded POST retries remain a seconds-wide residual (ROADMAP). A
  page busy running a form can't fire
  `pagehide` until the form finishes, so a tab closed during a long
  evaluation still leaves it waiting until Stop.
- `jonquil.nrepl.cljs-node` picks the HTTP port once per window and keeps it
  across restarts. On a launch it announces the page (`open-url!`: `open`,
  `xdg-open` or `JONQUIL_OPEN_URL`) before the setup form, because the
  browser REPL's setup blocks until a page connects, so the wait is bounded
  by `:page-timeout-ms` (60 s) rather than the ordinary eval timeout. When it
  times out, the setup stays pending, the console names the URL and the next
  Run waits for it. ClojureScript's "Waiting for browser to connect" line is
  dropped. Stop and Reset on Run kill the child, wait for the kill to finish
  (`await-kills!`, so the port is free) and ask for a reload.
- `jonquil.cljs-node` holds the browser-specific sentences and
  `browser-limitations`; the dialect label reads `ClojureScript (browser)`.

Both listeners (the prepl and the page server) are unauthenticated and
loopback-only.

## jank: `jonquil.jank`, `jonquil.nrepl.jank-child` and `jonquil.nrepl.jank`

jank is the first dialect whose runtime is `:launch` (ClojureScript on node is the second): Jonquil starts the
process, as it does for out-of-process Jolt, but nothing is shared with
`jonquil.nrepl.child`, because jank differs in too many details. Three
namespaces make it up, plus a profile in `jonquil.dialect`.

- `jonquil.jank` is pure: the console's sentences, the code strings sent
  for the find-ns check and for docs, the connect policy, and readers for
  jank's replies. No socket and no GTK.
- `jonquil.nrepl.jank-child` is the launcher. It finds the binary
  (`JONQUIL_JANK`, `PATH`, `/opt/homebrew/bin`, `~/.local/bin`), spawns
  `jank --module-path <dir> repl` directly in a private temp run
  directory, and waits for the `.nrepl-port` jank writes there, so there
  is no port scan and nothing is read or written in the project. The
  spawn keeps its stdin pipe open, because EOF on stdin ends `jank repl`.
  `kill!` signals the whole process tree before closing stdin (the other
  order lets jank exit first and orphans any descendant) and then deletes
  the run directory.
- `jonquil.nrepl.jank` is the evaluator, built on
  `jonquil.nrepl.connection` the way `jonquil.nrepl.jvm` is. The child
  starts lazily on the first evaluation. Connection takes a `:port-file`
  policy key, a function, because the port file lives in a run directory
  the evaluator owns rather than in the project. jank has no interrupt, so
  Stop and a Reset on Run kill the child and the next evaluation starts a
  fresh one. Stop's kill runs in the background, so the window doesn't
  block while jank dies; `:killing` counts kills in flight and the
  evaluator isn't settled until they end, so quitting still waits for
  them. Docs come from an eval of the var's metadata, never the
  `lookup` op, which hangs on current jank. Help is skipped while an
  evaluation runs, since jank serves one request at a time.

`jonquil.runtime-registry` knows about jank too: at quit it waits, within a
deadline, for a launch that is still in flight, so a window closed while
jank starts leaves no process and no run directory behind.
