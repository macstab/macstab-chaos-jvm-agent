<!--
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Engineered by  Christian Schnapka
                 Principal+ Embedded Systems Engineer
                 Macstab GmbH · Hamburg, Germany
                 https://macstab.com
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-->

# chaos-agent-api — Public API Contract Reference

> Stable external contract surface. This is the only module application and test code should depend on directly.
> 
> *Engineered by* **[Christian Schnapka](https://macstab.com)** — Principal+ Embedded Systems Engineer · [Macstab GmbH](https://macstab.com) · Hamburg, Germany

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

    // Register event listener (called on APPLIED, STARTED, STOPPED, RELEASED events)
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
    // Start the scenario (if startMode == MANUAL)
    void start();

    // Stop the scenario (permanent; cannot be restarted)
    void stop();

    // Release a gate-effect hold, unblocking all waiting threads
    void release();

    // Equivalent to stop()
    void close();
}
```

**`release()`**: Applicable only to gate-effect scenarios. Unblocks all threads currently waiting in `ManualGate.await()`. Does not stop the scenario — subsequent matching invocations will re-enter the gate.

**Idempotency**: `stop()` and `close()` are idempotent; calling them multiple times is safe.

---

# 5. ChaosScenario

```java
ChaosScenario scenario = ChaosScenario.builder()
    .id("unique-id")                              // required; unique within scope
    .description("optional label")
    .scope(ChaosScenario.ScenarioScope.SESSION)   // JVM or SESSION
    .precedence(0)                                // higher wins terminal action tie
    .selector(ChaosSelector.executor())
    .effect(ChaosEffect.delay(Duration.ofMillis(100)))
    .activationPolicy(ActivationPolicy.defaults())
    .build();
```

**`scope`**: `JVM` — affects all threads; `SESSION` — affects only threads bound to the session that activated it. JVM-scoped scenarios can only be activated via `ChaosControlPlane.activate()`; session-scoped scenarios can only be activated via `ChaosSession.activate()`.

**`precedence`**: Used to break ties when multiple scenarios match the same invocation and both have terminal actions (THROW, SUPPRESS, etc.). Higher precedence wins. Delay effects are always additive regardless of precedence.

---

# 6. ChaosSelector

Sealed hierarchy. Every selector variant must specify a set of `OperationType` values that it targets.

```java
// Thread pool task submission and worker execution
ChaosSelector.executor()
ChaosSelector.executor()
    .executorClassPattern(".*ThreadPool.*")
    .taskClassPattern(".*HttpRequest.*")
    .operations(OperationType.EXECUTOR_SUBMIT)

// Scheduled task submission and tick execution
ChaosSelector.scheduledExecutor()
ChaosSelector.scheduledExecutor()
    .executorClassPattern(".*")
    .periodicOnly(true)

// ForkJoin tasks
ChaosSelector.forkJoin()

// Thread lifecycle
ChaosSelector.thread()
ChaosSelector.thread()
    .threadNamePattern("worker-.*")
    .kind(ChaosSelector.ThreadKind.VIRTUAL)
    .daemon(false)

// BlockingQueue operations
ChaosSelector.queue()
ChaosSelector.queue().queueClassPattern(".*LinkedBlockingQueue.*")

// CompletableFuture completion
ChaosSelector.async()
ChaosSelector.async().operations(OperationType.ASYNC_COMPLETE, OperationType.ASYNC_CANCEL)

// Network sockets
ChaosSelector.network()
ChaosSelector.network()
    .remoteHostPattern("db.internal.*")
    .operations(OperationType.SOCKET_CONNECT)

// NIO Selector
ChaosSelector.nio()

// ClassLoader
ChaosSelector.classLoader()

// Arbitrary method entry/exit
ChaosSelector.method("com.example.MyService", "processOrder")
ChaosSelector.method().classPattern("com.example.*").methodNamePattern("process.*")

// Synchronization (AQS acquire = monitor enter proxy)
ChaosSelector.monitor()

// JVM-level operations (clock, GC, exit, JMX, JNDI, etc.)
ChaosSelector.jvmRuntime()
ChaosSelector.jvmRuntime().operations(OperationType.SYSTEM_CLOCK_MILLIS)

