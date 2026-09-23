# Jonquil

Jonquil is a DrRacket-style Clojure IDE written in Jolt: a Definitions editor, an Interactions console, Run and a Stop that really stops. It ports the idea of [DrClojure](https://github.com/kimtg/DrClojure-Neo), lifting its pure code for the text-analysis core (tokenizer, brackets, navigation, smart indent, formatter, doc and completion lookup).

**Status: pre-alpha, no GUI yet**

## What works headless

All of Phase 1 (core utilities) runs without GTK. Run `bb test` to verify. As of 2026-09-23, 54 tests pass (454 assertions).

- `jonquil.syntax`: Tokenization, bracket matching, indentation analysis, formatting and doc lookup.
- `jonquil.paint`: Syntax highlighting plan from tokens and rainbow brackets.
- `jonquil.docs`: Doc strings and completion candidates for an eval namespace.
- `jonquil.edit`: Pure edit operations (smart enter, auto-pair, indent, rename, etc.).
- `jonquil.eval`: The eval engine with `run-interruptible` Stop, output streaming and REPL history.

## Commands

```bash
bb check                  # require every namespace, exit 0
bb test                   # all test namespaces; exits 1 on any failure or error
bb test jonquil.edit-test # one namespace (the TDD loop)
```
