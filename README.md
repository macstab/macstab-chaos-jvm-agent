# macstab-chaos-jvm-agent

In-process JVM chaos testing agent. Inject controlled failures, delays, and resource stress into
selected JDK surfaces — with no application code changes required.

[![Java 17+](https://img.shields.io/badge/Java-17%2B-blue.svg)](https://openjdk.org/projects/jdk/17/)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)

---

## Why

Most chaos frameworks work at the infrastructure layer (network, DNS, containers). This agent works
**inside the JVM process** — intercepting `ThreadPoolExecutor`, `CompletableFuture`, sockets, clocks,
class loaders, JMX, JNDI, ZIP, and more. No sidecar, no iptables, no mock — real bytecode
instrumentation via [Byte Buddy](https://bytebuddy.net/).

Key differentiator: **per-test session isolation**. Multiple tests can run in parallel against the
same JVM. Each test gets its own `ChaosSession`; chaos from one test never bleeds into another.

---

## Quick Start

### 1. Add the dependency

```kotlin
// build.gradle.kts (test scope)
testImplementation("com.macstab:chaos-agent-testkit:0.1.0-SNAPSHOT")
```

### 2. Annotate your test class

```java
@ExtendWith(ChaosAgentExtension.class)
class MyServiceTest {

    @Test
    void shouldHandleExecutorDelays(ChaosSession session) {
        ChaosScenario scenario = ChaosScenario.builder()
            .id("slow-executor")
            .scope(ChaosScenario.ScenarioScope.SESSION)
            .selector(ChaosSelector.executor())
            .effect(ChaosEffect.delay(Duration.ofMillis(200)))
            .build();

        session.activate(scenario);

        try (ChaosSession.ScopeBinding scope = session.bind()) {
            // all executor submissions on this thread are delayed 200 ms
            myService.doWork();
        }
    }
}
```

`ChaosAgentExtension` self-attaches the agent, opens a fresh `ChaosSession` before each test, and
closes it (stopping all scenarios) after. No `-javaagent` flag needed for tests.

### 3. Or use `-javaagent` for production-like testing

```
java -javaagent:chaos-agent-bootstrap-0.1.0-SNAPSHOT.jar \
     -Dchaos.configFile=/etc/chaos/plan.json \
     -jar your-app.jar
```

---

## Core Concepts

| Concept | What it is |
|---|---|
| **Plan** | Named collection of scenarios loaded at startup or activated programmatically |
| **Scenario** | One selector + one effect + one activation policy |
| **Selector** | Matching rule: which JVM operation triggers this scenario |
| **Effect** | What to do when matched: delay, reject, corrupt, stress, … |
| **Activation policy** | Gating: probability, rate limit, warm-up count, time window, max applications |
| **Session** | Thread-scoped isolation boundary — chaos targets only threads bound to the session |
| **Handle** | `AutoCloseable` returned by `activate()`; close it to stop the scenario |

---

## Selectors

| Selector | Intercepts |
|---|---|
| `ChaosSelector.executor()` | `ThreadPoolExecutor.execute()` and `submit()` |
| `ChaosSelector.scheduledExecutor()` | `ScheduledExecutorService.schedule*()` |
| `ChaosSelector.forkJoin()` | `ForkJoinPool` tasks |
| `ChaosSelector.queue()` | `BlockingQueue.put()` / `take()` / `offer()` |
| `ChaosSelector.thread()` | `Thread.start()` |
| `ChaosSelector.async()` | `CompletableFuture.complete()` / `cancel()` |
| `ChaosSelector.method(className, methodName)` | Arbitrary method invocation via reflection |
| `ChaosSelector.classLoader()` | `ClassLoader.defineClass()` |
| `ChaosSelector.socket()` | `Socket` / `SocketChannel` connect, read, write, accept |
| `ChaosSelector.nio()` | `Selector.select()` spurious wakeups, NIO channel I/O |
| `ChaosSelector.serialization()` | `ObjectInputStream.readObject()` / `ObjectOutputStream.writeObject()` |
| `ChaosSelector.zip()` | `Inflater` / `Deflater` |
| `ChaosSelector.jndi()` | `InitialContext.lookup()` |
| `ChaosSelector.jmx()` | `MBeanServer.invoke()` / `getAttribute()` |
| `ChaosSelector.nativeLib()` | `System.loadLibrary()` |
| `ChaosSelector.shutdown()` | `System.exit()` / `Runtime.halt()` / shutdown hooks |
| `ChaosSelector.stress()` | Background stressors (heap, threads, GC, …) |

---

## Effects

### Interceptor effects (applied inline on the calling thread)

| Effect | Description |
|---|---|
| `ChaosEffect.delay(Duration)` | Fixed or randomised pause before the operation proceeds |
| `ChaosEffect.delay(min, max)` | Uniformly sampled pause in `[min, max]` |
| `ChaosEffect.gate(maxBlock)` | Block until `handle.release()` is called (or timeout elapses) |
| `ChaosEffect.reject(message)` | Throw an appropriate exception for the operation type |
| `ChaosEffect.suppress()` | Silently discard the operation; caller receives `null` / `false` |
| `ChaosEffect.exceptionalCompletion(kind, message)` | Complete a `CompletableFuture` exceptionally |
| `ChaosEffect.exceptionInjection(type, message)` | Inject an arbitrary exception into any method |
| `ChaosEffect.returnValueCorruption(strategy)` | Corrupt the return value: `NULL`, `ZERO`, `EMPTY`, `BOUNDARY_MAX`, `BOUNDARY_MIN` |
| `ChaosEffect.clockSkew(mode, offset)` | Skew `System.currentTimeMillis()` / `nanoTime()`: `FIXED`, `DRIFT`, `FREEZE` |
| `ChaosEffect.spuriousWakeup()` | Trigger spurious NIO `Selector.select()` returns |

### Stressor effects (long-running background chaos)

| Effect | Description |
|---|---|
| `ChaosEffect.heapPressure(bytes)` | Retain heap allocations for scenario lifetime |
| `ChaosEffect.keepAlive(threads)` | Spawn and hold non-daemon threads |
| `ChaosEffect.metaspacePressure(classCount)` | Load synthetic classes into metaspace |
| `ChaosEffect.directBufferPressure(bytes)` | Allocate and retain off-heap direct buffers |
| `ChaosEffect.gcPressure(allocationRate)` | Continuously allocate short-lived objects to stress GC |
| `ChaosEffect.finalizerBacklog(objectCount)` | Enqueue finalizable objects to exhaust the finalizer thread |
| `ChaosEffect.deadlock()` | Create a monitor deadlock between two background threads |
| `ChaosEffect.threadLeak(count)` | Start threads that never terminate |
| `ChaosEffect.threadLocalLeak(entryCount)` | Leak `ThreadLocal` entries on a background thread |
| `ChaosEffect.monitorContention(threads)` | Saturate a shared lock with background contenders |
| `ChaosEffect.codeCachePressure(classCount)` | Generate ByteBuddy classes to fill the JIT code cache |
| `ChaosEffect.safepointStorm(interval)` | Trigger periodic GC + retransformation to create STW pauses |
| `ChaosEffect.stringInternPressure(count)` | Intern unique strings to fill the string pool |
| `ChaosEffect.referenceQueueFlood(count)` | Enqueue phantom/weak references to flood the reference queue |

---

## Activation Policy

Control _when_ and _how often_ a scenario fires:

```java
ActivationPolicy policy = ActivationPolicy.builder()
    .startMode(ActivationPolicy.StartMode.AUTOMATIC)   // or MANUAL
    .probability(0.3)                                   // 30 % of matched operations
    .rateLimit(10, Duration.ofSeconds(1))               // max 10 applications per second
    .activateAfterMatches(5)                            // skip first 5 matches (warm-up)
    .activeFor(Duration.ofSeconds(30))                  // auto-expire after 30 s
    .maxApplications(100L)                              // stop after 100 total applications
    .randomSeed(42L)                                    // reproducible sampling
    .build();
```

All fields are optional and compose independently.

---

## Session Isolation

```java
@ExtendWith(ChaosAgentExtension.class)
class ParallelTests {

    @Test
    void testA(ChaosSession sessionA) {
        sessionA.activate(delayScenario);
        try (var b = sessionA.bind()) {
            // only threads bound to sessionA see chaos
        }
    }

    @Test
    void testB(ChaosSession sessionB) {
        // completely independent; sessionA scenarios are invisible here
        sessionB.activate(rejectScenario);
        // propagate context into executor threads:
        executor.submit(sessionB.wrap(() -> myService.doWork()));
    }
}
```

---

## JVM-Scoped Chaos (shared across all threads)

```java
ChaosControlPlane controlPlane = ChaosAgentBootstrap.current();

ChaosActivationHandle handle = controlPlane.activate(
    ChaosScenario.builder()
        .id("global-socket-delay")
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(ChaosSelector.socket())
        .effect(ChaosEffect.delay(Duration.ofMillis(50)))
        .build()
);

// ... run your test ...

handle.close(); // stops the scenario
```

---

## Startup Configuration (JSON plan)

```json
{
  "name": "soak-test-plan",
  "scenarios": [
    {
      "id": "executor-latency",
      "scope": "JVM",
      "selector": { "type": "executor" },
      "effect": { "type": "delay", "minDelay": "PT0.1S", "maxDelay": "PT0.5S" },
      "activationPolicy": { "probability": 0.5 }
    },
    {
      "id": "heap-stress",
      "scope": "JVM",
      "selector": { "type": "stress" },
      "effect": { "type": "heapPressure", "bytes": 67108864 }
    }
  ]
}
```

Pass via agent argument:

```
-javaagent:chaos-agent-bootstrap.jar=configFile=/etc/chaos/plan.json
# or inline:
-javaagent:chaos-agent-bootstrap.jar=configBase64=<base64-encoded-json>
# enable startup dump:
-javaagent:chaos-agent-bootstrap.jar=configFile=/etc/chaos/plan.json,debugDumpOnStart=true
```

---

## Diagnostics

```java
ChaosDiagnostics diag = controlPlane.diagnostics();

// snapshot of all active scenarios
ChaosDiagnostics.Snapshot snap = diag.snapshot();
snap.scenarios().forEach(r ->
    System.out.printf("%s: state=%s matched=%d applied=%d%n",
        r.id(), r.state(), r.matchedCount(), r.appliedCount()));

// full debug dump to stderr (also via JMX)
diag.debugDump();
```

JMX MBean: `com.macstab.chaos:type=ChaosDiagnostics`

---

## Modules

| Module | Role |
|---|---|
| `chaos-agent-api` | Stable public API — the only module application code should depend on directly |
| `chaos-agent-bootstrap` | Agent entry point (`premain`/`agentmain`), singleton init, MBean registration |
| `chaos-agent-core` | Scenario registry, matching, activation policies, session scoping, effects |
| `chaos-agent-instrumentation-jdk` | Byte Buddy advice classes, bootstrap bridge (42 interception handles) |
| `chaos-agent-startup-config` | JSON/base64/file config resolution and Jackson mapping |
| `chaos-agent-testkit` | JUnit 5 extension, `ChaosPlatform.installLocally()` for self-attach |
| `chaos-agent-examples` | Runnable examples demonstrating common patterns |

---

## Build

```bash
./gradlew build          # compile + test all modules
./gradlew test           # tests only
./gradlew :chaos-agent-bootstrap:jar  # produce the agent jar
```

Requires JDK 17+. Build toolchain is JDK 25; `--release 17` is enforced.

---

## Technical Notes

- Instrumentation is **permanent** for the JVM lifetime. `close()` stops scenarios but does not
  remove bytecode advice. This is a deliberate design choice — uninstallation is not in scope.
- The **BootstrapDispatcher** runs on the bootstrap classloader to bridge the classloader gap
  between JDK classes and the agent runtime. It holds 42 method handles covering all interception
  points.
- **ThreadLocal / LockSupport.park / AQS** are intentionally not instrumented — these are used
  internally by the dispatcher's reentrancy guard and would cause infinite recursion.
- Virtual thread support for `Thread.isVirtual()` selectors requires a JDK with virtual thread
  support at runtime (JDK 21+).

---

## Documentation

Detailed internal documentation lives in [`docs/`](docs/):

- [`overall-agent.md`](docs/overall-agent.md) — architecture, concurrency model, failure model, security
- [`api.md`](docs/api.md) — stable API surface reference
- [`core.md`](docs/core.md) — registry, matching pipeline, session scoping
- [`instrumentation.md`](docs/instrumentation.md) — Byte Buddy advice, bootstrap bridge
- [`bootstrap.md`](docs/bootstrap.md) — agent initialization, MBean registration
- [`startup-config.md`](docs/startup-config.md) — config resolution, JSON format
- [`testkit.md`](docs/testkit.md) — JUnit 5 extension, test installation

---

## License

Apache License 2.0 — see [LICENSE](LICENSE).
