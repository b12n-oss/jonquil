# Plugins

A plugin is one Clojure file that adds editor commands and keys, hooks on
saves, Runs and errors, and tools an agent can call. You can write one, or an
agent can propose one that waits for your Accept.

**Safety.** A plugin runs with Jonquil's own rights, so treat its file like
any code you run. `:fs`, `:proc` and `:net` are declarations only, and no
effect is a sandbox: a plugin can still call Java, read files or start
processes. Effects decide which of Jonquil's functions the plugin gets and
what the review shows you. A file you put in the folder yourself gets no
review. Accept on an agent's proposal runs that code in Jonquil.

Files live in `plugins/` beside the settings file (`~/.config/jonquil/plugins/`
or `$XDG_CONFIG_HOME/jonquil/plugins/`). A file is `<id>.clj` (id: lower-case
letters, digits and `-`, starting with a letter), its namespace is
`jonquil.plugins.<id>`, and it calls `defplugin` once:

```clojure
(ns jonquil.plugins.wrap-when
  (:require [jonquil.plugin :as p]))

(p/defplugin
  {:id      :wrap-when
   :doc     "Wrap the form at the caret in (when ...)."
   :effects #{}
   :commands
   [{:id :wrap-when/wrap :doc "Wrap the form at the caret in (when ...)."
     :scope :editor
     :edit (fn [snapshot] (p/wrap-form snapshot "(when " ")"))}]
   :bindings
   [{:key "j" :mods #{:cmd :alt} :command :wrap-when/wrap}]})
```

An `:edit` command gets `{:text :caret :sel-start :sel-end}` and returns
`{:edits [[start end replacement] ...] :caret n}` (edits in ascending order),
or `nil` to let the key go on as usual. `p/form-at-caret` and `p/wrap-form`
help build edits.

## Commands and effects

A command that does more than edit is a `:run` command, `(fn [ctx] ...)`,
and the plugin lists what it reaches in `:effects`:

```clojure
(ns jonquil.plugins.shout
  (:require [clojure.string :as str]
            [jonquil.plugin :as p]))

(p/defplugin
  {:id      :shout
   :doc     "Upper-case the whole buffer."
   :effects #{:buffer :console}
   :commands
   [{:id :shout/go :doc "Upper-case the whole buffer." :scope :window
     :run (fn [ctx]
            (p/replace-text! ctx (str/upper-case (p/buffer-text ctx)))
            (p/status! ctx "Shouted."))}]
   :bindings
   [{:key "u" :mods #{:cmd :alt} :command :shout/go}]})
```

A `:run` command runs in the background on the tab that was active when it
started, and its key always counts as used. One run goes at a time, and
nothing stops one that never finishes short of quitting. A throw shows on the
status bar and console with the plugin's id.

| Effect | Functions |
|---|---|
| `:console` | `(p/print! ctx s)` console line, `(p/status! ctx s)` status bar |
| `:buffer` | `(p/buffer-text ctx)`, `(p/replace-text! ctx text)` (one undo step), `(p/open! ctx path)` opens a tab and returns its id; `(assoc ctx ::p/tab id)` acts on it |
| `:eval` | `(p/eval! ctx code)` quiet, returns `{:value :out :err}`; `(p/repl! ctx code)` at the console, returns `{:value :out}`. Both take `{:timeout-ms n}` as a third argument |

Calling a function whose effect wasn't declared throws "this plugin didn't
declare :buffer"; a closed tab throws "that tab was closed".

Both eval functions run in the tab's namespace, wait 10 seconds by default
("the eval took longer than 10000 ms and was stopped"), and throw "the REPL
is busy" if something else is running.

- `p/eval!` leaves the console, history and `*1`/`*2`/`*3`/`*e` alone. A
  throw comes back as `:error`. Jolt dialect only; other dialects get told to
  use `p/repl!`.
- `p/repl!` works like Run Selection: echoed, shown, added to history. It
  works everywhere.

Stopping an eval equals pressing Stop. For Jolt as a child (a project with a
`deps.edn`), code still running a second after the interrupt makes Jonquil
restart the child, and its definitions are lost.

## Hooks

`:hooks` maps a hook point to `(fn [ctx event] ...)`. The ctx reaches only the
plugin's declared effects.

| Hook | Event |
|---|---|
| `:before-run` | `{:kind :text :path}` before a Run sends `:text`. Return `{:abort "sentence"}` to stop it |
| `:after-save` | `{:path p}` after a successful save |
| `:after-run` | `{:kind :status :value :error}`; `:kind` is `:run`, `:selection` or `:form`; `:status` is `:ok`, `:error`, `:interrupted` or `:notice` (dialect couldn't run it) |
| `:on-error` | `{:source :text :line :column}`; `:source` is `:run`, `:selection`, `:form` or `:repl`. `:line`/`:column` only when the error text names them (in-process Jolt, compiler errors only) |

A Run is Run, Run Selection or Run Form, from a key, menu, palette or an
agent's `jonquil_run`/`jonquil_run_selection`. A Run that never started (REPL
busy, other project's tab, unclosed bracket) fires nothing. `:on-error` also
hears console errors, but never those from a plugin's own `p/eval!`/`p/repl!`.

Hooks other than `:before-run` run in the background after the fact and can't
change the save or Run. At one point they run in plugin-id order, each getting
the previous one's returned event (non-maps are ignored). A throw shows with
the plugin's id and the next hook still runs. Up to 16 events queue while a
point is busy; further ones are skipped with a console line.

`:before-run` is synchronous, because the Run waits for it:

```clojure
(ns jonquil.plugins.no-todo
  (:require [jonquil.plugin :as p]))

(p/defplugin
  {:id      :no-todo
   :doc     "Refuse to Run code that still says TODO."
   :effects #{}
   :hooks   {:before-run (fn [_ e]
                           (if (.contains ^String (:text e) "TODO")
                             {:abort "the code still has a TODO"}
                             e))}})
```

A stopped Run shows `; plugin no-todo stopped this Run: the code still has a
TODO` on the console and status bar, sends nothing, and gives an agent's
`jonquil_run` the same line as its error. The first abort wins. Editing
`:text` changes nothing, and a non-sentence `:abort` is reported and ignored.

- All `:before-run` hooks together get 100 ms. If they time out or throw, the
  Run goes ahead with a line saying so. A hook that never returns is skipped
  by later Runs, with a line.
- `:before-run` hooks can't use effects, even declared ones (`p/print!` etc.
  throw and the Run goes ahead).
