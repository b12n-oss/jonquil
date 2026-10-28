# Driving Jonquil from an agent (MCP)

`jonquil --mcp [DIR]` runs Jonquil with no window as an MCP server on stdio,
one JSON-RPC message per line. An agent evaluates code, runs files and looks
things up in the same runtime a person would get in the IDE. `DIR` is the
default project directory (the working directory when you leave it out).
Register it with Claude Code:

```bash
claude mcp add jonquil -- jonquil --mcp /path/to/project
# from a checkout:
claude mcp add jonquil -- jolt -M -m jonquil.main --mcp /path/to/project
```

Six tools. Most take an optional `path` (a file or directory) that selects
the project, defaulting to `DIR`. `jonquil_run_file` requires its file `path`,
and `jonquil_check_brackets` takes either `text` or a `path` naming the file
to check:

<details>
<summary><strong>The six tools</strong></summary>

| Tool | What it does |
|---|---|
| `jonquil_eval` | Evaluates code as a REPL line; definitions persist between calls |
| `jonquil_run_file` | Runs a file's text the way the Run button does |
| `jonquil_check_brackets` | Checks brackets in text or a file; names the first unclosed or unmatched one |
| `jonquil_doc` | Documentation for a symbol in the project's runtime |
| `jonquil_complete` | Up to 50 completion candidates for a prefix |
| `jonquil_project` | The dialect, runtime and directory the other tools would use; starts nothing |

</details>

The dialect and runtime come from the IDE's own choice: the file's extension
and the project's `.jonquil.edn` (see [Choosing a dialect](dialects/index.md#choosing-a-dialect)
and [`.jonquil.edn`](dialects/index.md#jonquiledn)), so a Jolt project with a `deps.edn`
evaluates in a Jolt child, one with `{:runtime :in-process}` inside the
server's own process, and one with `{:dialect :clj}` attaches to its nREPL
server. `jonquil_eval` and `jonquil_run_file` take a `timeout_ms`; the default
deadline is 30 s, after which the evaluation is interrupted and the tool
answers with an error. If it still hasn't stopped 5 s after the interrupt,
Jonquil stops that runtime, and the next call starts a fresh one. Closing stdin ends the server and stops every runtime
it started. On a project with an out-of-process runtime, `jonquil_doc` and
`jonquil_complete` answer from the editor's offline tables until the first
evaluation has started the runtime, so evaluate once first to get
project-aware answers.

## Window mode

`jonquil --mcp --window [DIR]` opens the normal window and serves the same
protocol on stdio, so you watch the agent work. Register it the same way:

```bash
claude mcp add jonquil -- jonquil --mcp --window /path/to/project
# from a checkout:
claude mcp add jonquil -- jolt -M -m jonquil.main --mcp --window /path/to/project
```

Headless `jonquil --mcp` keeps its six tools unchanged. With `--window` the
server offers nineteen, and they act on the live window rather than on private
evaluators:

<details>
<summary><strong>The nineteen window tools</strong></summary>

| Tool | What it does |
|---|---|
| `jonquil_eval` | Evaluates code as a REPL entry at the console prompt: echoed, visible, with `*1` and history as if you had typed it |
| `jonquil_run` | Runs the active tab the way the Run button does: banner, bracket check, reset-on-run as configured |
| `jonquil_run_selection` | Takes `start` and `end` character offsets, selects that range in the active tab (visibly) and runs it as Cmd/Ctrl+E does |
| `jonquil_stop` | Interrupts the running evaluation and reports whether it stopped within 5 s |
| `jonquil_open` | Opens a file as a tab, or selects the tab already showing it; it becomes the active tab |
| `jonquil_tabs` | One line per tab: id, an active marker, dirty or saved, the path or Untitled |
| `jonquil_select_tab` | Makes the tab with the given `id` active |
| `jonquil_read_tab` | The tab's full text (the active tab when you leave `id` out) |
| `jonquil_replace_text` | Replaces the tab's text as one undoable edit; the tab reads dirty until saved |
| `jonquil_save` | Saves the active tab to `path`, or to its own path |
| `jonquil_console` | First line `mark <N>`, then the console output since the `since` mark (pass back the last `N` to read only what is new) |
| `jonquil_doc` | Documentation for a symbol, from the window's evaluator |
| `jonquil_complete` | Completion candidates for a prefix, one per line, at most 50 |
| `jonquil_check_brackets` | "ok", or the sentence naming the first problem, for `text` or a file `path` |
| `jonquil_project` | The window's live dialect selection: label, runtime, project directory, source, warnings |
| `jonquil_plugins_list` | One line per plugin: its live version, whether it's loaded or disabled, its effects, any pending proposal, and its doc |
| `jonquil_plugin_read` | A plugin's live source and its history (each version's status, source and reason), or one `version`'s source |
| `jonquil_plugin_propose` | Proposes a plugin's whole new `source` for `id`, with a `reason`, and answers its status: pending, rejected-by-checks or refused |
| `jonquil_plugin_status` | A proposal's status (the latest one when you leave `n` out), and your note when you rejected it |

</details>

<details>
<summary><strong>Timeouts, other projects' tabs, disconnecting and trust</strong></summary>

`jonquil_eval`, `jonquil_run` and `jonquil_run_selection` take a `timeout_ms`
(default 30000) and interrupt the evaluation when it expires. A program that
stops to read from stdin makes the call return early with what it printed so
far, and you can type into the console yourself or call `jonquil_stop`.

The window serves its launch project. Tabs from a foreign project open and
edit like any others, but Run on one is refused with the window's usual
message, so the agent can't quietly evaluate in the wrong runtime. A
`jonquil_run_selection` call moves the visible selection even when the run is
then refused or busy, because the selection is applied first by design.

