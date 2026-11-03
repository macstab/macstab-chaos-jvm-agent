<!--
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Engineered by  Christian Schnapka
                 Embedded Principal+ Engineer
                 Macstab GmbH · Hamburg, Germany
                 https://macstab.com
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-->

# chaos-agent-core — Execution Engine Reference

> Internal reference for the scenario registry, matching pipeline, activation policies, session scoping, effect execution, and stressor lifecycle.
> 
> *Engineered by* **[Christian Schnapka](https://macstab.com)** — Embedded Principal+ Engineer · [Macstab GmbH](https://macstab.com) · Hamburg, Germany

---

# 1. Overview

## Purpose

`chaos-agent-core` is the policy and execution engine. It owns everything between "the instrumented JDK method fired" and "the chaos effect was applied": scenario registration and lifecycle management, per-invocation matching, activation policy evaluation, effect dispatch, session scoping, diagnostics, and background stressor management.

## Scope

In scope:
- `ChaosRuntime` — thin facade that composes the control-plane and dispatch halves. Implements `ChaosControlPlane` by delegation. Exists as a single entry point so downstream modules (`chaos-agent-bootstrap`, `chaos-agent-instrumentation-jdk`, testkit, benchmarks) bind to one type.
- `ChaosControlPlaneImpl` (package-private) — actual control-plane implementation. Owns scenario registration, session lifecycle, diagnostics, event listeners, instrumentation handle, shutdown-hook tracking.
- `ChaosDispatcher` (package-private) — hot-path half. Owns the `before*` / `after*` / `adjust*` / `decorate*` interception methods invoked from `BootstrapDispatcher`.
- `ScenarioController` — per-scenario runtime guard with 8-check evaluation pipeline
- `ScenarioRegistry` — thread-safe store of controllers plus an immutable `OperationType` → controllers index used by `match()`; implements `ChaosDiagnostics`
- `SelectorMatcher` — stateless invocation-context matcher
- `InvocationContext` — value object capturing per-call data
- `RuntimeDecision` — merged decision: delay + gate + terminal action
- `ScopeContext` — per-thread session ID propagation
- `DefaultChaosSession` — session lifecycle, binding, task wrapping
- `CompatibilityValidator` — structural validation at activation time
- `ObservabilityBus` — event publication + metrics increment
- `ManualGate` — count-down + condition-variable gate for blocking effects
- `ClockSkewState` — mutable state for drift/freeze clock modes
- `ReturnValueCorruptor` — return-value corruption strategies
- `FailureFactory` — operation-specific exception construction
- `StressorFactory` + all `ManagedStressor` implementations

Out of scope:
- JVM bytecode interception (that is `chaos-agent-instrumentation-jdk`)
- Startup configuration resolution (`chaos-agent-startup-config`)
- Agent packaging and MBean registration (`chaos-agent-bootstrap`)

## Non-Goals

- Transactional multi-scenario activation
- Scenario persistence across JVM restarts
- Removal of stopped controllers from registry (they stay until the handle is explicitly closed or the session is closed)

---

# 2. Engineerural Context

**Dependencies** (inbound):
- `chaos-agent-instrumentation-jdk` calls `ChaosRuntime`'s `before*`/`after*`/`adjust*`/`decorate*` methods via the `ChaosBridge` → `BootstrapDispatcher` bridge
- `chaos-agent-bootstrap` creates `ChaosRuntime`, calls `setInstrumentation()`

**Dependencies** (outbound):
- `chaos-agent-api` only

**Trust boundary**: all inputs are trusted JVM-internal calls. `ChaosRuntime` applies no security checks on callers.

**Stable vs internal**: `ChaosRuntime` has `public` visibility because bootstrap and testkit instantiate it, but it should be treated as internal runtime machinery. No API stability guarantee.

---

# 3. Key Concepts and Terminology

| Term                     | Definition                                                                                                                                                                                                     |
|--------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **InvocationContext**    | Immutable record built at each instrumentation point: operation type, class names, target name, periodic flag, daemon/virtual thread flags, session ID.                                                        |
| **ScenarioContribution** | The value returned by `ScenarioController.evaluate()` when all 8 checks pass: carries the controller, effect, sampled delay, and gate timeout.                                                                 |
| **RuntimeDecision**      | Merged output of all matching contributions: accumulated delay, last gate action, highest-precedence terminal action.                                                                                          |
| **TerminalAction**       | The final action to execute: THROW, RETURN (with value), SUPPRESS, COMPLETE_EXCEPTIONALLY, or CORRUPT_RETURN.                                                                                                  |
| **TerminalKind**         | Enum discriminating TerminalAction variants.                                                                                                                                                                   |
| **GateAction**           | A pair of (ManualGate, maxBlockDuration) triggering a conditional block on the calling thread.                                                                                                                 |
| **SessionId**            | A string assigned by `DefaultChaosSession` and stored in `ScopeContext` as a `ThreadLocal`. Null means "not bound to any session" (JVM-scope applies).                                                         |
| **ScopeContext**         | Single mutable source of truth for the current thread's session ID. Used by `ChaosRuntime` dispatch methods and `DefaultChaosSession.bind()`.                                                                  |
| **baseSeed**             | Per-controller seed for `SplittableRandom`, derived from `ActivationPolicy.randomSeed` or 0. XOR-ed with `matchedCount` and `scenarioId.hashCode()` per evaluation to produce varied-but-reproducible samples. |

---

# 4. End-to-End Behavior

## Scenario Registration

```
controlPlane.activate(scenario) or session.activate(scenario)
  ↓
ChaosRuntime.registerScenario(scenario, scopeKey, sessionId)
  ↓
CompatibilityValidator.validate(scenario, featureSet)
  — throws ChaosUnsupportedFeatureException on JVM feature mismatch
  — throws ChaosValidationException on selector/effect/operation incompatibility
  ↓
hookDemand.require(SelectorOperations.of(scenario.selector()))
  — the instrumentation layer weaves the hook groups serving these operations, once each
    (no-op for stress selectors and for groups already woven)
  — a weaving failure fails the activation (failure category INSTRUMENTATION_FAILURE)
  ↓
new ScenarioController(scenario, scopeKey, sessionId, clock, observabilityBus, instrumentationSupplier)
  — state = REGISTERED; started = false
  ↓
ScenarioRegistry.register(controller)
  — atomic ConcurrentHashMap.compute(key, ...)
  — rebuilds and swaps the immutable operation index (pre-sorted per operation)
  — an existing STOPPED or INACTIVE controller under the key is replaced (and destroyed)
  — throws ChaosActivationException("scenario key already active") if the existing
    controller is REGISTERED or ACTIVE
  ↓
new DefaultChaosActivationHandle(controller, registry)
  if startMode == AUTOMATIC:
    handle.start()
      ↓
      ScenarioController.start()
        gate.reset()
        startedAt = clock.instant()
        started.set(true)
        state = ACTIVE
        stressor = StressorFactory.createIfNeeded(effect)  // starts background thread if needed;
                                                           // a creation failure propagates out of activation
        if ClockSkewEffect: clockSkewState = new ClockSkewState(skewEffect, ...)
        observabilityBus.publish(STARTED, ...)
  ↓
return DefaultChaosActivationHandle

// Plan activations (activate(ChaosPlan)) follow the same path for each scenario in the plan,
// then wrap all resulting handles in CompositeActivationHandle, which delegates start/stop/release/close
// to each member handle. The composite ID is "plan:<plan name>". If any scenario fails, the ones
// already registered are destroyed and the exception propagates (the plan is atomic).
```

## Per-Invocation Evaluation

`ScenarioController.evaluate(InvocationContext context)` runs 8 checks:

```
1. if (!started.get()) return null
   — AtomicBoolean read; lock-free
   — Fast exit for REGISTERED, STOPPED controllers

2. if (sessionId != null && !sessionId.equals(context.sessionId())) return null
   — String equality; no lock
   — Session-scoped controllers are invisible to unbound threads

3. if (!SelectorMatcher.matches(scenario.selector(), context)) return null
   — Exhaustive sealed switch; stateless; no allocation
   — Checks: operationType in selector.operations(), pattern fields

4. long matched = matchedCount.incrementAndGet()
   if (!passesActivationWindow()) { state=INACTIVE; reason="expired"; return null }
   — matchedCount increments even if window check fails
   — This ensures warm-up counter advances even when the window is expired

5. if (matched <= activateAfterMatches) return null
   — Warm-up: skip first N matches

6. if (!passesProbability(matched)) return null
   — new SplittableRandom(baseSeed ^ matched ^ id.hashCode()).nextDouble() < probability
   — checked before the rate limit so permits are only consumed by calls that will apply

7. if (!passesRateLimit()) return null
   — synchronized(this): FIXED window anchored at start(), advanced in whole windows, O(1)

8. if (!reserveApplication()) return null
   — maxApplications budget: a reservation, not yet a count
   — if appliedCount >= maxApplications: state=INACTIVE; reason="max applications reached"

return new ScenarioContribution(this, scenario, effect, sampleDelayMillis(matched), gateTimeout(), context)

// The dispatcher then merges all contributions (delay, then gate, then terminal action) and calls
//   commitApplication(context)  — for every delay, the winning gate, the winning terminal action:
//                                 appliedCount++, publish(APPLIED), metric "chaos.effect.applied"
//   releaseApplication()        — for contributions that lost on precedence: budget returned
```

**Note on matchedCount semantics**: `matchedCount` is incremented at step 4, before warm-up, probability and rate-limit checks. This means it reflects the total number of times the selector matched, not the number of times the effect was applied. This is intentional: diagnostics consumers can distinguish "the selector is working" from "the effect is being suppressed by policy."

## RuntimeDecision Merge

`ChaosRuntime.evaluate(InvocationContext)` aggregates contributions:

```java
long delayMillis = 0L;                // ADDITIVE across all contributions
GateAction gateAction = null;         // LAST gate wins (only one gate blocks at a time)
TerminalAction terminalAction = null; // HIGHEST PRECEDENCE wins; ties broken by sorted order
int terminalPrecedence = Integer.MIN_VALUE;

for (ScenarioContribution contribution : contributions) {
    delayMillis += contribution.delayMillis();
    if (effect instanceof GateEffect) {
        gateAction = new GateAction(controller.gate(), contribution.gateTimeout());
    }
    TerminalAction candidate = terminalActionFor(operationType, effect, scenario);
    if (candidate != null && scenario.precedence() >= terminalPrecedence) {
        terminalAction = candidate;
        terminalPrecedence = scenario.precedence();
    }
}
```

Delay composition: multiple delay scenarios stack. Three scenarios each contributing 50 ms delay = 150 ms total. This is intentional load amplification — composing delays is a feature, not a bug.

Gate composition: only the last gate in the sorted contribution list applies. Two gate scenarios = the higher-precedence gate blocks; the lower-precedence gate is ignored. This is a limitation of the current implementation (not a deliberate multi-gate feature).

Terminal action selection: the highest-precedence scenario wins. If two scenarios share the same precedence, the lexicographically first `id` wins (the `ScenarioRegistry` operation index keeps each operation's controllers pre-sorted by descending precedence, then id; `match()` preserves that order and does not sort per call).

## Decision Execution

```
applyPreDecision(decision):
  applyGate(gateAction):
    gate.await(maxBlock)  — blocks until release() or timeout; throws if interrupted
  if terminalAction != null:
    THROW     → throw terminalAction.throwable()
    RETURN(false) → throw RejectedExecutionException("operation suppressed")
    RETURN(other) → return normally (callers handle non-false return values differently)
    SUPPRESS  → return (no-op)
  sleep(delayMillis)  — Thread.sleep; interrupt restores flag + throws IllegalStateException
```

Note: `applyPreDecision` is not used for operations that return a value; those operations handle the terminal action inline. For example, `beforeBooleanQueueOperation` checks for RETURN and directly returns the boolean rather than throwing.

---

# 5. Architecture Diagrams

## Class Diagram — Core Component Relationships

```plantuml
@startuml
class ChaosRuntime {
  +activate(scenario) : ChaosActivationHandle
  +openSession(name) : ChaosSession
  +diagnostics() : ChaosDiagnostics
  +before*() / after*() / adjust*() / decorate*()
  -registry : ScenarioRegistry
  -scopeContext : ScopeContext
  -observabilityBus : ObservabilityBus
  -featureSet : FeatureSet
}

class ScenarioRegistry {
  +register(controller)
  +unregister(controller)
  +match(context) : List<ScenarioContribution>
  +snapshot() : Snapshot
  -controllers : ConcurrentHashMap<String, ScenarioController>
  -index : volatile Map<OperationType, ScenarioController[]>
  -failures : ConcurrentLinkedQueue<ActivationFailure>
}

class ScenarioController {
  +evaluate(context) : ScenarioContribution
  +start() / stop() / release() / destroy()
  -started : AtomicBoolean
  -matchedCount : AtomicLong
  -appliedCount : AtomicLong
  -state : volatile ScenarioState
  -gate : ManualGate
  -stressor : volatile ManagedStressor
  -clockSkewState : volatile ClockSkewState
}

class SelectorMatcher {
  +{static} matches(selector, context) : boolean
}

class InvocationContext {
  operationType
  targetClassName
  subjectClassName
  targetName
  periodic
  daemonThread
  virtualThread
  sessionId
}

class RuntimeDecision {
  delayMillis
  gateAction
  terminalAction
}

class ScenarioContribution {
  controller
  scenario
  effect
  delayMillis
  gateTimeout
}

ChaosRuntime --> ScenarioRegistry
ChaosRuntime --> ScopeContext
ScenarioRegistry "1" *-- "N" ScenarioController
ScenarioController --> SelectorMatcher
ScenarioController --> ManualGate
ScenarioController --> ManagedStressor
ChaosRuntime ..> InvocationContext : creates
ScenarioRegistry ..> ScenarioContribution : returns
ChaosRuntime ..> RuntimeDecision : builds
@enduml
```

**Takeaway**: `ChaosRuntime` is a thin orchestrator. The substantive decisions are in `ScenarioController.evaluate()` (policy) and `SelectorMatcher.matches()` (routing).

## Sequence Diagram — Gate Effect Lifecycle

```plantuml
@startuml
participant "Application Thread A" as A
participant "ChaosRuntime" as CR
participant "ScenarioController" as SC
participant "ManualGate" as G
participant "Test Thread" as T

T -> CR: activate(gateScenario) → handle
CR -> SC: start()
SC -> G: reset()

A -> CR: beforeSocketConnect(...)
CR -> SC: evaluate(context) → contribution with GateEffect
CR -> G: await(maxBlock)
note over G: A blocks here

T -> T: observe A is blocked
T -> SC: handle.release()
SC -> G: release()
note over G: A unblocks
G --> CR: returns
CR -> A: continues
@enduml
```

**Takeaway**: `ManualGate` is a reusable barrier backed by `ReentrantLock + Condition`. Each `release()` opens the gate for all currently waiting threads. The gate is auto-reset by `start()` on the next activation.

---

# 6. Component Breakdown

## SelectorMatcher

Exhaustive `switch` over the sealed `ChaosSelector` hierarchy. Each case extracts the matching conditions from the selector variant and applies them to `InvocationContext` fields.

Key matching rules:

| Selector type          | Primary match fields                                                              | Additional constraints                                               |
|------------------------|-----------------------------------------------------------------------------------|----------------------------------------------------------------------|
| `ThreadSelector`       | `operationType` in operations, `threadNamePattern.matches(targetName)`            | `kind` (ANY/PLATFORM/VIRTUAL), `daemon` flag                         |
| `ExecutorSelector`     | `operationType` in operations, `executorClassPattern.matches(targetClassName)`    | `taskClassPattern.matches(subjectClassName)`                         |
| `QueueSelector`        | `operationType` in operations, `queueClassPattern.matches(targetClassName)`       | —                                                                    |
| `AsyncSelector`        | `operationType` in operations                                                     | No class/name filtering                                              |
| `SchedulingSelector`   | `operationType` in operations, `executorClassPattern.matches(targetClassName)`    | `periodicOnly` flag                                                  |
| `ShutdownSelector`     | `operationType` in operations, `targetClassPattern.matches(targetClassName)`      | —                                                                    |
| `ClassLoadingSelector` | `operationType` in operations, `targetNamePattern.matches(targetName)`            | `loaderClassPattern.matches(targetClassName)`                        |
| `MethodSelector`       | `operationType` in operations, `classPattern.matches(targetClassName)`            | `methodNamePattern.matches(targetName)`, optional `signaturePattern` |
| `MonitorSelector`      | `operationType` in operations, `monitorClassPattern.matches(targetClassName)`     | —                                                                    |
| `JvmRuntimeSelector`   | `operationType` in operations                                                     | No class/name filtering                                              |
| `NioSelector`          | `operationType` in operations, `channelClassPattern.matches(targetClassName)`     | —                                                                    |
| `NetworkSelector`      | `operationType` in operations, `remoteHostPattern.matches(targetName)`            | —                                                                    |
| `ThreadLocalSelector`  | `operationType` in operations, `threadLocalClassPattern.matches(targetClassName)` | —                                                                    |
| `StressSelector`       | `operationType == LIFECYCLE`, `target != null`                                    | No class/name filtering                                              |

**null == wildcard**: Pattern fields that are `null` match anything. This is consistent across all selector types.

## CompatibilityValidator

Validates selector/effect combinations before registration. Throws `ChaosActivationException` on violation. All checks run at activation time, not at match time.

Key checks:

- Virtual thread selector requires `FeatureSet.supportsVirtualThreads()` (JDK 21+)
- Stressor effects require `StressSelector`; non-stress effects must not use `StressSelector`
- `GateEffect` requires a stress-compatible or appropriate operation selector
- `SpuriousWakeupEffect` requires `NioSelector`
- `ReturnValueCorruptionEffect` requires `MethodSelector` with `METHOD_EXIT` operation
- `ExceptionInjectionEffect` requires `MethodSelector` with `METHOD_ENTER` operation
- `ExceptionalCompletionEffect` requires `AsyncSelector`
- `ClockSkewEffect` requires `JvmRuntimeSelector` with one of: `SYSTEM_CLOCK_MILLIS`, `SYSTEM_CLOCK_NANOS`, `INSTANT_NOW`, `LOCAL_DATE_TIME_NOW`, `ZONED_DATE_TIME_NOW`, or `DATE_NEW`
- Session-scope scenarios may not use JVM-global selectors (clock, GC, exit, class loading, native library)
- **Destructive effects guard**: `DeadlockEffect` and `ThreadLeakEffect` require `activationPolicy.allowDestructiveEffects() == true`; activation without the flag throws `ChaosActivationException` at registration time

The destructive effects guard exists because deadlocked threads and leaked non-daemon threads cannot be cleaned up within the running process. The check is intentionally fail-fast and unconditional — there is no way to bypass it short of constructing an `ActivationPolicy` with `allowDestructiveEffects=true`.

## ScopeContext

Wraps a single `ThreadLocal<String>` holding the current session ID. Key operations:
- `currentSessionId()`: reads the `ThreadLocal`
- `bind(sessionId, Runnable)`: sets the `ThreadLocal`, runs the runnable, restores prior value in `finally`
- `wrap(sessionId, Runnable)`: returns a `Runnable` that, when executed, sets the `ThreadLocal` to `sessionId` for the duration of the run — used for propagating session context to executor threads

**Why ThreadLocal and not a method argument**: The session ID must be available at every instrumentation point without changing the signature of any JDK method. `ThreadLocal` is the only transparent carrier for per-thread state in a JVM agent context.

**Propagation**: besides explicit wrapping (`session.wrap(task)`, `ScopeContext.wrap(sessionId, task)`) and executor decoration, `SessionHandoff` carries the session across `ForkJoinPool` submissions and forks, `Thread.start()` and virtual threads: the session is recorded against the task or thread (weak, identity-keyed) when it is handed off and bound for the duration of its run. Work handed off from a thread without a session carries none; a `Thread` subclass whose `run()` does not call `super.run()` does not receive the session. A thread started inside a session runs in it for its whole life — except pool workers: a thread a `ThreadPoolExecutor` (or Tomcat's copy) creates in `addWorker`, and every `ForkJoinWorkerThread`, serves many tasks that each carry their submitter's session, so the worker itself inherits none. Other long-lived threads a session starts directly (a Netty event loop started from a test thread, a hand-made worker loop) stay in it.

**Across HTTP**: requests the JDK `HttpClient` or `HttpURLConnection` sends from a thread in a session carry `X-Chaos-Session: <id>` (`ChaosSession.HEADER`) — to internal hosts only: loopback, private and link-local addresses, single-label names and the `.local`, `.localhost`, `.internal`, `.svc` and `.cluster.local` domains, plus the globs of the agent setting `sessionHeaderHosts` (`-Dmacstab.chaos.sessionHeaderHosts`, `MACSTAB_CHAOS_SESSION_HEADER_HOSTS`; `*` for all). A header the caller set is left alone. A server joins it with `controlPlane.bindSession(id)` for the duration of the request — the Spring Boot starters register `ChaosSessionFilter` for that. The header decides every request: a session open in this JVM is bound; an unknown id, a closed session or no header at all runs the request outside any session, masking one the thread may have inherited. Other HTTP clients (OkHttp, Apache, Reactor Netty) do not add the header yet; set it yourself from `session.id()`.

## ManualGate

Backed by `ReentrantLock + Condition`. State is a `volatile boolean open`.

- `await(maxBlock)`: if gate is closed, `condition.await(maxBlock)`. Returns immediately if open or if timeout elapses.
- `release()`: sets `open = true`, signals all waiters (`condition.signalAll()`), unlocks.
- `reset()`: sets `open = false`. Called by `ScenarioController.start()` to re-arm the gate for a new activation.

**Thread safety**: All operations are guarded by the `ReentrantLock`. Multiple threads may block simultaneously in `await()`.

**AQS usage**: `ReentrantLock` uses `AbstractQueuedSynchronizer` internally. The AQS instrumentation (`MonitorEnterAdvice`) fires on `acquire()` inside `ReentrantLock.lock()`. This fires inside the chaos runtime itself. The DEPTH guard in `BootstrapDispatcher` short-circuits before re-entering chaos evaluation, preventing infinite recursion.

## ClockSkewState

Holds reference timestamps captured at `ScenarioController.start()`:
- `referenceMillis`: `System.currentTimeMillis()` at start
- `referenceNanos`: `System.nanoTime()` at start

Mode semantics:
- **FIXED**: adds a constant offset (`skewEffect.offsetMillis()`) to every clock read
- **DRIFT**: adds `offset * callCount` — the skew grows monotonically with each clock invocation; `callCount` is a per-state `AtomicLong`
- **FREEZE**: always returns the reference value; time appears stopped at the moment the scenario started

`ClockSkewState` is `volatile` in `ScenarioController`. On `stop()`, it is set to `null`; `applyClockSkew()` in `ChaosRuntime` checks for null before calling state methods.

`applyClockSkew()` is the single generalised entry point that handles all six clock operation types: `SYSTEM_CLOCK_MILLIS`, `SYSTEM_CLOCK_NANOS`, `INSTANT_NOW`, `LOCAL_DATE_TIME_NOW`, `ZONED_DATE_TIME_NOW`, and `DATE_NEW`. Only `SYSTEM_CLOCK_NANOS` routes through the nanosecond skew channel; every other type routes through the millisecond channel. The higher-level type-specific wrappers (`adjustInstantNow`, `adjustLocalDateTimeNow`, `adjustZonedDateTimeNow`, `adjustDateNew`) each extract the underlying millisecond value, pass it to `applyClockSkew()` with the appropriate `OperationType`, then reconstruct the high-level time object — preserving zone identity for `ZonedDateTime` and nanosecond sub-millisecond precision for `Instant`.

Test coverage: `ClockSkewRuntimeTest$HigherLevelTimeApis` (8 tests) covers FIXED skew on `Instant.now`, `LocalDateTime.now`, `ZonedDateTime.now`, and `Date`; FREEZE mode on `Instant.now`; isolation between `INSTANT_NOW` and `DATE_NEW` operation types; and passthrough when no scenario matches.

## ReturnValueCorruptor

Applies a `ChaosEffect.ReturnValueCorruptionEffect.Strategy` to a (returnType, actualValue) pair:

| Strategy     | int/long                               | float/double                           | String    | Collection | Boolean | null return |
|--------------|----------------------------------------|----------------------------------------|-----------|------------|---------|-------------|
| NULL         | null                                   | null                                   | null      | null       | null    | null        |
| ZERO         | 0 / 0L                                 | 0.0f / 0.0d                            | "0"       | empty      | false   | null        |
| EMPTY        | "" (if String)                         | 0.0                                    | ""        | empty      | false   | null        |
| BOUNDARY_MAX | `Integer.MAX_VALUE` / `Long.MAX_VALUE` | `Float.MAX_VALUE` / `Double.MAX_VALUE` | unchanged | unchanged  | true    | null        |
| BOUNDARY_MIN | `Integer.MIN_VALUE` / `Long.MIN_VALUE` | `Float.MIN_VALUE` / `Double.MIN_VALUE` | unchanged | unchanged  | false   | null        |

The return type is determined from the actual value's runtime class if the declared type is erased to `Object`. Unrecognized types fall through with the actual value unchanged (no silent data corruption for unknown types).

## FailureFactory

Constructs operation-appropriate exceptions for `RejectEffect` and `ExceptionalCompletionEffect`:

| Operation type                          | Exception type                                |
|-----------------------------------------|-----------------------------------------------|
| `SOCKET_CONNECT`, `NIO_CHANNEL_CONNECT` | `ConnectException`                            |
| `SOCKET_READ`, `SOCKET_ACCEPT`          | `SocketTimeoutException`                      |
| `SOCKET_WRITE`, `NIO_CHANNEL_WRITE`     | `IOException`                                 |
| `EXECUTOR_SUBMIT`                       | `RejectedExecutionException`                  |
| `JNDI_LOOKUP`                           | `NamingException`                             |
| `CLASS_LOAD`, `CLASS_DEFINE`            | `ClassNotFoundException` / `ClassFormatError` |
| `OBJECT_DESERIALIZE`                    | `InvalidClassException`                       |
| `OBJECT_SERIALIZE`                      | `NotSerializableException`                    |
| `NATIVE_LIBRARY_LOAD`                   | `UnsatisfiedLinkError`                        |
| `DIRECT_BUFFER_ALLOCATE`                | `OutOfMemoryError`                            |
| `SYSTEM_EXIT_REQUEST`                   | `SecurityException` (via suppressTerminal)    |
| default                                 | `RuntimeException` with the reject message    |

---

# 7. Data Model and State

## ChaosScenario (API)

```
id: String                         — unique within a registry scope key
description: String                — human-readable label
scope: JVM | SESSION               — isolation scope
precedence: int                    — tie-breaking for terminal action selection; default 0
selector: ChaosSelector            — sealed hierarchy; which operations trigger this scenario
effect: ChaosEffect                — sealed hierarchy; what to do when triggered
activationPolicy: ActivationPolicy — when and how often to apply
```

Immutable after construction.

## ActivationPolicy (API)

```
startMode: AUTOMATIC | MANUAL      — whether to start on activate() or require explicit start()
probability: double [0.0, 1.0]    — fraction of matching invocations that apply the effect
rateLimit: (permits, window)       — max applications per fixed window anchored at start (up to 2× permits across a boundary)
activateAfterMatches: long ≥ 0    — skip first N matches (warm-up)
activeFor: Duration                — auto-expire after this duration from start()
maxApplications: Long              — stop after this many total applications; null = unlimited
randomSeed: Long                   — seed for probability sampling; null = 0
```

All fields are optional and compose independently (AND logic). A scenario passes all guards or fires on none. Stress scenarios reject `maxApplications`, `rateLimit`, `activateAfterMatches > 0` and `probability < 1.0`; for them `activeFor` ends the stressor (state `INACTIVE`, reason `"expired"`).

## ScenarioReport (Diagnostics)

```
id: String
description: String
scopeKey: String ("jvm" or "session:<id>")
scope: JVM | SESSION
state: REGISTERED | ACTIVE | INACTIVE | STOPPED
matchedCount: long
appliedCount: long
reason: String  — last observed reason for no-fire or state transition
```

Snapshot value; not live-updating after capture.

---

# 8. Concurrency and Threading Model

See [overall-agent.md §8] for the full concurrency model. Core-specific notes:

## Rate Limit Synchronization

`synchronized(this)` on the `ScenarioController` instance guards `rateWindowStartMillis` and `rateWindowPermits`. The critical section is ~5 operations (compare, subtract, conditional reset, increment, compare). Lock hold time is sub-microsecond under contention-free conditions.

Under high concurrency with many threads hitting the same rate-limited scenario, this `synchronized` block becomes a serialization point. This is acceptable because rate-limited scenarios are typically not on ultra-hot paths (if you need lock-free rate limiting at microsecond granularity, this design would need to change).

## matchedCount vs appliedCount

`matchedCount.incrementAndGet()` runs at step 4, before warm-up and rate-limit checks. This is deliberate:
- `matchedCount` answers "is the selector working?"
- `appliedCount` answers "is the effect being applied?"
- `matchedCount > 0 && appliedCount == 0` diagnoses activation policy suppression

## Reservation for maxApplications

`reserveApplication()` and `commitApplication()` update `appliedCount` and the outstanding reservations under a small lock, so committed applications plus reservations never exceed the cap — concurrent threads cannot overshoot it. A reservation is committed only when the dispatcher actually applies the contribution; a scenario that loses on precedence releases its reservation and keeps its budget. The scenario becomes `INACTIVE` only once committed applications reach the cap.

## RateLimit Precision under High Concurrency

The `synchronized(this)` guard that protects the rate-limit window does more than prevent double-counting — it upholds the exact-permits guarantee: given `N` permits per window, _exactly_ `N` applications occur, never more and never fewer (when more threads compete than there are permits).

**Invariant (test coverage in `RateLimitUnderHighConcurrencyTest`)**:

| Scenario                                        | Expected `appliedCount` | Assertion                           |
|-------------------------------------------------|-------------------------|-------------------------------------|
| 100 threads, 7 permits, 10 s window             | `<= 7`                  | overshoot prevention                |
| 100 threads, 10 permits, 10 s window            | `== 10`                 | no permit wasted                    |
| 50 threads, `maxApplications=3`, `rateLimit=20` | `<= 3`                  | `maxApplications` wins when tighter |

The last row confirms that `maxApplications` and `rateLimit` are independent guards: the tighter constraint wins. When `maxApplications < permitsPerWindow`, the budget on `appliedCount` is exhausted before the rate-limit window is.

## stop() Monotonic State Transition

`ScenarioController` state transitions monotonically: `REGISTERED → ACTIVE → STOPPED` (or `ACTIVE → INACTIVE` on expiry / exhausted budget). Once a controller observes the stopped flag it never reverts. Re-activating the same id after `stop()` or `close()` registers a **new** controller that replaces the stopped one.

**Guarantees (test coverage in `StopDuringEvaluationTest`)**:

- `handle.stop()` completes a sequentially-consistent write; the calling thread reads `STOPPED` immediately after `stop()` returns.
- Threads that began an evaluation pass _before_ observing the stopped flag may complete with an effect applied. This is the legally-in-flight window; it is bounded and resolves without exception.
- Threads that enter `evaluate()` _after_ the stopped flag is visible receive a null contribution — no effect is applied.
- Concurrent reader threads never observe a `STOPPED → ACTIVE` state reversal. The `stopped` flag is an `AtomicBoolean`; once `true`, `compareAndSet(false, true)` by any other writer fails.

**Implementation note**: `evaluate()` reads `stopped` at the first step of the pipeline (before the selector check). This minimizes work done on stopped controllers and ensures the evaluation short-circuits cleanly without producing partial state.

## Multi-Scenario Terminal Action Precedence

When two or more scenarios match the same invocation and both carry terminal actions (reject, suppress, exception), the merge rule is:

1. **Order**: delay first, then gate, then terminal action. A delay is never dropped because a terminal action wins.
2. **Delays accumulate**: all matching scenarios' delay durations are summed before sleeping.
3. **Gate**: the highest-precedence gate holds the thread.
4. **Terminal action**: the scenario with the highest `precedence()` integer wins. Equal precedence is last-write (non-deterministic) but always results in exactly one terminal action being applied — never zero, never two.

**Test coverage in `TwoScenariosConflictingTerminalActionsTest`**:

- Higher-precedence REJECT (`precedence=100`) beats lower-precedence SUPPRESS (`precedence=0`) regardless of registration order.
- Delay accumulation: two 30 ms scenarios produce `>= 48 ms` observed latency (×0.8 tolerance for scheduler jitter).
- Under 80 concurrent threads × 5 iterations (400 total calls), all calls produce REJECT when reject has higher precedence — no call escapes with suppress or no effect.

**Why registration order is irrelevant**: `ChaosRuntime.evaluate()` iterates all matching controllers and builds a merged result; precedence is resolved by a final `max(precedence)` scan over contributions, not by insertion order.

## Session Isolation Invariants

Sessions provide hard isolation boundaries via `ScopeContext`, a thread-local stack of active session IDs.

**Invariants (test coverage in `SessionIsolationParallelTest`)**:

| Scenario                                        | Invariant                                                                        |
|-------------------------------------------------|----------------------------------------------------------------------------------|
| 10 sessions × 10 threads × 5 iterations         | Each session's `appliedCount == 50` — no cross-session bleed                     |
| 50 unbound threads, 10 active session scenarios | All session `appliedCount == 0` — unbound threads invisible to session selectors |
| Session closed after pre-close applications     | Post-close calls on any thread are not delayed — effect stops immediately        |

**Why cross-session bleed is impossible**: `ScopeContext.currentSessionId()` returns the top of the calling thread's session ID stack. The `ScenarioController` for a SESSION-scoped scenario stores its session ID at registration time. The evaluation pipeline (step 3) compares `currentSessionId()` against the controller's ID with reference equality on interned session UUIDs. A thread in session A can never satisfy the guard for a controller registered to session B.

**Root binding and lifecycle**: `DefaultChaosSession(...)` calls `scopeContext.bindRoot(id)` in its constructor — the opening thread is automatically in-scope from construction. `session.close()` calls `rootBinding.close()` last, after all controllers are destroyed, ensuring no thread reads a dangling session ID.

---

# 9. Error Handling and Failure Modes

## Exception Injection Failure

If `Class.forName(exceptionClassName)` throws `ClassNotFoundException`, or if neither a `(String)` constructor nor a no-arg constructor is accessible via reflection, `buildInjectedExceptionTerminal()` returns a `RuntimeException` describing the failure. This is a degrade-gracefully design: the scenario fires, but injects a different exception than configured.

**Operator signal**: If injected exceptions are unexpected `RuntimeException`s containing "chaos-agent: failed to instantiate", the `exceptionClassName` in the scenario configuration is wrong or the exception class is not on the classpath.

## Stressor Creation Failure

`StressorFactory.createIfNeeded()` does not catch stressor-creation errors. A failure (for example an `OutOfMemoryError` while allocating heap pressure, or a thread that cannot be started) propagates out of `start()` and therefore out of `activate()`. For a `RuntimeException` the scenario is unregistered again and the failure is recorded in the diagnostics failure list. A stressor never silently runs as a no-op.

Stressor notes:
- `activeFor` ends a stressor: when the window elapses the stressor is closed and the scenario becomes `INACTIVE` with reason `"expired"`.
- `VirtualThreadCarrierPinningStressor` holds carriers with **virtual** threads: on JDK 21–23 by sleeping inside a monitor (true pinning, no CPU), on JDK 24+ (JEP 491, monitors no longer pin) with a spin-wait — the carrier is equally unavailable but each held carrier costs one CPU while active.
- `MetaspacePressureStressor` with `retain=false` releases the class loader immediately after defining the classes (a transient spike); with `retain=true` the classes stay loaded until `close()`.

## Scenario Duplicate Registration

`ScenarioRegistry.register()` throws `ChaosActivationException("scenario key already active: " + key)` if a controller with the same key is present and still `REGISTERED` or `ACTIVE`. A `STOPPED` or `INACTIVE` controller under the key is replaced instead, so the same id can be activated again after `stop()`, `close()`, expiry or an exhausted budget. The key is `scopeKey + "::" + scenarioId` (for example `jvm::slow-db` or `session:<sid>::slow-db`) and is what `handle.id()` returns. The conflict is recorded in the diagnostics failure list as `ACTIVATION_CONFLICT` and rethrown.

## Session Close and Registry Cleanup

`DefaultChaosSession.close()` calls `ScenarioRegistry.unregister(controller)` for every session-scoped controller. `unregister()` uses `ConcurrentHashMap.remove(key, controller)` — identity-based conditional removal. If the controller was already replaced (e.g., by a duplicate-ID registration race), only the exact registered instance is removed.

JVM-scoped controllers are NOT removed on session close. They stay registered until `handle.close()` (which stops **and** unregisters) or `controlPlane.close()`. `handle.stop()` alone leaves a `STOPPED` entry in the registry until it is closed or replaced by a new activation of the same id. A closed control plane rejects `activate`, `openSession` and `addEventListener` with `IllegalStateException`.

---

# 10. Security Model

- No authentication or authorization on the `ChaosControlPlane` API
- `CompatibilityValidator` is a structural validator, not a security boundary
- Exception injection via `ExceptionInjectionEffect` uses `Class.forName()` with the current thread's classloader; the exception class must be on the application classpath
- Return-value corruption via `ReturnValueCorruptionEffect` can corrupt application data structures; misuse in production can cause data integrity issues

---

# 11. Performance Model

## Scenario Lookup Cost

`ScenarioRegistry.match()` reads the immutable operation index once and evaluates only the controllers whose selector names the intercepted operation, in their pre-sorted order — scenarios for other operations cost nothing and nothing is sorted per call. Clock-skew reads use the same index and return immediately when no scenario names the clock operation.

## Selector Matching Cost

`SelectorMatcher.matches()` is a sealed-hierarchy switch. Each case is at most 3–4 field comparisons and `NamePattern.matches()` calls. For simple string patterns (exact match), this is sub-microsecond. GLOB patterns use an allocation-free wildcard matcher (the compiled regex is only used for candidates containing line terminators or surrogate characters). For regex patterns, the cost depends on the regex complexity, and each match allocates a `Matcher`.

**Recommendation**: Use exact class name matches or simple prefix patterns rather than complex regexes in performance-sensitive scenarios.

## Evaluation Pipeline Cost (per controller, per intercepted call)

- Steps 1–3: ~10–50 ns (AtomicBoolean read + string equality + SelectorMatcher)
- Step 4 (matchedCount + window check): ~5 ns (AtomicLong CAS + instant comparison)
- Step 5 (warm-up): ~3 ns (AtomicLong comparison)
- Step 6 (rate limit): ~10–100 ns depending on synchronized contention
- Step 7 (probability): ~50 ns (SplittableRandom allocation + nextDouble)
- Step 8 (maxApplications CAS): ~5–20 ns (AtomicLong CAS)

Total per matching controller that passes all checks: ~100–300 ns exclusive of the effect itself.

## ObservabilityBus Publishing Cost

`observabilityBus.publish(APPLIED, ...)` and `incrementMetric()` are called on every successful application. With a NOOP metrics sink and no event listeners, the publish call is near-zero cost. With real listeners or a real metrics sink, the cost of the event handler adds to the hot path. **Listeners must be non-blocking.**

---

# 12. Configuration Reference

See `chaos-agent-api` for the full `ChaosScenario`, `ChaosSelector`, `ChaosEffect`, and `ActivationPolicy` builder APIs.

Key invariants enforced at construction time by the builder API or `CompatibilityValidator`:

- `probability` must be in [0.0, 1.0]
- `rateLimit.permits > 0`
- `activateAfterMatches >= 0`
- `maxApplications > 0` if set
- `randomSeed` may be any `long`; 0 is not a reserved value
- For `ClockSkewEffect.DRIFT` mode: a positive `offsetMillis` adds drift forward; negative adds drift backward
- For `ClockSkewEffect.FREEZE` mode: `offsetMillis` is ignored (the clock freezes at the reference value)

---

# 13. Extension Points

- `ChaosMetricsSink`: implement to forward `chaos.effect.applied` metrics to a custom sink
- `ChaosEventListener`: implement to subscribe to STARTED/STOPPED/RELEASED/APPLIED events
- Both are registered on `ChaosRuntime` at construction time or via `addEventListener()`

---

# 14. Stack Walkdown

**Application layer**: `ChaosRuntime.activate()` → `registerScenario()` → `ScenarioController` creation + `ScenarioRegistry.register()` + optional `start()`. Cost: one `ConcurrentHashMap.putIfAbsent()`.

**Hot path**: See Section 4 for the full call sequence from `ChaosRuntime.before*()` to `evaluate()` to decision execution.

**JVM layer**: `AtomicBoolean.get()`, `AtomicLong.incrementAndGet()`, `AtomicLong.compareAndSet()` — all mapped to x86 `LOCK CMPXCHG` / `LOCK XADD` instructions. `ScenarioRegistry.match()` — one `volatile` read of the immutable operation index; no lock, no stream.

**OS layer**: `Thread.sleep(delayMillis)` → `nanosleep(2)` on Linux. `synchronized(this)` → OS mutex via HotSpot's biased locking / lightweight locking / inflated lock stack depending on contention level.

---

# 15. References

- Reference: Java SE API — `java.util.concurrent.atomic.AtomicBoolean`, `AtomicLong`
- Reference: Java SE API — `java.util.concurrent.ConcurrentHashMap` §lock-free read path
- Reference: Java SE API — `java.util.SplittableRandom` (not thread-safe)
- Reference: JSR-133 — Java Memory Model §17.4 — happens-before rules for `volatile` reads/writes
- Reference: Java SE API — `java.util.concurrent.atomic.AtomicBoolean`, `AtomicLong` — https://docs.oracle.com/en/java/docs/api/java.base/java/util/concurrent/atomic/AtomicLong.html
- Reference: Java SE API — `java.util.concurrent.ConcurrentHashMap` (lock-free read via segment traversal) — https://docs.oracle.com/en/java/docs/api/java.base/java/util/concurrent/ConcurrentHashMap.html
- Reference: Java SE API — `java.util.SplittableRandom` (not thread-safe; xorshift-based non-linear PRNG) — https://docs.oracle.com/en/java/docs/api/java.base/java/util/SplittableRandom.html
- Reference: JSR-133 — Java Memory Model §17.4 — happens-before rules for `volatile` reads/writes — https://jcp.org/aboutJava/communityprocess/mrel/jsr133/index.html
- Reference: JLS §17.7 — Non-atomic treatment of double and long; `volatile long` guarantees — https://docs.oracle.com/javase/specs/jls/se21/html/jls-17.html#jls-17.7
- Reference: Java SE API — `java.util.concurrent.locks.ReentrantLock`, `Condition.await(timeout, unit)` — https://docs.oracle.com/en/java/docs/api/java.base/java/util/concurrent/locks/ReentrantLock.html
- Reference: JEP 444 — Virtual Threads; `Thread.isVirtual()`; carrier-thread pinning under `synchronized` — https://openjdk.org/jeps/444
- Reference: Intel® SDM Vol. 3 — `LOCK CMPXCHG` (compare-and-swap), `LOCK XADD` (fetch-and-add) — https://www.intel.com/content/www/us/en/developer/articles/technical/intel-sdm.html
- Reference: JVMS §6.5 `monitorenter` opcode — https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-6.html#jvms-6.5.monitorenter

---

<div align="center">

*Architecture, implementation, and documentation crafted with Love and Passion by*

**[Christian Schnapka](https://macstab.com)**  
Embedded Principal+ Engineer  
[Macstab GmbH](https://macstab.com) · Hamburg, Germany

*Building systems that operate correctly at the edges — including the ones you deliberately break.*

</div>
