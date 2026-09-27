# Getting started

## Requirements

- **[Jolt](https://github.com/jolt-lang/jolt)**, v0.8.13 or later, which
  already carries the reader fixes from jolt-lang/jolt#1134 and #1138
  that `(read)` from the console needs. Building Jolt from source needs a
  threaded Chez Scheme 10.x on `PATH`; if you don't already have one,
  Jolt's own `make` provisions Chez 10.4.1 for you, so there's usually
  nothing to install separately.
- **GTK4 and pango**, installed via Homebrew: `brew install gtk4`, which
  pulls in `pango` and `glib` as dependencies. Jonquil loads GTK4 and pango
  from Homebrew at startup rather than linking against them.

Linux is a target Jonquil's native library lookup already knows about
(`deps.edn` lists `libgtk-4.so.1`, `libpango-1.0.so.0`, and so on), but a
full Linux build and run hasn't shipped yet; ROADMAP.md tracks it under
Phase 5.

## Running

```bash
bb app:run           # opens an empty window
bb app:run file.clj  # opens file.clj in the Definitions editor
```

## Building

```bash
bb app:build   # writes ./jonquil (gitignored); it loads GTK4 and pango from Homebrew at startup
```

Once it's built, `./jonquil` and `./jonquil file.clj` work the same way as
`bb app:run` does, without needing Jolt or babashka on `PATH` at all.

### The macOS quarantine note

The binary is unsigned on macOS. `bb app:build` prints the fix if you need
to hand it to someone else:

```
jolt build: note — on macOS this binary is unsigned; to share it,
  `xattr -d com.apple.quarantine ./jonquil` on the target, or sign it.
```

Building and running it yourself on the same machine needs neither step;
Gatekeeper only objects to a binary that arrived from somewhere else.

## Settings

Jonquil remembers one thing today: the theme you last picked with
Cmd/Ctrl+Shift+M. It's saved to `$XDG_CONFIG_HOME/jonquil/settings.edn`, or
`~/.config/jonquil/settings.edn` when that variable is unset, blank, or not
an absolute path, as a small EDN map: `{:theme :navy}`, or whichever theme
you last chose.

Loading the file never throws. A missing file starts Jonquil in light,
the default theme, with nothing said about it. A file that can't be read,
or that names a theme Jonquil doesn't know, also falls back to light, but
says so once in the console. Saving is atomic: Jonquil writes a temp file
beside the target and renames it into place, and if the settings file is a
symlink, the save follows it and the link itself survives. To go back to
light, delete the file.

See [Using Jonquil](using-jonquil.md) for the rest of the window, and
[Development](development.md) for the test suite, the self-test harnesses,
and the lint and format tasks.
