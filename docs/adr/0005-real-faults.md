<!--
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Engineered by  Christian Schnapka
                 Embedded Principal+ Engineer
                 Macstab GmbH · Hamburg, Germany
                 https://macstab.com
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-->

# 0005 — Faults are the JDK's own failures, raised in the real call

**Status:** Accepted; amended in 2.0: the HTTP and JDBC suppress, which threw the agent's own `ChaosHttpSuppressException` / `ChaosJdbcSuppressException`, is refused at activation ("use reject") and both classes are removed; amended (2.0): a client-library reject fails the call through the channel the library itself fails it through — gRPC's own failing stream and the call's listener, a failed future, a callback, an error signal, a failed Netty write — never by a throw from a method the library does not expect to throw (a throw there panicked gRPC's synchronization context, hung Netty's write loop, and reached asynchronous callers synchronously); a library version on which that channel cannot be built is refused at activation where the agent can tell, else the call fails by a throw and the miss is named in `hookFailures`

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
