# Any nREPL server: babashka, other Clojures, other hosts

Part of [Dialects and runtimes](index.md).

`{:dialect :nrepl}` in `.jonquil.edn` attaches to an nREPL server you
started: babashka, Basilisp, or one on another machine. Jonquil connects on
the first Run and never starts or stops it. A feature works when the
server's `describe` reply offers the op behind it, and the start lines say
which ones are missing.

`:nrepl` says where to connect:

```clojure
{:dialect :nrepl :nrepl {:port 1667}}                 ; this machine
{:dialect :nrepl :nrepl {:host "box" :port 1667}}     ; another one
{:dialect :nrepl :nrepl {:port-file "tmp/nrepl.port"}} ; read the port from a file
```

With no `:port`, Jonquil reads `:port-file`, then `.nrepl-port` in the
project. The host defaults to `127.0.0.1`. An unusable value (a port
outside 1-65535, an absolute `:port-file`) warns in the console and uses
the default.

**babashka**: `bb nrepl-server` writes no port file, so name the port:

```bash
bb nrepl-server 1667
```

**Basilisp**: its server writes `.nrepl-port` in the folder you start it
in, so `{:dialect :nrepl}` alone is enough:

```bash
basilisp nrepl-server
```

![An nREPL window attached to babashka after Run: the console connects to the server at 127.0.0.1, prints the program's line, then answers two REPL entries, the second one babashka's version](../../images/nrepl-bb.png)

Run, the REPL, quick docs and completion work on both. Neither has an
`interrupt` op, so Stop disconnects and the evaluation keeps running on the
server. Neither has `stdin`: on babashka `read-line` waits until Stop; on
Basilisp type the line in the terminal running the server. Jump to
definition stays in the buffer for library functions on babashka, since
it names a file not on this disk. See [Known limits](../known-limits.md).

A remote server is unauthenticated code execution, now on the network.
Keep it on a trusted network or tunnel it: `ssh -L 1667:localhost:1667 box`,
then `{:port 1667}`. Over a slow link, completion and quick docs arrive
after a moment (see [Completion](../help-and-inspector.md#completion)).
