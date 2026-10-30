# Contributing

This page covers the local setup a change needs, the gates it must pass,
and how to send it.

## What you'll need

Everything in [Getting started](getting-started.md): Jolt, GTK 4, and
babashka. Run `bb info` for the grouped task cheat-sheet; it is quicker to
scan than `bb tasks`.

## The loop

1. Branch from `main`.
2. While iterating, run the focused test namespace:
   `bb test jonquil.edit-test` (any namespace name works).
3. Before committing, run the full suite once: `bb test`.
4. `bb verify` is the pre-commit gate: lint, the unit suite, and the live
   nREPL suite together. `bb hooks:install` wires a fast version of it
   into a git pre-commit hook; `bb hooks:install:full` adds the tests.

Two suites open real windows and take keyboard focus: `bb gtk:selftest`
(the front-end self-test rows) and `bb smoke:binary` (drives the built
binary). Don't type while they run; a stray keystroke lands in the test
editor and fails a row without anything being wrong.

## Style

- `bb lint` runs clj-kondo over `src`, `test` and `dev`; keep it at zero
  errors and don't add warnings.
- `bb lsp:format` and `bb lsp:clean-ns` apply the formatter and namespace
  hygiene; the `-check` variants are dry runs.
- Match the idioms of the file you are in, comment density included. New
  functions with four or more parameters take an options map.

## Sending a change

Open a pull request against `main` with the gates green. Keep the subject
in the repo's conventional style (`feat(scope): ...`, `fix(scope): ...`,
`docs: ...`), and say in the body what you measured, not what you expect:
test counts, self-test rows, and any timing that matters.

Writing a change with an LLM's help is fine. Either way you own what you
submit, so it meets the same review, tests and gates as any other change.
[AI_POLICY.md](../../AI_POLICY.md) states the project's stance in full.

## Where things are

The [Architecture](architecture/index.md) page maps the namespaces. The
[Development](development.md) page describes every harness in detail,
including how the self-test rows are written and what the binary smoke
checks. `ROADMAP.md` in the repository root tracks phase status and the
parked items a change might want to pick up.
