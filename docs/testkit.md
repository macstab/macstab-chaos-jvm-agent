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
3. **`@WithChaos`** — declares a test's chaos in the plan JSON model; honoured by this extension and by the Spring Boot, Micronaut and Quarkus test extensions
4. **`ChaosAssertions`** — assertions on handle and diagnostics counters (`applied().atLeast(3)`, `never()`, `hasNoFailuresFor(...)`)

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
com.macstab.chaos.jvm.testkit.ChaosAgentExtension
```

## Lifecycle

```
@BeforeEach:
  TrackingChaosControlPlane tracker = new TrackingChaosControlPlane(ChaosPlatform.installLocally())
  — ChaosPlatform.installLocally() is idempotent; returns the same ChaosRuntime across tests
  — TrackingChaosControlPlane wraps the real control plane to record JVM-scoped handle activations,
    and the sessions opened and event listeners added through it
  ChaosSession session = tracker.openSession(testDisplayName)
  — session ID is a UUID; displayName is the test method display name
  — no scenario is active yet: the test's own @BeforeEach methods run without chaos

Around the test method (beforeTestExecution → test method → afterTestExecution):
  @WithChaos scenarios of the class, its enclosing classes and the method are activated
  — the thread that runs the test method is bound to the session while it runs: also the
    separate thread of @Timeout(threadMode = SEPARATE_THREAD), and each dynamic test of a
    @TestFactory (under concurrent execution a dynamic test may run after its factory's
    teardown, outside the session)
  — the scenarios stop right after the method, before the test's own @AfterEach methods

@AfterEach:
  session.close()
  — stops all session-scoped scenarios
  — unregisters session-scoped controllers from ScenarioRegistry
  tracker.stopTracked()
  — closes (stops and unregisters) any JVM-scoped handles activated via controlPlane.activate()
    during the test, so the next test can activate the same ids
  — then closes the sessions opened via controlPlane.openSession() and removes the listeners
    added via controlPlane.addEventListener() (a listener the test removed itself is skipped)
  — runs in finally block: executes even if session.close() throws
  — a failure to release one of them is logged as a [chaos-agent] WARNING and the rest are released
  — prevents JVM-scoped scenario, session and listener leakage into subsequent tests
```

## Parameter Resolution

`ChaosAgentExtension` resolves the following parameter types in test methods and lifecycle callbacks:
- `ChaosSession` — injects the current test's session
- `ChaosControlPlane` — injects the `TrackingChaosControlPlane` wrapping the JVM-wide control plane

Parameters of any other type are not resolved by this extension (JUnit delegates to other resolvers).

The framework test extensions leave a parameter to the framework's own resolver where that resolver claims it, so JUnit never sees two resolvers for one parameter: the Spring Boot `ChaosAgentExtension` the parameters Spring autowires (an autowired test constructor, `@Autowired`, `@Value`, `@Qualifier`), `ChaosQuarkusExtension` every constructor parameter (Quarkus resolves them all), and `ChaosMicronautExtension` the `ChaosControlPlane` of a `@MicronautTest` class unless it sets `resolveParameters = false`. The framework then injects its `ChaosControlPlane` bean. `ChaosMicronautExtension` finds `@MicronautTest` by name, so it also works without micronaut-test on the class path.

Scenarios activated through an injected `ChaosControlPlane` bean are released like a framework extension's own: in a `@MicronautChaosTest` and a `@QuarkusChaosTest` the bean is a tracking control plane, and what a test activates through it is stopped when the last running chaos test class ends, or when the application context (Micronaut) or the application (Quarkus) stops. The listeners and sessions the application adds through the bean are the application's and stay (`new TrackingChaosControlPlane(delegate, false)`); the plane a framework extension injects into a test releases them after the test class, as the testkit's does after each test. In a Quarkus test the bean is produced in Quarkus's class loader and `ChaosQuarkusExtension` runs in the system class loader: the producer reaches the extension through the system class loader, and the two share the api because it is a parent-first artifact. A runner that does not put the test class path on the system class loader gets the untracked control plane and one `[chaos-agent]` warning.

**`ChaosDiagnostics` is not injectable.** Obtain it via `controlPlane.diagnostics()`:
```java
void myTest(ChaosControlPlane controlPlane) {
    ChaosDiagnostics diagnostics = controlPlane.diagnostics();
}
```

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

# 4. Declarative chaos: `@WithChaos`

`@WithChaos` activates scenarios written in the same JSON model as a startup plan ([startup-config.md](startup-config.md)). The JSON is either inline (`value`) or a class-path resource (`resource`, with or without a leading `/` or `classpath:`), and is a whole plan or a single scenario object. The annotation is repeatable and allowed on the test class and on test methods.

```java
@ExtendWith(ChaosAgentExtension.class)
@WithChaos(resource = "chaos/slow-downstream.json")          // whole plan, from the class path
class CheckoutTest {

