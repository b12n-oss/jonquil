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

### The locations list

Three palette commands list places in the buffer, in a panel on the right:
**List the uses of the symbol at the caret** (code only, never comments,
strings or keywords), **List the definitions of the symbol at the caret**
(a `defmulti` and each of its `defmethod`s, for example), and **List every
match of the find query, or of the selection**. F8 goes to the next place
and Shift+F8 to the previous one, selecting it; a click on a row goes there
too. Places follow your edits, so a line added above one doesn't move it
off its text. A place whose text you deleted is reported gone, and stepping
past either end says so. A list belongs to the tab it came from: closing
that tab clears it.

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
(Back, Meta, Table, Def, Close), the rows, and a footer of keys. A
collection's rows read `label · preview`; maps sharing the same keys
become a table.

- Up and Down move. Return or Right goes in; Left or Backspace comes out.
- Page Up and Page Down page through long collections.
- `m` toggles metadata; `v` switches a table to a list and back.
- `d` prompts for a name and defines that var from the current value.
- Escape closes the panel (inside the `d` prompt, only the prompt).

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
