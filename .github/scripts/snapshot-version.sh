#!/bin/sh
# Works out the version a SNAPSHOT of this commit is published as (publish-snapshot.yml): the version
# semantic-release would release from it, with the release's own commit rules and tag format, plus
# -SNAPSHOT. With no commit since the last release that makes one, there is no next version and no
# SNAPSHOT. Runs in the release image, so POSIX sh only; writes SNAPSHOT_VERSION to $GITHUB_OUTPUT.
set -eu

git config --global --add safe.directory "$PWD"
# semantic-release reads the branch it releases from: the checked-out commit becomes that branch.
git checkout -q -B "$BRANCH"

# The image's config, reduced to its tag format and its commit analyzer: nothing is published,
# tagged or pushed. jq -e fails the job unless the analyzer is listed directly in .plugins.
jq -e '.extends == null and any(.plugins[]; ((arrays | .[0]) // .) == "@semantic-release/commit-analyzer")' \
  "/configs/$CONFIG_FILE" > /dev/null
jq '{tagFormat, plugins: [.plugins[] | select(((arrays | .[0]) // .) == "@semantic-release/commit-analyzer")]}' \
  "/configs/$CONFIG_FILE" > .releaserc

# The local clone as the repository: the dry run needs no token and reaches no remote.
OUT=$(npx semantic-release --dry-run --no-ci --branches "$BRANCH" --repository-url "file://$PWD" 2>&1) || {
  echo "$OUT"
  exit 1
}
echo "$OUT" | grep -E 'Found git tag|commits since last release|next release version|no relevant changes' || true

NEXT=$(echo "$OUT" | sed -n 's/.*The next release version is \([0-9][0-9A-Za-z.+-]*\).*/\1/p' | tail -n 1)
if [ -z "$NEXT" ]; then
  echo "No commit since the last release makes one: no SNAPSHOT"
  echo "SNAPSHOT_VERSION=" >> "$GITHUB_OUTPUT"
  exit 0
fi
echo "SNAPSHOT version: ${NEXT}-SNAPSHOT"
echo "SNAPSHOT_VERSION=${NEXT}-SNAPSHOT" >> "$GITHUB_OUTPUT"