  @Test
  @WithChaos("""
      {"id": "slow-db", "scope": "SESSION",
       "selector": {"type": "jdbc", "operations": ["JDBC_STATEMENT_EXECUTE"]},
       "effect": {"type": "delay", "minDelay": "PT0.2S", "maxDelay": "PT0.2S"}}
      """)
  void timesOutOnSlowDatabase(ChaosControlPlane chaos) {
    // ... exercise the code ...
    ChaosAssertions.assertThat(chaos.diagnostics()).scenario("slow-db").applied().atLeast(1);
  }
}
```

| Scenario `scope` | Activated with              | Reaches                                                           |
|------------------|-----------------------------|-------------------------------------------------------------------|
| `SESSION`        | the test's `ChaosSession`   | threads bound to the session and the work they hand off ([sessions](user-guide.md)) |
| `JVM`            | the (tracking) control plane | every thread in the JVM                                           |

When each extension activates and stops them:

| Extension                                   | Class-level `@WithChaos`             | Method-level `@WithChaos`        | Test thread bound to the session |
|---------------------------------------------|--------------------------------------|----------------------------------|----------------------------------|
| testkit `ChaosAgentExtension`               | around each test method (session per test) | around each test method    | yes, while any `@WithChaos` applies |
| Spring Boot 3/4 `ChaosAgentExtension`       | `beforeAll` → `afterAll`             | around the test method          | no (bind, or use `JVM` scope)    |
| `ChaosMicronautExtension`                   | `beforeAll` → `afterAll`             | around the test method          | no (bind, or use `JVM` scope)    |
| `ChaosQuarkusExtension`                     | `beforeAll` → `afterAll`             | around the test method          | no (bind, or use `JVM` scope)    |

"Around the test method" means after the test's `@BeforeEach` methods and before its `@AfterEach` methods (`beforeTestExecution` → `afterTestExecution`): set-up and tear-down run without the method's chaos.

A `JVM`-scoped scenario id has one owner at a time. The testkit activates class-level `@WithChaos` for every test, so with concurrent test methods (`junit.jupiter.execution.parallel.mode.default=concurrent`) a class-level `JVM` scenario is activated again while it is still active and the second test fails with an activation conflict. Declare such scenarios with `SESSION` scope, or keep the class on one thread with `@Execution(ExecutionMode.SAME_THREAD)`.

A class-level `@WithChaos` applies to the class's `@Nested` classes too: the testkit activates the enclosing classes' scenarios, outermost first, for each nested test; the other extensions keep the outer class's scenarios active, in the session the nested classes share, while they run.

An annotation with neither or both of `value` and `resource`, a missing resource or invalid JSON fails the test before it runs with `IllegalArgumentException` (for invalid JSON its message names the line and column, and its cause is the parser's `ConfigLoadException`); scenarios already activated for that test are stopped first, and a failure to stop one is suppressed in that exception. Each annotation's plan keeps its name and observability settings: its `SESSION` scenarios are activated as one plan in the test's session, its `JVM` scenarios as one plan on the control plane. The Quarkus extension's older `@ChaosScenario` annotation still works but is deprecated for removal in 3.0: it knows only a few selectors and effect shorthands, while `@WithChaos` covers every selector, effect and policy field.

`WithChaosSupport.activate(element, controlPlane, session)` does the work and is public, for extensions of other frameworks.

---

# 5. Assertions: `ChaosAssertions`

```java
ChaosAssertions.assertThat(handle).applied().atLeast(3);
ChaosAssertions.assertThat(handle).applied().within(Duration.ofSeconds(2)).exactly(1);
ChaosAssertions.assertThat(handle).matched().never();
ChaosAssertions.assertThat(handle).hasState(ChaosDiagnostics.ScenarioState.ACTIVE);
ChaosAssertions.assertThat(chaos.diagnostics()).hasNoFailuresFor("slow-db").scenario("slow-db").applied().atMost(5);
```

| Assertion                         | Holds when                                                                            |
|-----------------------------------|---------------------------------------------------------------------------------------|
| `applied()` / `matched()`         | selects the counter: effects that took effect (L14 in [semantics.md](semantics.md)) or selector matches |
| `atLeast(n)` / `exactly(n)`       | the counter is ≥ n / = n; after `within(d)`, retried every 10 ms until it holds or `d` elapses |
| `atMost(n)` / `never()`           | the counter is ≤ n / 0; after `within(d)`, it must stay so for the whole of `d` (checked every 10 ms, failing at the first violation) |
| `hasState(s)`                     | the scenario is in state `s`                                                          |
| `hasNoFailures()`                 | no activation has failed since the agent started (JVM-wide; prefer the next one)      |
| `hasNoFailuresFor(id, more...)`   | no activation of these scenario ids has failed (at least one id; a repeated id counts once) |
| `scenario(id)`                    | starts scenario assertions read from diagnostics; fails if no scenario has the id     |

Failures are `org.opentest4j.AssertionFailedError`, which every JUnit and IDE renders as a test failure, with the scenario's key (`<scopeKey>::<id>`, e.g. `jvm::slow-db` or `session:<id>::slow-db`, for a handle and for `scenario(id)` alike), the expectation and the actual count in the message; a count failure also carries the expectation and the count as its expected and actual values, for the IDE's comparison view.

Bounds and timeouts are checked by one rule: a negative bound (`atLeast(-1)`, `atMost(-1)`, `exactly(-1)`) or a negative `within` timeout throws `IllegalArgumentException`, a `null` one `NullPointerException` naming it. A `within` timeout of any length is accepted (a timeout beyond what `System.nanoTime` can count waits without limit).

The waiting under `within` is agent-internal work: when the agent is installed, the assertion's pauses run as an internal call, so a `THREAD_SLEEP` scenario neither delays nor counts them. Without the agent nothing is hooked and the bridge is never touched.

---

# 5b. Sessions across HTTP

A session follows the test's own threads and the work they hand off. To reach the server side of an HTTP call — the app under test in `@SpringBootTest(webEnvironment = RANDOM_PORT)`, or another service — the request carries the session:

- the JDK `HttpClient` and `HttpURLConnection` (and so `TestRestTemplate`, `RestTemplate`, `RestClient` on those transports) add `X-Chaos-Session: <id>` to requests sent from a thread in a session;
- the Spring Boot starters' `ChaosSessionFilter` (test starters always, runtime starters with `macstab.chaos.enabled`) binds the request thread to that session; elsewhere call `controlPlane.bindSession(header)` in your own filter.

A task the test submits to a thread pool carries the session (and so the header on the calls it makes) only once the session has a scenario: activate the session's scenarios before submitting work. A pool task submitted from a session without scenarios runs outside any session and its requests carry no `X-Chaos-Session`.

The header decides per request, so a server whose worker threads inherited a session (started while one was bound) still runs a request from another session — or with an unknown id, from none — accordingly. `SessionAcrossHttpTest` in the `sb3-retry-resilience-test` example shows a session-scoped downstream failure that hits only the requests from the test's session.

---

# 5a. Replaying a flaky run

A probabilistic scenario without a seed fails differently on every run. Record which matches took effect and replay exactly those:

```java
ChaosDecisionRecorder recorder = new ChaosDecisionRecorder();
chaos.addEventListener(recorder);
// ... the run that failed ...
System.out.println(recorder.decisions());          // {flaky-db=[3, 17, 18]}

