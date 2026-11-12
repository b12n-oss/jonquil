# Driving Jonquil from an agent (MCP)

`jonquil --mcp [DIR]` runs Jonquil with no window as an MCP server on stdio
(one JSON-RPC message per line). An agent evaluates code, runs files and looks
things up in the runtime a person would get in the IDE. `DIR` is the default
project directory (the working directory if omitted):

```bash
claude mcp add jonquil -- jonquil --mcp /path/to/project
# from a checkout:
claude mcp add jonquil -- jolt -M -m jonquil.main --mcp /path/to/project
```

Six tools. Most take an optional `path` selecting the project (default
`DIR`); `jonquil_run_file` requires a file `path`, and `jonquil_check_brackets`
takes `text` or a `path`.

<details>
<summary><strong>The six tools</strong></summary>

| Tool | What it does |
|---|---|
| `jonquil_eval` | Evaluates code as a REPL line; definitions persist |
| `jonquil_run_file` | Runs a file's text like the Run button |
| `jonquil_check_brackets` | Names the first unclosed or unmatched bracket |
| `jonquil_doc` | Documentation for a symbol |
| `jonquil_complete` | Up to 50 completion candidates for a prefix |
| `jonquil_project` | The dialect, runtime and directory the other tools would use; starts nothing |

</details>