// Serialization
ChaosSelector.serialization()

// ZIP compression/decompression
ChaosSelector.zip()

// ThreadLocal operations
ChaosSelector.threadLocal()

// Shutdown hooks and System.exit
ChaosSelector.shutdown()

// Native library loading
ChaosSelector.nativeLib()

// JMX
ChaosSelector.jmx()

// JNDI
ChaosSelector.jndi()

// Background stressor lifecycle
ChaosSelector.stress(StressTarget.HEAP)
```

---

# 7. ChaosEffect

Sealed hierarchy. Divided into interceptor effects (applied inline on the calling thread) and stressor effects (run as background threads for the scenario lifetime).

## Interceptor Effects

```java
// Fixed delay
ChaosEffect.delay(Duration.ofMillis(200))

// Randomized delay: uniform in [min, max]
ChaosEffect.delay(Duration.ofMillis(50), Duration.ofMillis(500))

// Block until handle.release() is called (or timeout elapses)
ChaosEffect.gate(Duration.ofSeconds(30))  // maxBlock=null means block forever

// Throw an operation-appropriate exception
ChaosEffect.reject("connection refused")

// Silently discard; return null/false per operation contract
ChaosEffect.suppress()

// Complete a CompletableFuture exceptionally
ChaosEffect.exceptionalCompletion(ExceptionalCompletionEffect.FailureKind.TIMEOUT, "msg")

// Inject any exception at method entry
ChaosEffect.exceptionInjection("java.io.IOException", "chaos", withStackTrace=true)

// Corrupt a method's return value
ChaosEffect.returnValueCorruption(ReturnValueCorruptionEffect.Strategy.NULL)

// Skew System.currentTimeMillis() / nanoTime()
ChaosEffect.clockSkew(ClockSkewEffect.Mode.FIXED, offsetMillis=5000)
ChaosEffect.clockSkew(ClockSkewEffect.Mode.DRIFT, offsetMillis=100)  // accumulates per call
ChaosEffect.clockSkew(ClockSkewEffect.Mode.FREEZE, offsetMillis=0)   // freezes at start time

// Inject spurious NIO Selector.select() return (returns 0 immediately)
ChaosEffect.spuriousWakeup()
```

## Stressor Effects

```java
ChaosEffect.heapPressure(bytes)
ChaosEffect.keepAlive(threads)
ChaosEffect.metaspacePressure(classCount)
ChaosEffect.directBufferPressure(bytes)
ChaosEffect.gcPressure(allocationRatePerSecondBytes)
ChaosEffect.finalizerBacklog(objectCount)
ChaosEffect.deadlock()                    // NON-RECOVERABLE
ChaosEffect.threadLeak(count)             // NON-RECOVERABLE
ChaosEffect.threadLocalLeak(entryCount)
ChaosEffect.monitorContention(threads)
ChaosEffect.codeCachePressure(classCount)
ChaosEffect.safepointStorm(intervalMillis)
ChaosEffect.stringInternPressure(count)
ChaosEffect.referenceQueueFlood(count)
```

**Warning**: `deadlock()` and `threadLeak()` are non-recoverable within the JVM process. Use only in controlled test environments. `close()` on these scenarios cannot terminate deadlocked or permanently-parked threads.

---

# 8. ActivationPolicy

```java
ActivationPolicy policy = ActivationPolicy.builder()
    .startMode(ActivationPolicy.StartMode.AUTOMATIC)  // default: AUTOMATIC
    .probability(0.5)                                  // 0.0–1.0; default: 1.0
    .rateLimit(10, Duration.ofSeconds(1))              // N permits per window
    .activateAfterMatches(5)                           // skip first 5 matches
    .activeFor(Duration.ofSeconds(30))                 // auto-expire after 30s
    .maxApplications(100L)                             // stop after 100 applications
    .randomSeed(42L)                                   // for reproducible sampling
    .build();
