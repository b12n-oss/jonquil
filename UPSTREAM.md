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

- `src/jonquil/syntax.clj`: `Character/toLowerCase` is not shimmed in Jolt. Two call sites (lines 602 and 810) replaced with `(first (str/lower-case (subs ... 0 1)))`. This change was verified in a trial lift on 2026-09-23; upstream's 13 pure deftests pass on Jolt after the change.
- `src/jonquil/syntax.clj`: `special-forms` and `core-builtins` read the Jolt dialect profile (`(:special-forms dialect/jolt)`, `(:builtins dialect/jolt)` in `src/jonquil/dialect.clj`), which holds upstream's two sets verbatim, so the file also requires `jonquil.dialect`. A re-sync that changes either set edits `src/jonquil/dialect.clj`.

## Syncing with upstream

To stay current with upstream developments:

1. Run `bb upstream:diff` to list upstream commits that touch `src/` or `test/` since the pin.
2. Review each commit; port what matters to Jonquil.
3. Bump `UPSTREAM_PIN` to the new commit SHA in the same commit that lands the port.

This keeps Jonquil's provenance transparent and drift visible.
