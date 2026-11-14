<!--
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Engineered by  Christian Schnapka
                 Embedded Principal+ Engineer
                 Macstab GmbH · Hamburg, Germany
                 https://macstab.com
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-->

# 0007 — Reference documentation is checked against, or generated from, the code

**Status:** Withdrawn (2.0)

## Decision (withdrawn)

Every reference fact of the documentation — the plan format, the selector and effect tables, the effect ↔ operation compatibility, the bridge slots, the laws of evaluation, the API names in examples — was to be generated from the code or checked against it by a documentation test that fails the build.

## Why it was withdrawn

The documentation tests that carried the decision (`ReferenceTablesSyncTest`, `HandleTableDocTest`, `CompatibilityMatrixDocTest`, `SemanticsLawTraceabilityTest`, `CrossLayerDocSyncTest`) were removed in 2.0, before the release. No documentation test replaces them: the documents are kept in step with the code by review.

## What remains checked against the code

- The published plan schema: `PlanSchemaTest` derives it from `ChaosPlanMapper` and fails the build when the committed file differs (ADR 0004).
- The public API of each published module: `apiCheck` against its `api/*.api` file.
