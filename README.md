# Jonquil

Jonquil is a DrRacket-style Clojure IDE written in Jolt: a Definitions editor, an Interactions console, Run and a Stop that really stops. It ports the idea of [DrClojure](https://github.com/kimtg/DrClojure-Neo), lifting its pure code for the text-analysis core (tokenizer, brackets, navigation, smart indent, formatter, doc and completion lookup).

**Status: alpha, the window works**

![Jonquil's window: a toolbar, the Definitions editor above the Interactions console, and a status bar](docs/images/window.png)

See [Using Jonquil](docs/guide/using-jonquil.md) for a full walkthrough: the window layout, Run versus the REPL prompt, stdin, Stop, Reset on Run, Open and Save, and the keys.

## Requirements

- [Jolt](https://github.com/jolt-lang/jolt) v0.8.11
- GTK4 and pango, installed via Homebrew

## Running

```bash
bb run           # opens an empty window
bb run file.clj  # opens file.clj in the Definitions editor
```

## Building

```bash
bb build   # writes ./jonquil (gitignored), a self-contained binary
```

The binary is unsigned on macOS. `bb build` itself prints the fix if you need to hand it to someone else:

```
jolt build: note — on macOS this binary is unsigned; to share it,
  `xattr -d com.apple.quarantine ./jonquil` on the target, or sign it.
```

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
