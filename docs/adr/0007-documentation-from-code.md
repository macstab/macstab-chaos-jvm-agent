<!--
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Engineered by  Christian Schnapka
                 Embedded Principal+ Engineer
                 Macstab GmbH · Hamburg, Germany
                 https://macstab.com
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-->

# 0007 — Reference documentation is checked against, or generated from, the code

**Status:** Accepted (2.0)

## Context

Documentation that restates the code drifts: before this decision the docs gave the handle count as 57, 64, 67 and 77 in different places, described a plan format the parser rejected, and left four operations and nine effects out of their tables.

## Decision

Every reference fact the code defines is either generated from it or checked against it by a test that fails the build:

| Fact | Source | Test |
|------|--------|------|
| Plan format | `ChaosPlanMapper` | `PlanSchemaTest` (generated schema), `CrossLayerDocSyncTest` (every example parses) |
| Selector operations, effect sections, type lists | the schema | `ReferenceTablesSyncTest` |
| Effect ↔ operation compatibility | `CompatibilityValidator` | `CompatibilityMatrixDocTest` (generated table) |
| Bridge slots and handle counts | `BootstrapDispatcher` | `HandleTableDocTest` |
| Laws of evaluation | the tests tagged `law-L<n>` | `SemanticsLawTraceabilityTest` |
| API names in prose and examples | the API | `CrossLayerDocSyncTest` (and `java verified` snippets compile) |
| Public API | the class files | `apiCheck` (`api/*.api`) |

Generated parts are regenerated with `-PupdatePlanSchema`, `-PupdateDocs` and `apiDump`; the doc tests declare the documents as inputs so a document-only change reruns them.

## Consequences

- Prose explains; facts cannot go stale without failing the build.
- A change to the model shows in review as a diff of the schema, the tables or the API file.
