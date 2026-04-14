# 1. Overview

## Purpose

`chaos-agent-testkit` is the test-facing convenience layer for local self-attach and session setup. It reduces boilerplate but does not change the underlying runtime model.

## Scope

In scope:

- local installation helper
- session helper
- JUnit 5 extension for per-test session management

Out of scope:

- isolated runtime per test
- automatic cleanup of JVM-global chaos handles
- custom assertion DSLs

# 2. Architectural Context

This module depends on:

- `chaos-agent-api`
- `chaos-agent-bootstrap`
- JUnit Jupiter API for the extension

It is a consumer convenience layer, not a separate runtime.

# 3. Key Concepts And Terminology

- Shared control plane: the singleton runtime returned by local install
- Test session: a `ChaosSession` opened for one test case
- Extension store: JUnit `ExtensionContext.Store` entries used to hold the control plane and session

# 4. End-to-End Behavior

## Helper Methods

- `ChaosTestKit.install()` installs the local agent and returns the shared `ChaosControlPlane`
- `ChaosTestKit.openSession(displayName)` installs if needed, then opens a new session

## `ChaosAgentExtension`

Lifecycle:

1. `beforeEach(...)` installs the local agent
2. opens a new session named after the test display name
3. stores both objects in the JUnit extension store
4. injects either `ChaosControlPlane` or `ChaosSession` into test methods
5. `afterEach(...)` closes only the session

Important consequence:

- the extension does not close the control plane and does not uninstall instrumentation

# 5. Architecture Diagrams

No PlantUML diagram is included. The lifecycle is simple and the prose above is more precise for the only two classes in the module.

# 6. Component Breakdown

## `ChaosTestKit`

Responsibility:

- thin convenience wrapper around `ChaosPlatform`

Why it exists:

- keep test code from repeatedly writing local install boilerplate

## `ChaosAgentExtension`

Responsibility:

- session lifecycle around each JUnit test
- parameter injection for `ChaosControlPlane` and `ChaosSession`

Why it exists:

- make the common test case one annotation plus one parameter

# 7. Data Model And State

Stored test-scoped objects:

- `ChaosControlPlane`
- `ChaosSession`

State ownership caveats:

- the control plane is JVM-wide and reused
- the session is per test
- only the session is automatically closed

This distinction is operationally important because JVM-global scenarios activated directly on the control plane can outlive the test that created them unless the test closes their handles explicitly.

# 8. Concurrency And Threading Model

## Single-Test Behavior

The extension opens the session during `beforeEach(...)`, which means the current core implementation binds the session to whichever thread executes that callback. In the common JUnit execution model this is also the test method thread, but the module does not itself guarantee that scheduling choice.

## Parallel Test Behavior

If JUnit parallel execution is enabled:

- multiple sessions can coexist against the same runtime
- session-scoped scenarios remain isolated only to the extent that session binding and propagation are correct
- JVM-global scenarios are shared across tests and can interfere with one another

This module does not provide cross-test isolation stronger than the underlying runtime model.

# 9. Error Handling And Failure Modes

Expected failures:

- local self-attach unavailable
- dynamic agent loading disabled
- scenario activation misuse inside tests

Important misuse case:

```java
ChaosActivationHandle handle =
    controlPlane.activate(jvmGlobalScenario);
```

Why it is risky in tests:

- the extension will close the session after the test
- it will not close `handle`
- the JVM-global scenario can therefore affect later tests

Prefer session-scoped scenarios in tests unless the test explicitly owns and closes every JVM-global handle.

# 10. Security Model

Security is usually not the primary concern for a test helper, but the trust model still matters:

- local self-attach is a privileged operation
- tests using this module can intentionally alter JVM-global runtime behavior

Treat testkit usage as trusted test infrastructure, not as safe multi-tenant isolation.

# 11. Performance Model

Install cost is front-loaded when the runtime first self-attaches. After that:

- per-test cost is mostly session creation and teardown
- runtime hot-path cost remains whatever the core imposes for active scenarios

The module itself adds negligible overhead beyond JUnit callback and store usage.

# 12. Observability And Operations

Testkit does not create new telemetry. Operational debugging still goes through:

- `ChaosDiagnostics`
- runtime logs
- optional startup or manual debug dumps

For tests, a useful pattern is to inspect diagnostics when a scenario appears not to fire rather than assuming the extension failed.

# 13. Configuration Reference

There are no independent testkit configuration keys in this repository.

Prerequisites for local install are inherited from bootstrap and the JVM:

- self-attach must be permitted
- dynamic agent loading must be available

# 14. Extension Points And Compatibility Guarantees

Stable caller-facing conveniences:

- `ChaosTestKit.install()`
- `ChaosTestKit.openSession(...)`
- `ChaosAgentExtension` JUnit behavior as currently implemented

Non-goals and current limits:

- no auto-cleanup for JVM-global handles
- no separate runtime per test class or method

# 15. Stack Walkdown

## API Layer

Relevant because tests consume `ChaosControlPlane` and `ChaosSession` directly.

## Application / Runtime Layer

Highly relevant because testkit is only a thin wrapper over the real runtime.

## JVM Layer

Relevant because local self-attach and instrumentation still happen inside the test JVM.

## Memory / Concurrency Layer

Relevant for parallel test execution and session propagation.

## OS / Container Layer

Relevant only to the extent that local attach permissions or container hardening can block installation.

## Infrastructure Layer

Usually not materially relevant beyond the test runner and CI JVM configuration.

# 16. References

- Reference: JUnit 5 User Guide
- Reference: Java Platform SE API Specification — `java.lang.instrument`
