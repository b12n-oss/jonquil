# Contributing

This page covers the local setup a change needs, the gates it must pass,
and how to send it.

## What you'll need

Everything in [Getting started](getting-started.md): Jolt and GTK 4. Jolt
runs the tasks too. Run `jolt info` for the grouped task cheat-sheet; it is quicker to
scan than `jolt tasks`.

## The loop

1. Branch from `main`.
2. While iterating, run the focused test namespace:
   `jolt test jonquil.edit-test` (any namespace name works).
3. Before committing, run the full suite once: `jolt test`.
4. `jolt verify` is the pre-commit gate: lint, the unit suite, and the live
   nREPL suite together. `jolt hooks:install` wires a fast version of it
   into a git pre-commit hook; `jolt hooks:install:full` adds the tests.

Two suites open real windows and take keyboard focus: `jolt gtk:selftest`
(the front-end self-test rows) and `jolt smoke:binary` (drives the built
binary). Don't type while they run; a stray keystroke lands in the test
editor and fails a row without anything being wrong.

## Style

- `jolt lint` runs clj-kondo over `src`, `test` and `dev`; keep it at zero
  errors and don't add warnings.
- `jolt metrics:gate` must pass. Nothing under `src/`, `test/`, `dev/` or `ci/` may cross
  a jolt-metrics critical limit, so split the function or the file. Unused
  code goes, unless `ci/metrics-allow.edn` names it with why it stays.
- `jolt lsp:format` and `jolt lsp:clean-ns` apply the formatter and namespace
  hygiene; the `-check` variants are dry runs.
- Match the idioms of the file you are in, comment density included. New
  functions with four or more parameters take an options map.

## Sending a change

Open a pull request against `main` with the gates green.

Keep the subject in the repo's conventional style (`feat(scope): ...`,
`fix(scope): ...`, `docs: ...`). In the body, say what you measured: test
counts, self-test rows, and any timing that matters.

Writing a change with an LLM's help is fine. Either way you own what you
submit, so it meets the same review, tests and gates as any other change.
[AI_POLICY.md](../../AI_POLICY.md) states the project's stance in full.
Leave the assistant's own credit lines out of commit messages and the pull
request description (a `Co-Authored-By` naming it, a "Generated with"
footer): a change is described by what it does, and a check on every pull
request fails on them (see
[Development](development.md#the-attribution-workflow)).

## Where things are

The [Architecture](architecture/index.md) page maps the namespaces. The
[Development](development.md) page describes every harness in detail,
including how the self-test rows are written and what the binary smoke
checks.