- Inspect Form, the console and `jonquil_eval` don't ask these hooks.

## Loading and reloading

Plugins load at startup, one file at a time. A broken file is skipped and the
console names the problem (with a line when known). A file gets 10 seconds to
load. Each `:edit` command is tried on sample buffers (2 seconds each) before
going live, and its answer is checked on your real buffer every time it runs,
so a wrong answer is refused instead of editing the wrong text.

**Bindings** must hold Cmd/Ctrl. Chords the window takes first are refused
(Cmd+Alt+W is the window's Cmd+W). A binding that loses only some chords loads
with a warning (`:mods #{:cmd}` on P also matches Cmd+Shift+P, the palette's),
and so does a chord a built-in already uses. Plugins take keys in id order, so
on a shared chord the earlier id wins and the later one gets a warning.
Plugin bindings come ahead of built-in ones; their commands appear in the
palette with the plugin's id.

**Reload.** Saving a plugin file in a tab reloads it. **Reload Plugins** in
the palette reloads every file that changed, appeared or went away (needed for
edits made elsewhere). A failed reload keeps the previous version running and
your file untouched. Later plugins are rechecked, and one that now fails is
removed and named until fixed.

**No plugins.** `--no-plugins` (or `JONQUIL_NO_PLUGINS=1` for a Dock or Finder
launch) starts with none. Reload, Enable, Disable, Revert and Accept refuse
with a line saying plugins are off, and an agent can't propose one. The list,
History and review still open. To keep one off persistently, add its id to
`settings.edn`'s `:plugins :disabled` and start normally.

## The plugin list

**Plugins…** in the palette lists every plugin in the right-hand panel: id,
live version, loaded/disabled/not loaded, effects, pending proposal and doc
(the same as `jonquil_plugins_list`). A plugin that fails its checks shows as
not loaded, and one whose file was deleted keeps its row ("file deleted").
Select a row, then:

- **Disable**: writes the id into `settings.edn` and reloads; commands and
  keys go at once. The file stays.
- **Enable**: removes it and reloads through the usual checks.
- **Open**: the file in a tab.
- **History**: its versions.
- **Review Proposals**: pending agent proposals.

Disable writes this and keeps your other settings (you can also write it by
hand, then Reload Plugins). If `:plugins` holds something Jonquil can't use,
Enable and Disable refuse rather than overwrite it:

```clojure
{:plugins {:disabled #{:wrap-when}}}
```

## History and revert

Every version that goes live is kept in `plugins/.history/<id>/` (`1.clj`,
`2.clj`, ... plus `history.edn` with when, where from and why). Entries are
never changed or removed. A new version is added when a plugin loads with text
different from its latest, with the reason "edited by hand"; an edit that
fails its checks isn't one. An unreadable `history.edn` is left alone and the
plugin still loads.

**History** lists versions newest first (status, source: you, an agent or a
revert, when, why). Select an older one, add an optional reason, press
**Revert**. It runs the reload checks, writes the file, and records a new
version ("revert to v2"), so nothing is erased. **Back** returns to the list.

Revert refuses, saying why in the footer, if the version was never live
(proposals go through Accept), the plugin is disabled, the text is already
live, or the file differs from the live version (an unreloaded or failing edit
a write would lose). A tab open on the file follows a Revert or Accept: it
reloads if clean, and otherwise keeps your unsaved edits with a one-time
console warning that saving overwrites the version on disk.

## Proposals from an agent

An agent proposes a change with `jonquil_plugin_propose` (see
[Driving Jonquil from an agent (MCP)](mcp.md)). It becomes a pending version
and the status bar counts the waiting ones. **Review Plugin Proposals** in the
palette shows, in the right-hand panel: the agent's reason, whether it builds
on the live version or an older one, a diff, the declared effects (new ones
marked), and the checks' warnings and timings. Next steps through several.

**Accept** reruns the checks in Jonquil, then writes the file and makes it
live. If they fail, it stays pending and the footer says why. **Reject** takes
an optional note. The agent can read the outcome but can never Accept.

**The isolated check.** Before review, the proposal is checked in a separate
Jolt, in a fresh temporary folder only you can open, with credential-looking
environment variables removed. This is isolation from the running Jonquil, not
a sandbox. The child runs as you, with your files and network, so top-level
code that deletes a file deletes it during the check (no more reach than an
agent has via `jonquil_eval`), and the scrub catches only obvious names. It
could even rewrite a file Jonquil reads later, such as another plugin or
`settings.edn`. What it can't touch before Accept is the running Jonquil: your
tabs, REPL, loaded plugins and already-read settings, and it can't crash or
hang the window.

- A check over 30 seconds is stopped and the agent gets that reason.
- After a timeout, no result or an error exit, the check's folder is kept for a
  day; the agent's answer and the History line give the path of `check.log`.
- With no `jolt` available, the proposal waits unchecked (the review says so)
  and its checks run on Accept.
