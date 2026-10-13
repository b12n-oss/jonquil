# Getting started

## Requirements

- **[Jolt](https://github.com/jolt-lang/jolt)**, v0.8.15 or later. It
  carries the reader fixes from jolt-lang/jolt#1134 and #1138 that
  `(read)` from the console needs, and the closed-socket fix from
  jolt-lang/jolt#1183 that the ClojureDart attach connection relies on. Building Jolt from source needs a
  threaded Chez Scheme 10.x on `PATH`; if you don't already have one,
  Jolt's own `make` provisions Chez 10.4.1 for you, so there's usually
  nothing to install separately.
- **GTK4 and pango**. On macOS, install them with Homebrew: `brew install
  gtk4`, which pulls in `pango` and `glib` as dependencies. Jonquil loads GTK4
  and pango at startup rather than linking against them. On Linux they come
  from your distribution; see [Linux](#linux).

Jonquil was most recently tested on Jolt v0.8.21.

Each dialect other than in-process Jolt needs one more thing, and only when
you use it:

- **jank** (experimental): a `jank` binary. `brew install
  jank-lang/jank/jank` is the simplest; Jonquil also looks on `PATH`, in
  `~/.local/bin`, and at whatever `JONQUIL_JANK` names. See
  [jank](using-jonquil.md#jank-experimental).
- **JVM Clojure**: Java 17 or later and the `clojure` CLI, plus an nREPL
  server you start yourself in the project. See
  [JVM Clojure](using-jonquil.md#jvm-clojure-attaching-to-your-own-nrepl-server).
- **ClojureDart** (experimental): a running app built with the ClojureDart
  fork that serves nREPL. See
  [ClojureDart](using-jonquil.md#clojuredart-experimental-attaching-to-a-running-app).
- **Jolt out of process**: a checkout of jolt-lang/nrepl for the full
  nREPL middleware. See
  [Running Jolt out of process](using-jonquil.md#running-jolt-out-of-process).

## Running

```bash
bb app:run                # opens an empty window
bb app:run file.clj       # opens file.clj in the Definitions editor
bb app:run ~/dev/project  # opens a folder: the file tree shows it, ready to pick a file
```

## Building

```bash
bb app:build   # writes ./jonquil (gitignored); it loads GTK4 and pango from the system at startup
```

Once it's built, `./jonquil`, `./jonquil file.clj`, `./jonquil a.clj b.clj` (one tab each) and `./jonquil <folder>` work the same way as
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

## The macOS app

```bash
bb app:bundle    # builds ./jonquil, then ./Jonquil.app from it
bb app:install   # the same, then moves it to ~/Applications/Jonquil.app and registers it
```

`bb app:bundle` writes `./Jonquil.app` (gitignored) with the icon, an
`Info.plist` whose identifier is `net.b12n.jonquil`, and the binary inside.
The short version string comes from `git describe --tags --always` and the
build number from `git rev-list --count HEAD`. The bundle declares two
document types, Clojure source (`clj`, `cljc`, `cljd`, `jank`, `edn` and
`bb`) and Folder, both as Alternate. That means they show up under Open
With and as Dock drop targets, but Jonquil never becomes the default app for
anything. `bb app:install` replaces an existing `~/Applications/Jonquil.app`
only when it is a Jonquil bundle, and refuses otherwise.

The app still loads GTK4 and pango from Homebrew, so `brew install gtk4`
stays a requirement. It isn't notarized either: the quarantine note above
applies when you copy `Jonquil.app` to another Mac.

macOS registers any new `.app` it finds on disk, so after `bb app:bundle` the
checkout's `./Jonquil.app` may also appear in Open With next to the installed
one. `bb app:install` moves the bundle into `~/Applications`, so no second
copy is left. After a plain `bb app:bundle`, delete `./Jonquil.app` or run
`lsregister -u ./Jonquil.app` (it lives under
`/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/`).

### Where tools come from in a Finder launch

A Finder or Dock launch inherits a bare `PATH`, without Homebrew, so `jolt`
and `jank` wouldn't be found. When Jonquil's `PATH` has none of
`/opt/homebrew/bin`, `/usr/local/bin`, `~/.local/bin` and `~/bin`, it asks
your login shell once, in the background (`$SHELL -il -c`, zsh when `SHELL`
is unset), and hands the answer to the Jolt child and to jank. If the shell
takes more than 5 seconds but had already printed its `PATH`, Jonquil uses
that. If the shell fails, or times out with nothing usable printed, or prints
no `PATH`, Jonquil keeps the `PATH` it has and adds whichever of those four
folders exist and aren't already in it. It then says where
the `PATH` came from in the console, with one of these lines:

```
; PATH from your login shell (zsh).
; PATH from well-known folders (your login shell timed out).
; PATH from well-known folders (your login shell failed).
; PATH from well-known folders (your login shell printed no PATH).
```

A launch from a terminal (`./jonquil`, `bb app:run`) is unchanged. It
inherits your shell's `PATH`, which usually has one of those folders, so no
lookup runs and no line appears. A terminal `PATH` with none of them triggers
the lookup too.

## Linux

Jonquil builds and runs on Linux. It was tested on Ubuntu 26.04 with GTK 4.22,
in Docker on both arm64 and amd64 (see [Development](development.md#linux-checks-in-docker)),
and checked by hand in a noVNC desktop.

### Requirements

- **GTK 4 and pango from your distribution.** On Ubuntu that is `libgtk-4-1`
  and `libpango-1.0-0`, plus `adwaita-icon-theme` for the icon theme. Jonquil loads the shared libraries by
  name at startup (`libgtk-4.so.1`, `libpango-1.0.so.0`, and so on), so
  nothing is linked at build time.
- **Jolt** v0.8.15 or later, as above. On x86_64 take the binary from Jolt's
  release page. Jolt publishes no Linux arm64 binary, so there you build it
  from source (`make` provisions its own Chez Scheme).
- A desktop session. The checks so far ran under X11 (Xvfb, openbox and
  pcmanfm in Docker); a Wayland desktop on real hardware is untested.

### Build and install

```bash
bb app:build       # writes ./jonquil
bb app:install     # the same build, then installs it for your user
bb app:uninstall   # takes it out again
```

`./jonquil`, `./jonquil file.clj`, `./jonquil a.clj b.clj` and
`./jonquil <folder>` behave as they do on macOS.

`bb app:install` writes four paths, all under `~/.local/share`:

| Path | What it is |
|---|---|
| `~/.local/share/jonquil/jonquil` | the binary |
| `~/.local/share/applications/net.b12n.jonquil.desktop` | the launcher |
| `~/.local/share/icons/hicolor/256x256/apps/net.b12n.jonquil.png` | the icon |
| `~/.local/share/mime/packages/net.b12n.jonquil.xml` | the MIME types |

The MIME package defines `text/x-clojure` (`*.clj`, `*.cljc`, `*.cljd`,
`*.bb`), `application/edn` (`*.edn`) and `text/x-jank` (`*.jank`), each a
kind of `text/plain`. The launcher lists those three types, so Jonquil
appears in a file manager's Open With list. Nothing outside `~/.local/share`
is touched, and Jonquil is never made the default app for anything. Afterwards
the installer runs `update-mime-database` and `update-desktop-database` on
your `~/.local/share` folders when they are on `PATH`, and prints a note for
each that isn't.

Running it again replaces the earlier install. The launcher carries an
`X-Jonquil-Install=1` line, and `app:install` and `app:uninstall` refuse to
touch a launcher at that path without it, since that file isn't theirs. To
start Jonquil from a terminal after installing, use
`gtk-launch net.b12n.jonquil [file or folder...]`, or run
`~/.local/share/jonquil/jonquil` directly.

### Where tools come from in a launcher start

A start from the desktop launcher can inherit a short `PATH`, and the
lookup described in [the macOS section](#where-tools-come-from-in-a-finder-launch)
runs here too. When `SHELL` is unset, the shell it asks is the one in your
`/etc/passwd` entry (matched by user id), or `/bin/sh` when there is no entry
or its shell field is empty. Users whose account lives in LDAP or another
network source have no line in that file, so they get `/bin/sh`.

One gap remains. The lookup only runs when none of `/opt/homebrew/bin`,
`/usr/local/bin`, `~/.local/bin` and `~/bin` is on the `PATH`. A desktop
session that has `/usr/local/bin` on its `PATH` skips the lookup, so a tool
that lives only in `~/.local/bin` isn't found from a launcher start. Start
Jonquil from a terminal, or put the tool in `/usr/local/bin`.

### Keys

Ctrl is the command key, so the shortcuts the guide writes as Cmd/Ctrl work
as Ctrl chords. Ctrl+Q is quick doc, as it is on macOS, and it does not quit.
Closing the window quits. There is no Linux quit chord yet.

## Settings

Jonquil keeps a few choices between runs in
`$XDG_CONFIG_HOME/jonquil/settings.edn`, or `~/.config/jonquil/settings.edn`
when that variable is unset, blank, or not an absolute path. It's a small
EDN map:

```clojure
{:theme :navy
 :file-tree {:visible? true :width 240}
 :dialect :jolt}
```

- `:theme` is the theme you last picked with Cmd/Ctrl+Shift+M.
- `:file-tree` is whether the file tree was showing and how wide it was,
  saved when you toggle it and when you quit.
- `:dialect` is optional and only written by hand: the dialect an untitled
  buffer or a file outside any project starts in (see
  [Choosing a dialect](using-jonquil.md#choosing-a-dialect)).

Loading the file never throws. A missing file starts Jonquil with the
defaults (the light theme, and the tree hidden unless you opened a folder) and says nothing. A file that
can't be read, or a value Jonquil can't use, falls back to the default for
that key and says so once in the console. Saving changes only
the key it's saving and keeps everything else in the file, and it refuses
to overwrite a file it couldn't read, telling you in the console instead,
so a hand edit with a typo is never thrown away. Saving is atomic: Jonquil
writes a temp file beside the target and renames it into place, and if the
settings file is a symlink, the save follows it and the link itself
survives. To go back to every default, delete the file.

See [Using Jonquil](using-jonquil.md) for the rest of the window, and
[Development](development.md) for the test suite, the self-test harnesses,
and the lint and format tasks.
