# Commands, keys and plugins

> **Archive.** This is the full design record for this part of Jonquil, as it stood on 2026-10-29: the reasoning behind each choice, how it was checked, and what was ruled out, step by step. The current, shorter description is [docs/guide/architecture/commands-keys-plugins.md](../../guide/architecture/commands-keys-plugins.md). This copy isn't kept up to date, and it isn't part of the docs site.

Part of the [architecture notes](index.md).

Keys and commands are data, and plugins add to that data while Jonquil runs.
Part 1 built the keymap and the command registry, and Part 2 built plugins on
top of them. Both are kept here slice by slice, as the record of why each piece
is the way it is.

## Part 1: the keymap and the command registry

- `jonquil.keymap` and `jonquil.command-registry` (plugins Part 1, slice
  S1, 2026-10-24): key bindings and commands as data. `jonquil.keymap`
  matches a lowered keyval and a GDK modifier state against an ordered
  binding vector, first match wins. A binding names `:key` (or `:keys`),
  `:mods` that must be held and `:not` that must be up, with `:cmd`
  meaning Control or Meta on every platform. Anything in neither set is
  ignored. `jonquil.command-registry` maps a command id to a doc line and
  a `(fn [ctx args])`, where ctx is the map the window's key handler
  already receives, so a command is a plain function a test can call. The
  shipped bindings live in `src/jonquil/keymap.edn`, read when Jonquil is
  compiled, because the installed app has no source tree. S1 dispatched
  them through one **slot** clause in `on-window-key`'s cond, first placed
  where the plain chords used to be, so every clause above and below kept
  its precedence. Later slices grew the slot upward until the cond was
  gone. A differential oracle checked the keymap against a
  transcription of the replaced clauses, for every key they named and all
  32 modifier combinations. Slice S2 (2026-10-24) added `:when`: a vector
  of named conditions, all of which must hold. `resolve-binding` asks them
  through a `holds?` fn only once a binding's key and modifiers match, in
  order, stopping at the first false, the short-circuit order of the `and`s
  it replaces. Conditions live beside the commands (`window-conditions`) as
  functions over ctx. `on-window-key` builds a per-key ctx, its own
  argument map plus the active tab's find-bar reads and actions as fns, so
  the registry never touches GTK. S2 moved F3 and Cmd/Ctrl+G (while the
  find bar can navigate), plain F6 and the source panel's
  Cmd/Ctrl+Shift+O into the slot, which then sat right below the bare
  Escape clause. Its oracle crossed every key and modifier set with each
  combination of the three states those clauses read. Slice S3
  (2026-10-24) gave commands an outcome: `:handled` (the key is used up),
  `:to-gtk` (stop here and let GTK have it) or `:next` (not mine after
  all, try the next matching binding). A binding can name a fixed
  `:outcome` instead of a command. `dispatch` walks the matching bindings
  and runs each command until one answers `:handled` or `:to-gtk`, and
  says `:unmatched` when it runs out. That let the whole Escape chain move
  into the keymap, in its old order: the find bar (whose own 0, an IME
  composing, is `:to-gtk` and never reaches Stop), popovers (`:next` when
  none was open), the inspector, the source panel, the tree menu, the
  tree, Stop while evaluating, and a bare `:to-gtk`. The slot then sat
  right after F5, and its oracle crossed Escape with all eight states the
  chain reads. Slice S4 (2026-10-24) retired the cond. F5 became the
  first binding and the two Cmd/Ctrl+[ back clauses the last, the jump
  back answering `:next` when there's nothing to go back from. The rename
  guard (while the rename popover's entry has focus, every key but Escape
  is the entry's; since S8's follow-up the command palette's entry too)
  covers a class of keys, so it stays a fixed stage ahead of the
  keymap, as the spec keeps the editor's preedit yield, and asks the named
  condition `:rename-focused` (since plugins P3 `:entry-focused`, as it
  covers the command palette's entry too). `window-answer` holds that stage and the
  dispatch and answers 1 or 0. It is the whole of `on-window-key` now,
  and the oracle drove it over the full handler's transcription. Slice
  S5 (2026-10-25) started on the editor. Its eleven chords, Ctrl+Space
  through F2 (completion, comment, the two formats, redo, quick doc, jump
  and rename), are an `:editor` scope with its own `editor-commands` and
  `editor-conditions`, dispatched from one slot in the editor's
  `handle-key!` between plain Return and the typing clauses. Two matcher
  forms came with it. `:exact #{...}` needs exactly that set among Shift,
  Alt, Control and Meta, ignoring Super, for Shift+F1, Shift+F6, F12 and
  F2. `:physical? true` lets a binding also match through dispatch's
  `:physical` option, the key the hardware keycode names. That's the
  macOS Cmd+Option+F/L case, where Option turns the keyval into another
  character (`jonquil.gtk.editor/physical-key`). It is opt-in per binding,
  so no other chord picks it up. The editor ctx holds thunks, so each
  command reads the editor's state when it runs, and the slice's oracle
  crossed every key the clauses named with three keycodes, both
  platforms, all 32 modifier sets and every command answer. Slice S6
  (2026-10-25) finished the editor, and its cond is gone.
  Cmd/Ctrl+Enter (Run Form) is the first `:editor` binding, Return or
  KP_Enter with `:cmd`, always used up so no newline goes in.
  `editor-answer` runs the spec's pipeline and answers 1 or 0: the preedit
  yield asks the named condition `:preedit-active`, then the completion
  popup gets first look through a thunk, then the keymap, and only an
  unmatched key reaches the typing stages (smart enter, auto-pairs,
  Backspace, Tab), the editor's private `typing-key!`. Plain Return moved
  from above the chords to the head of those stages, which is safe because
  no shipped binding can match a plain Return. A second oracle proved it,
  comparing the whole of `handle-key!` against its old cond over every
  stage answer. Slice S7 (2026-10-25) moved the context menus
  onto the registry. `editor-menu` and `console-menu` list each item's
  label, its action name and the command it runs, and `menu-items` turns
  a menu and a ctx into the items `install-context-menu!` takes, so an item
  and its chord run one command. Indent, Unindent and the console's
  Complete became commands with no binding. A menu oracle compared both
  menus with the literal vectors they replaced. The file tree's menu stays
  a file-operations popover, since its items act on a path picked when it
  opens and aren't commands anywhere else. Slice S8 (2026-10-25) added
  the command palette, Part 1's one new piece of UI, on Cmd/Ctrl+Shift+P.
  `jonquil.palette` builds its entries from the registry and the keymap
  (doc line, chords per platform, source), filters them and moves the
  selection, all pure. `run-command-in` runs a picked command with the
  ctx of its own scope, and the two per-key ctxs are named functions now
  (`window-key-ctx`, `editor/key-ctx`), so a palette run builds the same
  ctx its key would. Commands that only work inside the key chain that
  guards them carry `:palette? false` and stay out of the list. Slice S9
  (2026-10-25) closed Part 1 by deleting the oracles, as the spec plans,
  once every slice had merged with its oracle green and every self-test
  row unchanged. The shipped keymap is now pinned by `keymap_shipped_test`
  (every binding names a registered command and condition, what each
  window chord runs with its Shift forms ahead of the plain ones, that no
  window binding takes an editor chord and no editor binding takes a plain
  Return, Run Form's chords, F5, the Escape chain's order and conditions,
  the palette chord, and each context-menu item's command) and by the
  window self-test rows.

## Part 2: plugins

- Plugins at a glance (Part 2, slices P1 to P11c, 2026-10-25 to
  2026-10-28). The half with no GTK and no live state is data and checks: `jonquil.plugin` (the
  `defplugin` API), `plugin-check` (the validator), `plugin-protocol` (load,
  soak and validate one candidate), `plugin-history` (the version files),
  `plugin-list` (the list's rows and a plugin's version lines) and
  `plugin-review` (a proposal's review). The live side is
  `plugin-registry` (the registry atom, the effect functions and the
  bindings that reach the keymap), `plugin-loader` (every step: load,
  reload, revert, propose, Accept, Reject, Enable and Disable) and
  `plugin-hooks`. An agent's proposal is checked by `plugin-isolated`, which
  starts a throwaway `jolt` running `plugin-checker` from the files
  `plugin-checker-files` writes. `plugin-mcp` is the four plugin MCP tools.
  In the window, `jonquil.gtk.review-pane`, `plugin-list-pane` and
  `plugin-history-pane` share the right-hand slot, and every step runs on
  one plugin agent (`plugin-step!`), so no two ever interleave. The bullets
  below are the slice-by-slice record.
- `jonquil.plugin` and `jonquil.plugin-check` (plugins Part 2, slice P1,
  2026-10-25): the plugin API's first piece and the validator, both pure,
  with nothing loaded from disk yet. `jonquil.plugin/defplugin` records a
  plugin file's definition, with its line, while a load binds
  `*definitions*`, and throws at any other time. `plugin-check/validate`
  takes plain data (the definitions, the file's id, the registered commands
  as `{id {:scope :passes?}}`, the condition ids, the shipped keymap and
  the OS) and answers `{:ok? :problems :warnings}`, one sentence each, never
  throwing, so the isolated check can send the same request to a
  throwaway Jolt as EDN. It checks the definition, the id, the effects, the
  commands and the hooks. It refuses a binding that would take a key you
  type (a printable key, Return, Tab or Backspace with neither Ctrl nor
  Meta held, in either scope, since the window sees keys first) and an
  `:editor` binding the window always takes first. A chord a built-in
  uses, or one the window takes only sometimes, is a warning naming that
  command. `:passes? true` on the three window commands that can hand the
  key on (`:popover/close-all`, `:source/jump-back`, `:find/bar-key`) is
  how it tells the two apart. Slice P2 (2026-10-25) added the API's first
  helpers, `form-at-caret` (the form Run Form would send) and `wrap-form`,
  and `jonquil.plugin-protocol`, the one function every change to the live
  plugin set will go through. `run-protocol` loads a candidate file
  (`load-string` with `*ns*` and the collector bound, a reader or compiler
  error named with its line, the namespace checked), validates it, and
  soaks each `:edit` command on six fixture snapshots under a 2 s timeout,
  checking each result's op shape. The shape check runs inside the timed
  call with printing bounded, so a lazy or endless result can't hang it. A
  command that doesn't answer in time is reported once and its other
  fixtures are skipped, since `future-cancel` can't stop CPU-bound code in
  process (an agent's proposal is soaked in a separate process later).
  Nothing goes live yet. Slice P3 (2026-10-25) made plugins live.
  `jonquil.plugin-registry` holds them in one atom, `live`, and derives the
  plugins' `:edit` commands as registry commands and their bindings per
  scope in id order. Each controller dispatches the plugin bindings ahead
  of the shipped ones, so with no plugins the tables are exactly the
  shipped ones. A plugin command checks its op against the live buffer
  before applying it, and a throw, a bad op or a failed apply names the
  plugin. `jonquil.plugin-loader` finds `<settings-dir>/plugins/<id>.clj`,
  runs the protocol on each file in id order (each load under a deadline,
  so a file that loops at top level is skipped too), and checks each one
  against the built-ins plus the plugins already loaded, so a chord an
  earlier plugin took is warned about. The window loads plugins once it
  opens (since P4, on the load agent, which resets `live`);
  `--no-plugins` and `JONQUIL_NO_PLUGINS=1` skip that. Slice P5 (2026-10-25)
  made `:run` commands live: `plugin-registry/commands` wraps each one so
  it captures the active tab id, builds a ctx holding only its plugin's
  declared effects' functions (`jonquil.plugin` checks the declaration on
  each call), runs on a future one run per command at a time, and reports
  a throw with the plugin's id. The functions come from
  `plugin-registry/effects`, which the window sets; each is a
  `plugin-registry/gui-turn` on the GTK thread against the tab with that
  id (like `mcp.window/gui-call`, except a turn that missed its 5 s
  deadline is abandoned, so a call reported as timed out never lands
  later). Slice P5b (2026-10-26) added the `:eval` effect. `ev/eval!`
  takes `:quiet`, which the two Jolt runtimes honour (`ev/QuietEval`): no
  history entry, the prompt namespace and `*1` `*2` `*3` `*e` unchanged.
  In process that's a skipped `repl-vars` shift; a Jolt child gets the
  code wrapped by `ops/quiet-code`, which binds `*ns*` to the buffer's
  namespace around a `load-string`, pre-shifts `*1` `*2` as
  `keeping-history` does, and prints the value after a random token, which
  the evaluator splits off before anything reaches `on-output`.
  `plugin-registry/await-eval!` waits on the plugin's worker and stops an
  eval that times out. Slice P6 (2026-10-26) added the hooks that watch:
  `:after-save`, `:after-run` and `:on-error`, in `jonquil.plugin-hooks`.
  The window tags the opts of the evals that count with `:hook-source`
  (`:run :selection :form :repl`) and the tab, and `hooked-opts` chains
  their `:on-complete` into `plugin-hooks/after-eval!`; `save-to!`'s
  `on-saved` callback fires `:after-save`. `run-hooks!` threads the event
  through each plugin's hook in id order on one future per point, with a
  ctx built by `plugin-registry/effect-ctx` as a `:run` command's is.
  Events that come while a point is busy queue on that future, up to 16,
  and past that are skipped with a line. Slice P6b (2026-10-26) added
  `:before-run`: `do-run!`, `do-run-selection!`, `mcp-run-selection!` and
  `do-run-form!` call `before-run-stopped` when the Run would start, which
  asks `plugin-hooks/before-run!` on the GTK thread. That runs the chain
  on a future and waits up to 100 ms; the ctx carries `::p/refused`, so
  every effect throws there (no GTK turn can happen while the GTK thread
  waits), and a busy flag keeps an overrun chain to one. Slice P7 (2026-10-26)
  added history: `jonquil.plugin-history` keeps each live version under
  `plugins/.history/<id>/` (`<n>.clj` and a `history.edn` vector,
  rewritten whole through an atomic move, never overwritten when it
  can't be read). `plugin-loader/load-step!` records every plugin whose
  source changed after its `reset!`, unless the text is already the
  latest version, so a relaunch adds nothing. `revert-step!` sends an
  old version through `reload` with `:text` (the same protocol and
  re-check of later plugins), and only on a pass writes `<id>.clj`,
  resets the registry and records a `:revert` version; the plugin list's
  History calls it (P11b). Slice P8 (2026-10-26) built the checker an
  agent's proposal will be checked with, short of starting it:
  `jonquil.plugin-checker` reads `request.edn` and `candidate.clj` from its
  working folder, runs `run-protocol :in-process` in that (child) process,
  prints one EDN result between `__JONQUIL_CHECK__` and `__END_CHECK__`
  lines, and calls `System/exit`, since a soak thread it gave up on would
  otherwise keep `jolt -e` alive. `jonquil.plugin-checker-files` embeds it
  and the eleven namespaces it loads (plus `keymap.edn`) at compile time
  and writes them under `~/.cache/jonquil/plugin-checker/<hash>` through
  `middleware-files/ensure-root!`, which now takes a file set.
  `jonquil.plugin-isolated` builds the request text, scrubs
  credential-named variables from the child's environment, and
  `parse-result` reads the last marker pair, turning no output, garbage, a
  half-shaped result, an overrun or a non-zero exit into a failed check.
  The result is advisory: the candidate shares the checker's process and
  can forge it, so Accept (P9) checks again in process and compares. Slice
  P8b (2026-10-26) runs it: `plugin-isolated/check!` writes the request
  and the candidate to a fresh owner-only `jonquil-plugin-check-` folder,
  starts the `jolt` the out-of-process runtime finds, in that folder with
  the scrubbed environment, and waits up to 30 s. A child that overruns is
  killed with SIGKILL, along with every process it had started (taken just
  before, since they outlive it otherwise). It gets no SIGTERM first,
  because that would run a candidate's shutdown hooks, and one of those
  can start a process after the list was taken. Only the log's last MiB
  is read. When the checker printed its result the folder goes; when the
  child misbehaved (overran, printed no result or garbage, exited
  non-zero) it's kept with its log cut to that tail, the result names the
  log, and `leftovers/prune!` removes it a day later. With no `jolt` the
  answer is `:unchecked? true` and nothing is written. `check!` is the
  protocol's isolated seam (`run-protocol` itself is embedded in the
  child, so it can't start one), and `plugin-loader/propose-check` calls it (P9). The child
  is isolation from Jonquil, not a sandbox: it runs as you, with your
  files and network. The environment scrub is by name, so a credential in
  a variable called something else (a proxy URL with a password in it)
  reaches the child, and Jolt starts it through `sh -c "env -i ..."`, so
  the kept variables are briefly in that command line. A process the
  candidate starts and leaves running when its check ends on its own
  keeps running. A candidate can require only the embedded namespaces and
  `clojure.*`. Slice P9 (2026-10-27) gave a proposal its lifecycle,
  headless, and P9b put it in the window. A proposal is a version in the
  history, `:source :agent`, either `:pending` (keeping what its check
  reported: warnings, timings, the summary, and whether it was unchecked)
  or `:rejected-by-checks` (keeping the problems), and it records the live
  version it was `:based-on`. The history stays append-only: a later
  change of status (`:superseded`, `:accepted`, `:rejected` with the
  user's note) is a short entry with the same `:n` appended after it, so
  a version's status is its last entry's. A pending proposal supersedes an
  older pending one in the same write; a failing one supersedes nothing.
  `plugin-loader/propose-check` runs the isolated check against the live
  registry off the plugin agent (it can take 30 s), and `propose-step!`
  records its result on the agent and gives the console line.
  `accept-step!` runs the protocol again in process through `reload`, and
  goes live only when that passes and, for a checked proposal, the
  summary of what loaded equals the reported one; the compare sees the
  id, doc, effects, commands, bindings and hook points, not what a
  function does. The compare runs after the proposal's top-level code
  has already run in Jonquil (the in-process check loads it), so it
  keeps a misreported plugin from going live, not from running: pressing
  Accept is the trust decision, as the spec's trust boundary says, and a
  refused brand-new plugin's namespace keeps the code it loaded,
  registered nowhere. It refuses a version whose source is missing, a
  disabled plugin, and a live plugin whose file changed since it was
  loaded, or whose edit failed its checks (reload or fix it first), and every refusal leaves the file, live,
  the status and a live plugin's namespace as they were.
  `reject-step!` records the note. `revert-step!` now refuses a version
  that was never live, so a proposal can't skip Accept that way. Slice
  P9b (2026-10-27) is the window side. `jonquil.plugin-review` is pure
  apart from `pending-reviews`, which reads the history: it turns each
  pending proposal into summary lines (unchecked first when it is, where
  it stands against live, the effects with new ones marked, the warnings
  and soak timings, each labelled as what the check reported, and a last
  line saying Accept runs the code in Jonquil) and a line diff against
  live, its own LCS capped at a million cells (about half a second on
  Jolt), past which it's the whole old file removed and the new one added.
  Live is the registry's source, or the latest accepted version's when the
  plugin isn't loaded. `jonquil.gtk.review-pane` shows one review at a
  time in the right-hand slot the inspector and the source panel use. The
  window runs every plugin step (a load, Accept, Reject, opening the
  review) through `plugin-step!` on the plugin agent, which builds every
  pending review there, off the GTK thread, then sets the status bar's
  pending count and, when the panel shows, shows the same plugin's
  proposal again, or the first. The same proposal still shown keeps the
  note being typed for Reject, and an Accept's lines all go on the
  panel's footer, so a refusal says why. Nothing in the panel is an MCP
  tool. Slice P10 (2026-10-27) gives agents four of them over the window
  (`jonquil_plugins_list`, `jonquil_plugin_read`, `jonquil_plugin_propose`
  and `jonquil_plugin_status`). Their text comes from `jonquil.plugin-mcp`,
  pure apart from reading the plugins folder and its histories, which
  checks every id against `plugin-loader/plugin-id?` before it becomes a
  path and refuses a proposal before any check when its id or reason is
  bad, its reason passes 4000 characters or its source 262144, or plugins
  are off. The window gives the tool table two ui keys: `:plugin-state*`
  (the plugins folder, the live registry and the disabled ids, read on
  every call) and `:plugin-propose!*`, which runs the isolated check on
  the MCP serve thread, then `propose-step!` through `plugin-step!`, so
  the count and an open panel refresh as after any step. The serve loop
  takes one request at a time, so a propose holds it for up to the check's
  30 s plus up to 20 s for the agent and the window; when the step hasn't
  finished by then the answer is `status: unknown`. A refresh that
  replaces the shown proposal with a newer version of the same plugin arms
  a one-time refusal of Accept, so a click aimed at the version the user
  read never accepts one an agent swapped in. Slice P10b (2026-10-27)
  serves a plugin's own `:mcp-tools`. `plugin-check` validates each row
  (a short `:name`, a `:doc`, typed `:args` and a `:run`), and the served
  name `plugin_<id>_<name>` is capped at 50 characters.
  `plugin-checker/summary` carries the rows' name, doc and args when a
  plugin has them, `plugin-isolated` accepts that shape, Accept compares
  them (P9's I5), and the review names them with new ones marked.
  `plugin-registry/mcp-tools` turns the live registry into served rows. A
  call checks its arguments, then runs `:run` on a future with the
  declared effects (and the active tab, through a GTK turn, only for a
  `:buffer` tool), turns the answer into a result inside that future, and
  answers within 30 s, one call per tool at a time. `jonquil.mcp/serve!`
  takes a table or a fn that answers one, read once per message, and
  `mcp-window/with-plugin-tools` builds that fn from the built-in table and
  the ui's `:plugin-tools*`, for `--mcp --window` and the listener alike.
  Slice P10c (2026-10-28) tells the client when those tools change.
  `serve!` takes `:subscribe`, a fn that gets a `notify!` and answers its
  unsubscribe, which runs however the session ends. Every write in a session
  holds one lock, so a notification never splits an answer. `notify!` sends
  nothing until the client's `notifications/initialized`, sends on a future
  so a reload on the GTK thread never waits on a client, and coalesces: a
  pending flag cleared under the lock gives one send in flight and one
  queued, however many swaps come. `plugin-registry/watch-tools!` watches
  the live registry and calls `notify!` when the served tool definitions
  differ, and a throw there never reaches the loader's `reset!`. The ui's
  `:watch-plugin-tools*` wraps it, `with-plugin-tools` then sets
  `:list-changed?`, so `initialize` declares `"listChanged" true`, and stdio
  and each listener connection subscribe on their own. Headless `--mcp`
  passes no `:subscribe` and declares nothing.
  Slice P11 (2026-10-28) adds the plugin list. `jonquil.plugin-list` builds
  one row per plugin, from the registry, the plugin files and the histories,
  and `jonquil_plugins_list` prints those rows' lines, so the panel and the
  MCP tool never disagree. `settings/save-plugin-disabled!` merges inside
  `:plugins`, because `save-settings!` merges only top-level keys and would
  drop `:trusted-projects`. `plugin-loader/set-enabled-step!` runs on the
  plugin agent like every other step: it reads the settings, answers
  "already disabled" without writing when there's nothing to change (two
  quick clicks queue two steps), else writes and reloads. `plugin-step!`
  builds the rows after every step, beside the reviews, and
  `jonquil.gtk.plugin-list-pane` shows them in the right-hand slot.
  Slice P11b (2026-10-28) adds History and Revert to it. `history-line`
  moved from `jonquil.plugin-mcp` to `jonquil.plugin-list`, so
  `jonquil_plugin_read` and the panel print the same lines, and
  `plugin-list/version-rows` gives them newest first. A top-level
  `plugin-history-shown` atom holds the id whose history is open:
  `show-right-panel!` clears it for any other panel and
  `hide-right-panel! :history` clears it, so it follows the panel, and
  `plugin-step!` reads it to rebuild that history off the GTK thread after
  every step. Revert reads its reason at the click and runs `revert-step!`
  on the plugin agent. `revert-step!` now also refuses a version whose
  text is already live while the file is there (a deleted file is still
  written back).
  Follow-up F1 (2026-10-28): `plugin-list`'s line says "file deleted"
  when a plugin has an accepted version but no file, and `load-step!`,
  `set-enabled-step!`, `revert-step!` and `accept-step!` refuse with one
  line (`plugins-off`) when their opts carry `:plugins? false`. The window
  keeps that flag in a `plugins-on*` atom, seeded from `--no-plugins`, and
  passes it to every step.
  Follow-up F2 (2026-10-28): after every plugin step the callback runs
  `sync-plugin-tabs!` on the GTK thread, in its own `try`. For each tab on
  a plugin file (`plugin-loader/plugin-file-or-target?`, so a symlink's
  target counts) it reads the file and asks `tabs/disk-sync`: a clean tab
  reloads with `set-text!`, a dirty one is warned once and takes the disk
  text as its saved baseline (`editor/rebase-saved!`), and one whose edits
  already equal the disk text just takes the baseline.
  Follow-up F3 (2026-10-28): when `plugin-isolated/check!` keeps its
  folder it returns the log as `:log`, and `propose-step!` records it on
  the `:rejected-by-checks` entry as `:check-log`. `propose-text` names it,
  and `status-text` and `plugin-list/history-line` name it while the file
  exists.
  Slice P4 (2026-10-25) made a change take effect
  without a restart. `plugin-loader/reload` diffs the files against the
  live registry: an unchanged file is left alone; a new or changed one goes
  through the protocol against the plugins sorting before it; a failure
  keeps the old entry and loads its accepted source again, so its
  namespace is what it was; a gone file or a disabled id (`settings.edn`
  `:plugins {:disabled #{...}}`) is removed; and every plugin after a
  change is validated again, since its keys can depend on the earlier
  ones. Every load (startup, Reload Plugins in the palette, a saved plugin
  file through a hook in `save-to!`) runs on one agent that keeps going
  after a throw, so loads never race and none is lost.
