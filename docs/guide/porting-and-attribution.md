# Porting and attribution

Jonquil ports the idea of [DrClojure](https://github.com/kimtg/DrClojure-Neo)
to Jolt, lifting its pure code for the text-analysis core: the tokenizer,
bracket matching, navigation, smart indent, formatter, and doc and
completion lookup. `UPSTREAM.md` carries the file-by-file provenance table.
This page carries the reasoning behind it, plus the Jolt fixes the port
needed along the way.

The pin is DrClojure-Neo at `41c56b8` (2026-09-22), checked out locally at
`~/dev/b12n-oss/kimtg--DrClojure-Neo`.

## What was lifted, and what was rebuilt

`src/jonquil/syntax.clj` is a straight lift of upstream's `syntax.clj`, and
its test, `test/jonquil/upstream/syntax_test.clj`, ports upstream's own
tests over unchanged in spirit. Three files re-express upstream code rather
than copy it: `jonquil.paint` re-expresses `highlight-doc!`, `jonquil.edit`
re-expresses upstream's UI handlers as pure edit operations, and
`jonquil.console` re-expresses upstream's `attr-normal`/`attr-prompt`/
`attr-result`/`attr-error`/`attr-system` as hex colours a GTK theme can use
directly. `jonquil.eval` reworks upstream's eval engine rather than
following its shape, since Jonquil's Stop and stdin handling need a
different threading model than upstream's own.

Everything else, the GTK front end, the session and settings layers, the
themes, and every P4 help feature, is new code with no upstream
counterpart.

## The one code delta the lift needed

`Character/toLowerCase` isn't shimmed on this project's Jolt pin. Two call
sites in `src/jonquil/syntax.clj` (case-insensitive comparisons) were
rewritten as `(first (str/lower-case (subs s 0 1)))` instead. This was
verified in a trial lift on 2026-09-23: upstream's 13 pure deftests still
pass on Jolt once the substitution is in place, so the delta is mechanical,
not a behaviour change.

## Staying in sync

`bb upstream:diff` lists upstream commits since `UPSTREAM_PIN` that touch
`src` or `test`, against the checkout at `$DRCLOJURE_DIR` (default
`~/dev/b12n-oss/kimtg--DrClojure-Neo`). Review each one, port what
matters, and bump `UPSTREAM_PIN` to the new commit SHA in the same commit
as the port. Then `UPSTREAM.md` keeps matching the code Jonquil took from
upstream.

## The Jolt fixes this project needed along the way

Two reader bugs blocked the console's `(read)` outright: `(read)` from
stdin failed on Jolt through `LineNumberingPushbackReader`, filed as
jolt-lang/jolt#1133 (merged in PR #1134) and #1137 (merged in PR #1138,
2026-09-25). This project's pinned Jolt, v0.8.15, carries both; the fixes
landed well before that release, back when the getting-started
requirement still named an exact `main` commit rather than a tagged
version.

One more is filed but not blocking: `clojure.pprint/pprint` ignores a bound
`*out*` and writes to the process's real stdout instead, so Jonquil doesn't
refer it into the program namespace at all. It's a filing candidate for
Jolt itself rather than something Jonquil can work around locally, and it
was still true as of `v0.8.12-30-gc9808c39` when last re-checked, on
2026-09-25.

## The clojure-cli.repl lift: the inspector's view code

The inspector panel's own rendering logic is a second lift, this time from
[clojure-cli.repl](https://github.com/clojure/clojure-cli.repl), pinned at
`775e858`. Its terminal inspector keeps the pure half of the work, the
functions that turn a value into a path, a type line, and a page of rows
or a table, in one file: `server/src/clojure_cli/repl/inspect.clj`.
`src/jonquil/inspect/view.clj` lifts that file outright, with its three
`nrepl.*` requires dropped along with the nREPL middleware they served.

The one shape change the lift needed: upstream's functions all close over
one shared, global `(def view (atom nil))`, since a single terminal
inspector serves every client of one server. Jonquil's window keeps one
pane per window instead, so every lifted function now takes that view map
as an argument and returns the next one, rather than reading and swapping
the atom itself; the two places that used to read a session's own
bindings (`start`'s `*1`, `def-as`'s `*ns*`) take that value straight from
their caller in place of the session lookup. Every other line inside the
lifted functions is unchanged from upstream. `UPSTREAM.md` carries the
exact delta, function by function; `run-op`, added at the end of the
file, is Jonquil's own: the dispatcher that turns a throw into the
current view plus a note, the way upstream's own nREPL middleware did for
its wire replies.

clojure-cli.repl ships under EPL-1.0, the same licence DrClojure uses, so
the lift needs no separate licensing text of its own: `UPSTREAM.md`'s
table already names the one file it covers.

## Licence

Jonquil ports and adapts DrClojure by Kim Taegyoon and clojure-cli.repl's
inspector, both distributed under the Eclipse Public License 1.0 (see
`LICENSE` and `NOTICE` in the repository root). Code lifted from either
keeps its original copyright, and `UPSTREAM.md`'s table says exactly
which files that covers.
