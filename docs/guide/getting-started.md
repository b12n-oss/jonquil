# Getting started

## Requirements

- **[Jolt](https://github.com/jolt-lang/jolt)**, v0.8.23 or later (the
  version last tested). Building Jolt from source needs a threaded Chez
  Scheme 10.x on `PATH`; Jolt's own `make` provisions one if missing.
- **GTK4 and pango**. On macOS, `brew install gtk4` pulls in `pango` and
  `glib`. Jonquil loads them at startup rather than linking against them.
  On Linux they come from your distribution; see [Linux](#linux).

Jolt runs the tasks below (`jolt app:run`, `jolt app:build`, the tests) from
`bb.edn`, so you don't need babashka.

Each other dialect needs one more thing, only when you use it:

- **jank** (experimental): a `jank` binary; `brew install
  jank-lang/jank/jank` is simplest. See [jank](dialects/jank.md).
- **JVM Clojure**: Java 17+, the `clojure` CLI, and an nREPL server you
  start. See [JVM Clojure](dialects/jvm-clojure.md).
- **ClojureDart** (experimental): a running app built with the ClojureDart
  fork that serves nREPL (private for now). See [ClojureDart](dialects/clojuredart.md).
- **ClojureScript** (experimental): `npx shadow-cljs watch <build>` in a
  shadow-cljs project, or just the Clojure CLI and `node` for a loose
  `.cljs` file. See
  [attaching](dialects/clojurescript.md#clojurescript-experimental-attaching-to-a-shadow-cljs-build),
  [node](dialects/clojurescript.md#clojurescript-experimental-running-on-node)
  and [browser](dialects/clojurescript.md#clojurescript-experimental-running-in-the-browser).
- **Any nREPL server**: the server, plus `.jonquil.edn {:dialect :nrepl
  :nrepl {:port N}}`. See [Any nREPL server](dialects/other-nrepl.md).
- **Jolt out of process**, the default with a `deps.edn`: `git` on `PATH`
  and a network connection the first time, or a checkout of
  jolt-lang/nrepl. See
  [Running Jolt out of process](dialects/index.md#running-jolt-out-of-process).

## Running

```bash
jolt app:run                # opens an empty window
jolt app:run file.clj       # opens file.clj in the Definitions editor
jolt app:run ~/dev/project  # opens a folder: the file tree shows it, ready to pick a file
```

## Building

```bash
jolt app:build   # writes ./jonquil (gitignored); it loads GTK4 and pango from the system at startup
```

Then `./jonquil`, `./jonquil file.clj`, `./jonquil a.clj b.clj` (one tab
each) and `./jonquil <folder>` work like `jolt app:run`.
Jolt is needed only for a project that runs Jolt as a child (one with a
`deps.edn`), and Jonquil looks for it on your login shell's `PATH`; a
loose file runs inside the binary itself.

### The macOS quarantine note

The binary is unsigned on macOS. `jolt app:build` prints the fix if you
need to hand it to someone else:

```
jolt build: note — on macOS this binary is unsigned; to share it,
  `xattr -d com.apple.quarantine ./jonquil` on the target, or sign it.
```

Building and running it on the same machine needs neither step.

## The macOS app

```bash
jolt app:bundle    # builds ./jonquil, then ./Jonquil.app from it
jolt app:install   # the same, then moves it to ~/Applications/Jonquil.app and registers it
```

`jolt app:bundle` writes `./Jonquil.app` (gitignored) with the icon and
identifier `net.b12n.jonquil`. It declares Clojure source and Folder
document types as Alternate, so Jonquil shows under Open With and as a Dock
drop target but is never the default app. `jolt app:install` replaces an
existing `~/Applications/Jonquil.app` only if it is a Jonquil bundle.

The app still loads GTK4 and pango from Homebrew, so `brew install gtk4`
stays a requirement. It isn't notarized either, so the quarantine note
applies when you copy `Jonquil.app` to another Mac.

After a plain `jolt app:bundle`, the checkout's `./Jonquil.app` may show in
Open With next to the installed one. Delete it or run
`lsregister -u ./Jonquil.app` (it lives under
`/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/`).

### Where tools come from in a Finder launch

A Finder or Dock launch inherits a bare `PATH`, so `jolt`, `jank`,
`clojure` and `node` wouldn't be found. When `PATH` has none of
`/opt/homebrew/bin`, `/usr/local/bin`, `~/.local/bin` and `~/bin`, Jonquil
asks your login shell once (`$SHELL -il -c`, zsh if unset) and passes the
result to the Jolt child, jank and ClojureScript. If the shell fails or
prints nothing usable, Jonquil adds whichever of those folders exist. The
console says which happened:

```
; PATH from your login shell (zsh).
; PATH from well-known folders (your login shell timed out).
; PATH from well-known folders (your login shell failed).
; PATH from well-known folders (your login shell printed no PATH).
```

A terminal launch usually has one of those folders already, so no lookup
runs and no line appears.

## Linux

Jonquil builds and runs on Linux (tested on Ubuntu 26.04 with GTK 4.22, in
Docker on arm64 and amd64, see
[Development](development.md#linux-checks-in-docker)).

### Requirements

- **GTK 4 and pango from your distribution.** On Ubuntu that is
  `libgtk-4-1` and `libpango-1.0-0`, plus
  `adwaita-icon-theme`. Nothing is linked at build time.
- **Jolt** v0.8.23 or later. On x86_64 take the binary from Jolt's release
  page. There is no Linux arm64 binary, so build it from source (`make`
  provisions its own Chez Scheme).
- A desktop session. Checks ran under X11; Wayland is untested.

### Build and install

```bash
jolt app:build       # writes ./jonquil
jolt app:install     # the same build, then installs it for your user
jolt app:uninstall   # takes it out again
```

`./jonquil`, `./jonquil file.clj`, `./jonquil a.clj b.clj` and
`./jonquil <folder>` behave as they do on macOS.

`jolt app:install` writes four paths, all under `~/.local/share`:

| Path | What it is |
|---|---|
| `~/.local/share/jonquil/jonquil` | the binary |
| `~/.local/share/applications/net.b12n.jonquil.desktop` | the launcher |
| `~/.local/share/icons/hicolor/256x256/apps/net.b12n.jonquil.png` | the icon |
| `~/.local/share/mime/packages/net.b12n.jonquil.xml` | the MIME types |

The MIME package defines `text/x-clojure`, `application/edn` and
`text/x-jank`, so Jonquil appears in Open With but is never the default.
Nothing outside `~/.local/share` is touched, and the installer runs
`update-mime-database` and `update-desktop-database` when they are on
`PATH`. Running it again replaces the earlier install; `app:install` and
`app:uninstall` refuse a launcher without its `X-Jonquil-Install=1` line.
To start from a terminal afterwards, run
`~/.local/share/jonquil/jonquil [file or folder...]`, or
`gtk4-launch net.b12n.jonquil [file or folder...]` (Ubuntu's
`libgtk-4-bin`; GTK 3's `gtk-launch` works too).

### Where tools come from in a launcher start

The [lookup above](#where-tools-come-from-in-a-finder-launch) runs here
too. When `SHELL` is unset, it uses the shell in your `/etc/passwd` entry,
or `/bin/sh` (always, for LDAP accounts). It is skipped when any of the
four folders is already on `PATH`, so a tool living only in `~/.local/bin`
isn't found if `/usr/local/bin` is on it. Start from a terminal, or put the
tool in `/usr/local/bin`.

### Keys

Ctrl is the command key, so the shortcuts the guide writes as Cmd/Ctrl
work as Ctrl chords. Ctrl+Q is quick doc, as on macOS, and does not quit.
Ctrl+W closes the tab, and on the last tab it closes the window, which
quits. There's no separate quit chord.

## Settings

Jonquil keeps a few choices between runs in
`$XDG_CONFIG_HOME/jonquil/settings.edn`, or `~/.config/jonquil/settings.edn`
when that variable is unset, blank, or not an absolute path:

```clojure
{:theme :navy
 :file-tree {:visible? true :width 240}
 :dialect :jolt
 :reopen-tabs? true
 :remember-session? true}
```

- `:theme`: the theme you last picked with Cmd/Ctrl+Shift+M, one of
  `:light`, `:dark`, `:solarized`, `:navy` or `:auto` (follow macOS's
  light or dark setting).
- `:file-tree`: whether the tree was showing and how wide, saved when you
  toggle it and when you quit.
- `:dialect` (hand-written, optional): the dialect an untitled buffer or a
  file outside any project starts in (see
  [Choosing a dialect](dialects/index.md#choosing-a-dialect)).
- `:reopen-tabs?` (hand-written): `false` stops Jonquil saving and
  reopening the last session's tabs. Omitted or `true` keeps it on.
- `:remember-session?` (hand-written): `false` stops Jonquil recording what
  each project evaluated and offering it back as a replay (see
  [Last session's replay](using-jonquil.md#last-sessions-replay)). Omitted
  or `true` keeps it on. A project's `.jonquil.edn` can override it either
  way.
- `:mcp-listen?` (hand-written): `true` makes each window serve Jonquil's
  MCP tools on a loopback socket, so an agent can attach to an open window
  with `jonquil --mcp-attach` (see [Driving Jonquil from an agent](mcp.md)).

The tabs live next door in `sessions.edn`, one entry per project folder,
newest first, at most 20 (see
[Tabs from last time](files-and-tabs.md#tabs-from-last-time)). It changes
as you open and close tabs, which is why it's separate from the file you
edit by hand.

A missing file means the defaults; an unreadable file or unusable value
falls back per key with one console note. Saving changes only its own key,
refuses to overwrite a file it couldn't read, and is atomic. Delete the
file to reset everything, and `sessions.edn` to forget remembered tabs. The
replay records are in `replays/` next to it.

See [Using Jonquil](using-jonquil.md) for the rest of the window, and
[Development](development.md) for the test suite, the self-test harnesses,
and the lint and format tasks.
