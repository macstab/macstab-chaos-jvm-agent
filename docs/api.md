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


### A complete example

Compiled by the documentation test, so it always matches the API: a session-scoped slow database for one test, a plan-wide cap, and a replay of a recorded run.

```java
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDecisionRecorder;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;

public final class SlowDatabaseExample {
  public static void run(final ChaosControlPlane chaos, final Runnable callTheService) {
    final ChaosScenario slowDb =
        ChaosScenario.builder("slow-db")
            .scope(ChaosScenario.ScenarioScope.SESSION)
            .selector(ChaosSelector.jdbc(OperationType.JDBC_STATEMENT_EXECUTE))
            .effect(ChaosEffect.delay(Duration.ofMillis(200)))
            .activationPolicy(ActivationPolicy.builder().probability(0.3).maxApplications(10L).build())
            .build();

    final ChaosDecisionRecorder recorder = new ChaosDecisionRecorder();
    chaos.addEventListener(recorder);
    final String recordedSession;
    try (ChaosSession session = chaos.openSession("slow-db test")) {
      recordedSession = session.id();
      session.activate(slowDb);
      callTheService.run();
    }
    chaos.removeEventListener(recorder);

    // The same decisions again, without drawing (a session scenario replays its session's log;
    // recorder.replay(scenario) replays the JVM-scoped log):
    try (ChaosSession replay = chaos.openSession("slow-db replay")) {
      replay.activate(recorder.replay(slowDb, recordedSession));
      callTheService.run();
    }
  }
}
```

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
- Record constructors: when a record gains a component, its previous canonical signature stays as a delegating constructor until the next major release. Build records through their factories and builders (`ActivationPolicy.count(n)`, `ActivationPolicy.builder()`, `ChaosScenario.builder(id)`) rather than positionally

---

# 2. ChaosControlPlane

Entry point to the chaos agent, obtained via `ChaosPlatform.current()` (or `ChaosPlatform.installed()`, an `Optional` that is empty while no agent is installed) or `ChaosTestKit.install()`. `ChaosPlatform` is the supported entry point of `chaos-agent-bootstrap`.

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

    // Register an event listener; invoked synchronously (wrap it in ChaosEventListener.async
    // to receive events on an agent-owned thread)
    void addEventListener(ChaosEventListener listener);

    // Remove a listener as it was registered; true if it was registered
    boolean removeEventListener(ChaosEventListener listener);

    // Bind the calling thread to the open session with this id (null or unknown: no session)
    ChaosSession.ScopeBinding bindSession(@Nullable String sessionId);

    // Stop and unregister all scenarios (does not uninstall instrumentation);
    // afterwards activate/openSession/addEventListener throw IllegalStateException
    void close();
}
```

**`activate(ChaosScenario)`**: Requires `scenario.scope() == JVM`. Weaves the hook groups serving the selector's operations if they are not woven yet (the first activation of a group pays its one-time retransformation cost; a weaving failure throws `ChaosInstrumentationException` and is recorded as `INSTRUMENTATION_FAILURE`), registers the scenario with `ScenarioRegistry`, starts it if `startMode == AUTOMATIC`, returns a handle. Throws `ChaosActivationException` when a scenario with the same id is still `REGISTERED` or `ACTIVE`, or on incompatible configuration. A previous scenario with the same id that is `STOPPED` or `INACTIVE` is replaced, so re-activating an id after `stop()` or `close()` works.

**`activate(ChaosPlan)`**: Rejects any session-scoped scenario in the plan with `ChaosActivationException`. Every scenario is validated, and every hook the plan needs is woven (the union of its hook groups and all its method selectors, in one call each), before any scenario is registered: a weaving failure starts none of them and is recorded under the plan's handle id (`plan:<name>`). Activation is atomic: if any scenario still fails, the ones already registered are removed again before the exception propagates.

**`openSession(String)`**: Weaves the hook groups needed to propagate the session (executors, schedulers, thread start, fork-join), if not woven yet (a weaving failure throws `ChaosInstrumentationException` and is recorded under `session:<displayName>`), then creates a `DefaultChaosSession` with a UUID and the given display name. The opening thread is bound to the session until `session.close()`; call `session.bind()` on other threads that should participate.

**After `close()`**: `activate`, `openSession` and `addEventListener` throw `IllegalStateException("chaos control plane is closed")`.

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

**Thread propagation**: `bind()` pushes the session ID onto a per-thread stack (plain `ThreadLocal`) on the calling thread. The session ID is automatically propagated to work handed off while the thread is bound: tasks submitted to executors and schedulers once the session has a scenario (the worker run and every scheduled tick of a periodic task execute bound to the session; a task submitted before the session's first scenario is activated runs outside it), `ForkJoinPool` submissions and forks (so `CompletableFuture.*Async` and parallel streams), threads started with `Thread.start()` and virtual threads (including `Executors.newVirtualThreadPerTaskExecutor()`). The receiving task or thread is bound for the duration of its run. A `Thread` subclass that overrides `run()` without calling `super.run()` does not receive the session. A session-scoped clock skew applies only on bound threads.

For tasks submitted to executors outside a `bind()` block, use `session.wrap(task)` to explicitly carry the session context.

**`close()` contract**: Stops and unregisters all session-scoped scenarios and removes the opening thread's binding. Subsequent calls to `bind()` on a closed session are undefined behavior.

---

# 4. ChaosActivationHandle

```java
public interface ChaosActivationHandle extends AutoCloseable {
    // Registry key: "jvm::<id>", "session:<sessionId>::<id>", or "plan:<name>" for plans
    String id();

