<!--
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Engineered by  Christian Schnapka
                 Embedded Principal+ Engineer
                 Macstab GmbH · Hamburg, Germany
                 https://macstab.com
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-->

# 0008 — Nullness is part of the types, and the compiler proves it

**Status:** Accepted (2.0); amends [0004](0004-plan-json-by-mixins.md): the API now has one dependency; amended (2.0): every production package is `@NullMarked` (a `package-info.java` each, which an API test requires of every new package), so the non-null default is in the bytecode and not only a NullAway option; a record component that the constructor normalises from `null` (a selector's patterns, `ChaosScenario` description, scope and policy, `ChaosPlan` metadata and observability, `NamePattern` mode and value) has an explicit canonical constructor whose parameter is `@Nullable` while the accessor is not

## Context

Much of the agent's data is optional by design: a selector without a peer filter, a policy without a rate limit, a call without a URL, a terminal action without a throwable. Before this decision, which values could be `null` lived in Javadoc only, and a forgotten check surfaced as a `NullPointerException` inside a JDK method the agent had woven, at the worst possible place.

## Decision

Every reference in production code is non-null unless it is annotated `@Nullable` (JSpecify), and NullAway, running in every compilation as an error, proves each dereference safe, in JSpecify mode, so type arguments (`AtomicReference<@Nullable T>`) carry nullness as well. Optional record components, parameters and return values say so in their type. Values that must be present but are reached through an untyped carrier (the payload of a `TerminalAction`) are read through accessors that fail with a message naming the engine bug, not with a bare `NullPointerException`. `chaos-agent-api` publishes the JSpecify annotations as its one dependency, so the nullness reaches Java tools and Kotlin; the agent jar leaves them out, as no annotation is needed at runtime.

## Consequences

- A missing null check is a compile error, not a production incident inside a JDK class.
- The API's optional parts are visible in its signatures and its Javadoc.
- Code that cannot be checked statically states its invariant at one place (`Objects.requireNonNull` with the reason), which NullAway then relies on.
- Test sources are not checked: tests pass `null` on purpose.

## Alternatives rejected

- **Javadoc only**: what it replaces.
- **`Optional` in records and parameters**: allocates on the hot path and is not what the JSON format or the JDK call sites provide.