// the next run fires on the same matches:
chaos.activate(recorder.replay(flakyDbScenario));
// or by hand / in a plan: "activationPolicy": {"replayMatches": [3, 17, 18]}
```

Match numbers count the scenario's selector matches since it started; replay reproduces the run when the calls arrive in the same order (single-threaded paths, or a deterministic test). See law L9a in [semantics.md](semantics.md).

The recorder keeps one log per scenario and scope: `replay(scenario)` replays the JVM-scoped run, `replay(scenario, sessionId)` the run in that session, and neither mixes in the other's matches. A scenario that starts again (a new activation, a reload) starts a new log, since its match numbers start again from 1. A log keeps at most `ChaosDecisionRecorder.MAX_DECISIONS` (1,000,000) numbers; past that `replay` throws `IllegalStateException` ("decision log truncated"), since the run could not be reproduced.

---

# 6. Self-Attach Requirements

`ChaosPlatform.installLocally()` uses the JDK Attach API to self-attach the agent. Requirements:

- A JDK runtime with the `jdk.attach` module (a JRE or a `jlink` image without it cannot self-attach)
- No JVM flag is required: when the JVM does not allow a process to attach to itself (`jdk.attach.allowAttachSelf` unset, the default since JDK 9), ByteBuddy attaches from a short-lived helper process, and the agent installs as usual. On JDK 21 and later the JVM then prints the [JEP 451](https://openjdk.org/jeps/451) warning on stderr ("WARNING: A Java agent has been loaded dynamically …"). It is a warning only; chaos works. The agent does not suppress it. To avoid it, either load the agent at startup with `-javaagent:` (no dynamic loading, no warning), or add `-XX:+EnableDynamicAgentLoading` to the test JVM, which allows the dynamic load explicitly. A JVM started with `-XX:-EnableDynamicAgentLoading` refuses the self-attach; `installLocally()` then fails with `IllegalStateException`, and `-javaagent:` is the way to run.

**No `--add-opens` flags required.** The agent self-grants every JDK module open it needs at install time — manifest `Add-Opens` for `-javaagent:` startup attach, `Instrumentation#redefineModule` for self-attach. See [Module access strategy](instrumentation.md#module-access-strategy).
- `chaos-agent-bootstrap` must be on the test class path (the testkit depends on it). `ChaosPlatform.installLocally()` obtains the `Instrumentation` through ByteBuddy's `ByteBuddyAgent.install()`; no agent jar is searched for or configured.

