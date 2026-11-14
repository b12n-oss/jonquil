# Structural editing

Structural editing changes code by whole forms: it moves a bracket past the
next form, swaps two forms, or deletes one, and the brackets stay balanced.
Jonquil's set follows [Calva's paredit](https://calva.io/paredit/).

Every example on this page is run by Jonquil's test suite against the real
commands, so each row shows what the command does today. In an example `‸`
marks the caret and `«…»` a selection.

- **Where to find them.** All of them are under Edit > Structure in the
  menus and in the command palette (Cmd/Ctrl+Shift+P).
- **Keys.** None of them has a key until you turn on the **Calva paredit**
  key set in [Preferences](keys.md#key-sets). The Key column below shows
  that set's keys. They use Control on every system, a Mac included, where
  Alt is Option. A command that says `none` has no key in the set, and
  Preferences can give it one.
- **Your other keys stay as they are** on the default key set. The Calva
  set takes a few keys that normally do something else, listed under
  [Keys the Calva set changes](#keys-the-calva-set-changes). There is no
  strict mode that refuses an unbalanced delete.
- **Parinfer** works with or without these commands. With Parinfer on, the
  indentation follows the bracket a command moved, in every mode.
- **Undo.** Each command is one undo step.
- **Unfinished code.** Inside a list that isn't closed yet, these commands do
  nothing.

## Slurp and barf

| Command | Key | Before | After |
|---|---|---|---|
| Slurp forward: the list takes in the next form | Ctrl+Alt+Right; Linux: Ctrl+Alt+. | `(a‸) b c` | `(a‸ b) c` |
| Barf forward: the list gives up its last form | Ctrl+Alt+Left; Linux: Ctrl+Alt+, | `(a‸ b)` | `(a‸) b` |
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
| Drag the form forward, past the next one | Alt+Down, Ctrl+Alt+Shift+F | `(‸a b c)` | `(b ‸a c)` |
| Drag the form backward, past the one before | Alt+Up, Ctrl+Alt+Shift+B | `(a ‸b c)` | `(‸b a c)` |
| Transpose the two forms around the caret | Ctrl+Alt+T | `(a‸ b c)` | `(b a‸ c)` |
| Join the two lists or strings around the caret | Ctrl+Shift+J | `(a)‸ (b)` | `(a‸ b)` |
| Split the list or string at the caret | none | `(a ‸b)` | `(a)‸ (b)` |
| Convolute: turn the two lists around the caret inside out | Ctrl+Shift+C | `(let [x 1] (frob ‸a b))` | `(frob (let [x 1] ‸a b))` |

## Kill and copy

| Command | Key | Before | After |
|---|---|---|---|
| Kill the form at the caret, onto the clipboard | Ctrl+Shift+Delete | `(a ‸b c)` | `(a ‸c)` |
| Kill the form before the caret | Ctrl+Alt+Backspace | `(a b‸ c)` | `(a ‸ c)` |
| Kill to the end of the line, keeping the brackets balanced | Mac: Ctrl+K | `(a ‸b c)` | `(a ‸)` |
| Kill to the start of the line, keeping the brackets balanced | Mac: Cmd+Backspace | `(a b ‸c)` | `(‸c)` |
| Kill to the end of the list | Ctrl+Delete | `(a ‸b c)` | `(a ‸)` |
| Kill to the start of the list | Ctrl+Backspace | `(a b ‸c)` | `(‸c)` |
| Splice, killing what follows the caret in the list | Ctrl+Alt+Shift+Delete | `x (a ‸b c) y` | `x a ‸ y` |
| Splice, killing what comes before the caret in the list | Ctrl+Alt+Shift+Backspace | `x (a b ‸c) y` | `x ‸c y` |
| Copy the form at the caret | none | `(a ‸b c)` | `(a ‸b c)` |

## Select

| Command | Key | Before | After |
|---|---|---|---|
| Expand the selection: the form at the caret, then each list around it | Linux: Alt+Shift+Right | `(foo (b‸ar baz))` | `(foo («bar» baz))` |
| Shrink the selection back a step | Linux: Alt+Shift+Left | `(foo («bar» baz))` | `(foo (b‸ar baz))` |
| Extend the selection over the next form | Mac: Alt+Shift+Right; Linux: Ctrl+Shift+Right | `(a ‸b c)` | `(a «b» c)` |
| Extend the selection over the form before it | Mac: Alt+Shift+Left; Linux: Ctrl+Shift+Left | `(a b‸ c)` | `(a «b» c)` |

## Move

| Command | Key | Before | After |
|---|---|---|---|
| Move forward by a form | Ctrl+Right, Alt+Right | `(a ‸b c)` | `(a b‸ c)` |
| Move backward by a form | Ctrl+Left, Alt+Left | `(a b‸ c)` | `(a ‸b c)` |
| Move up to the start of the list around the caret | Ctrl+Up | `(a (b ‸c))` | `(a ‸(b c))` |
| Move down into the next list | Ctrl+Down | `(a‸ (b c))` | `(a (‸b c))` |
| Move out of the list, past its closer | Ctrl+Alt+Down | `(a (b ‸c) d)` | `(a (b c)‸ d)` |
| Move into the list before the caret, from the back | Ctrl+Alt+Up | `(a (b c) ‸d)` | `(a (b c‸) d)` |
| Move to the start of the list around the caret | Ctrl+Home | `(a b ‸c)` | `(‸a b c)` |
| Move to the end of the list around the caret | Ctrl+End | `(a ‸b c)` | `(a b c‸)` |

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

## Keys the Calva set changes

With the Calva paredit set on, these keys stop doing their usual job in the
editor and run a structural command, as they do in Calva:

- Ctrl+Left and Ctrl+Right, and Alt+Left and Alt+Right (Option on a Mac),
  move by a form. They normally move by a word.
- Ctrl+Up and Ctrl+Down move out of a list and into the next one.
- Ctrl+Home and Ctrl+End go to the start and end of the list around the
  caret. On Linux they normally go to the start and end of the file.
- Ctrl+Backspace and Ctrl+Delete kill to the start and end of the list. On
  Linux they normally delete a word.
- On a Mac, Ctrl+K kills to the end of the line with the brackets kept
  balanced, and Cmd+Backspace kills to its start.

A key of your own in `keys.edn` still wins over the set, so you can take
any of these back in Preferences.

## Calva keys the set leaves out

- **Raise and the rewraps.** Calva puts them on two-step keys (Ctrl+Alt+P
  then Ctrl+Alt+R, and Ctrl+Alt+R then a second key). Jonquil has no
  two-step keys yet.
- **Kill to the end and start of the line on Linux.** Calva uses Ctrl+K
  then a second key there.
- **Split.** Calva's Ctrl+Shift+S is Jonquil's Save As.
- **Expand and shrink the selection on a Mac.** Calva's Ctrl+W and
  Ctrl+Shift+W close the tab in Jonquil. On Linux they are on
  Alt+Shift+Right and Left.
- Calva's strict mode, its drags up and down a level, and its selection
  variants of the moves have no Jonquil command.

Every command left out is still under Edit > Structure and in the palette.

## Keys on other systems and layouts

On Linux, Ctrl+Alt+Right and Left often switch workspaces, so the set also
puts slurp and barf on Ctrl+Alt+. and Ctrl+Alt+, there.

On a Mac, Option changes the character a key sends, so Jonquil matches a key
held with Option by its position on a US keyboard. On another layout
(Dvorak, Colemak, AZERTY, QWERTZ) a binding written as Ctrl+Alt+S fires on
the key that sits where S does on a US keyboard.
