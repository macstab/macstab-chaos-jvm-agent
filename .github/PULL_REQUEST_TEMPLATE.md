## What and why

<!-- The behaviour this changes and the reason. Link the issue. -->

## Checklist

- [ ] `./gradlew build` passes (format, tests, coverage floors, API signatures, doc checks)
- [ ] A public API change is recorded with `./gradlew apiDump` and visible in `api/*.api`
- [ ] A plan model change is recorded with `-PupdatePlanSchema`; a compatibility change with `-PupdateDocs`
- [ ] A change to a law of `docs/semantics.md` has a test tagged `@Tag("law-L<n>")`
- [ ] A new hook is in `HookCoverageIntegrationTest` and its bridge slot in `docs/instrumentation.md` §7
- [ ] `CHANGELOG.md` has an entry under "Unreleased"