**Gradle test configuration example** (optional; only avoids the JEP 451 warning):
```kotlin
// build.gradle.kts
tasks.test {
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}
```

---

# 7. Typical Usage Patterns

## Session-scoped delay on executor

```java
@ExtendWith(ChaosAgentExtension.class)
class ExecutorDelayTest {
    @Test
    void slowExecutorShouldTriggerTimeout(ChaosSession session) throws Exception {
        ChaosScenario scenario = ChaosScenario.builder("slow-executor")
            .scope(ChaosScenario.ScenarioScope.SESSION)
            .selector(ChaosSelector.executor(
                Set.of(OperationType.EXECUTOR_SUBMIT, OperationType.EXECUTOR_WORKER_RUN)))
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
        ChaosScenario.builder("heap-stress")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.stress(StressTarget.HEAP))
            .effect(ChaosEffect.heapPressure(64L * 1024 * 1024, 64 * 1024))
            .activationPolicy(ActivationPolicy.always())
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
    session.activate(ChaosScenario.builder("db-socket-reject")
        .scope(ChaosScenario.ScenarioScope.SESSION)
        .selector(ChaosSelector.network(
            Set.of(OperationType.SOCKET_CONNECT),
            NamePattern.regex("db\\.internal\\..*")))
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
    ChaosActivationHandle handle = session.activate(ChaosScenario.builder("gate-test")
        .scope(ChaosScenario.ScenarioScope.SESSION)
        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
        .effect(ChaosEffect.gate(Duration.ofSeconds(5)))   // the submit proceeds after 5 s at the latest
        .build()
    );

    Thread t = new Thread(session.wrap(() -> executor.execute(() -> {}))); // the submit blocks on the gate
    t.start();

    // Wait until the submit is held at the gate: the thread parks there with the gate's timeout.
    // A release() before that would not let it through (the gate closes again at once).
    while (t.getState() != Thread.State.TIMED_WAITING) {
        Thread.onSpinWait();
    }
    assertTrue(t.isAlive());

    handle.release(); // unblocks the gate
    t.join(2000);
    assertFalse(t.isAlive());
}
```