When the client disconnects (EOF on stdin), the window closes and any unsaved
edits are discarded, so have the agent call `jonquil_save` first. A failure in
the serve loop writes a line to stderr and closes the window the same way.
`bb mcp:smoke --window` exercises all of this end to end.

The trust boundary is the headless server's, plus the files the window can
reach: the agent can open, edit and save any file you can.

</details>

<details>
<summary><strong>An agent proposing a plugin</strong></summary>

`jonquil_plugin_propose` never runs the agent's code in Jonquil. The proposal
is checked in a separate jolt process first. When it passes, it waits as a
pending proposal, the status bar counts it, and you decide it in Review Plugin
Proposals. No tool can accept or reject one, so the agent learns your decision
by calling `jonquil_plugin_status`, which also passes on your note when you
reject it.

A proposal is refused before any check when its id isn't a plugin id, when its
reason is blank or longer than 4000 characters, when its source is longer than
262144, or when the window was started with `--no-plugins`. The reading tools
still answer with plugins off. A check can take up to 30 s and the window may be
busy with another plugin step, so a propose can take most of a minute. If the
step doesn't finish in time, the answer is `status: unknown`, and
`jonquil_plugin_status` tells the agent whether the proposal was recorded.

If an agent's newer proposal replaces the one you're reading while the panel
shows it, the next Accept is refused and says so, so you never accept a version
you didn't see. Press Accept again once you've read the new one.

</details>

<details>
<summary><strong>A plugin's own tools</strong></summary>

A plugin can give agents tools of its own in `:mcp-tools`, one map per tool:

```clojure
:mcp-tools [{:name "twice" :doc "The text twice."
             :args {"text" {:type "string" :doc "The text." :required? true}}
             :run (fn [ctx args] (str (get args "text") (get args "text")))}]
```

A window serving MCP (`--mcp --window`, or one you attach to) serves each live
plugin's tools as `plugin_<id>_<name>` after its nineteen, so the one above is
`plugin_smoke-t_twice` in a plugin `smoke-t`. Argument types are `string`,
`integer`, `number` and `boolean`, and a required argument that's missing or
of the wrong type is refused before `:run` sees it. `:run` answers a string,
or `{:text "..." :error? true}`. It runs with only the effects the plugin
declares, gets 30 s to answer, and runs one call at a time per tool. A call
still going at 30 s keeps the tool busy until it ends, since nothing can stop
it.

A tool's `:name` is lower-case letters, digits and `_`, starting with a letter,
and the served name can be at most 50 characters. An argument name is 1 to 64
characters from letters, digits, `_`, `.` and `-`. Claude Code 2.1.311 called tools whose
full `mcp__jonquil__` names ran to 70 characters, so 50 is headroom for other
clients rather than Claude Code's own limit, which wasn't found.

The tool list is read on every call, so a reload, an Accept or a disable shows
up on the client's next `tools/list`. Jonquil also tells the client when a
plugin's tools change, the startup load included, by sending
`notifications/tools/list_changed`. Claude Code 2.1.311 lists its tools again
on that and can call a new tool in the same turn (measured 2026-10-28). A
client that ignores the notification still sees the tools on its next list, or
after it reconnects. When an
agent proposes a plugin, the review names its tools and marks new ones, and
Accept refuses a plugin whose tools aren't the ones its check reported.

</details>

## Attaching to a window that is already open

`--window` starts a new window for each agent session. To hand an agent the
window you already have, with its tabs and its console, turn listening on in
`settings.edn`:

```edn
{:mcp-listen? true}
```

A window started with that setting serves the same nineteen tools on a socket
bound to 127.0.0.1, on a port the operating system picks, to one client at a
time. It publishes the port in `mcp/<pid>.edn` inside the settings directory
(next to `settings.edn`), one file per window. Then register the bridge:

```bash
claude mcp add jonquil -- jonquil --mcp-attach /path/to/project
```

`jonquil --mcp-attach [DIR]` finds the live window whose registered folder
matches `DIR` (the newest, if several do) and relays JSON-RPC lines between its own stdio and that window.
Left bare, `jonquil --mcp-attach` attaches when exactly one window is
listening. With none, or with several and no `DIR` to choose between them, it
refuses and says why, listing the live windows when there is more than one.

<details>
<summary><strong>Which folder it matches, and detaching</strong></summary>

The folder a window registers is the one it opened on. A window with no file
tree root registers its working directory. Open Folder re-roots the window and
its registration together, so attach matches the folder the window shows now,
and a client that attaches after the move is served that folder.

Detaching leaves the window running, with its tabs and unsaved edits as they
were, which is the difference from `--window`, where EOF closes the window. If
the relay loses its connection mid-stream it writes one line to stderr and
exits. The window removes its entry when it quits, and an entry left behind by
a crash is recognized by its dead pid and skipped. `bb mcp:smoke --attach`
exercises all of this end to end.

</details>

**Trust.** While `:mcp-listen?` is on, any local process that can reach the
loopback interface gets unauthenticated code execution in your account, plus
the window's file reach. That is the same family of exposure as running any
local nREPL server. The default is off, and nothing listens unless you turn it
on.

## Trust boundary

Whoever can start `jonquil --mcp` can run code as you. Evaluating is the point
of the tools, and nothing authenticates the caller. That is the model of MCP
over stdio: the client controls what it spawns, so you already decide who gets
to launch the server. It is also the same reach `bb nrepl-server` gives. Don't
put it behind anything that lets someone else choose its command line or feed
its stdin.
