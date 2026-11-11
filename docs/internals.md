# Runtime Mechanics and JVM Internals

The layers under the [README](../README.md)'s architecture overview: how one call is evaluated, how JDK classes reach the agent, and what the JVM does underneath. The normative rules are in [semantics.md](semantics.md); this page explains how they are carried out.

## Floor -2 — Runtime mechanics (principal-level)

### Evaluation Pipeline

Every intercepted operation that some scenario is indexed under runs through `ScenarioController.evaluate()`: nine checks in a fixed order, the first failing one ends the evaluation. The order, and which steps count as a match, is set out in [semantics.md §3](semantics.md#3-the-policy-of-one-scenario); what each costs:

1. Started: `AtomicBoolean.get()`, a volatile read.
2. Session: `String.equals()` against the scenario's session; a JVM-scoped scenario has none and passes.
3. Selector: `SelectorMatcher.matches()`, an exhaustive `switch` over the sealed `ChaosSelector` types; stateless, no allocation.
4. Expiry: `activeUntil` / `activeFor` against the real clock; an expired scenario turns `INACTIVE`.
5. Not yet active: before `activeFrom` the call is skipped without being counted.
6. Warm-up: `matchedCount.incrementAndGet()`, then `matched > activateAfterMatches`.
7. Probability: `new SplittableRandom(baseSeed ^ rotl(matched, 32) ^ id.hashCode() · φ).nextDouble()`, before the rate limit so permits are spent only on calls that will apply.
8. Rate limit: a fixed window anchored at the scenario's start, under `synchronized(this)` (a burst across a window boundary can see up to twice the permits).
9. Max applications: a reservation against the `appliedCount` budget, so concurrent callers cannot overshoot it.

**Why a reservation at step 9?** A naive `incrementAndGet()` then compare pattern allows N racing threads to simultaneously read `count < max`, all increment past the cap, and all apply the effect. Committed applications plus outstanding reservations never exceed the cap. The dispatcher then applies the merged decision in the order delay → gate → terminal action and commits only the contributions that take effect (every delay, the winning gate, the winning terminal action by precedence) — those increment `appliedCount` and publish `APPLIED`; a scenario that loses on precedence releases its reservation and keeps its budget.

### Classloader Bridge

JDK classes (`Thread`, `Socket`, `System`, etc.) are loaded by the **bootstrap classloader** — the root of the classloader hierarchy with no parent. ByteBuddy advice woven into these classes executes *in* the bootstrap classloader's namespace, which cannot see agent classes by name. The bridge:

1. At startup, `BootstrapDispatcher.class` bytecode (plus the few types woven advice references) is extracted from the agent JAR, written to a temp JAR, and appended to the bootstrap classpath via `Instrumentation.appendToBootstrapClassLoaderSearch`
2. A 79-slot `MethodHandle[]` array (`BootstrapDispatcher.HANDLE_COUNT`) is built against `BridgeDelegate.class` using `MethodHandles.publicLookup()` and wired into `BootstrapDispatcher.install()` via reflection against the bootstrap-classloader version (`Class.forName("...BootstrapDispatcher", true, null)`)
3. `handles` is written to the `volatile` field **before** `delegate` — the Java Memory Model's happens-before rule on volatile writes guarantees any thread that observes `delegate != null` also observes the fully-initialized `handles` array

### Reentrancy Guard

Chaos code itself calls instrumented JDK methods (`Thread.sleep`, `LockSupport.park`, `ConcurrentHashMap` internals). Without protection, each chaos dispatch would trigger another chaos dispatch, recursing until stack overflow. The guard:

```
DEPTH : ThreadLocal<int[]>  (bootstrap-classloader resident)
every dispatch method (guard inlined, no shared trampoline, no per-call lambda):
  int[] depth = DEPTH.get()
  if depth[0] > 0 → return fallback immediately
  depth[0]++
  try { ... dispatch ... }
  finally { depth[0]-- }
```

The `int[]` is kept for the thread's lifetime rather than removed when the depth returns to zero: it also holds the call-depth scope counters that make nested or delegating instrumented calls (an HTTP client calling another instrumented client, a JDBC proxy delegating to the driver) evaluate chaos only once, at the outermost call. Its footprint is one small array per thread that ever reaches an instrumented call, released with the thread.

A second recursion risk: `ThreadLocal.get()` is itself instrumented once the `THREAD_LOCAL` hook group is woven. Reading `DEPTH.get()` inside a dispatch method would re-trigger `ThreadLocalGetAdvice`, which would call `BootstrapDispatcher.beforeThreadLocalGet()`, which would call `DEPTH.get()` ... The advice contains an identity check:

```java
if (BootstrapDispatcher.isDepthThreadLocal(threadLocal)) return false;
```

This single pointer-equality check is the only thing preventing infinite recursion at that specific callsite.

---

## Floor -3 — JVM internals, bytecode, and OS mechanics (the 1% layer)

### ByteBuddy Advice and the JVM Retransformation Mechanism

ByteBuddy instrumentation uses `AgentBuilder` with `RedefinitionStrategy.RETRANSFORMATION` and `disableClassFormatChanges()`. What this means at the bytecode level:

- **`disableClassFormatChanges()`** limits ByteBuddy to what `RetransformClasses` allows: a retransformation may change method bodies, the constant pool and attributes, but may not add, remove or rename fields or methods, change signatures or modifiers, or change the class hierarchy. Advice therefore inlines into existing method bodies and keeps its state in locals or in agent-side classes.
- **`@Advice.OnMethodEnter`** bytecode is *copied*, not called, into the target method at its entry. The JVM sees one method body; once the method is hot, the JIT compiles the advice with it, and the idle path is a static field read and a branch.
- **Clock intrinsics.** `System.currentTimeMillis()` and `System.nanoTime()` are `native` and `@IntrinsicCandidate`: there is no bytecode body to weave advice into, and HotSpot compiles calls to them into direct calls to the VM's clock (`clock_gettime` through the vDSO on Linux) that bypass any Java wrapper. So `ClockSkewEffect` cannot be woven into those methods. Direct calls are skewed instead at their call sites in the packages named with `clockCallSites=com.example.` (the agent rewrites the call instructions; JDK packages are refused), or through `ChaosRuntime.adjustClockMillis()` / `adjustClockNanos()`. The instrumented `java.time` reads (`Instant.now()`, `LocalDateTime.now()`, `ZonedDateTime.now()`, including the `ZoneId` overloads, and `new Date()`) are skewed automatically.

### JMM Happens-Before in the Bootstrap Bridge

The two-field publication in `BootstrapDispatcher.install()`:

```java
handles = methodHandles;  // volatile write W1
delegate = bridgeDelegate; // volatile write W2
```

By the Java Memory Model ([JLS §17.4.4–17.4.5](https://docs.oracle.com/javase/specs/jls/se21/html/jls-17.html#jls-17.4.4)):

> A write to a volatile field happens-before every subsequent read of that field.

W1 happens-before W2 (program order + volatile ordering). Any thread T that reads `delegate != null` (volatile read R2) has R2 synchronizes-with W2, and W2 happens-after W1. By transitivity: `handles` is visible to T. On x86-64 HotSpot compiles a volatile store to a `MOV` followed by a `LOCK`-prefixed instruction as a full fence; on AArch64 to `STLR` (store-release), and a volatile load to `LDAR` (load-acquire).

### `AtomicLong.incrementAndGet()` on x86-64

```java
matchedCount.incrementAndGet()
// compiles to:
LOCK XADD [rsi+offset], 1   ; atomic fetch-and-add on x86-64
// or equivalently via CAS loop:
LOCK CMPXCHG [rsi+offset], rax
```

The `LOCK` prefix on x86 asserts the cache coherency protocol (MESI) for the cache line containing the field, issues a full memory barrier (both acquire and release semantics), and ensures atomicity across hyperthreads sharing an L1 cache. On AMD Zen and Intel Architectures with MESIF, this causes a cache-line ownership transfer if another core holds the line in Modified state — the latency spike is 40–70 cycles for cross-core coherency vs. ~4 cycles for same-core hits.

**False sharing risk**: `matchedCount` and `appliedCount` are two `AtomicLong` objects allocated one after the other with the controller, so they usually share a 64-byte cache line. When many threads count matches while others commit applications, that line moves between cores. This is an accepted trade-off: `matchedCount` is touched only by calls that already match a scenario, never on the idle path, and padding (`@Contended` is JDK-internal) would cost 128 bytes per controller.

### `synchronized(this)` — Rate Limit and the HotSpot Lock Inflation Protocol

The rate-limit check is `synchronized(this)` on the `ScenarioController` instance ([JVMS §6.5 `monitorenter`](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-6.html#jvms-6.5.monitorenter), opcode `0xC2`). On the JDKs the agent supports:

1. **Biased locking is gone**: disabled by default in JDK 15 ([JEP 374](https://openjdk.org/jeps/374)) and removed in JDK 18, so there is no per-thread bias to revoke.
2. **Lightweight (thin) lock**: an uncontended acquire is one CAS on the object's mark word (JDK 21) or a push onto the thread's lock stack (the lightweight locking that becomes the default later); no OS call.
3. **Inflated monitor**: under contention HotSpot inflates to an `ObjectMonitor`; waiting threads spin briefly, then block on a `ParkEvent` (`futex(2)` on Linux).

Rate-limited scenarios contend rarely, so the uncontended CAS path dominates.

### `LockSupport.park()` and OS Thread Scheduling

`LockSupport.park(blocker)` ([JDK source: `java.util.concurrent.locks.LockSupport`](https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/util/concurrent/locks/LockSupport.java)) maps to `Unsafe.park(false, 0L)` → JVM intrinsic → OS-level thread suspension:

- **Linux**: `pthread_cond_timedwait(3)` → `futex(2)` with `FUTEX_WAIT_BITSET` or `FUTEX_WAIT`. The thread is removed from the kernel run queue, its `task_struct` state set to `TASK_INTERRUPTIBLE`, and control returned to the scheduler. Wakeup via `LockSupport.unpark()` calls `futex(FUTEX_WAKE)`.
- **macOS**: the same POSIX path, `pthread_cond_timedwait(3)` on HotSpot's `Parker`; the kernel side is a Mach wait.
- **Timer resolution**: with high-resolution timers (`CONFIG_HIGH_RES_TIMERS=y`, the default on modern kernels) a timed park wakes within microseconds of its deadline on an idle machine; under load the woken thread still waits for a CPU, so `Thread.sleep(1)` can take noticeably longer than 1 ms. Injected delays are lower bounds, not exact durations.

The `THREAD_PARK` instrumentation fires a `beforeThreadPark()` dispatch before the actual park. If a `DelayEffect` is configured, the summed delay is slept with `Thread.sleep` — which itself calls `park`, which the reentrancy DEPTH guard intercepts and returns the fallback immediately. Without the DEPTH guard, a 100ms chaos delay on `THREAD_PARK` would cause infinite `sleep → park → chaos eval → sleep → park ...` recursion.

### Safepoint Mechanics and `SafepointStormStressor`

HotSpot safepoints are JVM-global stop-the-world pauses. All application threads must reach a "safe point" — a location in the bytecode where the JVM knows the full GC root set. The `SafepointStormStressor` deliberately triggers them by calling `System.gc()` on a timer and, when it has an `Instrumentation` and `retransformClassCount > 0`, `Instrumentation.retransformClasses()` on a sample of loaded classes.

How a safepoint is reached on current HotSpot:
1. The VM thread arms every Java thread's poll word ([thread-local handshakes, JEP 312](https://openjdk.org/jeps/312)).
2. JIT-compiled code polls at loop back-edges and method returns; an armed poll diverts the thread into the VM, where it blocks until the operation ends.
3. The interpreter checks at branches and returns; threads in native code or blocked count as safe already and are held only when they return to Java.
4. With every thread stopped, the VM runs the operation (a full GC, a class redefinition, a deoptimisation) and releases them.

`SafepointStormStressor` calls `System.gc()` (and the retransformations) on a timer; each requires a safepoint (a `System.gc()` only where explicit collections are not disabled: under `-XX:+DisableExplicitGC` a storm without retransformations is refused at activation), so every application thread stops for its duration. This simulates STW pause pressure that would appear in production under heavy GC load or JVM agent activity.

### Virtual Threads and Carrier-Thread Pinning Risk

On JDK 21+ ([JEP 444 — Virtual Threads](https://openjdk.org/jeps/444)), virtual threads (`Thread.ofVirtual()`) are scheduled by the JVM's `ForkJoinPool`-based scheduler, mounted on platform carrier threads. A virtual thread that calls `synchronized` blocks *pins* its carrier thread — the carrier cannot be reassigned to another virtual thread while the virtual thread holds a monitor.

The `MONITOR_ENTER` operation is hooked at `AbstractQueuedSynchronizer.acquire()`, the path of `ReentrantLock` and the other `java.util.concurrent` locks, not at `synchronized` blocks. A `DelayEffect` on it delays each lock acquisition. On a virtual thread the delay is a `Thread.sleep`, which unmounts the virtual thread and frees its carrier, unless the thread is pinned at that moment: on JDK 21–23 that is the case inside a `synchronized` block or a native frame ([JEP 444](https://openjdk.org/jeps/444)); from JDK 24 `synchronized` no longer pins ([JEP 491](https://openjdk.org/jeps/491)). So a delay injected while pinned holds the carrier for its whole duration, and under load that can exhaust the carrier pool, which is the failure the [`sb4-virtual-thread-pinning`](../chaos-agent-examples/sb4-virtual-thread-pinning) example demonstrates.

### `SplittableRandom` — Why Not `ThreadLocalRandom`?

`ScenarioController.passesProbability()` creates a fresh `SplittableRandom` from the seed, the match number and the scenario id per call. Why not `ThreadLocalRandom.current().nextDouble()`?

1. **Reproducibility**: `ThreadLocalRandom` is seeded per thread from a process-wide seed generator that cannot be fixed. With a fixed `randomSeed` in `ActivationPolicy`, we need deterministic sampling across runs — same seed + same `matchedCount` = same draw. `SplittableRandom` with an explicit seed satisfies this; `ThreadLocalRandom` does not.
2. **Thread-safety**: `SplittableRandom` is not thread-safe ([JDK API](https://docs.oracle.com/en/java/docs/api/java.base/java/util/SplittableRandom.html)). Creating a new instance per call (cheap — 3 `long` fields) avoids any shared-state issue. The seed mixes in the match number `matched` (rotated) and the scenario id (multiplied by the golden-ratio constant), so successive matches and different scenarios draw independently.
3. **Why not `Random(seed).nextDouble()`?** `java.util.Random` uses a linear congruential generator with `AtomicLong` state — it's thread-safe but that safety is achieved via CAS, adding unnecessary contention. `SplittableRandom` is SplitMix64: a counter advanced by a constant, finalised by a 64-bit mixing function, with no synchronisation and a statistically strong first output, which matters here because each instance is used once.
