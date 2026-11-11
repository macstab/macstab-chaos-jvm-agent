<!--
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Engineered by  Christian Schnapka
                 Embedded Principal+ Engineer
                 Macstab GmbH · Hamburg, Germany
                 https://macstab.com
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-->

# macstab-chaos-jvm-agent — System Architecture Reference

> Authoritative internal reference for principal engineers, SREs, and production incident responders.
> 
> *Engineered by* **[Christian Schnapka](https://macstab.com)** — Embedded Principal+ Engineer · [Macstab GmbH](https://macstab.com) · Hamburg, Germany

---

# 1. Overview

## Purpose

`macstab-chaos-jvm-agent` is an in-process JVM chaos injection system. It intercepts selected JDK
surfaces — thread pools, schedulers, queues, sockets, NIO selectors, class loaders, serialization,
clocks, JNDI, JMX, native libraries, and more — and injects controlled failures, delays, and
resource stress without requiring any change to application source code.

The system operates at the bytecode level via ByteBuddy advice, woven into the JDK classes by
retransformation on the first activation (or session) that needs a hook group
([ADR 0002](adr/0002-on-demand-weaving.md)). All fault injection is exact, deterministic (when
seeded), and scoped either to the full JVM process or to individual test sessions running on
specific threads.

Key properties:
- **No application change** — zero-instrumentation target code required
- **Per-session isolation** — concurrent tests share a JVM; chaos is thread-local unless explicitly JVM-scoped
- **Runtime control** — scenarios start, stop, fire, and expire without JVM restart
- **Activation policy composition** — probability, rate limit, warm-up, time window, max-applications, random seed
- **78 bridge handles** (`BootstrapDispatcher.HANDLE_COUNT`) in 41 hook groups, covering the JDK surface and the client libraries relevant to enterprise backend workloads

## Scope

In scope:
- All JVM interception points of the hook groups ([instrumentation.md §7](instrumentation.md#7-the-78-interception-handles) lists the handles)
- JVM-global and session-local chaos, independently composable
- Startup and programmatic activation
- JSON plan loading at startup (file, inline, base64)
- JUnit 5 test integration via `ChaosAgentExtension`
- Diagnostics: in-process snapshot, debug dump, JMX MBean

Out of scope:
- A remote control protocol of the agent's own: plans arrive over the framework integrations' management endpoints and the MBean's `activatePlan` operation (with `jmxControl`), each off by default
- Distributed or multi-process coordination
- Agent uninstallation as a user-facing feature. Internally `JdkInstrumentationInstaller.uninstall(Instrumentation)` resets the transformations and the bridge delegate (used to roll back a failed initialization); classes already loaded through the bootstrap bridge jar stay on the bootstrap class path
- Arbitrary user-defined instrumentation points beyond the built-in hook groups (a `MethodSelector` weaves application methods only, never a JDK class)
- Kernel-level or network-layer fault injection

## Key Assumptions

- Process owner is trusted; the agent is installed by the same party that runs the JVM
- Scenario definitions are trusted configuration, not adversarial tenant input
- The target JVM permits startup instrumentation (`-javaagent:`) or local self-attach via the Attach API
- Callers of intercepted JDK methods accept the possibility of in-process blocking, exception injection, and return-value corruption as intended test behavior

## Non-Goals

- Sandboxing untrusted code in a shared JVM
- Transactional activation semantics (activate/rollback atomically across multiple scenarios)
- Zero overhead when no scenarios are active once hooks are woven (a JVM that never activated a scenario or opened a session runs unmodified JDK classes, but a woven hook keeps a fast-path operation-index lookup, which is not zero cost)
- Strict real-time latency guarantees on injected delays

---

# 2. Architectural Context

## Module Decomposition

The agent is a multi-module Gradle project organised as a strict directed dependency graph: the stable public API at the top, bytecode instrumentation and the bootstrap bridge at the bottom, with runtime core, startup configuration, and framework integrations layered between. Every module is independently versioned and independently publishable — consumers depend on `chaos-agent-api` for contract stability and pull in exactly one integration module (test or runtime, Boot 3 or Boot 4) for the chosen environment.

### Module responsibilities

- **Public contract** — `chaos-agent-api` is the only module application code compiles against. Sealed hierarchies (`ChaosSelector`, `ChaosEffect`), records (`ChaosScenario`, `ChaosPlan`, `ActivationPolicy`, `NamePattern`), and the `ChaosControlPlane` / `ChaosSession` interfaces form the stable surface. Everything else is implementation.
- **Runtime core** — `chaos-agent-core` holds the scenario registry, the 9-step evaluation pipeline, session scoping, and every stressor implementation (heap, metaspace, direct-buffer, GC, finalizer, deadlock, thread-leak, monitor, code-cache, safepoint, string-intern, reference-queue, thread-local, keep-alive, virtual-thread carrier pinning). Hot-path code (`ChaosDispatcher`, `ScenarioController`) is profiled against JMH benchmarks.
- **Bytecode instrumentation** — `chaos-agent-instrumentation-jdk` defines the ByteBuddy `@Advice` classes and the bootstrap-classloader bridge. `BootstrapDispatcher` (bootstrap-resident, appended via `Instrumentation.appendToBootstrapClassLoaderSearch`) is the only path by which instrumented JDK methods reach the agent classloader — a `volatile` two-field publication protocol guarantees JMM visibility of the 78-slot `MethodHandle[]` dispatch table.
- **Agent lifecycle** — `chaos-agent-bootstrap` owns the `premain` / `agentmain` entry points, the singleton `ChaosControlPlane` installation, and JMX MBean registration.
- **Configuration resolution** — `chaos-agent-startup-config` resolves plans from `configFile`, `configJson`, `configBase64`, and environment variables, deserialises them via Jackson polymorphic mapping, and runs the live-reload file watcher.
- **Test integrations** — `chaos-agent-testkit` provides `ChaosAgentExtension` (JUnit 5) and `ChaosPlatform.installLocally()` for self-attach. `chaos-agent-spring-boot3-test-starter` and `chaos-agent-spring-boot4-test-starter` compose `@ChaosTest` on top and wire a class-scoped `ChaosSession` into Spring Boot tests.
- **Runtime integrations** — `chaos-agent-spring-boot3-starter` and `chaos-agent-spring-boot4-starter` expose `ChaosControlPlane` as a Spring bean and register the `/actuator/chaos` endpoint for live plan activation against running applications.

### Runtime dispatch

Every intercepted JVM operation travels through five bytecode frames before a decision is reached:

1. **Instrumented JDK method** — e.g. `ThreadPoolExecutor.execute()`, with ByteBuddy advice woven into the method body when a scenario first needs its hook group (not called — *copied* inline)
2. **`BootstrapDispatcher`** — bootstrap-classloader-resident static entry point holding the 78-slot `MethodHandle[]` and a `ThreadLocal<int[]>` reentrancy depth guard (a one-element `int` array avoids `Integer` autoboxing per call)
3. **`BridgeDelegate`** — agent-classloader bridge that unboxes arguments and forwards to the active runtime
4. **`ChaosDispatcher` / `ScenarioController`** — registry lookup + the 9-step evaluation pipeline: `started` → session → selector → expiry → `activeFrom` → `matchedCount++` and warm-up → probability → rate limit → max-applications reservation
5. **`ChaosEffect`** — terminal action executed inline on the calling thread (`delay` / `reject` / `suppress` / `gate` / `exception` / `corruptReturnValue` / `skewClock` / stressor handle)

The reentrancy guard short-circuits when chaos code itself calls instrumented JDK methods (e.g. a delay effect's own `Thread.sleep` would otherwise recurse into `THREAD_PARK` interception). The same per-thread `int[]` carries call-depth scope counters, so nested or delegating instrumented calls are evaluated once, at the outermost call.

### Structural diagram

*macstab-chaos-jvm-agent — Module Architecture*

```mermaid
flowchart TB
  subgraph contract["Public Contract"]
    api["chaos-agent-api<br/>sealed ChaosSelector (25 selector types), ChaosEffect (33 effect types)<br/>records ChaosScenario, ChaosPlan, ActivationPolicy, NamePattern<br/>OperationType (92 operation types)"]
  end
  subgraph runtime["Runtime"]
    core["chaos-agent-core"]
    instr["chaos-agent-instrumentation-jdk<br/>ByteBuddy @Advice + BootstrapDispatcher (bootstrap CL)<br/>78 handle slots, ThreadLocal&lt;int[]&gt; reentrancy guard,<br/>volatile two-field publication"]
    config["chaos-agent-startup-config"]
    boot["chaos-agent-bootstrap"]
  end
  subgraph testing["Test-Time Integrations"]
    testkit["chaos-agent-testkit"]
    sb3t["chaos-agent-spring-boot3-test-starter"]
    sb4t["chaos-agent-spring-boot4-test-starter"]
  end
  subgraph integrations["Runtime Integrations"]
    sbc["chaos-agent-spring-boot-common"]
    sb3["chaos-agent-spring-boot3-starter"]
    sb4["chaos-agent-spring-boot4-starter"]
    qx["chaos-agent-quarkus-extension (+ deployment)"]
    qm["chaos-agent-quarkus-management (+ deployment)"]
    mn["chaos-agent-micronaut-integration"]
    mm["chaos-agent-micrometer"]
    otel["chaos-agent-opentelemetry"]
  end

  core --> api
  instr --> core
  instr --> api
  config --> api
  boot --> core
  boot --> instr
  boot --> config
  testkit --> boot
  testkit --> config
  testkit --> api
  sb3t --> testkit
  sb4t --> testkit
  sb3t --> sbc
  sb4t --> sbc
  sbc --> boot
  sb3 --> sbc
  sb4 --> sbc
  qx --> testkit
  qx --> boot
  qm --> core
  qm --> boot
  mn --> testkit
  mn --> boot
  mm --> core
  otel --> core

  classDef apiStyle fill:#E8F4FD,stroke:#5B8DB8,color:#000
  classDef coreStyle fill:#FFF3E0,stroke:#C28A3A,color:#000
  classDef bridgeStyle fill:#F3E5F5,stroke:#8E5BA8,color:#000
  classDef bootStyle fill:#E8F5E9,stroke:#4E9A5B,color:#000
  classDef configStyle fill:#FFF8E1,stroke:#C2A13A,color:#000
  classDef testStyle fill:#FCE4EC,stroke:#B8587A,color:#000
  classDef springStyle fill:#E0F2F1,stroke:#3E8E86,color:#000
  class api apiStyle
  class core coreStyle
  class instr bridgeStyle
  class boot bootStyle
  class config configStyle
  class testkit,sb3t,sb4t testStyle
  class sbc,sb3,sb4,qx,qm,mn,mm,otel springStyle
```

### Module reference

| Module                                  | Role                                                                      |
|-----------------------------------------|---------------------------------------------------------------------------|
| `chaos-agent-api`                       | **Stable public API** — the only module application code should depend on; its one dependency is the JSpecify nullness annotations |
| `chaos-agent-bootstrap`                 | The agent JAR: entry points, singleton, MBean registration; self-contained, ByteBuddy and Jackson relocated, no transitive dependencies |
| `chaos-agent-core`                      | Scenario registry, evaluation pipeline, session scoping, stressors        |
| `chaos-agent-instrumentation-jdk`       | ByteBuddy advice, bootstrap bridge (78 handle slots)              |
| `chaos-agent-startup-config`            | JSON/base64/file config resolution and Jackson mapping                    |
| `chaos-agent-spring-boot-common`        | Shared Spring Boot code of both starters: auto-configuration, trusted enablement, Actuator endpoint, session filter |
| `chaos-agent-quarkus-extension`         | Quarkus `@ChaosTest` support and CDI producer, with its deployment module |
| `chaos-agent-micronaut-integration`     | Micronaut test extension, context configurer and `/chaos` management endpoint |
| `chaos-agent-micrometer`                | Optional Micrometer binder: event, application and skip counters, injected-delay timer, scenario gauges |
| `chaos-agent-quarkus-management`        | Optional Quarkus extension serving `/q/chaos` (snapshot, activate, stop), with its deployment module |
| `chaos-agent-opentelemetry`             | Optional OpenTelemetry metrics and `chaos.applied` span events on the traced calls chaos hit |
| `chaos-agent-testkit`                   | JUnit 5 extension, `ChaosPlatform.installLocally()` for self-attach       |
| `chaos-agent-spring-boot3-test-starter` | `@ChaosTest` + `ChaosAgentExtension` for Spring Boot 3 tests              |
| `chaos-agent-spring-boot4-test-starter` | `@ChaosTest` + `ChaosAgentExtension` for Spring Boot 4 tests              |
| `chaos-agent-spring-boot3-starter`      | Runtime starter with Actuator endpoint for Spring Boot 3                  |
| `chaos-agent-spring-boot4-starter`      | Runtime starter with Actuator endpoint for Spring Boot 4                  |
| `chaos-agent-examples`                  | Runnable usage examples                                                   |

## Runtime Boundaries

Two classloader realms are in play:

1. **Bootstrap classloader**: loads JDK classes (`java.*`, `javax.*`) and must also load
   `BootstrapDispatcher` so advice woven into JDK methods can call it.
2. **Agent classloader**: loads all other agent classes. `ChaosRuntime`, `ScenarioRegistry`,
   `ChaosBridge`, all advice classes, etc., live here.

The bridge between these realms is the `BootstrapDispatcher ↔ ChaosBridge ↔ ChaosRuntime` triple
described in Section 6 and the Instrumentation document.

## Trust Boundaries

- **Agent ↔ JVM**: fully trusted; the agent runs in the same process with the same credentials
- **Agent ↔ application code**: trusted consumer; scenarios fire on application threads, blocking or throwing is intentional behavior
- **Scenario configuration ↔ agent**: trusted at load time; no validation of semantic correctness beyond type safety and `CompatibilityValidator` structural checks
- **External configuration (file, env var, agent arg)**: validated for file path safety (no symlinks, no directory traversal), size-capped at 1 MiB, but JSON content is not sanitized against injection

## Deployment/Runtime Context

The agent is attached in one of two modes:
- **Premain** (`-javaagent:` on the JVM command line)
- **Agentmain** (dynamic attach via `VirtualMachine.attach()` at runtime; requires the opt-in `-Dmacstab.chaos.allow-dynamic-attach=true` or `MACSTAB_CHAOS_ALLOW_DYNAMIC_ATTACH=true`, else `agentmain` does nothing)

Both modes (and the `installForLocalTests()` self-attach) get exactly the same hook set: every hook group is woven by retransforming already-loaded JDK classes, on demand when a scenario first needs it or a session is opened (or all at start with `instrumentation=eager`).

---

# 3. Key Concepts and Terminology

| Term                    | Definition                                                                                                                                                                                                                                            |
|-------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Scenario**            | A named combination of selector, effect, and activation policy. The atomic unit of chaos.                                                                                                                                                             |
| **Selector**            | A matching rule specifying which JVM operations trigger the scenario. Evaluated against an `InvocationContext`.                                                                                                                                       |
| **Effect**              | What happens when a scenario fires: delay, reject, suppress, gate, exception injection, return-value corruption, clock skew, or background stressor.                                                                                                  |
| **Activation policy**   | A set of guards (probability, rate limit, warm-up count, time window, max applications) that filter matches before applying effects. All guards compose as AND.                                                                                       |
| **Plan**                | A named list of scenarios; the unit of configuration at startup.                                                                                                                                                                                      |
| **Session**             | A thread-local isolation scope. Chaos registered under a session is only applied to threads explicitly bound to that session.                                                                                                                         |
| **JVM scope**           | Chaos that applies to all threads regardless of session binding.                                                                                                                                                                                      |
| **ScenarioController**  | The per-scenario runtime object that owns lifecycle state, counters, and gate.                                                                                                                                                                        |
| **ScenarioRegistry**    | The `ConcurrentHashMap`-backed store of all active controllers, plus an immutable operation-type index used by `match()`.                                                                                                                             |
| **InvocationContext**   | A value object capturing the operation type, class names, target name, and session ID at each instrumentation point. Built by `ChaosRuntime` dispatch methods.                                                                                        |
| **RuntimeDecision**     | The merged output of all matching contributions: total delay, optional gate action, optional terminal action.                                                                                                                                         |
| **BootstrapDispatcher** | Bootstrap-classloader-resident static dispatcher; the crossing point between instrumented JDK code and the agent.                                                                                                                                     |
| **Handle**              | `ChaosActivationHandle` — an `AutoCloseable` returned by `activate()`; close it to stop the scenario.                                                                                                                                                 |
| **Stressor**            | A `ManagedStressor` that runs as a background thread or thread group for the duration of a stressor-effect scenario.                                                                                                                                  |
| **Hook group**          | A `JdkInstrumentationInstaller.HookGroup` (e.g. `THREAD_POOL_EXECUTOR`, `SOCKET`, `TIME_API`, `JDBC`) tagged with the `OperationType`s it serves. Woven on the first activation needing one of them (all at start with `instrumentation=eager`).      |

---

# 4. End-to-End Behavior

## Startup path (`-javaagent:`; dynamic attach and self-attach are identical after the entry point)

```
JVM starts with -javaagent:chaos-agent-bootstrap.jar[=args]
  ↓
ChaosAgentBootstrap.premain(agentArgs, instrumentation)
  ↓
AgentLog.hold(): the agent's log lines are held until java.util.logging is configured
  ↓
ChaosRuntime created (Clock.systemUTC, NOOP metrics sink)
  ↓
JdkInstrumentationInstaller.install(instrumentation, runtime, eager, clockCallSites,
                                    sessionHeaderHosts, bridgeDir)
    ├── injectBridge(): package BootstrapDispatcher into temp JAR,
    │                   appendToBootstrapClassLoaderSearch
    ├── installDelegate(): build 78-slot MethodHandle[], wire into BootstrapDispatcher.install()
    └── runtime.setHookDemand(...): hook groups are woven via retransformation when a
        scenario or a session first needs them (eager: every group now)
  ↓
JFR integration; ChaosReports (status and hits files) when reports=on
  ↓
Optional<LoadedPlan> plan = StartupConfigLoader.load(settings)
  if plan present:
    runtime.activate(plan)       ← HookDemand.require(operations) weaves the needed hook
                                   groups, then registers scenarios with AUTOMATIC start mode
    (or, for a watched configFile, StartupConfigPoller.startWithInitialPlan)
  ↓
MBeanServer registration: com.macstab.chaos.jvm:type=ChaosDiagnostics
  (premain: deferred until the application has initialised java.util.logging, 30 s at most;
   agentmain and self-attach: before the plan is loaded)
```

## Hot path (per-intercepted-method, on the application thread)

```
Application calls e.g. ThreadPoolExecutor.execute(task)
  ↓
ByteBuddy @Advice.OnMethodEnter fires
  ↓
BootstrapDispatcher.decorateExecutorRunnable("execute", executor, task)
  ↓
  [DEPTH guard, inlined: if DEPTH[0] > 0 → return task immediately (reentrancy bypass)]
  DEPTH[0]++
  ↓
  snapshot handles[], delegate → null-check
  ↓
  h[DECORATE_EXECUTOR_RUNNABLE].invoke(delegate, "execute", executor, task)
    → ChaosBridge.decorateExecutorRunnable(...)
    → ChaosRuntime → ExecutorDispatch.decorateExecutorRunnable(...)
  ↓
  DispatchEngine.idle(EXECUTOR_SUBMIT): no scenario indexed under the operation → return the
                                        task (wrapped for a session with scenarios) at once
  ↓
  ExecutorDispatch builds InvocationContext{EXECUTOR_SUBMIT, executorClass, taskClass, ..., sessionId}
  ↓
  DispatchEngine.evaluate(context):
    ScenarioRegistry.match(context) → controllers indexed under EXECUTOR_SUBMIT (pre-sorted by
                                      precedence DESC, id ASC, scope), evaluate each, collect
                                      the non-null contributions
      Per controller, ScenarioController.evaluate() runs the nine policy steps
      (semantics.md §3) and returns a ScenarioContribution or null
    RuntimeDecision merge:
      - delayNanos: SUM of all contributions' sampled delays (saturating)
      - gateAction: the first gate contribution (highest precedence, smallest id)
      - terminalAction: the first terminal contribution (highest precedence, smallest id);
        a terminal that cannot change this call (NOT_APPLICABLE) neither applies nor counts
      - commit (appliedCount++, APPLIED event) for every delay, the winning gate and the
        winning terminal action; the other contributions release their maxApplications reservation
  ↓
  Decision execution (delay → gate → terminal action):
    sleep(delayNanos)                     — an interrupt restores the flag and ends the delay
    applyGate(gateAction)                 — gate.await(maxBlock); an interrupt restores the flag
                                            and the call proceeds
    if THROW → throw the failure (RejectedExecutionException for EXECUTOR_SUBMIT)
    if SUPPRESS → a FutureTask is cancelled and handed on, any other task is replaced by a no-op
    return the task, wrapped for the session when the session has a registered scenario
  ↓
  DEPTH decremented (the per-thread int[] is kept; it also holds call-depth scope counters)
  ↓
  Advice returns wrapped task to ThreadPoolExecutor
    → ThreadPoolExecutor runs wrapped task, which restores session context on the worker thread
```

## Session scope propagation

Threads see session-scoped chaos only if bound:
1. `session.bind()` pushes the session ID onto the calling thread's session stack in `ScopeContext` (a per-thread `Deque`); the opening thread is bound from `openSession` on
2. Every dispatch reads `scopeContext.currentSessionId()`, the top of that stack
3. The session ID is embedded in the `InvocationContext`
4. `ScenarioController.evaluate()` skips the call if its `sessionId != null && !sessionId.equals(context.sessionId())`
5. Executor task submission wraps the `Runnable`/`Callable` to carry the session ID across thread pool boundaries via `ScopeContext.wrap()`, once the session has a registered scenario; `SessionHandoff` carries it into fork-join tasks, `Thread.start()` and virtual threads ([core.md §6](core.md#scopecontext))

Consequence: a test can safely activate session-scoped chaos and submit work to a shared `ThreadPoolExecutor`; only tasks submitted from a thread bound to the session (after its first scenario was activated) carry the session ID and are subject to session-scoped chaos. Tasks from other threads — including other parallel tests — are unaffected.

---

# 5. Architecture Diagrams

## Component Diagram — Answering: What depends on what?

The module graph, with every module and its dependencies, is the structural diagram under [Module Decomposition](#module-decomposition).

**Takeaway**: `chaos-agent-api` is the contract applications program against; the agent's internal modules (core, instrumentation, bootstrap) are implementation detail, even where they are public in the Java sense.

## Sequence Diagram — Hot path through ByteBuddy → BootstrapDispatcher → ChaosRuntime

```mermaid
sequenceDiagram
  participant T as Application Thread
  participant A as ByteBuddy Advice<br/>(bootstrap CL)
  participant BD as BootstrapDispatcher<br/>(bootstrap CL)
  participant CB as ChaosBridge<br/>(agent CL)
  participant CR as ChaosRuntime<br/>(agent CL)
  participant SR as ScenarioRegistry<br/>(agent CL)
  participant SC as ScenarioController<br/>(agent CL)
  T->>A: call instrumented JDK method
  A->>BD: decorateExecutorRunnable(op, executor, task)
  BD->>BD: DEPTH guard check
  BD->>CB: h[DECORATE_EXECUTOR_RUNNABLE].invoke(delegate, op, executor, task)<br/>via MethodHandle
  CB->>CR: decorateExecutorRunnable(op, executor, task)
  CR->>CR: idle check, then build InvocationContext
  CR->>SR: match(context)
  SR->>SC: evaluate(context) [for each controller]
  SC->>SC: 9-step pipeline
  SC-->>SR: ScenarioContribution or null
  SR-->>CR: List#lt;ScenarioContribution#gt;
  CR->>CR: merge → RuntimeDecision (DispatchEngine)
  CR->>CR: delay, gate, terminal action
  CR-->>CB: (possibly wrapped) Runnable
  CB-->>BD: Runnable
  BD->>BD: DEPTH-- (the int[] is kept)
  BD-->>A: Runnable
  A-->>T: (original method runs with wrapped task)
```

**Takeaway**: Every intercepted JDK operation crosses the bootstrap-to-agent classloader boundary via a pre-built `MethodHandle`. The full round-trip is: application thread → advice → BootstrapDispatcher → ChaosBridge → ChaosRuntime → ScenarioRegistry → ScenarioController × N → back.

## State Diagram — ScenarioController lifecycle

```mermaid
stateDiagram-v2
  [*] --> REGISTERED: activate() registers it<br/>(event REGISTERED)
  REGISTERED --> ACTIVE: start()<br/>(AUTOMATIC: inside activate()<br/>MANUAL: handle.start())<br/>event STARTED
  REGISTERED --> STOPPED: stop() / close()<br/>event STOPPED
  ACTIVE --> INACTIVE: activeFor / activeUntil passed,<br/>maxApplications spent,<br/>or the stressor ended<br/>event INACTIVE (with reason)
  ACTIVE --> STOPPED: stop() / close()<br/>event STOPPED
  INACTIVE --> STOPPED: stop() / close()<br/>event STOPPED
  STOPPED --> [*]: close() unregisters it,<br/>or an activation of the id replaces it
  INACTIVE --> [*]: an activation of the id replaces it
```

**Takeaway**: Only a `REGISTERED` controller starts; `STOPPED` and `INACTIVE` are final for the handle (L19), and `start()` on them is a no-op. `ACTIVE → INACTIVE` happens inside `evaluate()` on the calling thread when a call sees the bound passed or the budget spent, and without any call on the agent's expiry thread (`ExpiryScheduler`), which checks each time-bounded scenario at its end and ends a stressor that stopped on its own. A stop releases the gate, closes the stressor, cancels the expiry check and clears the clock skew. Activating the same id again registers a new controller that replaces the `STOPPED` or `INACTIVE` one; a duplicate id while the old one is `REGISTERED` or `ACTIVE` throws `ChaosActivationConflictException` (a `ChaosActivationException`). A failed activation registers nothing, so `ScenarioState.FAILED` is never reported.

## Deployment Diagram — classloader structure at runtime

```mermaid
flowchart TB
  subgraph g1["JVM Process"]
  subgraph g2["Bootstrap ClassLoader"]
    JDK["java.lang.*<br/>java.util.*<br/>java.net.*<br/>etc."]
    BD["BootstrapDispatcher<br/>(temp JAR injected at startup)"]
  end
  subgraph g3["Agent ClassLoader"]
    CR["ChaosRuntime"]
    SR["ScenarioRegistry"]
    CB["ChaosBridge"]
    ADV["ByteBuddy Advice Classes"]
    ST["Stressor threads"]
  end
  subgraph g4["Application ClassLoader"]
    APP["Application Code"]
  end
  JDK -.->|"advice calls (static)"| BD
  BD -.->|"MethodHandle[]"| CB
  CB -.->|"direct call"| CR
  CR -.->|"local field"| SR
  ADV -.->|"woven into"| JDK
  end
```

**Takeaway**: `BootstrapDispatcher` must be visible to JDK classes; it therefore lives in the bootstrap classloader. `ChaosRuntime` and all policy logic live in the agent classloader. The `MethodHandle[]` array is the only communication path across the classloader boundary; no class cast is performed across it.

---

# 6. Component Breakdown

## ChaosRuntime (`chaos-agent-core`)

**Responsibility**: The facade the other modules bind to. Implements `ChaosControlPlane` by delegating to `ChaosControlPlaneImpl` (registration, sessions, diagnostics, listeners), and delegates each `before*`/`after*`/`adjust*`/`decorate*` entry point the instrumentation layer calls to its domain class (`ThreadDispatch`, `ExecutorDispatch`, `NetworkDispatch`, `FileDispatch`, …; [core.md §4](core.md#dispatch-structure)). The domains build the `InvocationContext`; `DispatchEngine`, shared by all of them, checks the operation index, merges the contributions into a `RuntimeDecision` and carries it out (delay, gate, terminal action).

**Owned concerns** (across `ChaosControlPlaneImpl`, the domains and `DispatchEngine`):
- `InvocationContext` construction per operation type
- `RuntimeDecision` merge across multiple contributions (delay accumulation, precedence-based gate and terminal action selection)
- Delay execution (`Thread.sleep`)
- Gate application (`ManualGate.await`)
- Terminal action dispatch: throw, return a value, suppress, complete exceptionally, corrupt the return value, and the data-shaping kinds (cap a length, corrupt a read, abortive close, timeout, DNS answer, rewrite, redirect)
- `ScopeContext` session-ID capture
- `Instrumentation` reference for stressors needing retransformation

**Thread safety**: All public methods are thread-safe. Shared mutable state:
- `registry`: `ConcurrentHashMap`-backed, read through an immutable operation index without a lock
- `instrumentation` (in `ChaosControlPlaneImpl`): `volatile Optional`
- the open sessions and the plan observability owners: `ConcurrentHashMap`

**Why this design**: One shared engine prevents duplicate match/evaluate logic per operation, and one class per domain keeps the entry points readable. The runtime doesn't know or care about classloader boundaries — that separation is enforced by `BootstrapDispatcher` and `ChaosBridge` above it.

**Extension points**: `ChaosMetricsSink` injection at construction for custom metric forwarding (deprecated for removal since 2.0: use `chaos-agent-micrometer`, `chaos-agent-opentelemetry` or a `ChaosEventListener` on `APPLIED` events (`chaos.applications`)).

**Misuse risks**:
- A dispatch before any scenario is started returns the call unchanged (`RuntimeDecision.none()` or the idle check)
- Injecting a delay effect on `MONITOR_ENTER` or `THREAD_PARK` can cause secondary AQS activity inside `Thread.sleep()` — protected by the BootstrapDispatcher DEPTH guard
- Calling `close()` destroys and unregisters all controllers and makes the control plane reject further `activate`/`openSession`/`addEventListener` calls; it does not uninstall ByteBuddy advice

## ScenarioController (`chaos-agent-core`)

**Responsibility**: Per-scenario runtime guard. Evaluates a 9-step pipeline for every `InvocationContext` and either returns a `ScenarioContribution` or `null`.

**Evaluation pipeline**: the nine steps, their order and which of them count a match are set out in [semantics.md §3](semantics.md#3-the-policy-of-one-scenario); what each step costs is in [internals.md](internals.md#evaluation-pipeline).

**Why a reservation for maxApplications**: committed applications plus outstanding reservations never exceed the cap, so concurrent threads cannot overshoot it. The dispatcher commits the reservation (incrementing `appliedCount` and publishing `APPLIED`) only for contributions that take effect; a scenario that loses on precedence releases it and keeps its budget.

**Rate limit synchronization**: The rate window uses `synchronized(this)` rather than a lock-free structure. This is a deliberate simplicity trade-off: rate-limited scenarios are not expected to be on ultra-hot paths where this contention would matter. The lock scope is minimal (a few nanoseconds).

## ScenarioRegistry (`chaos-agent-core`)

**Responsibility**: Thread-safe store of `ScenarioController` instances; implements `ChaosDiagnostics`.

**Operation index**: Besides the `ConcurrentHashMap` of controllers, the registry keeps an immutable `OperationType → ScenarioController[]` index (built from `SelectorOperations.of(selector)`), each array pre-sorted by match order. The index is rebuilt and swapped on every register/unregister.

**Match path**: `index.get(context.operationType())`, then `evaluate()` on each indexed controller, collecting non-null contributions — only scenarios whose selector names the intercepted operation are evaluated, and nothing is sorted per call. This is the hot path. With N scenarios naming the operation, it is O(N) per intercepted method call; scenarios for other operations cost nothing. Clock-skew reads use the same index and return immediately when no scenario names the clock operation.

**Ordering guarantee**: Contributions keep the index order: descending `precedence`, then ascending `id`, then the scope key (`jvm` before `session:<id>`). This is deterministic and stable, so tests can rely on predictable effect selection when multiple scenarios match: an equal-precedence tie goes to the smallest id.

**Failure recording**: a bounded `ArrayDeque<ActivationFailure>` (the 256 most recent, guarded by itself) accumulates activation errors (validation failures, duplicate registration, weaving failures, etc.). These are surfaced in `snapshot()` for operator inspection.

## SelectorMatcher (`chaos-agent-core`)

**Responsibility**: Stateless evaluation of a `ChaosSelector` against an `InvocationContext`. Uses an exhaustive `switch` over the sealed `ChaosSelector` hierarchy.

**Pattern**: Each selector variant carries its own matching predicates (class name patterns, operation type sets). The switch ensures compile-time exhaustiveness — adding a new selector subtype forces a new case here.

**Thread safety**: Stateless; all methods are static. May be called concurrently from arbitrarily many threads.

## BootstrapDispatcher (`chaos-agent-instrumentation-jdk`)

**Responsibility**: Bootstrap-classloader-resident static dispatcher. Provides the only callable surface visible to advice woven into JDK methods.

**Two-field volatile publication**: `handles` is written before `delegate` so that any thread that snapshot-reads a non-null `delegate` is guaranteed to also see a non-null `handles` array. Both fields are `volatile`. This is the minimal safe publication protocol for a two-field pair without synchronization.

**Reentrancy guard**: `ThreadLocal<int[]> DEPTH` (a one-element `int[]` is held instead of `Integer` so the counter is read once per call and mutated in place, avoiding a second `ThreadLocal.get()` and autoboxing on every intercepted JDK call). Checked, incremented and decremented (in `finally`) inline in every dispatch method — there is no shared trampoline, so the dispatch path allocates nothing per call. When `DEPTH > 0`, any nested dispatch (e.g., chaos code calling `Thread.sleep()`, which is instrumented for `THREAD_PARK`) returns the fallback immediately. The `int[]` is kept when DEPTH returns to zero: it also holds the call-depth scope counters that make nested or delegating instrumented calls evaluate once, at the outermost call. Its footprint is one small array per thread, released with the thread.

**Special case — ThreadLocal advice**: `ThreadLocalGetAdvice` and `ThreadLocalSetAdvice` include an identity check: `if (BootstrapDispatcher.isDepthThreadLocal(threadLocal)) return false`. Without this check, the advice on `ThreadLocal.get()` would fire when `DEPTH.get()` is called inside a dispatch method, creating an infinite recursion that the DEPTH guard cannot prevent (the DEPTH read is itself a `ThreadLocal.get()`).

**Sneaky throw**: The `sneakyThrow` helper rethrows any `Throwable` without checked-exception declarations by exploiting generic type erasure at the JVM level. This allows advice methods declared `throws Throwable` to propagate exceptions through the dispatch methods without wrapping.

## JdkInstrumentationInstaller (`chaos-agent-instrumentation-jdk`)

**Responsibility**: Assembles the ByteBuddy transformations, grouped into `HookGroup`s, and weaves each group on demand (via the `HookDemand` callback it registers on the runtime) or all at once in `eager` mode. Manages the bridge injection lifecycle. Woven groups are reported as `wovenHookGroups` in `ChaosDiagnostics.snapshot().runtimeDetails()`.

**Bootstrap bridge injection**: Reads `BootstrapDispatcher.class` and the types woven advice references (the scheduled-task wrappers, the API exceptions it throws) from the agent JAR's resources and writes them into a temp JAR in `bridgeDir` when it is set (a trusted directory, or the start is aborted), else in `java.io.tmpdir`, then `/dev/shm` when the temp directory is read-only. The temp JAR (created with mode 0600 on POSIX) is appended to the bootstrap classpath via `Instrumentation.appendToBootstrapClassLoaderSearch`. On POSIX the file is unlinked immediately afterwards (the class path entry keeps its own handle), so nothing is left in the temp directory even after `kill -9`; on Windows, where the open jar cannot be deleted, it stays until the next start sweeps stale jars from earlier JVMs (only the JVM user's own on POSIX). Injection happens once per JVM.

**MethodHandle array construction** (`buildMethodHandles`): Uses `MethodHandles.publicLookup()` against `BridgeDelegate.class` to build `BootstrapDispatcher.HANDLE_COUNT` handles (78). All handles are unbound virtual handles resolved against the interface, not the implementation, so the bootstrap classloader can invoke them without visibility into `ChaosBridge`; each dispatch call supplies the delegate instance as the first argument. The handle array is passed to `BootstrapDispatcher.install()` via reflection (`Class.forName(..., null)` with bootstrap classloader).

**Hook groups**: every interception point belongs to one of 41 `HookGroup`s (`THREAD_START`, `THREAD_POOL_EXECUTOR`, `EXECUTOR_SUBMIT`, `SCHEDULED_EXECUTOR`, `FORK_JOIN`, `BLOCKING_QUEUE`, `COMPLETABLE_FUTURE`, `COMPLETABLE_FUTURE_CANCEL`, `SHUTDOWN_HOOK`, `CLASS_LOADER`, `CLASS_DEFINE`, `JDK_INTERNAL_CLASS_LOAD`, `RUNTIME_LIFECYCLE`, `REFLECTION_INVOKE`, `DIRECT_BUFFER`, `SERIALIZATION`, `THREAD_PARK`, `MONITOR_ENTER`, `NIO`, `SOCKET`, `DATAGRAM`, `SOCKET_CHANNEL`, `ZIP`, `THREAD_LOCAL`, `CLOCK_CALL_SITES`, `TIME_API`, `SSL_HANDSHAKE`, `KAFKA`, `GRPC`, `REDIS`, `R2DBC`, `AWS_SDK`, `NETTY`, `HTTP_CLIENT`, `JDBC`, `JNDI_JMX`, `NATIVE_LIBRARY`, `THREAD_SLEEP`, `DNS`, `PROCESS`, `FILE_IO`; their targets are in [instrumentation.md §5](instrumentation.md#interception-map)) listing the `OperationType`s it serves; method hooks (`METHODS`) are woven per `MethodSelector`. When a scenario is activated (or a session opened, which needs `THREAD_START`, `EXECUTOR_SUBMIT`, `SCHEDULE_SUBMIT`, `FORK_JOIN_TASK_RUN` and `HTTP_CLIENT_SEND`, so it weaves the six groups serving them), the control plane calls `HookDemand.require(operations)` and the installer weaves the groups not yet woven, once each, each with its own `ResettableClassFileTransformer`. Woven groups stay woven until uninstall. A weaving failure fails the activation with failure category `INSTRUMENTATION_FAILURE`. Every group uses retransformation (`Can-Retransform-Classes: true`), so premain, agentmain and self-attach get the same hook set.

**AQS instrumentation safety**: `MONITOR_ENTER` is hooked at `AbstractQueuedSynchronizer.acquire(int)` only. `ConcurrentHashMap` (used by `ScenarioRegistry`) does not use AQS for reads. `ManualGate` waits on a `CountDownLatch` (AQS shared acquire, not `acquire(int)`), and it is only reached from `applyGate()`, inside a dispatch where `DEPTH` is already > 0 — so the DEPTH guard short-circuits before re-entering chaos evaluation in any case.

**Clock intrinsic caveat**: `System.currentTimeMillis()` and `System.nanoTime()` are `@IntrinsicCandidate` native methods: there is no bytecode body to weave advice into, and the JIT turns calls to them into direct clock reads. Clock skew works without configuration for `java.time.Instant.now()`, `LocalDateTime.now()`, `ZonedDateTime.now()` and `new java.util.Date()` (non-native Java methods woven at exit, slots 41–44 and 56 of the handle array) and for the system `Clock`'s `instant()` / `millis()` (`CLOCK_READ`, slots 71 and 72). Direct calls to `System.currentTimeMillis()` / `nanoTime()` are skewed only at their call sites in the packages named with the `clockCallSites` agent setting, which rewrites those calls (`SYSTEM_CLOCK_MILLIS` / `SYSTEM_CLOCK_NANOS`; JDK packages are refused); elsewhere they stay beyond reach ([internals.md](internals.md#bytebuddy-advice-and-the-jvm-retransformation-mechanism)).

## Stressors (`chaos-agent-core`)

Stressors implement `ManagedStressor extends AutoCloseable`. Each starts one or more background threads or performs background JVM operations for the duration of the scenario.

| Stressor                              | Mechanism                                                                                                            | Close behavior                                                                                                          |
|---------------------------------------|----------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------|
| `HeapPressureStressor`                | Retains `byte[]` allocations in a list, allocated in the constructor                                                 | Clears the list; the GC reclaims it                                                                                     |
| `KeepAliveStressor`                   | Starts one named thread (daemon or not, per `daemon`) in a `Thread.sleep` heartbeat loop                             | Sets a stop flag and interrupts the thread                                                                              |
| `MetaspacePressureStressor`           | Defines synthetic classes in its own class loader                                                                    | Drops the loader; a later collection reclaims the Metaspace (with `retain=false` the loader is dropped right after definition) |
| `DirectBufferPressureStressor`        | Allocates `ByteBuffer.allocateDirect` slabs (with `registerCleaner=true`: allocated and dropped)                     | Frees the held buffers through their cleaner, else drops them to the GC                                                 |
| `GcPressureStressor`                  | Allocates `byte[]` at the configured rate on a daemon thread, for `duration`                                         | Interrupts the thread                                                                                                   |
| `FinalizerBacklogStressor`            | Creates objects with slow `finalize()` methods, unreachable at once                                                  | Wakes the finalizer sleeping; the queued ones return without sleeping                                                   |
| `DeadlockStressor`                    | N threads each hold `ReentrantLock` i and wait in `lockInterruptibly()` for lock (i+1) mod N                         | Interrupts the threads; they leave `lockInterruptibly()` and release their locks                                        |
| `ThreadLeakStressor`                  | Starts N threads (daemon or not) parked in a `LockSupport.park()` loop, optionally for a `lifespan`                  | Interrupts the threads and waits briefly for them                                                                       |
| `ThreadLocalLeakStressor`             | Plants `ThreadLocal` entries of `valueSizeBytes` in the common `ForkJoinPool`'s workers                             | Empties the planted entries                                                                                             |
| `MonitorContentionStressor`           | N threads repeatedly take one shared `ReentrantLock` (fair or not) and hold it                                      | Sets a stop flag and interrupts the contenders                                                                          |
| `CodeCachePressureStressor`           | Defines synthetic classes in isolated loaders and calls their methods so the JIT compiles them                      | Drops the references; the code cache is freed when the JIT unloads the methods                                          |
| `SafepointStormStressor`              | Calls `System.gc()` (and, with an `Instrumentation` and `retransformClassCount > 0`, `retransformClasses`) on a timer | Interrupts the timer thread                                                                                             |
| `StringInternPressureStressor`        | Interns distinct strings and holds them                                                                              | Drops the references; the next collection removes the strings from the table                                            |
| `ReferenceQueueFloodStressor`         | Floods the reference handler with `WeakReference`s to garbage, then collects, every `floodInterval`                 | Stops the thread and drains what is left                                                                                |
| `VirtualThreadCarrierPinningStressor` | N virtual threads hold their carriers: monitor pinning on JDK 21–23, spin-wait on JDK 24+ (one CPU per held carrier) | Stops and joins the virtual threads                                                                                     |

**Critical operational note**: `DeadlockStressor` and `ThreadLeakStressor` need `allowDestructiveEffects`. `stop()`/`close()` ends their threads (they are interrupted and leave the lock wait or the park loop), but until then the deadlocked or leaked threads hold their locks and memory, and leaked non-daemon threads keep the JVM from exiting. Use them in short-lived test processes or in scenarios that verify deadlock or leak detection.

---

# 7. Data Model and State

## InvocationContext

```java
record InvocationContext(
    OperationType operationType,       // the JDK surface being intercepted
    @Nullable String targetClassName,  // primary class (executor, queue, socket, etc.)
    @Nullable String subjectClassName, // secondary class (task class, payload class)
    @Nullable String targetName,       // method name, thread name, host, class name, URL, SQL
    boolean periodic,                  // for scheduled operations
    @Nullable Boolean daemonThread,    // thread metadata
    @Nullable Boolean virtualThread,   // thread metadata
    @Nullable String sessionId,        // current session ID or null for JVM scope
    long byteCount,                    // bytes of a read or write, -1 when none
    @Nullable String path,             // file path
    @Nullable InetAddress remoteAddress, // resolved peer
    int remotePort,                    // peer port, -1 when none
    @Nullable String signature,        // method descriptor (method selectors)
    int timeoutMillis                  // the call's own timeout, -1 when none
)
```

Built fresh per intercepted call that passes the idle check (some scenario is indexed under the operation). Not pooled. Allocation pressure on hot paths is a known trade-off of this design; it is proportional to the calls of operations that have a scenario, not the total invocation rate.

## ScenarioContribution

```java
record ScenarioContribution(
    ScenarioController controller,
    ChaosScenario scenario,
    ChaosEffect effect,
    long delayNanos,
    @Nullable Duration gateTimeout,
    InvocationContext context,
    long matchNumber,         // the 1-based match, for APPLIED's `match` and the effects' seeds
    long ratePermitWindow     // the rate-limit window its permit came from
)
```

Immutable value returned by `ScenarioController.evaluate()` when the scenario passes all nine steps. Carries the sampled delay and gate timeout so the runtime can execute them without calling back into the controller, and holds a `maxApplications` reservation that `commit()` turns into an application or `release()` returns.

## RuntimeDecision

```java
record RuntimeDecision(
    long delayNanos,                          // accumulated sum across all matching contributions
    @Nullable GateAction gateAction,          // the first gate in match order (only one gate blocks)
    @Nullable TerminalAction terminalAction   // the first terminal action in match order
)
```

Delay is additive: if three matching scenarios each contribute 100 ms, the total delay is 300 ms. Gates and terminal actions are winner-take-all: the first in match order wins, which is the highest precedence and, on a tie, the smallest scenario id (deterministic: the `ScenarioRegistry` operation index keeps each operation's controllers pre-sorted by precedence descending, then id ascending).

## TerminalAction Semantics by Operation Type

The same logical effect (e.g., `SuppressEffect`) produces different terminal actions depending on the operation type:

| Effect   | Operation Type                                   | Terminal behavior                                       |
|----------|--------------------------------------------------|---------------------------------------------------------|
| Suppress | THREAD_START, VIRTUAL_THREAD_START               | THROW `RejectedExecutionException`                      |
| Suppress | SYSTEM_EXIT_REQUEST                              | THROW `SecurityException`                               |
| Suppress | QUEUE_OFFER, ASYNC_COMPLETE(_EXCEPTIONALLY)      | RETURN `false`                                          |
| Suppress | QUEUE_POLL                                       | RETURN (the poll is skipped and returns `null`)         |
| Suppress | RESOURCE_LOAD                                    | RETURN `null`                                           |
| Suppress | (default: executor decoration, selector select, etc.) | SUPPRESS (the call is skipped or its task replaced) |
| Reject   | QUEUE_OFFER, ASYNC_COMPLETE(_EXCEPTIONALLY)      | RETURN `false`                                          |
| Reject   | RESOURCE_LOAD                                    | RETURN `null`                                           |
| Reject   | (default)                                        | THROW operation-specific exception via `FailureFactory` |

This per-operation specialization is necessary because operations have different contracts for indicating failure: `BlockingQueue.offer()` returns `false` for rejection, while `Thread.start()` throws. Mapping a uniform `SuppressEffect` to operation-specific behavior is centralized in `DispatchEngine.suppressTerminal()` and `rejectTerminal()`. Which operations accept a suppress or a reject at all is decided at activation by `CompatibilityValidator` ([configuration-reference.md](configuration-reference.md)).

## Scenario State Transitions

| Transition                | Trigger                                       | Thread that executes it                                                                             |
|---------------------------|-----------------------------------------------|-----------------------------------------------------------------------------------------------------|
| REGISTERED → ACTIVE       | `ScenarioController.start()`                  | Thread calling `handle.start()` or `activate()` with AUTOMATIC start mode                           |
| ACTIVE → INACTIVE         | `activeFor` / `activeUntil` passed            | Application thread whose `evaluate()` sees it, or the agent's expiry thread at the bound (whichever comes first) |
| ACTIVE → INACTIVE         | `maxApplications` reached (on the commit that spends it) | Application thread triggering the interception                                          |
| ACTIVE → INACTIVE         | a stressor ended on its own (thread failed, `duration` or `lifespan` elapsed) | Agent expiry thread                                                 |
| REGISTERED/ACTIVE/INACTIVE → STOPPED | `stop()` or `destroy()`            | Thread calling `handle.stop()`, `handle.close()` or `controlPlane.close()` (close also unregisters) |

**One agent-owned expiry thread** (`ExpiryScheduler`, a daemon shared by the JVM) checks each time-bounded scenario at its end, so a scenario ends without any call; `state()`, the diagnostics and a re-activation of the id apply a passed bound first.

---

# 8. Concurrency and Threading Model

## Execution Model

The hot path (evaluate + decision) executes entirely on the application thread that triggered the instrumented JDK call. The agent's own threads are few and daemon: the expiry thread (`ExpiryScheduler`, started by the first time-bounded scenario or stressor), the threads of a configured stressor, the plan-file poller (`configWatchInterval`), the reports writer (`reports=on`) and, after `premain`, the thread that registers the MBean once `java.util.logging` is initialised.

## State Ownership

| State                                                           | Owner            | Concurrency primitive                            |
|-----------------------------------------------------------------|------------------|--------------------------------------------------|
| `ScenarioController.started`                                    | Per-controller   | `AtomicBoolean`                                  |
| `ScenarioController.matchedCount`                               | Per-controller   | `AtomicLong`                                     |
| `ScenarioController.appliedCount` / `reservedApplications`      | Per-controller   | `AtomicLong`; with `maxApplications`, both under `applicationBudgetLock` |
| `ScenarioController.state` / `reason`                           | Per-controller   | `volatile` field                                 |
| `ScenarioController.rateWindowStartMillis`, `rateWindowPermits` | Per-controller   | `synchronized(this)`                             |
| `ScenarioController.stressor` / `clockSkewState` / `expiry`     | Per-controller   | `volatile` field, written under `synchronized(this)` |
| `ScenarioRegistry.controllers`                                  | Registry         | `ConcurrentHashMap` (lock-free read)             |
| `ScenarioRegistry.index`                                        | Registry         | `volatile` immutable map, swapped on change      |
| `ScenarioRegistry.failures`                                     | Registry         | bounded `ArrayDeque`, guarded by itself          |
| `ChaosControlPlaneImpl.instrumentation`                         | Control plane    | `volatile Optional`                              |
| `ScopeContext.currentSessionId()`                               | Per-thread       | `ThreadLocal<Deque<String>>` (a `ConcurrentLinkedDeque` per thread) |
| `BootstrapDispatcher.handles` / `delegate`                      | Static, JVM-wide | `volatile` (two-field safe publication protocol) |
| `BootstrapDispatcher.DEPTH`                                     | Per-thread       | `ThreadLocal<int[]>`                             |

## Visibility and Publication Guarantees

- **`BootstrapDispatcher.install()`**: `handles` is written before `delegate`. A reader that observes `delegate != null` is guaranteed to observe `handles != null` due to the happens-before established by `volatile` writes/reads (Reference: JSR-133 — Java Memory Model §17.4.5).
- **`ScenarioController.start()`**: in two phases — `state = ACTIVE` under the monitor, then `STARTED` is published, then `started.set(true)` (and the clock skew and the expiry check) under the monitor again. Reads of `started.get()` in `evaluate()` either see `true` (after the write) or `false` (safe fallback, returns null), so no call is matched before `STARTED` is delivered.
- **`ScenarioController.stop()`**: under the monitor, sets `started.set(false)` and `STOPPED`, then releases the gate (`CountDownLatch.countDown()`, which happens-before the return of every `await()` it releases), closes the stressor and cancels the expiry check; a thread released from `gate.await()` observes the stopped state.

## JMM Relevance

The rate-limit synchronized block (`synchronized(this)`) establishes full happens-before between the last write and the next read of `rateWindowStartMillis` / `rateWindowPermits`. Without this synchronization, two threads could both see an empty window and both issue permits, violating the rate limit.

The probability sampling creates a new `SplittableRandom` per call to avoid concurrent-access issues. `SplittableRandom` is not thread-safe (Reference: Java SE API, `SplittableRandom`).

## Virtual Thread Awareness

`FeatureSet.isVirtualThread(Thread)` calls `Thread.isVirtual()` directly (the agent needs JDK 21). Virtual threads park on `LockSupport.park()`, which is instrumented; the DEPTH guard ensures the chaos agent's own internal parking does not trigger secondary chaos.

---

# 9. Error Handling and Failure Modes

## Scenario Activation Failures

Activation errors are caught in `ChaosControlPlaneImpl.registerScenario()` and classified; the scenario is unregistered again, the failure recorded once and published as a `FAILED` event, and the original exception rethrown (L21):

| Exception type                                                         | Category                  |
|------------------------------------------------------------------------|---------------------------|
| `ChaosUnsupportedFeatureException`                                     | `UNSUPPORTED_RUNTIME`     |
| `ChaosInstrumentationException` (or any failure while weaving its hooks) | `INSTRUMENTATION_FAILURE` |
| `ChaosActivationConflictException` (the id is `REGISTERED` or `ACTIVE`) | `ACTIVATION_CONFLICT`     |
| `ChaosValidationException`, a scope refusal (`ChaosActivationException`), any other `RuntimeException` | `INVALID_CONFIGURATION` |
| an `Error` (a `LinkageError` from a stressor or a listener, for instance) | `INTERNAL_ERROR`        |

All activation failures are surfaced in `ChaosDiagnostics.snapshot().failures()` (the 256 most recent).

## Hot-Path Failures

- **Delay interrupted**: if the injected `Thread.sleep` is interrupted, the interrupt flag is restored and the delay ends; the call proceeds, and the intercepted method observes the interrupt as it would without chaos.
- **Gate wait interrupted**: `ManualGate.await()` waits on a `CountDownLatch`; an interrupt ends the wait, `applyGate()` restores the flag and the call proceeds (L15). A gate also ends at its `maxBlock`, and the call then proceeds.
- **Exception injection**: the class an `ExceptionInjectionEffect` names is resolved at activation, through the agent's class loader; a class that cannot be found or built fails the activation with `ChaosValidationException`, so the woven method never throws anything other than the configured exception.

## Registry Cleanup

- `session.close()` unregisters all session-scoped controllers from `ScenarioRegistry` and stops them.
- JVM-scoped scenarios stay registered until `handle.close()` (stop + unregister) or `controlPlane.close()` is called. `handle.stop()` alone leaves a `STOPPED` entry, which a later activation of the same id replaces.
- **JVM-scoped scenarios survive `session.close()`**: this is by design.

## Stressor Failure Modes

- Stressor creation failures are not swallowed: they propagate out of `activate()` (an `OutOfMemoryError`, also a thread the JVM cannot start, as `ChaosUnsupportedFeatureException`). `MetaspacePressureStressor` closes its partially-filled loader and rethrows if class definition fails (e.g. metaspace already exhausted).
- `activeFor` and `activeUntil` end a stressor: it is closed and the scenario becomes `INACTIVE` with reason `"expired"`. A stressor that stops on its own ends its scenario too (`stressor failed: <type>`, `duration elapsed`, `lifespan elapsed`, L17). `activeFrom`, `maxApplications`, `rateLimit`, `activateAfterMatches`, `probability < 1` and `replayMatches` are rejected for stress scenarios.
- `CodeCachePressureStressor` generates classes without a target application method; classes are immediately eligible for GC if the code cache does not retain them.
- `SafepointStormStressor` calls `Instrumentation.retransformClasses()` on its timer thread when `retransformClassCount > 0`, through the `Instrumentation` the agent hands the runtime at every start (`-javaagent`, dynamic attach, self-attach). Only a runtime created without the agent (`new ChaosRuntime()`) has none; its storm skips the retransformation step and still triggers `System.gc()`.

## Effect on JVM Stability

The following effects are inherently destabilizing and should only be used in controlled test environments:

- `DeadlockStressor`: creates a real deadlock between its own threads; they hold their locks until the scenario is stopped, which interrupts them
- `ThreadLeakStressor`: grows the JVM thread count by `threadCount`; the threads stay until the scenario is stopped or their `lifespan` elapses
- `HeapPressureStressor` + `DirectBufferPressureStressor`: retain GC roots; can cause OOM if retention exceeds available memory
- `MetaspacePressureStressor`: fills metaspace; can cause `OutOfMemoryError: Metaspace`

## Destructive Effects Safeguard

`DeadlockEffect` and `ThreadLeakEffect` require an explicit opt-in flag in `ActivationPolicy`. Any attempt to activate a scenario using either effect without `allowDestructiveEffects = true` throws `ChaosValidationException` at registration time — before any stressor thread or lock is created.

This is enforced by `CompatibilityValidator.validateDestructiveEffectGuard()`. It is a correctness guard, not a security boundary: the calling code is trusted. The guard prevents accidental activation in scenarios that were copied from test suites into long-running processes without adjusting the policy.

```java
// Required for DeadlockEffect and ThreadLeakEffect
ActivationPolicy.withDestructiveEffects()

// The JSON plan equivalent
"activationPolicy": { "allowDestructiveEffects": true }
```

---

# 10. Security Model

## Threat Surface

The agent is not a security boundary. It operates with full JVM privileges and is designed for use in test environments. The following is an analysis for operators who deploy it in controlled production-like environments.

## Trust Model

- **Configuration at startup**: Agent args and environment variables are read without sanitization beyond type validation and path safety. A hostile actor with access to JVM arguments or environment can inject arbitrary chaos scenarios.
- **Enabling switches**: plan authors are trusted; a switch that enables injection or a management endpoint is honoured only from a launch-time local source (system properties, environment variables, the command line, the application's own config files, test properties), unless `macstab.chaos.allow-remote-enable=true` itself comes from one. A remote config server cannot switch chaos on. Booleans accept `true|false|1|0|yes|no|on|off`. The endpoints are authenticated. The sources per framework: [configuration-reference §10.1](configuration-reference.md#101-configuration-trust).
- **Programmatic activation**: `ChaosControlPlane` has no authentication or authorization. Any code in the JVM that can obtain the control plane reference can activate arbitrary chaos.
- **File path security** (in `StartupConfigLoader`): paths are normalized (resolves `..`), symlinks are rejected (except the Kubernetes ConfigMap layout `<name>` → `..data/<name>`, whose real file is then checked and read) via `LinkOption.NOFOLLOW_LINKS`, non-regular files are rejected, and files larger than 1 MiB are rejected. This prevents directory traversal and some forms of TOCTOU attacks on the resolved path.
- **`System.exit()` suppression**: A `SuppressEffect` on `SYSTEM_EXIT_REQUEST` throws `SecurityException` from `System.exit` / `Runtime.exit` / `Runtime.halt`. This can interfere with legitimate shutdown flows if misconfigured in a production environment. Use only in test scenarios that explicitly verify exit-path behavior.
- **Serialization injection**: `beforeObjectDeserialize()` fires before `ObjectInputStream.readObject()`. A `RejectEffect` here throws a synthetic `InvalidClassException`. If applied to a production stream, it will corrupt deserialization. Do not activate on JVM-scoped scenarios targeting all `ObjectInputStream` instances in production.

## No Sensitive Data Handling

The agent does not transmit or log sensitive data. `InvocationContext` fields are class names, method names, and session IDs — all developer-assigned identifiers. The observability bus (`ObservabilityBus`) publishes to `ChaosEventListener` instances; log handlers should not log `payload` fields from real-data objects.

## Input Validation

- Agent arg parsing (`AgentArgsParser`): semicolon-separated `key=value` pairs (max 8192 characters); duplicate keys resolved as last-wins. No shell injection risk (parsing is pure Java string splitting, not shell evaluation).
- JSON config (`ChaosPlanMapper`): Jackson with strict unknown-fields policy. Unknown fields in the JSON cause `ConfigLoadException`. This prevents silent partial config application.
- Selector patterns: `ChaosSelector.*Pattern` fields are applied via substring match or regex; malformed patterns throw at construction time, failing fast before scenarios are activated.

---

# 11. Performance Model

## Hot Path Costs

Per intercepted JDK call that has at least one active matching scenario:

| Operation                                               | Cost estimate                                                                                                                 |
|---------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------|
| `ScopeContext.currentSessionId()`                       | 1 `ThreadLocal.get()` — nanoseconds (with `DEPTH` identity check overhead on ThreadLocal instrumentation)                     |
| `InvocationContext` allocation                          | 1 record allocation, ~60–80 bytes on-heap                                                                                     |
| `ScenarioRegistry.match()`                              | Index lookup + N `ScenarioController.evaluate()` calls (N = scenarios naming the operation); no per-call sort                 |
| Per-`evaluate()`: started + sessionId + SelectorMatcher | Lock-free reads; sub-microsecond for typical selectors                                                                        |
| Per-`evaluate()`: rate limit                            | `synchronized(this)` — contended only if N threads are hitting the same scenario at the same time; typically nanosecond-range |
| Per-`evaluate()`: probability                           | `new SplittableRandom(...).nextDouble()` — allocation + compute; ~50 ns                                                       |
| `Thread.sleep` (the summed delay)                        | Actual blocking delay per scenario (intentional)                                                                              |

## Zero-Scenario Fast Path

When no registered scenario names the intercepted operation (in particular when none is registered), the dispatch's idle check (`DispatchEngine.idle`, one read of the operation index) returns before any `InvocationContext` is built, without allocating. With no scenario and no session at all, hook groups are not even woven (on-demand weaving), so the JDK methods run unmodified.

When `DEPTH > 0` in a `BootstrapDispatcher` dispatch method, the fallback is returned before any MethodHandle invocation — pure `ThreadLocal.get()` + integer comparison.

## Memory Footprint

- `ScenarioController`: ~200 bytes per instance (fields, AtomicLong, AtomicBoolean, volatile pointers)
- `ScenarioRegistry`: `ConcurrentHashMap` overhead proportional to N registered controllers
- `BootstrapDispatcher.handles`: 78 `MethodHandle` references, one array — ~330 bytes
- `DEPTH ThreadLocal`: one small `int[]` per thread that reached an instrumented call, kept for the thread's lifetime (it also holds call-depth scope counters)

## Bottlenecks

- **Stressor threads** are the dominant resource consumers when active; see Section 6 stressor table for specifics
- **Gate blocking** (`ManualGate.await()`) blocks the calling thread indefinitely until `release()` is called; unconstrained gate effects can exhaust thread pools
- **Delay accumulation** is additive; N overlapping delay scenarios each contributing D ms results in N×D ms per intercepted call — this is intentional but can compound unexpectedly

## Scaling Characteristics

- N active scenarios: `match()` cost is O(N) per intercepted call, where N counts only the scenarios whose selector names the intercepted operation (operation-type index). Practical upper bound is ~20–30 scenarios on the same operation before the scan overhead becomes observable (microseconds per call).
- P concurrent threads: no global lock on the hot path; the immutable operation index is read with one `volatile` read; `AtomicLong` / CAS are contention-free under normal loads. The only contention points are the per-controller rate-limit and `maxApplications` locks.

---

# 12. Observability and Operations

## Logs

The agent uses `java.util.logging` (`java.util.logging.Logger`). Log output goes to the JVM's default log destination. Key log entries:

- `FINE`: Optional instrumentation target not present (e.g., `javax.naming.InitialContext` absent)
- `WARNING`: ByteBuddy transformation failure for a specific type (class name + throwable)
- (No log output on the hot path to avoid impacting latency measurements)

## Metrics

`ChaosMetricsSink` interface, deprecated for removal since 2.0: use `chaos-agent-micrometer`, `chaos-agent-opentelemetry` or a `ChaosEventListener` on `APPLIED` events (`chaos.applications`). Default: `NOOP`. Custom sinks can be injected at `ChaosRuntime` construction. The sole metric currently emitted is `chaos.effect.applied` with tags `scenarioId` and `operation`. The metrics modules are described in [`observability.md`](observability.md).

## Events

`ChaosEventListener` receives:
- `STARTED`: scenario started
- `STOPPED`: scenario stopped (once per transition)
- `INACTIVE`: scenario ended on its own, with `reason` `expired` or `max applications reached`
- `RELEASED`: gate manually released
- `APPLIED`: a contribution took effect — every matching delay, the winning gate, the winning terminal action (on the hot path — listeners must be non-blocking)

`REGISTERED` (before a scenario starts), `SKIPPED` (the policy kept a matched effect; only while a listener is registered) and `FAILED` (an activation failed) complete the set; [observability.md §2](observability.md#2-events) lists their attributes.

Register via `controlPlane.addEventListener(listener)`. Listeners execute synchronously on the calling thread inside `ObservabilityBus.publish()`; a listener's exception is caught and logged, never thrown into the application. **Do not perform I/O or blocking operations in a plain listener**: wrap it with `AsyncChaosEventListener.async(listener)` to have it called on an agent-owned thread through a bounded queue (events are dropped, and counted, when it falls behind).

## Diagnostics API

```java
ChaosDiagnostics diag = controlPlane.diagnostics();

// Full snapshot
Snapshot snap = diag.snapshot();
snap.capturedAt();                    // Instant
snap.scenarios();                     // List<ScenarioReport> — id, state, matchedCount, appliedCount, reason
snap.failures();                      // List<ActivationFailure> — activation-time errors
snap.runtimeDetails();                // Map<String, String> — JVM version, woven hook groups, event drops, current session, …

// Single scenario lookup
Optional<ScenarioReport> report = diag.scenario("my-scenario-id");

// Human-readable dump
String dump = diag.debugDump();  // multi-line text, suitable for logs
```

## JMX

MBean registered at: `com.macstab.chaos.jvm:type=ChaosDiagnostics`

Operations `debugDump()` and `listScenarios()` and the attribute `ControlEnabled` inspect agent state from `jconsole` or `jmxterm` without application code changes. The control operations `activatePlan(planJson)`, `stopScenario(id)` and `stopAll()` refuse unless the agent was started with `jmxControl=true` (`-Dmacstab.chaos.jmx.control=true`, `MACSTAB_CHAOS_JMX_CONTROL=true`).

## Debugging Workflow

1. **Check registration**: `diag.snapshot().scenarios()` — verify scenario is in ACTIVE state
2. **Check counters**: `matchedCount > 0` means the selector is hitting; `appliedCount == 0` means all activation policy checks are failing
3. **Check reason**: `ScenarioReport.reason()` — e.g., `"expired"`, `"max applications reached"`, `"stopped"`, `"rate limited"` (note: reason is last-observed reason, not the reason for zero applied count specifically)
4. **Check failures**: `diag.snapshot().failures()` — validation or registration errors at activation time
5. **Enable startup dump**: `debugDumpOnStart=true` agent arg — dumps full state to stderr immediately after plan activation

## Operational Signals to Monitor

- `ScenarioReport.state == ACTIVE` with `appliedCount > 0` → scenario is actively injecting chaos
- `appliedCount == matchedCount` → every match applies (probability=1.0, no rate limit, no warm-up)
- `appliedCount == 0 && matchedCount > 0` → activation policy is filtering; check reason
- `state == INACTIVE` with reason `"expired"` → `activeFor` window has passed
- `state == INACTIVE` with reason `"max applications reached"` → scenario has exhausted its cap

---

# 13. Configuration Reference

## Agent Argument Syntax

```
-javaagent:chaos-agent-bootstrap.jar[=key1=value1;key2=value2;...]
```

Semicolon-separated `key=value` pairs (quote the argument in a shell). `\;`, `\=` and `\\` are escapes; any other backslash is kept as written, so Windows paths and JSON escapes work. A key without `=` is a boolean flag set to `true`. Duplicate keys: last value wins.

## Startup Arguments

| Key                   | Default   | Description                                                                  |
|-----------------------|-----------|------------------------------------------------------------------------------|
| `configFile`          | none      | Absolute or relative path to a JSON plan file. Symlinks rejected. Max 1 MiB. |
| `configJson`          | none      | Inline JSON string of the plan.                                              |
| `configBase64`        | none      | Base64-encoded JSON plan. Standard or URL-safe Base64; whitespace ignored.   |
| `debugDumpOnStart`    | `false`   | Print diagnostic dump to stderr after plan activation. Alias: `debugDump`.   |
| `configWatchInterval` | `0` (off) | Poll `configFile` every N ms and reload it atomically. Minimum 50 ms.        |
| `instrumentation`     | on demand | `eager` weaves every hook group at start (1.x); anything else = on demand.   |

## Environment Variables

| Variable                            | Equivalent arg        | Description                                                |
|-------------------------------------|-----------------------|------------------------------------------------------------|
| `MACSTAB_CHAOS_CONFIG_FILE`         | `configFile`          | Path to JSON plan file. Agent arg takes precedence.        |
| `MACSTAB_CHAOS_CONFIG_JSON`         | `configJson`          | Inline JSON. Agent arg takes precedence.                   |
| `MACSTAB_CHAOS_CONFIG_BASE64`       | `configBase64`        | Base64 JSON. Agent arg takes precedence.                   |
| `MACSTAB_CHAOS_DEBUG_DUMP_ON_START` | `debugDumpOnStart`    | `true`/`1`/`yes`/`on` to enable. Either source enables it. |
| `MACSTAB_CHAOS_CONFIG_WATCH_INTERVAL` | `configWatchInterval` | Poll interval in ms. Agent arg takes precedence. Alias: `MACSTAB_CHAOS_WATCH_INTERVAL`. |
| `MACSTAB_CHAOS_INSTRUMENTATION`     | `instrumentation`     | `eager` = weave all hooks at start. Arg > sysprop > env.   |
| `MACSTAB_CHAOS_BRIDGE_DIR`          | `bridgeDir`           | Directory for the bootstrap bridge jar; must be trusted.   |

Each startup argument also has a system property `macstab.chaos.<key>` (for example `-Dmacstab.chaos.configFile=…`), read after the agent arg and before the environment variable.

## Priority Order

`configJson` > `configBase64` > `configFile` (the first source set wins); within each, the agent arg > the system property (`macstab.chaos.configJson`, …) > the environment variable (`MACSTAB_CHAOS_CONFIG_JSON`, …)

## JSON Plan Format

```jsonc
{
  "metadata": { "name": "plan-name", "description": "optional" },
  "scenarios": [
    {
      "id": "unique-scenario-id",
      "description": "optional",
      "scope": "JVM",
      "precedence": 0,
      "selector": { "type": "executor", ... },
      "effect": { "type": "delay", "minDelay": "PT0.1S", "maxDelay": "PT0.5S" },
      "activationPolicy": {
        "startMode": "AUTOMATIC",
        "probability": 1.0,
        "rateLimit": { "permits": 10, "window": "PT1S" },
        "activateAfterMatches": 0,
        "activeFor": null,
        "maxApplications": null,
        "randomSeed": null
      }
    }
  ]
}
```

Duration fields use ISO-8601 format (`PT0.1S` = 100 ms, `PT30S` = 30 seconds).

---

# 14. Extension Points and Compatibility Guarantees

## Stable API Surface

`chaos-agent-api` is the contract applications program against; the other published modules' public API is recorded and versioned too (see Versioning below). Within a major version, API changes are additive; removals go through deprecation, and a record that gains a component keeps its previous canonical constructor (see Versioning).

The agent's internal modules (`chaos-agent-core`, `chaos-agent-instrumentation-jdk`, `chaos-agent-bootstrap`) and every `@Internal` type or member may change at any release without notice.

## Extension Points

- **`ChaosMetricsSink`**: inject custom metric forwarding at `ChaosRuntime` construction time (deprecated for removal since 2.0: use `chaos-agent-micrometer`, `chaos-agent-opentelemetry` or a `ChaosEventListener` on `APPLIED` events (`chaos.applications`))
- **`ChaosEventListener`**: register event listeners on the control plane for observability hooks
- **`ChaosSelector`**: sealed interface; cannot be extended outside the module (sealed hierarchy)
- **`ChaosEffect`**: sealed interface; cannot be extended outside the module
- **`ChaosPlanMapper`**: uses Jackson with `FAIL_ON_UNKNOWN_PROPERTIES` — extending the JSON format requires a library update

## Versioning

The public API of every published module (`chaos-agent-api`, `chaos-agent-startup-config`, the framework integrations and the testkit, each recorded in its module's `api/*.api`) follows semantic versioning. `@Internal` types and members are left out of those records and can change in any release.

Records of the API grow components (`ActivationPolicy` keeps 8- and 10-argument constructors from its earlier shapes). The compatibility policy for their constructors: when a record gains a component, its previous canonical signature stays as a delegating constructor until the next major release, so positional callers keep compiling and keep their meaning. Construct records through their factories and builders (`ActivationPolicy.count(1)`, `ActivationPolicy.builder()…build()`, `ChaosScenario.builder(id)`), not positionally: a positional call is easy to misplace (a value in the `randomSeed` slot instead of `maxApplications` compiles silently).

## Migration Notes

Upgrading from 1.x: [migration-2.0.md](migration-2.0.md).

---

# 15. Stack Walkdown

## API / Framework Layer

**What happens**: Application code calls `session.activate(scenario)` or `controlPlane.activate(scenario)`. `ChaosControlPlaneImpl.registerScenario()` validates the scenario, weaves the hook groups it needs, creates a `ScenarioController`, registers it in `ScenarioRegistry`, and returns a `DefaultChaosActivationHandle`. If `startMode == AUTOMATIC`, the controller is started (`REGISTERED → ACTIVE`) before `activate` returns.

**Costs**: the first activation of a hook group pays its retransformation; after that, `ConcurrentHashMap.compute()` plus a rebuild of the immutable operation index.

## Application / Runtime Layer

**What happens on the hot path**: Instrumented JDK method fires ByteBuddy advice → `BootstrapDispatcher` → `ChaosBridge` → `ChaosRuntime` → its domain dispatch. With a scenario indexed under the operation, the domain builds the `InvocationContext`, `DispatchEngine` calls `ScenarioRegistry.match()`, merges the decision, and executes delay + gate + terminal action.

**Costs**: 1 `InvocationContext` allocation per call; N controller `evaluate()` calls (N = scenarios indexed under the operation); `Thread.sleep()` for delays (OS scheduler interaction, minimum resolution ~1 ms on Linux/macOS).

## JVM Layer

**ByteBuddy advice weaving**: Advice is woven per hook group via `AgentBuilder.installOn(Instrumentation)` — on the first activation that needs the group (or at agent startup with `instrumentation=eager`). The JVM retransforms the target class bytecode once; thereafter, the advice is a compiled-in part of the method body. No JVM-level overhead per call beyond the instructions added by the advice.

**`MethodHandle` invocation**: `MethodHandle.invoke()` is JIT-compilable. After sufficient warm-up, the virtual dispatch through `BridgeDelegate` is inlined by the JIT and is functionally equivalent to a direct call.

**ThreadLocal access**: `DEPTH.get()` and `currentSessionId()` are `ThreadLocal.get()` calls. With the HotSpot JVM, `ThreadLocal.get()` on a non-inherited `ThreadLocal` is ~1–5 ns after JIT compilation. This is the dominant fixed overhead on the hot path when no scenario matches.

## Memory / Concurrency Layer

**Operation-index read path**: `ScenarioRegistry.match()` does one `volatile` read of the immutable operation index and iterates a pre-sorted array; no lock, no stream, no sort. Register/unregister rebuild the index under a lock and swap it in; the `ConcurrentHashMap` of controllers is only read off the hot path (rebuilds, snapshots).

**`AtomicLong.incrementAndGet()`** (matchedCount): Lock-free CAS; ~3–10 ns on x86 under low contention.

**`SplittableRandom`**: Allocated per probability check. Each allocation is ~40 bytes; the GC cost is proportional to the rate of matched (not merely intercepted) operations.

## OS / Kernel / Network Layer

**`Thread.sleep` (the injected delay)**: on a platform thread, parks on the thread's `Parker` (`pthread_cond_timedwait`, a `futex(2)` wait on Linux); a virtual thread unmounts instead. Actual sleep precision is limited by the kernel scheduler's timer resolution (typically 1 ms on HZ=1000 kernels, tunable via high-resolution timers). Under load, the sleep may overshoot the requested duration.

**Socket interception**: `beforeSocketConnect`, `beforeSocketRead`, etc., fire before the underlying blocking system call. An injected delay adds latency to the blocking call. A REJECT effect throws before the system call is issued — the connection is never attempted.

**LockSupport.park interception**: `park()` maps to `pthread_cond_timedwait` (or `futex` on Linux). An injected pre-park delay causes the thread to sleep before parking, extending the blocking duration beyond what the caller intended.

## Infrastructure Interactions

The agent has no direct infrastructure dependencies at runtime. It does not perform network calls or external service lookups. Its own file I/O is the startup temp JAR, the plan file (read at start and, with `configWatchInterval`, polled) and, with `reports=on`, the status and hits files. The temp JAR (bootstrap bridge) is written to `bridgeDir`, else `java.io.tmpdir` (then `/dev/shm` when that is read-only), and, on POSIX, unlinked right after it is appended to the bootstrap class path (on Windows the open jar stays until the next start sweeps it).

---

# 16. References

- Reference: JVMS §5 — Classloader delegation model — https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-5.html
- Reference: JVMS §6.5 — `monitorenter` / `monitorexit` / `invokevirtual` opcodes — https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-6.html
- Reference: JSR-133 — Java Memory Model §17.4.5 happens-before, §17.4.7 well-formed executions — https://jcp.org/aboutJava/communityprocess/mrel/jsr133/index.html
- Reference: JLS §17 — Threads and Locks — https://docs.oracle.com/javase/specs/jls/se21/html/jls-17.html
- Reference: JLS §11.1 — Checked vs unchecked exceptions (sneaky-throw: JVM does not enforce checked exceptions at bytecode level) — https://docs.oracle.com/javase/specs/jls/se21/html/jls-11.html
- Reference: JEP 444 — Virtual Threads (Java 21) — https://openjdk.org/jeps/444
- Reference: JEP 374 — Deprecate and Disable Biased Locking — https://openjdk.org/jeps/374
- Reference: JVMTI Spec §11.2.2 — `RetransformClasses` constraints (class format change restrictions) — https://docs.oracle.com/en/java/docs/api/java.instrument/module-summary.html
- Reference: Byte Buddy — `AgentBuilder`, `@Advice`, `RedefinitionStrategy` — https://bytebuddy.net/#/tutorial
- Reference: `java.lang.instrument.Instrumentation` — `appendToBootstrapClassLoaderSearch`, `retransformClasses` — https://docs.oracle.com/en/java/docs/api/java.instrument/java/lang/instrument/Instrumentation.html
- Reference: Java SE API — `java.util.SplittableRandom` (not thread-safe; new instance per call required) — https://docs.oracle.com/en/java/docs/api/java.base/java/util/SplittableRandom.html
- Reference: Java SE API — `java.util.concurrent.ConcurrentHashMap` — https://docs.oracle.com/en/java/docs/api/java.base/java/util/concurrent/ConcurrentHashMap.html
- Reference: Java SE API — `java.util.concurrent.atomic.AtomicLong` — https://docs.oracle.com/en/java/docs/api/java.base/java/util/concurrent/atomic/AtomicLong.html
- Reference: JDK-8029999 — `@IntrinsicCandidate`; JIT intrinsic inlining bypasses bytecode advice — https://bugs.openjdk.org/browse/JDK-8029999
- Reference: Intel® 64 and IA-32 Architectures SDM Vol. 3 — `LOCK CMPXCHG`, `LOCK XADD`, `MFENCE` memory ordering — https://www.intel.com/content/www/us/en/developer/articles/technical/intel-sdm.html
- Reference: ARM Architecture Reference Manual — `STLR` (Store-Release), `LDAR` (Load-Acquire), `STXR` (Store-Exclusive) — https://developer.arm.com/documentation/ddi0487/latest
- Reference: Linux `futex(2)` — `FUTEX_WAIT`, `FUTEX_WAKE`, `FUTEX_WAIT_BITSET` — https://man7.org/linux/man-pages/man2/futex.2.html
- Reference: Linux `nanosleep(2)` — timer resolution, `CONFIG_HZ`, high-resolution timers — https://man7.org/linux/man-pages/man2/nanosleep.2.html
- Reference: Linux `pthread_mutex_lock(3)` — futex-backed mutex implementation — https://man7.org/linux/man-pages/man3/pthread_mutex_lock.3p.html

---

<div align="center">

*Architecture, implementation, and documentation crafted with Love and Passion by*

**[Christian Schnapka](https://macstab.com)**  
Embedded Principal+ Engineer  
[Macstab GmbH](https://macstab.com) · Hamburg, Germany

*Building systems that operate correctly at the edges — including the ones you deliberately break.*

</div>