Dialect and runtime follow the IDE's own choice: the file's extension and the
project's `.jonquil.edn` (see [Choosing a dialect](dialects/index.md#choosing-a-dialect)
and [`.jonquil.edn`](dialects/index.md#jonquiledn)). A Jolt project with a
`deps.edn` evaluates in a Jolt child, `{:runtime :in-process}` in the server's
process, and `{:dialect :clj}` attaches to its nREPL server.

- `jonquil_eval` and `jonquil_run_file` take `timeout_ms` (default 30 s). On
  expiry the evaluation is interrupted and the tool errors; 5 s later, if it
  still runs, that runtime is stopped and the next call starts a fresh one.
- Closing stdin ends the server and its runtimes.
- With an out-of-process runtime, `jonquil_doc` and `jonquil_complete` use the
  editor's offline tables until a first evaluation starts the runtime.

## Window mode

`jonquil --mcp --window [DIR]` opens the normal window and serves the protocol
on stdio, so you watch the agent work:

```bash
claude mcp add jonquil -- jonquil --mcp --window /path/to/project
# from a checkout:
claude mcp add jonquil -- jolt -M -m jonquil.main --mcp --window /path/to/project
```

With `--window` the server offers twenty tools (headless keeps six), acting
on the live window:

<details>
<summary><strong>The twenty window tools</strong></summary>

| Tool | What it does |
|---|---|
| `jonquil_eval` | Evaluates as a console entry: echoed, with `*1` and history |
| `jonquil_run` | Runs the active tab like the Run button |
| `jonquil_run_selection` | Selects `start`..`end` offsets (visibly) and runs them like Cmd/Ctrl+E |
| `jonquil_stop` | Interrupts the running evaluation; reports whether it stopped within 5 s |
| `jonquil_open` | Opens a file as a tab, or selects its tab; it becomes active |
| `jonquil_tabs` | One line per tab: id, active marker, dirty or saved, path or Untitled |
| `jonquil_select_tab` | Makes the tab `id` active |
| `jonquil_read_tab` | The tab's full text (active tab without `id`) |
| `jonquil_replace_text` | Replaces the tab's text as one undoable edit; the tab reads dirty. Text holding a NUL byte is refused with an error naming the line |
| `jonquil_save` | Saves the active tab to `path` or its own path |
| `jonquil_console` | `mark <N>` then the output since the `since` mark; pass back `N` to read only what's new |
| `jonquil_doc` | Documentation for a symbol, from the window's evaluator |
| `jonquil_complete` | Up to 50 completion candidates, one per line |
| `jonquil_check_brackets` | "ok", or the sentence naming the first problem |
| `jonquil_project` | The window's live dialect selection: label, runtime, directory, source, warnings |
| `jonquil_plugins_list` | One line per plugin: version, loaded or disabled, effects, pending proposal, doc |
| `jonquil_plugin_read` | A plugin's live source and history, or one `version`'s source |
| `jonquil_plugin_propose` | Proposes whole new `source` for `id` with a `reason`; answers pending, rejected-by-checks or refused |
| `jonquil_plugin_status` | A proposal's status (latest without `n`) and your note if rejected |
| `jonquil_change_set_propose` | Saves a named list of `forms` (each a `path` and the form's `text`) as a change set marked as yours, for you to apply or revert |

</details>

Every call shows in the window's [Messages panel](help-and-inspector.md#messages), named by the client, so you can see afterwards what the agent did.

<details>
<summary><strong>Timeouts, other projects' tabs, disconnecting and trust</strong></summary>

`jonquil_eval`, `jonquil_run` and `jonquil_run_selection` take `timeout_ms`
(default 30000). A program that stops to read stdin makes the call return early
with what it printed; type into the console or call `jonquil_stop`.

The window serves its launch project. Run on another project's tab is
refused, though `jonquil_run_selection` still moves the visible selection.

When the client disconnects (EOF on stdin) the window closes and unsaved edits
are discarded, so have the agent `jonquil_save` first. `jolt mcp:smoke --window`
exercises all of this end to end.

The trust boundary is the headless server's, plus the files the window can
reach: the agent can open, edit and save any file you can.

</details>

<details>
<summary><strong>An agent proposing a plugin</strong></summary>

`jonquil_plugin_propose` never runs the agent's code in Jonquil. It is checked
first in a separate jolt process (isolation, not a sandbox; see
[Plugins](plugins.md)), then waits as a pending proposal that you decide in
Review Plugin Proposals. No tool can accept or reject one; the agent reads your
decision, and your reject note, with `jonquil_plugin_status`.

A proposal is refused before any check when its id isn't a plugin id, its
reason is blank or over 4000 characters, its source is over 262144, or the
window runs with `--no-plugins` (the reading tools still answer). A propose can take most of a minute
(30 s check, busy window); if it doesn't finish, the answer is
`status: unknown` and `jonquil_plugin_status` says whether it was recorded. If
a newer proposal replaces the one you're reading, the next Accept is refused;
press it again after reading the new one.

</details>

<details>
<summary><strong>An agent proposing a change set</strong></summary>

`jonquil_change_set_propose` takes a `name` and `forms`, each form a `path`
(absolute, or relative to the window's folder) and the `text` of one
top-level form that replaces the form with the same first two elements in that
file (a later entry of the same two elements replaces the file's next such
form). Each path is resolved to its real path and must name an existing,
readable Clojure file (`.clj`, `.cljc`, `.cljs`, `.cljd` or `.bb`) inside the
window's folder, or the call is refused with the reason. The set is saved
beside the settings file, marked with the client's own name (control
characters dropped, at most 60 characters), and the window shows it as "a
client calling itself" that name. A line goes to the console and the status
bar. The call applies nothing and edits no file or tab: **Change Sets** in the
palette lists it, the panel shows each form as a diff of the file's current
form against the proposed text, and only you press Apply to REPL or Delete.
There is no Revert for a proposed set. A name that is already taken is
refused, and the file is created only if the name is free, so a proposal never
replaces one of your sets, even when two windows race. It is also refused when
`forms` is empty or holds more than 200 entries, an entry isn't exactly one
balanced form, or the texts together pass 262144 characters.

</details>

<details>
<summary><strong>A plugin's own tools</strong></summary>

A plugin gives agents tools in `:mcp-tools`:

```clojure
:mcp-tools [{:name "twice" :doc "The text twice."
             :args {"text" {:type "string" :doc "The text." :required? true}}
             :run (fn [ctx args] (str (get args "text") (get args "text")))}]
```

A window serving MCP (`--mcp --window`, or one you attach to) serves each live
plugin's tools as `plugin_<id>_<name>` after its twenty: `plugin_smoke-t_twice`
for plugin `smoke-t` above.

- Argument types: `string`, `integer`, `number`, `boolean`. A missing or
  wrong-typed required argument is refused before `:run`.
- `:run` returns a string or `{:text "..." :error? true}`. It runs with only
  the plugin's declared effects, gets 30 s, and runs one call at a time per
  tool (a call still going at 30 s keeps the tool busy).
- Tool `:name`: lower-case letters, digits, `_`, starting with a letter; the
  served name is at most 50 characters. Argument names: 1 to 64 characters of
  letters, digits, `_`, `.`, `-`.

Jonquil sends `notifications/tools/list_changed` when a plugin's tools change;
a client that ignores it sees them on its next list or after reconnecting.
Accept refuses a plugin whose tools differ from what its check reported.

</details>

## Attaching to a window that is already open

To give an agent the window you already have, with its tabs and console, turn
listening on in `settings.edn`:

```edn
{:mcp-listen? true}
```

That window serves the same twenty tools on a socket bound to 127.0.0.1, on
a port the OS picks, to one client at a time. It publishes the port in
`mcp/<pid>.edn` beside `settings.edn`. Then register the bridge:

```bash
claude mcp add jonquil -- jonquil --mcp-attach /path/to/project
```

`jonquil --mcp-attach [DIR]` finds the live window whose folder matches `DIR`
(the newest if several) and relays JSON-RPC between its stdio and that window.
Bare, it attaches only when exactly one window is listening; otherwise it
refuses and says why.

<details>
<summary><strong>Which folder it matches, and detaching</strong></summary>

A window registers the folder it opened on; Open Folder re-roots both.

Detaching leaves the window running with its tabs and unsaved edits, unlike
`--window`, where EOF closes it. If the relay loses its connection it writes a
line to stderr and exits. A crashed window's leftover entry is recognized by
its dead pid and skipped. `jolt mcp:smoke --attach` exercises all of this end to
end.

</details>

**Trust.** While `:mcp-listen?` is on, any local process that can reach the
loopback interface gets unauthenticated code execution in your account, plus
the window's file reach, the same exposure as any local nREPL server. It is off
by default and nothing listens unless you turn it on.

## Trust boundary

Whoever can start `jonquil --mcp` can run code as you; nothing authenticates
the caller, as with any MCP stdio server and `bb nrepl-server`. Don't put it
behind anything that lets someone else choose its command line or feed its
stdin.

What an agent evaluates in a window isn't recorded for the
[last session's replay](using-jonquil.md#last-sessions-replay), but the code
it sends does land in the console's Up/Down history, and that history is
saved to disk with the replay's record (owner-only, in `replays/` beside the
settings file). `:remember-session? false`, in the settings file or in the
project's `.jonquil.edn`, keeps it off disk.