    // Start the scenario (if startMode == MANUAL; no-op for AUTOMATIC)
    void start();

    // Stop the scenario (permanent; cannot be restarted)
    void stop();

    // Release a gate-effect hold, unblocking all waiting threads
    void release();

    // Current lifecycle state (snapshot; may change concurrently)
    ChaosDiagnostics.ScenarioState state();

    // Interface default is stop(); the handles returned by the runtime
    // override it to stop AND unregister the scenario
    @Override default void close() { stop(); }
}
```

**`close()`**: On every handle returned by `activate()` (scenario, session and plan handles), `close()` stops the scenario **and** removes it from the registry, so it disappears from diagnostics and its id can be activated again. Use try-with-resources for automatic cleanup. `stop()` alone leaves a `STOPPED` entry registered (which is still replaced by a later activation of the same id).

**`release()`**: Applicable only to gate-effect scenarios. Unblocks all threads currently waiting in `ManualGate.await()` and re-arms the gate. Does not stop the scenario — subsequent matching invocations will re-enter the gate. On an `INACTIVE` scenario (`maxApplications` spent, or expired) it opens the gate once without re-arming it: a spent `maxApplications` holds its last admitted caller until `release()` or `stop()`, while expiry itself already releases waiters. A no-op for non-gate scenarios and for scenarios that are neither `ACTIVE` nor `INACTIVE`.

**`state()`**: Returns one of `REGISTERED`, `ACTIVE`, `INACTIVE`, `STOPPED`. Useful for assertions in tests (`assertThat(handle.state()).isEqualTo(ACTIVE)`).

**Idempotency**: `stop()` and `close()` are idempotent; calling them multiple times is safe.

**Plan activations**: `controlPlane.activate(ChaosPlan)` returns a composite handle whose `stop()`/`close()` are broadcast to every scenario in the plan and whose `id()` is `plan:<plan name>`.

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

## Presets

`ChaosPresets` holds ready-made scenarios for the faults production systems meet, each the JVM counterpart of a native rule. A preset returns a prefilled `ChaosScenario.Builder` (id, description, selector, effect, tag `preset=<name>`), so scope, activation policy and id stay yours.

| Preset | Fault | Native rule |
|--------|-------|-------------|
| `ChaosPresets.diskFull(dir)` | writes fail with `ENOSPC` | `/dir:write:ENOSPC` |
| `ChaosPresets.failingDisk(dir)` | reads, writes, fsyncs fail with `EIO` | `/dir:*:EIO` |
| `ChaosPresets.fsyncFailure(dir)` | fsyncs fail with `EIO` | `/dir:fsync:EIO` |
| `ChaosPresets.slowDisk(dir, latency)` | file I/O is slower | `/dir:*:LATENCY` |
| `ChaosPresets.tooManyOpenFiles(dir)` | opens fail with `EMFILE` | `/dir:open:EMFILE` |
| `ChaosPresets.connectionRefused(cidr, port)` | connects are refused | `tcp4://…:connect:ECONNREFUSED` |
| `ChaosPresets.connectionResets(cidr, port)` | reads fail with `ECONNRESET` | `tcp4://…:recv:ECONNRESET` |
| `ChaosPresets.slowNetwork(cidr, port, latency)` | socket reads and writes are slower | `tcp4://…:*:LATENCY` |
| `ChaosPresets.trickledResponses(cidr, port, maxBytes)` | reads return few bytes | `tcp4://…:recv:TRICKLE` |
| `ChaosPresets.packetLoss(cidr, port)` | datagrams are dropped | `udp4://…:sendto:…` |
| `ChaosPresets.failover(cidr, port, host, targetPort)` | connects go elsewhere | endpoint redirect |
| `ChaosPresets.dnsOutage(names)` | lookups fail | `dns://…:GAI:EAI_NONAME` |
| `ChaosPresets.clockJump(offset)` | every wall-clock read is off | `clock_gettime/realtime:OFFSET` |
| `ChaosPresets.threadExhaustion()` | platform thread starts fail | `pthread_create:ERRNO:EAGAIN` |

