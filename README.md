<p align="center"><img src="docs/images/logo.svg" alt="Jonquil logo: a yellow jonquil between a pair of parentheses" width="128"></p>

# Jonquil

Jonquil is a DrRacket-style Clojure IDE written in Jolt: a Definitions editor, an Interactions console, Run and a Stop that really stops. It ports the idea of [DrClojure](https://github.com/kimtg/DrClojure-Neo), lifting its pure code for the text-analysis core (tokenizer, brackets, navigation, smart indent, formatter, doc and completion lookup).

**Status: alpha, the window, editing, themes and help work**

![Jonquil's window: a toolbar, the Definitions editor above the Interactions console, and a status bar](docs/images/window.png)

See [Using Jonquil](docs/guide/using-jonquil.md) for a full walkthrough: the window layout, Run versus the REPL prompt, stdin, Stop, Reset on Run, editing keys, find and replace, Open and Save, themes, help, and the keys.

![Jonquil's window in the dark theme](docs/images/theme-dark.png)

Ctrl+Space completes as you type, with a documentation preview beside the candidate list, in both the Definitions editor and the console's input line. Ctrl+Q or Shift+F1 shows a symbol's documentation on the spot. F12 or Ctrl+B jumps to where it's defined, and Shift+F6 or F2 renames every code use of it. Shift+F10 or a right click reaches all of it from a context menu.

![The completion popup open over a partial word, with a candidate list and its doc preview](docs/images/completion.png)

## Requirements

- [Jolt](https://github.com/jolt-lang/jolt) built from `main` at `c9808c39` or later: v0.8.12 plus the reader fixes from jolt-lang/jolt#1134 and #1138, which `(read)` from the console needs
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

The core runs without GTK: everything below, plus `jonquil.console` and `jonquil.session`. Run `bb test` to verify. As of 2026-09-26, 106 tests pass (744 assertions).

- `jonquil.syntax`: Tokenization, bracket matching, indentation analysis, formatting and doc lookup.
- `jonquil.paint`: Syntax highlighting plan from tokens and rainbow brackets.
- `jonquil.docs`: Doc strings and completion candidates for an eval namespace, falling back to `user` when that namespace is gone.
- `jonquil.edit`: Pure edit operations (smart enter, auto-pair, indent, rename, etc.).
- `jonquil.eval`: The eval engine with `run-interruptible` Stop, output streaming and REPL history.
- `jonquil.help`: The decisions behind completion, quick doc, jump to definition and rename.

## Commands

```bash
bb check                  # require every namespace, exit 0
bb test                   # all test namespaces; exits 1 on any failure or error
bb test jonquil.edit-test # one namespace (the TDD loop)
bb gtk:selftest           # real-window self-tests (opens windows; don't type while it runs)
bb docs:shots             # re-capture docs/images/*.png, one process per scene
bb smoke:binary           # build ./jonquil and drive it with cgevent key --pid (keys go only to its own windows)
```

`bb info` (or `jolt info`, since jolt reads this same `bb.edn`) prints the full task list grouped by what it's for, including the quality and git-hook tasks below.

## Quality and git hooks

```bash
bb lint                 # clj-kondo over src, test and dev (report only)
bb lint:strict          # the same, but exit non-zero if there are any findings
bb lsp:format-check     # clojure-lsp format, dry run
bb lsp:clean-ns-check   # clojure-lsp clean-ns, dry run
bb lsp:check            # the two dry runs together
bb verify               # lint (report) + test (must pass): the pre-commit gate
bb hooks:install        # a fast pre-commit hook: lint errors + format --dry
bb hooks:install:full   # the same, plus the test suite (slower)
bb hooks:uninstall      # remove the git pre-commit hook
```

`.github/workflows/lint.yml` runs the same lint and format check on GitHub
Actions, but only when triggered by hand from the Actions tab. Jonquil is a
private repo for now, so the workflow carries no push, pull request or
schedule trigger.

## The name

A jonquil (*Narcissus jonquilla*) is a small, scented narcissus with several yellow flowers to a stem.

<p align="center"><img src="docs/images/jonquil-1787.jpg" alt="Botanical plate of Narcissus jonquilla: a bulb with roots, long narrow leaves and a stem of small yellow flowers" width="240"></p>

*Narcissus jonquilla* by Sydenham Edwards, from William Curtis's *Botanical Magazine* (1787). Public domain, via [Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Narcissus_jonquilla_1787.jpg). The logo in `docs/images/logo.svg` was drawn for this project.
