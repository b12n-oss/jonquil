# Upstream: DrClojure-Neo

**Repository:** https://github.com/kimtg/DrClojure-Neo  
**Pinned commit:** `41c56b8` (2026-09-22)  
**Local checkout:** `~/dev/b12n-oss/kimtg--DrClojure-Neo`

## Lifted code

Jonquil ports the pure core of DrClojure, lifting its lexing, analysis, and completion code.

| Jonquil file | Upstream source | How |
|---|---|---|
| `src/jonquil/syntax.clj` | `src/DrClojure/syntax.clj` | Lifted |
| `test/jonquil/upstream/syntax_test.clj` | `test/DrClojure/syntax_test.clj` | Ported |
| `src/jonquil/paint.clj` | `src/DrClojure/syntax.clj` | Re-expresses `highlight-doc!` |
| `src/jonquil/edit.clj` | `src/DrClojure/ui.clj` | Re-expresses handlers |
| `src/jonquil/console.clj` | `src/DrClojure/ui.clj` | Re-expresses `attr-normal`, `attr-prompt`, `attr-result`, `attr-error`, `attr-system` as hex colours |
| `src/jonquil/eval.clj` | `src/DrClojure/eval.clj` | Reworks |
| `test/jonquil/eval_test.clj` | `test/DrClojure/eval_test.clj` | Ports upstream tests, plus new rows |
| `test/jonquil/paint_test.clj` | `test/DrClojure/syntax_test.clj` | Re-expresses 3 upstream deftests |
| `test/jonquil/edit_test.clj` | `test/DrClojure/core_test.clj` | Cases derived from upstream |

## Code deltas from upstream

- `src/jonquil/syntax.clj`: `Character/toLowerCase` is not shimmed in Jolt. Two call sites (lines 590 and 798 at 2026-10-28) replaced with `(first (str/lower-case (subs ... 0 1)))`. This change was verified in a trial lift on 2026-09-23; upstream's 13 pure deftests pass on Jolt after the change.
- `src/jonquil/syntax.clj`: `special-forms` and `core-builtins` read the Jolt dialect profile (`(:special-forms dialect/jolt)`, `(:builtins dialect/jolt)` in `src/jonquil/dialect.clj`), which holds upstream's two sets verbatim, so the file also requires `jonquil.dialect`. A re-sync that changes either set edits `src/jonquil/dialect.clj`.

## Syncing with upstream

To stay current with upstream developments:

1. Run `bb upstream:diff` to list upstream commits that touch `src/` or `test/` since the pin.
2. Review each commit; port what matters to Jonquil.
3. Bump `UPSTREAM_PIN` to the new commit SHA in the same commit that lands the port.

This keeps Jonquil's provenance transparent and drift visible.

# Upstream: clojure-cli.repl

**Repository:** https://github.com/clojure/clojure-cli.repl  
**Pinned commit:** `775e858`  
**Local checkout:** `~/dev/b12n-oss/clojure--clojure-cli.repl`  
**Licence:** EPL-1.0

## Lifted code

Jonquil ports the inspector's view code: the pure functions that turn a value into the panel's rows, paths and table cells, with the nREPL middleware wiring left behind.

| Jonquil file | Upstream source | How |
|---|---|---|
| `src/jonquil/inspect/view.clj` | `server/src/clojure_cli/repl/inspect.clj` | Lifted |

## Code deltas from upstream

- The ns becomes `jonquil.inspect.view`; the `:require` list drops the three `nrepl.*` requires (`nrepl.middleware`, `nrepl.misc`, `nrepl.transport`), which existed only to wire the nREPL middleware.
- The global `(def view (atom nil))` is gone. Every lifted function (`render`, `respond`, `start`, `toggle-table`, `in`, `out`, `page`, `toggle-meta`, `def-as`) takes `view` as its first argument instead and passes it on at every internal call; the atom is now owned by the caller.
- `sessions`, `remember-session`, `session-binding`, `op-fns`, `middleware` and the `set-descriptor!` call are gone along with the nREPL glue they served.
- `start`'s signature becomes `[view value rows cols]`, with the frame `{:label "*1" :value (datafy value)}` in place of upstream's `(session-binding session-id #'clojure.core/*1)` lookup.
- `def-as`'s signature becomes `[view target-ns var-name]`; `target-ns` is passed in directly in place of upstream's `(session-binding session-id #'clojure.core/*ns*)` lookup.
- Every other line inside the lifted functions is unchanged from upstream.
- Jonquil adds one function of its own, `run-op`, at the end of the file: a dispatch over the lifted ops that turns a throw into the current view plus `:note`, the way the server's `middleware` answered errors.
