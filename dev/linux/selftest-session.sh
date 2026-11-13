#!/bin/sh
# Runs inside the jonquil-linux container under xvfb-run, from the copied tree:
# a window manager, then the command given (jolt gtk:selftest by default).
# Started by `jolt linux:selftest`.
#
# The window manager is what makes a window "active", as on any desktop.
# Without one no window ever is, and rows that depend on it fail: the full-doc
# popover closes itself in a window that isn't active.
set -eu

openbox >/dev/null 2>&1 &
for _ in $(seq 1 100); do
  xprop -root _NET_SUPPORTING_WM_CHECK 2>/dev/null | grep -q 'window id' && break
  sleep 0.1
done
xprop -root _NET_SUPPORTING_WM_CHECK 2>/dev/null | grep -q 'window id' || {
  echo "selftest-session: openbox did not start" >&2
  exit 1
}

if [ "$#" -eq 0 ]; then set -- jolt gtk:selftest; fi
exec "$@"
