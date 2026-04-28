<!--
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Engineered by  Christian Schnapka
                 Embedded Principal+ Engineer
                 Macstab GmbH · Hamburg, Germany
                 https://macstab.com
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-->

# chaos-agent-api — Public API Contract Reference

> Stable external contract surface. This is the only module application and test code should depend on directly.
> 
> *Engineered by* **[Christian Schnapka](https://macstab.com)** — Embedded Principal+ Engineer · [Macstab GmbH](https://macstab.com) · Hamburg, Germany

---

# 1. Overview

## Purpose

`chaos-agent-api` defines the stable public contract for controlling the chaos agent. It contains:
- `ChaosControlPlane` — top-level control interface
- `ChaosSession` — per-test / per-thread isolation scope
- `ChaosActivationHandle` — lifecycle handle for a single activated scenario
- `ChaosPlan` — named collection of scenarios for batch activation
- `ChaosScenario` — the atomic chaos unit: selector + effect + policy
- `ChaosSelector` — sealed hierarchy of matching rules
- `ChaosEffect` — sealed hierarchy of chaos effects
- `ActivationPolicy` — guards on when and how often an effect fires
- `ChaosDiagnostics` — read-only inspection of agent state
- `OperationType` — enum of intercepted JDK operations
- `ChaosEvent` / `ChaosEventListener` — event bus contract
- `ChaosMetricsSink` — metrics integration point
- All exception types

## Stability Contract

- All public types, methods, and fields in this module have backward-compatible evolution guarantees
- Additive changes (new methods with defaults, new enum constants) are allowed in minor versions
- Removals and breaking changes require major version bump with prior deprecation

---

# 2. ChaosControlPlane

Entry point to the chaos agent, obtained via `ChaosAgentBootstrap.current()` or `ChaosTestKit.install()`.

```java
public interface ChaosControlPlane {
    // Activate a single scenario (JVM-scoped)
    ChaosActivationHandle activate(ChaosScenario scenario);

    // Activate all scenarios in a plan (JVM-scoped; all must be JVM scope)
    ChaosActivationHandle activate(ChaosPlan plan);

    // Open a new session for per-thread chaos isolation
    ChaosSession openSession(String displayName);

    // Inspect current agent state
    ChaosDiagnostics diagnostics();

    // Register event listener. Invoked synchronously on every ChaosEvent.Type:
    //   REGISTERED, STARTED, STOPPED, RELEASED, APPLIED, SKIPPED, FAILED
    void addEventListener(ChaosEventListener listener);

    // Stop all active scenarios (does not uninstall instrumentation)
    void close();
}
```

**`activate(ChaosScenario)`**: Requires `scenario.scope() == JVM`. Registers the scenario with `ScenarioRegistry`, starts it if `startMode == AUTOMATIC`, returns a handle. Throws `ChaosActivationException` on duplicate ID or incompatible configuration.

**`activate(ChaosPlan)`**: Rejects any session-scoped scenario in the plan with `ChaosActivationException`. Activates all scenarios atomically from the caller's perspective (though internal registration is sequential).

**`openSession(String)`**: Creates a `DefaultChaosSession` with a UUID and the given display name. Does not bind the session to the current thread — call `session.bind()` explicitly.

---

# 3. ChaosSession

Session-scoped chaos isolation. A session represents a logical test or operation boundary on one or more threads.

```java
public interface ChaosSession {
    // Activate a session-scoped scenario (scenario.scope() must be SESSION)
    ChaosActivationHandle activate(ChaosScenario scenario);

    // Bind this session to the current thread for the duration of the AutoCloseable block
    ScopeBinding bind();

    // Wrap a Runnable to carry this session's context into another thread
    Runnable wrap(Runnable task);

    // Wrap a Callable to carry this session's context into another thread
    <T> Callable<T> wrap(Callable<T> task);

    // Display name (for diagnostics)
    String displayName();

    // UUID string (used as session ID in ScenarioController evaluation)
    String id();

    // Stop all session-scoped scenarios; unregister controllers; release session resources
    void close();

    interface ScopeBinding extends AutoCloseable {
        void close(); // unbinds; no exception
    }
}
```

**Thread propagation**: `bind()` sets the session ID in a `ThreadLocal` on the calling thread. The session ID is automatically propagated to tasks submitted to `ThreadPoolExecutor` (via the `decorateExecutorRunnable/Callable` instrumentation), but only for tasks submitted within the `bind()` scope.

For tasks submitted to executors outside a `bind()` block, use `session.wrap(task)` to explicitly carry the session context.

**`close()` contract**: Stops all session-scoped scenarios. Subsequent calls to `bind()` on a closed session are undefined behavior.

---

# 4. ChaosActivationHandle

```java
public interface ChaosActivationHandle extends AutoCloseable {
    // Unique ID of the activated scenario (plan handles return the composite plan ID)
    String id();

    // Start the scenario (if startMode == MANUAL; no-op for AUTOMATIC)
    void start();

    // Stop the scenario (permanent; cannot be restarted)
    void stop();

    // Release a gate-effect hold, unblocking all waiting threads
    void release();

    // Current lifecycle state (snapshot; may change concurrently)
    ChaosDiagnostics.ScenarioState state();

    // Equivalent to stop()
    @Override default void close() { stop(); }
}
```

**`release()`**: Applicable only to gate-effect scenarios. Unblocks all threads currently waiting in `ManualGate.await()`. Does not stop the scenario — subsequent matching invocations will re-enter the gate.

**`state()`**: Returns one of `REGISTERED`, `ACTIVE`, `INACTIVE`, `STOPPED`. Useful for assertions in tests (`assertThat(handle.state()).isEqualTo(ACTIVE)`).

**Idempotency**: `stop()` and `close()` are idempotent; calling them multiple times is safe.

**Plan activations**: `controlPlane.activate(ChaosPlan)` returns a composite handle whose `stop()` stops every scenario in the plan and whose `id()` reports the plan ID.

---

# 5. ChaosScenario

```java
ChaosScenario scenario = ChaosScenario.builder("unique-id")  // required; unique within scope
    .description("optional label")
    .scope(ChaosScenario.ScenarioScope.SESSION)               // JVM or SESSION
    .precedence(0)                                            // higher wins terminal action tie
    .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
    .effect(ChaosEffect.delay(Duration.ofMillis(100)))
    .activationPolicy(ActivationPolicy.always())
    .build();
```

**`scope`**: `JVM` — affects all threads; `SESSION` — affects only threads bound to the session that activated it. JVM-scoped scenarios can only be activated via `ChaosControlPlane.activate()`; session-scoped scenarios can only be activated via `ChaosSession.activate()`.

**`precedence`**: Used to break ties when multiple scenarios match the same invocation and both have terminal actions (THROW, SUPPRESS, etc.). Higher precedence wins. Delay effects are always additive regardless of precedence.

---

# 6. ChaosSelector

Sealed hierarchy. Each selector is an immutable record built through a static factory. Every factory (except `jdbc()`) takes a `Set<OperationType>` naming the operations the selector targets; pass an empty set to accept every operation the selector understands. Pattern-based filters take `NamePattern` values (`NamePattern.any()`, `NamePattern.prefix("...")`, `NamePattern.regex("...")`).

```java
// Thread pool task submission and worker execution
ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT, OperationType.EXECUTOR_WORKER_RUN));

// Scheduled task submission and tick execution
ChaosSelector.scheduling(Set.of(OperationType.SCHEDULE_SUBMIT, OperationType.SCHEDULE_TICK));

// Thread lifecycle (platform or virtual)
ChaosSelector.thread(Set.of(OperationType.THREAD_START), ChaosSelector.ThreadKind.VIRTUAL);

// BlockingQueue operations
ChaosSelector.queue(Set.of(OperationType.QUEUE_PUT, OperationType.QUEUE_TAKE));

// CompletableFuture completion
ChaosSelector.async(Set.of(OperationType.ASYNC_COMPLETE, OperationType.ASYNC_CANCEL));

// Network sockets — optionally filter by remote host pattern
ChaosSelector.network(Set.of(OperationType.SOCKET_CONNECT));
ChaosSelector.network(
    Set.of(OperationType.SOCKET_CONNECT),
    NamePattern.prefix("db.internal"));

// NIO channels and Selectors
ChaosSelector.nio(Set.of(OperationType.NIO_SELECTOR_SELECT));
ChaosSelector.nio(
    Set.of(OperationType.NIO_SELECTOR_SELECT),
    NamePattern.regex("sun\\.nio\\.ch\\..*"));

// ClassLoader operations — filter by loader class and target class name
ChaosSelector.classLoading(
    Set.of(OperationType.CLASS_LOAD),
    NamePattern.any(),                      // any loader class
    NamePattern.prefix("com.example.plugin"));

// Arbitrary method entry/exit
ChaosSelector.method(
    Set.of(OperationType.METHOD_ENTER, OperationType.METHOD_EXIT),
    NamePattern.exact("com.example.MyService"),
    NamePattern.exact("processOrder"));

// Monitor enter/exit (synchronized)
ChaosSelector.monitor(Set.of(OperationType.MONITOR_ENTER));

// JVM runtime intrinsics (clock, GC, exit, …)
ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_CLOCK_MILLIS));

// ThreadLocal operations — optionally filter by stored-value type
ChaosSelector.threadLocal(Set.of(OperationType.THREAD_LOCAL_GET));
ChaosSelector.threadLocal(
    Set.of(OperationType.THREAD_LOCAL_GET),
    NamePattern.prefix("com.example.tenant"));

// Shutdown hooks and System.exit / Runtime.halt
ChaosSelector.shutdown(Set.of(OperationType.SYSTEM_EXIT_REQUEST, OperationType.SHUTDOWN_HOOK_REGISTER));

// HTTP client requests — sync and async. Matches both HttpClient#send and #sendAsync.
ChaosSelector.httpClient(
    Set.of(OperationType.HTTP_CLIENT_SEND, OperationType.HTTP_CLIENT_SEND_ASYNC));
ChaosSelector.httpClient(
    Set.of(OperationType.HTTP_CLIENT_SEND, OperationType.HTTP_CLIENT_SEND_ASYNC),
    NamePattern.regex("https://api\\.example\\.com/.*"));

// JDBC (all operations, or explicit subset)
ChaosSelector.jdbc();
ChaosSelector.jdbc(OperationType.JDBC_STATEMENT_EXECUTE, OperationType.JDBC_PREPARED_STATEMENT);

// DNS resolution — optionally filter by hostname pattern
ChaosSelector.dns(Set.of(OperationType.DNS_RESOLVE));
ChaosSelector.dns(
    Set.of(OperationType.DNS_RESOLVE),
    NamePattern.prefix("db.internal"));

// SSL/TLS handshake
ChaosSelector.ssl(Set.of(OperationType.SSL_HANDSHAKE));

// File I/O
ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_READ, OperationType.FILE_IO_WRITE));

// Background stressor lifecycle binding
ChaosSelector.stress(StressTarget.HEAP);
```

---

# 7. ChaosEffect

Sealed hierarchy. Divided into interceptor effects (applied inline on the calling thread) and stressor effects (run as background threads for the scenario lifetime).

## Interceptor Effects

```java
// Fixed delay
ChaosEffect.delay(Duration.ofMillis(200));

// Randomized delay: uniform in [min, max]
ChaosEffect.delay(Duration.ofMillis(50), Duration.ofMillis(500));

// Block until handle.release() is called (or maxBlock elapses)
ChaosEffect.gate(Duration.ofSeconds(30));

// Throw an operation-appropriate exception
ChaosEffect.reject("connection refused");

// Silently discard; return null/false per operation contract
ChaosEffect.suppress();

// Complete a CompletableFuture exceptionally
ChaosEffect.exceptionalCompletion(
    ChaosEffect.ExceptionalCompletionEffect.FailureKind.TIMEOUT,
    "simulated upstream timeout");

// Inject any exception at method entry (fully qualified class name)
ChaosEffect.injectException("java.io.IOException", "chaos");

// Corrupt a method's return value
ChaosEffect.corruptReturnValue(ChaosEffect.ReturnValueStrategy.NULL);

// Skew System.currentTimeMillis() / nanoTime()
ChaosEffect.skewClock(Duration.ofMillis(5000), ChaosEffect.ClockSkewMode.FIXED);
ChaosEffect.skewClock(Duration.ofMillis(100),  ChaosEffect.ClockSkewMode.DRIFT);  // accumulates per call
ChaosEffect.skewClock(Duration.ZERO,            ChaosEffect.ClockSkewMode.FREEZE); // freezes at start time

// Inject spurious NIO Selector.select() return (returns 0 immediately)
ChaosEffect.spuriousWakeup();
```

## Stressor Effects

```java
ChaosEffect.heapPressure(64L * 1024 * 1024, 64 * 1024);
ChaosEffect.keepAlive("chaos-keepalive", false, Duration.ofSeconds(5));
ChaosEffect.metaspacePressure(500, 16);
ChaosEffect.directBufferPressure(128L * 1024 * 1024, 1024 * 1024);
ChaosEffect.gcPressure(50L * 1024 * 1024, Duration.ofSeconds(30));
ChaosEffect.finalizerBacklog(10_000, Duration.ofMillis(50));
ChaosEffect.deadlock(2);                                  // NON-RECOVERABLE — requires withDestructiveEffects()
ChaosEffect.threadLeak(4, "chaos-leak-", true);           // NON-RECOVERABLE — requires withDestructiveEffects()
ChaosEffect.threadLocalLeak(100, 4096);
ChaosEffect.monitorContention(/* implementation-specific args — see Javadoc */);
ChaosEffect.codeCachePressure(200, 8);
ChaosEffect.safepointStorm(Duration.ofSeconds(1));
ChaosEffect.stringInternPressure(10_000, 64);
ChaosEffect.referenceQueueFlood(10_000, Duration.ofMillis(50));
```

**Warning**: `deadlock()` and `threadLeak()` are non-recoverable within the JVM process. Use only in controlled test environments. `close()` on these scenarios cannot terminate deadlocked or permanently-parked threads.

---

# 8. ActivationPolicy

`ActivationPolicy` is a record — there is no builder. Use the canonical constructor directly or one of the static factory methods:

```java
// Most common: always fire, start immediately
ActivationPolicy.always()

// Opt in to non-recoverable effects (deadlock, thread leak)
ActivationPolicy.withDestructiveEffects()

// Start paused; fire only after handle.start()
ActivationPolicy.manual()

// Fine-grained: 30% probability, capped at 100 total, seeded for reproducibility
new ActivationPolicy(
    StartMode.AUTOMATIC,
    0.30,           // probability
    0,              // activateAfterMatches (no warm-up)
    100L,           // maxApplications
    null,           // activeFor (no time bound)
    null,           // rateLimit
    42L,            // randomSeed
    false           // allowDestructiveEffects
);
```

All guards compose as AND: a scenario fires only if every configured guard passes.

### Fields

| Field                     | Type        | Default     | Meaning                                                                                                                                                                                                                       |
|---------------------------|-------------|-------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `startMode`               | `StartMode` | `AUTOMATIC` | `AUTOMATIC` starts on registration; `MANUAL` waits for `handle.start()`                                                                                                                                                       |
| `probability`             | `double`    | `1.0`       | Fraction of passing matches that fire; must be in `(0.0, 1.0]`. An explicit `0.0` is rejected with `IllegalArgumentException`; omit the scenario activation entirely to disable it. A `null` value in JSON defaults to `1.0`. |
| `activateAfterMatches`    | `long`      | `0`         | Skip this many initial matches before becoming eligible                                                                                                                                                                       |
| `maxApplications`         | `Long`      | `null`      | Hard cap on total applications; `null` = unlimited                                                                                                                                                                            |
| `activeFor`               | `Duration`  | `null`      | Auto-expire this long after first start; `null` = no expiry                                                                                                                                                                   |
| `rateLimit`               | `RateLimit` | `null`      | Sliding-window permit cap; `null` = unlimited                                                                                                                                                                                 |
| `randomSeed`              | `Long`      | `0L`        | Seed for `SplittableRandom`; XOR-ed with `scenario.id().hashCode()` for per-scenario uniqueness                                                                                                                               |
| `allowDestructiveEffects` | `boolean`   | `false`     | Must be `true` to activate `DeadlockEffect` or `ThreadLeakEffect`; see below                                                                                                                                                  |

### Destructive Effects Safeguard

`DeadlockEffect` and `ThreadLeakEffect` create JVM state that cannot be recovered within a running process — deadlocked threads cannot be interrupted; leaked non-daemon threads prevent JVM exit until the process is killed.

`CompatibilityValidator` rejects any scenario using these effects unless `allowDestructiveEffects == true`. The rejection is a `ChaosActivationException` thrown at registration time, not at effect application time. This is an opt-in correctness guard, not a security boundary — the calling code is trusted.

```java
// Correct: explicit opt-in
ChaosScenario.builder("deadlock-test")
    .selector(ChaosSelector.stress(StressTarget.DEADLOCK))
    .effect(ChaosEffect.deadlock(2))
    .activationPolicy(ActivationPolicy.withDestructiveEffects())
    .build();

// Rejected at activation: throws ChaosActivationException
ChaosScenario.builder("deadlock-test")
    .selector(ChaosSelector.stress(StressTarget.DEADLOCK))
    .effect(ChaosEffect.deadlock(2))
    .activationPolicy(ActivationPolicy.always())  // allowDestructiveEffects=false
    .build();
```

**`randomSeed`**: With the same seed, probability sampling is deterministic across runs given the same sequence of `matchedCount` values. Different scenarios with the same seed still produce different samples because the seed is XOR-ed with `scenario.id().hashCode()`.

---

# 9. OperationType

The `OperationType` enum defines all intercepted JDK operations. Full list:

| Category                        | OperationType values                                                                                        |
|---------------------------------|-------------------------------------------------------------------------------------------------------------|
| Executor                        | `EXECUTOR_SUBMIT`, `EXECUTOR_WORKER_RUN`, `EXECUTOR_SHUTDOWN`, `EXECUTOR_AWAIT_TERMINATION`                 |
| Scheduled                       | `SCHEDULE_SUBMIT`, `SCHEDULE_TICK`                                                                          |
| ForkJoin                        | `FORK_JOIN_TASK_RUN`                                                                                        |
| Thread                          | `THREAD_START`, `VIRTUAL_THREAD_START`                                                                      |
| Queue                           | `QUEUE_PUT`, `QUEUE_TAKE`, `QUEUE_OFFER`, `QUEUE_POLL`                                                      |
| Async                           | `ASYNC_COMPLETE`, `ASYNC_COMPLETE_EXCEPTIONALLY`, `ASYNC_CANCEL`                                            |
| Clock (auto-wired)              | `INSTANT_NOW`, `LOCAL_DATE_TIME_NOW`, `ZONED_DATE_TIME_NOW`, `DATE_NEW`                                     |
| Clock (manual hook — see §11)   | `SYSTEM_CLOCK_MILLIS`, `SYSTEM_CLOCK_NANOS`                                                                 |
| GC                              | `SYSTEM_GC_REQUEST`                                                                                         |
| Exit                            | `SYSTEM_EXIT_REQUEST`                                                                                       |
| Reflection                      | `REFLECTION_INVOKE`                                                                                         |
| Memory                          | `DIRECT_BUFFER_ALLOCATE`                                                                                    |
| Serialization                   | `OBJECT_DESERIALIZE`, `OBJECT_SERIALIZE`                                                                    |
| Class                           | `CLASS_LOAD`, `CLASS_DEFINE`, `RESOURCE_LOAD`                                                               |
| Synchronization                 | `MONITOR_ENTER`, `THREAD_PARK`                                                                              |
| NIO                             | `NIO_SELECTOR_SELECT`, `NIO_CHANNEL_READ`, `NIO_CHANNEL_WRITE`, `NIO_CHANNEL_CONNECT`, `NIO_CHANNEL_ACCEPT` |
| Network                         | `SOCKET_CONNECT`, `SOCKET_ACCEPT`, `SOCKET_READ`, `SOCKET_WRITE`, `SOCKET_CLOSE`                            |
| Infrastructure                  | `JNDI_LOOKUP`, `JMX_INVOKE`, `JMX_GET_ATTR`, `NATIVE_LIBRARY_LOAD`                                          |
| Compression                     | `ZIP_INFLATE`, `ZIP_DEFLATE`                                                                                |
| ThreadLocal                     | `THREAD_LOCAL_GET`, `THREAD_LOCAL_SET`                                                                      |
| Shutdown                        | `SHUTDOWN_HOOK_REGISTER`                                                                                    |
| Method (manual hook — see §9.1) | `METHOD_ENTER`, `METHOD_EXIT`                                                                               |
| Stressor                        | `LIFECYCLE` (internal; targets stressor start/stop)                                                         |

## 9.1 Manual hook operation types

Most operation types in §9 are **auto-wired**: the agent installs ByteBuddy advice on a fixed JDK call site at `premain`, the advice fires automatically whenever application code reaches that site, and zero application changes are needed.

Four operation types are **manual-hook only** — fully functional, fully tested, but the agent cannot trigger them automatically and the application must route the event through the agent's public API:

| OperationType         | Why it is not auto-wired                                                                                                                                                                                                                            | Public hook                                                      |
|-----------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------|
| `SYSTEM_CLOCK_MILLIS` | `System.currentTimeMillis()` is `native @IntrinsicCandidate`; HotSpot JIT replaces the call with a direct hardware-clock instruction (`RDTSC` / `MRS CNTVCT_EL0`) that bypasses any bytecode wrapper. Hard JVM limitation for `-javaagent:` agents. | `chaosRuntime.adjustClockMillis(real)`                           |
| `SYSTEM_CLOCK_NANOS`  | Same as above for `System.nanoTime()`.                                                                                                                                                                                                              | `chaosRuntime.adjustClockNanos(real)`                            |
| `METHOD_ENTER`        | The agent has no engine that takes a `MethodSelector` and dynamically rewrites every matching class on activation. The runtime evaluation is built; the dynamic instrumentation pass is not.                                                        | `chaosRuntime.beforeMethodEnter(class, method)`                  |
| `METHOD_EXIT`         | Same as above.                                                                                                                                                                                                                                      | `chaosRuntime.afterMethodExit(class, method, returnType, value)` |

Selector matching, effect application, activation policy (probability, schedules, max activations), `ChaosDiagnostics`, and `ChaosEventListener` all behave identically to the auto-wired operation types — once the hook is invoked.

### Clock skew via `TimeProvider` wrapper

Most applications already use a `TimeProvider` / `Clock` wrapper for testability. Route real time through the agent in that wrapper:

```java
public final class TimeProvider {
    private final ChaosRuntime chaos;
    public long currentTimeMillis() {
        return chaos.adjustClockMillis(System.currentTimeMillis());
    }
    public long nanoTime() {
        return chaos.adjustClockNanos(System.nanoTime());
    }
}
```

Then any `JvmRuntimeSelector` scenario with `SYSTEM_CLOCK_MILLIS` and a `ClockSkewEffect` skews every `timeProvider.currentTimeMillis()` reading. For zero-config skew of plain `Instant.now()` / `LocalDateTime.now()` / `ZonedDateTime.now()` / `new Date()` call sites, use the auto-wired operations instead.

### Method entry / exit via Spring AOP, AspectJ, or interceptor

The agent does not auto-rewrite arbitrary user methods. Wire entry / exit hooks from any interception machinery the application already runs — Spring AOP `@Around`, AspectJ, Micronaut / Quarkus interceptors, an annotation processor, or your own ByteBuddy advice:

```java
@Around("@annotation(com.example.Audited)")
public Object intercept(ProceedingJoinPoint pjp) throws Throwable {
    String cls = pjp.getTarget().getClass().getName();
    String mth = pjp.getSignature().getName();

    chaosRuntime.beforeMethodEnter(cls, mth);            // may throw injected exception

    Object result = pjp.proceed();

    return chaosRuntime.afterMethodExit(
        cls, mth,
        ((MethodSignature) pjp.getSignature()).getReturnType(),
        result);                                          // may corrupt return value
}
```

A `MethodSelector` scenario then drives `ExceptionInjectionEffect` (entry) or `ReturnValueCorruptionEffect` (exit) for any matching class+method pattern.

---

# 10. ChaosDiagnostics

```java
public interface ChaosDiagnostics {
    // Full point-in-time snapshot of all registered scenarios
    Snapshot snapshot();

    // Lookup a specific scenario by ID
    Optional<ScenarioReport> scenario(String id);

    // Human-readable multi-line text dump (suitable for logs or JMX inspection)
    String debugDump();

    record Snapshot(
        Instant capturedAt,
        List<ScenarioReport> scenarios,
        List<ActivationFailure> failures,
        Map<String, String> runtimeDetails
    ) {}

    record ScenarioReport(
        String id,
        String description,
        String scopeKey,
        ChaosScenario.ScenarioScope scope,
        ScenarioState state,
        long matchedCount,
        long appliedCount,
        String reason
    ) {}

    record ActivationFailure(
        String scenarioId,
        FailureCategory category,
        String message
    ) {}

    enum ScenarioState { REGISTERED, ACTIVE, INACTIVE, STOPPED }

    enum FailureCategory {
        INVALID_CONFIGURATION,
        UNSUPPORTED_RUNTIME,
        ACTIVATION_CONFLICT
    }
}
```

**`matchedCount`**: total invocations that matched the selector since `start()`. Incremented before activation policy checks.

**`appliedCount`**: total invocations that passed all activation policy checks and had the effect applied.

**`reason`**: last observed reason for a state transition or evaluation failure. Examples: `"started"`, `"stopped"`, `"expired"`, `"max applications reached"`. This is diagnostic text, not a machine-readable code.

---

# 11. ChaosEventListener

```java
@FunctionalInterface
public interface ChaosEventListener {
    void onEvent(ChaosEvent event);
}

record ChaosEvent(
    ChaosEvent.Type type,
    String scenarioId,
    String message,
    Map<String, String> attributes,
    Instant timestamp
) {
    enum Type {
        REGISTERED,  // scenario was registered with the control plane
        STARTED,     // scenario transitioned INACTIVE → ACTIVE
        APPLIED,     // effect was applied to a matched operation
        SKIPPED,     // selector matched but activation policy vetoed (probability / rate limit)
        FAILED,      // effect evaluation threw unexpectedly (listener / reflective failure)
        RELEASED,    // gate-effect release() unblocked waiters
        STOPPED      // scenario transitioned to STOPPED
    }
}
```

**Contract**: Listeners are called synchronously on the application thread that triggered the event. Must not block, perform I/O, or throw checked exceptions. Listener exceptions are caught and logged; they do not abort the chaos operation.

**Observability use**: Subscribe a listener to count `APPLIED` vs `SKIPPED` per scenario ID — this is the ground truth for "how many times did my chaos actually fire during this test?".

---

# 12. Exception Types

| Exception | Module | When thrown |
|-----------|--------|-------------|
| `ChaosActivationException` | `chaos-agent-api` | Scenario activation fails: duplicate ID, scope mismatch, invalid configuration |
| `ChaosUnsupportedFeatureException` | `chaos-agent-api` | Scenario requires a JVM feature not available on the running JVM (e.g., JFR absent on a stripped JRE) |
| `ConfigLoadException` | `chaos-agent-startup-config` | Startup configuration cannot be read or parsed. Not part of the core API surface — only thrown from `StartupConfigLoader.load()` and only visible to code that imports the startup-config module. |

All are `RuntimeException` subclasses. They propagate to the caller of `activate()` or `StartupConfigLoader.load()`.

---

# 13. ChaosMetricsSink

```java
public interface ChaosMetricsSink {
    void increment(String metricName, Map<String, String> tags);

    ChaosMetricsSink NOOP = (name, tags) -> {};
}
```

Currently emitted metric: `chaos.effect.applied` with tags `scenarioId` and `operation`.

Inject a custom sink via `new ChaosRuntime(clock, yourSink)` (bootstrap-level customization; not exposed via the public API currently — this is a known gap in the public API surface).

---

# 14. References

- Reference: Java SE API — `java.util.concurrent.atomic.AtomicBoolean`, `AtomicLong` — https://docs.oracle.com/en/java/docs/api/java.base/java/util/concurrent/atomic/AtomicLong.html
- Reference: JEP 444 — Virtual Threads; `Thread.isVirtual()`; carrier-thread pinning — https://openjdk.org/jeps/444
- Reference: JSR-133 — Java Memory Model §17.4.5 happens-before; volatile publication guarantees — https://jcp.org/aboutJava/communityprocess/mrel/jsr133/index.html
- Reference: JLS §17 — Threads and Locks (sealed interface, record, pattern switch — language features used in API) — https://docs.oracle.com/javase/specs/jls/se21/html/jls-17.html
- Reference: JVMS §5 — Classloader resolution (relevant to `ExceptionInjectionEffect.exceptionClassName` class lookup) — https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-5.html

---

<div align="center">

*Architecture, implementation, and documentation crafted with Love and Passion by*

**[Christian Schnapka](https://macstab.com)**  
Embedded Principal+ Engineer  
[Macstab GmbH](https://macstab.com) · Hamburg, Germany

*Building systems that operate correctly at the edges — including the ones you deliberately break.*

</div>
