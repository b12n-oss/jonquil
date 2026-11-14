# Commands, keys and plugins

Part of the [architecture notes](index.md).

The full design record, with the reasoning and what was ruled out, is kept in `docs/history/architecture/commands-keys-plugins.md`.

Keys and commands are data, and plugins add to that data while Jonquil runs.

## The keymap

`jonquil.keymap` matches a lowered keyval and a GDK modifier state against an ordered vector of bindings. The first match wins. A binding names:

- `:key` or `:keys`, with `:mods` that must be held and `:not` that must be up. `:cmd` means Control or Meta on every platform.
- `:exact #{...}` instead, for exactly that set among Shift, Alt, Control and Meta.
- `:alt` among its modifiers, which also lets it match the key the hardware keycode names. On macOS Option changes the keyval (Option+S arrives as ß), so `jonquil.gtk.editor/physical-key` names the key from Apple's keycode table, which is a US layout's. A binding that doesn't name `:alt` never matches that way. `:physical? true` opts a binding in by hand and is no longer needed.
- `:when`, named conditions that must all hold. They are asked only after key and modifiers match, in order.
- A command id, or a fixed `:outcome`.

A command answers `:handled` (key used up), `:to-gtk` (let GTK have it) or `:next` (try the next matching binding). `dispatch` returns `:unmatched` when it runs out. The Escape chain (find bar, popovers, inspector, source panel, tree menu, tree, Stop, bare `:to-gtk`) is ordinary bindings in that order.

The live key map of a scope is three layers, first match winning: your own bindings from `keys.edn` (`jonquil.user-keys`), then the plugins', then the shipped ones. `keys.edn` also has a `:remove` list, which drops a plugin's or a shipped binding named by chord and command. A bad entry is skipped and named in the console, and a file that doesn't read loads nothing. `jonquil.keymap/typing-key?` is the one rule for a key that types or moves the caret (everything but the function keys and Escape): a binding on one needs a modifier other than Shift, in `keys.edn` and in the Keys tab alike.

There are two scopes. `window-answer` is the whole of `on-window-key`: a fixed stage lets the focused rename or palette entry keep every key but Escape (`:entry-focused`), then the keymap runs. `editor-answer` runs the preedit yield, the completion popup, the `:editor` keymap, and only then the typing stages (smart enter, auto-pairs, Backspace, Tab). Invariant: no shipped binding may match a plain Return.

## Commands and the palette

