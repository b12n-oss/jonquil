# The macOS app and Linux

Part of the [architecture notes](index.md).

## The macOS app: PATH and Open With

The same machinery serves Linux; the section after this one covers what
differs there.

A Finder or Dock launch has a bare `PATH`, and Open With hands the app files
through GApplication rather than argv. Three small namespaces and a build
script cover both.

- `jonquil.tool-path` is pure. It knows the four well-known folders, decides
  whether a `PATH` needs a lookup (`needs-lookup?`: none of the folders is an
  entry), builds the probe's argv (the login, interactive shell printing
  `PATH` between two markers, so an rc file's banner can't be mistaken for
  the answer), parses the last marker pair, appends the existing well-known
  folders for the fallback, and resolves a program name against a `PATH`
  string. Anything that touches the disk arrives as an `exists?` argument.
- `jonquil.login-path` runs the lookup. `jonquil.main/-main` calls `start!`,
  which either records the process `PATH` or starts the shell in a future,
  once per process (a second call changes nothing). The shell's stdout goes
  to a temp file, so a single deadline (5 s) covers its whole run, where a
  pipe would also wait for every process still holding it open. On timeout,
  or once the output passes 1 MB, the process tree is killed and whatever it
  printed is still parsed. The tree's members are listed before the TERM and
  any still alive 300 ms later get KILL, since a `.zshrc` that ignores TERM
  passes that on to its children. The result is cached in a promise, and
  `answer` waits for it at most the deadline plus 1.5 s, long enough for that
  kill to finish. Nothing in it throws out. `console-line` turns the
  result into the sentence the window appends to the console from a future
  (through `on-gui!`), so the wait never sits on the GTK thread.
- Children don't trust their own lookup of bare names: `jolt.process`
  resolves a bare program name against Jonquil's own `PATH`, not against
  `:extra-env`. So `jonquil.nrepl.child` resolves `jolt` to an absolute path
  with `tool-path/resolve-program` over the login `PATH`, and both it and
  `jonquil.nrepl.jank-child` pass that `PATH` as `:extra-env {"PATH" ...}` so
  the programs the child itself starts see it too.
- Open With and Dock drops arrive as GApplication's `open` signal, which
  needs `G_APPLICATION_HANDLES_OPEN`. The application keeps
  `G_APPLICATION_NON_UNIQUE` so a second launch starts its own process
  instead of handing its activation to the running one over D-Bus.
  `on-app-open!` turns the `GFile**` array into local paths (a file with no
  local path is reported as not local and skipped), builds the window first
  if GTK hasn't yet, and calls the window's `:open-paths!`. That function
  re-roots and shows the tree for a directory (the same `folder-result!` as
  Open Folder) and sends a file through `open-file!`, the function behind
  Open, so tab rules are identical. A failure is written to the status line
  per file, so with several failures only the last remains visible.
- `dev/app_bundle.clj` builds `Jonquil.app`: `info-plist` is a pure function
  of the version, build number and extensions, and `replaceable?` is the
  rule install uses to replace only a Jonquil bundle. The bundle isn't
  re-signed because `jolt build` appends the program image after the
  launcher's Mach-O, and `codesign --force` then fails strict validation;
  the linker's ad-hoc signature on the launcher is
  what macOS checks, and the build task verifies it exists.

## Linux

The GTK layer is the same code on both systems. Four places differ, and the
install is a separate script.

### File arguments go through `open`

`jonquil.main/-main` no longer picks a file or folder out of the arguments.
It builds `argv` with `gtk-argv` (`"jonquil"`, then every argument made
absolute against the working directory) and `window/start!` hands that to
`g_application_run` through `run-application!`, as a NULL-terminated `char**`.
With more than the program name in it, GApplication emits `open` with the
files and not `activate`, which is how a terminal launch reaches the same
handler as Open With. With no arguments `argv` is not passed and `activate`
fires as before.

Absolute paths are the point. GApplication's option parser sees every
argument that starts with `-`, so a file named `-x.clj` would be taken for an
option and `--help` would print GTK's usage. A `--` isn't a separator here;
it arrives as a file called `--`. An absolute path never starts with a dash.
`gtk-argv` checks nothing on disk and canonicalises nothing, so an argument
that names nothing still reaches the `open` handler, which reports it on the
status line the way it reports a missing file from Finder.

When the `open` is what started the app, `launch-split` makes the first path
the window's launch option, a folder as `:dir` and anything else as `:file`,
exactly what the single command-line argument did before. The remaining
paths go to `open-paths` as tabs. Once a window exists, every path goes to
`open-paths`.

Linux sets the program name too. On X11 GTK writes `WM_CLASS` from it, and the
launcher's `StartupWMClass` is the app id, so `run-application!` calls
`g_set_prgname("net.b12n.jonquil")` before the loop starts. macOS keeps what
`g_application_run` derives from `argv[0]`.

### Which thread is GTK's

`gtk-thread?` in `dev/gtk_async.clj` used to call `pthread_main_np`, which
exists only on macOS, where `g_application_run` runs on the process's main
thread. It now asks GLib instead: `g_main_context_is_owner` on
`g_main_context_default`. That is 0 before `g_application_run` and 1 inside
the `open` handler, on both systems.

### The passwd lookup

With `SHELL` unset, `jonquil.tool-path/default-shell` decides which shell the
`PATH` lookup runs. On macOS it is `/bin/zsh`. Elsewhere `passwd-shell` finds
the line of `/etc/passwd` whose third field equals the process's uid
(`getuid()`), and takes its seventh field; a missing line, a blank shell or
a short line gives `/bin/sh`. It matches by uid because Jolt on Linux has no
`user.name` property. The file is read directly, with no `getent`, so a user
who exists only in LDAP or NSS gets `/bin/sh`.

### The keycode fallback

The Alt chords for format selection have a fallback on physical key codes for
macOS, where Option changes the keyval GDK reports (`MAC-KEYCODE-F`, 3, and
`MAC-KEYCODE-L`, 37). `keycode-fallback?` takes the OS name and is true only
on `"Mac OS X"`, because on Linux X11 keycode 37 is Left Control.

### `dev/app_linux.clj`: the install

`app-bundle/-main` dispatches to the `app-linux` namespace on Linux, which
leaves `dev/app_bundle.clj` unchanged in shape. `app-linux` keeps the pure
parts apart from the file work. `install-paths` names the four files for a
home directory, `desktop-entry` and `mime-xml` produce the launcher and the
shared-mime-info package, and `replaceable?` is the rule that an existing
launcher is replaced only when it carries the line `X-Jonquil-Install=1`. The
`Exec` line quotes the binary path by the Desktop Entry rules, with `%F` so a
file manager can pass files.

`install!` writes the MIME package, the icon and the launcher first and the
binary last, through a temp name and a rename so a running copy is replaced
rather than overwritten. A failed step removes the temp file and names what
was already written. Then it runs `update-mime-database` and
`update-desktop-database` when they are on `PATH`, and a note replaces each
that isn't. `uninstall!` deletes the four files and then `~/.local/share/jonquil`,
the only recursive delete. Both refuse a launcher that isn't Jonquil's.
`jonquil.app-linux-test` covers the pure parts and the file work against a
temp home.
