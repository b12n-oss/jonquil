# Using Jonquil

Jonquil is a DrRacket-style IDE for Clojure: a Definitions editor above an
Interactions console, with a Run button that runs the top pane and a Stop
that actually stops it. This page covers the window, Run and the REPL
prompt, stdin, Stop, editing, find and replace, and themes. The rest of
the guide has its own pages: [Files and tabs](files-and-tabs.md),
[Help and the inspector](help-and-inspector.md),
[Dialects and runtimes](dialects/index.md), [Plugins](plugins.md),
[Driving Jonquil from an agent](mcp.md), [Keys](keys.md) and
[Known limits](known-limits.md).

Each section starts with what you need day to day. Edge cases, exact
messages and per-server detail sit in folded blocks below it: click a
bold line to open one.


## The window

![Jonquil's window: a toolbar, the Definitions editor above the Interactions console, and a status bar](../images/window.png)

Across the top sits a toolbar: **☰ Files** (the [file tree](files-and-tabs.md#file-tree)), **Run (F5)**, **Stop (Esc)**, **Open…**,
**Save…**, and a **Reset on Run** checkbox that starts checked. Below that,
a vertical split holds two panes you can resize by dragging the divider
between them: the **Definitions** editor on top, where you write a program,
and the **Interactions** console below, where output appears and where you
can type expressions directly at a `user=> ` prompt. A status label along the
bottom reads `Ready`, `Running…`, `Waiting for input`, or `Stopped`,
depending on what the last Run or REPL entry is doing. Beside it, the
dialect label says which runtime this window uses (see
[Dialects and runtimes](dialects/index.md)).

The console wraps a line that's wider than the pane onto the next row,
so a long path or a long printed value never pushes the rest of the
output sideways. The editor doesn't wrap: code keeps its own line breaks,
and the pane scrolls horizontally instead.

The window title names the open file, or `Untitled` when there isn't one,
and gains a leading `* ` the moment the editor has unsaved changes.

## Running a program

Press **Run**, **F5**, or **Cmd/Ctrl+R** to evaluate everything in the
Definitions pane, top to bottom. Before anything runs, Jonquil checks the
pane's brackets and refuses to run a broken one; see "The friendly
unclosed-bracket message" below for what that looks like. If a program is
already running, Run doesn't queue a second one; it just warns that one is
running already. Once the brackets check out, the console prints a `;
--- Running core.clj ---` banner, naming the tab's file, or `Definitions`
for an Untitled tab (only when nothing else is already running), then streams the program's own output as it prints, and
finishes with the result on its own line: `=> 3`, say, for whatever the
last form evaluates to. The status label tracks the run and settles back
on `Ready` when it's done.

![A second Run while (loop [] (recur)) is still running: the console warns that an evaluation is already in progress](../images/run-while-running.png)

`docs/demos/hello.clj` is the simplest case: it prints a line, leaves a
value behind, and shows exactly that shape.

You can also run just part of the Definitions pane: select some text (or
put the caret on a line with no selection) and press **Cmd/Ctrl+E** to
evaluate the selection, or the current line, at the console prompt. It
shows up there like anything else you typed, with its result, and joins
the REPL history.

![A selection run at the console with Cmd/Ctrl+E, shown with its result](../images/run-selection.png)

Press Cmd/Ctrl+Enter, or pick Run Form from the editor's right-click menu,
to run just the form around the caret. It runs the top-level form the
caret sits in, or, when that form is a `(comment ...)` block, the form
inside the block that holds the caret rather than the whole block. The
form briefly flashes so you can see exactly what ran, then goes through
the same path Cmd/Ctrl+E does: it's echoed at the console prompt,
evaluated there, and joins the REPL history. With no form at the caret,
nothing runs and the status bar says "No form at the caret."

<details>
<summary><strong>Run Form's details: discarded forms, metadata and the flash</strong></summary>

A form you've switched off with `#_` counts as no form, so Run Form won't
run code you've discarded (a stacked `#_ #_` discards both forms, the way
the reader does). Metadata in front of a form travels with it:
`^:private (defn f [] 1)` runs with its `^:private`. The flash is a real
selection for a quarter of a second, but a key pressed during it cancels
the flash first and puts the caret back, so it never types over the form.

</details>

## The REPL prompt, and how it differs from Run

![A Run's output followed by a REPL entry evaluated afterward, in the same console](../images/repl.png)

The prompt at the bottom of the console takes one expression at a time.
It names the namespace your entry will evaluate in, the way `clj` does, so a
new window reads `user=> `. It follows that namespace as it moves: an
`(ns demo.core)` typed at the prompt, or a Run of a buffer that starts with
one, turns it into `demo.core=> `, and whatever you've typed behind it
stays put. Type something and press Return, and the result appears the same
way a Run's result does, `=> `-prefixed. Where Run always evaluates the
whole Definitions pane, and by default clears out whatever earlier
evaluations defined first, a REPL entry evaluates only what you typed, and
always builds on top of whatever is already there, including anything the
last Run left behind. That's what makes it useful for poking at a program
you just ran: define a function with Run, then call it a few different
ways at the prompt without re-running anything.

A few REPL helpers are there without a `require` in the namespace the
prompt shows: `doc`, `find-doc`, `apropos` and `dir` from `clojure.repl`,
and `pprint` and `pp` from `clojure.pprint`, whose output lands in the
console like any `println`. `(pp)` pretty-prints `*1`, the last value. A program that defines its own `pprint` or `doc` keeps its
own. A file that starts with its own `ns` form gets a fresh namespace,
though, so it requires them the way it would under `clj`.

`*1`, `*2` and `*3` hold the last three values, and `*e` the last
error, so `(ex-data *e)` right after a failure shows what it carried. A
Run's result sets `*1` too, as load-file does in an nREPL editor, for Jolt in
process and out of process. In process, a Stop leaves
them as they were. Out of process, a Stop that has to restart Jolt clears
them along with every definition.

Up and Down recall earlier entries, but only from the first or last line
of what you're typing. On a multi-line entry, Up and Down move the caret
around inside it instead, the way you'd expect from any text editor.

If you try to submit while a program is running but not waiting on input,
the console holds your input and prints a one-time reminder instead:
"A program is running. Press Stop (Esc) to cancel it."

## `read-line` and stdin

![greet.clj waiting on read-line, then showing the typed answer's result](../images/stdin.png)

When a running program calls `read-line`, the status label switches to
`Waiting for input` and whatever you type at the prompt goes straight to
the program as its input, line by line, rather than being evaluated as
Clojure. `docs/demos/greet.clj` shows the whole round trip: it asks a
question, waits, and prints the answer back once you press Return.

Cmd/Ctrl+D sends end-of-input while a program is waiting this way, the
same signal `read-line` would see from a closed pipe on the command line.

This works with Jolt in process and out of process, with JVM Clojure (see
[JVM Clojure](dialects/jvm-clojure.md)), and with
launched ClojureScript on node. ClojureDart has no stdin. Out of process,
Jonquil's own nREPL middleware in the Jolt child asks for each line, a
`print`ed prompt shows before the wait, and Stop frees a read that's
waiting (see [Running Jolt out of process](dialects/index.md#running-jolt-out-of-process)).
One difference: in process, Cmd/Ctrl+D closes input for the rest of the
run; out of process it ends only the read that's waiting, and the next
`read-line` waits again, as JVM nREPL does. ClojureScript on node follows
the out-of-process shape: Cmd/Ctrl+D ends one read, the next one waits,
and Stop frees a waiting read by restarting the process, as Stop on that
dialect always does.

<details>
<summary><strong>Text you typed while the program was running</strong></summary>

One thing worth knowing: whatever you've typed at the prompt but not yet
sent stays there after the program finishes. If the program ended while
that text was waiting for it, your next Return doesn't evaluate it. It
prints "This was typed while the program ran. Press Return again to
evaluate it." and a second Return evaluates it. Edit the text first and
Return behaves normally. Nothing is deleted either way.

</details>

## Stop

![forever.clj mid-run, and the console right after Stop](../images/stop.png)

Press **Stop** or **Esc** to interrupt whatever is running, whether it's
stuck in a loop or blocked on `read-line`. Either way the result is
`; interrupted`, and the window never freezes waiting for it.
`docs/demos/forever.clj` is built to demonstrate exactly this: it counts
forever until you stop it.

![The same demo, animated: Run, a few ticks, then Stop](../images/stop.gif)

Esc only does this while something is running. The rest of the
time it's left alone, so it can still reach an IME composition or GTK's
own handling of the key.

An out-of-process runtime answers Stop the same way, with one twist: see
"Dialects and runtimes" below for when it restarts the child instead of
interrupting it.

## Reset on Run, versus accumulate

The **Reset on Run** checkbox in the toolbar starts checked. While it's
checked, each Run first removes every namespace that earlier Runs of the
same tab, or REPL entries, created (libraries you've required stay loaded, since `require`
won't bring them back), then evaluates the Definitions pane fresh. Uncheck
it, and Run stops clearing first: later Runs build on whatever earlier
ones already defined, the same way the REPL prompt always does. In a
window attached to a runtime you started (ClojureDart, JVM Clojure,
shadow-cljs or an nREPL server) the box shows unticked and greyed out,
since Jonquil can't clear namespaces out of an app or a process it doesn't
own. ClojureScript that Jonquil launches, on node or in the browser, starts
unticked too but you can tick it, because a reset there restarts
ClojureScript and that takes a few seconds. Switch back to a Jolt file and
your own setting returns.

`docs/demos/reset.clj` walks through the difference with a small,
self-contained experiment: run it once with the box checked, delete a
definition it depends on, and watch the next Run fail because Reset on Run
cleared what the deleted line used to provide. Then uncheck the box and
repeat, and the same missing definition no longer matters, because nothing
got cleared.

## The friendly unclosed-bracket message

![unbalanced.clj's Run result: a plain-language sentence naming the unclosed bracket and its line](../images/unbalanced.png)

Forget a closing paren, and Jonquil doesn't hand you a raw reader
exception. Run checks the pane's brackets before it evaluates anything, so
an unclosed opener names itself and its line, something like "The ( on
line 6 is never closed.", and a stray closer with no opener to match does
the same, "The ) on line 1 has no matching opener." Either way, nothing
runs. `docs/demos/unbalanced.clj` is deliberately broken this way.

## Editing

Typing `(`, `[`, `{` or `"` inserts the matching closer right along with
it, or wraps a selection in the pair instead of replacing it. Type the
closing character yourself over one Jonquil already inserted and it steps
past the existing one rather than adding a second, and Backspace removes
an empty pair in a single press instead of leaving a stray opener behind.

Tab indents the current line, or every line in a selection, one level;
Shift+Tab unindents the same way. Cmd/Ctrl+/ or Cmd/Ctrl+; toggles line
comments on the line or selection. Cmd/Ctrl+Shift+F formats the whole
file, and Cmd+Option+F on macOS (Ctrl+Alt+F on Linux), or the same chord
with L, formats just the selection or the current line. A format is
one undo step, so a single Cmd/Ctrl+Z puts the file back the way it was.
The caret stays on the same code it was on, wherever the new indentation
moves it, and the view scrolls to it.
Cmd/Ctrl+Z undoes more generally; Cmd/Ctrl+Shift+Z, GTK's own binding,
and Cmd/Ctrl+Y both redo.

F6 moves focus between the Definitions editor and the Interactions
console, so a keyboard-only user can reach the prompt without touching the
mouse. Cmd/Ctrl+L clears the console's output history while leaving
whatever's typed at the prompt alone. Cmd/Ctrl+Shift+L, CIDER's C-c C-o,
clears only the most recent entry instead: its echoed input, everything it
printed, and its result or error line, leaving every earlier entry and the
live prompt untouched. Pressing it again right after does nothing, since
there's nothing left of that entry to clear. Cmd/Ctrl+= (or Cmd/Ctrl++)
zooms in, Cmd/Ctrl+- zooms out, and Cmd/Ctrl+0 resets the zoom, and all
three resize both panes together. The status bar tracks the editor caret
as `Ln L, Col C`, following clicks, the arrow keys and ordinary typing.

## Find and replace

![The find bar open over a query, with matches highlighted and a count](../images/find.png)

Cmd/Ctrl+F opens the find bar. Cmd/Ctrl+H opens the same bar with its
replace field showing too. Select a single line of text first and the
bar opens prefilled with it. Every match in the pane highlights as you
type, and a count beside the field says where you stand, "2 of 5", or
"No matches" when there's nothing to find.

Return, F3 or Cmd/Ctrl+G moves to the next match; Shift+Return, Shift+F3
or Cmd/Ctrl+Shift+G moves to the previous one. F3 and Cmd/Ctrl+G also
work with focus back in the editor, not just while the find bar has it.
Replace swaps the current match and Replace All swaps every one, and
each of those is a single undo step. With Tab moved onto either button,
Return presses it. Escape closes the bar, but only
while the bar itself has focus; with focus somewhere else, Escape keeps
its usual job of stopping a running program.

## Unicode and IME

![unicode.clj's output: Hangul in a definition, an emoji in the printed result](../images/unicode.png)

Names and output aren't limited to ASCII anywhere in Jonquil.
`docs/demos/unicode.clj` defines a function named 안녕 and prints an emoji
alongside it, in both the Definitions pane and the console. Typing Hangul,
Thai, or any other composed script works the same in both panes, and (like
everything else in the output history) you can't type into old console
output, only at the current prompt.

<details>
<summary><strong>Return during an input-method composition</strong></summary>

There's one documented quirk around Return while an input-method
composition is still in progress. If you press Return while the last
syllable is still underlined and uncommitted, Jonquil lets GTK handle the
key itself: the syllable commits, and a plain newline is inserted, with no
smart indent in the editor and no evaluation in the console. Press Return
again once the syllable has landed, and it does what you'd expect: the
editor indents, or the console evaluates. In testing with 2-Set Korean, a
syllable still underlined in the editor committed first and was followed by
a plain newline at column 0, and Hangul names worked end to end: typing
`(def 안녕 "...")` at the prompt returned `#'user/안녕`. Typing a closing
character such as `"` or `)` after a Hangul syllable commits the syllable
by itself, so an entry that ends that way needs only one Return.

</details>

## Themes

Cmd/Ctrl+Shift+M cycles through five choices, in order: light (the
default), dark, solarized, navy, auto, and then back to light. The status
bar names the current one, `Theme: dark`, say, except while a program is
running, when it's busy showing that instead.

![Jonquil's window in the light theme](../images/theme-light.png)
![Jonquil's window in the dark theme](../images/theme-dark.png)
![Jonquil's window in the solarized theme](../images/theme-solarized.png)
![Jonquil's window in the navy theme](../images/theme-navy.png)

A theme recolours both panes: background, text, syntax highlighting,
rainbow brackets, the unmatched-bracket colour, the caret, selections,
the find highlight, and the different kinds of console output. GTK's own
chrome switches to its dark or light variant to match, and dark and navy
both count as dark for that purpose. Inside a find match, the text keeps
the theme's plain text colour instead of its syntax colour, so it stays
readable against the highlight.

Solarized keeps the lower contrast of the deck-app palette it's
drawn from. Dark and navy are built for night use.

Auto isn't a palette of its own. On macOS it shows the light theme while the
system is in Light mode and the dark theme while it's in Dark mode, and it
follows a change in System Settings within about a second. When you pick it
the status bar says which one it landed on, `Theme: auto (dark)` or
`Theme: auto (light)`; that text stays as it was if the system flips later,
though the window itself changes. Off macOS, auto shows light for now.

<details>
<summary><strong>Where the choice is saved</strong></summary>

The choice sticks: it's saved to `$XDG_CONFIG_HOME/jonquil/settings.edn` (or
`~/.config/jonquil/settings.edn` when that variable isn't set) as
`{:theme :navy}`, or whichever theme you picked (`{:theme :auto}` for auto,
which is saved as the choice and not as the light or dark it resolved to). A
missing settings file starts Jonquil in light, with nothing said about it; a
file that can't be read, or that names a theme Jonquil doesn't know, also
starts it in light, but says so once in the console. A settings file that's
a symlink stays a symlink after Jonquil saves through it. To go back to
light, delete the file.

</details>