---

# 8. Anti-Patterns and Misuse Risks

| Anti-pattern                                                               | Risk                                                                                                         | Correct approach                                                                                     |
|----------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------|
| Activating JVM-scoped scenario without closing the handle                  | Scenario leaks across tests; chaos remains active for subsequent tests in the same JVM                       | Always call `handle.close()` in `finally`                                                            |
| Not binding session before submitting work to an executor                  | Session context not propagated; executor tasks not affected by session-scoped chaos                          | Use `session.bind()` before submitting, or `session.wrap(task)` to create a context-carrying wrapper |
| Submitting pool work before the session's first scenario is activated     | The task runs outside the session: session-scoped chaos and `X-Chaos-Session` do not reach it                | Activate the session's scenarios before submitting                                                   |
| Using `ChaosTestKit.install()` in a JVM started with `-XX:-EnableDynamicAgentLoading` | `IllegalStateException` at runtime (the JVM refuses dynamic agents)                                          | Remove the flag, or load the agent with `-javaagent:`                                                |
| Checking `appliedCount` without waiting for scenarios to fire              | Race condition: the scenario may not have fired yet                                                          | Use event listeners or explicit synchronization points                                               |
| Registering the same scenario ID twice in the same scope                   | `ChaosActivationException("scenario key already active")` (only while the previous one is REGISTERED/ACTIVE) | Use unique IDs per test; let `ChaosAgentExtension` manage session lifecycle                          |

---

# 9. Diagnostics in Test Context

```java
@Test
void debugChaos(ChaosSession session, ChaosControlPlane controlPlane) {
    // ... activate scenarios ...
    ChaosDiagnostics diag = controlPlane.diagnostics();   // ChaosDiagnostics is not injectable

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

# 10. References

- Reference: JUnit 5 — `Extension`, `BeforeEachCallback`, `AfterEachCallback`, `ParameterResolver` — https://junit.org/junit5/docs/current/user-guide/#extensions
- Reference: Java SE API — `com.sun.tools.attach.VirtualMachine` (self-attach via Attach API) — https://docs.oracle.com/en/java/docs/api/jdk.attach/com/sun/tools/attach/VirtualMachine.html
- Reference: JEP 451 — Prepare to Disallow the Dynamic Loading of Agents (JDK 21+; the warning a self-attach prints) — https://openjdk.org/jeps/451
- Reference: JSR-133 — Java Memory Model — `ThreadLocal` visibility guarantees per thread — https://jcp.org/aboutJava/communityprocess/mrel/jsr133/index.html

---

<div align="center">

*Architecture, implementation, and documentation crafted with Love and Passion by*

**[Christian Schnapka](https://macstab.com)**  
Embedded Principal+ Engineer  
[Macstab GmbH](https://macstab.com) · Hamburg, Germany

*Building systems that operate correctly at the edges — including the ones you deliberately break.*

</div>
