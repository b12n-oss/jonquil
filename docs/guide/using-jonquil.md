# Using Jonquil

Jonquil is a DrRacket-style IDE for Clojure: a Definitions editor above an
Interactions console, with a Run button that runs the top pane and a Stop
that actually stops it. This page walks through the window, the editor
and console, the file tree and tabs, themes, the dialects Jonquil can run,
and the limits that still apply.

## The window

![Jonquil's window: a toolbar, the Definitions editor above the Interactions console, and a status bar](../images/window.png)

Across the top sits a toolbar: **☰ Files** (the [file tree](#file-tree)), **Run (F5)**, **Stop (Esc)**, **Open…**,
**Save…**, and a **Reset on Run** checkbox that starts checked. Below that,
a vertical split holds two panes you can resize by dragging the divider
between them: the **Definitions** editor on top, where you write a program,
and the **Interactions** console below, where output appears and where you
can type expressions directly at a `user=> ` prompt. A status label along the
bottom reads `Ready`, `Running…`, `Waiting for input`, or `Stopped`,
depending on what the last Run or REPL entry is doing. Beside it, the
dialect label says which runtime this window uses (see
[Dialects and runtimes](#dialects-and-runtimes)).

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

A form you've switched off with `#_` counts as no form, so Run Form won't
run code you've discarded (a stacked `#_ #_` discards both forms, the way
the reader does). Metadata in front of a form travels with it:
`^:private (defn f [] 1)` runs with its `^:private`. The flash is a real
selection for a quarter of a second, but a key pressed during it cancels
the flash first and puts the caret back, so it never types over the form.

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
and `pprint` from `clojure.pprint`, whose output lands in the console like
any `println`. A program that defines its own `pprint` or `doc` keeps its
own. A file that starts with its own `ns` form gets a fresh namespace,
though, so it requires them the way it would under `clj`.

`*1`, `*2` and `*3` hold the last three values, and `*e` the last
error, so `(ex-data *e)` right after a failure shows what it carried. A
Run counts as well as a prompt entry, the way an nREPL editor's load-file
does, for Jolt in process and out of process. In process, a Stop leaves
them as they were. Out of process, a Stop that has to restart Jolt clears
them along with every definition. `clojure.pprint/pp` doesn't print `*1` yet, because
Jolt's own `pp` is still an empty stub, so use `(pprint *1)` instead.

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

One thing worth knowing: whatever you've typed at the prompt but not yet
sent stays there after the program finishes. If the program ended while
that text was waiting for it, your next Return doesn't evaluate it. It
prints "This was typed while the program ran. Press Return again to
evaluate it." and a second Return evaluates it. Edit the text first and
Return behaves normally. Nothing is deleted either way.

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
ClojureDart or JVM Clojure window the box shows unticked and greyed out,
since Jonquil can't clear namespaces out of an app or a JVM it doesn't
own; switch back to a Jolt file and your own setting returns.

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
file, and Cmd/Ctrl+Option+F (Alt+F on Linux and Windows keyboards) or the
+L equivalent formats just the selection or the current line. A format is
one undo step, so a single Cmd/Ctrl+Z puts the file back the way it was.
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
each of those is a single undo step. Escape closes the bar, but only
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

## Open and Save

Cmd/Ctrl+O opens a file in a new tab, next to the one you're in (see
[Tabs](#tabs), below). Nothing you've typed is ever replaced by an Open.
The one exception is a fresh, empty Untitled tab: Open fills that tab
instead of leaving it behind. A file that's already open in a tab, even
when you pick it through a symlink, just brings that tab forward.

Opening a file that isn't valid UTF-8 doesn't fail either. Bytes that
can't be decoded turn into the replacement character, and the console
warns in grey: "This file isn't valid UTF-8; unreadable bytes show as �
and saving will replace them." The file on disk is left untouched until
you save.

Cmd/Ctrl+S saves to the file's remembered path, or opens a Save dialog if
there isn't one yet. Cmd/Ctrl+Shift+S always opens the Save dialog, even
for a file that already has a path. Either way, the save itself is atomic:
Jonquil writes to a hidden temporary file next to the target first, then
renames it into place, so a crash or a full disk mid-write can't leave a
half-written file behind. Saving through a symlink follows the link and
updates the file it points to, leaving the link itself in place. That
holds even when the link points at nothing yet: the save creates the file
the link names, and a relative link is resolved against the real location
of its folder. A chain of more than 40 links prints "; save: too many
levels of symbolic links". When the file already exists, the temporary
file is created private (mode 600) before the new text goes in and only
then gets the file's own permissions, so a private file's content is never
written into a more open file.

![The close prompt: Save, Don't Save and Cancel over unsaved changes](../images/close-prompt.png)

Closing with unsaved changes asks first, however you close: Cmd/Ctrl+W on
the last tab, the window's own close button, Cmd+Q, Dock → Quit, or
logging out all raise the same prompt, offering Save, Don't Save and
Cancel. With several tabs holding unsaved changes you get one prompt that
names them all, and its first button is Save All. Cancel, or dismissing
the prompt any other way, leaves everything open and untouched. If a save
fails or you cancel partway through, the window stays open and the tab
that didn't save comes to the front, so a failed save never closes a tab
that still has unsaved work in it. `docs/demos/close-prompt.clj` is there to
try it on: make an edit, then close the window.

## Tabs

![Two tabs: core.clj, and demo.clj with unsaved changes, after running both](../images/tabs.png)

One window holds as many files as you like, one per tab. Cmd/Ctrl+T opens
an empty Untitled tab, and Open puts each file in a tab of its own. The
tab strip only appears once there are two tabs, so a one-file session
looks exactly as it always did. A second Untitled tab is labelled
`Untitled 2`, and so on. A tab's label carries a dot while it has
unsaved changes. When two open files share a name, each label adds its
folder (`core.clj · talk`). Cmd/Ctrl+Shift+] and Cmd/Ctrl+Shift+[ step
through the tabs, wrapping round at either end, and Cmd/Ctrl+1 to 8 jump
straight to a tab, with Cmd/Ctrl+9 for the last. Cmd/Ctrl+W, or a tab's
×, closes that tab, asking first when it has unsaved changes.

Every tab shares the window's one evaluator and its one console, the way
a project REPL in CIDER or Calva does. So `demo.clj` can call what
`core.clj` defined, and the prompt follows whichever tab ran last.

Reset on Run works per tab. Each tab owns the namespaces its own Runs
created, and a Run with the box ticked removes only that tab's
namespaces, plus anything you created at the prompt, plus `user`.
Running `demo.clj` leaves `talk.core` alone. Closing a tab hands its
namespaces back to the prompt's pile, so the next Run from any tab
clears them. Two things follow from sharing one session, and they're
worth knowing:

- Running `core.clj` again removes and recreates `talk.core`, but
  `talk.demo` still holds its alias to the old namespace until you run
  `demo.clj` again too. That's ordinary Clojure reloading. Tabs just make
  it come up more often.
- Every Run still starts from a fresh `user`, so two Untitled tabs without
  an `ns` form overwrite each other's definitions.

A window belongs to one project, the one its first file came from. Open a
file from somewhere else and it still gets a tab you can edit and save,
but Run, Run Selection, Run Form and Inspect Form refuse in that tab, and
the console says which project the file belongs to and which one the
window runs. In-process Jolt files run wherever they live, since they'd
use the same evaluator anyway. With only one tab open, opening a file
from another project switches the window over as it always did, and
closing tabs until the one left belongs elsewhere switches it too.

### Tabs from last time

Jonquil remembers which files have tabs in the window, in order, and
which one is selected. It saves that list each time you open, close or
switch a tab, and again when you quit, so a crash or a force quit
doesn't lose it. It keeps that list per project folder,
the folder the file tree is rooted at, for the 20 most recent folders.
Start Jonquil again in the same folder without naming a file (`jonquil`
from that folder, or `jonquil` with the folder as its argument) and those
tabs come back, with the selected one selected again. The window shows
straight away and the tabs open into it. A start from the
Dock or Finder has no folder of its own, so it goes back to the project
you quit most recently and roots the file tree there.

Only files are remembered. Untitled tabs, and text you didn't save, are
not: quitting already asks about those, and a file you chose not to save
reopens as it is on disk. Naming a file at launch (`jonquil core.clj`)
opens just that file. A remembered file that's been deleted or moved is
skipped, and the console names it: `; Couldn't reopen notes.clj from last
time.`, or `; Couldn't reopen 3 files from last time:` followed by their
names. If `sessions.edn` itself can't be read, the console says `;
Couldn't read sessions.edn, so no tabs were reopened.` The list lives in `sessions.edn`, next to `settings.edn` (see
[Themes](#themes) for where that is). To turn the whole thing off, put
`:reopen-tabs? false` in `settings.edn`; Jonquil then neither saves nor
reopens anything.

## Opening from Finder and the Dock

With the macOS app installed (see [Getting started](getting-started.md#the-macos-app)),
Open With on a Clojure file in Finder, or dropping files on Jonquil's Dock
icon, opens each file in a tab. On Linux, a file manager's Open With on an
installed Jonquil does the same (see [Linux](getting-started.md#linux)), and
so does giving several files on the command line: `jonquil a.clj b.clj` opens
two tabs on either system. The usual tab rules apply: a file that's
already open just selects its tab, an untouched Untitled tab is filled
first, and a file from another project than the window's gets an editable
tab that refuses Run (see [Tabs](#tabs)). A folder, from Open With, a
drop or the command line, roots the file tree there and shows it, even if
you'd hidden it. When a command line mixes a folder and files, the first
argument sets the window's project the way a single argument always has, and
the others open as tabs. A file manager on Linux doesn't offer Jonquil for
folders, because the MIME package has no `inode/directory` entry. Pass the
folder on the command line instead, to `~/.local/share/jonquil/jonquil` or
to `gtk4-launch net.b12n.jonquil <folder>` (from `libgtk-4-bin`).

A file Jonquil can't honour says so on the status line: a path it can't read
is named with the reason, and a file that isn't local (a URI) is reported as
"not a local file". The rest of a batch still opens. When several fail, only
the last failure stays on the status line.

## File tree

![The file tree beside two tabs: my-talk with src/talk expanded, core.clj and demo.clj open, and demo.clj's row highlighted](../images/file-tree.png)

Launch Jonquil on a folder and you get a tree of it on the left:

```bash
jonquil ~/dev/my-talk          # the built binary
bb app:run ~/dev/my-talk       # from a checkout
```

The window then runs that folder's project (its `deps.edn`, `pubspec.yaml`
or `.jonquil.edn` decides the dialect, as it would for a file inside it)
and reopens the tabs you had there last time (see
[Tabs from last time](#tabs-from-last-time)), or starts with one empty
Untitled tab when there are none. Launched on a file instead, the
tree is rooted at that file's project folder, or at the file's own folder
when it isn't in a project. With no argument from a terminal it's the
current directory. From Finder or the Dock there's no useful current
directory, so the tree starts empty and offers **Open Folder…**.

**☰ Files** in the toolbar, or Cmd/Ctrl+Shift+E, shows and hides it.
Hidden, the window looks exactly as it does without a tree. The tree
starts shown when you launched on a folder and hidden otherwise; after
that, whatever you last chose is remembered in the settings file, along
with the tree's width (drag its edge).

A click on a folder opens or closes it. A click on a file opens it in a
tab, or brings forward the tab that already has it. With the tree focused,
the arrow keys move, Right and Left open and close a folder, Return opens,
and Escape hands focus back to the editor. The active tab's file is
highlighted when its row is showing. Switching tabs never opens folders by
itself, but saving a file somewhere under the root, or opening one into a
tab, does: a new file you just saved shows up in the tree right away.

Dotfiles and dot-folders (`.git`, `.cpcache`, `.clj-kondo`…) and the
folders `target` and `node_modules` are hidden, along with whatever the root's `.gitignore`
names, as long as the line is a plain name (`out`), a `*` pattern (`*.log`)
or a folder (`classes/`). Lines with `!`, `**` or a `/` in them are left
out rather than half-applied, and nested `.gitignore` files aren't read.
Folders come first, then files, alphabetically. Anything that isn't a
Clojure-family file (`.clj`, `.cljc`, `.cljs`, `.cljd`, `.edn`, `.bb`) is
dimmed, but still opens. A symlinked folder opens like any other, and a
link that loops back to one of its own parents shows up but won't open
into itself.

The tree doesn't watch the disk. ⟳ in its header re-reads every open
folder, and opening a folder always reads it fresh. **Open Folder…**, also
in the header, roots the tree somewhere else; it doesn't open or close any
tab, and only changes which project the window runs while the window holds
nothing but one empty Untitled tab.

### Files and folders

Right-click a row for **New File…**, **New Folder…**, **Rename…** and **Move to
Trash**. Right-click the empty space under the rows for the first two, which
then go in the tree's root folder. With a row selected, Shift+F10 or the Menu
key opens the same menu. On a file row, New File and New Folder go in the
folder that file is listed in. The "(can't read)" row has no menu.

F2 renames the selected row. Delete, or Cmd+Backspace (Ctrl+Backspace on
Linux), moves it to the Trash. Return still opens a file or toggles a folder.

The menu swaps to a name box. Return accepts the name, while Escape or a
click outside cancels. Escape on the menu itself closes it. Rename pre-selects the
part of the name before the extension. A name that won't do keeps the box open
and says why: it's blank, contains `/` or a NUL character, is `.` or `..`, is already used in
that folder, or is held by an open tab (a tab can outlive its file after a
trip to the Trash). Rename never overwrites anything. Changing only the case
of a name (core.clj to Core.clj) works.

Open tabs follow a rename: a tab on the renamed file, or on a file under a
renamed folder, now points at the new path, and its unsaved text and dirty
mark stay as they were. Expanded folders stay expanded. On a link, Rename and
Move to Trash act on the link itself, the way Finder does, and leave whatever
it points to alone.

Move to Trash always asks first, with Cancel as the default button, and uses
the system Trash (`~/.Trash` on macOS, the freedesktop trash on Linux).
Nothing is ever deleted permanently, so the Trash is the undo. If the move
fails, the console says so and nothing is deleted. Tabs on a trashed file
stay open with their text, and the console tells you that Save writes the file
back. For a trashed folder it says to use Save As instead, because Save can't
recreate the folder.

A new file whose name the tree hides (a dotfile, or a `.gitignore` match) is
still created and opened, and the console says it's hidden. Dragging rows to
move them isn't supported yet.

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

Solarized keeps the lower contrast of the a-sibling-cljd-app palette it's
drawn from. Dark and navy are built for night use.

Auto isn't a palette of its own. On macOS it shows the light theme while the
system is in Light mode and the dark theme while it's in Dark mode, and it
follows a change in System Settings within about a second. When you pick it
the status bar says which one it landed on, `Theme: auto (dark)` or
`Theme: auto (light)`; that text stays as it was if the system flips later,
though the window itself changes. Off macOS, auto shows light for now.

The choice sticks: it's saved to `$XDG_CONFIG_HOME/jonquil/settings.edn` (or
`~/.config/jonquil/settings.edn` when that variable isn't set) as
`{:theme :navy}`, or whichever theme you picked (`{:theme :auto}` for auto,
which is saved as the choice and not as the light or dark it resolved to). A
missing settings file starts Jonquil in light, with nothing said about it; a
file that can't be read, or that names a theme Jonquil doesn't know, also
starts it in light, but says so once in the console. A settings file that's
a symlink stays a symlink after Jonquil saves through it. To go back to
light, delete the file.

## Help

P4 adds four features on top of everything above: completion with a doc
preview, quick documentation, jump to definition, and rename, plus a
right-click menu that reaches all of them. Each one resolves in the
namespace your program actually runs in, so a function Run just defined
shows up the same way a core one does.

Every key below checks the Control key specifically, not Command: Cmd+Q
still means Quit, and Cmd+Space still does whatever Spotlight or your
input source switcher already has it bound to. Cmd+B, Cmd+F1, and
Command's usual companions to these keys do nothing here, on purpose.
F-keys need the Fn key held down too, on most Mac keyboards.

### Completion

Ctrl+Space, in either the Definitions editor or the console's input line.
With no prefix at the caret it does nothing; with exactly one match it
inserts that match right away; otherwise a popup opens below the caret
(above the input line in the console) with the candidate list on one side
and a documentation preview for whichever one is selected on the other.

![The completion popup open over a partial word, with a candidate list and its doc preview](../images/completion.png)

While the popup is open, Up and Down move the selection one row, PageUp
and PageDown move it eight rows, Enter or Tab commits the selected
candidate, and Escape closes the popup without touching the buffer.
Typing keeps narrowing the list, and moving the caret out of the word
(an arrow key past its start, say) closes the popup the same way Escape
does. In the console's input line, the popup's own keys take over from
history and from evaluation while it's open: Up and Down move the list
instead of paging through history, and Enter commits the candidate
instead of running the line. Close the popup first, by committing
something or by Escape, to get history and evaluation back.

Candidates come from Clojure's special forms and core built-ins, whatever
the Definitions pane itself defines, and whatever your program has
defined by running or by typing at the prompt.

### Quick documentation

Ctrl+Q or Shift+F1 opens a popup with the namespace, arglists, and
docstring for the symbol at the caret, or in the selection when the
selection is exactly one symbol. With no symbol there, the status bar
says so instead of opening anything. Escape, or moving the caret, closes
the popup.

![The quick documentation popup over a call to a core function](../images/quick-doc.png)

### Jump to definition

F12 or Ctrl+B moves the caret to the symbol's own definition and scrolls
it into view, when Jonquil can find one in the buffer. It checks the
nearest enclosing local binding first (a function parameter, a `let`, or
anything else that can shadow a name), then a top-level `def`, `defn`, or
similar form anywhere in the file. When neither turns up but the symbol
resolves to a definition in another file, which JVM Clojure's server can
tell Jonquil, that file opens in a read-only source panel on the right,
with the caret on the definition. In other runtimes, which don't say
where a definition lives, jump shows the var's documentation in the
quick-doc popup instead. When nothing matches at all, the status bar
says so.

Inside the source panel, F12 or Ctrl+B follows a definition again, in
the same file or into another, and Cmd/Ctrl+[ goes back. Escape closes
the panel, and Cmd/Ctrl+I swaps it for the inspector. Library code from
a jar, and any file outside your project, is read-only. A file in your
project has an **Open in window** button (Cmd/Ctrl+Shift+O while the panel
has focus) that opens it in a tab for editing, on the same line, or
brings forward the tab that already has it. Nothing in your other tabs is
touched.

![F12 on clojure.string/join in a JVM Clojure window: the source panel shows string.clj from the Clojure jar, read-only, on join's definition](../images/clj-source.png)

### Rename

Shift+F6 or F2 opens a small popup prefilled with the symbol at the
caret. Type a new name and press Enter to apply it, or press Escape to
cancel. A rename touches only code: every place the exact symbol appears
as its own token, never inside a comment, a string, a regex literal, or a
keyword, and never a longer name that merely contains it, so renaming
`greet` leaves `greeting` alone. If the new name is blank, isn't a valid
Clojure symbol, matches the old name, or there's nothing to rename, the
popup explains why instead of doing anything. Applying a rename is one
undo step, so Cmd/Ctrl+Z reverts it in a single press, and the status bar
reports how many occurrences changed.

![The rename popup, prefilled with the symbol at the caret](../images/rename.png)

### Right-click menus

Shift+F10, or a right click over the Definitions editor, opens a menu
led by Run Form, then Jump to Definition, Rename Symbol, Quick
Documentation, Inspect Last Result, Inspect Form, and Complete, alongside
Jonquil's other editing commands
(Toggle Comment, Format All, Format Selection, Indent, Unindent, Find,
Replace) and GTK's own Cut, Copy, and Paste. The same menu over the
console's input line carries Complete and Clear Last Output next to
GTK's own entries. Every item runs the exact function its key would.

### Freeing Ctrl+Space on macOS

macOS reserves Ctrl+Space by default, under System Settings > Keyboard >
Keyboard Shortcuts > Input Sources > "Select the previous input source."
While that shortcut stays on, a real Ctrl+Space keypress goes to the
system first and never reaches Jonquil at all. Turn it off there (or
rebind it to something else) to free Ctrl+Space for completion. This
isn't something Jonquil can work around from inside the app: in testing,
a Ctrl+Space routed straight to Jonquil's own process by its pid reached
the completion handler without trouble, but an actual key press on the
keyboard still went to the system's input-source switch as long as that
shortcut was turned on.

## Inspecting values

Cmd/Ctrl+I opens the inspector panel on the last result, the value the
console itself would call `*1`. Cmd/Ctrl+Shift+I evaluates the form at
the caret first, the same form Run Form would, and opens the panel on
whatever it returns. Both are also on the Definitions editor's right-click
menu, as Inspect Last Result and Inspect Form. The panel takes focus the
moment it opens, so every key below reaches it directly: nothing you type
next lands in the editor until you close the panel again.

![The inspector panel open on a map, with a vector of same-shaped maps rendered as a table](../images/inspector.png)

The panel sits on the right, in its own pane: a path at the top (`*1`, or
a level down, `*1 / :speakers`), a line naming the value's type and size,
a small toolbar (Back, Meta, Table, Def, Close), the rows themselves, and
a footer. A collection's rows read `label · preview`, one per entry, or,
when every entry is a map and they all share the same keys, one row per
entry in a table instead, a column per key. A leaf value, a string or a
number, shows its own printed form in place of rows.

Up and Down move the selection. Return or Right goes in a level, onto
whatever's selected; Left or Backspace comes back out, landing on the row
you went in from. Page Up and Page Down page through a long collection,
and so does Down past the last visible row, or Up from the first row on
a page that isn't the first, landing on the new page's first or last row
in turn. `m` shows or hides the current value's metadata, and `v`
switches a table between its table and its plain list form. `d` opens a
small prompt: type a name and press Return to define that var from the
current value. Escape there closes only the prompt, leaving the panel
behind it untouched, and never Stops a program that happens to be
running. A successful def prints `; Defined #'ns/name` on the console and
leaves the panel exactly where it was, the same row still selected.
Escape anywhere else in the panel closes it and hands focus back to the
editor.

The footer names where you are and what the keys do, something like
`11-20 of 25 · ↑↓ move · ⏎ in · ← out · d def · m meta · v table`. An
empty collection reads `0 of 0` rather than a range it doesn't have. A
note from the last action, `no metadata`, `not tabular`, or an error,
shows there too for a few seconds and then clears on its own.

In-process Jolt supports this outright. JVM Clojure does too, once its
own server offers clojure-cli.repl's inspector. ClojureDart and Jolt
running out of process don't, in this version: Cmd/Ctrl+I there puts a
short sentence in the status bar instead of opening anything. Opening a
file whose dialect or runtime differs from the one the panel opened
against hides the panel; Cmd/Ctrl+I on the new file opens a fresh one.

On the JVM, before your first Run, Cmd/Ctrl+I says "Nothing to inspect
yet: Jonquil isn't attached to the server. Run something first." After a
Run, the first Cmd/Ctrl+I already shows that Run's own value: Jonquil
registers with the server's inspector the moment it connects, invisibly,
so there's no extra step to remember. If the server dies, whether the
panel is open or a request of its own is still on the way, the footer
reads "Lost the connection to the nREPL server." and every action stays
disabled until you reopen the panel. Only one request is in flight at a
time either way, so a key pressed while one is still out does nothing at
all, rather than queuing up behind it.

![The inspector panel against a real JVM Clojure server, after Run and a REPL entry](../images/clj-inspector.png)

The panel measures its own size, in rows and character columns, the
moment it opens; resizing the window changes that only the next time you
open it with Cmd/Ctrl+I, never while it's already showing. On the JVM,
the server keeps exactly one inspector view per connection, so it's
whichever client's action landed there last, Jonquil's own or another
tool attached to the same server. Either runtime caps a collection at
1000 entries: past that, the info line reads `1000+ items` instead of a
count, and paging still works over everything up to the cap.

## Dialects and runtimes

Jonquil evaluates through a dialect profile: what a language's files look
like, how they're painted, and which evaluator runs them. Four profiles
exist today: Jolt, ClojureDart (experimental), JVM Clojure and jank
(experimental). The way Jonquil picks a profile, and the evaluator
protocol under it, are general enough to take more. The status
bar's dialect label reads `Jolt` most of the time, or `Jolt (nREPL)` when
this window's Jolt is running out of process rather than inside Jonquil
itself. For ClojureDart it reads `ClojureDart (attached)` while Jonquil
holds a live connection to the app, and `ClojureDart (not running)` the
rest of the time. JVM Clojure reads the same way, `Clojure (JVM) (attached)`
or `Clojure (JVM) (not running)`. For jank it reads `jank (running)` while Jonquil's own jank process is
up, and `jank (not running)` until the first evaluation starts one.

### Choosing a dialect

Jonquil picks a dialect and a runtime for each window, in this order:

1. `.jonquil.edn` in the project, if it names one.
2. A single profile marker in the project (Jolt's is `deps.edn`).
3. The open file's extension.
4. The `:dialect` saved in `settings.edn`.
5. Jolt, when none of the above decides.

The project is the nearest directory, at or above the open file, holding
`.jonquil.edn` or a profile marker. A loose file with nothing like that
above it, or an unsaved window, has no project, so only the extension and
the settings default are left to decide.

ClojureDart's own marker is `pubspec.yaml`. A ClojureDart app commonly
carries a `deps.edn` too, for `bb` tasks, say, so both markers can be
present in the same project at once: rule 2 only decides when exactly one
marker hits, so the file's extension breaks the tie instead. `.cljd`
opens as ClojureDart, `.clj` and `.cljc` stay Jolt's, and a file neither
extension claims, `bb.edn` say, falls back to Jolt.

JVM Clojure has no marker of its own: its `.clj` and `.cljc` extensions,
and the `deps.edn` a JVM project usually has too, both already belong to
Jolt. So only rule 1 ever picks it inside a project, by naming
`{:dialect :clj}` in that project's own `.jonquil.edn`. Rule 4's settings
default can still choose JVM Clojure, but only for a loose or unsaved
file with no project at all; inside a project, a marker-less default like
this one is skipped, and selection falls through to whichever rule comes
next.

jank has no project marker either. A `.jank` file opens as jank by its
extension, and a project can name it with `{:dialect :jank}` in its
`.jonquil.edn`, the same way as JVM Clojure.

### `.jonquil.edn`

A `.jonquil.edn` at the project root pins the dialect and, for Jolt,
chooses the runtime:

```clojure
{:dialect :jolt :runtime :nrepl}
```

Both keys default independently, so either can be left out. A file that
isn't valid EDN, or that doesn't hold a map, never blocks Jonquil from
opening anything: the console prints a line like `; project: .jonquil.edn
isn't valid EDN, so it is ignored`, and selection falls through to the
next rule as though the file weren't there at all.

`{:dialect :jank}` pins jank for a project whose files aren't all `.jank`
(see [jank](#jank-experimental)).

`{:dialect :clj}` is the only way to reach JVM Clojure: a project with
just that one line in its `.jonquil.edn` attaches to its own nREPL server
instead of running under Jolt (see below). A JVM project can also list
namespaces under `:auto-require` for Jonquil to load as it connects (see
[Auto-require](#auto-require)). Any other dialect ignores that key, and
the console says so.

### Running Jolt out of process

`{:runtime :nrepl}` moves your program out of Jonquil's own process.
Jonquil starts a separate `jolt nrepl-server` in the project directory and
talks to it over a socket instead of evaluating inline. That's the whole
point: a program that segfaults, calls `System/exit`, or otherwise takes
Jolt down with it takes down that child, not the window running it.

At start, the console reports the child's pid, its port, and which
project directory it's running in, then lists what this particular
runtime can't do (three of those are below). Stop still interrupts a
running evaluation first; if the child doesn't answer within about a
second, Stop kills and restarts it instead, and says so, rather than
leaving you to notice your definitions vanished for no visible reason.
Reset on Run works the same way it does in-process, clearing earlier Run
and REPL definitions before evaluating fresh, just by sending a reset form
over the wire instead of removing namespaces locally.

Opening a file from a different project switches the whole session: the
old evaluator is interrupted and stopped off to the side, a new one
starts for the new project, and the console says something like
`Switched to Jolt (nREPL). The previous session is gone.` The same switch
happens if the new file's project asks for a different runtime, or none
at all.

Three limits are worth knowing going in. There's no stdin: a program that
calls `read-line` or `read` gets nil or an EOF straight away. Output
appears only once an evaluation finishes rather than as it prints
(the jolt-lang/nrepl middleware sends it at the end), so a program that prints once a second for ten
seconds shows nothing for those ten seconds and then everything at once.
And jump to definition can't leave the buffer, because the wire op it
would need for that (`info`) isn't there yet, only `lookup`, which
carries no file or line to jump to.

### The jolt-lang/nrepl checkout

Interrupt and lookup both need Jonquil to inject middleware from a
jolt-lang/nrepl checkout when it starts the child, found at
`$JONQUIL_JOLT_NREPL`, or `~/dev/jolt-lang/nrepl` when that variable isn't
set. Without either one in place, the child runs a vanilla nREPL server
with neither op, so Stop always falls back to killing and restarting it,
even on a program that would otherwise have interrupted cleanly.

### `.nrepl-port`

While the child runs, `jolt nrepl-server` writes its port into the
project's `.nrepl-port`, the file any nREPL-aware editor already looks
for. The first child in a project folder remembers what the file held
before. If another child starts there while it runs (two windows on one
project, or switching a project from A to B and back to A quickly), they
share that memory. Whenever a child stops while others still run, the file
is rewritten with the newest running child's port. The last one out
restores whatever was there before, or deletes the file if nothing was, so
a project that already used that file for something else gets it back
exactly as it was.

### ClojureDart (experimental): attaching to a running app

ClojureDart support is experimental. Where the out-of-process Jolt
runtime starts and owns its own child, this dialect works the other way
round: Jonquil attaches to a ClojureDart app you already have running,
the way CIDER attaches to a live Clojure process. Connect, load the
file, then evaluate forms one at a time from a `(comment ...)` block.
Jonquil never starts the app, stops it, or sends it any signal at all.

Attaching needs the app running under `bb dev:macos` (or
`clj -M:cljd flutter`), on the `nrepl-poc` ClojureDart fork at
`199d173d` or later. That fork writes its port to `NREPL.lock` in the
app directory, which is how Jonquil finds it; upstream ClojureDart
carries no nREPL server of its own, so there's nothing there for
Jonquil to attach to.

Jonquil connects the first time you Run, or on any evaluation while it
holds no live connection, and prints "; Connecting to the app on port
N..." while it does. A fresh session's first answer takes a few seconds
and brings the ClojureDart banner along with it. If nothing's running
yet, the console says so instead: "; ClojureDart app isn't running
here. Start it with `bb dev:macos` (or `clj -M:cljd flutter`)." A port
that refuses the connection answers "; NREPL.lock says port N, but
nothing answers there. The app may have quit; restart it.", and a port
that answers but isn't a ClojureDart server at all gets "; Port N isn't
a ClojureDart nREPL server." Jonquil only ever reads `NREPL.lock`: it
never writes to the file or deletes it.

Run loads the buffer's own text, unsaved edits and all, the way
CIDER's C-c C-k loads a file, and leaves the session sitting in the
file's namespace afterward. Rather than a value per form, it prints one
quiet line when it's done, "; Loaded <ns> (<n> forms)", or, if a form
fails, "Load stopped at form <n> of <ns>" with the app's own error in
place of it. An untitled buffer has no path for the app's error
messages or its watcher to follow, so Run refuses first: "Save the file
first: the app's error messages and its watcher both go by the file's
path."

Cmd/Ctrl+E and Cmd/Ctrl+Enter (Run Form) both run in the buffer's own
namespace. Since the app's nREPL server has no `in-ns`, Jonquil
evaluates the buffer's `ns` form first whenever the session is sitting
somewhere else, then sends your code. A REPL entry typed at the console
prompt runs wherever the session already happens to be.

Stop detaches rather than interrupting anything: it closes the
connection at once, even one that's still mid-handshake, and prints
"; Detached from the app. The app wasn't touched; a change already sent
may still land." The next evaluation reconnects with a fresh session.
An evaluation that's still waiting past 30 seconds gets one more line,
"; Still waiting on the app...", and after that Jonquil just keeps
waiting: there's no timeout of its own.

Reset on Run is unticked and greyed out for this dialect, with a tooltip
that says why: "Hot reload can't unload a namespace, so every Run adds to
the running app." Every Run builds on what's already there instead of
clearing it first. Your own tick isn't lost: open a Jolt file in the same
window and the box comes back the way you left it.

Completion stays offline here: candidates come from the buffer's own
definitions, the aliases its `ns` form declares, and ClojureDart's own
special forms and built-ins, never anything the running app itself
knows about. Quick documentation does ask the app, and answers one of
four ways: the symbol's own docs when the app has them, "Not attached;
Run first" with no live connection, "No docs: the app didn't answer in
time" once its own budget runs out, or, only on a fork older than
`199d173d`, "No docs for ClojureDart symbols yet (the app's ClojureDart
fork doesn't answer lookup)".

### JVM Clojure: attaching to your own nREPL server

JVM Clojure attaches the same way ClojureDart does: Jonquil never starts
anything, only connects to a server you already have running. Start one
yourself, most simply `clojure -M:serve` from
[clojure-cli.repl](https://github.com/clojure/clojure-cli.repl)'s own
`:serve` alias, or any nREPL server that writes its port to `.nrepl-port`
in the project directory. Then add `{:dialect :clj}` to that project's
`.jonquil.edn` and open a `.clj` or `.cljc` file under it; nothing else
picks this dialect (see [Choosing a dialect](#choosing-a-dialect) above).

If the project doesn't have a server alias yet, this is the one Jonquil's
own `bb test:clj` uses. It needs Java 17 or later and the `clojure` CLI:

```clojure
;; deps.edn
{:paths ["src"]
 :aliases {:serve {:extra-deps {io.github.clojure/clojure-cli.repl-server {:mvn/version "0.1.0"}}
                   :main-opts ["-m" "clojure-cli.repl.server"]}}}
```

```clojure
;; .jonquil.edn
{:dialect :clj}
```

Run `clojure -M:serve` in the project directory and wait for
`nREPL server listening on port N`, then open a source file in Jonquil
and press Run. Stopping the server with Ctrl+C removes its
`.nrepl-port`, and the status bar drops back to `(not running)`; start it
again and the next Run reconnects.

![A JVM Clojure window after Run: the console connects to the server, loads the file, then answers two REPL entries in its namespace](../images/clj-attach.png)

Jonquil connects the first time you Run, or on any evaluation while it
holds no live connection, printing "; Connecting to the nREPL server on
port N..." while it does. If nothing's listening yet, the console says
so instead: "; No nREPL server here. Start one in `<dir>` with
`clojure -M:serve` (clojure-cli.repl), or any nREPL server that writes
`.nrepl-port`." A stale `.nrepl-port` naming a port nothing answers gets
"; .nrepl-port says port N, but nothing answers there. The server may
have stopped.", and a port that answers but isn't nREPL at all gets
"; Port N isn't an nREPL server Jonquil can use." Jonquil only ever
reads `.nrepl-port`: since it never started the server that owns the
file, it never writes to it or deletes it either.

#### Auto-require

A project's usual namespaces can load on their own, so the first line you
type at the prompt can call into them without a `require` first. List them
in the project's `.jonquil.edn`:

```clojure
;; .jonquil.edn
{:dialect :clj
 :auto-require [my.app.core my.app.dev]}
```

Each time Jonquil connects, it prints `; auto-require: my.app.core
my.app.dev` and sends one `(require 'my.app.core 'my.app.dev)` on its
session before whatever you asked to run. That means the first Run or REPL
entry, and again after a reconnect, because a restarted server has
forgotten everything. It isn't sent twice on the same connection.

If the require fails, a namespace that isn't on the classpath for example,
the server's own error shows in the console, followed by "; auto-require
failed (the error is above), so carrying on without it. It runs again on
the next connect." Your own Run or REPL line still goes out after it.
Stop during a slow require ends that evaluation as usual, and your request
isn't sent.

The list has to be a vector of namespace symbols. Anything else is
ignored, with a line like `; project: .jonquil.edn :auto-require should be
a vector of namespace symbols, like [my.app.core], not my.app.core, so it
is ignored`. Jonquil reads `.jonquil.edn` when a window chooses its
dialect, so an edit to the list takes effect in a new window.

Run sends the whole Definitions pane as a `load-file`, without naming a
namespace itself: the file's own `ns` form is what decides where the
server evaluates it, the same as any nREPL client's load-file. A
successful Run then moves the prompt to that namespace afterward. A REPL
entry typed at the prompt evaluates in the prompt's own current
namespace, and the prompt moves again if the reply itself names a
different one, after an `in-ns` call, say.

Cmd/Ctrl+E and Cmd/Ctrl+Enter (Run Form) both evaluate in the buffer's
own namespace instead, sent explicitly alongside the code rather than by
switching the session there first: Jonquil never sends a buffer's `ns`
form on its own. When the server hasn't loaded that namespace yet,
typically because the file hasn't been Run, the console says so instead
of guessing: "; demo.core isn't loaded in the server yet. Run the file
first." Running the file first, so the server knows the
namespace, clears it.

Stop sends the server's own `interrupt` and waits up to a second for the
evaluation to end. When it does, the result reads
`; interrupted`, the same as every other runtime. When it doesn't,
Jonquil falls back the way ClojureDart's Stop always works: "; The
server didn't stop the evaluation, so Jonquil detached. It may still be
running." The next evaluation reconnects with a fresh session. Switching
to a different file, or closing the window, while an evaluation is
running sends the same interrupt first, best effort.

`read-line` and other stdin reads work the way they do everywhere else
in Jonquil: type at the prompt while the status bar reads "Waiting for
input", and the line goes straight to the program. Cmd/Ctrl+D at that
prompt sends end-of-input when the server runs nREPL 1.8.0 or later, so
`read-line` returns nil. It sends only while a read is waiting, because
1.8.0 queues an early EOF and would end your next read with it. The EOF
doesn't stick: the next `read-line` waits for a line again.

On an older server nothing is sent and the read keeps waiting for an
actual line. The console says why, once per connection, the first time
you press Cmd/Ctrl+D. nREPL 1.7.0 fails an empty `stdin` op rather than
answering EOF, and the `clojure-cli.repl-server` 0.1.0 release brings in
1.7.0. To get 1.8.0 from it, add nREPL to the `:serve` alias:

```clojure
:serve {:extra-deps {io.github.clojure/clojure-cli.repl-server {:mvn/version "0.1.0"}
                     nrepl/nrepl {:mvn/version "1.8.0"}}
        :main-opts ["-m" "clojure-cli.repl.server"]}
```

Completion and quick documentation both ask the server, not just this
buffer. Ctrl+Space merges the server's own candidates in with the
offline ones, and Ctrl+Q or Shift+F1 shows a symbol's real docstring and
arglists, wherever it's defined. A definition outside the
buffer ends with a `Defined in` line naming the file and line number
and F12 opens it in the source panel (see Jump to definition), `Defined in clojure-1.12.5.jar!/clojure/core.clj:2748`
for something inside a jar, or a plain path and line for project code.
Before the buffer's own namespace has been loaded, quick doc and
completion still answer for names the prompt's own namespace already
knows, `clojure.core` functions before the file's first Run, say;
anything only the buffer's (not yet loaded) namespace would resolve
says so instead of guessing: "No docs: demo.core isn't loaded in the
server yet. Run the file first."

![Quick doc on clojure.core's map in a JVM Clojure window, ending with a Defined in line inside the Clojure jar](../images/clj-quick-doc.png)

Press Ctrl+Q or Shift+F1 a second time on the same popover, and Jonquil
asks the server for full documentation instead of the short quick-doc
text: clojure-cli.repl's own `doc` op, which returns ClojureDocs' full
entry, examples included, for a symbol that has one. The status bar
reads "Loading full docs for X..." while it waits, off the GTK thread,
so typing elsewhere isn't blocked. The very first full-doc request
downloads ClojureDocs' whole export, a few megabytes, into the server's
own config directory, which can take several seconds; every later
request, in that session or a later one, answers in milliseconds once
the server already has it. Full docs are only ever fetched for a symbol
quick doc already found, so pressing it twice on a name Jonquil couldn't
resolve tells you so again in the status bar, rather than asking the
server for a doc it already knows nothing useful about.

![Full documentation for map after a second quick-doc press, with ClojureDocs' examples](../images/clj-full-doc.png)

### jank (experimental)

Jonquil supports jank as an experimental dialect. Unlike ClojureDart and JVM Clojure, you don't start
anything yourself: Jonquil launches a `jank repl` for the window.

A window picks jank when the open file is a `.jank` file, or when the
project's `.jonquil.edn` says `{:dialect :jank}`. There is no project
marker for it. The status bar reads `jank (not running)` until the first
evaluation, then `jank (running)`.

![A jank window after Run: the console starts jank, prints the program's line, then answers a REPL entry in the file's namespace](../images/jank.png)

#### Installing jank

Install jank with `brew install jank-lang/jank/jank`, or point the
`JONQUIL_JANK` environment variable at a jank binary you already have.
Jonquil looks for the binary in this order: `JONQUIL_JANK`, `jank` on your
`PATH`, `/opt/homebrew/bin/jank`, then `~/.local/bin/jank`. With none of
them present, the first Run says so, naming both ways to fix it.

#### How it runs

Jonquil starts one jank per window, on the first evaluation, and not when
the window opens. It runs `jank repl` from a private temporary folder with
`--module-path <project>/src`, or the project folder when there's no
`src`. For a loose file with no project, the module path is the folder its
`ns` implies: `talk.core` saved as `…/src/talk/core.jank` gives `…/src`, so
a sibling such as `talk.util` loads. A file whose `ns` doesn't match its
path falls back to its own folder. jank
writes its `.nrepl-port` into that private folder, so your project's
`.nrepl-port`, if it has one, is never touched. Closing the window, or
quitting while jank is still starting, stops the process and removes the
folder.

Run sends the whole file as a `load-file`, and the prompt then moves to the
file's namespace. A REPL line evaluates in the prompt's namespace. Run
Selection and Run Form evaluate in the buffer's namespace, and when jank
hasn't loaded it yet they say "; demo.core isn't loaded in jank yet. Run
the file first." rather than running the code in `user`.

jank has no interrupt, so Stop kills the process and starts a fresh one:
"; Stopped. jank restarted, so earlier definitions are gone." Reset on
Run is on by default, as it is for every other runtime. With it ticked, a
Run restarts jank first ("; Reset: restarted jank."), but only when the
process has already served a request: the first Run just starts jank and
prints no Reset line. Uncheck it and Run builds on whatever the process
already has.

One caveat for a loose `.jank` file with no project: when the first
evaluation is a REPL line or Run Selection rather than Run, jank starts
without that module path, so a `require` of a sibling namespace fails. It lasts until a Run starts jank: a Run after a Stop, or any
Run with Reset on Run ticked. Stop alone doesn't help, because a REPL line
still starts jank without the path. Run the file first to avoid it.

#### Docs and completion

Quick documentation reads the var's own metadata, through an ordinary
eval, because nREPL's `lookup` op hangs on current jank. Jonquil never
sends it. While an evaluation is running there are no docs, since jank
answers one request at a time: the popover says "No docs while jank is
running."

Completion merges jank's own candidates with the offline ones. Homebrew's
jank 0.1 lists only the prompt namespace's own vars and the builtins, not
the vars a namespace referred from another one, so those names come from
the words in your buffer instead.

#### Limits

The console prints these when a jank window opens:

- jank is experimental, and Jonquil starts and stops its own jank process.
- Stop restarts jank, so earlier definitions are gone afterwards.
- Programs can't read input: jank hasn't implemented read-line yet.
- Jump to definition outside this file and the inspector aren't available.

Jonquil can't attach to a jank you started yourself yet, and F12 works
only inside the buffer.

jank also holds a program's printed output until the evaluation ends, so a
`println` inside a long loop shows nothing while it runs, and an evaluation
you Stop never shows its output at all. While something runs, the status
bar reads "Running…"; that's the signal to watch before pressing Stop.

Some loops don't compile in jank yet: `(loop [] (recur))`, and a `recur`
that follows a `when` in the loop body, fail with
`codegen/internal-failure`. Writing every `recur` inside an `if` branch
works, for example
`(loop [i 0] (if (< i n) (recur (inc i)) i))`.

## Driving Jonquil from an agent (MCP)

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

| Tool | What it does |
|---|---|
| `jonquil_eval` | Evaluates code as a REPL line; definitions persist between calls |
| `jonquil_run_file` | Runs a file's text the way the Run button does |
| `jonquil_check_brackets` | Checks brackets in text or a file; names the first unclosed or unmatched one |
| `jonquil_doc` | Documentation for a symbol in the project's runtime |
| `jonquil_complete` | Up to 50 completion candidates for a prefix |
| `jonquil_project` | The dialect, runtime and directory the other tools would use; starts nothing |

The dialect and runtime come from the IDE's own choice: the file's extension
and the project's `.jonquil.edn` (see [Choosing a dialect](#choosing-a-dialect)
and [`.jonquil.edn`](#jonquil-edn)), so a project with `{:runtime :nrepl}`
evaluates in a Jolt child and one with `{:dialect :clj}` attaches to its nREPL
server. `jonquil_eval` and `jonquil_run_file` take a `timeout_ms`; the default
deadline is 30 s, after which the evaluation is interrupted and the tool
answers with an error. Closing stdin ends the server and stops every runtime
it started. On a project with an out-of-process runtime, `jonquil_doc` and
`jonquil_complete` answer from the editor's offline tables until the first
evaluation has started the runtime, so evaluate once first to get
project-aware answers.

### Window mode

`jonquil --mcp --window [DIR]` opens the normal window and serves the same
protocol on stdio, so you watch the agent work. Register it the same way:

```bash
claude mcp add jonquil -- jonquil --mcp --window /path/to/project
# from a checkout:
claude mcp add jonquil -- jolt -M -m jonquil.main --mcp --window /path/to/project
```

Headless `jonquil --mcp` keeps its six tools unchanged. With `--window` the
server offers fifteen, and they act on the live window rather than on private
evaluators:

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

### Attaching to a window that is already open

`--window` starts a new window for each agent session. To hand an agent the
window you already have, with its tabs and its console, turn listening on in
`settings.edn`:

```edn
{:mcp-listen? true}
```

A window started with that setting serves the same fifteen tools on a socket
bound to 127.0.0.1, on a port the operating system picks, to one client at a
time. It publishes the port in `mcp/<pid>.edn` inside the settings directory
(next to `settings.edn`), one file per window. Then register the bridge:

```bash
claude mcp add jonquil -- jonquil --mcp-attach /path/to/project
```

`jonquil --mcp-attach [DIR]` finds the live window whose registered folder
matches `DIR` and relays JSON-RPC lines between its own stdio and that window.
Left bare, `jonquil --mcp-attach` attaches when exactly one window is
listening. With none, or with several and no `DIR` to choose between them, it
refuses and says why, listing the live windows when there is more than one.

The folder a window registers is the one it opened on. A window with no file
tree root registers its working directory. The registration is static: if you
use Open Folder to re-root the window later, the entry keeps the launch folder,
so attach still matches that one.

Detaching leaves the window running, with its tabs and unsaved edits as they
were, which is the difference from `--window`, where EOF closes the window. If
the relay loses its connection mid-stream it writes one line to stderr and
exits. The window removes its entry when it quits, and an entry left behind by
a crash is recognized by its dead pid and skipped. `bb mcp:smoke --attach`
exercises all of this end to end.

**Trust.** While `:mcp-listen?` is on, any local process that can reach the
loopback interface gets unauthenticated code execution in your account, plus
the window's file reach. That is the same family of exposure as running any
local nREPL server. The default is off, and nothing listens unless you turn it
on.

### Trust boundary

Whoever can start `jonquil --mcp` can run code as you. Evaluating is the point
of the tools, and nothing authenticates the caller. That is the model of MCP
over stdio: the client controls what it spawns, so you already decide who gets
to launch the server. It is also the same reach `bb nrepl` gives today. Don't
put it behind anything that lets someone else choose its command line or feed
its stdin.

## Keys

| Key | Where | Does |
|---|---|---|
| F5 | anywhere in the window | Run |
| Cmd/Ctrl+R | anywhere in the window | Run |
| Esc | anywhere in the window | Stop, only while something is running |
| Cmd/Ctrl+O | anywhere in the window | Open |
| Cmd/Ctrl+S | anywhere in the window | Save |
| Cmd/Ctrl+Shift+S | anywhere in the window | Save As |
| Cmd/Ctrl+W | anywhere in the window | Close the tab (asks first over unsaved changes); on the last tab, close the window, which quits the app |
| Cmd/Ctrl+T | anywhere in the window | New Untitled tab |
| Cmd/Ctrl+Shift+] | anywhere in the window | Next tab (wraps) |
| Cmd/Ctrl+Shift+[ | anywhere in the window | Previous tab (wraps) |
| Cmd/Ctrl+1 to 8 | anywhere in the window | Go to that tab |
| Cmd/Ctrl+9 | anywhere in the window | Go to the last tab |
| Cmd/Ctrl+Shift+E | anywhere in the window | Show or hide the file tree |
| Up/Down, Return | file tree | Move, and open a file or a folder |
| Right/Left | file tree | Open or close the selected folder |
| F2 | file tree | Rename the selected row |
| Delete, Cmd/Ctrl+Backspace | file tree | Move the selected row to the Trash |
| Shift+F10, Menu | file tree | Open the menu for the selected row |
| Escape | file tree | Give focus back to the editor |
| ( [ { " | Definitions editor | Auto-pair the closer, or wrap the selection |
| Tab | Definitions editor | Indent the line or selection |
| Shift+Tab | Definitions editor | Unindent the line or selection |
| Cmd/Ctrl+/ | Definitions editor | Toggle line comments |
| Cmd/Ctrl+; | Definitions editor | Toggle line comments |
| Cmd/Ctrl+Shift+F | Definitions editor | Format the whole file |
| Cmd/Ctrl+Option(Alt)+F | Definitions editor | Format the selection or current line |
| Cmd/Ctrl+Option(Alt)+L | Definitions editor | Format the selection or current line |
| Cmd/Ctrl+Z | anywhere in the window | Undo |
| Cmd/Ctrl+Shift+Z | anywhere in the window | Redo (GTK's own binding) |
| Cmd/Ctrl+Y | anywhere in the window | Redo |
| Cmd/Ctrl+E | anywhere in the window | Run the editor's selection, or its current line, at the console prompt |
| Cmd/Ctrl+Enter | Definitions editor | Run the form at the caret (in a comment block, the form inside it) |
| F6 | anywhere in the window | Move focus between the editor and the console |
| Escape | Definitions editor, console input | Close an open completion, quick doc or rename popover (before it would Stop) |
| Cmd/Ctrl+L | anywhere in the window | Clear the console's output history |
| Cmd/Ctrl+Shift+L | anywhere in the window | Clear the last entry's output (CIDER's C-c C-o) |
| Cmd/Ctrl+= (or +) | anywhere in the window | Zoom in |
| Cmd/Ctrl+- | anywhere in the window | Zoom out |
| Cmd/Ctrl+0 | anywhere in the window | Reset zoom |
| Cmd/Ctrl+F | anywhere in the window | Open the find bar |
| Cmd/Ctrl+H | anywhere in the window | Open the find bar with replace showing |
| Return, F3 or Cmd/Ctrl+G | find bar (F3/Cmd+G also from the editor) | Next match |
| Shift+Return, Shift+F3 or Cmd/Ctrl+Shift+G | find bar | Previous match |
| Escape | find bar | Close the find bar |
| Cmd/Ctrl+Shift+M | anywhere in the window | Cycle the theme (light, dark, solarized, navy, auto) |
| Return | console prompt | Evaluate, send the line as stdin, or insert a newline for an unfinished multi-line entry |
| Shift+Return | console prompt | Always insert a newline |
| Up | console prompt, first line of input | Recall the previous history entry |
| Down | console prompt, last line of input | Recall the next history entry |
| Cmd/Ctrl+D | console prompt, while waiting on input | Send end-of-input |
| Ctrl+Space | Definitions editor, console input | Open or commit completion |
| Ctrl+Q or Shift+F1 | Definitions editor | Quick documentation |
| Ctrl+Q or Shift+F1, pressed again on the same popover | Definitions editor | Full documentation (JVM Clojure only) |
| F12 or Ctrl+B | Definitions editor | Jump to definition |
| Shift+F6 or F2 | Definitions editor | Rename |
| Shift+F10 or right click | Definitions editor, console input | Open the context menu |
| Cmd/Ctrl+I | anywhere in the window | Inspect the last result |
| Cmd/Ctrl+Shift+I | anywhere in the window | Inspect the form at the caret |
| Up/Down | inspector panel | Move the selection |
| Return or Right | inspector panel | Go in on the selected row |
| Left or Backspace | inspector panel | Go out a level |
| Page Up/Page Down | inspector panel | Page |
| m | inspector panel | Toggle metadata |
| v | inspector panel | Toggle between the table and the list |
| d | inspector panel | Open the def prompt |
| Escape | inspector panel | Close the panel and focus the editor |
| Cmd/Ctrl+[ | Source panel | Back to where the panel was |
| Cmd/Ctrl+Shift+O | Source panel | Open the panel's project file in a tab |
| Escape | Source panel | Close the panel |

## Known limits

A few rough edges are already known and parked for a later phase, rather
than being surprises you'd otherwise have to rediscover:

- Return during an IME composition commits the syllable and inserts a
  plain newline rather than acting on it; a second Return does the rest.
  See the Unicode section above.
- Closing the window, or Cmd/Ctrl+W, while a program runs quits Jonquil
  at once, which ends the program too. It isn't a Stop: nothing is printed,
  and the program gets no chance to finish what it was doing.
- A keystroke in a very large Definitions pane can stall briefly. At
  20,000 lines the worst stall measured about 69 ms, and the colours catch
  up within about 0.7 s. At 2,000 lines it's about 10 ms. The benchmark is
  in [Development](development.md#benchmark).
- An unresolved symbol evaluates to an `Unbound` var rather than an error,
  when Jonquil is running Jolt out of process.
- A crash or SIGKILL of Jonquil leaves its out-of-process Jolt child
  running, since neither can run a hook. Ordinary Quit, Cmd/Ctrl+W,
  closing the window, and SIGTERM, SIGHUP or SIGINT all stop the child
  first. Exiting can take a while when a child is still starting: stop
  waits up to about 16 seconds for each start in flight.
- A wedged out-of-process child can hold a completion or quick-doc lookup
  for up to 300 ms before Jonquil falls back to its own offline answer.
- Stop against an unresponsive out-of-process child takes about 2.2
  seconds: a second waiting for it to answer the interrupt, a second more
  for it to exit once killed, and a little overhead either side.
- Stop against a ClojureDart app detaches instead of interrupting: the
  app keeps whatever it already has.
- No stdin, for the ClojureDart dialect.
- ClojureDart needs the `nrepl-poc` fork at `199d173d` or later; upstream
  ClojureDart carries no nREPL server to attach to.
- `println` output arrives for ClojureDart too, but is lost once the form
  that printed it throws.
- Jonquil never re-sends what you ran against ClojureDart: only
  its own `(+ 1 2)` wake-up probe repeats, and if the app's REPL still
  doesn't answer it, Jonquil says so instead of sending your code.
- No server-side completion for ClojureDart: only this buffer's own
  symbols and ClojureDart's own forms.
- Jump to definition for ClojureDart stays inside the buffer: the fork's
  `lookup` carries no file or line to jump to.
- Reset on Run isn't available for ClojureDart: hot reload can't unload a
  namespace, so every Run adds to the app instead of replacing it.
- A ClojureDart load stops at the first form the app's REPL compiler
  rejects, even though the app's own watcher still compiles the saved
  file.
- JVM Clojure attaches to a server you started, and never starts or stops
  the JVM itself.
- Reset on Run isn't available for JVM Clojure either: it's your own JVM,
  so Jonquil doesn't unload namespaces in it.
- Stdin closes only on nREPL 1.8.0 or later: on older servers a read
  waits for a line.
- Output JVM Clojure prints from a thread other than the one an
  evaluation ran on, after that evaluation finishes, isn't shown.
- Stop can't end a JVM Clojure loop that never checks for interruption,
  such as `(loop [] (recur))`, on nREPL 1.7 with JDK 20 or later. The
  server answers that it was interrupted and the console prints
  `; interrupted`, but the loop keeps running in your JVM. A sleep,
  blocking I/O and `read-line` do stop. Jonquil never kills the JVM, so
  the way out is to restart the server yourself.
- The inspector panel never refreshes itself: a later evaluation leaves
  it showing whatever it had until you open it again.
- A collection in the inspector panel shows at most 1000 entries.
- On JVM Clojure, the server keeps one inspector view per connection. If
  another client inspects on the same server, it replaces Jonquil's own
  view, and Jonquil's next move in the panel continues from whatever
  that other client left.
- Resizing the inspector panel doesn't reflow the rows already on
  screen; the new width only takes effect the next time you open it.
- No inspector for ClojureDart or for Jolt running out of process, in
  this version.
