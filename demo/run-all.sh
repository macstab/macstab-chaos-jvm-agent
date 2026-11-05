#!/usr/bin/env bash
# Runs every demo under the agent and checks its output against expected.txt. Each demo is a
# single-file Java program (java Demo.java) with its plan.json, run in a scratch working copy.
#
# Usage:  demo/run-all.sh [agent.jar]
#         (default: chaos-agent-bootstrap/build/libs/chaos-agent-bootstrap-<version>.jar)
# Exit status: 0 when every demo printed what it expects, 1 otherwise.
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

pass=0
fail=0
for demo in "$DEMO_DIR"/[0-9][0-9]-*/; do
  name="$(basename "$demo")"
  work="$(mktemp -d)"
  cp "$demo"/Demo.java "$demo"/plan.json "$work"/
  if actual="$(cd "$work" && "$JAVA" -javaagent:"$AGENT"=configFile="$work/plan.json" \
      Demo.java 2>"$work/stderr")" && [[ "$actual" == "$(cat "$demo/expected.txt")" ]]; then
    echo "[PASS] $name"
    pass=$((pass + 1))
  else
    echo "[FAIL] $name"
    echo "  expected:"; sed 's/^/    /' "$demo/expected.txt"
    echo "  actual:";   printf '%s\n' "${actual:-}" | sed 's/^/    /'
    echo "  stderr (tail):"; tail -5 "$work/stderr" | sed 's/^/    /'
    fail=$((fail + 1))
  fi
  rm -rf "$work"
done
echo "$pass passed, $fail failed"
[[ "$fail" -eq 0 ]]
