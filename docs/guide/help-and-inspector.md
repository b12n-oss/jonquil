# Help and the inspector

Completion, quick doc, jump to definition, rename, the command palette, and the value inspector.

## Help

Completion, quick documentation, jump to definition and rename all resolve
in the namespace your program runs in, so a function Run just defined
shows up like a core one. The right-click menu and command palette reach
them too.

Ctrl+Space, Ctrl+Q and Ctrl+B mean Control specifically, even on a Mac. The
F-keys take no Control or Command (most Mac keyboards need Fn). What each
runtime supports is in [Known limits](known-limits.md).

### Completion

Ctrl+Space, in the editor or the console's input line, opens a popup of
candidates with a doc preview. One match is inserted straight away.

![The completion popup open over a partial word, with a candidate list and its doc preview](../images/completion.png)

- Up and Down move one row, PageUp and PageDown eight.
- Enter or Tab commits; Escape closes without changing the buffer.
- In the console, Up/Down and Enter belong to the popup while it's open, not
  to history or evaluation.

Candidates come from special forms, built-ins, the file's own definitions,
and whatever your program has defined. Each row is tagged with its source
(`special`, `builtin`, `buffer`, `user`, and for ClojureScript `core` or
`remote`). When a server answers completion, its names join the list as
they arrive.

### Quick documentation

Ctrl+Q or Shift+F1 shows the namespace, arglists and docstring for the
symbol at the caret. Escape or moving the caret closes it.

![The quick documentation popup over a call to a core function](../images/quick-doc.png)

### Jump to definition

F12 or Ctrl+B jumps to the symbol's definition: the nearest local binding,
then a top-level `def` or `defn` in the file, then another file if the
runtime can say where it lives.

