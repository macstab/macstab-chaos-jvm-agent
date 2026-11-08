#!/usr/bin/env bash
# Builds the module jars (examples and benchmarks excluded) twice from a clean tree, without the
# build cache, and fails when any jar differs between the two builds. Both builds run on this
# machine with the same JDK; the check shows that the build itself adds no timestamps or ordering.
set -euo pipefail
cd "$(dirname "$0")/.."

# sha256sum (coreutils) where present, as on minimal Linux images; shasum elsewhere (macOS).
if command -v sha256sum >/dev/null; then
  SHA256=(sha256sum)
else
  SHA256=(shasum -a 256)
fi

# Fails when the build produced no jar: comparing two empty lists would prove nothing.
checksums() {
  local jars
  jars=$(find . -path '*/build/libs/*.jar' -not -path './chaos-agent-examples/*' \
    -not -path './chaos-agent-benchmarks/*' | LC_ALL=C sort)
  if [[ -z "$jars" ]]; then
    echo "no jars found under */build/libs: nothing to compare" >&2
    exit 1
  fi
  while IFS= read -r jar; do
    "${SHA256[@]}" "$jar"
  done <<<"$jars"
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
