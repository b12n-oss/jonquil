# The macOS app and Linux

Part of the [architecture notes](index.md).

The full design record, with the reasoning and what was ruled out, is kept in `docs/history/architecture/macos-and-linux.md`.

## The macOS app: PATH and Open With

The same machinery serves Linux. A Finder or Dock launch has a bare `PATH`,
and Open With hands the app files through GApplication rather than argv.

- `jonquil.tool-path` is pure: it decides whether a `PATH` needs a lookup,
  builds the probe (a login, interactive shell printing `PATH` between two
  markers, so an rc banner can't be mistaken for it) and parses the answer.
  Disk access arrives as an `exists?` argument.
- `jonquil.login-path` runs the lookup once per process from
  `jonquil.main/-main`, under a 5 s deadline, killing the shell's process
  tree on timeout and parsing whatever it printed. Nothing throws out.
  `console-line` is appended through `on-gui!`, so the wait never sits on
  the GTK thread.
- `jolt.process` resolves bare program names against Jonquil's own `PATH`,
  not `:extra-env`. So `jonquil.nrepl.child` resolves `jolt` to an absolute
  path over the login `PATH`, and it and `jonquil.nrepl.jank-child` pass
  that `PATH` as `:extra-env` for the programs the child starts.
- Open With and Dock drops arrive as GApplication's `open` signal
  (`G_APPLICATION_HANDLES_OPEN`). The app stays `G_APPLICATION_NON_UNIQUE`,
  so a second launch is its own process. `on-app-open!` converts the files
  to local paths, builds the window if needed and calls `:open-paths!`: a
  directory re-roots the tree like Open Folder, a file goes through
  `open-file!`. Failures go to the status line per file.
- `dev/app_bundle.clj` builds `Jonquil.app`. The bundle isn't re-signed,
  because `jolt build` appends the program image after the launcher's
  Mach-O and `codesign --force` then fails strict validation; the build
  task checks the linker's ad-hoc signature, which is what macOS checks.

## Linux

The GTK layer is shared. Four things differ, and the install is a separate
script.

### File arguments go through `open`

`jonquil.main/-main` builds `argv` with `gtk-argv` (`"jonquil"` plus every
argument made absolute) and `window/start!` hands it to
`g_application_run`. With extra arguments GApplication emits `open`, so a
terminal launch reaches the same handler as Open With; with none it emits
`activate`.

Absolute paths are required: GApplication parses any argument starting with
`-` as an option, and `--` is not a separator (it arrives as a file named
`--`). `gtk-argv` checks nothing on disk, so a missing file is reported on
the status line like one from Finder.

When `open` started the app, `launch-split` makes the first path the launch
option (a folder as `:dir`, otherwise `:file`) and opens the rest as tabs.
Afterwards every path goes to `open-paths`. On Linux `run-application!` also
calls `g_set_prgname("net.b12n.jonquil")` so `WM_CLASS` matches the
launcher's `StartupWMClass`.

### Which thread is GTK's

`gtk-thread?` in `dev/gtk_async.clj` uses `g_main_context_is_owner` on the
default context, which works on both systems (`pthread_main_np` is
macOS-only).

### The passwd lookup

With `SHELL` unset, `default-shell` is `/bin/zsh` on macOS. Elsewhere
`passwd-shell` reads the shell field of the `/etc/passwd` line matching
`getuid()` (Jolt on Linux has no `user.name`), falling back to `/bin/sh`.
It doesn't use `getent`, so LDAP/NSS-only users get `/bin/sh`.

### The keycode fallback

The Alt chords for format selection fall back to physical key codes only on
macOS (`keycode-fallback?`), where Option changes the reported keyval. On
Linux X11 keycode 37 is Left Control.

### `dev/app_linux.clj`: the install

`app-bundle/-main` dispatches to `app-linux`. `install!` writes the
shared-mime-info package, icon and `.desktop` launcher, then the binary last
(temp name and rename, so a running copy is replaced), and runs
`update-mime-database` and `update-desktop-database` when present. A
launcher is replaced only if it carries `X-Jonquil-Install=1`; both
`install!` and `uninstall!` refuse any other. `uninstall!` removes the four
files and `~/.local/share/jonquil`, its only recursive delete.