```java
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosPresets;
import java.time.Duration;

public final class PresetExample {
  public static void run(final ChaosControlPlane chaos) {
    // One write in ten under /var/lib/app hits a full disk.
    chaos.activate(
        ChaosPresets.diskFull("/var/lib/app")
            .activationPolicy(ActivationPolicy.builder().probability(0.1).build())
            .build());
    // The database replica answers 200 ms late.
    chaos.activate(
        ChaosPresets.slowNetwork("10.0.0.7", 5432, Duration.ofMillis(200)).build());
  }
}
```

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

// Skew clock reads (Instant.now(), LocalDateTime/ZonedDateTime.now(), new Date();
// System.currentTimeMillis()/nanoTime() only through the manual hook — see §9.1)
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
| `maxApplications`         | `Long`      | `null`      | Hard cap on total applications; `null` = unlimited. Only applications that take effect count — a scenario that loses on precedence does not consume budget                                                                    |
| `activeFor`               | `Duration`  | `null`      | Auto-expire this long after first start (state `INACTIVE`, reason `"expired"`, with the `INACTIVE` event), also without traffic; for stress scenarios this also ends the stressor; `null` = no expiry                    |
| `rateLimit`               | `RateLimit` | `null`      | Fixed-window permit cap, window anchored at scenario start (up to 2× permits across a window boundary); `null` = unlimited                                                                                                    |
| `randomSeed`              | `Long`      | `0L`        | Seed for `SplittableRandom`; XOR-ed with `scenario.id().hashCode()` for per-scenario uniqueness                                                                                                                               |
| `allowDestructiveEffects` | `boolean`   | `false`     | Must be `true` to activate `DeadlockEffect` or `ThreadLeakEffect`; see below                                                                                                                                                  |
| `activeFrom`              | `Instant`   | `null`      | Wall-clock instant before which the scenario does not fire; such calls are not counted as matches. Read from the real system clock, so an injected clock skew cannot move it. `null` = no lower bound                      |
| `activeUntil`             | `Instant`   | `null`      | Wall-clock instant from which on the scenario no longer fires (state `INACTIVE`, reason `"expired"`, also without traffic); ends a stressor. Read from the real clock. Must be after `activeFrom`. `null` = no upper bound |

