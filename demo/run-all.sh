#!/usr/bin/env bash
# Runs every demo under the agent and checks its output against expected.txt. Each demo is a
# single-file Java program (java Demo.java) with its plan.json, run in a scratch working copy.
#
# Usage:  demo/run-all.sh [agent.jar]
#         (default: chaos-agent-bootstrap/build/libs/chaos-agent-bootstrap-<version>.jar)
# Exit status: 0 when every demo printed what it expects, 1 otherwise. A demo that runs longer than
# DEMO_TIMEOUT seconds (default 120) fails; timeout(1) (coreutils; gtimeout on macOS) enforces it.
# A demo whose stderr has a WARNING line or an exception (a line naming an Exception or Error, or a
# stack frame) fails too, even when its output matches: the agent must run them without warnings.
set -euo pipefail

DEMO_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$DEMO_DIR/.." && pwd)"
AGENT="${1:-}"
if [[ -z "$AGENT" ]]; then
  VERSION="$(sed -n 's/^version *= *//p' "$REPO_ROOT/gradle.properties")"
  AGENT="$REPO_ROOT/chaos-agent-bootstrap/build/libs/chaos-agent-bootstrap-$VERSION.jar"
fi
if [[ ! -f "$AGENT" ]]; then
  echo "agent jar not found; build it with ./gradlew :chaos-agent-bootstrap:shadowJar" >&2
  exit 1
fi
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
DEMO_TIMEOUT="${DEMO_TIMEOUT:-120}"
if command -v timeout >/dev/null; then
  LIMIT=(timeout "$DEMO_TIMEOUT")
elif command -v gtimeout >/dev/null; then
  LIMIT=(gtimeout "$DEMO_TIMEOUT")
else
  echo "warning: no timeout(1) found; the demos run without a time limit" >&2
  LIMIT=()
fi

# The scratch copy of the demo running now; removed on exit, also when interrupted.
work=""
trap 'if [[ -n "$work" ]]; then rm -rf "$work"; fi' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# stderr lines that fail a demo.
NOISE='^WARNING|Exception|Error[: ]|^[[:space:]]+at '

pass=0
fail=0
for demo in "$DEMO_DIR"/[0-9][0-9]-*/; do
  name="$(basename "$demo")"
  work="$(mktemp -d)"
  cp "$demo"/Demo.java "$demo"/plan.json "$work"/
  if actual="$(cd "$work" && ${LIMIT[@]+"${LIMIT[@]}"} "$JAVA" \
      "-javaagent:$AGENT=configFile=$work/plan.json" Demo.java 2>"$work/stderr")" \
      && [[ "$actual" == "$(cat "$demo/expected.txt")" ]] \
      && ! grep -Eq "$NOISE" "$work/stderr"; then
    echo "[PASS] $name"
    pass=$((pass + 1))
  else
    echo "[FAIL] $name"
    echo "  expected:"; sed 's/^/    /' "$demo/expected.txt"
    echo "  actual:";   printf '%s\n' "${actual:-}" | sed 's/^/    /'
    echo "  stderr (tail):"; tail -5 "$work/stderr" | sed 's/^/    /'
    if grep -Eq "$NOISE" "$work/stderr"; then
      echo "  stderr warnings and exceptions:"; grep -E "$NOISE" "$work/stderr" | sed 's/^/    /'
    fi
    fail=$((fail + 1))
  fi
  rm -rf "$work"
  work=""
done
echo "$pass passed, $fail failed"
[[ "$fail" -eq 0 ]]
