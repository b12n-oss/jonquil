# Porting and attribution

Jonquil ports the idea of [DrClojure](https://github.com/kimtg/DrClojure-Neo)
to Jolt, lifting its pure code for the text-analysis core: the tokenizer,
bracket matching, navigation, smart indent, formatter, and doc and
completion lookup. `UPSTREAM.md` carries the file-by-file provenance table.
This page carries the reasoning behind it, plus the Jolt fixes the port
needed along the way.

The pin is [DrClojure-Neo](https://github.com/kimtg/DrClojure-Neo) at commit
[`7056c61`](https://github.com/kimtg/DrClojure-Neo/commit/7056c61e09f8e9ad3c63fc4b1bd9ac35d15ad3ed) (2026-09-28), recorded in `UPSTREAM_PIN`.
Most of the lifted code dates from the earlier pin `41c56b8` (2026-09-22);
`7056c61` brought only its smart-indent fix, and its Parinfer half isn't
ported (see `UPSTREAM.md`).

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

`bb upstream:diff` lists the upstream commits since `UPSTREAM_PIN` that
touch `src` or `test`. It needs a clone of DrClojure-Neo with a remote
named `upstream` pointing at the original repository, and `DRCLOJURE_DIR`
set to that clone's folder:

```sh
git clone https://github.com/kimtg/DrClojure-Neo
git -C DrClojure-Neo remote add upstream https://github.com/kimtg/DrClojure-Neo
export DRCLOJURE_DIR=$PWD/DrClojure-Neo
bb upstream:diff
```

The task fetches `upstream` and compares the pin with `upstream/main`.
Review each commit, port what matters, and bump `UPSTREAM_PIN` to the new
commit SHA in the same commit as the port, so `UPSTREAM.md` keeps
matching the code Jonquil took from upstream.

## The Jolt fixes this project needed along the way

Two reader bugs blocked the console's `(read)` outright: `(read)` from
stdin failed on Jolt through `LineNumberingPushbackReader`, filed as
jolt-lang/jolt#1133 (merged in PR #1134) and #1137 (merged in PR #1138,
2026-09-25). Every Jolt from v0.8.15 on carries both; the fixes
landed well before that release, back when the getting-started
requirement still named an exact `main` commit rather than a tagged
version.

`clojure.pprint/pprint` used to ignore a bound `*out*` and write to the
process's real stdout, so Jonquil left it out of the program namespace.
The cause was that the one-argument `pprint` and `(cl-format true ...)`
called `clojure.core/print` while `*out*` was still pprint's own buffer.
That was filed as jolt-lang/jolt#1354 and fixed in #1355, which shipped in
Jolt v0.8.22, and `pprint` is referred into the prompt's namespace the
same way `doc` is. `pp` was an empty stub until jolt-lang/jolt#1389 made it
expand to `(pprint *1)`, as on the JVM. That shipped in Jolt v0.8.23, which
Jonquil now needs, and `pp` is referred next to `pprint`.

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
pane per window instead, so every lifted function now takes the view atom
as its first argument, and reads and swaps that one instead of a global; the two places that used to read a session's own
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