**Factories.** `ActivationPolicy.always()`, `manual()` and `withDestructiveEffects()` cover the common cases; `count(n)` fires on the first `n` matches (`maxApplications = n`, the native library's `@first=N`) and `window(from, until)` fires between two instants (`@from`/`@until`). `ActivationPolicy.builder()` sets any combination and, unlike the canonical constructor, does not break when the record gains a field.

### Destructive Effects Safeguard

`DeadlockEffect` and `ThreadLeakEffect` create JVM state that cannot be recovered within a running process — deadlocked threads cannot be interrupted; leaked non-daemon threads prevent JVM exit until the process is killed.

`CompatibilityValidator` rejects any scenario using these effects unless `allowDestructiveEffects == true`. The rejection is a `ChaosValidationException` thrown at registration time, not at effect application time. This is an opt-in correctness guard, not a security boundary — the calling code is trusted.

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

**Stress scenarios** accept only `startMode`, `activeFor`, `activeUntil` and `allowDestructiveEffects`: `maxApplications`, `rateLimit`, `activateAfterMatches > 0`, `probability < 1.0` and `activeFrom` are rejected with `ChaosValidationException`, because a stressor runs from start until `stop()`/`close()` or until `activeFor` or `activeUntil` ends.

**Clock-skew scenarios** apply to every clock read while active. They honour `activeFor`, `activeFrom` and `activeUntil`; `maxApplications`, `rateLimit`, `activateAfterMatches > 0` and `probability < 1.0` are rejected with `ChaosValidationException` (1.x ignored them silently).

**`randomSeed`**: With the same seed, probability sampling is deterministic across runs given the same sequence of `matchedCount` values, and so are the sampled delay, the torn-write length, the corrupted bit and the DNS shuffle order of each match. Different scenarios with the same seed still produce different samples because the seed is XOR-ed with `scenario.id().hashCode()`.

---

# 9. OperationType

The `OperationType` enum defines all intercepted JDK operations. Full list:

| Category                        | OperationType values                                                                                                                   |
|---------------------------------|----------------------------------------------------------------------------------------------------------------------------------------|
| Executor                        | `EXECUTOR_SUBMIT`, `EXECUTOR_WORKER_RUN`, `EXECUTOR_SHUTDOWN`, `EXECUTOR_AWAIT_TERMINATION`                                            |
| Scheduled                       | `SCHEDULE_SUBMIT`, `SCHEDULE_TICK`                                                                                                     |
| ForkJoin                        | `FORK_JOIN_TASK_RUN`                                                                                                                   |
| Thread                          | `THREAD_START`, `VIRTUAL_THREAD_START`, `THREAD_SLEEP`                                                                                 |
| Queue                           | `QUEUE_PUT`, `QUEUE_TAKE`, `QUEUE_OFFER`, `QUEUE_POLL`                                                                                 |
| Async                           | `ASYNC_COMPLETE`, `ASYNC_COMPLETE_EXCEPTIONALLY`, `ASYNC_CANCEL`                                                                       |
| Clock (auto-wired)              | `INSTANT_NOW`, `CLOCK_READ`, `LOCAL_DATE_TIME_NOW`, `ZONED_DATE_TIME_NOW`, `DATE_NEW`                                                              |
| Clock (manual hook — see §11)   | `SYSTEM_CLOCK_MILLIS`, `SYSTEM_CLOCK_NANOS`                                                                                            |
| GC                              | `SYSTEM_GC_REQUEST`                                                                                                                    |
| Exit                            | `SYSTEM_EXIT_REQUEST`                                                                                                                  |
| Reflection                      | `REFLECTION_INVOKE`                                                                                                                    |
| Memory                          | `DIRECT_BUFFER_ALLOCATE`                                                                                                               |
| Serialization                   | `OBJECT_DESERIALIZE`, `OBJECT_SERIALIZE`                                                                                               |
| Class                           | `CLASS_LOAD`, `CLASS_DEFINE`, `RESOURCE_LOAD`                                                                                          |
| Synchronization                 | `MONITOR_ENTER`, `THREAD_PARK`                                                                                                         |
| NIO                             | `NIO_SELECTOR_SELECT`, `NIO_CHANNEL_READ`, `NIO_CHANNEL_WRITE`, `NIO_CHANNEL_CONNECT`, `NIO_CHANNEL_ACCEPT`                            |
| Network                         | `SOCKET_CONNECT`, `SOCKET_ACCEPT`, `SOCKET_READ`, `SOCKET_WRITE`, `SOCKET_CLOSE`, `SOCKET_BIND`, `SOCKET_SHUTDOWN`, `DATAGRAM_SEND`, `DATAGRAM_RECEIVE`                                                     |
| Infrastructure                  | `JNDI_LOOKUP`, `JMX_INVOKE`, `JMX_GET_ATTR`, `NATIVE_LIBRARY_LOAD`                                                                     |
| Compression                     | `ZIP_INFLATE`, `ZIP_DEFLATE`                                                                                                           |
| ThreadLocal                     | `THREAD_LOCAL_GET`, `THREAD_LOCAL_SET`                                                                                                 |
| HTTP client                     | `HTTP_CLIENT_SEND`, `HTTP_CLIENT_SEND_ASYNC`                                                                                           |
| JDBC                            | `JDBC_CONNECTION_ACQUIRE`, `JDBC_STATEMENT_EXECUTE`, `JDBC_PREPARED_STATEMENT`, `JDBC_TRANSACTION_COMMIT`, `JDBC_TRANSACTION_ROLLBACK` |
| DNS / TLS                       | `DNS_RESOLVE`, `SSL_HANDSHAKE`                                                                                                         |
| File I/O                        | `FILE_IO_READ`, `FILE_IO_WRITE`, `FILE_IO_SYNC`, `FILE_IO_TRUNCATE`, `FILE_IO_DELETE`, `FILE_IO_MOVE`, `FILE_IO_MOVE_TARGET`, `FILE_IO_OPEN`, `FILE_IO_MAP`, `FILE_IO_CLOSE`                                                                                                      |
| Processes                       | `PROCESS_START` |
| Messaging (Kafka)               | `MESSAGE_SEND`, `MESSAGE_RECEIVE`                                                                                                      |
| RPC (gRPC)                      | `RPC_CALL`                                                                                                                             |
| Redis (Jedis, Lettuce)          | `REDIS_COMMAND`                                                                                                                        |
| R2DBC                           | `R2DBC_STATEMENT_EXECUTE`                                                                                                              |
| AWS SDK v2                      | `AWS_SDK_CALL`                                                                                                                         |
| Netty                           | `NETTY_CHANNEL_WRITE`, `NETTY_CHANNEL_READ`                                                                                            |
| Shutdown                        | `SHUTDOWN_HOOK_REGISTER`                                                                                                               |
| Method (woven per scenario)     | `METHOD_ENTER`, `METHOD_EXIT`                                                                                                          |
| Stressor                        | `LIFECYCLE` (internal; targets stressor start/stop)                                                                                    |

## 9.1 Manual hook operation types

Most operation types in §9 are **auto-wired**: the agent weaves ByteBuddy advice into a fixed JDK call site when a scenario first needs it (the call site's hook group is retransformed on that scenario's activation; `instrumentation=eager` weaves everything at start), the advice fires automatically whenever application code reaches that site, and zero application changes are needed.

Two operation types are **manual-hook only** — fully functional, fully tested, but the agent cannot trigger them automatically and the application must route the event through the agent's public API. (`METHOD_ENTER` / `METHOD_EXIT` are woven automatically for the methods a `MethodSelector` names; see "Method entry / exit" below.)

| OperationType         | Why it is not auto-wired                                                                                                                                                                                                                            | Public hook                                                      |
|-----------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------|
| `SYSTEM_CLOCK_MILLIS` | `System.currentTimeMillis()` is `native @IntrinsicCandidate`; HotSpot compiles the call into a direct call to the VM clock (`clock_gettime` through the vDSO on Linux), with no bytecode to weave. Hard JVM limitation for `-javaagent:` agents. | `chaosRuntime.adjustClockMillis(real)`                           |
| `SYSTEM_CLOCK_NANOS`  | Same as above for `System.nanoTime()`.                                                                                                                                                                                                              | `chaosRuntime.adjustClockNanos(real)`                            |

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

### Method entry / exit

With the agent attached, activating a `MethodSelector` scenario weaves entry and exit hooks into the methods it names, and removing the scenario restores them (see `docs/configuration-reference.md`, `method` selector). `signaturePattern` selects overloads by JVM descriptor. JDK classes (those of the bootstrap and platform class loaders) are never woven, whatever the patterns name, and a selector whose class and method patterns both match everything (`ANY`, a `GLOB` of `*` only, the `REGEX` `.*`, `^.*$`, `.*+`) is refused with `IllegalArgumentException`.

Without the agent — a `ChaosRuntime` in a plain unit test — wire the events from interception the application already runs (Spring AOP `@Around`, AspectJ, Micronaut / Quarkus interceptors):

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

    // Lookup a specific scenario by ID; an ID in several scopes answers the JVM scope's,
    // else the session with the smallest id (a session's own: its activation handle)
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
        INSTRUMENTATION_FAILURE,   // the hook group the selector needs could not be woven
        ACTIVATION_CONFLICT,
        INTERNAL_ERROR
    }
}
```

**Activation failure categories**: every failed activation is recorded once, as an `ActivationFailure` and a `FAILED` event, and the original exception is rethrown. So is a refusal by `ChaosRuntime.validate(scenario)` or a weaving failure of `ChaosRuntime.requireHooks(scenario)`, which register nothing.

| Category | Recorded when | Thrown |
|----------|---------------|--------|
| `INVALID_CONFIGURATION` | the scenario breaks a rule of the validator, or a stressor fails to start for another reason | `ChaosValidationException`, `ChaosActivationException`, or the stressor's `RuntimeException` |
| `UNSUPPORTED_RUNTIME` | the runtime cannot run the scenario: a missing JVM feature, direct-buffer pressure above `-XX:MaxDirectMemorySize` (refused before anything is allocated; the default limit `0` is the maximum heap), a stressor this JVM's flags leave without load (`safepointStorm` without retransforms under `-XX:+DisableExplicitGC`, `finalizerBacklog` under `--finalization=disabled`), or a stressor that could not get its resources at start (an `OutOfMemoryError`, which is also how the JVM reports a thread it cannot start) | `ChaosUnsupportedFeatureException` |
| `INSTRUMENTATION_FAILURE` | the hooks the selector needs could not be woven | `ChaosInstrumentationException` |
| `ACTIVATION_CONFLICT` | a running scenario already has the id | `ChaosActivationConflictException` |
| `INTERNAL_ERROR` | any other `Error` while the scenario registers or starts (a `LinkageError`, for instance) | the `Error` itself |

A failed activation leaves no scenario registered. A failed `start()` of a `MANUAL` scenario is recorded the same way, closes what the stressor had started, and leaves the scenario `REGISTERED`: the handle stays yours to retry or close.

**`runtimeDetails`**: key/value runtime metadata. Includes `wovenHookGroups` — the comma-separated, sorted names of the hook groups woven so far (empty when none; hooks are woven on demand) — and two health keys, both empty when all is well: `unmatchedHooks`, the hooks woven into a type that matched none of its methods (the JDK changed the method and the hook is inactive), and `hookFailures`, the types whose weaving failed (none of their hooks applies).

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
    Instant timestamp,
    ChaosEvent.Type type,
    String scenarioId,
    String message,
    Map<String, String> attributes
) {
    enum Type {
        REGISTERED,  // scenario registered, before it starts
        STARTED,     // scenario transitioned to ACTIVE
        APPLIED,     // a contribution took effect on a matched operation
        SKIPPED,     // the selector matched, the policy kept the effect (only while a listener is registered)
        FAILED,      // an activation failed
        RELEASED,    // gate-effect release() unblocked waiters
        STOPPED,     // scenario transitioned to STOPPED (once per transition)
        INACTIVE     // scenario ended on its own (expired, max applications reached); attribute reason
    }
}
```