- A file in your project opens in a tab; Cmd/Ctrl+[ goes back.
- Library code and files outside the project open in a read-only source
  panel on the right. In it, F12 follows definitions, Cmd/Ctrl+[ goes back,
  Escape closes it and Cmd/Ctrl+I swaps it for the inspector.
- Where a runtime can't say (or for built-ins), you get the var's
  documentation instead.

![F12 on clojure.string/join in a JVM Clojure window: the source panel shows string.clj from the Clojure jar, read-only, on join's definition](../images/clj-source.png)

### Rename

Shift+F6 or F2 opens a popup prefilled with the symbol. Enter applies,
Escape cancels. Only code changes: exact symbol tokens, never comments,
strings, regexes, keywords or longer names (renaming `greet` leaves
`greeting`). A blank or invalid name is explained in the popup. A rename is
one undo step.

![The rename popup, prefilled with the symbol at the caret](../images/rename.png)

### Right-click menus

Shift+F10 or a right click over the editor opens a menu with Run Form, Jump
to Definition, Rename Symbol, Quick Documentation, Inspect Last Result,
Inspect Form, Complete and the editing commands. Each runs the same
function as its key.

### The command palette

Cmd/Ctrl+Shift+P lists Jonquil's commands, with their keys and source
(built-in or plugin). Type words to narrow by description or id, Up and
Down to move, Return or a click to run, Escape to close.

The same words work at the Interactions prompt after a `!`, so you can run
a command without leaving the keyboard's place: `!clear the console` and
Return runs it, and the console names what ran. Words that match several
commands list them and run none, and a command whose description is
exactly the words wins over the longer ones. The line goes into the
history like any other, so Up brings it back. Only one line of words
counts, with a space somewhere after the `!`: a single word after it is
read as code, since `!x` and `!=` are Clojure symbols, so type `! clear`
for a one-word command. A line with brackets or quotes, or more than one
line, always goes to the evaluator.

### Switching panels

The panels on the right share one place, so opening one hides the one that
was there. The palette lists **Switch to panel: name** for each panel you've
had open in this window, the one you saw last first, leaving out the one on
screen. Cmd/Ctrl+Alt+1 to 8 each go to one panel: 1 the Inspector, 2
Source, 3 Locations, 4 Frames, 5 Trace, 6 Plugins, 7 Messages and 8
Processes. Both focus the panel
when it's showing. Otherwise Frames, Trace and Plugins open the way their own
commands do, asking for the last error, the recorded calls or the plugin
list as they are now. The Inspector, Source and Locations come back with
what they showed when it still holds. Until something fills them, their
keys say on the status bar what does. Switching to another dialect empties
the Inspector and Source, and closing the tab a locations list came from
clears that list, so those leave the palette's list too. A plugin's history
and an agent's proposal aren't offered, since each belongs to the plugin or
proposal it was opened from.

### Layouts

**Save the layout**, in the palette, keeps the window's arrangement as it
is: whether the file tree shows and how wide, which right-hand panel shows,
and where the editor and console split. The panel opens at its usual
width. It's named after what
shows, such as `File tree and Trace panel` or `Editor only`, so saving again
with the same parts showing replaces it. The palette then lists **Use
layout: name** for each one, which shows or hides the tree, opens the panel
the way its key does and sets the tree's width and the split. A panel with
nothing to show yet says so instead. **Forget every saved layout**
clears them. Layouts live in the settings file, so every window and the
next start see them.

### Messages

The Messages panel keeps a log of what happened in the background while you
were looking elsewhere, newest at the top. Every MCP tool call an agent makes
gets a row naming the agent by the name its client sends, so a
`jonquil_replace_text` from Claude Code reads "Claude Code replaced the text
of core.clj". When an open tab's file changes on disk, there's a row for that
too. That's the case to watch for, because agents usually edit files with
their own tools, which Jonquil never sees. The panel also logs an agent
attaching or leaving, an attached runtime losing its connection, and a
runtime starting or failing to start.

The tick boxes above the list choose what shows. Agent, Files, Plugins,
Connections and Runtime are on to begin with. Reads (an agent reading a tab,
the console or the docs) and Status (everything the status bar has said, with
repeats merged into one row like "Ready ×3") are logged but hidden. While
there's anything unread of the kinds that start ticked, the status bar shows
"● 3 messages", and clicking it opens the panel. Showing the panel marks
everything read.

Enter on a row jumps to its tab, and on an agent's evaluation it opens the
code and the result below the row, cut at 20 lines. A row for a plugin
proposal opens the Review panel. The disk check covers files open in tabs,
about every two seconds. A tab with no unsaved edits follows its file, and
one with unsaved edits keeps them ([When the file changes on
disk](files-and-tabs.md#when-the-file-changes-on-disk)). The log keeps
the newest 1,000 entries, and the footer says how many older ones it dropped.

### Describing keys

Three palette commands explain the keys, from the live keymap, plugins
included. **Describe the next key you press** prints what the next key
does: the command it runs, its source, and any condition it needs, or that
it goes to GTK. That key is only described, never run. **List every key
binding** prints the window's keys, then the editor's, each with its
command's description. **Show the keys you pressed last** lists the last
50 chords with the command that ran for each, or that the window passed it
on to the editor or GTK. Typing, including Return, Tab and Backspace, shows
only as a count, never as the characters typed. A command's own keys are in the
palette's row for it.

When the editor's caret sits on a symbol, the status bar names the keys
that act on it, such as `Ctrl+Q doc · F12 jump · Shift+F6 rename`. They're
read from the live keymap, so your own bindings show, and the line follows
the caret a moment after it stops. **Show what keys do at the caret**, in
the palette, turns the line off or back on for this session.

### The locations list

Three palette commands list places in the buffer, in a panel on the right:
**List the uses of the symbol at the caret** (code only, never comments,
strings or keywords), **List the definitions of the symbol at the caret**
(a `defmulti` and each of its `defmethod`s, for example), and **List every
match of the find query, or of the selection** (the find bar's query when
it has one). A list stops at 1,000 places, and its title says when it did.
F8 or Cmd/Ctrl+. goes to the next place and Shift+F8 or Cmd/Ctrl+Shift+. to
the previous one, selecting it (on a Mac, F8 needs fn, see [Keys](keys.md)); a
click on a row goes there too, and F8 keeps working after the panel is
closed. Places follow ordinary edits, so a line added above one doesn't
move it off its text. A place whose text you deleted is reported gone.
Format All and reloading the file from disk replace the whole buffer, which
leaves the list out of date: F8 says so, and listing again fixes it.
Stepping past either end says so. A buffer list belongs to the tab it came
from: closing that tab clears it.

**Find Usages in Project** lists every use of the symbol at the caret in
the project's Clojure files: the folder the file tree shows, or the file's
own folder when there's no tree, minus what `.gitignore`, dot folders,
`target` and `node_modules` hide. An open tab counts with its unsaved text.
It matches by name, so `str/join` and `clojure.string/join` count as uses of
`join`, and so does a `join` of your own in another namespace. Each row
reads `path:line: kind: text`, where the kind says how the name is used: a
`call` (the first thing in a list, `(join ...)` or `#(join ...)`), a `value`
(passed around, `(map join xs)`), a `def` (the name a `def` form defines),
or `ns` (inside the `ns` form, such as `:refer [join]`). The check boxes
above the list show or hide each kind. F8 steps through the shown rows and
opens each file in a tab when it gets there, and from then on that file's
places follow its edits as a buffer list's do. A file whose text changed
after the search, on disk or in its tab, isn't stepped into at the old
places: F8 says it changed, and searching again fixes it. Closing a tab
keeps the list, and F8 opens that file again. Like the other lists it
stops at 1,000 places. The search runs in the background, so typing
carries on while it looks.

**Compare with the file on disk** and **Compare with git HEAD** list the
places where the tab differs from that version, one row per difference,
such as `4: 2 lines here, 1 line in HEAD: (def c 3)` with the other
version's first line. F8 steps through them like any list. **Take the
other version here** puts the compared version's lines in place of the
difference at the caret, as one step Cmd/Ctrl+Z undoes and with Parinfer
left out of it, and lists what's left. git runs as `git show HEAD:./name`
in the file's own folder, so a file outside a repository, or one git
doesn't track, gets git's reason on the status bar. Line endings and trailing
blank lines don't count as differences, and a Take keeps the tab's own line
endings.

### Freeing Ctrl+Space on macOS

macOS reserves Ctrl+Space for "Select the previous input source" (System
Settings > Keyboard > Keyboard Shortcuts > Input Sources). While it's on,
the keypress never reaches Jonquil. Turn it off or rebind it to use
completion.

## Inspecting values

Cmd/Ctrl+I opens the inspector on the last result (`*1`). Cmd/Ctrl+Shift+I
evaluates the form at the caret, as Run Form would, and inspects that. The
panel takes focus until you close it.

![The inspector panel open on a map, with a vector of same-shaped maps rendered as a table](../images/inspector.png)

The panel on the right shows a path, the value's type and size, a toolbar
(Back, Earlier, Later, Meta, Table, Def, Close), the rows, and a footer of keys. A
collection's rows read `label · preview`; maps sharing the same keys
become a table. The path starts with where the value came from: `*1` for the
last result, the printed line for a console result you clicked (its first 40
characters), a local's name, or a traced call such as `(demo.core/area 3)`.

- Up and Down move. Return or Right goes in; Left or Backspace comes out.
- Page Up and Page Down page through long collections.
- `m` toggles metadata; `v` switches a table to a list and back.
- `d` prompts for a name and defines that var from the current value.
- `[` and `]`, or Earlier and Later, go back and forward among the objects
  you inspected, each opened at its top; the footer then reads `object 2 of
  3`.
- Escape closes the panel (inside the `d` prompt, only the prompt).

The inspector remembers the last 10 objects it was opened on (an object
inspected twice in a row counts once), and opening a new one after going
back drops the later ones, as a browser does. It keeps the objects
themselves so they can be browsed again, so a large result stays in memory
until 10 newer inspections push it out. The history works with Jolt, in
process and out; a JVM server's inspector keeps only its current object, so
there Earlier and Later say "This server keeps only the object it shows
now."

Out of process, Jonquil loads the inspector into its Jolt child as nREPL
middleware, so stepping through a value never changes `*1`, `*2` or `*3`. Collections cap
at 1000 entries (`1000+ items`). Runtime support is in
[Known limits](known-limits.md).

<details>
<summary><strong>On the JVM</strong></summary>

- Before your first Run, Cmd/Ctrl+I says "Nothing to inspect yet: Jonquil
  isn't attached to the server. Run something first."
- If the server dies, the footer reads "Lost the connection to the nREPL
  server." and actions stay disabled until you reopen the panel.
- The server keeps one inspector view per connection, so another tool on the
  same server can replace yours.

![The inspector panel against a real JVM Clojure server, after Run and a REPL entry](../images/clj-inspector.png)

</details>

### Live results

Every result and error line the console prints keeps hold of its value, so
an older one stays inspectable after later entries. Click a result line and
the inspector opens on that value a moment later, once a double-click would
have come (on macOS that's the system's double-click speed); a double-click selects a word instead, and a drag that selects
text doesn't count as a click either. Right-click a line (which puts the caret there) or put the caret in
it, and the console's menu and the palette act on that result:

- **Inspect This Result** opens it in the inspector.
- **Copy This Result** copies the printed value, without the `=> `.
- **Define a Var for This Result** puts
  `(def  (jonquil.results-rt/value! 7 "…"))` at the prompt with the caret where
  the name goes, so the live value gets a name; Enter defines it. The second
  argument names the runtime that kept it.
- **Jump to This Var**, for a var such as `#'demo.core/area`, opens its
  definition as F12 does.

The kinds are a value, a var, a namespace and an exception. Each verb asks a
table of what each kind offers before it acts, so Jump to This Var on a
number says the result isn't a var. The last 100 results are kept; an older
one, or one kept by a runtime that has since restarted, says it's gone, and
Define's form throws "That result is gone." for it rather than defining a
placeholder. Live results come from Jolt, in process or in a
project's Jolt child, and quiet evaluations (completion, help) keep nothing;
other runtimes print results as plain text.

## Tracing a function

**Trace the function at the caret**, in the palette, wraps that function so
every call to it is recorded; run it again on the same name to stop.
**Show the calls of the traced functions** opens the Trace panel on the
right, one row per call, oldest first and indented by how deep it was
called from another traced function:

```
(user/tall 2) => [[0 0] [1 1]]
  (user/tpair 0) => [0 0]
  (user/tpair 1) => [1 1]
```

A call that threw reads `threw` and the error, and one still running reads
`(running)`. Enter on a row opens what that call returned in the inspector,
or its arguments when it threw. The panel refreshes after every evaluation
while it's open. Values print as the Frames panel's locals do, and a lazy
seq nothing has realized is never realized for the row, nor kept, so a
traced function can still hand out a seq too big to hold. Calls made while
a lazy result is realized happen after the call that returned it has
finished, so they show at the top level, not under it. The last 500 calls
are kept; **Clear the recorded calls** forgets them, and **Stop tracing every
function** unwraps everything.

Calls from functions compiled before the trace still go through it. Jolt
doesn't record a traced function's call to itself, only the outer call, and
redefining a traced function (Run again) replaces the wrapper, so the new
definition isn't traced. Tracing runs where your code runs, in process or in
a project's Jolt child, and doesn't touch `*1`; other runtimes say it needs
Jolt for now.

## The Processes panel

**Show the processes**, in the palette, or Cmd/Ctrl+Alt+8 opens the
Processes panel: the fibers your program spawned with `jolt.fibers/spawn`,
newest first, each with its number, its state as Jolt reports it
(`running`, `ready`, `parked`, `done` or `dead`), the function it runs and
how long ago it started. **Interrupt** raises an error in the selected fiber
("Interrupted from Jonquil's Processes panel"), which a `masked` region
holds off until it ends, and **Kill** ends it outright. **Forget finished**
drops the done and dead ones; Jonquil keeps the last 50 of those anyway.
While the panel shows, it refreshes every second.

Jolt keeps no list of live fibers (jolt-lang/jolt#1606 asks for one), so
the first time the panel opens Jonquil starts watching `spawn`, the way
Trace wraps a function, and code compiled earlier goes through it too.
Fibers spawned before that aren't listed. Neither are the fibers core.async
starts itself, for `io-thread`, for `go` blocks on its fiber backend and for
flow processes, because they don't go through `spawn`. Threads (`future`,
agents) aren't fibers, so they're not listed either. It works in
process and in a project's Jolt child; other runtimes say it needs Jolt.

## Counting how often each line runs

**Count how often each line runs**, in the palette, turns on Jolt's line
counters for code compiled from then on. Run a saved file and the gutter
shows, beside each line that ran, how many times it did: `6`, `999`,
`1.2k`, `12k`. Each Run counts its own lines alone. The counts are for the
text that ran, so the first edit hides them until the next Run, and a Run
Form or Run Selection refreshes them only while the file still holds the
text its last Run did. An Untitled or unsaved tab has no file for Jolt to
count by, and the console says so. **Clear line counts** zeroes the
counters, and the toggle again turns counting off.

Jolt has one instrumentation mode at a time, so counting and Keep every
local (debug mode) take turns: turning one on turns the other off, and the
status bar says so. A Jolt child that restarts comes back without
counting, and the next Run says so on the console. Counting works in
process and in a project's Jolt child, on a Jolt with `jolt.profile`
(v0.8.24 or later); other runtimes say it needs Jolt.

## The last error's frames

After an error in Jolt, **Show the last error's frames and locals**, in the
palette, opens the Frames panel on the right. Its title is the error, such as
`ArithmeticException: Divide by zero`. Below it is one row per frame of the
call stack, innermost first, and under each frame its locals:

```
user/dbg-inner  line 2 (no file)
    n = 3
    xs = [1 2 3]
```

Frames stop where Jonquil's own evaluation code starts, so the list shows your
code. Return or a click on a frame opens its file at that line, in a tab
for a file of your project and in the source panel for anything else. Code
typed at the prompt or run from an unsaved buffer has no file, so its frame
shows the line within that text and says so instead. A Run of a saved file
keeps the file on a Jolt whose `eval-string` takes a source path (Jolt main
from merge commit `94309872`, jolt-lang/jolt#1476). Return or a click on a local opens its value in
the inspector, where it lives, so even a huge or lazy value is never copied out.
A local's row prints at most 8 items, 3 levels deep, and 100 characters. A lazy seq nothing has realized yet reads `<lazy seq, not realized>`, because printing it would run your code again (a `(map save! rows)` would save the rows again); opening it in the inspector realizes it.

### Evaluating in a frame

The field under the list evaluates a form in the selected row's frame (a
frame row, or one of its locals). The form sees that frame's locals by name
and resolves other names in the frame's namespace, so a private function of
that namespace works too, and Enter shows it in the console:

```
; in user/dbg-inner: (count xs)
=> 3
```

The console shows the code as soon as you press Enter, then what the forms
printed (the first 10000 characters) and the last one's value, at most 100
items and 10 levels deep. Several forms run in order. A form that throws
shows one line with the error, and Stop stops one that runs too long, saying
"Stopped.". Nothing resumes or unwinds: you are looking at a frame that has
already stopped, so the error stays the last error, and `*1` stays where it
was. The forms' own side effects still happen. At run time, `*ns*` is the
prompt's, not the frame's. In the Jolt child the window waits the usual 10
seconds for the answer; press Stop for a form that's still running there.
After a newer error, a panel still showing the older one refuses to inspect or
evaluate: "The panel shows an older error: show the frames again."

### Break on error

**Break on error**, in the palette, makes a Jolt evaluation stop where it
throws, before anything unwinds (run it again to turn it off; it starts off).
The console says `; Paused at ArithmeticException: Divide by zero.`, the
evaluation is still running, and the Frames panel opens on the live stack
with its title starting `Paused:` and a **Continue** button in its bar.

While it's paused, the field evaluates on the thread that threw. A form
sees the frame's locals, as after an error, and also everything that was
still in effect there: a `binding` the code was inside still holds, and no
`finally` or `with-open` has run yet, so a file the code had open is still
open. (A `try` whose `catch` didn't match the error has already unwound by
then, its `finally` included: Jolt rethrows from it.) **Continue** (or **Continue from the break** in the palette) lets the
error unwind as it would have, and the panel then shows its frames and the
restarts. Stop ends the evaluation instead, and Stop on an evaluation
blocked in a sleep or a wait stops it without pausing. Nothing can resume the code or
change a local.

Only errors that escape the evaluation pause: one your own code catches
doesn't, and neither do Jonquil's quiet evaluations (completion, help,
timing). A top-level expression typed at the prompt keeps no frame of its
own, so its locals show only inside a function it called; when no frame is
your code's, the panel says so instead of listing Jonquil's own. Break on error
works for Jolt, in process and in a project's Jolt child, where it needs the
child's Jolt to have `jolt.debug`, the jolt-lang/nrepl middleware Jonquil
starts it with, and `jolt.nrepl/*eval-wrapper*`, the hook Jolt's nREPL
server calls around each evaluation (Jolt main from merge commit `440ea77a`,
jolt-lang/jolt#1474, not yet in a release). Turning it on says so when one of them is missing, and the other
runtimes say so too.

### Restarts

After an evaluation fails, three restarts take it from there, in every
dialect, from the palette or from the bar of buttons at the top of the Frames
panel:

- **Retry the last failed evaluation** runs it again the same way: a REPL
  entry, Run Form or Run Selection sends the same code again, and a Run runs
  its tab again (its current text, as Run does).
- **Edit the last failed evaluation at the prompt** puts its code at the
  console prompt to change before Enter. For a Run it says to edit the file.
- **Define the name the last error couldn't resolve, then retry** is there
  when the error was `Unable to resolve symbol: foo`. It puts `(def foo )`
  and the code at the prompt, with the caret where the value goes; type the
  value and Enter defines the name and runs the code again, as one entry.

Code you edit or define at the prompt runs in the prompt's namespace, like
any REPL entry, which can differ from the buffer namespace a failed Run Form
used. A success clears the failure, so a restart always belongs to the error
you just saw. Nothing resumes the failed evaluation itself: by the time the
console shows the error, the stack has unwound, so there is nothing to abort
either.

### Keeping every local

Jolt keeps a frame's locals only when the code was compiled with them.
**Keep every local for the debugger** turns Jolt's debug mode on (run it again
to turn it off). Code you load or run afterwards keeps all its locals, so Run
again, or reload a namespace, after turning it on. Debug mode keeps tail calls
only for `recur`, so very deep recursion that isn't `recur` can run out of
stack while it's on: Jolt then runs out of memory, keeps no frames, and the
panel says so. Turn debug mode off and run it again. Without debug mode, a
frame shows the locals Jolt happened to keep, and code typed at the prompt
shows none.

The panel reads the frames after the error was caught, from the evaluator that
ran the code, in process or in the Jolt child. It needs a Jolt with
`jolt.debug` (`jolt-lang/jolt` #1465); on an older Jolt the panel says
"Frames need a Jolt with jolt.debug (newer than this one)." and debug mode
stays off.

In a JVM Clojure window the panel lists the last error's frames too, read from
its stack trace on your server: each Clojure fn once (the REPL's own frames
and Java's left out), and a frame opens its file from the server's classpath,
a jar's included. The JVM keeps no locals, so frames have none, and the field
and debug mode say they need Jolt. Showing the frames leaves `*1`, `*2`, `*3`
and `*e` as they were. The other dialects say "The debugger needs Jolt." for
now.
