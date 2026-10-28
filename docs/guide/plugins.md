# Plugins

A plugin is one Clojure file that adds to Jonquil while it runs: editor
commands and the keys that run them, hooks that hear about saves, Runs and
errors, and tools an agent can call. You can write one yourself, or an
agent can propose one, which then waits until you accept it (see Proposals
from an agent, below). Either way it runs with Jonquil's own rights, so a
plugin file deserves the same trust as any other code you run.

Plugin files live in a `plugins` folder beside the settings file, so
`~/.config/jonquil/plugins/` (or `$XDG_CONFIG_HOME/jonquil/plugins/`). A
file is `<id>.clj`, where the id is lower-case letters, digits and `-`,
starting with a letter, and its namespace is `jonquil.plugins.<id>`. It
calls `defplugin` once:

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

An `:edit` command gets a snapshot of the buffer, `{:text :caret
:sel-start :sel-end}`, and returns the change to make as
`{:edits [[start end replacement] ...] :caret n}`, with `:edits` a vector
in ascending order, or `nil` when it doesn't apply here (the key then
goes on as usual). `p/form-at-caret` and `p/wrap-form` are there to build
on. `:effects #{}` says the plugin is pure.

## Commands and effects

A command that does more than edit the buffer is a `:run` command,
`(fn [ctx] ...)`, and its plugin lists what it reaches in `:effects`:

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

A `:run` command runs in the background, so a slow one doesn't freeze the
window, and its key always counts as used (what the function returns is
ignored; an `:edit` command returning `nil` is the way to let a key go
on). It acts on the tab that was active when it started, even if you
switch tabs while it works.

| Effect | Functions |
|---|---|
| `:console` | `(p/print! ctx s)` prints a line on the console, `(p/status! ctx s)` shows it on the status bar |
| `:buffer` | `(p/buffer-text ctx)`, `(p/replace-text! ctx text)` (one undo step), `(p/open! ctx path)`, which opens a file in a tab and returns its id; `(assoc ctx ::p/tab id)` acts on that tab |
| `:eval` | `(p/eval! ctx code)` evaluates quietly and returns `{:value :out :err}`; `(p/repl! ctx code)` evaluates at the console and returns `{:value :out}`. Both take `{:timeout-ms n}` as a third argument |

Calling a function whose effect the plugin didn't declare throws "this
plugin didn't declare :buffer", and a tab that has closed throws "that tab
was closed".

Three more effects, `:fs`, `:proc` and `:net`, can be declared but come
with no functions in this release. They say what a plugin means to reach,
so the review of an agent's proposal can show it to you. None of the
effects is a sandbox, though. A plugin's code can still call Java, read a
file or start a process directly, and nothing in Jonquil stops it: a
declaration decides which of Jonquil's own functions the plugin's ctx
holds, and what the review shows you before you accept an agent's
proposal. A plugin file you put in the folder yourself gets no review.

`p/eval!` runs the code in the tab's namespace (the one its `ns` form
names, once that exists), and the console never hears of it. It adds
nothing to the REPL history, and `*1`, `*2`, `*3` and `*e` are what they
were before it. `:value` is the printed value, the text the console would
show after `=> `, and a throw from the code comes back as `:error` with
`:value` nil. It works with the Jolt dialect, in process or in a
child, and on any other dialect it says to use `p/repl!`, which works
everywhere. `p/repl!` evaluates at the console the way Run Selection
does: the code is echoed, its output and result show, it goes into the
history, and it runs in the tab's namespace. Either one waits up to 10
seconds by default and then stops the eval and throws "the eval took
longer than 10000 ms and was stopped". Stopping it is the same as
pressing Stop, so for Jolt running as a child (a project with a
`deps.edn`), code that doesn't stop about a second after the interrupt makes Jonquil restart
the child, and its definitions are gone. The console says so when that
happens, as it says when Jolt auto-requires your project's namespaces. It also
throws "the REPL is busy" while something else is running, and
`p/eval!` stops code that reads from the console. A throw from a `:run`
command shows on the status bar and the console with the plugin's id.
One run of a command goes at a time: pressing its key again while it's
still working says it's still running, and nothing can stop a command
that never finishes short of quitting Jonquil.