**Published events**: every type is published; when, and with which attributes, is in [observability.md §2](observability.md#2-events). `APPLIED` is published only for contributions that actually take effect on a call — every matching delay, the winning gate and the winning terminal action by precedence. A scenario that loses on precedence publishes nothing and does not consume its `maxApplications` budget. `APPLIED` attributes carry `operation`, `scope`, `effectType` and, for session scenarios, `sessionId`.

**Contract**: Listeners are called synchronously on the thread that triggered the event, unless wrapped in `ChaosEventListener.async(listener)` (bounded queue of 10 000; overflow is dropped and counted as `droppedEvents`). Must not block, perform I/O, or throw checked exceptions. Listener exceptions are caught and logged (a listener's first at WARNING, later ones at FINE); they do not abort the chaos operation.

**Observability use**: Subscribe a listener to count `APPLIED` events per scenario ID — this is the ground truth for "how many times did my chaos actually fire during this test?". Skipped matches arrive as `SKIPPED` with the policy check as `reason`; `matchedCount` and `appliedCount` in `diagnostics().snapshot()` give the totals.

---

# 12. Exception Types

| Exception | Module | When thrown |
|-----------|--------|-------------|
| `ChaosActivationException` | `chaos-agent-api` | Scenario activation fails: scope mismatch, invalid configuration |
| `ChaosActivationConflictException` | `chaos-agent-api` | A `ChaosActivationException`: a scenario with the same id is already active (a retry may succeed once it stops) |
| `ChaosInstrumentationException` | `chaos-agent-api` | A `ChaosActivationException`: the hooks the scenario needs cannot be woven into the running JVM; the cause is the weaving failure, and the same scenario fails the same way again |
| `ChaosValidationException` | `chaos-agent-api` | A scenario's effect, selector and policy do not fit together (`CompatibilityValidator`) |
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
