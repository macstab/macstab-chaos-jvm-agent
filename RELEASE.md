# Release Process

Release pipeline for `macstab-chaos-jvm-agent` - GitHub Packages (automated),
Maven Central (manual), and Javadoc on GitHub Pages (automated).

## First-time Maven Central setup

Only needed **once** per namespace. The namespace `com.macstab.chaos.jvm` is a
sub-namespace of `com.macstab` - if `com.macstab` is already verified for other
projects, Central Portal inherits that verification automatically.

1. **Sonatype Central Portal account** - https://central.sonatype.com/
2. **Verify namespace** (only if not already verified at `com.macstab`):
   - Namespaces -> Add Namespace -> `com.macstab.chaos.jvm`
   - Add the `sonatype-verify=...` TXT record to `macstab.com`
   - Wait for DNS propagation, click Verify
3. **Generate user token** (Account -> Generate User Token), put into
   `~/.gradle/gradle.properties`:
   ```properties
   ossrhUsername=<token-username>
   ossrhPassword=<token-password>
   ```
4. **GPG signing key**:
   ```bash
   gpg --gen-key
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   gpg --export-secret-keys <KEY_ID> > ~/.ssh/mavencentral_gpg_keyring.gpg
   ```
5. **Gradle signing config** in `~/.gradle/gradle.properties`, either a key ring:
   ```properties
   signing.keyId=<last-8-chars-of-key-id>
   signing.password=<gpg-passphrase>
   signing.secretKeyRingFile=/Users/you/.ssh/mavencentral_gpg_keyring.gpg
   ```
   or the key itself, in memory (`gpg --armor --export-secret-keys <KEY_ID>`; in CI the
   `ORG_GRADLE_PROJECT_signingKey` / `ORG_GRADLE_PROJECT_signingPassword` variables):
   ```properties
   signingKey=<ASCII-armoured secret key>
   signingPassword=<gpg-passphrase>
   ```
   A release (a version without `-SNAPSHOT`) must be signed, whatever the repository (Maven
   Central, GitHub Packages, the `build/staging-deploy` directory the release attests): every
   publish task fails before uploading anything when neither is configured, or the key is empty.
   Snapshots are never signed.

## Required GitHub secrets

Configure at `Settings -> Secrets and variables -> Actions`:

| Secret | Used by | Purpose |
|--------|---------|---------|
| `BOT_GITHUB_TOKEN` | semantic-release, publish-snapshot | Push tags, create releases, merge the release into develop; delete the previous snapshot package version (only that step holds it) |
| `BOT_USER_NAME` | semantic-release (back-merge of the release to develop) | Git config for bot commits |
| `BOT_USER_EMAIL` | semantic-release (back-merge of the release to develop) | Git config for bot commits |
| `NPM_TOKEN` | semantic-release | Optional, only if publishing to npm |
| `SIGNING_KEY`, `SIGNING_PASSWORD` | publish-release (secrets of the `release` environment) | The in-memory signing key (ASCII-armoured) and its passphrase |

Gradle never sees the bot token: `./gradlew publish` gets the workflow's own `GITHUB_TOKEN`
(`packages: write`), and every checkout runs with `persist-credentials: false`.

## Required `release` environment

`Settings -> Environments -> New environment -> release`, with required reviewers. The
`publish-release` job runs in it, so a release publish waits for an approval, and the signing
secrets are readable only there.

## Required GitHub Pages setting

`Settings -> Pages -> Build and deployment -> Source: Deploy from a branch`, branch `gh-pages`,
folder `/ (root)`. The `deploy-javadoc` workflow pushes the aggregated Javadoc of each release to
`gh-pages` under `<version>/`, and to `latest/` when the release is the newest one. A manual run
rebuilds `latest/` from the newest release tag; it never publishes a branch.

## Publishing overview

| Target          | Trigger                        | Version          | Flow      |
|-----------------|--------------------------------|------------------|-----------|
| GitHub Packages | Build on `develop` succeeds    | `X.Y.Z-SNAPSHOT` | Automatic |
| GitHub Packages | Push tag `X.Y.Z`               | `X.Y.Z`          | Automatic, after approval in the `release` environment |
| GitHub Pages    | Push tag `X.Y.Z`               | `X.Y.Z` Javadoc  | Automatic |
| Maven Central   | Manual checkout of tag + push  | `X.Y.Z`          | Manual    |

## Branching model

