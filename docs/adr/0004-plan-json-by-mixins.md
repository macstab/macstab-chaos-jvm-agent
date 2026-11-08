<!--
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Engineered by  Christian Schnapka
                 Embedded Principal+ Engineer
                 Macstab GmbH · Hamburg, Germany
                 https://macstab.com
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-->

# 0004 — The API carries no JSON annotations; the plan format is defined by mix-ins

**Status:** Accepted (2.0); its "no dependencies" consequence is amended by [0008](0008-nullness-in-the-types.md); amended (2.0): a record whose documented default differs from Java's (`withStackTrace`, `retain`, `objectSizeBytes`, `acquisitionDelay`, `Observability`) has a private JSON constructor with boxed parameters that applies the defaults, and `ActivationPolicy.fromJson` is private; the mix-ins name them as the creators, so the API stays free of Jackson

## Context

`chaos-agent-api` is what applications compile against. Jackson annotations on its types tie every user to `jackson-annotations` and make the API's shape and the file format change together.

## Decision

The API types are plain records and interfaces. `chaos-agent-startup-config` defines the plan format with Jackson mix-ins (`ApiJsonMixins`): the `type` discriminators, the creators, strict reading (unknown fields and trailing content rejected). `ChaosPlanMapper` is the one reader and writer. The format is published as a JSON Schema derived from that mapper (`PlanSchemaTest`), specified in `docs/plan-schema.md`, and fuzzed (`PlanFuzzTest`).

## Consequences

- The API has no dependencies; the format can evolve without changing it.
- Another `ObjectMapper` does not know the discriminators: plans are read and written with `ChaosPlanMapper`.
- The schema cannot drift from the parser: the build fails when they differ.

## Alternatives rejected

- **Annotations on the API**: the coupling above.
- **A hand-written schema**: drifts; the generated one cannot.
