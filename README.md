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
bb app:run           # opens an empty window
bb app:run file.clj  # opens file.clj in the Definitions editor
```

## Building

```bash
bb app:build   # writes ./jonquil (gitignored); it loads GTK4 and pango from Homebrew at startup
```

The binary is unsigned on macOS. `bb app:build` itself prints the fix if you need to hand it to someone else:

```
jolt build: note — on macOS this binary is unsigned; to share it,
  `xattr -d com.apple.quarantine ./jonquil` on the target, or sign it.
```

## What works headless

The core runs without GTK: everything below, plus `jonquil.console` and `jonquil.session`. Run `bb test` to verify. As of 2026-09-23, 71 tests pass (506 assertions).

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
bb gtk:selftest           # real-window self-tests (opens windows; don't type while it runs)
bb docs:shots             # re-capture docs/images/*.png, one process per scene
bb smoke:binary           # build ./jonquil and drive it with cgevent key --pid (keys go only to its own windows)
```