- `develop` - ongoing work. Every push builds, tests, and publishes a SNAPSHOT.
- `main` - release branch. Every merge triggers semantic-release, which
  analyses conventional-commit messages since the last tag, bumps the version,
  creates the tag `X.Y.Z` (bare, no `v`: the tag format comes from the release
  image's `/configs/.releaserc-gradle.json`), and cuts a GitHub release with the
  generated notes. It does not write `CHANGELOG.md` (see below). The tag push then triggers `publish-release.yml` (GitHub
  Packages) and `deploy-javadoc.yml` (GitHub Pages). The release is then merged
  back into develop; develop keeps its own `gradle.properties` (its `-SNAPSHOT`
  version), and any other conflict fails the job for a manual merge.

## CHANGELOG.md

`CHANGELOG.md` is written by hand: every change adds its lines under the top section,
`# Unreleased — X.Y.Z`. The workflow drops `@semantic-release/changelog` from the image's
config (`jq` over `/configs/.releaserc-gradle.json`), because the plugin prepends a generated
`# X.Y.Z (date)` section of every commit subject since the last tag, a second section for the
same version above the hand-written one. Before merging a release into main, rename the top
heading to the version and date, for example `# 2.0.0 (2026-11-30)`. The generated notes still
become the GitHub release's text and the release commit's message.

## API baseline

`japicmp` compares every API module with the release named by `apiBaseline` in
`gradle.properties` and fails on a binary-incompatible change unless
`apiBreaksAccepted = true`. After a release, set `apiBaseline` to it; after a
major release, also set `apiBreaksAccepted = false`.

## Code coverage (Jacoco)

`jacocoTestReport` writes a module's report (XML + HTML) when it is asked for; it runs the
module's tests first, but a test run alone does not produce it. `check` enforces each module's
coverage floor (`coverageFloors` in `build.gradle.kts`). The floors of the modules inside the agent
jar (api, core, instrumentation-jdk, startup-config, bootstrap) also count what the bootstrap tests'
child JVMs executed (`childJvmCoverage`), so they are verified by the root project's `check`
(`<module>CoverageVerification`), and `:<module>:check` runs only the module's own tests. The
root-level `jacocoAggregatedReport` task combines the reports of the modules with a floor and the
child JVMs' data into
`build/reports/jacoco/aggregated/{html,jacoco.xml}`; CI runs it and uploads the result as a
workflow artifact named `jacoco-coverage`.

Jacoco is restricted to `com.macstab.chaos.jvm.*` so the agent does not try to
instrument JDK internals (which would break on bytecode versions newer than
the bundled ASM).

## Workflows

- `build.yml` - spotless + `./gradlew build jacocoAggregatedReport` on PRs and pushes to main/develop,
  the tests on JDK 21 and 25 on amd64 and arm64 (`jdk-matrix`), and the demos (`demos`)
- `publish-snapshot.yml` - publishes `X.Y.Z-SNAPSHOT` to GitHub Packages
  after a successful build on develop
- `semantic-release.yml` - tags the release after a successful build of a push to main, and
  merges the tagged release back into develop, keeping develop's `gradle.properties`
  (`.github/scripts/back-merge.sh`; any other conflict fails the job). The release image is
  pinned by digest, and the bot token reaches git as a request header, never in a remote URL.
- `publish-release.yml` - on a tag push, in the `release` environment: `./gradlew check` of the
  tagged commit with the tag's version, then, as a separate step that runs only after the check
  passed, `./gradlew publish` (only this step sees the signing key and the token), then the build
  provenance and SBOM attestations
- `deploy-javadoc.yml` - builds the aggregated Javadoc read-only, then a separate job deploys
  it to `gh-pages` on tag push

Every workflow validates the Gradle wrapper, and the wrapper checks the distribution's
SHA-256 (`distributionSha256Sum`).

## Commit message format (Conventional Commits)

Semantic-release reads commit messages to pick the version bump:

| Type     | Version Bump | Example                         |
|----------|--------------|---------------------------------|
| `feat:`  | MINOR        | `feat: add JMX reset hook`      |
| `fix:`   | PATCH        | `fix: bootstrap resource lookup`|
| `perf:`  | PATCH        | `perf: reduce advice overhead`  |
| `chore:` | none         | `chore: bump deps`              |
| `docs:`  | none         | `docs: fix README recipe`       |
| `feat!:` | MAJOR        | `feat!: remove legacy API`      |

## Publishing a release to Maven Central (manual)

```bash
# After the 1.0.0 tag is on main:
git fetch --tags
git checkout 1.0.0

# Build and test first, then stage signed artifacts into the Central Portal (two calls: one call
# does not order the uploads after the tests)
./gradlew clean build
./gradlew publish

# Trigger the Central Portal upload API
./gradlew releaseToCentral

git checkout main
```

Then at https://central.sonatype.com/ -> Deployments, review the artifacts
and click **Publish**. Artifacts sync to Maven Central in 10-30 minutes.

## Troubleshooting

**SNAPSHOT not publishing** - check Actions logs for Build & Test; the
snapshot workflow only runs after a successful build on `develop`.

**Release tag exists but no GitHub Release** - semantic-release runs on main,
not on tag. Check the `Semantic Release` workflow run for failures.

**Javadoc deploy fails with "pages not enabled"** - enable
`Settings -> Pages -> Source: GitHub Actions` once; subsequent deploys work.

**`./gradlew publish` fails locally** - expected if OSSRH credentials are
not in `~/.gradle/gradle.properties`. The build script skips the OSSRH
repo and signing tasks when credentials are absent.

**Maven Central HTTP 401** - regenerate the token at
https://central.sonatype.com/ -> Account -> Generate User Token.

**Maven Central HTTP 402/405** - namespace not verified. Verify
`com.macstab.chaos.jvm` (or the parent `com.macstab`) in Central Portal.

## Version bump

The next development version after a release is set by editing
`gradle.properties` on develop:

```properties
version=1.1.0-SNAPSHOT
```

Commit as `chore: bump to 1.1.0-SNAPSHOT`. Semantic-release maintains the
release-line versions itself on main.