## Hooks

A plugin can also watch what happens in the window through `:hooks`, a
map from a hook point to `(fn [ctx event] ...)`. The ctx is the same one
a `:run` command gets, so a hook reaches only its plugin's declared
effects:

```clojure
(ns jonquil.plugins.ran
  (:require [jonquil.plugin :as p]))

(p/defplugin
  {:id      :ran
   :doc     "Print each Run's kind and value."
   :effects #{:console}
   :hooks   {:after-run (fn [ctx e] (p/print! ctx (str "ran " (:kind e) " " (:value e))))}})
```

| Hook | Event |
|---|---|
| `:before-run` | `{:kind :text :path}`, just before a Run sends `:text` (the code), with `:path` nil for an unsaved tab. Returning `{:abort "sentence"}` stops the Run |
| `:after-save` | `{:path p}`, the path as you named it, after the save succeeded |
| `:after-run` | `{:kind :status :value :error}`. `:kind` is `:run`, `:selection` or `:form`; `:status` is `:ok`, `:error`, `:interrupted` (you pressed Stop) or `:notice` (the dialect couldn't run it, with its sentence as `:error`); `:value` is the printed value |
| `:on-error` | `{:source :text :line :column}`. `:source` is `:run`, `:selection`, `:form` or `:repl` (an entry at the console); `:text` is the error as the console shows it |

A Run here is Run, Run Selection or Run Form, from a key, a menu, the
palette or an agent's `jonquil_run` and `jonquil_run_selection`. A Run that
never started, because the REPL was busy, the tab belongs to another
project or a bracket is unclosed, fires nothing. `:on-error` hears an
error from any of those and from what you, or an agent's `jonquil_eval`,
type at the console. It never hears from a plugin's own `p/eval!` or
`p/repl!`, so a hook that evaluates code can't set itself off. `:line`
and `:column` are there only when the error text names them, which
in-process Jolt does for an error the compiler places (an unresolved
symbol, say) but not for a runtime throw like `(/ 1 0)`, and Jolt running
as a child never does.

Hooks run in the background, after the save or Run is done, so none of
them can change it. The hooks at one point run one after another, in
plugin-id order, and each gets the event the one before returned (a hook
that returns something other than a map leaves it as it was). A hook's
throw shows with the plugin's id, and the next plugin's hook still runs.
Events that arrive while the hooks at a point are still busy (Save All
saves every tab at once) wait their turn, up to 16 of them, and past that
one is skipped with a line on the console. So a hook that never returns
holds up its own point, and the plugins after it there, but nothing else.

`:before-run` works differently, because the Run waits for it. Here's a
plugin that won't let a Run go ahead while the code still has a `TODO`
in it:

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

A Run it stops shows `; plugin no-todo stopped this Run: the code still
has a TODO` on the console and the status bar, and sends nothing: no
banner, no echo, no `:after-run`. An agent's `jonquil_run` gets the same
line back as its error. The hooks run in plugin-id order and the first
abort wins, so the hooks after it aren't asked. Changing `:text` in the
event doesn't change what's sent, and an `:abort` that isn't a sentence
(a keyword, an empty string) is reported and the Run goes ahead.

Jonquil waits at most 100 ms for all the `:before-run` hooks together.
Past that, the Run goes ahead with a line saying the hooks took too long,
naming the plugin whose hook was running when it can. A hook that throws
is reported the same way, and the Run goes ahead. Since nothing can stop
a hook that never returns, it keeps running in the background, and until
it does, each Run skips the `:before-run` hooks with a line saying so.

A `:before-run` hook can't use effects, even when its plugin declares
them, because the window is waiting for it and every effect needs the
window. `p/print!` and the rest throw there, which is reported, and the
Run goes ahead. The event carries the code and the path, which is what
a check before a Run needs. The hooks are asked only when a Run would
really start, so not when the REPL is busy, the tab belongs to another
project or a bracket is unclosed. Inspect Form, the console and an
agent's `jonquil_eval` don't ask them either, so a guard plugin doesn't
cover those.

## Loading and reloading

Plugins load when Jonquil starts, one file at a time, so a broken file is
skipped while the others load, and the console names what's wrong,
with its line when the reader or compiler gave one. A file gets 10
seconds to load; one that loops at top level, or whose first compile of
what it requires is very slow, is skipped with that reason (a slow
compile usually gets through at the next start). Before anything goes
live, each `:edit` command is tried on a few sample buffers (empty, a
form with the caret at its start, middle and end, a selection, two
forms), each for at most 2 seconds, and its answer's shape is checked.
The same check runs on your real buffer each time the key runs it, so a
wrong answer is refused with the plugin's name on the status bar instead
of editing the wrong text.

A binding must hold Cmd/Ctrl. A bare letter, Return, Tab or Backspace
would take a key you type, so it's refused with that reason, and so is an
editor binding the window always takes first, on every chord it matches
(Cmd+Alt+W is the window's Cmd+W, which ignores Alt). A binding that loses
only some of its chords loads with a warning naming them: `:mods #{:cmd}`
on P also matches Cmd+Shift+P, which is the palette's. A chord a built-in already uses loads with a
warning naming that command. Plugins load, and take their keys, in id
order, so when two share a chord the one whose id sorts first gets it
first, and the other loads with a warning: that its binding never fires,
or, when the first one's binding is guarded or can pass the key on, that
it fires only when the first doesn't. Plugin bindings
come ahead of the built-in ones, and their commands show in the command
palette with the plugin's id as the source.

Saving a plugin file in a tab reloads that plugin, and **Reload Plugins**
in the command palette reloads every plugin whose file changed, appeared
or went away (an edit made in another program needs this one). A reload
that fails keeps the previous version working and leaves your file as
you wrote it, and the console names each problem. A reload also checks
the plugins after the one that changed again, since their keys can
depend on it: one that now fails, say because it binds a command that's
gone, is removed and named, and comes back once what it needs is fixed
and saved (or at the next Reload Plugins).

`--no-plugins`, or `JONQUIL_NO_PLUGINS=1` for a
launch from the Dock or Finder, starts with none, and keeps it that way:
Reload Plugins, Enable, Disable, Revert and Accept each refuse with one
line saying plugins are off, and change nothing. Saving a plugin file
doesn't reload it either, and an agent can't propose one. The plugin list,
History and the review still open, so you can look, and Reject still
records a note. To keep a plugin off for next time, put its id in
`settings.edn`'s `:plugins :disabled` (Disable's refusal names the file),
then start without `--no-plugins`.

## The plugin list

**Plugins…** in the command palette lists every plugin on the right,
where the source panel shows. A row says what `jonquil_plugins_list` says
to an agent: the plugin's id, its live version, whether it's loaded,
disabled or not loaded, its effects, any pending proposal and its doc. A
plugin whose file fails its checks is listed too, as not loaded, so you
can always turn it off from here. A plugin that once went live keeps its
row after its file is deleted, because its history stays: the row says
"file deleted", and its History can bring the file back. Select a row, then:

- **Disable** keeps the plugin off without deleting its file. It writes the
  id into `settings.edn` and reloads, so its commands and keys go at once.
- **Enable** takes it out again and reloads, so the plugin goes through
  its checks and the console says how that went.
- **Open** shows the plugin's file in a tab.
- **History** shows the plugin's versions, described below.
- **Review Proposals** opens the review of any pending agent proposals,
  described below.

The footer says what the last button did. Disable writes this, keeping
every other setting, the rest of `:plugins` included:

```clojure
{:plugins {:disabled #{:wrap-when}}}
```

You can also write it by hand and then Reload Plugins. When that key holds
something Jonquil can't use, Enable and Disable refuse and say so rather
than overwrite it.

## History and revert

Every version of a plugin that goes live is kept, so you can see what
changed and go back. They live in `plugins/.history/<id>/`: each version's
source as `1.clj`, `2.clj` and so on, and `history.edn`, a list saying
when each one went live, where it came from and why. Jonquil adds a
version whenever a plugin loads with text different from its latest one,
at startup, on Reload Plugins or when you save its file, with the reason
"edited by hand". An edit that fails its checks isn't a version. The
entries are never changed or removed, and if `history.edn` can't be read
Jonquil leaves it alone: the plugin still loads, and the console says its
history wasn't recorded. Going back to an older version runs it through
the same checks as a reload and, when it passes, writes it to the plugin
file (through a symlink, if the file is one) and keeps it as a new
version, "revert to v2", so going back never erases anything.

**History** in the plugin list shows the selected plugin's versions in
the same place, newest first, one line each: the version, its status, where
it came from (you, an agent or a revert), when, and why. The title says
which version is live. Select an older version, type a reason if you want
one, and press **Revert**. The reason goes after "revert to v2: " in the
new version's line. Revert refuses, and says why on the footer, when the
version was never live (a proposal goes through Accept instead), when the
plugin is disabled, when its text is already the live one, and when the
plugin's file differs from the live version, because writing over it would
lose an edit: one you haven't reloaded yet, or one that failed its checks.
Fix that file or reload it first. A plugin whose file was deleted gets it
back. A tab already open on the plugin's file follows the Revert: with no
unsaved edits it reloads, and the console says so. With unsaved edits it
keeps them, and the console warns once that saving the tab will write over
the version on disk. The same happens after an Accept, and after any plugin
step when another program changed a plugin file that's open in a tab.
**Back** returns to the list.

## Proposals from an agent

An agent's proposed change to a plugin waits for you. It's a version too,
pending, and the status bar counts how many are waiting. **Review Plugin
Proposals** in the command palette opens the review on the right, where
the source panel shows: the agent's reason, whether the proposal is based
on the live version or an older one, a diff against what's live now, the
effects it declares with any new ones marked, and its checks' warnings and
timings. Next steps through them when there's more than one. **Accept**
runs it through the same checks again, in Jonquil this time, and only
then writes the file and makes it live, so pressing Accept is the moment
you trust that code. When it doesn't pass, it stays pending and the
footer says why. **Reject** takes an optional note. The agent can read
what happened to its proposal but can never press Accept. An agent proposes
with `jonquil_plugin_propose`, one of the window's MCP tools, described
with a plugin's own tools under Driving Jonquil from an agent (MCP).

Before a proposal reaches the review, Jonquil checks it in a separate Jolt
started for that one check, in a new temporary folder only you can open,
with the environment variables whose names look like credentials taken
out. That child is not a sandbox. It runs as you, with your files and
your network, so a proposal whose top-level code deletes a file deletes
it during the check, and the scrub only catches the obvious names. That
reach on your machine is no more than an agent already has through
`jonquil_eval`. The code could even rewrite a file on disk that Jonquil
reads later, such as another plugin's file or `settings.edn`. What the
check keeps out of reach is the running Jonquil: until you press Accept,
the agent's code can't touch your open tabs, the REPL, the plugins that
are loaded or the settings Jonquil has already read, and it can't crash
or hang the window. A check that runs past 30
seconds is stopped, and the agent gets that reason back. When a check
fails that way, or the child prints no result or exits with an error, its
folder is kept so you can see what happened: the agent's answer, the
agent's status and the version's line in History all give the path of
its `check.log`, until the folder is cleaned up a day later. When there's no
`jolt` to check in, the proposal waits unchecked, the review says so, and
its checks run when you press Accept.
