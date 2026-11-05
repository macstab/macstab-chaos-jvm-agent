<!--
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Engineered by  Christian Schnapka
                 Embedded Principal+ Engineer
                 Macstab GmbH · Hamburg, Germany
                 https://macstab.com
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-->

# 0005 — Faults are the JDK's own failures, raised in the real call

**Status:** Accepted

## Context

A fault injected by a mock or a proxy tests the mock. Retry, timeout and resource-cleanup code reacts to the exact exception type, message and side effects of the real failure — a `ConnectException` from `connect`, a short count from a `FileChannel` write, a reset connection, `error=24` from `ProcessBuilder.start`.

## Decision

Advice runs inside the intercepted JDK or library method and fails it the way the platform fails it: the JDK's exception types and messages (`ChaosEffect.Errno` maps each errno to the exception the JDK throws for it, with the running OS's code), real sleeps, real short writes and resets (`SO_LINGER` 0), the client library's own exception built through the client's class loader. The native sibling library injects the same faults at the libc level; `docs/cross-layer.md` maps the two.

## Consequences

- Application code cannot tell an injected fault from a real one, which is the point.
- Each fault must be specified per operation: `CompatibilityValidator` rejects an effect on an operation it cannot fail faithfully, and the compatibility table of the configuration reference documents the result.
- The child-JVM integration tests assert the exact exceptions and messages, against the real JDK.

## Alternatives rejected

- **Throwing a generic chaos exception**: application error handling would never see it in production.
