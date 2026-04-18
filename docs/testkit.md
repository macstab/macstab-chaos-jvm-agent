<!--
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Engineered by  Christian Schnapka
                 Embedded Principal+ Engineer
                 Macstab GmbH · Hamburg, Germany
                 https://macstab.com
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-->

# chaos-agent-testkit — Test Integration Reference

> Internal reference for `ChaosAgentExtension`, `ChaosTestKit`, and the agent self-attach path.
> 
> *Engineered by* **[Christian Schnapka](https://macstab.com)** — Embedded Principal+ Engineer · [Macstab GmbH](https://macstab.com) · Hamburg, Germany

---

# 1. Overview

## Purpose

`chaos-agent-testkit` provides two integration surfaces for test code:

1. **`ChaosAgentExtension`** — JUnit 5 extension that manages agent installation, session lifecycle, and parameter injection automatically
2. **`ChaosTestKit`** — static helper for tests that do not use the JUnit 5 extension and manage chaos lifecycle manually

Both surfaces delegate to `ChaosPlatform.installLocally()` in `chaos-agent-bootstrap` for the actual agent installation.

## Scope

In scope:
- JUnit 5 `Extension` integration
- Agent self-attach via Attach API
- `ChaosSession` lifecycle management per test method

Out of scope:
- TestNG or JUnit 4 integration
- Per-class or per-suite session scoping (only per-method sessions are provided by `ChaosAgentExtension`)

---

# 2. ChaosAgentExtension

## Registration

```java
@ExtendWith(ChaosAgentExtension.class)
class MyServiceTest {
    @Test
    void testWithSession(ChaosSession session) { ... }

    @Test
    void testWithControlPlane(ChaosControlPlane controlPlane) { ... }
}
```

Alternatively, for auto-extension without annotation:
```java
// In META-INF/services/org.junit.jupiter.api.extension.Extension
com.macstab.chaos.testkit.ChaosAgentExtension
```

## Lifecycle

```
@BeforeAll equivalent:
  ChaosPlatform.installLocally()
  — self-attaches agent if not already installed
  — idempotent; safe to call from multiple extensions

@BeforeEach equivalent:
  ChaosSession session = controlPlane.openSession(testDisplayName)
  — session ID is a UUID; displayName is the test method display name

@AfterEach equivalent:
  session.close()
  — stops all session-scoped scenarios
  — unregisters session-scoped controllers from ScenarioRegistry
  — does NOT affect JVM-scoped scenarios
```

## Parameter Resolution

`ChaosAgentExtension` resolves the following parameter types in test methods and lifecycle callbacks:
- `ChaosSession` — injects the current test's session
- `ChaosControlPlane` — injects the agent's `ChaosControlPlane` (JVM-global handle)
- `ChaosDiagnostics` — injects the diagnostics interface

Parameters of any other type are not resolved by this extension (JUnit delegates to other resolvers).

## Session Isolation Guarantee

Each test method gets its own `ChaosSession` with a unique ID. Session-scoped scenarios activated in test A are never visible to test B, even if tests run in parallel. This guarantee holds because:
1. Each session has a distinct UUID as its ID
2. `ScenarioController.evaluate()` compares `sessionId` by string equality
3. `session.close()` in `@AfterEach` unregisters and stops all controllers for that session

JVM-scoped scenarios (registered via `controlPlane.activate()`) are shared across all tests in the same JVM process. Use JVM-scoped scenarios only for intentionally shared fault injection.

---

# 3. ChaosTestKit

```java
// Install agent (idempotent) and get control plane:
ChaosControlPlane controlPlane = ChaosTestKit.install();

// Install and open a session in one call:
ChaosSession session = ChaosTestKit.openSession("my-test");
```

Use `ChaosTestKit` when:
- Using TestNG or another test framework that does not support JUnit 5 extensions
- Manually controlling chaos lifecycle in integration tests
- Running chaos from non-test code (benchmarks, load tests)

The caller is responsible for `session.close()` in all branches (preferably try-with-resources or `finally`).

---

# 4. Self-Attach Requirements

`ChaosPlatform.installLocally()` uses the JDK Attach API to self-attach the agent. Requirements:

- JDK (not JRE): requires `tools.jar` (JDK 8) or the `java.attach` module (JDK 9+)
- `--add-opens java.base/jdk.internal.misc=ALL-UNNAMED` may be required on JDK 17+ for some Attach API paths
- `-Djdk.attach.allowAttachSelf=true` must be set on JDK 9+ to allow a process to attach to itself. `ChaosAgentExtension` sets this as a system property before self-attach. Alternatively, set it in the JVM command line.
- The agent JAR must be locatable on the classpath. `ChaosPlatform` uses a classpath scan to find the bootstrap JAR.

**Gradle/Maven test configuration example**:
```kotlin
// build.gradle.kts
tasks.test {
    jvmArgs("-Djdk.attach.allowAttachSelf=true")
}
```

---

# 5. Typical Usage Patterns

## Session-scoped delay on executor

```java
@ExtendWith(ChaosAgentExtension.class)
class ExecutorDelayTest {
    @Test
    void slowExecutorShouldTriggerTimeout(ChaosSession session) throws Exception {
        ChaosScenario scenario = ChaosScenario.builder()
            .id("slow-executor")
            .scope(ChaosScenario.ScenarioScope.SESSION)
            .selector(ChaosSelector.executor())
            .effect(ChaosEffect.delay(Duration.ofMillis(500)))
            .build();

        session.activate(scenario);

        try (ChaosSession.ScopeBinding scope = session.bind()) {
            // All executor submissions on this thread see 500 ms delay
            Future<?> f = myExecutor.submit(() -> doWork());
            assertThrows(TimeoutException.class, () -> f.get(100, TimeUnit.MILLISECONDS));
        }
    }
}
```

## JVM-scoped stressor for soak testing

```java
@Test
void serviceResilientUnderHeapPressure() {
    ChaosControlPlane cp = ChaosTestKit.install();
    ChaosActivationHandle handle = cp.activate(
        ChaosScenario.builder()
            .id("heap-stress")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.stress(StressTarget.HEAP))
            .effect(ChaosEffect.heapPressure(64 * 1024 * 1024))
            .build()
    );
    try {
        runSoakTest();
    } finally {
        handle.close(); // stops the stressor and releases retained heap
    }
}
```

## Verifying exception injection

```java
@Test
void dbCallShouldHandleNetworkFailure(ChaosSession session) {
    session.activate(ChaosScenario.builder()
        .id("db-socket-reject")
        .scope(ChaosScenario.ScenarioScope.SESSION)
        .selector(ChaosSelector.network()
            .remoteHostPattern("db.internal.*")
            .operations(OperationType.SOCKET_CONNECT))
        .effect(ChaosEffect.reject("simulated connection refused"))
        .build()
    );

    try (ChaosSession.ScopeBinding scope = session.bind()) {
        assertThrows(ConnectException.class, () -> dbService.query("SELECT 1"));
    }
}
```

## Manual gate for synchronization testing

```java
@Test
void gateBlocksAndReleasesCorrectly(ChaosSession session) throws Exception {
    ChaosActivationHandle handle = session.activate(ChaosScenario.builder()
        .id("gate-test")
        .scope(ChaosScenario.ScenarioScope.SESSION)
        .selector(ChaosSelector.executor())
        .effect(ChaosEffect.gate(Duration.ofSeconds(5)))
        .build()
    );

    CountDownLatch started = new CountDownLatch(1);
    Thread t = new Thread(() -> {
        started.countDown();
        try (ChaosSession.ScopeBinding scope = session.bind()) {
            executor.execute(() -> {}); // blocks on gate
        }
    });
    t.start();

    started.await();
    // Assert: thread is blocked
    assertTrue(t.isAlive());

    handle.release(); // unblocks gate
    t.join(1000);
    assertFalse(t.isAlive());
}
```

---

# 6. Anti-Patterns and Misuse Risks

| Anti-pattern | Risk | Correct approach |
|-------------|------|-----------------|
| Activating JVM-scoped scenario without closing the handle | Scenario leaks across tests; chaos remains active for subsequent tests in the same JVM | Always call `handle.close()` in `finally` |
| Not binding session before submitting work to an executor | Session context not propagated; executor tasks not affected by session-scoped chaos | Use `session.bind()` before submitting, or `session.wrap(task)` to create a context-carrying wrapper |
| Using `ChaosTestKit.install()` without `-Djdk.attach.allowAttachSelf=true` | `IllegalStateException` at runtime | Add the JVM arg to test config |
| Checking `appliedCount` without waiting for scenarios to fire | Race condition: the scenario may not have fired yet | Use event listeners or explicit synchronization points |
| Registering the same scenario ID twice in the same scope | `IllegalStateException("scenario key already active")` | Use unique IDs per test; let `ChaosAgentExtension` manage session lifecycle |

---

# 7. Diagnostics in Test Context

```java
@Test
void debugChaos(ChaosSession session, ChaosDiagnostics diag) {
    // ... activate scenarios ...

    ChaosDiagnostics.Snapshot snap = diag.snapshot();
    snap.scenarios().forEach(r ->
        System.out.printf("[chaos] %s: state=%s matched=%d applied=%d reason=%s%n",
            r.id(), r.state(), r.matchedCount(), r.appliedCount(), r.reason()));

    // Or dump everything:
    System.out.println(diag.debugDump());
}
```

If `appliedCount == 0 && matchedCount > 0`:
- The selector is working (matchedCount > 0 confirms this)
- An activation policy guard is filtering: check `probability`, `rateLimit`, `activateAfterMatches`, `activeFor`, `maxApplications`
- Check `reason` field for the last state-transition reason

If `matchedCount == 0`:
- The selector is not matching: verify the selector's `operationType`, class name patterns, and that `session.bind()` is active when the instrumented call fires

---

# 8. References

- Reference: JUnit 5 — `Extension`, `BeforeEachCallback`, `AfterEachCallback`, `ParameterResolver` — https://junit.org/junit5/docs/current/user-guide/#extensions
- Reference: Java SE API — `com.sun.tools.attach.VirtualMachine` (self-attach via Attach API) — https://docs.oracle.com/en/java/docs/api/jdk.attach/com/sun/tools/attach/VirtualMachine.html
- Reference: JDK 9+ — `jdk.attach.allowAttachSelf` system property (required for self-attach) — https://openjdk.org/jeps/451
- Reference: JSR-133 — Java Memory Model — `ThreadLocal` visibility guarantees per thread — https://jcp.org/aboutJava/communityprocess/mrel/jsr133/index.html

---

<div align="center">

*Architecture, implementation, and documentation crafted by*

**[Christian Schnapka](https://macstab.com)**  
Embedded Principal+ Engineer  
[Macstab GmbH](https://macstab.com) · Hamburg, Germany

*Building systems that operate correctly at the edges — including the ones you deliberately break.*

</div>
