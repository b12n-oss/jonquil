#!/bin/sh
# Fails when commit messages, or a text such as a pull request description,
# carry an AI assistant's attribution: a Claude-Session trailer, a claude.ai
# session link, a Co-Authored-By line naming Claude, the "Generated with Claude
# Code" footer, or an anthropic.com noreply address. A change is described by
# what it does; who or what typed it isn't part of the record. AI_POLICY.md
# says how the project stands on AI assistance.
#
#   ci/attributioncheck.sh <range>     the commits in a git range (a..b)
#   ci/attributioncheck.sh --text      the text on stdin
set -eu

# Four commits from September 2026 carry such lines. They were public before
# this rule, and taking the lines out would rewrite history for everyone who
# has a clone, so they stay and are named here. Nothing is added to this list.
before_the_rule="
253f7d3bb3c3f7a0acd31080b1b1c01a73987a22
f934beec8f81ad9bba95db1163ac27a5867e3856
1bc6256267c5cdb10a379412bfd0860c90a59879
6c36dad12a5cb0bd6b79c40d5a7d1148d2b32cc4
"

markers='Claude-Session:|claude\.ai/code/session_|Co-Authored-By:.*[Cc]laude|Generated with \[?Claude Code|noreply@anthropic\.com'

case "${1:-}" in
  "")
    echo "usage: ci/attributioncheck.sh <range> | --text" >&2
    exit 2 ;;
  --text)
    if found=$(grep -Ein "$markers"); then
      echo "attributioncheck: the text carries AI attribution:" >&2
      printf '%s\n' "$found" | sed 's/^/  /' >&2
      exit 1
    fi
    echo "attributioncheck: the text is clean" ;;
  *)
    range=$1
    commits=$(git log --format=%H -E -i --grep="$markers" "$range" | while read -r c; do
      # patterns with an opening paren: bash 3.2 misreads the bare form here
      case "$before_the_rule" in (*"$c"*) ;; (*) echo "$c" ;; esac
    done)
    if [ -n "$commits" ]; then
      echo "attributioncheck: commits in $range carry AI attribution:" >&2
      for c in $commits; do
        echo "  $(git log -1 --format='%h %s' "$c")" >&2
        git log -1 --format=%B "$c" | grep -Ein "$markers" | sed 's/^/      /' >&2
      done
      exit 1
    fi
    echo "attributioncheck: $(git rev-list --count "$range") commits in $range are clean" ;;
esac