`jonquil.command-registry` maps a command id to a doc line and a `(fn [ctx args])`. The ctx is the map the key handler already receives (`window-key-ctx`, `editor/key-ctx`; the editor's holds thunks), so a command is a plain function a test can call and the registry never touches GTK.

- `:palette? false` keeps a command that only works inside its guarding key chain out of the palette.
- Context menus (`editor-menu`, `console-menu`, `menu-items`) list label, action and command, so an item and its chord run one command. The file tree's menu is a file-operations popover instead.
- Shipped bindings live in `src/jonquil/keymap.edn`, read at compile time because the installed app has no source tree.
- `jonquil.palette` (Cmd/Ctrl+Shift+P) is pure: it builds entries from the registry and keymap, filters, and moves the selection. `run-command-in` builds the same ctx the command's key would.
- `jonquil.structural` holds the structural editing commands as pure functions from a snapshot to an edit op, over `jonquil.commands/form-tree`. The registry applies an op through the editor's `:apply-structural-op!`, which runs that one edit under Parinfer's Paren mode, so Parinfer follows the moved bracket and doesn't move it back.
- `keymap_shipped_test` pins the shipped keymap: every binding names a registered command and condition, Shift forms sit ahead of plain ones, window and editor chords don't overlap, and the Escape chain keeps its order.

## Preferences and the menus

`jonquil.key-prefs` is the Keys tab as data: its rows from the live key map, the chord a key press stands for, the binding a chord would take a key from, and your `keys.edn` after a recording or a reset. `jonquil.gtk.prefs-window` builds the widgets and `jonquil.gtk.parts.prefs` is the part. The tab writes `keys.edn` whole and has the command palette part reload it, so the tab and a hand edit go through one loader.

`src/jonquil/menus.edn` is the menu layout, read at compile time, and `jonquil.menus` checks it against the registry and gives each item an action name and the first key that runs its command under no condition. `jonquil.gtk.menus` builds a GMenu and one action per command, in a group the window holds under the prefix `menu`. macOS shows the model as the system menu bar and Linux behind a menu button. The part rebuilds the model when your keys or the plugins change.

A menu item that shows a key also fires on that key on macOS, but only when the window's key handler passes the key on; a key the handler answers never reaches the menu (measured with a probe window). The handler passes keys on deliberately, to a focused entry or to the editor. So before it does, for any key a menu could show, it calls the part's `:menu-yield-fn`: the action group comes off the window, GTK finds no action and hands the key to the focused widget, and the group goes back on idle.

## Plugins

A plugin is `<settings-dir>/plugins/<id>.clj`. The data-and-checks half has no GTK and no live state: `jonquil.plugin` (`defplugin`), `plugin-check` (validator), `plugin-protocol` (load, soak, validate), `plugin-history`, `plugin-list`, `plugin-review`. The live half is `plugin-registry` (the `live` atom, effects, bindings), `plugin-loader` (every step), `plugin-hooks`, the isolated check (`plugin-isolated`, `plugin-checker`, `plugin-checker-files`) and `plugin-mcp`.

### The plugin file and validation

`defplugin` records a definition with its line while a load binds `*definitions*`, and throws at any other time. `plugin-check/validate` takes plain data (definitions, id, registered commands, condition ids, shipped keymap, OS) and answers `{:ok? :problems :warnings}` without throwing, so the isolated check can send the same request to a throwaway Jolt as EDN. It checks the id, effects, commands, hooks and `:mcp-tools` rows (served name `plugin_<id>_<name>`, capped at 50 characters).

- Refused: a binding that takes a key you type (a printable key, Return, Tab or Backspace without Ctrl or Meta), and an `:editor` binding the window always takes first.
- Warned, naming the command: a chord a built-in uses, or one the window takes only sometimes. `:passes? true` on `:popover/close-all`, `:source/jump-back` and `:find/bar-key` marks the second kind.

### Protocol

`plugin-protocol/run-protocol` is the one function every change to the live set goes through. It loads the candidate (a reader or compiler error is named with its line), validates it, and soaks each `:edit` command on six fixture snapshots, checking each result's op shape. Limits: 2 s per soak. A command that times out is reported once and its other fixtures are skipped, because `future-cancel` can't stop CPU-bound code in process.

### Registry and live swap

`plugin-registry` derives the plugins' `:edit` commands and per-scope bindings in id order. Each controller dispatches plugin bindings ahead of the shipped ones, so with no plugins the tables are the shipped ones. A command checks its op against the live buffer first, and any failure names the plugin.

`plugin-loader/reload` diffs files against the live registry:

- An unchanged file is left alone.
- A new or changed one goes through the protocol against the plugins sorting before it.
- A failure keeps the old entry and reloads its accepted source.
- A gone file, or an id in `:plugins {:disabled #{...}}` in `settings.edn`, is removed.
- Later plugins are validated again, since their keys can depend on earlier ones.

Loads happen at window open, from Reload Plugins in the palette, and from a saved plugin file (a hook in `save-to!`). `--no-plugins` and `JONQUIL_NO_PLUGINS=1` skip them and every step refuses with `plugins-off`. After each step `sync-plugin-tabs!` refreshes tabs on plugin files through `tabs/disk-sync`: a clean tab reloads, a dirty one keeps its edits, is warned once, and takes the disk text as its saved baseline (`editor/rebase-saved!`).

### Effects

`:run` commands act through effects the plugin declares. `plugin-registry/commands` gives each run a ctx holding only its declared effects' functions (`jonquil.plugin` checks the declaration on every call), one run per command at a time, on a future. Each effect is a `gui-turn` on the GTK thread against the tab captured at call time, abandoned after 5 s so a call reported as timed out never lands later. `:eval` uses `ev/eval!` with `:quiet` (no history entry, `*1` `*2` `*3` `*e` and the prompt namespace unchanged).

### Hooks

`plugin-hooks` has `:after-save`, `:after-run`, `:on-error` and `:before-run`. `run-hooks!` threads an event through each plugin's hook in id order on one future per point; events queue while a point is busy, up to 16, then are skipped with a line. `:before-run` is asked on the GTK thread, waits at most 100 ms, and every effect throws in it, since no GTK turn can happen while that thread waits.

### History and revert

`plugin-history` keeps versions under `plugins/.history/<id>/` (`<n>.clj` plus a `history.edn` vector rewritten whole through an atomic move, never overwritten when unreadable). The history is append-only: a status change (`:superseded`, `:accepted`, `:rejected`) is a short entry with the same `:n` appended, so a version's status is its last entry's.

`revert-step!` sends an old version through `reload`, writes `<id>.clj` only on a pass, and records a `:revert` version. It refuses a version that was never live, so a proposal can't skip Accept that way.

### Proposals, check and Accept

An agent's proposal is a history version with `:source :agent`, `:pending` or `:rejected-by-checks`, recording the live version it was `:based-on`. A pending one supersedes an older pending one.

`plugin-isolated/check!` runs off the plugin agent. It writes the request and candidate to an owner-only temp folder and starts `jolt` there with credential-named variables scrubbed. The child runs `plugin-checker` and prints one EDN result between `__JONQUIL_CHECK__` and `__END_CHECK__`. It may require only the embedded namespaces and `clojure.*`.

- Limit: 30 s, then SIGKILL to the child and its processes (no SIGTERM, which would run shutdown hooks).
- A bad result, overrun or non-zero exit is a failed check; the folder is kept with its log tail and named as `:check-log`, and `leftovers/prune!` removes it after a day.
- With no `jolt` the answer is `:unchecked? true`.
- The child is isolation, not a sandbox: it runs as you, and the scrub is by variable name. The result is advisory since the candidate can forge it.

`accept-step!` runs the protocol again in process and goes live only if that passes and, for a checked proposal, the summary of what loaded (id, doc, effects, commands, bindings, hook points, `:mcp-tools` rows) equals the reported one. The compare runs after the proposal's top-level code has already run, so it stops a misreported plugin from going live, not from running. Pressing Accept is the trust decision. It refuses a missing source, a disabled plugin, and a live plugin whose file changed or failed its checks, and every refusal leaves file, live and status as they were.

### Panels

`plugin-review` turns each pending proposal into summary lines (new effects and tools marked, warnings and timings labelled as the check's report) and a line diff against live. `plugin-list` builds one row per plugin from the registry, files and histories, and `jonquil_plugins_list` prints those same rows, so the panel and the tool agree. `review-pane`, `plugin-list-pane` and `plugin-history-pane` share the right-hand slot.

A refresh that swaps the shown proposal for a newer version of the same plugin arms a one-time refusal of Accept, so a click aimed at what the user read never accepts what an agent swapped in. Enable and Disable go through `set-enabled-step!`, which answers "already disabled" without writing, and `settings/save-plugin-disabled!` merges inside `:plugins` so `:trusted-projects` survives.

### The plugin agent and threading

Every step (load, Accept, Reject, Enable, Disable, Revert, opening a review) runs through `plugin-step!` on one agent that keeps going after a throw, so steps never interleave and none is lost. The agent builds the reviews and rows off the GTK thread; the callback, which the plugins window part sets at startup, then updates the pending count and any open panel. The isolated check runs off the agent, and `propose-step!` records its result on it.

### Plugin MCP tools

`plugin-mcp` produces the text for `jonquil_plugins_list`, `jonquil_plugin_read`, `jonquil_plugin_propose` and `jonquil_plugin_status`. Every id is checked with `plugin-loader/plugin-id?` before it becomes a path. A proposal is refused before any check for a bad id or reason, a reason over 4000 characters, a source over 262144, or plugins off.

`jonquil_plugin_propose` holds the one-request-at-a-time serve loop for up to 30 s of check plus 20 s for the agent; past that the answer is `status: unknown`.

A plugin's own `:mcp-tools` are served as `plugin_<id>_<name>` through `plugin-registry/mcp-tools` and `mcp-window/with-plugin-tools`. A call checks its arguments, runs `:run` on a future with the declared effects (a GTK turn for the active tab only for a `:buffer` tool), and answers within 30 s, one call per tool at a time.

Clients are told when tools change. `serve!` takes `:subscribe`, a fn that gets a `notify!`. `notify!` sends nothing before `notifications/initialized`, sends on a future so a GTK-thread reload never waits on a client, and coalesces to one send in flight and one queued. `plugin-registry/watch-tools!` calls it when the served definitions differ, and a throw there never reaches the loader's `reset!`. `initialize` declares `"listChanged" true` only where this is wired, not in headless `--mcp`.
