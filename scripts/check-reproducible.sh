#!/usr/bin/env bash
# Builds the module jars (examples and benchmarks excluded) twice from a clean tree, without the
# build cache, and fails when any jar differs between the two builds. Both builds run on this
# machine with the same JDK; the check shows that the build itself adds no timestamps or ordering.
set -euo pipefail
cd "$(dirname "$0")/.."

checksums() {
  find . -path '*/build/libs/*.jar' -not -path './chaos-agent-examples/*' \
    -not -path './chaos-agent-benchmarks/*' -print0 | sort -z | xargs -0 shasum -a 256
}

build() {
  ./gradlew clean assemble --no-build-cache --no-configuration-cache -q >/dev/null
}

first=$(mktemp)
second=$(mktemp)
trap 'rm -f "$first" "$second"' EXIT

build
checksums >"$first"
build
checksums >"$second"

if diff -u "$first" "$second"; then
  echo "reproducible: $(wc -l <"$first" | tr -d ' ') jars identical across two builds"
else
  echo "not reproducible: the jars above differ between two builds" >&2
  exit 1
fi
