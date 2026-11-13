#!/bin/sh
# Merges the release semantic-release just tagged on main back into develop (semantic-release.yml).
# develop takes the released version into gradle.properties and keeps its other properties; any
# other conflict fails the job and is resolved by hand. SNAPSHOT versions are not kept in
# gradle.properties: publish-snapshot.yml works them out from the commits. Runs in the release image, so POSIX sh only.
set -eu

git config --global --add safe.directory "$GITHUB_WORKSPACE"
git config --global user.email "$BOT_USER_EMAIL"
git config --global user.name "$BOT_USER_NAME"
# The token travels in a request header, never in the remote URL, where git would store it in
# .git/config and print it in errors.
AUTH=$(printf 'x-access-token:%s' "$GH_TOKEN" | base64 | tr -d '\n')
git config --global http.https://github.com/.extraheader "AUTHORIZATION: basic $AUTH"

git fetch --tags origin "$FROM_BRANCH" "$TARGET_BRANCH"
git checkout -B "$FROM_BRANCH" "origin/$FROM_BRANCH"
TAG_NAME=$(git describe --exact-match --tags HEAD 2>/dev/null) || {
  echo "No release on HEAD"
  exit 0
}
echo "Release tag is $TAG_NAME"

git checkout -B "$TARGET_BRANCH" "origin/$TARGET_BRANCH"
git merge --no-ff --no-commit "$TAG_NAME" || echo "The merge stopped on conflicts"
# develop's gradle.properties stays, conflicting or not, with the released version in it.
git checkout HEAD -- gradle.properties
RELEASED=$(git show "$TAG_NAME:gradle.properties" | sed -n 's/^[[:space:]]*version[[:space:]]*=[[:space:]]*//p' | tr -d ' \t\r')
sed -i "s/^\([[:space:]]*version[[:space:]]*=[[:space:]]*\).*/\1$RELEASED/" gradle.properties
git add gradle.properties
CONFLICTS=$(git diff --name-only --diff-filter=U)
if [ -n "$CONFLICTS" ]; then
  echo "ERROR: merging $TAG_NAME into $TARGET_BRANCH conflicts in:"
  echo "$CONFLICTS"
  exit 1
fi
if git rev-parse -q --verify MERGE_HEAD >/dev/null; then
  git commit --no-edit
  git push origin "$TARGET_BRANCH"
else
  echo "$TARGET_BRANCH already contains $TAG_NAME"
fi
