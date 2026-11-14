## What and why

<!-- The behaviour this changes and the reason. Link the issue. -->

## Checklist

- [ ] `./gradlew build` passes (format, tests, coverage floors, API signatures)
- [ ] A public API change is recorded with `./gradlew apiDump` and visible in `api/*.api`
- [ ] A plan model change is recorded with `-PupdatePlanSchema`
- [ ] A change to a law of `docs/semantics.md` comes with a test of the changed behaviour
- [ ] A new hook is in `HookCoverageIntegrationTest` and its bridge slot in `docs/instrumentation.md` §7
- [ ] The commit messages are conventional commits (`!` and a `BREAKING CHANGE:` footer for a breaking change); `CHANGELOG.md` is generated, never edited
