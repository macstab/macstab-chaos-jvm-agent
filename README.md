<!--
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Engineered by  Christian Schnapka
                 Embedded Principal+ Engineer
                 Macstab GmbH · Hamburg, Germany
                 https://macstab.com
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-->

<div align="center">

# macstab-chaos-jvm-agent

**In-process JVM chaos engineering — bytecode-level fault injection with zero application changes**

[![Java 17+](https://img.shields.io/badge/Java-17%2B-blue.svg)](https://openjdk.org/projects/jdk/17/)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)
[![ByteBuddy](https://img.shields.io/badge/ByteBuddy-instrumentation-orange.svg)](https://bytebuddy.net/)

*Designed and engineered by* **[Christian Schnapka](https://macstab.com)** —
Embedded Principal+ Engineer · [Macstab GmbH](https://macstab.com) · Hamburg, Germany

</div>

---

## The Short Version

Most chaos tools attack from outside: kill a container, poison DNS, drop packets at the network layer. This tool goes **inside** — it rewrites JDK bytecode at startup and intercepts 42 distinct JVM operations while the process is running. No sidecars. No mocks. No application code changes. Real `Thread.sleep`, real `ConnectException`, real `OutOfMemoryError` — injected surgically into the exact call sites you choose.

```java
// Three lines to make your socket layer fall apart on demand:
session.activate(ChaosScenario.builder()
    .selector(ChaosSelector.network().remoteHostPattern("db.internal.*"))
    .effect(ChaosEffect.reject("chaos: connection refused"))
    .build());
```

---

<!-- TOC -->
* [macstab-chaos-jvm-agent](#macstab-chaos-jvm-agent)
  * [The Short Version](#the-short-version)
  * [Floor 0 — What it does (plain English)](#floor-0--what-it-does-plain-english)
  * [Floor -1 — Architecture (senior engineer territory)](#floor--1--architecture-senior-engineer-territory)
  * [Floor -2 — Runtime mechanics (principal-level)](#floor--2--runtime-mechanics-principal-level)
    * [Evaluation Pipeline](#evaluation-pipeline)
    * [Classloader Bridge](#classloader-bridge)
    * [Reentrancy Guard](#reentrancy-guard)
  * [Floor -3 — JVM internals, bytecode, and OS mechanics (the 1% layer)](#floor--3--jvm-internals-bytecode-and-os-mechanics-the-1-layer)
    * [ByteBuddy Advice and the JVM Retransformation Mechanism](#bytebuddy-advice-and-the-jvm-retransformation-mechanism)
    * [JMM Happens-Before in the Bootstrap Bridge](#jmm-happens-before-in-the-bootstrap-bridge)
    * [`AtomicLong.incrementAndGet()` on x86-64](#atomiclongincrementandget-on-x86-64)
    * [`synchronized(this)` — Rate Limit and the HotSpot Lock Inflation Protocol](#synchronizedthis--rate-limit-and-the-hotspot-lock-inflation-protocol)
    * [`LockSupport.park()` and OS Thread Scheduling](#locksupportpark-and-os-thread-scheduling)
    * [Safepoint Mechanics and `SafepointStormStressor`](#safepoint-mechanics-and-safepointstormstressor)
    * [Virtual Threads and Carrier-Thread Pinning Risk](#virtual-threads-and-carrier-thread-pinning-risk)
    * [`SplittableRandom` — Why Not `ThreadLocalRandom`?](#splittablerandom--why-not-threadlocalrandom)
  * [Quick Start](#quick-start)
    * [1. Add the dependency](#1-add-the-dependency)
    * [2. Annotate your test](#2-annotate-your-test)
    * [3. Or attach at startup for production-like testing](#3-or-attach-at-startup-for-production-like-testing)
  * [Core Concepts](#core-concepts)
  * [Selectors — Full Reference](#selectors--full-reference)
  * [Effects — Full Reference](#effects--full-reference)
    * [Inline effects (execute on the calling thread)](#inline-effects-execute-on-the-calling-thread)
    * [Background stressor effects](#background-stressor-effects)
  * [Activation Policy](#activation-policy)
  * [Session Isolation](#session-isolation)
  * [Startup Configuration (JSON)](#startup-configuration-json)
  * [Diagnostics](#diagnostics)
  * [Module Layout](#module-layout)
  * [Spring Boot Integration](#spring-boot-integration)
    * [Test Starter](#test-starter)
    * [Runtime Starter](#runtime-starter)
  * [Performance](#performance)
    * [Hot-path overhead targets](#hot-path-overhead-targets)
    * [What the JIT does](#what-the-jit-does)
    * [Benchmarks](#benchmarks)
  * [Build](#build)
  * [Detailed Documentation](#detailed-documentation)
  * [License](#license)
<!-- TOC -->

---

## Floor 0 — What it does (plain English)

You have a Java service. You want to know what happens when:
- the database connection pool is always slow
- the executor that processes your orders gets delayed
- `System.currentTimeMillis()` lies to your TTL checks
- `Selector.select()` wakes up for no reason, like the Linux kernel actually does
- the GC is under pressure while your request is in-flight

This library lets you **turn those scenarios on and off programmatically**, scoped to a single test thread, without touching production code. Multiple tests run in parallel in the same JVM — each test's chaos is invisible to every other test.

---

## Floor -1 — Architecture (senior engineer territory)

The agent loads via the standard Java Instrumentation API (`-javaagent:` or dynamic self-attach). ByteBuddy weaves `@Advice` hooks into selected JDK methods at startup. Those hooks call a static dispatcher that routes to the live scenario registry, evaluates matching scenarios against an 8-check activation pipeline, and executes the decision inline on the calling thread.

```
Application Thread
  └─► instrumented JDK method (e.g. ThreadPoolExecutor.execute)
        └─► ByteBuddy @Advice (inlined bytecode)
              └─► BootstrapDispatcher (bootstrap classloader)
                    └─► ChaosBridge → ChaosRuntime (agent classloader)
                          └─► ScenarioRegistry.match() → evaluate() × N
                                └─► RuntimeDecision: delay + gate + terminal action
```

**42 interception handles** span: thread lifecycle, executor submission, scheduled ticks, blocking queues, `CompletableFuture`, NIO selectors, TCP sockets, clock (`currentTimeMillis`/`nanoTime`), GC, `System.exit`, reflection, `ObjectInputStream`, class loading, `LockSupport.park`, AQS acquire, JNDI, JMX, ZIP compression, `ThreadLocal`, native library loading, and arbitrary method entry/exit.

**Session isolation**: each test gets a `ChaosSession` backed by a `ThreadLocal<String>`. Session-scoped chaos evaluates only when the session ID on the current thread matches. Executor submissions within a `session.bind()` scope carry the session ID into worker threads via task decoration — chaos propagates exactly where intended and nowhere else.

---

## Floor -2 — Runtime mechanics (principal-level)

### Evaluation Pipeline

Every intercepted JVM operation runs through `ScenarioController.evaluate()` — an 8-gate pipeline that short-circuits on the first failed check:

1. `started.get()` — `AtomicBoolean`, maps to a volatile read + memory barrier
2. `sessionId` equality — `String.equals()`, null means JVM-scope (passes all)
3. `SelectorMatcher.matches()` — exhaustive sealed-type `switch` over `ChaosSelector` subtypes; stateless; zero allocation
4. `matchedCount.incrementAndGet()` + activation-window check — `AtomicLong` CAS → lazy INACTIVE transition
5. Warm-up gate: `matched <= activateAfterMatches`
6. Rate limit: `synchronized(this)` sliding-window token bucket — `rateWindowStartMillis + rateWindowPermits`
7. Probability: `new SplittableRandom(baseSeed ^ matched ^ id.hashCode()).nextDouble()`
8. Max-applications: CAS loop on `appliedCount` — prevents overshoot under concurrent access

**Why the CAS loop at step 8?** A naive `incrementAndGet()` then compare pattern allows N racing threads to simultaneously read `count < max`, all increment past the cap, and all apply the effect. The CAS loop (`compareAndSet(current, current+1)` with retry on collision) is the only correct solution under the Java Memory Model.

### Classloader Bridge

JDK classes (`Thread`, `Socket`, `System`, etc.) are loaded by the **bootstrap classloader** — the root of the classloader hierarchy with no parent. ByteBuddy advice woven into these classes executes *in* the bootstrap classloader's namespace, which cannot see agent classes by name. The bridge:

1. At startup, `BootstrapDispatcher.class` bytecode is extracted from the agent JAR, written to a temp JAR, and appended to the bootstrap classpath via `Instrumentation.appendToBootstrapClassLoaderSearch`
2. A 42-slot `MethodHandle[]` array is built against `BridgeDelegate.class` using `MethodHandles.publicLookup()` and wired into `BootstrapDispatcher.install()` via reflection against the bootstrap-classloader version (`Class.forName("...BootstrapDispatcher", true, null)`)
3. `handles` is written to the `volatile` field **before** `delegate` — the Java Memory Model's happens-before rule on volatile writes guarantees any thread that observes `delegate != null` also observes the fully-initialized `handles` array

### Reentrancy Guard

Chaos code itself calls instrumented JDK methods (`Thread.sleep`, `LockSupport.park`, `ConcurrentHashMap` internals). Without protection, each chaos dispatch would trigger another chaos dispatch, recursing until stack overflow. The guard:

```
DEPTH : ThreadLocal<Integer>  (bootstrap-classloader resident)
invoke():
  if DEPTH.get() > 0 → return fallback immediately
  DEPTH.set(DEPTH.get() + 1)
  try { ... dispatch ... }
  finally { if --depth == 0: DEPTH.remove() }
```

The `ThreadLocal.remove()` in the `finally` block is critical for thread pool longevity — without it, the `ThreadLocal` entry accumulates on pooled threads, creating a slow per-thread memory leak across thousands of requests.

A second recursion risk: `ThreadLocal.get()` is itself instrumented in Phase 2. Reading `DEPTH.get()` inside `invoke()` would re-trigger `ThreadLocalGetAdvice`, which would call `invoke()`, which would call `DEPTH.get()` ... The advice contains an identity check:

```java
if (threadLocal == BootstrapDispatcher.depthThreadLocal()) return false;
```

This single pointer-equality check is the only thing preventing infinite recursion at that specific callsite.

---

## Floor -3 — JVM internals, bytecode, and OS mechanics (the 1% layer)

### ByteBuddy Advice and the JVM Retransformation Mechanism

ByteBuddy instrumentation uses `AgentBuilder` with `RedefinitionStrategy.RETRANSFORMATION` and `disableClassFormatChanges()`. What this means at the bytecode level:

- **`disableClassFormatChanges()`** constrains ByteBuddy to inline-only transformations: no new fields, no new constant pool entries that change the class format, no changes to method signatures. The transformed class must be accepted by `ClassFileTransformer.transform()` under the constraints of JVMTI's `RetransformClasses`. This is enforced by JVMTI spec §11.2.2 ("Retransformation Incapable") — specifically, that retransformable transformers may only modify method bodies, not the class schema.
- **`@Advice.OnMethodEnter`** bytecode is copied verbatim (not called — *copied*) into the target method's bytecode at the entry point. The JVM sees one contiguous method body. After JIT compilation (`-XX:CompileThreshold` default 10,000 calls for C2 on HotSpot), the advice body is inlined by the JIT compiler as part of the compiled native frame. There is no virtual dispatch overhead after warm-up.
- **Native method interception** (specifically `System.currentTimeMillis()`, `System.nanoTime()`): these are `@IntrinsicCandidate` native methods. HotSpot replaces them with Architecture-specific intrinsics during JIT compilation — on x86-64, `currentTimeMillis()` becomes a direct `RDTSC` + conversion sequence; on AArch64, it uses `MRS X0, CNTVCT_EL0`. ByteBuddy advice on the Java wrapper is dead code after JIT compilation. The `ClockSkewEffect` cannot intercept production clock reads via bytecode instrumentation alone — it works only via the direct `ChaosRuntime.applyClockSkew()` API.

### JMM Happens-Before in the Bootstrap Bridge

The two-field publication in `BootstrapDispatcher.install()`:

```java
handles = methodHandles;  // volatile write W1
delegate = bridgeDelegate; // volatile write W2
```

By JSR-133 §17.4.5 ([https://jcp.org/aboutJava/communityprocess/mrel/jsr133/index.html](https://jcp.org/aboutJava/communityprocess/mrel/jsr133/index.html)):

> A write to a volatile field happens-before every subsequent read of that field.

W1 happens-before W2 (program order + volatile ordering). Any thread T that reads `delegate != null` (volatile read R2) has R2 synchronizes-with W2, and W2 happens-after W1. By transitivity: `handles` is visible to T. On x86-64, the `volatile` write compiles to a `MOV` + `LOCK XCHG` or `MFENCE` (depending on JIT strategy) to enforce store-ordering. On AArch64, it emits `STLR` (Store-Release) which provides release semantics, ensuring all prior stores are visible before this store completes.

### `AtomicLong.incrementAndGet()` on x86-64

```java
matchedCount.incrementAndGet()
// compiles to:
LOCK XADD [rsi+offset], 1   ; atomic fetch-and-add on x86-64
// or equivalently via CAS loop:
LOCK CMPXCHG [rsi+offset], rax
```

The `LOCK` prefix on x86 asserts the cache coherency protocol (MESI) for the cache line containing the field, issues a full memory barrier (both acquire and release semantics), and ensures atomicity across hyperthreads sharing an L1 cache. On AMD Zen and Intel Architectures with MESIF, this causes a cache-line ownership transfer if another core holds the line in Modified state — the latency spike is 40–70 cycles for cross-core coherency vs. ~4 cycles for same-core hits.

**False sharing risk**: `ScenarioController` packs `matchedCount` and `appliedCount` as adjacent `AtomicLong` fields. Both fields fit in the same 64-byte cache line on typical JVMs. Under high-concurrency scenarios where many threads increment `matchedCount` while others CAS `appliedCount`, the cache line bounces between cores. This is a known trade-off in the current implementation — `@Contended` (JDK internal) padding could eliminate it at the cost of 128 bytes per controller.

### `synchronized(this)` — Rate Limit and the HotSpot Lock Inflation Protocol

The rate-limit check is `synchronized(this)` on the `ScenarioController` instance. HotSpot's lock protocol ([JVM Spec §6.5 monitorenter](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-6.html#jvms-6.5.monitorenter)):

1. **Biased locking** (if `-XX:+UseBiasedLocking`, JDK < 21): the thread ID is CAS-written into the mark word of the object header. Subsequent acquires by the same thread are lock-free — just a mark word read. JDK 21 removed biased locking ([JEP 374](https://openjdk.org/jeps/374)).
2. **Lightweight lock**: CAS on the object's mark word to install a pointer to the current thread's stack frame. The `monitorenter` bytecode (opcode `0xC2` in the JVM instruction set) triggers this.
3. **Heavyweight lock (inflated)**: when contention is detected, HotSpot inflates to an OS mutex — `pthread_mutex_lock(3)` on Linux, which maps to `futex(2)` with `FUTEX_WAIT` on the lock word. The inflated monitor (`ObjectMonitor` in JVM internals) contains an entry queue and a wait set backed by `ParkEvent` objects.

For the rate-limit case, contention is expected to be near-zero (rate-limited scenarios are rare by design). Biased or lightweight locking dominates — the synchronized block executes in ~5 ns under the uncontended path.

### `LockSupport.park()` and OS Thread Scheduling

`LockSupport.park(blocker)` ([JDK source: `java.util.concurrent.locks.LockSupport`](https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/util/concurrent/locks/LockSupport.java)) maps to `Unsafe.park(false, 0L)` → JVM intrinsic → OS-level thread suspension:

- **Linux**: `pthread_cond_timedwait(3)` → `futex(2)` with `FUTEX_WAIT_BITSET` or `FUTEX_WAIT`. The thread is removed from the kernel run queue, its `task_struct` state set to `TASK_INTERRUPTIBLE`, and control returned to the scheduler. Wakeup via `LockSupport.unpark()` calls `futex(FUTEX_WAKE)`.
- **macOS**: `mach_wait_until(2)` or `semaphore_timedwait` via the Mach port abstraction.
- **Minimum scheduler quanta**: on a Linux kernel with `CONFIG_HZ=1000` (1ms tick), the thread cannot be rescheduled faster than 1ms. `Thread.sleep(delayMillis)` with `delayMillis=1` may actually sleep 1–2ms depending on scheduler load. High-resolution timers (`CONFIG_HIGH_RES_TIMERS=y`) reduce this to sub-millisecond on modern kernels.

The `THREAD_PARK` instrumentation fires a `beforeThreadPark()` dispatch before the actual park. If a `DelayEffect` is configured, `Thread.sleep(delayMillis)` is called — which itself calls `park`, which the reentrancy DEPTH guard intercepts and returns the fallback immediately. Without the DEPTH guard, a 100ms chaos delay on `THREAD_PARK` would cause infinite `sleep → park → chaos eval → sleep → park ...` recursion.

### Safepoint Mechanics and `SafepointStormStressor`

HotSpot safepoints are JVM-global stop-the-world pauses. All application threads must reach a "safe point" — a location in the bytecode where the JVM knows the full GC root set. The `SafepointStormStressor` deliberately triggers them by calling both `System.gc()` and `Instrumentation.retransformClasses()` on a timer.

How safepoints work at the JVM level:
1. The JVM sets a "safepoint flag" in a polling page (a memory-mapped page set to no-access)
2. JIT-compiled code contains safepoint polls at loop back-edges and method returns — a load from the polling page. If the page is no-access, the resulting `SIGSEGV` is caught by the JVM signal handler which parks the thread at the safepoint
3. Interpreted code polls at each bytecode boundary
4. Once all threads are parked, the JVM performs the safepoint operation (GC, retransformation, deoptimization, etc.) and releases all threads

`SafepointStormStressor` calling `Instrumentation.retransformClasses()` forces a safepoint on the calling timer thread, which blocks all application threads for the duration of the transformation. This simulates STW pause pressure that would appear in production under heavy GC load or JVM agent activity.

### Virtual Threads and Carrier-Thread Pinning Risk

On JDK 21+ ([JEP 444 — Virtual Threads](https://openjdk.org/jeps/444)), virtual threads (`Thread.ofVirtual()`) are scheduled by the JVM's `ForkJoinPool`-based scheduler, mounted on platform carrier threads. A virtual thread that calls `synchronized` blocks *pins* its carrier thread — the carrier cannot be reassigned to another virtual thread while the virtual thread holds a monitor.

The `MONITOR_ENTER` interception instruments `AbstractQueuedSynchronizer.acquire()` — a proxy for `java.util.concurrent.locks.ReentrantLock`, not for `synchronized` blocks. The `beforeMonitorEnter()` chaos dispatch (if configured with a `DelayEffect`) adds latency to every AQS lock acquisition. On virtual threads, this delay occurs while the virtual thread is pinned to its carrier (if the lock is reentrant), which blocks that carrier from serving other virtual threads. Under high concurrency, this can cascade into carrier thread exhaustion.

This is documented in JEP 444: "A virtual thread cannot be unmounted when it is pinned to its carrier" — specifically in the case of `synchronized` blocks and JNI calls. AQS-based locks (`ReentrantLock`) do not pin virtual threads; the virtual thread is unmounted when parked inside AQS.

### `SplittableRandom` — Why Not `ThreadLocalRandom`?

`ScenarioController.passesProbability()` creates `new SplittableRandom(baseSeed ^ matched ^ id.hashCode())` per call. Why not `ThreadLocalRandom.current().nextDouble()`?

1. **Reproducibility**: `ThreadLocalRandom` seeds are non-deterministic (seeded from `/dev/urandom` or `nanoTime()`). With a fixed `randomSeed` in `ActivationPolicy`, we need deterministic sampling across runs — same seed + same `matchedCount` = same draw. `SplittableRandom` with an explicit seed satisfies this; `ThreadLocalRandom` does not.
2. **Thread-safety**: `SplittableRandom` is not thread-safe ([JDK API](https://docs.oracle.com/en/java/docs/api/java.base/java/util/SplittableRandom.html)). Creating a new instance per call (cheap — 3 `long` fields) avoids any shared-state issue. The seed is varied by `matched` to prevent the same `Random(seed)` from always returning the same first value.
3. **Why not `Random(seed).nextDouble()`?** `java.util.Random` uses a linear congruential generator with `AtomicLong` state — it's thread-safe but that safety is achieved via CAS, adding unnecessary contention. `SplittableRandom` uses a non-linear generator (a variant of `xorshift`) with no internal synchronization.

---

## Quick Start

### 1. Add the dependency

```kotlin
// build.gradle.kts
testImplementation("com.macstab:chaos-agent-testkit:0.1.0-SNAPSHOT")
```

### 2. Annotate your test

```java
@ExtendWith(ChaosAgentExtension.class)
class MyServiceTest {

    @Test
    void shouldHandleExecutorDelays(ChaosSession session) {
        session.activate(ChaosScenario.builder()
            .id("slow-executor")
            .scope(ChaosScenario.ScenarioScope.SESSION)
            .selector(ChaosSelector.executor())
            .effect(ChaosEffect.delay(Duration.ofMillis(200)))
            .build());

        try (ChaosSession.ScopeBinding scope = session.bind()) {
            myService.doWork(); // executor submissions delayed 200ms
        }
    }
}
```

`ChaosAgentExtension` self-attaches the agent, opens a fresh `ChaosSession` per test, and closes it after. No `-javaagent` flag required for tests.

### 3. Or attach at startup for production-like testing

```bash
java -javaagent:chaos-agent-bootstrap-0.1.0-SNAPSHOT.jar=configFile=/etc/chaos/plan.json \
     -jar your-app.jar
```

---

## Core Concepts

| Concept | What it is |
|---------|-----------|
| **Scenario** | One selector + one effect + one activation policy |
| **Selector** | Matching rule: which JVM operation(s) trigger this scenario |
| **Effect** | What happens: delay, reject, suppress, gate, exception, corrupt, stress, skew |
| **Activation policy** | Gating: probability, rate limit, warm-up, time window, max applications |
| **Session** | Thread-local isolation scope — chaos targets only session-bound threads |
| **Handle** | `AutoCloseable` returned by `activate()`; close to stop the scenario |

---

## Selectors — Full Reference

| Selector | Intercepts |
|----------|-----------|
| `executor()` | `ThreadPoolExecutor.execute()`, `submit()` |
| `scheduledExecutor()` | `ScheduledExecutorService.schedule*()` |
| `forkJoin()` | `ForkJoinTask.doExec()` |
| `thread()` | `Thread.start()` — platform and virtual threads |
| `queue()` | `BlockingQueue.put()` / `take()` / `offer()` / `poll()` |
| `async()` | `CompletableFuture.complete()` / `completeExceptionally()` / `cancel()` |
| `network()` | `Socket` connect / read / write / close, `ServerSocket.accept()` |
| `nio()` | `Selector.select()` spurious wakeups, `SocketChannel` / `ServerSocketChannel` |
| `method(class, method)` | Arbitrary method entry (`METHOD_ENTER`) and exit (`METHOD_EXIT`) |
| `classLoader()` | `ClassLoader.defineClass()`, `loadClass()`, `getResource()` |
| `monitor()` | `AbstractQueuedSynchronizer.acquire()` — AQS lock contention proxy |
| `jvmRuntime()` | `currentTimeMillis()`, `nanoTime()`, `gc()`, `exit()`, `halt()` |
| `serialization()` | `ObjectInputStream.readObject()` / `ObjectOutputStream.writeObject()` |
| `zip()` | `Inflater.inflate()` / `Deflater.deflate()` |
| `jndi()` | `InitialContext.lookup()` |
| `jmx()` | `MBeanServer.invoke()` / `getAttribute()` |
| `nativeLib()` | `System.loadLibrary()` / `System.load()` |
| `shutdown()` | `System.exit()` / `Runtime.halt()` / shutdown hook register/remove |
| `threadLocal()` | `ThreadLocal.get()` / `ThreadLocal.set()` |
| `stress(target)` | Background stressor lifecycle binding |

---

## Effects — Full Reference

### Inline effects (execute on the calling thread)

| Effect | Description |
|--------|-------------|
| `delay(Duration)` | Fixed pause before the operation proceeds |
| `delay(min, max)` | Uniform random pause in `[min, max]` |
| `gate(maxBlock)` | Block until `handle.release()` is called (or timeout elapses) |
| `reject(message)` | Throw a semantically correct exception for the operation type |
| `suppress()` | Silently discard; return `null` / `false` per operation contract |
| `exceptionalCompletion(kind, msg)` | Complete a `CompletableFuture` with a failure |
| `exceptionInjection(class, msg)` | Inject arbitrary exception at method entry via reflection |
| `returnValueCorruption(strategy)` | Corrupt return value: `NULL`, `ZERO`, `EMPTY`, `BOUNDARY_MAX`, `BOUNDARY_MIN` |
| `clockSkew(mode, offsetMs)` | Skew `currentTimeMillis()` / `nanoTime()`: `FIXED`, `DRIFT`, `FREEZE` |
| `spuriousWakeup()` | Force `Selector.select()` to return 0 immediately |

### Background stressor effects

| Effect | What it does | Recoverable? |
|--------|-------------|:---:|
| `heapPressure(bytes)` | Retain `byte[]` allocations on heap | ✅ |
| `keepAlive(threads)` | Spawn idle non-daemon threads | ✅ |
| `metaspacePressure(classes)` | Define synthetic classes into isolated classloader | ✅ (slow GC) |
| `directBufferPressure(bytes)` | Allocate off-heap `ByteBuffer.allocateDirect` | ✅ (GC-dependent) |
| `gcPressure(allocationRate)` | Continuously allocate short-lived objects | ✅ |
| `finalizerBacklog(count)` | Flood the finalizer queue | ✅ |
| `deadlock()` | Create a real JVM monitor deadlock | ❌ |
| `threadLeak(count)` | Start permanently-parked threads | ❌ |
| `threadLocalLeak(entries)` | Leak `ThreadLocal` entries on a background thread | ✅ (partial) |
| `monitorContention(threads)` | Saturate a shared lock with background contenders | ✅ |
| `codeCachePressure(classes)` | Generate ByteBuddy classes to fill the JIT code cache | ✅ |
| `safepointStorm(intervalMs)` | Trigger periodic GC + retransformation (STW pauses) | ✅ |
| `stringInternPressure(count)` | Intern unique strings into the JVM string pool | ✅ (pool is GC root) |
| `referenceQueueFlood(count)` | Flood the JVM reference queue with phantom refs | ✅ |

> ⚠️ `deadlock()` and `threadLeak()` create non-recoverable JVM state. Use only in throw-away test processes.

---

## Activation Policy

```java
ActivationPolicy policy = ActivationPolicy.builder()
    .probability(0.3)                        // fire 30% of the time
    .rateLimit(10, Duration.ofSeconds(1))     // max 10 applications per second
    .activateAfterMatches(5)                  // skip first 5 matches (warm-up)
    .activeFor(Duration.ofSeconds(30))        // auto-expire after 30 s
    .maxApplications(100L)                    // stop after 100 total applications
    .randomSeed(42L)                          // reproducible sampling
    .build();
```

All guards compose as AND. All fields are optional. Default: fire on every match.

---

## Session Isolation

```java
@Test void testA(ChaosSession sessionA) {
    sessionA.activate(delayScenario);
    try (var b = sessionA.bind()) {
        // only threads carrying sessionA's ID see this chaos
        executor.execute(sessionA.wrap(() -> myService.doWork()));
    }
}

@Test void testB(ChaosSession sessionB) {
    // completely independent, even in parallel — different session UUID
    sessionB.activate(rejectScenario);
}
```

---

## Startup Configuration (JSON)

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
    }
  ]
}
```

```bash
-javaagent:agent.jar=configFile=/etc/chaos/plan.json
-javaagent:agent.jar=configBase64=<base64-json>
-javaagent:agent.jar=configJson={"name":"..."}
-javaagent:agent.jar=configFile=/etc/plan.json,debugDumpOnStart=true
```

Environment variables: `MACSTAB_CHAOS_CONFIG_FILE`, `MACSTAB_CHAOS_CONFIG_JSON`, `MACSTAB_CHAOS_CONFIG_BASE64`.

---

## Diagnostics

```java
ChaosDiagnostics diag = controlPlane.diagnostics();
ChaosDiagnostics.Snapshot snap = diag.snapshot();

snap.scenarios().forEach(r ->
    System.out.printf("%s: state=%s matched=%d applied=%d reason=%s%n",
        r.id(), r.state(), r.matchedCount(), r.appliedCount(), r.reason()));

System.out.println(diag.debugDump()); // full text dump
```

JMX MBean: `com.macstab.chaos:type=ChaosDiagnostics` — inspect from `jconsole` without code changes.

**Diagnosing zero applications**:
- `matchedCount > 0 && appliedCount == 0` → selector works; activation policy is filtering
- `matchedCount == 0` → selector not matching; verify operation type, class name pattern, and `session.bind()` is active

---

## Module Layout

| Module | Role |
|--------|------|
| `chaos-agent-api` | **Stable public API** — the only module application code should depend on |
| `chaos-agent-bootstrap` | Agent entry point (`premain`/`agentmain`), singleton, MBean registration |
| `chaos-agent-core` | Scenario registry, evaluation pipeline, session scoping, stressors |
| `chaos-agent-instrumentation-jdk` | ByteBuddy advice, bootstrap bridge (42 interception handles) |
| `chaos-agent-startup-config` | JSON/base64/file config resolution and Jackson mapping |
| `chaos-agent-testkit` | JUnit 5 extension, `ChaosPlatform.installLocally()` for self-attach |
| `chaos-agent-spring-boot3-test-starter` | `@ChaosTest` + `ChaosAgentExtension` for Spring Boot 3 tests |
| `chaos-agent-spring-boot4-test-starter` | `@ChaosTest` + `ChaosAgentExtension` for Spring Boot 4 tests |
| `chaos-agent-spring-boot3-starter` | Runtime starter with Actuator endpoint for Spring Boot 3 |
| `chaos-agent-spring-boot4-starter` | Runtime starter with Actuator endpoint for Spring Boot 4 |
| `chaos-agent-examples` | Runnable usage examples |

---

## Spring Boot Integration

Two axes, four modules: test-time vs runtime, Boot 3 vs Boot 4. All four are `compileOnly` against their Spring Boot BOM — they are inert until the consuming application supplies Spring Boot on the classpath.

### Test Starter

The test starters give a `@SpringBootTest` class one-annotation access to chaos instrumentation. Add the dependency, put `@ChaosTest` on the class, declare a `ChaosSession` parameter on any test method.

```kotlin
// build.gradle.kts — Spring Boot 3
testImplementation("com.macstab:chaos-agent-spring-boot3-test-starter:0.1.0-SNAPSHOT")

// Spring Boot 4
testImplementation("com.macstab:chaos-agent-spring-boot4-test-starter:0.1.0-SNAPSHOT")
```

```java
@ChaosTest
class OrderServiceChaosTest {

    @Test
    void slowDatabaseRejectsOrdersGracefully(ChaosSession chaos) {
        chaos.activate(ChaosScenario.builder()
            .id("slow-jdbc")
            .selector(ChaosSelector.executor(NamePattern.prefix("HikariPool")))
            .effect(ChaosEffect.delay(Duration.ofSeconds(3)))
            .build());

        try (var binding = chaos.bind()) {
            assertThrows(OrderTimeoutException.class,
                () -> orderService.placeOrder(testOrder));
        }
    }
}
```

`@ChaosTest` composes `@SpringBootTest` and `@ExtendWith(ChaosAgentExtension.class)`. The extension self-attaches the agent (idempotent across the JVM), opens a class-scoped `ChaosSession`, injects it into test method parameters, and closes it after the last test method runs. `@Nested` classes inherit the same session. `ChaosControlPlane` can also be injected as a parameter. No `-javaagent` flag is needed for test JVMs.

### Runtime Starter

The runtime starters wire the chaos agent into a running Spring Boot application and optionally expose a Spring Boot Actuator endpoint for runtime activation and control.

```kotlin
// build.gradle.kts — Spring Boot 3
implementation("com.macstab:chaos-agent-spring-boot3-starter:0.1.0-SNAPSHOT")

// Spring Boot 4
implementation("com.macstab:chaos-agent-spring-boot4-starter:0.1.0-SNAPSHOT")
```

```yaml
# application.yml — opt-in required; all flags default to false
macstab:
  chaos:
    enabled: true
    config-file: /etc/chaos/soak-plan.json  # optional startup plan
    debug-dump-on-start: false
    actuator:
      enabled: true   # exposes /actuator/chaos — protect with Spring Security
```

When `enabled: true`, the starter installs the agent and exposes `ChaosControlPlane` as a Spring bean with `destroyMethod = "close"`. If `config-file` is set, the plan is loaded and activated on `ApplicationReadyEvent`. When `actuator.enabled: true` (and `spring-boot-actuator` is on the classpath), the `/actuator/chaos` endpoint becomes available:

```bash
# Inspect active scenarios
curl http://localhost:8080/actuator/chaos

# Activate a plan inline
curl -X POST http://localhost:8080/actuator/chaos \
     -H 'Content-Type: application/json' \
     -d '{"name":"latency","scenarios":[{"id":"slow-executor","scope":"JVM","selector":{"type":"executor"},"effect":{"type":"delay","minDelay":"PT0.2S","maxDelay":"PT0.5S"}}]}'

# Stop a specific scenario by ID
curl -X DELETE http://localhost:8080/actuator/chaos/slow-executor

# Stop all starter-managed scenarios
curl -X POST http://localhost:8080/actuator/chaos/stopAll
```

> The `/actuator/chaos` endpoint can activate arbitrary fault injection in the live JVM. Protect it as you would a shutdown endpoint — never expose it unauthenticated to the public internet.

For deep technical detail on all four modules — lifecycle, conditional wiring, `@Nested` session propagation, `ChaosHandleRegistry` design, `ChaosAgentInitializer` timing, Boot 3 vs Boot 4 factory differences, and PlantUML sequence diagrams — see [`docs/spring-integration.md`](docs/spring-integration.md).

---

## Performance

The agent is designed to be invisible on any I/O-bound path. All numbers below are for the **hot path after JIT warm-up** (~10 000 invocations for C2 tier on HotSpot).

### Hot-path overhead targets

| Scenario | Target | What drives the cost |
|----------|--------|----------------------|
| Agent installed, zero active scenarios | **< 50 ns** | Null-bridge short-circuit: one null check + return |
| One scenario active, no selector match | **< 100 ns** | Full 8-check evaluation pipeline, selector miss exits at check 3 |
| One scenario active, match, no terminal effect | **< 300 ns** | All 8 checks pass, no effect applied |
| Session scope miss (wrong session ID) | **< 20 ns additional** | ThreadLocal read + identity compare, exits before selector evaluation |
| 10 active scenarios, one match | **< 1 µs** | Linear registry scan, all misses exit at selector check |

For reference: HikariCP connection borrow ~5–15 µs · local TCP roundtrip ~50–200 µs · `Thread.sleep(1)` ~1 ms. The agent overhead is below the noise floor of any realistic I/O call.

### What the JIT does

ByteBuddy advice bytecode is **copied verbatim into the target method body** at retransformation time — not called, *inlined at the bytecode level*. After JIT warm-up, the C2 compiler inlines the `MethodHandle.invoke()` dispatch chain through `ChaosBridge` into `ChaosDispatcher`. In the zero-scenario case the entire hot path reduces to a null check and an untaken branch.

The `volatile` read of `ChaosDispatcher`'s scenario registry is a single acquire load. On x86 TSO (total store order) the hardware guarantees load-load ordering without an `MFENCE` instruction — the acquire semantics cost zero additional cycles versus a plain read on Intel/AMD. On AArch64 it compiles to `LDAR` (load-acquire), which prevents speculative execution of dependent loads past the registry pointer.

### Benchmarks

`chaos-agent-benchmarks` contains a full JMH 1.37 suite across JDBC and HTTP client hot paths at all scenario-count variants. Run with:

```bash
./gradlew :chaos-agent-benchmarks:run
```

See [`docs/benchmarks.md`](docs/benchmarks.md) for full JIT warm-up analysis, result interpretation, and production calibration against real I/O costs.

---

## Build

```bash
./gradlew build                        # compile + test all modules
./gradlew test                         # tests only
./gradlew :chaos-agent-bootstrap:jar   # produce the agent JAR
./gradlew :chaos-agent-benchmarks:run  # run JMH benchmarks
```

Requires JDK 17+. Build toolchain targets JDK 25; `--release 21` is enforced.

---

## Detailed Documentation

Internal Architecture documentation lives in [`docs/`](docs/):

| Document | What it covers |
|----------|---------------|
| [`overall-agent.md`](docs/overall-agent.md) | System Architecture, all analysis dimensions, stack walkdown, PlantUML diagrams |
| [`api.md`](docs/api.md) | Stable API contract: builders, selectors, effects, diagnostics |
| [`core.md`](docs/core.md) | Evaluation pipeline, session scoping, stressor lifecycle, JMM analysis |
| [`instrumentation.md`](docs/instrumentation.md) | ByteBuddy advice, bootstrap bridge, reentrancy guard, 42-handle table |
| [`bootstrap.md`](docs/bootstrap.md) | Agent initialization, self-attach, MBean registration |
| [`startup-config.md`](docs/startup-config.md) | Config source resolution, JSON schema, path safety |
| [`testkit.md`](docs/testkit.md) | JUnit 5 extension, session lifecycle, anti-patterns |
| [`spring-integration.md`](docs/spring-integration.md) | Spring Boot 3 and 4 starters: `@ChaosTest`, `ChaosAgentExtension`, Actuator endpoint, configuration reference |
| [`benchmarks.md`](docs/benchmarks.md) | JMH benchmark suite: hot-path targets, JIT analysis, result interpretation, `ChaosDispatcher` vs `ChaosRuntime` profiling |

---

## License

Apache License 2.0 — see [LICENSE](LICENSE).

---

<div align="center">

*Architecture, implementation, and documentation crafted by*

**[Christian Schnapka](https://macstab.com)**
Embedded Principal+ Engineer
[Macstab GmbH](https://macstab.com) · Hamburg, Germany

*Building systems that operate correctly at the edges — including the ones you deliberately break.*

</div>
