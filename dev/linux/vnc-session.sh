#!/bin/bash
# Runs inside the jonquil-linux container, from the copied tree (~/jonquil):
# a desktop on Xvfb, Jonquil installed under ~/.local/share, and the screen
# served on port 6080 through x11vnc and noVNC. Started by `bb linux:vnc`.
set -eu

export DISPLAY=:1 GDK_BACKEND=x11

# wait_for DESCRIPTION COMMAND...: poll up to 10 s for COMMAND to succeed.
wait_for() {
  what=$1; shift
  for _ in $(seq 1 100); do
    "$@" 2>/dev/null && return 0
    sleep 0.1
  done
  echo "timed out waiting for $what" >&2
  exit 1
}

Xvfb :1 -screen 0 1600x1000x24 &
wait_for "the X socket" test -S /tmp/.X11-unix/X1

# A failing install stops the script here, with its output on the terminal.
bb app:install

openbox &
wait_for openbox xprop -root _NET_SUPPORTING_WM_CHECK
pcmanfm --desktop &
# The folder window is handed to the desktop instance, so wait for that one.
wait_for "the pcmanfm desktop" sh -c 'xwininfo -root -tree | grep -q "1600x1000.*pcmanfm\|pcmanfm.*1600x1000"'

# Smoke files for the hand check.
mkdir -p ~/smoke/nrepl-project
cp docs/demos/hello.clj ~/smoke/hello.clj
echo '(println "hello from jank")' > ~/smoke/hello.jank
echo '{:runtime :nrepl}' > ~/smoke/nrepl-project/.jonquil.edn
printf '(def answer 42)\n(* 6 7)\n' > ~/smoke/nrepl-project/core.clj
pcmanfm ~/smoke &

x11vnc -display :1 -nopw -forever -shared -rfbport 5900 -quiet &
wait_for x11vnc bash -c 'exec 3<>/dev/tcp/127.0.0.1/5900'

exec websockify --web /usr/share/novnc 6080 localhost:5900
