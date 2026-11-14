# Structural editing

Structural editing changes code by whole forms: it moves a bracket past the
next form, swaps two forms, or deletes one, and the brackets stay balanced.
Jonquil's set follows [Calva's paredit](https://calva.io/paredit/).

Every example on this page is run by Jonquil's test suite against the real
commands, so each row shows what the command does today. In an example `‸`
marks the caret and `«…»` a selection.

- **Where to find them.** All of them are under Edit > Structure in the
  menus and in the command palette (Cmd/Ctrl+Shift+P).
- **Keys.** A command has Calva's key where that key is a single chord that
  isn't already a normal key. The keys use Control on every system, a Mac
  included, where Alt is Option. The rest say `none`, and
  [Preferences](keys.md#your-own-keys) can give any of them a key.
- **Your other keys stay as they are.** The arrow keys, Backspace and Delete
  work as before, and there is no strict mode that refuses an unbalanced
  delete.
- **Parinfer** works with or without these commands. With Parinfer on, the
  indentation follows the bracket a command moved, in every mode.
- **Undo.** Each command is one undo step.
- **Unfinished code.** Inside a list that isn't closed yet, these commands do
  nothing.

## Slurp and barf

| Command | Key | Before | After |
|---|---|---|---|
| Slurp forward: the list takes in the next form | Ctrl+Alt+Right, Ctrl+Alt+. | `(a‸) b c` | `(a‸ b) c` |
| Barf forward: the list gives up its last form | Ctrl+Alt+Left, Ctrl+Alt+, | `(a‸ b)` | `(a‸) b` |
| Slurp backward: the list takes in the form before it | Ctrl+Alt+Shift+Left | `a (‸b)` | `(a ‸b)` |
| Barf backward: the list gives up its first form | Ctrl+Alt+Shift+Right | `(a ‸b)` | `a (‸b)` |

## Raise, splice and wrap

| Command | Key | Before | After |
|---|---|---|---|
| Raise the form at the caret over the list around it | none | `(a ‸b c)` | `‸b` |
| Splice: remove the brackets around the caret | Ctrl+Alt+S | `(a ‸b)` | `a ‸b` |
| Wrap the form at the caret in () | Ctrl+Alt+Shift+P | `‸a b` | `(‸a) b` |
| Wrap the form at the caret in [] | Ctrl+Alt+Shift+S | `‸a b` | `[‸a] b` |
| Wrap the form at the caret in {} | Ctrl+Alt+Shift+C | `‸a b` | `{‸a} b` |
| Wrap the form at the caret in #{} | Ctrl+Alt+Shift+E | `‸a b` | `#{‸a} b` |
| Wrap the form at the caret in "" | Ctrl+Alt+Shift+Q | `‸a b` | `"‸a" b` |
| Change the brackets around the caret to () | none | `[a ‸b]` | `(a ‸b)` |
| Change the brackets around the caret to [] | none | `(a ‸b)` | `[a ‸b]` |
| Change the brackets around the caret to {} | none | `(a ‸b)` | `{a ‸b}` |
| Change the brackets around the caret to #{} | none | `(a ‸b)` | `#{a ‸b}` |

## Drag, transpose, join and split

| Command | Key | Before | After |
|---|---|---|---|
| Drag the form forward, past the next one | Alt+Down | `(‸a b c)` | `(b ‸a c)` |
| Drag the form backward, past the one before | Alt+Up | `(a ‸b c)` | `(‸b a c)` |
| Transpose the two forms around the caret | Ctrl+Alt+T | `(a‸ b c)` | `(b a‸ c)` |
| Join the two lists or strings around the caret | Ctrl+Shift+J | `(a)‸ (b)` | `(a‸ b)` |
| Split the list or string at the caret | none | `(a ‸b)` | `(a)‸ (b)` |
| Convolute: turn the two lists around the caret inside out | Ctrl+Shift+C | `(let [x 1] (frob ‸a b))` | `(frob (let [x 1] ‸a b))` |

## Kill and copy

| Command | Key | Before | After |
|---|---|---|---|
| Kill the form at the caret, onto the clipboard | Ctrl+Shift+Delete | `(a ‸b c)` | `(a ‸c)` |
| Kill the form before the caret | Ctrl+Alt+Backspace | `(a b‸ c)` | `(a ‸ c)` |
| Kill to the end of the line, keeping the brackets balanced | none | `(a ‸b c)` | `(a ‸)` |
| Kill to the start of the line, keeping the brackets balanced | none | `(a b ‸c)` | `(‸c)` |
| Kill to the end of the list | none | `(a ‸b c)` | `(a ‸)` |
| Kill to the start of the list | none | `(a b ‸c)` | `(‸c)` |
| Splice, killing what follows the caret in the list | Ctrl+Alt+Shift+Delete | `x (a ‸b c) y` | `x a ‸ y` |
| Splice, killing what comes before the caret in the list | Ctrl+Alt+Shift+Backspace | `x (a b ‸c) y` | `x ‸c y` |
| Copy the form at the caret | none | `(a ‸b c)` | `(a ‸b c)` |

## Select

| Command | Key | Before | After |
|---|---|---|---|
| Expand the selection: the form at the caret, then each list around it | none | `(foo (b‸ar baz))` | `(foo («bar» baz))` |
| Shrink the selection back a step | none | `(foo («bar» baz))` | `(foo (b‸ar baz))` |
| Extend the selection over the next form | none | `(a ‸b c)` | `(a «b» c)` |
| Extend the selection over the form before it | none | `(a b‸ c)` | `(a «b» c)` |

## Move

| Command | Key | Before | After |
|---|---|---|---|
| Move forward by a form | none | `(a ‸b c)` | `(a b‸ c)` |
| Move backward by a form | none | `(a b‸ c)` | `(a ‸b c)` |
| Move up to the start of the list around the caret | none | `(a (b ‸c))` | `(a ‸(b c))` |
| Move down into the next list | none | `(a‸ (b c))` | `(a (‸b c))` |
| Move out of the list, past its closer | Ctrl+Alt+Down | `(a (b ‸c) d)` | `(a (b c)‸ d)` |
| Move into the list before the caret, from the back | Ctrl+Alt+Up | `(a (b c) ‸d)` | `(a (b c‸) d)` |
| Move to the start of the list around the caret | none | `(a b ‸c)` | `(‸a b c)` |
| Move to the end of the list around the caret | none | `(a ‸b c)` | `(a b c‸)` |

## Notes on a few commands

- **Slurp** takes from the next list out when the innermost one has nothing
  after it: `(a (b‸)) c` becomes `(a (b‸) c)`.
- **Kill form** and the other kills copy what they remove to the clipboard.
  Copy form copies without removing.
- **Kill to the end of the line** takes whole any form that starts on the
  line, and never goes past the closer of the list around the caret. Inside
  a string it stops at the closing quote. Kill to the start of the line
  mirrors it.
- **Join** joins two lists of the same kind, or two strings. **Split** splits
  the list at the caret, or the string the caret is in.
- **Shrink selection** goes back the way Expand selection came, and does
  nothing once the buffer or the selection has changed.
- **Rewrap** keeps a reader prefix such as a quote. A set's `#` goes with its
  brace.

## Keys that need another modifier on some systems

On Linux, Ctrl+Alt+Right and Left often switch workspaces, so Ctrl+Alt+. and
Ctrl+Alt+, slurp and barf as well.

On a Mac, Option changes the character a key sends, so Jonquil matches a key
held with Option by its position on a US keyboard. On another layout
(Dvorak, Colemak, AZERTY, QWERTZ) a binding written as Ctrl+Alt+S fires on
the key that sits where S does on a US keyboard.

## Why some have no key

Calva puts Raise and Rewrap on two-step keys, which Jonquil doesn't have
yet. It puts the selection and move commands on keys that already do
something else (Option+Right moves by a word on a Mac, Ctrl+Backspace
deletes a word on Linux), so those stay out of Jonquil's defaults.
