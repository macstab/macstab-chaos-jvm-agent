<!--
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Engineered by  Christian Schnapka
                 Embedded Principal+ Engineer
                 Macstab GmbH · Hamburg, Germany
                 https://macstab.com
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-->

# 0003 — The agent jar shades ByteBuddy and Jackson and declares no dependencies

**Status:** Accepted (2.0)

## Context

The agent runs inside applications that bring their own ByteBuddy (Mockito, Hibernate) and Jackson (Spring, Quarkus), often in other versions. Two copies of the same packages on one class path resolve to whichever comes first; an agent that changes the application's ByteBuddy or Jackson breaks the system it is meant to test.

## Decision

`chaos-agent-bootstrap` publishes one jar with every runtime dependency merged in, ByteBuddy and Jackson relocated to `com.macstab.chaos.jvm.shaded.*`, and a POM without dependencies. The framework starters take the core classes from that jar instead of depending on `chaos-agent-core` and `chaos-agent-startup-config`.

## Consequences

- No clash with the application's libraries, whichever versions it uses.
- The jar is larger, and a security fix in ByteBuddy or Jackson needs an agent release (Dependabot proposes the update; the build verifies it).
- Coverage of the child-JVM tests runs against the shaded classes; `childJvmCoverage` maps it back onto the module classes.
- Amended in 2.0: the bootstrap bridge classes (ADR 0001) are stored under `META-INF/macstab-chaos-bridge/`, not under their class names, so no class loader that reads the jar defines a copy of its own. The thin `chaos-agent-instrumentation-jdk` jar keeps them under their class names: it is published for builds and benchmarks that use the plain classes, and must never be bundled in an application.

- Amended in 2.0: the libraries' multi-release overlays (`META-INF/versions/**`) and `module-info.class` files are left out of the agent jar. The agent jar is not a multi-release jar (its manifest declares no `Multi-Release`), so the JVM never reads those overlays, and the base classes of ByteBuddy and Jackson run on every JDK the agent supports (21 and later).

## Alternatives rejected

- **Ordinary dependencies**: the clash above.
- **A separate class loader for the agent's dependencies**: inlined advice and the bridge must reach agent types from JDK and application classes; isolating the agent's own classes behind a custom loader breaks those references.