```

All guards compose as AND: a scenario fires only if all configured guards pass.

**`startMode = MANUAL`**: The scenario is registered but not started. Call `handle.start()` explicitly to begin evaluation. Useful for pre-registering scenarios and starting them at a precise point in a test.

**`randomSeed`**: Determines the `SplittableRandom` base seed. With the same seed, probability sampling is deterministic across runs given the same sequence of `matchedCount` values. Different scenarios with the same seed still produce different samples because the seed is XOR-ed with `scenario.id().hashCode()`.

---

# 9. OperationType

The `OperationType` enum defines all intercepted JDK operations. Full list:

| Category | OperationType values |
|----------|---------------------|
| Executor | `EXECUTOR_SUBMIT`, `EXECUTOR_WORKER_RUN` |
| Scheduled | `SCHEDULE_ONCE`, `SCHEDULE_FIXED_RATE`, `SCHEDULE_FIXED_DELAY`, `SCHEDULE_TICK` |
| ForkJoin | `FORK_JOIN_TASK_RUN` |
| Thread | `THREAD_START`, `VIRTUAL_THREAD_START` |
| Queue | `QUEUE_PUT`, `QUEUE_TAKE`, `QUEUE_OFFER`, `QUEUE_POLL` |
| Async | `ASYNC_COMPLETE`, `ASYNC_COMPLETE_EXCEPTIONALLY`, `ASYNC_CANCEL` |
| Clock | `SYSTEM_CLOCK_MILLIS`, `SYSTEM_CLOCK_NANOS` |
| GC | `SYSTEM_GC_REQUEST` |
| Exit | `SYSTEM_EXIT_REQUEST` |
| Reflection | `REFLECTION_INVOKE` |
| Memory | `DIRECT_BUFFER_ALLOCATE` |
| Serialization | `OBJECT_DESERIALIZE`, `OBJECT_SERIALIZE` |
| Class | `CLASS_LOAD`, `CLASS_DEFINE`, `RESOURCE_LOAD` |
| Synchronization | `MONITOR_ENTER`, `THREAD_PARK` |
| NIO | `NIO_SELECTOR_SELECT`, `NIO_CHANNEL_READ`, `NIO_CHANNEL_WRITE`, `NIO_CHANNEL_CONNECT`, `NIO_CHANNEL_ACCEPT` |
| Network | `SOCKET_CONNECT`, `SOCKET_ACCEPT`, `SOCKET_READ`, `SOCKET_WRITE`, `SOCKET_CLOSE` |
| Infrastructure | `JNDI_LOOKUP`, `JMX_INVOKE`, `JMX_GET_ATTR`, `NATIVE_LIBRARY_LOAD` |
| Compression | `ZIP_INFLATE`, `ZIP_DEFLATE` |
| ThreadLocal | `THREAD_LOCAL_GET`, `THREAD_LOCAL_SET` |
| Shutdown | `SHUTDOWN_HOOK_REGISTER`, `SHUTDOWN_HOOK_REMOVE`, `EXECUTOR_SHUTDOWN` |
| Method | `METHOD_ENTER`, `METHOD_EXIT` |
| Stressor | `LIFECYCLE` (internal; targets stressor start/stop) |

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
    enum Type { STARTED, STOPPED, RELEASED, APPLIED }
}
```

**Contract**: Listeners are called synchronously on the application thread that triggered the event. Must not block, perform I/O, or throw checked exceptions. Listener exceptions are caught and logged; they do not abort the chaos operation.

---

# 12. Exception Types

| Exception | When thrown |
|-----------|-------------|
| `ChaosActivationException` | Scenario activation fails: duplicate ID, scope mismatch, invalid configuration |
| `ChaosUnsupportedFeatureException` | Scenario requires a JVM feature not available at runtime (e.g., virtual threads on JDK 17) |
| `ConfigLoadException` | Startup configuration cannot be read or parsed |

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

*Engineerure, implementation, and documentation crafted by*

**[Christian Schnapka](https://macstab.com)**  
Principal+ Embedded Systems Engineer  
[Macstab GmbH](https://macstab.com) · Hamburg, Germany

*Building systems that operate correctly at the edges — including the ones you deliberately break.*

</div>
