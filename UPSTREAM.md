# Upstream: DrClojure-Neo

**Repository:** https://github.com/kimtg/DrClojure-Neo  
**Pinned commit:** [`7056c61`](https://github.com/kimtg/DrClojure-Neo/commit/7056c61e09f8e9ad3c63fc4b1bd9ac35d15ad3ed) (2026-09-28), also in `UPSTREAM_PIN`  
**Checking for new commits:** `bb upstream:diff` (see [Porting and attribution](docs/guide/porting-and-attribution.md#staying-in-sync))

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

- `src/jonquil/syntax.clj`: `Character/toLowerCase` is not shimmed in Jolt. Two call sites (lines 607 and 815 at 2026-10-29) replaced with `(first (str/lower-case (subs ... 0 1)))`. This change was verified in a trial lift on 2026-09-23; upstream's 13 pure deftests pass on Jolt after the change.
- `src/jonquil/syntax.clj`: `compute-smart-indent` takes upstream `7056c61`'s rule (no dedent before a closer while a bracket before the caret is open) but not its method. Upstream scans the text before the caret on every Enter, which measured 82 ms at the end of a 4574-line file. Jonquil's private `prefix-unclosed?` answers the same question from the whole-buffer `compute-brackets` result: the painter's cached one when the cached text is equal (about 0.2 ms), else one uncached scan. It runs only when Enter is right before a closer. A test checks it against upstream's prefix scan at every caret position.
- `src/jonquil/syntax.clj`: `special-forms` and `core-builtins` read the Jolt dialect profile (`(:special-forms dialect/jolt)`, `(:builtins dialect/jolt)` in `src/jonquil/dialect.clj`), which holds upstream's two sets verbatim, so the file also requires `jonquil.dialect`. A re-sync that changes either set edits `src/jonquil/dialect.clj`.

## Not ported

From `7056c61` (Parinfer Smart Mode), only the `compute-smart-indent` fix was
ported. These parts weren't, because Parinfer is a feature decision that
hasn't been taken yet:

- `src/DrClojure/parinfer.clj` (Parinfer v3.13.1: Smart, Indent and Paren modes)
- the Parinfer menu, status-bar indicator and Ctrl+Alt+P wiring in `src/DrClojure/ui.clj`
- the Parinfer-aware branch of `handle-smart-enter!`, and `update-document-diff!`
- `test-resources/parinfer/*.json`, `test/DrClojure/json_helper.clj` and `test/DrClojure/parinfer_test.clj`

## Syncing with upstream

To stay current with upstream developments:

1. Run `bb upstream:diff` to list upstream commits that touch `src/` or `test/` since the pin.
2. Review each commit; port what matters to Jonquil.
3. Bump `UPSTREAM_PIN` to the new commit SHA in the same commit that lands the port.

This keeps Jonquil's provenance transparent and drift visible.

# Upstream: clojure-cli.repl

**Repository:** https://github.com/clojure/clojure-cli.repl  
**Pinned commit:** [`775e858`](https://github.com/clojure/clojure-cli.repl/commit/775e8587abd65d9f202b15865eae21e834b57903) (2026-09-28)  
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
