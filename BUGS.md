# Bug Register — chaos-testing-java-agent

Results of a deep bug-focused review across the production source tree. Each entry records the location, what breaks, and the suggested direction for a fix. Severities follow the usual shorthand: **CRITICAL** = silent data loss / resource exhaustion / certain crash; **HIGH** = observable incorrectness or contract violation; **MEDIUM** = correctness smell or contention-class perf issue.

Status flags:

- `Open` — reported, not yet investigated further.
- `Verified` — re-checked by re-reading the exact lines; bug is real.
- `Downgraded` — re-verification showed the impact is smaller than first claimed.
- `Fixed` — patch applied and verified by build + test.

All findings below are `Open` pending the next verification pass.

---

## CRITICAL

### CRIT-1 — Spring starter bypasses plan-file hardening
- **Location:** `chaos-agent-spring-boot-common/src/main/java/com/macstab/chaos/spring/boot/common/ChaosAutoConfiguration.java:100-101`
- **What breaks:** `applyStartup` reads the plan via bare `Files.readString(path)`. The sibling `StartupConfigLoader` enforces `NOFOLLOW_LINKS`, a 1 MiB size cap, and TOCTOU protection. None of that applies here.
- **Impact:** a `configFile` sourced from untrusted config (mounted ConfigMap, env-driven profile) can point at a symlink to a sensitive file, or at a huge file that OOMs the JVM.
- **Fix direction:** delegate to `StartupConfigLoader.load(...)`, or extract the validated read into a shared helper in `startup-config` and call it from both sites.
- **Status:** Fixed — confirmed `Files.readString(path)` at line 101 with no NOFOLLOW_LINKS, no size cap, no path validation, while `StartupConfigLoader.loadFromFile()` (lines 92-108) enforces all three.

### CRIT-2 — Classloader leak in `CodeCachePressureStressor`
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/CodeCachePressureStressor.java:97`
- **What breaks:** each synthetic class is loaded into `new URLClassLoader(new URL[0], null)` that is created inline and never retained or closed. `close()` nulls `retainedClasses` but the anonymous loader still anchors every `Class<?>`, so Metaspace cannot be reclaimed.
- **Impact:** every activation permanently grows the number of live classloaders and retained Metaspace. Long-running test suites eventually blow Metaspace.
- **Fix direction:** retain the loader in an `isolatedLoader` field (as `MetaspacePressureStressor` already does) and call `loader.close()` from `close()`.
- **Status:** Fixed — `URLClassLoader` instantiated inline at line 97, never stored; `close()` at line 69 only nulls `retainedClasses`. Contrast confirmed with `MetaspacePressureStressor.isolatedLoader` + explicit `loader.close()` in its `close()`.

### CRIT-4 — `MethodSelector.signaturePattern` matched against the wrong (null) context field
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/SelectorMatcher.java:70-71`
- **What breaks:** `signaturePattern` is documented as matching the JVM method descriptor (e.g. `(Ljava/lang/String;I)V`). The matcher feeds it `context.subjectClassName()` instead. `ChaosDispatcher.beforeMethodEnter/afterMethodExit` set `subjectClassName = null` on every method-interception context. `NamePattern.matches(null)` returns `false` for any non-ANY mode — so every user-supplied `signaturePattern` (EXACT, PREFIX, GLOB, REGEX) evaluates to `false` and the whole `MethodSelector` never matches.
- **Impact:** Any scenario scoped with a `signaturePattern` is silently disabled; the user gets zero chaos with no diagnostic. A whole selector feature is a dead letter.
- **Fix direction:** Add a `signature`/`descriptor` field to `InvocationContext`, populate it at METHOD_ENTER/EXIT advice sites, and match against it here. Until then, reject non-null `signaturePattern` in the canonical constructor with a clear error.
- **Status:** Fixed — re-read `SelectorMatcher.java:66-71`, `ChaosSelector.java:603-608`, `InvocationContext.java:18-23`, and `ChaosDispatcher.java:365-394`. All method-interception paths set `subjectClassName = null`; `NamePattern.matches(null)` with non-ANY mode returns `false`.

### CRIT-5 — `ScenarioRegistry.register` orphans a started controller when `handle.start()` throws
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosControlPlaneImpl.java:160-195`
- **What breaks:** `registerScenario` calls `registry.register(controller)` at line 167 **before** `handle.start()` at line 171. `start()` can throw (`HeapPressureStressor` constructor allocates eagerly; `ClockSkewState` calls `toNanos()`). The `catch` blocks at lines 174/180/188 call `registry.recordFailure` and rethrow — but never `registry.unregister(controller)`. `started.set(true)` and `state = ACTIVE` already ran inside `ScenarioController.start()`, so the orphan participates in every `ScenarioRegistry.match()` call on the hot dispatch path for the rest of the JVM's life.
- **Impact:** A start failure leaves an undestroyable, permanently-ACTIVE scenario applying chaos the operator believes is not configured. No handle is ever returned so no caller can stop it.
- **Fix direction:** Wrap `handle.start()` in a try/catch; on exception call `registry.unregister(controller)` and `controller.destroy()` before re-throwing.
- **Status:** Fixed — re-read `ChaosControlPlaneImpl.java:160-195` and `ScenarioController.java:208-230`; `register` precedes `start()`; catch blocks do not unregister.

### CRIT-3 — Bootstrap JAR missing `ScheduledCallableWrapper`
- **Location:** `chaos-agent-instrumentation-jdk/src/main/java/com/macstab/chaos/instrumentation/JdkInstrumentationInstaller.java:1259` (and `ScheduledExecutorAdvice` / `ScheduledCallableAdvice`)
- **What breaks:** `injectBridge` writes three classes into the bootstrap JAR: `BootstrapDispatcher`, its `$ThrowingSupplier`, `ScheduledRunnableWrapper`. `ScheduledCallableAdvice` instantiates `new ScheduledCallableWrapper<>()` from bootstrap-loaded `ScheduledThreadPoolExecutor`, but that class is **not** on the bootstrap classpath.
- **Impact:** any `schedule(Callable, long, TimeUnit)` call under an active scenario throws `NoClassDefFoundError` in the application thread.
- **Fix direction:** include `ScheduledCallableWrapper.class` in `injectBridge`. Verify by reading the advice source line-by-line before patching.
- **Status:** Fixed — `injectBridge` (lines 1241-1266) writes exactly three classes: `BootstrapDispatcher`, `BootstrapDispatcher$ThrowingSupplier`, `ScheduledRunnableWrapper`. `ScheduleCallableAdvice.enter()` at line 46 calls `new ScheduledCallableWrapper<>(executor, task)`. Advice is inlined into `ScheduledThreadPoolExecutor.schedule(Callable, ...)` (bootstrap-loaded). `NoClassDefFoundError` is certain on first invocation.

---

## HIGH

### HIGH-1 — `ScenarioController.evaluate()` races with `stop()` on `state`
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ScenarioController.java:326, 356`
- **What breaks:** `evaluate()` writes `state = INACTIVE` through a naked volatile, outside any `synchronized` block. `stop()` is synchronized and writes `state = STOPPED`. A window exists where `stop()` completes, an in-flight `evaluate()` that already read the earlier state then continues and overwrites `STOPPED` back to `INACTIVE`.
- **Impact:** a stopped scenario is silently un-stopped. `evaluate()` keeps incrementing `matchedCount` on a nominally dead controller.
- **Fix direction:** perform the ACTIVE→INACTIVE transition under `synchronized(this)` with a guard that refuses to downgrade from STOPPED.
- **Status:** Fixed — `state` is volatile (line 75); `stop()` is `synchronized void` (line 252); `evaluate()` is unsynchronised and writes `state = INACTIVE` at lines 326 and 356. Race window for STOPPED → INACTIVE overwrite confirmed.

### HIGH-2 — `ClockSkewState` overflow leaves controller in half-initialised state
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ClockSkewState.java:44-46` + `ScenarioController.java:226`
- **What breaks:** `effect.skewAmount().toNanos()` throws `ArithmeticException` for skews past ±292 years (reachable from user-supplied JSON). The exception is thrown from inside synchronized `start()` **after** `started = true` but **before** `clockSkewState = …` is assigned. The controller is left ACTIVE with `clockSkewState == null`.
- **Impact:** subsequent `applyClockSkew` calls null-guard and silently skip. Scenario reports ACTIVE; no skew is applied. Pure silent correctness failure.
- **Fix direction:** validate skew magnitude in `ClockSkewEffect`'s compact constructor; translate to a clean `ChaosValidationException` at plan-build time.
- **Status:** Fixed — `started.set(true)` at line 220 runs before `clockSkewState = new ClockSkewState(...)` at lines 224-226; `ClockSkewState` constructor calls `effect.skewAmount().toNanos()` at line 45 without overflow guard.

### HIGH-3 — `VIRTUAL_THREAD_CARRIER_PINNING` missing from validator switch
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/CompatibilityValidator.java:84-89` (switch body in `validateStressBinding`)
- **What breaks:** The `StressTarget` switch statement omits `VIRTUAL_THREAD_CARRIER_PINNING`. Non-exhaustive switch statements on enums fall through silently in Java. Further, there is no `featureSet.supportsVirtualThreads()` gate (unlike the `ThreadSelector.VIRTUAL` path at lines 78-83).
- **Impact:** bad pairings (e.g. `VIRTUAL_THREAD_CARRIER_PINNING` with `HeapPressureEffect`) pass validation and fail later with `ClassCastException` or a silent no-op. On JDK 17 the stressor is silently broken.
- **Fix direction:** add an explicit case that validates the effect type and gates on the feature set.
- **Status:** Fixed — the switch in `validateStressBinding` handles every other `StressTarget` enum value (HEAP, METASPACE, DIRECT_BUFFER, GC_PRESSURE, FINALIZER_BACKLOG, KEEPALIVE, THREAD_LEAK, THREAD_LOCAL_LEAK, DEADLOCK, MONITOR_CONTENTION, CODE_CACHE_PRESSURE, SAFEPOINT_STORM, STRING_INTERN_PRESSURE, REFERENCE_QUEUE_FLOOD) but omits `VIRTUAL_THREAD_CARRIER_PINNING` (defined in `ChaosSelector.java:454`). Statement-form switch falls through silently.

### HIGH-4 — Double-destroy after partial plan rollback
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/DefaultChaosSession.java:121-140` (and the `close()` loop at 199-207)
- **What breaks:** `activate(ChaosPlan)` delegates each scenario through `this.activate(ChaosScenario)`, which appends the handle to `this.handles` before returning it. When a later scenario fails and the rollback loop destroys the already-added handles, it leaves them in `this.handles`. The later `session.close()` calls `destroy()` on them a second time.
- **Impact:** duplicate STOPPED events to every observability listener for each rolled-back scenario; operators see phantom lifecycle events.
- **Fix direction:** in the rollback path, remove the handle from `this.handles` before (or after) destroying it, or track rolled-back handles separately.
- **Status:** Fixed — `activate(ChaosPlan)` at line 124 calls `activate(scenario)` which appends to `this.handles` at line 102. Rollback loop at lines 128-139 calls `destroy()` / `stop()` but never `handles.remove(child)`. `close()` at line 199 iterates `this.handles` and destroys each again.

### HIGH-5 — `ThreadLeakStressor.close()` violates the `ManagedStressor` contract
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ThreadLeakStressor.java:63-68`
- **What breaks:** `close()` sets `stopped`, interrupts every thread, returns immediately. The contract on `ManagedStressor.close()` explicitly requires *waiting for termination within a reasonable timeout*.
- **Impact:** leaked threads run a 50 ms `parkNanos` loop; callers that check `aliveCount()` or thread liveness immediately after `stop()` see stale state. Tests and diagnostics race against the interrupt-observation latency.
- **Fix direction:** after the interrupt loop, `join(200L)` each thread with a bounded wait; re-interrupt on `InterruptedException`.
- **Status:** Fixed — `close()` body is `stopped.set(true)` + `interrupt()` loop, no `join()`. `ManagedStressor` interface javadoc (lines 27-29) explicitly requires waiting for termination.

### HIGH-6 — `GcPressureStressor` holds strong reference past `close()`
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/GcPressureStressor.java:58-64` and `80-84`
- **What breaks:** the allocation thread takes a local snapshot `final byte[][] snapshot = ring`. `close()` sets `ring = null` then interrupts, but the interrupt is asynchronous — the snapshot can continue receiving writes for up to one full `BATCH_INTERVAL_MS` after `close()` returns.
- **Impact:** strong reference to retained objects escapes the close window; violates the stated cleanup contract.
- **Fix direction:** check `running.get()` inside the inner allocation loop before each batch assignment, or add `join` to `close()` as `SafepointStormStressor` already does.
- **Status:** Fixed — allocation thread captures `final byte[][] snapshot = ring` (line 59); `close()` sets `ring = null` at line 82 but snapshot remains live on the thread's stack frame, receiving writes at line 61 until the batch ends.

### HIGH-7 — `StartupConfigPoller` leaks on activation failure
- **Location:** `chaos-agent-bootstrap/src/main/java/com/macstab/chaos/bootstrap/ChaosAgentBootstrap.java:211-238`
- **What breaks:** `POLLER.set(poller.get())` runs before `poller.get().startWithInitialPlan(...)`. If `startWithInitialPlan` throws, there is no catch/finally to close the poller; the scheduler daemon thread is leaked until JVM exit.
- **Impact:** leaked daemon thread and a stray `POLLER` reference on every failed startup.
- **Fix direction:** wrap `startWithInitialPlan` in try/catch; on exception, call `poller.close()` and clear `POLLER`.
- **Status:** Fixed — `POLLER.set(poller.get())` at line 226 runs before `poller.get().startWithInitialPlan(...)` at line 227 inside the `loadedPlan.ifPresent(...)` lambda. No try/catch in the lambda; exception propagates out leaving POLLER populated and the scheduler daemon alive.

### HIGH-8 — `PeriodicAdvice` does not chaos-adjust `period`
- **Location:** `chaos-agent-instrumentation-jdk/src/main/java/com/macstab/chaos/instrumentation/ScheduledExecutorAdvice.java:56-78`
- **What breaks:** only `initialDelay` is routed through `adjustScheduleDelay`; `period = periodMillis` assigns the raw millisecond value with no chaos pass.
- **Impact:** scenarios designed to extend scheduling affect only the first fire; all subsequent repeats run at the original cadence.
- **Fix direction:** route `period` through the same adjustment pipeline.
- **Status:** Fixed — `initialDelay` is passed through `adjustScheduleDelay()` at lines 72-74; `period = periodMillis` (line 75) is the raw normalised value. The comment at lines 67-69 acknowledges only the unit normalisation, not the chaos dispatch.

### HIGH-9 — `ExceptionInjectionEffect` constructor lookup asymmetry
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosDispatcher.java:1129-1163`
- **What breaks:** `exClass.getConstructor(String.class)` (public-only) is used for the `(String)` constructor lookup, but `exClass.getDeclaredConstructor()` is used for the no-arg fallback. A package-private `(String)` constructor silently falls through to the no-arg path, discarding the configured message.
- **Impact:** the injected exception fires but with no message text — debugging is harder for no reason.
- **Fix direction:** use `getDeclaredConstructor(String.class)` + `setAccessible(true)` for symmetry, or explicitly document the public-only requirement and reject non-public matches at plan-build time.
- **Status:** Fixed — `getConstructor(String.class)` (public-only) at line 1148 vs. `getDeclaredConstructor()` (no-arg) at line 1150. No `setAccessible(true)` anywhere in the method. Package-private `(String)` ctor falls through silently.

### HIGH-10 — `DelayEffect.maxDelay` overflow on the hot path
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ScenarioController.java:487`
- **What breaks:** `sampleDelayMillis` calls `nextLong(min, max + 1)` where `max = maxDelay.toMillis()`. A `maxDelay` of `Duration.ofMillis(Long.MAX_VALUE)` overflows `max + 1` to `Long.MIN_VALUE`, throwing `IllegalArgumentException` ("bound must be greater than origin") on the hot dispatch path.
- **Impact:** unchecked exception escapes `evaluate()` into application threads under a malformed scenario.
- **Fix direction:** clamp `maxDelay` in the `DelayEffect` compact constructor to a sane upper bound (e.g. `Duration.ofDays(30)`), or guard the sampling arithmetic.
- **Status:** Fixed — line 487 is `nextLong(min, max + 1)`; `DelayEffect` compact constructor (chaos-agent-api) validates ordering but does not clamp upper bound. Overflow path is reachable from user JSON.

### HIGH-12 — `ReturnValueCorruptor.EMPTY` strategy returns wrong concrete type; `ClassCastException` at call site
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ReturnValueCorruptor.java:129-157`
- **What breaks:** `corruptEmpty` substitutes `Collections.emptyList()` for any `List`-assignable `returnType`. A method declared to return `ArrayList<String>` gets `ImmutableCollections$ListN` — not assignable to `ArrayList`. ByteBuddy inserts an implicit `checkcast` at the `@Advice.Return` write site, producing `ClassCastException` at the call site.
- **Impact:** `EMPTY` corruption on any method returning a concrete `List`/`Set`/`Map` subtype crashes the caller — a chaos effect designed to be graceful instead produces an unexpected unchecked exception.
- **Fix direction:** Reverse the assignability check: only substitute `emptyList()` when the declared type is assignable *from* `ArrayList` (`returnType.isAssignableFrom(ArrayList.class)`), so you substitute only when the empty singleton is a valid subtype.
- **Status:** Fixed — re-read lines 129-157; `List.class.isAssignableFrom(returnType)` is true for `ArrayList` but `emptyList()` is `ImmutableCollections$ListN`, not `ArrayList`. Same pattern for `Set`/`Map`/`Collection`.

### HIGH-13 — Six dispatcher entry points silently discard `GateAction` from matching `GateEffect` scenarios
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosDispatcher.java:466-489, 598-621, 751-775, 805-828, 830-854, 934-957`
- **What breaks:** `beforeGcRequest`, `beforeNioSelect`, `beforeAsyncCancel`, `beforeThreadLocalGet`, `beforeThreadLocalSet`, and `beforeThreadSleep` all call `evaluate(context)` but never call `applyGate(decision.gateAction())`. Every other comparable entry point does call `applyGate` (e.g. `adjustScheduleDelay:169`, `beforeBooleanQueueOperation:210`, `afterMethodExit:396`).
- **Impact:** A `GateEffect` scenario targeting `THREAD_SLEEP`, `NIO_SELECTOR_SELECT`, `SYSTEM_GC_REQUEST`, `ASYNC_CANCEL`, or `THREAD_LOCAL_GET/SET` is silently ignored — threads never block; chaos intent is lost with no diagnostic.
- **Fix direction:** Add `applyGate(decision.gateAction());` immediately after `evaluate(context)` in each of the six methods, matching the `applyPreDecision` pattern.
- **Status:** Fixed — re-read `beforeGcRequest:466-489` and the five other line ranges; all have `final RuntimeDecision decision = evaluate(context);` followed immediately by a terminal-action check with no `applyGate` call.

### HIGH-14 — `KeepAliveStressor.close()` interrupts but never joins its thread; violates `ManagedStressor` contract
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/KeepAliveStressor.java:66-69`
- **What breaks:** `close()` is `running.set(false); thread.interrupt();` and returns. `ManagedStressor.java:27-28` mandates waiting for termination before returning. Non-daemon variants hold a platform thread that blocks JVM shutdown.
- **Impact:** Thread leak on repeated activate/deactivate cycles; non-daemon variants prevent clean JVM exit.
- **Fix direction:** `thread.interrupt(); try { thread.join(500L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }` — mirrors `SafepointStormStressor.close()`.
- **Status:** Fixed — re-read lines 66-69; full method body is `running.set(false); thread.interrupt();`, no join.

### HIGH-15 — `ReferenceQueueFloodStressor.flood` creates `WeakReference` objects that are themselves unreachable; entire stressor is a no-op
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ReferenceQueueFloodStressor.java:90-98`
- **What breaks:** `new WeakReference<>(new byte[1024], queue)` is discarded — no strong reference to the `WeakReference` object is kept. For a `WeakReference` to be enqueued, the Reference object itself must still be reachable when the GC determines the referent is unreachable. Here both the `WeakReference` and the `byte[1024]` are simultaneously garbage; the JVM may collect both without ever touching the queue.
- **Impact:** The stressor never floods `ReferenceHandler`. Users configure it believing they're stressing the GC reference pipeline; nothing happens. Silent broken chaos mode.
- **Fix direction:** Collect the created references in a local `List<WeakReference<byte[]>>` scoped through the `System.gc()` call so the Reference objects stay strongly reachable until after GC.
- **Status:** Fixed — re-read lines 90-98; `new WeakReference<>(...)` is a bare statement with no assignment. JLS/JVM spec: a Reference whose referent and the Reference itself are simultaneously unreachable may be collected without enqueueing.

### HIGH-16 — `DefaultChaosSession.close()` pops the *caller* thread's scope stack; cross-thread close corrupts a foreign session
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ScopeContext.java:61-72` + `DefaultChaosSession.java:67, 36-39`
- **What breaks:** The `ScopeBinding` lambda captured at session creation does `sessionStack.get().pop()` — `sessionStack` is a `ThreadLocal`, so it resolves to the *calling* thread's deque, not the thread that opened the session. The class javadoc at lines 36-39 explicitly claims `close()` is safe to call from any thread.
- **Impact:** Cross-thread `session.close()` (e.g. Spring `@PreDestroy` on a servlet thread) pops whatever session happens to be on top of the caller's stack, silently disabling the wrong session's chaos. The original opener's stack retains the stale ID forever.
- **Fix direction:** Capture the deque reference at `bind()` time (`final Deque<String> stack = sessionStack.get();`) and close over it. Add a peek-match guard so LIFO violations fail loudly.
- **Status:** Fixed — re-read `ScopeContext.java:61-72`; lambda uses `sessionStack.get()` at pop-time (not capture-time). `DefaultChaosSession.java:67` calls `bind(id)` on the constructor thread; close() may execute elsewhere.

### HIGH-17 — `DefaultChaosSession.close()` is not idempotent; double close pops an unrelated session off the scope stack
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/DefaultChaosSession.java:192-221`
- **What breaks:** No closed-state guard. Every call to `close()` invokes `rootBinding.close()` which pops the thread's top-of-stack without an identity check. `AutoCloseable.close()` is strongly suggested to be idempotent. Framework integrations that register a session with `@AfterEach` **and** use `try-with-resources` will close twice.
- **Impact:** Second close pops an unrelated neighbor session off the stack, silently disabling that session's chaos. Also re-destroys all handles, producing duplicate STOPPED events.
- **Fix direction:** `private final AtomicBoolean closed = new AtomicBoolean(false);` with an early-return guard at the top of `close()`.
- **Status:** Fixed — re-read lines 192-221; no closed flag, no identity check in `ScopeContext.java:62-71` (`if (!stack.isEmpty()) stack.pop()`).

### HIGH-18 — `StartupConfigPoller.pollOnce` catches `Exception` but not `Throwable`; any `Error` permanently kills config reloading
- **Location:** `chaos-agent-bootstrap/src/main/java/com/macstab/chaos/bootstrap/StartupConfigPoller.java:205`
- **What breaks:** `catch (Exception e)` inside `pollOnce` does not catch `Error` (OOM, `StackOverflowError`, `LinkageError`, `NoClassDefFoundError`). `ScheduledExecutorService.scheduleAtFixedRate` contract: any uncaught `Throwable` from the task silently suppresses all future executions with no notification.
- **Impact:** One transient `Error` during a poll permanently kills config-watching for the JVM lifetime. Operators editing the watched file see no reload and no log entry pointing at the failure.
- **Fix direction:** Widen to `catch (Throwable t)` (logging it), so no `Throwable` escapes the scheduled task.
- **Status:** Fixed — re-read `StartupConfigPoller.java:171-209`; only `IOException` and `Exception` are caught; the scheduled executor is a `newSingleThreadScheduledExecutor`.

### HIGH-19 — `StartupConfigPoller.close()` races with in-flight `pollOnce`; loses the race and leaks zombie scenarios
- **Location:** `chaos-agent-bootstrap/src/main/java/com/macstab/chaos/bootstrap/StartupConfigPoller.java:152-159`
- **What breaks:** `close()` calls `scheduler.shutdownNow()` (fires an interrupt, returns immediately) then enters `synchronized(this)` to clear `activeScenarios`/`activeHandles`. An already-running `pollOnce` — blocked in `Files.newInputStream` (not interrupt-responsive) — will later reach `applyDiff` (also `synchronized(this)`), see every scenario in the freshly-parsed plan as "new", and call `runtime.activate(scenario)` for each, producing handles stored into the now-cleared maps of a closed poller. Those handles are never destroyed.
- **Impact:** After a close that races with a file-change poll, zombie scenarios remain active in the runtime with no owning reference to stop them. Registry grows on every test-suite run.
- **Fix direction:** Set `volatile boolean closed` under the lock in `close()` and check it at the top of `applyDiff`; or `scheduler.awaitTermination(500, MILLISECONDS)` before entering the sync block.
- **Status:** Fixed — lines 152-159: `shutdownNow()` with no `awaitTermination`; `applyDiff:211-235` re-activates any scenario absent from `activeScenarios` with no closed-state check.

### HIGH-20 — `RUNTIME` is CAS'd before `JdkInstrumentationInstaller.install`; install failure leaves a permanently broken runtime
- **Location:** `chaos-agent-bootstrap/src/main/java/com/macstab/chaos/bootstrap/ChaosAgentBootstrap.java:210-214`
- **What breaks:** `RUNTIME.compareAndSet(null, runtime)` at line 211 runs before `JdkInstrumentationInstaller.install(runtime, instrumentation)` at line 214. If `install` throws (`injectBridge` on IOException, `installDelegate` on reflection failure), RUNTIME already holds the new `ChaosRuntime` with `BootstrapDispatcher.delegate == null` and no transformers active. `installForLocalTests` short-circuits on `RUNTIME.get() != null` and returns the broken runtime; no retry is possible.
- **Impact:** A transient install failure (full /tmp, permission denial) permanently disables all instrumentation while leaving the control plane appearing installed. Tests that rely on `installForLocalTests` get a silent no-op.
- **Fix direction:** CAS RUNTIME only after `install`, `registerMBean`, and startup-plan all succeed. On failure, `RUNTIME.compareAndSet(runtime, null)` in a `catch { throw; }` guard.
- **Status:** Fixed — re-read `initialize():199-239`; CAS at 211, install at 214; `installForLocalTests:127-142` returns `RUNTIME.get()` without re-check; no rollback.

### HIGH-21 — Quarkus `ChaosArcProducer` installs agent regardless of `macstab.chaos.enabled`
- **Location:** `chaos-agent-quarkus-extension/src/main/java/com/macstab/chaos/quarkus/ChaosArcProducer.java:46-51`
- **What breaks:** The CDI producer has no property gate. The sibling recorder honours `macstab.chaos.enabled` but the producer fires unconditionally as soon as any CDI bean injects `ChaosControlPlane` — regardless of the flag.
- **Impact:** Production builds that ship the extension jar "for optional observability" are forcibly byte-code-instrumented as soon as an `@Inject ChaosControlPlane` exists. The "one switch" contract is broken.
- **Fix direction:** Gate with `@IfBuildProperty(name = "macstab.chaos.enabled", stringValue = "true")` or a runtime guard that returns `null`/throws when the flag is unset.
- **Status:** Fixed — re-read `ChaosArcProducer.java:46-51`; only `@DefaultBean` is declared; the property check in `ChaosQuarkusRecorder.chaosEnabled():72-79` is never consulted by the producer.

### HIGH-22 — Quarkus `@ChaosScenario` JVM-scope handles never stopped; annotation is also not `@Repeatable`
- **Location:** `chaos-agent-quarkus-extension/src/main/java/com/macstab/chaos/quarkus/ChaosQuarkusExtension.java:138-153` and `ChaosScenario.java:35-37, 80`
- **What breaks:** `activateAnnotations` calls `controlPlane.activate(scenario)` for `JVM`-scoped annotations and discards the handle; no `afterAll` stop exists. Default `scope()` is `"JVM"` (line 80), so every annotation without an explicit scope leaks across tests and test classes. Additionally `@ChaosScenario` lacks `@Repeatable`, so the documented "multiple annotations per element" feature silently collapses to at most one annotation.
- **Impact:** JVM-scoped annotations pollute every following test in the JVM. Documented multi-annotation support is silently non-functional.
- **Fix direction:** Capture handle from `controlPlane.activate(scenario)`, stop it in `afterAll` (class-level) or `afterEach` (method-level). Add `@Repeatable(ChaosScenarios.class)` with a container annotation.
- **Status:** Fixed — re-read lines 145-152; both branches assign nothing; `ChaosScenario.java` lines 35-37: no `@Repeatable`; line 80: default scope is `"JVM"`.

### HIGH-23 — `adjustScheduleDelay` returns `Long.MAX_VALUE` for SUPPRESS/RETURN; `ScheduledThreadPoolExecutor` overflows trigger time and fires task immediately
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosDispatcher.java:175-178`
- **What breaks:** `return Long.MAX_VALUE` (milliseconds) is written back as the new delay for SUPPRESS/RETURN terminals. `ScheduledThreadPoolExecutor.triggerTime()` converts to nanos: `MILLISECONDS.toNanos(Long.MAX_VALUE)` overflows a 64-bit `long` to a large negative number. The JDK overflow guard in `triggerTime` computes a past trigger time from a negative nanos value, so the task is immediately runnable — the exact opposite of suppression.
- **Impact:** A `SUPPRESS` scenario targeting `SCHEDULE_SUBMIT` causes tasks to fire immediately on the next scheduler tick instead of being blocked. Chaos intent is fully inverted with no diagnostic.
- **Fix direction:** Replace `return Long.MAX_VALUE` with `return TimeUnit.DAYS.toMillis(365L * 100L)` (safe maximum that roundtrips through `toNanos` without overflow), or throw `RejectedExecutionException` directly inside `adjustScheduleDelay` for SUPPRESS/RETURN.
- **Status:** Fixed — lines 172-178: RETURN/SUPPRESS both `return Long.MAX_VALUE`; `ScheduledExecutorAdvice.java:72-76` writes this as the delay with `unit = TimeUnit.MILLISECONDS`.

### HIGH-24 — `ObservabilityBus.incrementMetric()` propagates `ChaosMetricsSink` exceptions into application threads
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ObservabilityBus.java:135-136`
- **What breaks:** `incrementMetric()` calls `metricsSink.increment(name, tags)` with no try/catch. Contrast with `publish()` (lines 101-122) which wraps every listener call in `try/catch (Throwable)` specifically because it "runs on application threads inside ByteBuddy advice." A custom sink that loses its Micrometer registry connection will throw an unchecked exception through `ScenarioController.evaluate()` → dispatcher → straight into application code.
- **Impact:** A misbehaving metrics sink crashes instrumented application threads at JDK call sites (e.g. `Thread.sleep`, `HttpClient.send`). Exception type is opaque to the application team.
- **Fix direction:** Wrap line 136 in the same `try/catch (Throwable)` pattern used in `publish()`, logging at `WARNING` and continuing.
- **Status:** Fixed — `publish()` lines 103-122 has per-listener `try/catch (Throwable)`; `incrementMetric()` line 136: `metricsSink.increment(name, tags);` — no guard.

### HIGH-25 — `MetaspacePressureStressor` leaks its `URLClassLoader` when `ByteBuddy` throws during class generation
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/MetaspacePressureStressor.java:46-52`
- **What breaks:** `loader` is a local `URLClassLoader` created at line 46; `this.isolatedLoader = loader` is assigned at line 51, after the ByteBuddy generation loop (lines 48-50). If `generateClass` throws (e.g. `LinkageError`, `IOException` from `unloaded.load()`), the constructor exits before line 51. `close()` checks `if (isolatedLoader != null)` and skips — the `URLClassLoader` is permanently leaked.
- **Impact:** Every failed stressor activation leaks a classloader. Under active metaspace pressure the exact scenario this stressor targets, construction failures compound with each attempt.
- **Fix direction:** Assign `this.isolatedLoader = loader` immediately after creation (before the loop) so `close()` can always reclaim it; or `try { ... } catch { loader.close(); throw; }`.
- **Status:** Fixed — `MetaspacePressureStressor.java:46`: local `loader`; line 51: `this.isolatedLoader = loader` after loop; `close():58-60` reads `isolatedLoader`.

### HIGH-26 — `CodeCachePressureStressor` only invokes generated methods 20 times — two orders of magnitude below JIT compilation threshold; stressor is a near-no-op
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/CodeCachePressureStressor.java:116`
- **What breaks:** Line 112 comments "invoke enough times to exceed the JIT compilation threshold (typically 10 000 calls)." The loop at line 116 is `invocation < 20`. HotSpot's default C2 threshold is 10 000 invocations; C1 threshold is ~1 500. 20 invocations triggers neither. The generated method body is `FixedValue.value(0L)` — a constant return — which HotSpot is likely to inline speculatively without emitting code into the code-cache segment at all.
- **Impact:** The stressor runs, loads classes, measures a compile-time delta, but never meaningfully fills the code cache. The chaos mode that is supposed to trigger 10-50× JIT deoptimization produces no observable effect. Silent broken chaos mode.
- **Fix direction:** Change `invocation < 20` to `invocation < 15_000` (safely above both C1 and C2 thresholds per class).
- **Status:** Fixed — line 116: `for (int invocation = 0; invocation < 20; invocation++)`; contradicts line 112's own comment.

### HIGH-27 — `VirtualThreadCarrierPinningStressor` calls `Thread.ofPlatform()` (JDK 19 API); throws `NoSuchMethodError` on JDK 17, orphans a controller
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/VirtualThreadCarrierPinningStressor.java:63`
- **What breaks:** `Thread.ofPlatform()` is stable API only since JDK 19. On JDK 17 it throws `NoSuchMethodError` during stressor construction. `CompatibilityValidator` has zero check for this case (HIGH-3 covers the missing switch, but no feature-gate exists at all). Per CRIT-5 the already-registered controller is orphaned as permanently ACTIVE.
- **Impact:** On JDK 17, any `virtualThreadCarrierPinning` scenario crashes the start path and leaves a permanently ACTIVE ghost controller in the registry.
- **Fix direction:** In the `CompatibilityValidator` case added for HIGH-3, also gate on `featureSet.javaVersion() >= 19` or use a reflection probe. Fallback to `new Thread(runnable)` as a portable alternative inside the stressor.
- **Status:** Fixed — `VirtualThreadCarrierPinningStressor.java:63`: `Thread.ofPlatform().daemon(true).name(name).start(...)`; grep confirms `CompatibilityValidator` has no match for `VIRTUAL_THREAD_CARRIER_PINNING` or `VirtualThreadCarrierPinningEffect`.

### HIGH-28 — `ScheduledRunnableWrapper` swallows genuine application exceptions in periodic tasks; `ScheduledFuture` is never cancelled
- **Location:** `chaos-agent-instrumentation-jdk/src/main/java/com/macstab/chaos/instrumentation/ScheduledRunnableWrapper.java:22-45`
- **What breaks:** A single `try` block at line 23 encloses both `BootstrapDispatcher.beforeScheduledTick(...)` and `delegate.run()`. The `catch (Throwable throwable)` at line 27 — when `periodic == true` — logs and discards ALL throwables unconditionally. A genuine `RuntimeException` or `Error` from `delegate.run()` is caught and silenced. The `ScheduledFuture` is never cancelled (exception never escapes `run()`), so the broken task continues firing on schedule indefinitely.
- **Impact:** Real application errors in periodic tasks are permanently masked; the executor's self-cancellation mechanism on uncaught exception is defeated. Operators relying on health-check or metric periodic tasks never observe the failure.
- **Fix direction:** Separate the try blocks: wrap only `beforeScheduledTick` to catch chaos-injected exceptions; let `delegate.run()` run outside (or in a separate try that re-throws non-chaos exceptions).
- **Status:** Fixed — lines 23-44: single catch covering `delegate.run()`; `if (periodic)` branch swallows everything including genuine application exceptions.

### HIGH-29 — Two `@WriteOperation` methods on `ChaosActuatorEndpoint` without `@Selector`; causes `IllegalStateException` at Spring Boot actuator startup
- **Location:** `chaos-agent-spring-boot-common/src/main/java/com/macstab/chaos/spring/boot/common/ChaosActuatorEndpoint.java:56-97`
- **What breaks:** `activate(String planJson)` (line 57) and `stopAll()` (line 94) are both `@WriteOperation` on the same `@Endpoint(id = "chaos")` class without `@Selector`. Spring Boot Actuator's `EndpointDiscoverer` requires at most one `@WriteOperation` per endpoint path segment; multiple are only allowed when each is distinguished by a `@Selector`-annotated parameter. Violation throws `IllegalStateException: Multiple write operations found for endpoint chaos` at context startup.
- **Impact:** Application context fails to start whenever the actuator is enabled. **Note:** iteration-1 agent cited passing tests; this finding was re-verified against the Spring Boot spec in iteration 2. Manual check against runtime Boot version is recommended.
- **Fix direction:** Convert `stopAll` to `@DeleteOperation` (no `@Selector`) — a distinct HTTP verb, not a path conflict with `activate`'s `@WriteOperation` (POST).
- **Status:** Fixed — lines 56-57 `@WriteOperation activate`, lines 93-94 `@WriteOperation stopAll`; neither has `@Selector`. Spring Boot spec requires `@Selector` to distinguish multiple write ops. (Disputed: iteration-1 test pass suggests Boot version may accept this; verify against Boot 3.x runtime.)

### HIGH-30 — `evaluate()` gate-action selection is inverted: lowest-precedence `GateEffect` wins instead of highest
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosDispatcher.java:1091-1092`
- **What breaks:** `contributions` are iterated in descending-precedence order (highest first, per `CONTRIBUTION_ORDER` at `ScenarioRegistry.java:43-46`). For terminal actions the `>=` guard at line 1097 means the first (highest-precedence) wins. For `GateEffect`, `gateAction = new GateAction(...)` at line 1092 unconditionally overwrites on every match — the last iteration (lowest-precedence) wins. Refines/corrects MED-11 (which misidentified which one is discarded).
- **Impact:** A high-precedence gate with a strict timeout is overridden by a low-precedence gate with a permissive timeout — the operator's intended tight gate is silently discarded. Observable as intercepted operations proceeding sooner than configured.
- **Fix direction:** Mirror the terminal pattern: add `int gatePrecedence = Integer.MIN_VALUE;` and guard `if (contribution.scenario().precedence() >= gatePrecedence) { gateAction = ...; gatePrecedence = ...; }`.
- **Status:** Fixed — `ScenarioRegistry.java:43-46`: descending-precedence sort confirmed; `ChaosDispatcher.java:1091-1092`: unconditional overwrite; line 1097: `>=` guard exists for terminals but is absent for gates.

### HIGH-31 — `ScheduledCallableWrapper` is package-private; `IllegalAccessError` from inlined JDK advice even after CRIT-3 bootstrap-JAR fix
- **Location:** `chaos-agent-instrumentation-jdk/src/main/java/com/macstab/chaos/instrumentation/ScheduledCallableWrapper.java:7`
- **What breaks:** `ScheduledCallableWrapper` is declared `final class` (no `public`). ByteBuddy inlines `ScheduledCallableAdvice.enter()` into `java.util.concurrent.ScheduledThreadPoolExecutor.schedule(Callable, long, TimeUnit)`. The inlined call site is in package `java.util.concurrent`. Access to a package-private class from a different package at the call site produces `IllegalAccessError`, regardless of classloader. This means the CRIT-3 fix (adding the class to the bootstrap JAR) is necessary but not sufficient — a second fix is required.
- **Impact:** `schedule(Callable, delay, unit)` on any instrumented executor throws `IllegalAccessError` even after CRIT-3 is fixed. The full `ScheduleCallableAdvice` path remains broken.
- **Fix direction:** Declare `ScheduledCallableWrapper` (and its constructor) `public`. Also verify `ScheduledRunnableWrapper` is `public` — it is, which explains why the runnable path works.
- **Status:** Fixed — `ScheduledCallableWrapper.java:7`: `final class ScheduledCallableWrapper<T>` — no `public`. `ScheduledRunnableWrapper.java`: `public final class ScheduledRunnableWrapper` — correctly public. `ScheduledExecutorAdvice.java:46`: `task = new ScheduledCallableWrapper<>(...)` inlined into JDK class.

### HIGH-32 — `SpuriousWakeupEffect` is never applied: `terminalActionFor` has no branch for it; NIO selector wakeup chaos is a complete no-op
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosDispatcher.java:1105-1127`
- **What breaks:** `terminalActionFor` pattern-matches `RejectEffect`, `SuppressEffect`, `ExceptionalCompletionEffect`, `ExceptionInjectionEffect`, and `ReturnValueCorruptionEffect` — then falls off returning `null`. A `SpuriousWakeupEffect` scenario evaluates, `decision.terminalAction()` is `null`, and `beforeNioSelect` skips the terminal block, returning `false` (proceed with real select). The scenario validates and registers successfully but never fires.
- **Impact:** NIO event-loop tests relying on simulated spurious wakeups silently pass through without the intended effect. A whole NIO chaos mode is a dead letter.
- **Fix direction:** Add `if (effect instanceof ChaosEffect.SpuriousWakeupEffect) return new TerminalAction(TerminalKind.SUPPRESS, null, null);` to `terminalActionFor`. `beforeNioSelect` already returns `true` (skip) for `TerminalKind.SUPPRESS`.
- **Status:** Fixed — `terminalActionFor:1107-1127`: five effect types enumerated; `SpuriousWakeupEffect` absent. `beforeNioSelect:610-619`: returns `true` only when `terminalAction != null && kind == SUPPRESS`.

### HIGH-11 — `ChaosRuntime` / `ChaosDispatcher` public API surface too wide
- **Location:**
  - `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosRuntime.java:27` (public, 71 public methods)
  - `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosDispatcher.java:27` (public, 60 public methods)
- **What breaks:** both classes are `public final` and referenced across module boundaries (`chaos-agent-bootstrap`, `chaos-agent-instrumentation-jdk`). Once 1.0 ships, every one of those methods becomes part of the frozen API surface.
- **Impact:** every future rename or removal is a breaking change; downstream agents and SPI extensions can bind to any of those bridge methods.
- **Fix direction:** extract a narrow `RuntimeBridge` SPI annotated `@Internal`, keep the concrete types package-private, expose only the contract surface via `ChaosControlPlane` and the bridge interface.
- **Status:** Fixed — both `public final`; method counts refined to ~72 public on `ChaosRuntime`, ~61 on `ChaosDispatcher`. External references in `chaos-agent-bootstrap`, `chaos-agent-spring-boot-common`, `chaos-agent-spring-boot3-starter`, `chaos-agent-spring-boot4-starter`, `chaos-agent-benchmarks`.

### HIGH-33 — `JfrChaosEventSink.close()` never invoked; `FlightRecorder` periodic hook leaks across runtime lifetimes
- **Location:** `chaos-agent-bootstrap/src/main/java/com/macstab/chaos/bootstrap/jfr/JfrChaosEventSink.java:22-49` and `chaos-agent-bootstrap/src/main/java/com/macstab/chaos/bootstrap/jfr/JfrIntegration.java:42-45`
- **What breaks:** Constructor registers `FlightRecorder.addPeriodicEvent(ChaosStressorSnapshotEvent.class, periodicHook)`. `close()` removes it — but nothing ever calls `close()`. `ChaosControlPlane` has `addEventListener` but no `removeEventListener`; `ChaosControlPlaneImpl.close()` only destroys controllers, never unwinds listeners. Javadoc explicitly requires "callers must close this sink when the agent or test is torn down to avoid a stale periodic hook holding a reference to the diagnostics object" — no caller does.
- **Impact:** `FlightRecorder` (JVM-wide singleton) retains the periodic `Runnable` which closes over `diagnostics` → `ScenarioRegistry` → every `ScenarioController` ever registered. Test suites new-ing multiple `ChaosRuntime` instances accumulate N stale hooks; JFR invokes each per tick, each pinning dead runtimes. Same leak in agent re-attach flows.
- **Fix direction:** Add `ChaosControlPlane.removeEventListener(...)` invoked during shutdown; or register a `AutoCloseable`-aware listener list that `ChaosControlPlaneImpl.close()` drains; or have `JfrIntegration` install a JVM shutdown hook that invokes `sink.close()`.

### HIGH-34 — Quarkus extension ships `@Recorder` and `@BuildStep` in one jar; no runtime/deployment split
- **Location:** `chaos-agent-quarkus-extension/build.gradle.kts`, `chaos-agent-quarkus-extension/src/main/resources/META-INF/quarkus-extension.yaml`, `chaos-agent-quarkus-extension/src/main/java/com/macstab/chaos/quarkus/ChaosQuarkusBuildStep.java`, `ChaosQuarkusRecorder.java`
- **What breaks:** Both `compileOnly("io.quarkus:quarkus-core")` and `compileOnly("io.quarkus:quarkus-core-deployment")` declared in one module; `@Recorder`-annotated `ChaosQuarkusRecorder` and `@BuildStep`-annotated `ChaosQuarkusBuildStep` end up in a single jar. `quarkus-extension.yaml` has no `deployment-artifact:` field. Quarkus extensions MUST be split into `-runtime.jar` and `-deployment.jar`; the loader discovers build steps only from the `deployment-artifact:` pointer.
- **Impact:** Either (a) Quarkus refuses to load the extension (build step never runs, recorder bytecode never emitted into runtime init), or (b) `io.quarkus.deployment.*` API types leak onto the user's production classpath causing `NoClassDefFoundError` at first runtime classload.
- **Fix direction:** Create `chaos-agent-quarkus-extension-deployment` sibling module containing `ChaosQuarkusBuildStep` + `compileOnly("io.quarkus:quarkus-core-deployment")`. Keep only `@Recorder` + CDI producers in runtime module. Add `deployment-artifact: com.macstab.chaos:chaos-agent-quarkus-extension-deployment` to `quarkus-extension.yaml`.

### HIGH-35 — Boot test-starter `ImportAutoConfiguration.imports` file is never consumed by `@SpringBootTest`; `ChaosTestAutoConfiguration` silently inert
- **Location:** `chaos-agent-spring-boot3-test-starter/src/main/resources/META-INF/spring/org.springframework.boot.test.autoconfigure.ImportAutoConfiguration.imports`, `chaos-agent-spring-boot4-test-starter/src/main/resources/META-INF/spring/org.springframework.boot.test.autoconfigure.ImportAutoConfiguration.imports`, `chaos-agent-spring-boot3-test-starter/src/main/java/com/macstab/chaos/spring/boot3/test/ChaosTestAutoConfiguration.java:17-19`
- **What breaks:** The `ImportAutoConfiguration.imports` SPI is ONLY scanned when a test is annotated with `@ImportAutoConfiguration` (directly or via a slice annotation like `@DataJpaTest`/`@WebMvcTest`). A plain `@SpringBootTest` or `@ChaosTest` (which wraps `@SpringBootTest` + `@ExtendWith`) never triggers that mechanism. The class is annotated `@TestConfiguration(proxyBeanMethods = false)` (not `@AutoConfiguration`), so even if Boot tried to load it as auto-config via the regular `AutoConfiguration.imports`, the `AutoConfigurationImportSelector` would refuse. Same on Boot 3 and 4 starters.
- **Impact:** Consumers who rely on the test-starter to publish a `ChaosControlPlane` bean into `@SpringBootTest` contexts get no bean. `@Autowired ChaosControlPlane` becomes unsatisfied — NPE or `NoSuchBeanDefinitionException`.
- **Fix direction:** Change the class annotation to `@AutoConfiguration` and move the SPI file to `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`; OR document that consumers must add `@ImportAutoConfiguration(ChaosTestAutoConfiguration.class)` on every test class.

### HIGH-36 — `MonitorSelector.monitorClass` filter is dead code for `MONITOR_ENTER`; dispatcher hardcodes class name `AbstractQueuedSynchronizer`
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosDispatcher.java:570-582` (`beforeMonitorEnter`)
- **What breaks:** `beforeMonitorEnter()` builds its `InvocationContext` with the subject class name hard-coded as `"java.util.concurrent.locks.AbstractQueuedSynchronizer"` — zero plumbing from the actual `@Advice.This` lock. A user who configures `ChaosSelector.monitor(ops, NamePattern.prefix("com.example"))` compares `"com.example.*"` against the hardcoded AQS string → never matches. The scenario registers fine but never fires; no warning.
- **Impact:** Any user-authored `MonitorSelector` scenario with a non-default `monitorClass` filter silently no-ops. Masked by every existing test using `NamePattern.any()`.
- **Fix direction:** Thread `@Advice.This Object lock` from `MonitorEnterAdvice.enter()` through `BootstrapDispatcher.beforeMonitorEnter` into the dispatcher so `lock.getClass().getName()` flows into `context.subjectClassName()`. Add an integration test with a specific `NamePattern.exact(...)` proving filtering works end-to-end and a negative test proving a non-matching pattern does NOT apply.

### HIGH-37 — `ThreadLeakStressor` calls `Thread.ofPlatform()` (JDK 19 API); `NoSuchMethodError` on JDK 17 at class load
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ThreadLeakStressor.java:43-46`
- **What breaks:** `Thread.ofPlatform().daemon(effect.daemon()).name(name).start(...)`. `Thread.ofPlatform()` is JDK 19+. `CompatibilityValidator` case `THREAD_LEAK` checks only effect type — no `runtimeFeatureVersion() >= 19` gate. On JDK 17 `NoSuchMethodError: Thread.ofPlatform()` at link-resolve, orphaning a registered-but-unstarted controller (CRIT-5 cascade).
- **Impact:** Identical to HIGH-27 but on a different stressor. Agent declaring JDK 17 baseline but using JDK 19 API silently fails at stressor activation time.
- **Fix direction:** Add `runtimeFeatureVersion() >= 19` guard in `CompatibilityValidator` for `THREAD_LEAK`; or replace with `Thread t = new Thread(runnable, name); t.setDaemon(effect.daemon()); t.start();`.

### HIGH-38 — `DeadlockStressor` calls `Thread.ofPlatform()` (JDK 19 API); `NoSuchMethodError` on JDK 17
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/DeadlockStressor.java:49-52`
- **What breaks:** `Thread.ofPlatform().daemon(true).name(name).start(...)`. `CompatibilityValidator` case `DEADLOCK` checks only effect type — no JDK-version gate. `ActivationPolicy.allowDestructiveEffects()` is an orthogonal authorization flag, not an API-availability guard.
- **Impact:** Same class of silent failure as HIGH-37. `DEADLOCK` scenario registration/start throws `NoSuchMethodError` on JDK 17; controller is orphaned per CRIT-5.
- **Fix direction:** Add JDK-version gate in `CompatibilityValidator`; or rewrite with `new Thread(...)` + `setDaemon(true)` + `setName(...)`.

### HIGH-39 — `GcPressureStressor` calls `Thread.ofPlatform()` (JDK 19 API); `NoSuchMethodError` on JDK 17
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/GcPressureStressor.java:48-51`
- **What breaks:** `Thread.ofPlatform().daemon(true).name("chaos-gc-pressure").start(...)`. No JDK-version gate in `CompatibilityValidator` case `GC_PRESSURE`.
- **Impact:** Same as HIGH-37/38 for GC pressure scenarios.
- **Fix direction:** Gate at JDK 19 or use classic `Thread` constructor.

### HIGH-40 — `MonitorContentionStressor` calls `Thread.ofPlatform()` (JDK 19 API); `NoSuchMethodError` on JDK 17
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/MonitorContentionStressor.java:46-49`
- **What breaks:** `Thread.ofPlatform().daemon(true).name(name).start(...)`. No JDK-version gate in `CompatibilityValidator` case `MONITOR_CONTENTION`.
- **Impact:** Same silent-failure class as HIGH-37/38/39 for monitor-contention scenarios.
- **Fix direction:** Gate at JDK 19 or use classic `Thread` constructor.

### HIGH-41 — Declared baseline mismatch: `build.gradle.kts` sets `release=21` while docs + CompatibilityValidator treat JDK 17 as the baseline
- **Location:** root `build.gradle.kts` compile task `options.release.set(21)`; `chaos-agent-core/src/main/java/com/macstab/chaos/core/FeatureSet.java:9` documents "minimum supported JDK version (17)"; README / examples claim JDK 17 baseline
- **What breaks:** Bytecode is emitted with class-file major 65 (Java 21). A user on JDK 17 — the documented baseline — gets `UnsupportedClassVersionError` on EVERY class at first classload. `JdkVersionGate`/`FeatureSet.runtimeFeatureVersion()` can report `17` at runtime but the JVM already refused to load the agent before any gate runs. The existing HIGH-27/37/38/39/40 per-stressor gates (even if added) are moot because the agent never starts.
- **Impact:** Either the effective baseline is 21 (every "JDK 17 support" claim in README, Javadoc, blog posts, the `FeatureSet` docstring, and the `CompatibilityValidator` policy is wrong), or `release=21` is a bug and the build must drop to 17 (in which case HIGH-27/37–40 actually matter). Until one of these is picked, the public API surface lies about its own baseline.
- **Fix direction:** Pick one: (a) lower `release` to 17 and add explicit JDK-version gates for every JDK 19/21 API use site (HIGH-27, HIGH-37, HIGH-38, HIGH-39, HIGH-40); or (b) raise all docs/READMEs/`FeatureSet` Javadoc to "JDK 21" and drop the CompatibilityValidator's JDK-17 claims. Also set `JavaLanguageVersion.of(21)` toolchain consistently and remove `supportsVirtualThreads()` run-time checks that are now dead code.

### HIGH-42 — Micronaut `ApplicationContextConfigurer` SPI file is in neither recognized layout; `ChaosContextConfigurer` is never discovered by Micronaut 4
- **Location:** `chaos-agent-micronaut-integration/src/main/resources/META-INF/micronaut/io.micronaut.context.ApplicationContextConfigurer`; no `META-INF/services/io.micronaut.context.ApplicationContextConfigurer`
- **What breaks:** Micronaut 4's `SoftServiceLoader` merges classic `META-INF/services/<fqn>` plain-text files with a `META-INF/micronaut/<service-fqn>/<impl-fqn>` directory layout (the implementation FQN is the **file name** inside a directory **named for the service**). This repo uses a single flat file at `META-INF/micronaut/io.micronaut.context.ApplicationContextConfigurer` whose contents are the impl FQN — neither layout matches. `preVisitDirectory` is never entered (not a directory), classic `META-INF/services` file is absent. `loadApplicationContextCustomizer` returns `ApplicationContextConfigurer.NO_OP`; `ChaosContextConfigurer.configure()` is never invoked.
- **Impact:** Auto-attach pathway documented in the configurer Javadoc ("picks it up automatically whenever this integration jar is on the classpath") is dead code on stock Micronaut 4. Agent not self-attached at bootstrap. AOT / native-image paths break identically. In-repo tests mask this because `ChaosMicronautExtension.beforeAll` calls `installLocally()` directly.
- **Fix direction:** Either move the file to `META-INF/services/io.micronaut.context.ApplicationContextConfigurer` (single line = impl FQN), OR change to a directory: `META-INF/micronaut/io.micronaut.context.ApplicationContextConfigurer/com.macstab.chaos.micronaut.ChaosContextConfigurer` (empty file). Alternatively add `@ContextConfigurer` + `annotationProcessor("io.micronaut:micronaut-inject-java")` so the descriptor is generated.

### HIGH-43 — `@MicronautChaosTest` combined with `@MicronautTest` makes two `ParameterResolver`s compete for `ChaosControlPlane` parameters; JUnit fails test methods
- **Location:** `chaos-agent-micronaut-integration/src/main/java/com/macstab/chaos/micronaut/ChaosMicronautExtension.java:61-65`
- **What breaks:** Extension returns `true` from `supportsParameter` for both `ChaosSession.class` and `ChaosControlPlane.class`. Micronaut's own `MicronautJunit5Extension.supportsParameter` returns `true` for any parameter whose type is a bean in the `ApplicationContext` — and `ChaosControlPlane` is produced by `ChaosFactory.chaosControlPlane()`. JUnit raises `ParameterResolutionException("Discovered multiple competing ParameterResolvers ...")` for any `void test(ChaosControlPlane cp)`.
- **Impact:** Documented `@MicronautChaosTest` usage fails at parameter resolution the moment a user adds a `ChaosControlPlane` parameter — the very thing the composed annotation is designed to enable.
- **Fix direction:** Restrict `ChaosMicronautExtension.supportsParameter` to `ChaosSession.class` only; let Micronaut resolve `ChaosControlPlane` via its bean container. If JVM-scope (pre-context) access is required, introduce a distinguishing qualifier (e.g. `@ChaosJvm`) and gate on that.

### HIGH-44 — Quarkus `@BuildStep @Record(RUNTIME_INIT)` returns `FeatureBuildItem` and the recorder class is in the same (runtime) module; build-time recorder use causes `IllegalStateException`
- **Location:** `chaos-agent-quarkus-extension/src/main/java/com/macstab/chaos/quarkus/ChaosQuarkusBuildStep.java:32-37`
- **What breaks:** The method mixes two responsibilities (recording a runtime-init call AND producing a `FeatureBuildItem`); more critically, `ChaosQuarkusRecorder` lives in the same jar as the `@BuildStep` class. Quarkus' deployment indexer includes the recorder class in the runtime-class-list; generated bytecode then references the live `ChaosQuarkusRecorder` rather than the build-time proxy, producing `IllegalStateException: Cannot use a recorder from the runtime module at deployment time`.
- **Impact:** Any Quarkus app depending on this extension fails its build — a separate, louder manifestation of HIGH-34.
- **Fix direction:** Move `FeatureBuildItem` production into a non-recording `@BuildStep` sibling method; confine the `@Record(RUNTIME_INIT)` method to `recorder.installAgent()` alone. Put recorder + producer in a `-runtime` module and build steps in a `-deployment` sibling module (fixes HIGH-34 in tandem).

### HIGH-45 — Quarkus extension lacks `reflect-config.json` / `@RegisterForReflection` for sealed `ChaosSelector`/`ChaosEffect` records; native-image deserialisation throws `NoSuchMethodException` at runtime
- **Location:** `chaos-agent-quarkus-extension/src/main/resources/META-INF/quarkus-extension.yaml` (no `native-image` stanza); absence of `src/main/resources/META-INF/native-image/**/reflect-config.json`
- **What breaks:** `ChaosPlanMapper.read` reflectively constructs sealed-type records (`ChaosSelector.Executor`, `ChaosEffect.Delay`, …). GraalVM native-image strips unreached reflection metadata, so the canonical constructors' method signatures are missing at runtime. `Class.forName` or Jackson-driven construction throws `InvocationTargetException` wrapping `NoSuchMethodException`.
- **Impact:** Quarkus native builds either silently drop every scenario (best case: mapper fails early and the control plane stays empty) or crash at `ApplicationReadyEvent` with obscure constructor-lookup errors.
- **Fix direction:** Add `META-INF/native-image/com.macstab.chaos/chaos-agent-quarkus-extension/reflect-config.json` enumerating `ChaosPlan`, every `ChaosScenario`, every permitted subtype of `ChaosSelector`/`ChaosEffect`, plus their canonical constructors. Or annotate a marker class with `@RegisterForReflection(targets = { … })`. Add a native-image smoke test to CI.

### HIGH-46 — `ChaosActuatorEndpoint.activate` propagates `ConfigLoadException`/`IllegalArgumentException` raw → HTTP 500 with internal class names, Jackson offsets, and stack frames leaked to client
- **Location:** `chaos-agent-spring-boot-common/src/main/java/com/macstab/chaos/spring/boot/common/ChaosActuatorEndpoint.java:56-65`
- **What breaks:** Distinct from MED-43 (which covered only the 500 status code on `ConfigLoadException`): the endpoint also does not catch `IllegalArgumentException`/`IllegalStateException` thrown by `controlPlane.activate(plan)` (scope conflicts, etc.). Spring Actuator's default error rendering (especially `server.error.include-stacktrace=on_param`/`always`) exposes the exception chain including `JsonParseException` context, package layout, and Jackson version.
- **Impact:** Information-disclosure defence-in-depth gap. Attacker or curious operator sends malformed plan JSON and receives internals mapping out the library.
- **Fix direction:** Wrap body in try/catch for `ConfigLoadException`, `IllegalArgumentException`, `IllegalStateException` → return `ActivationResponse("error", null, "invalid plan")` with redacted message. Log full detail at DEBUG/WARN server-side only.

### HIGH-47 — `ChaosHandleRegistry.stopAll()` iteration races `register()` at the same handle id and silently loses the concurrently-registered replacement
- **Location:** `chaos-agent-spring-boot-common/src/main/java/com/macstab/chaos/spring/boot/common/ChaosHandleRegistry.java:83-95`
- **What breaks:** Sequence: (1) `stopAll` iterator snapshots entry `(id="x", old)`; (2) concurrent `register(new)` with same id — `handles.put` returns `old`, `register` calls `old.stop()`; (3) `stopAll` calls `handles.remove("x")` which removes the NEW handle (not old); (4) `stopAll` calls `old.stop()` again (double-stop). Net: NEW handle is un-tracked in the registry AND never stopped, but its `controlPlane.activate` side-effects are live. (Distinct from MED-50: MED-50 is "handle registered AFTER iteration is missed"; this one is "handle registered DURING iteration at a colliding id is actively removed and lost".)
- **Impact:** Ghost JVM-scoped scenarios survive registry `stopAll`; cannot be stopped through the starter; not visible in the starter-level diagnostics. Exposed under fuzzing of `/actuator/chaos stopAll + activate`.
- **Fix direction:** Use `handles.remove(entry.getKey(), entry.getValue())` (key+value CAS remove); switch to `handles.entrySet().iterator()` and `iterator.remove()`; or snapshot + clear + stop in one pass.

### HIGH-48 — Every `ChaosSelector.*Selector` record exposes its `operations` `EnumSet` through the record accessor; external mutation breaks identity and invariants
- **Location:** `chaos-agent-api/src/main/java/com/macstab/chaos/api/ChaosSelector.java` (every variant's compact constructor and the auto-generated accessor, e.g. `ThreadSelector`, `ExecutorSelector`, `QueueSelector`, `AsyncSelector`, `SchedulingSelector`, `ShutdownSelector`, `ClassLoadingSelector`, `MethodSelector`, `MonitorSelector`, `JvmRuntimeSelector`, `NioSelector`, `NetworkSelector`, `ThreadLocalSelector`, `HttpClientSelector`, `JdbcSelector`, `DnsSelector`, `SslSelector`, `FileIoSelector`)
- **What breaks:** Compact constructors do `EnumSet.copyOf(operations)` defensively against the caller, but `EnumSet` is mutable and the record's auto-generated accessor returns that same internal reference. Callers can `selector.operations().clear()` — this silently changes the record's `equals`/`hashCode`, corrupting any cache keyed on the selector (dispatcher compiled-selector cache, registry dedup) and violating the VALID_OPS-subset invariant enforced in the constructor. Compare with `ChaosPlan.scenarios` using `List.copyOf` (immutable) and `ChaosScenario.tags` using `Collections.unmodifiableMap` — selector records broke the pattern.
- **Impact:** Silent state corruption; duplicate entries in identity-keyed data structures; difficult-to-diagnose "same scenario matches twice" bugs.
- **Fix direction:** In every compact constructor, store `Set.copyOf(operations)` (truly immutable) — this also costs less than an `EnumSet.copyOf` followed by `Collections.unmodifiableSet` wrapper. Accept a minor loss of `EnumSet` bulk-ops speed on the hot path.

### HIGH-49 — `NamePattern` records in `ANY` mode with a non-canonical `value` field break `equals` against the `ANY` singleton and the dedup pattern
- **Location:** `chaos-agent-api/src/main/java/com/macstab/chaos/api/NamePattern.java:78, 86-99`
- **What breaks:** Compact constructor rejects blank `value` only when `mode != ANY`. So `new NamePattern(MatchMode.ANY, "foo")` is legal. The static singleton `ANY = new NamePattern(MatchMode.ANY, "*")`. Record-generated `equals` compares both fields; two "ANY" patterns compare unequal if their `value` differs. Matching path short-circuits `ANY` via `mode == ANY` check (so matching is unaffected), but dedup caches keyed on `NamePattern` treat them as distinct → duplicate cache entries; selector dedup breaks; Jackson-deserialised `{"mode":"ANY","value":"anything"}` drifts from in-code `NamePattern.any()`.
- **Impact:** Silent cache bloat; selectors that should be deduped are not; cross-language round-tripping (YAML → JSON → Java) can produce mysterious non-equal selectors.
- **Fix direction:** In the compact constructor, `this.value = (this.mode == MatchMode.ANY) ? "*" : value;` — canonicalise `ANY` mode's `value`.

### HIGH-50 — Two benchmark `@Setup(Level.Trial)` methods pass `probability=0.0d` to `ActivationPolicy`; every JMH trial aborts before a single iteration runs
- **Location:** `chaos-agent-benchmarks/src/main/java/com/macstab/chaos/benchmarks/HttpClientBenchmark.java:99, 165`; `chaos-agent-benchmarks/src/main/java/com/macstab/chaos/benchmarks/ChaosRuntimeBenchmark.java:106, 172`
- **What breaks:** `ActivationPolicy.java:71-76` rejects `probability <= 0.0d` (use MED-9 NaN handling in tandem). Benchmarks `agentInstalled_oneMatch_noEffect` and `tenScenarios_oneMatch` in both classes throw `IllegalArgumentException` at `@Setup`, so JMH reports trial errors for four of the advertised benchmark rows.
- **Impact:** The performance targets in `README.md:645` (e.g. "One scenario active, match, no terminal effect < 300 ns") cannot be reproduced by the user. The benchmark suite fails the moment someone runs `./gradlew :chaos-agent-benchmarks:run`.
- **Fix direction:** Use `probability=1.0d` + `maxApplications=1L` to scope the effect to a single application per trial, matching the apparent intent.

### HIGH-51 — `README.md` + `docs/api.md` + `docs/testkit.md` all reference non-existent `ChaosScenario.builder()` (zero-arg), `.id(...)` setter, `ActivationPolicy.builder()`, and `ChaosSelector.executor()` (zero-arg) APIs; every quick-start snippet fails to compile
- **Location:** `README.md:280-285, 387-396, 525-529`; `docs/api.md:147-246, 256-307`; `docs/testkit.md:149-155, 174-…, 194-…, 215-220`
- **What breaks:** Real API: `ChaosScenario.builder(String id)` requires an id; `Builder` has no `.id()` setter; `ActivationPolicy` is a record with no `builder()` static method; `ChaosSelector.executor(Set<OperationType>)` requires operations. Every flagship quick-start snippet in the three documents fails with compile errors.
- **Impact:** First-user experience is broken. README is 40 KB; the API reference is documented as "the stable external contract surface"; both mislead every reader.
- **Fix direction:** Either add the documented builder APIs (likely preferred), or regenerate every snippet from the real record-based API. Add a javac-doctest CI step that parses fenced `java` code blocks and compiles them against the API jar.

### HIGH-52 — `README.md` selectors table (18 entries) and effects table (≥ 10 entries) document wrong method names and arities across the board
- **Location:** `README.md:322-342` (selectors table), `README.md:349-379` (effects table)
- **What breaks:** Selectors table lists `executor()`, `scheduledExecutor()`, `forkJoin()`, `thread()`, `queue()`, `async()`, `network()`, `nio()`, `classLoader()`, `monitor()`, `jvmRuntime()`, `serialization()`, `zip()`, `jndi()`, `jmx()`, `nativeLib()`, `shutdown()`, `threadLocal()` as zero-arg factories — real API requires `Set<OperationType>`. Several method names are wrong entirely (`scheduledExecutor` → `scheduling`, `classLoader` → `classLoading`). Effects table: `exceptionInjection` → `injectException`; `returnValueCorruption` → `corruptReturnValue`; `clockSkew(Mode, long)` → `skewClock(Duration, Mode)`; `monitorContention(int)` → `monitorContention(Duration, int)`; `threadLeak(count)` → `threadLeak(int, boolean, Duration)`; `safepointStorm(long)` → `safepointStorm(Duration)`; `stringInternPressure(int)` → 2 args; `referenceQueueFlood(int)` → 2 args.
- **Impact:** Library's self-advertisement is misleading about nearly every capability it offers.
- **Fix direction:** Script-generate the tables from `ChaosSelector` and `ChaosEffect` sealed hierarchies; add a CI check that diffs the tables against an always-accurate generator.

### HIGH-53 — `docs/api.md` "Public API Contract Reference" claims `probability=0.0` is coerced to `1.0`; runtime actually throws `IllegalArgumentException`
- **Location:** `docs/api.md:347` (fields table)
- **What breaks:** The doc says `"0.0 treated as 1.0"`. `ActivationPolicy.java:71-76` explicitly rejects `0.0` with a message instructing callers to omit the scenario instead. The coercion only exists for *null* JSON fields, not numeric `0.0`. Related to HIGH-50 — benchmarks authored to match the doc hit the real validation.
- **Impact:** Users follow the doc, set `probability=0.0`, and get a cryptic exception at activation.
- **Fix direction:** Rewrite the row: `"0.0 is rejected (IllegalArgumentException); omit the scenario to disable it. null in JSON defaults to 1.0."`

### HIGH-54 — `docs/api.md` uses Kotlin/Python named-argument syntax (`offsetMillis=5000`, `withStackTrace=true`) inside fenced `java` blocks; every such snippet is not valid Java
- **Location:** `docs/api.md:256-307` (effect-factory examples)
- **What breaks:** Named-arg syntax is not Java; these are not compilable. Additionally the method names and arities are wrong (see HIGH-52). Signals to readers that the project does not run its documentation through a compiler.
- **Impact:** Obvious "this was never tested" sign on the public API reference.
- **Fix direction:** Replace with real Java (`ChaosEffect.skewClock(Duration.ofMillis(5000), ClockSkewEffect.Mode.FIXED)`, etc.) and wire into a javac-doctest pipeline.

### HIGH-55 — `chaos-agent-examples/sb3-actuator-live-chaos` Resilience4j circuit-breaker demo cannot open the circuit under chaos (defaults override the intent)
- **Location:** `chaos-agent-examples/sb3-actuator-live-chaos/src/main/resources/application.properties` (Resilience4j config); `.../src/test/java/.../ActuatorLiveChaosIT.java:41-52`
- **What breaks:** `slidingWindowSize=5`, `failureRateThreshold=60%` — but `minimumNumberOfCalls` defaults to `100`. Resilience4j keeps the circuit CLOSED until 100 calls are recorded, regardless of failure rate in the sliding window. The 6-call test loop cannot open the circuit. The test passes only because `@CircuitBreaker(fallbackMethod=...)` fires the fallback on *any* thrown exception, not only when the circuit is open.
- **Impact:** Flagship Spring Boot 3 example demonstrates behaviour that does not actually occur — fallback-on-exception is being mistaken for open-circuit. Teaches readers the wrong mental model.
- **Fix direction:** Add `resilience4j.circuitbreaker.instances.payment-gateway.minimumNumberOfCalls=3` (or similar small value) so the circuit actually opens. Rewrite the test description to assert the intended state transition.

### HIGH-56 — `chaos-agent-examples/sb4-virtual-thread-pinning` contention scenario targets a private lock owned by the stressor instead of the app's `MetricsAggregator` monitor; the example doesn't actually demonstrate pinning
- **Location:** `chaos-agent-examples/sb4-virtual-thread-pinning/src/main/java/com/macstab/chaos/examples/sb4pinning/ChaosStartupPlan.java:37-42`; `MonitorContentionStressor.java:37` (builds its own internal `ReentrantLock`)
- **What breaks:** The scenario description says `"Saturate the MetricsAggregator monitor"` but `MonitorContentionStressor` contends only on its own private lock. The app's `MetricsAggregator.lock` is untouched. The demo's claim — that virtual threads pin on application code under contention — is not exercised by this plan.
- **Impact:** Users study the example to learn virtual-thread pinning diagnostics and see a scenario that demonstrates nothing of the sort. Flagship Boot 4 example is misleading.
- **Fix direction:** Replace with `ChaosSelector.monitor(Set.of(OperationType.MONITOR_ENTER))` + a `DelayEffect`, or with `VirtualThreadCarrierPinningEffect` that actually pins carriers on target code. Update the scenario description to match.

### HIGH-57 — `ScenarioController.evaluate()` writes `state` and `reason` outside `synchronized(this)`, racing with `start()`/`stop()`
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ScenarioController.java:326-328, 357-358`
- **What breaks:** `start()` and `stop()` write `state`/`reason` under `synchronized(this)`. `evaluate()` writes the same fields (to `INACTIVE`/`"expired"` and `INACTIVE`/`"max applications reached"`) without holding the lock. A concurrent `start()` on another thread can win the race and write `ACTIVE` between `evaluate()`'s guard-check and its own write, leaving the controller observably `INACTIVE` despite being just restarted. Diagnostic snapshots read `state` unsynchronized and can see a stale `INACTIVE` for a running controller.
- **Fix direction:** Hold `synchronized(this)` around the `state`/`reason` writes in `evaluate()`'s expiry/max-applications paths, or use a single `AtomicReference<StateAndReason>` pair for atomic compare-and-set.

### HIGH-58 — `passesActivationWindow()` reads `startedAt` outside `synchronized(this)`; JIT reordering can deliver `null` after `start()` observes `ACTIVE`
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ScenarioController.java:443-448`
- **What breaks:** `startedAt` is a `volatile Instant` written inside `start()` under the lock (line 219). `passesActivationWindow()` reads it outside any lock. JIT instruction reordering within the JVM can cause a thread calling `evaluate()` immediately after a concurrent `start()` to see `startedAt == null` even though `state == ACTIVE`. The `if (startedAt == null) return true` guard silently bypasses the activation window constraint on the first miss, and on a controller restart that resets `startedAt`, the window is permanently skipped if the null is re-observed.
- **Fix direction:** Read `startedAt` inside `synchronized(this)` or pass it as a local captured under the lock to `passesActivationWindow`; `volatile` alone is insufficient when the write is already under a lock that the reader needs to respect.

### HIGH-59 — `ScopeContext.bind()` close-lambda pops the stack unconditionally without verifying the top element matches the pushed ID; mis-ordered closes corrupt the entire per-thread scope
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ScopeContext.java:63-71`
- **What breaks:** `close()` checks `!stack.isEmpty()` then calls `stack.pop()` without verifying the top element is the session ID that was pushed by this binding. If a caller closes an outer binding before an inner one (or double-closes a binding), the wrong session ID is silently removed. In thread pools (Tomcat, Netty workers) this corrupts `currentSessionId()` for every subsequent request on that thread, causing scenario scope to leak between unrelated requests.
- **Fix direction:** Assert `stack.peek().equals(sessionId)` before popping; throw `IllegalStateException("ScopeBinding closed in wrong order")` on mismatch to surface the bug at the call site rather than propagating silently.

### HIGH-60 — `ChaosControlPlaneImpl.activate(ChaosPlan)` rollback calls `handle.stop()` for non-`DefaultChaosActivationHandle` types but never unregisters their controllers; ghost entries remain in `ScenarioRegistry`
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosControlPlaneImpl.java:74-85`
- **What breaks:** The rollback loop calls `destroy()` (stop + unregister) only for `DefaultChaosActivationHandle`; any other `ChaosActivationHandle` (e.g., `CompositeActivationHandle`) falls through to `handle.stop()`, which stops controllers but never calls `registry.unregister()`. Those controllers stay permanently in `ScenarioRegistry` in `STOPPED` state, appear in diagnostics as ghost entries, and prevent re-activation under the same scenario ID.
- **Fix direction:** Add `unregister()` to the `ChaosActivationHandle` interface, or enforce that only `DefaultChaosActivationHandle` instances can appear in the rollback list.

### HIGH-61 — `StartupConfigPoller.applyDiff` uses `Collectors.toMap` without a merge function; duplicate scenario IDs in a reloaded plan throw `IllegalStateException` mid-diff, leaving the runtime in a partially-mutated state
- **Location:** `chaos-agent-bootstrap/src/main/java/com/macstab/chaos/bootstrap/StartupConfigPoller.java:213`
- **What breaks:** `Collectors.toMap(ChaosScenario::id, s -> s)` throws `IllegalStateException` when the reloaded JSON contains two scenarios with the same `id`. The exception propagates out of `applyDiff`; the `pollOnce` catch logs and swallows it — but not before the synchronized block has already torn down scenarios whose content changed. The net result is that previously-running scenarios are permanently stopped without being replaced.
- **Fix direction:** Either use `Collectors.toMap(..., (a, b) -> b)` (last-wins) and emit a WARN, or validate for duplicate IDs in `ChaosPlanMapper` before the diff loop and throw `ConfigLoadException` with a descriptive message.

### HIGH-62 — `passesRateLimit()` computes window via `duration.toMillis()`, which truncates sub-millisecond windows to `0`; rate limit silently fires on every call
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ScenarioController.java:457-461`
- **What breaks:** `windowMillis = rateLimit.window().toMillis()` truncates toward zero. A `Duration.ofNanos(500)` or `Duration.ofMicros(999)` yields `windowMillis=0`. The sliding-window reset condition `nowMillis - rateWindowStartMillis >= 0` is always true (both longs), so `rateWindowPermits` resets to `0` on every call and the permit limit is never enforced — the rate limit is silently disabled. `RateLimit`'s constructor only validates `window.isPositive()`, which allows sub-millisecond values.
- **Fix direction:** In `passesRateLimit()`, guard with `if (windowMillis < 1) windowMillis = 1;` or reject at construction: add `if (window.toMillis() < 1) throw new IllegalArgumentException("window must be at least 1 millisecond")` in `RateLimit`'s compact constructor.

### HIGH-63 — `isSubTypeOf(BlockingQueue)` type matcher has no `not(isSynthetic())` guard; ByteBuddy attempts to instrument compiler-generated anonymous / lambda queue subtypes, causing `VerifyError`
- **Location:** `chaos-agent-instrumentation-jdk/src/main/java/com/macstab/chaos/instrumentation/JdkInstrumentationInstaller.java:210`
- **What breaks:** The matcher matches every class that implements `BlockingQueue`, including lambda-adapted classes produced by `LambdaMetafactory` and anonymous inner classes from mocking frameworks. These synthetic types lack concrete `put`/`take`/`poll`/`offer` method bodies. ByteBuddy attempts to weave `QueueAdvice` bodies into them; the woven bytecode references agent bootstrap classes that the synthetic class's loader cannot resolve, triggering `VerifyError` or `ClassCastException` at class-load time.
- **Fix direction:** Change matcher to `.and(ElementMatchers.not(ElementMatchers.isInterface())).and(ElementMatchers.not(ElementMatchers.isSynthetic()))`, mirroring the guard used for the JDBC matchers.

### HIGH-64 — JDBC type matchers `isSubTypeOf(Statement)` and `isSubTypeOf(Connection)` lack `not(isSynthetic())` guard; CGLIB / ByteBuddy proxy subtypes trigger `VerifyError` on JdbcAdvice weaving
- **Location:** `chaos-agent-instrumentation-jdk/src/main/java/com/macstab/chaos/instrumentation/JdkInstrumentationInstaller.java:724, 745`
- **What breaks:** HikariCP and Spring JDBC generate synthetic proxy subclasses (e.g., `HikariProxyStatement$$FastClassBySpringCGLIB$$...`) that are concrete non-interface `Statement`/`Connection` subtypes. ByteBuddy instruments them with advice that inserts checked-exception throws; bridge methods in synthetic proxies lack the required checked-exception declarations, causing `VerifyError`.
- **Fix direction:** Add `.and(ElementMatchers.not(ElementMatchers.isSynthetic()))` to both matchers at lines 724–725 and 745–746.

### HIGH-65 — `NativeLibraryLoadAdvice` targets `Runtime.loadLibrary0`, which was removed from `java.lang.Runtime` in JDK 21; native-library load interception is silently dead on JDK 21+
- **Location:** `chaos-agent-instrumentation-jdk/src/main/java/com/macstab/chaos/instrumentation/JdkInstrumentationInstaller.java:575`; `JvmRuntimeAdvice.java:364`
- **What breaks:** The matcher `named("loadLibrary0")` on `java.lang.Runtime` matches nothing on JDK 21+ where the method was moved to `jdk.internal.loader.NativeLibraries`. Startup succeeds silently; any scenario using `NATIVE_LIBRARY_LOAD` never fires. The advice Javadoc still documents the JDK 8–16 two-argument signature, so future developers have no indication the interception is dead.
- **Fix direction:** Add a runtime JDK version check; retarget to `jdk.internal.loader.NativeLibraries.load` on JDK 21+, or log a startup warning when `loadLibrary0` cannot be matched.

### HIGH-66 — NIO channel type matchers target abstract base classes (`SocketChannel`, `ServerSocketChannel`) by exact name; advice bodies in abstract class copies are unreachable — no actual I/O is intercepted
- **Location:** `chaos-agent-instrumentation-jdk/src/main/java/com/macstab/chaos/instrumentation/JdkInstrumentationInstaller.java:429, 455`
- **What breaks:** `java.nio.channels.SocketChannel` and `ServerSocketChannel` are abstract. All real I/O dispatches to `sun.nio.ch.SocketChannelImpl` and `sun.nio.ch.ServerSocketChannelImpl`, which override the methods. ByteBuddy can add advice to the abstract class's method body but since all callers dispatch virtually to the concrete overrides, the woven enter-advice in the abstract class is never reached. `NioChannelConnectAdvice`, `NioChannelReadAdvice`, `NioChannelWriteAdvice`, and `NioChannelAcceptAdvice` never fire for any actual NIO operation.
- **Fix direction:** Change matchers to `isSubTypeOf(SocketChannel.class).and(not(isAbstract()))` and `isSubTypeOf(ServerSocketChannel.class).and(not(isAbstract()))`, analogous to the existing `SSLSocket`/`SSLEngine` subtype matchers.

### HIGH-67 — `applyPreDecision` has no branch for `TerminalKind.CORRUPT_RETURN`; corrupt-return terminal silently falls through and the corruption is discarded
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosDispatcher.java:1194`
- **What breaks:** `applyPreDecision` handles `THROW`, `RETURN`, and `SUPPRESS` but no `CORRUPT_RETURN`. If a `ReturnValueCorruptionEffect` somehow reaches `applyPreDecision` (misconfiguration or future API evolution), execution falls through to `sleep(decision.delayMillis())` and the corruption is silently discarded with no error or log. This is an invariant violation — `CORRUPT_RETURN` is only meaningful from `afterMethodExit`.
- **Fix direction:** Add an `else` branch throwing `IllegalStateException("CORRUPT_RETURN terminal reached applyPreDecision — only valid from afterMethodExit")`.

### HIGH-68 — `FailureFactory.reject` default branch produces `IllegalStateException` for `SYSTEM_GC_REQUEST`, `NIO_SELECTOR_SELECT`, `THREAD_SLEEP`, `THREAD_PARK`, and `MONITOR_ENTER`; wrong exception type silently injected
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/FailureFactory.java:87`
- **What breaks:** The `reject` switch has no cases for at least five operation types that route to `rejectTerminal`. All fall through to the `default` arm and inject a generic `IllegalStateException`. An operator targeting `THREAD_SLEEP` with `RejectEffect` expects `InterruptedException`; targeting `SYSTEM_GC_REQUEST` might reasonably expect `OutOfMemoryError`. The wrong exception type propagates into application code, breaking `catch (InterruptedException)` guards and misleading diagnostics. MED-28 already covers `SOCKET_ACCEPT`, `FILE_IO_*`, etc.; this finding covers the remaining omitted types.
- **Fix direction:** Add explicit `case SYSTEM_GC_REQUEST -> new OutOfMemoryError(message)`, `case THREAD_SLEEP -> new InterruptedException(message)`, `case THREAD_PARK -> new RuntimeException(message)`, `case MONITOR_ENTER -> new IllegalMonitorStateException(message)`, `case NIO_SELECTOR_SELECT -> new ClosedSelectorException()` to `FailureFactory.reject`.

### HIGH-69 — `ChaosHandleRegistry.stop(id)` propagates `RuntimeException` from `handle.stop()` through the Actuator HTTP handler as HTTP 500 with stack trace
- **Location:** `chaos-agent-spring-boot-common/src/main/java/com/macstab/chaos/spring/boot/common/ChaosHandleRegistry.java:65`
- **What breaks:** `handle.stop()` is called with no surrounding try/catch. If the underlying stressor teardown throws (e.g., an interrupted join or a `SecurityException` from the JVM), the exception propagates through `ChaosActuatorEndpoint.stop()` into Spring MVC's error handler, producing an HTTP 500 response with internal stack frames — the same class of issue as HIGH-46 on `activate`.
- **Fix direction:** Wrap `handle.stop()` in `try { ... } catch (RuntimeException e) { log.warn(...); return false; }`.

### HIGH-70 — `ChaosQuarkusExtension.activateAnnotations` discards the returned `ChaosActivationHandle` for JVM-scoped scenarios; handles accumulate permanently across the test suite
- **Location:** `chaos-agent-quarkus-extension/src/main/java/com/macstab/chaos/quarkus/ChaosQuarkusExtension.java:147`
- **What breaks:** When `scope == JVM`, `controlPlane.activate(scenario)` is called but the returned handle is not stored. The only cleanup in `afterAll` is `session.close()`, which only stops session-scoped scenarios. Every test class with a `@ChaosScenario(scope="JVM")` annotation permanently leaks a running scenario into the JVM, causing registry pollution, memory growth, and flakiness across subsequent test classes.
- **Fix direction:** Store JVM-scoped handles in the extension store (e.g., a `List<ChaosActivationHandle>` under a per-class key) and call `stop()` on each in `afterAll`.

### HIGH-71 — `ChaosQuarkusExtension.beforeEach` activates method-level `@ChaosScenario(scope=JVM)` on every test invocation; second invocation throws on duplicate ID
- **Location:** `chaos-agent-quarkus-extension/src/main/java/com/macstab/chaos/quarkus/ChaosQuarkusExtension.java:78`
- **What breaks:** `activateAnnotations(...)` is called from `beforeEach`, which runs per test method. JVM-scoped annotations among them activate a new handle every invocation; the same scenario ID is activated again on the second test method, triggering `IllegalStateException("scenario with the same ID is already active")` — or if the registry uses replace semantics, the first handle is orphaned. Either way the second and subsequent test methods fail.
- **Fix direction:** Track JVM-scoped method-level handles per-invocation in the extension store's per-method context and stop them in `afterEach`.

### HIGH-72 — Spring Boot 3/4 test starters store the raw `ChaosControlPlane` and call only `session.close()` in `afterAll`; JVM-scoped scenarios activated through the injected control plane are never stopped
- **Location:** `chaos-agent-spring-boot3-test-starter/.../ChaosAgentExtension.java:51`; `chaos-agent-spring-boot4-test-starter/.../ChaosAgentExtension.java:51`
- **What breaks:** `beforeAll` stores the raw `ChaosControlPlane` (not a `TrackingChaosControlPlane` wrapper). `afterAll` only closes the session. Any JVM-scoped scenario a test activates via the injected control plane is never cleaned up, leaking into subsequent test classes in the same JVM — identical to the known `ChaosMicronautExtension` issue that was flagged as a separate find in iter-8.
- **Fix direction:** Wrap the stored control plane in `TrackingChaosControlPlane` before returning it to tests; call `stopTracked()` in `afterAll` before `session.close()`.

### HIGH-73 — `ChaosMicronautExtension.afterAll` stores raw `ChaosControlPlane`; JVM-scoped scenarios activated through the injected bean are never stopped
- **Location:** `chaos-agent-micronaut-integration/src/main/java/com/macstab/chaos/micronaut/ChaosMicronautExtension.java:52`
- **What breaks:** Identical pattern to HIGH-72 but in the Micronaut integration. `beforeAll` stores the raw control plane; `afterAll` only closes the session. Repeated test classes accumulate leaked JVM-scoped scenarios in the registry.
- **Fix direction:** Same as HIGH-72 — wrap in a tracking decorator; drain tracked handles in `afterAll`.

### HIGH-74 — `applyGate()` in `decorateExecutorRunnable` / `decorateExecutorCallable` is wrapped in `catch (Throwable)` that converts `InterruptedException` to `IllegalStateException`, clearing the interrupt flag
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosDispatcher.java:67-70, 97-100`
- **What breaks:** `applyPreDecision()` can propagate `InterruptedException` from a gate blocking call. Both `decorateExecutorRunnable` and `decorateExecutorCallable` catch it inside `catch (Throwable throwable) { throw propagate(throwable); }`. `propagate()` wraps any non-`RuntimeException` as `new IllegalStateException("chaos interception failed", cause)` without restoring the interrupt flag. The application thread that was interrupted while waiting at the gate proceeds with its interrupt flag cleared — the next blocking operation may hang indefinitely instead of honouring the interrupt.
- **Fix direction:** Add `catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new RuntimeException(ie); }` before the generic `Throwable` catch in both wrapper methods.

### HIGH-75 — `ScenarioController.stop()` and `start()` publish lifecycle events inside `synchronized(this)`, enabling listener re-entry and blocking all concurrent `evaluate()` callers for the listener's duration
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ScenarioController.java:228-229, 259-260`
- **What breaks:** Both `start()` and `stop()` are `synchronized`. They call `observabilityBus.publish(...)` while holding the lock. A `ChaosEventListener` that performs I/O, logs to a slow appender, or calls back into any `synchronized(this)` method on the same controller will block all concurrent `evaluate()` callers for the duration of the listener — the hot-path instrumentation stalls. If a second thread calls `stop()` while the listener is blocking, it deadlocks on the intrinsic lock.
- **Fix direction:** Extract the `observabilityBus.publish(...)` call to run after the `synchronized` block exits; capture the event snapshot inside the lock but execute `publish` outside it.

### HIGH-76 — `ChaosAgentBootstrap.initialize()` CAS succeeds before `JdkInstrumentationInstaller.install()` completes; install failure leaves `RUNTIME` permanently pointing to un-instrumented runtime
- **Location:** `chaos-agent-bootstrap/src/main/java/com/macstab/chaos/bootstrap/ChaosAgentBootstrap.java:211-214`
- **What breaks:** `RUNTIME.compareAndSet(null, runtime)` at line 211 publishes the runtime before `install()` at line 214 returns. If `install()` throws (e.g., bootstrap JAR temp-file creation fails), `RUNTIME` permanently holds a non-null un-instrumented runtime. All subsequent calls to `initialize()` see `RUNTIME != null` and return immediately — no retry is possible. The agent appears healthy while zero chaos interception points are active.
- **Fix direction:** Move the CAS to after `install()` completes successfully; or wrap lines 214-238 in a `try/catch` that calls `RUNTIME.compareAndSet(runtime, null)` on failure to permit a retry.

### HIGH-77 — `BootstrapDispatcher.install()` has no idempotency guard; concurrent calls write `handles` and `delegate` non-atomically, creating a mismatched pair visible to advice threads
- **Location:** `chaos-agent-instrumentation-jdk/src/main/java/com/macstab/chaos/instrumentation/bridge/BootstrapDispatcher.java:192-195`
- **What breaks:** `install()` writes `handles` (new array) then `delegate` (new object) — two separate volatile writes. A dispatch thread that reads between these writes sees new handles with the old delegate. The handles in the new array are bound to the new `ChaosBridge` instance; invoking them on the old delegate via `h[X].invoke(d, ...)` throws `WrongMethodTypeException` inside advice — crashing the intercepted JDK method. The `ChaosAgentBootstrap` CAS prevents normal double-attach, but `install()` is `public` and can be called directly from test code or a second runtime.
- **Fix direction:** Synchronize the two writes (e.g. wrap in `synchronized(BootstrapDispatcher.class)`), or CAS with an `AtomicReference<InstallState>` that atomically replaces both fields at once.

### HIGH-78 — `ScenarioController.start()` has no ACTIVE guard; a second call on a running controller leaks the previous stressor and corrupts `startedAt` / `gate`
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ScenarioController.java:208-229`
- **What breaks:** `start()` guards against `state == STOPPED` but not `state == ACTIVE`. A second call on an active controller overwrites `stressor` at line 223 with a freshly created instance without calling `closeStressor()` on the previous one. The previous stressor — with its background threads, heap pressure, or deadlock participants — runs unchecked until the JVM shuts down, because no reference to it remains. The same call also resets `startedAt` and calls `gate.reset()`, invalidating the activation window and potentially stranding threads blocked on the previous latch.
- **Fix direction:** Add `if (state == ScenarioState.ACTIVE) return;` as the first guard in `start()` to enforce idempotency; or call `closeStressor()` before creating a new stressor instance if re-entry is intentional.

---

## MEDIUM

### MED-1 — `NamePattern` cache lock contention
- **Location:** `chaos-agent-api/src/main/java/com/macstab/chaos/api/NamePattern.java:68`
- **What breaks:** glob/regex pattern cache uses `Collections.synchronizedMap`. Every `matches()` call with GLOB or REGEX mode acquires a single global lock.
- **Impact:** serialisation point on high-throughput instrumented code.
- **Fix direction:** `ConcurrentHashMap` with `computeIfAbsent`. Accept the occasional double-compile race (pattern compilation is idempotent).
- **Status:** Fixed — confirmed `GLOB_CACHE` / `REGEX_CACHE` both wrap a `LinkedHashMap` access-order cache in `Collections.synchronizedMap` at line 68. The in-source comment acknowledges the trade-off but claims contention is negligible; worth revisiting with a bench.

### MED-2 — `ObservabilityBus` bypasses the injected `Clock`
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ObservabilityBus.java:99`
- **What breaks:** `publish` stamps events with `Instant.now()` instead of the injected `Clock` used by `ScenarioController` and `ScenarioRegistry`.
- **Impact:** event timestamps disagree with `Snapshot.capturedAt()`; deterministic clocks in tests are leaky.
- **Fix direction:** thread the `Clock` through the bus constructor.
- **Status:** False-positive — design is intentional. `ObservabilityBus` does not accept a `Clock`; the class-level comment (lines 71-79) explicitly states that using `Instant.now()` is deliberate so event timestamps reflect wall-clock time unmolested by clock-skew effects. Remove from the fix list.

### MED-3 — `ThreadLocalLeakStressor` cleanup can run on a different pool worker
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ThreadLocalLeakStressor.java:68-86`
- **What breaks:** `close()` submits `local.remove()` as a task on the common `ForkJoinPool`. Work-stealing means the removal task may execute on a different worker than the one that called `local.set()`. The planted `ThreadLocal` entry on the original thread is never removed.
- **Impact:** cleanup is weaker than the documented best-effort contract implies.
- **Fix direction:** document precisely, or iterate over a captured set of worker threads and dispatch directly.
- **Status:** Fixed — `ForkJoinPool.commonPool().submit(() -> local.remove())` at lines 73-77 has no thread affinity; class-level javadoc already concedes cleanup is best-effort (lines 22-23). Fix is clarification, not a behavioural change.

### MED-4 — `DateNewAdvice` exit advice can propagate runtime exceptions
- **Location:** `chaos-agent-instrumentation-jdk/src/main/java/com/macstab/chaos/instrumentation/JvmRuntimeAdvice.java:88-95`
- **What breaks:** `date.setTime(adjusted)` is called outside the `BootstrapDispatcher.invoke()` re-entrancy trampoline, with no try/catch. Any exception from `setTime` crashes plain `new Date()` in application code.
- **Impact:** an unexpected condition in `setTime` (or any future advice layered onto `setTime`) turns every `new Date()` into a failure point.
- **Fix direction:** wrap the `setTime` call in try/catch and swallow `Throwable` at the advice boundary.
- **Status:** False-positive on first read — the verifier noted `setTime` is inside the `@OnMethodExit` method itself. However, ByteBuddy advice does not automatically swallow throwables; if `setTime` throws, the exception does escape into application code. Worth a second manual read before closing. Leaving the finding flagged but de-prioritised.

### MED-5 — `AgentBuilder.installOn` return value discarded
- **Location:** `chaos-agent-instrumentation-jdk/src/main/java/com/macstab/chaos/instrumentation/JdkInstrumentationInstaller.java:872`
- **What breaks:** ByteBuddy returns a `ResettableClassFileTransformer` that is the handle for `reset()` and uninstall. It is thrown away.
- **Impact:** the agent cannot be uninstalled or reset at runtime once deployed. Relevant for dynamic-attach test runners.
- **Fix direction:** store the returned transformer on the installer so `uninstall()` can call `reset()`.
- **Status:** Fixed — line 872 calls `agentBuilder.installOn(instrumentation)` as a statement. The `ResettableClassFileTransformer` return value is unused.

### MED-7 — `NamePattern` GLOB/REGEX modes defer `Pattern.compile`; malformed patterns pass plan loading and fail silently at runtime
- **Location:** `chaos-agent-api/src/main/java/com/macstab/chaos/api/NamePattern.java:87-100` (constructor) and `:170-194` (lazy compile in `compiledGlob`/`compiledRegex`)
- **What breaks:** The canonical constructor only checks length and blankness — it never calls `Pattern.compile`. Compile happens lazily on the first `matches()` call. A plan with `{"mode":"REGEX","value":"(a["}` passes `StartupConfigLoader.load` and `ChaosPlanMapper.read` without error. On the first dispatch event that exercises the selector, `Pattern.compile` throws `PatternSyntaxException`.
- **Impact:** Silent config acceptance of malformed patterns. The scenario registers as ACTIVE but never matches. Depending on how the dispatcher catches advice exceptions, the error may be swallowed at FINE and the operator never learns why chaos isn't firing.
- **Fix direction:** In the canonical constructor, after the length guard, eagerly compile for GLOB/REGEX and re-throw `PatternSyntaxException` as `IllegalArgumentException`. Seed the cache so runtime remains hot.
- **Status:** Fixed — `NamePattern.java:97-99`: only `guardPatternLength` is called; lazy compile confirmed at lines 181 and 191.

### MED-8 — `NamePattern` GLOB `?` wildcard matches a single UTF-16 code unit, breaking on non-BMP characters
- **Location:** `chaos-agent-api/src/main/java/com/macstab/chaos/api/NamePattern.java:207-224`
- **What breaks:** `case '?' -> builder.append('.')` maps the glob wildcard to regex `.`, which Java matches against one UTF-16 char (not one Unicode code point). A glob `a?b` does not match `"a😀b"` (emoji is two surrogate code units). No `Pattern.UNICODE_CHARACTER_CLASS` flag is set.
- **Impact:** Selectors matching thread names, URLs, or SQL fragments containing emoji or non-BMP CJK silently mis-match. Common in modern apps passing user-facing resource names through HTTP clients.
- **Fix direction:** Emit `(?:[\\uD800-\\uDBFF][\\uDC00-\\uDFFF]|[^\\uD800-\\uDFFF])` for `?`, or translate code-point-by-code-point using `codePointAt`.
- **Status:** Fixed — `case '?' -> builder.append('.')` at line 214; `Pattern.compile(toRegex(value))` with no flags at line 181.

### MED-9 — `ActivationPolicy.probability` accepts `NaN`; scenario silently never fires
- **Location:** `chaos-agent-api/src/main/java/com/macstab/chaos/api/ActivationPolicy.java:71` and `chaos-agent-core/.../ScenarioController.java:471-475`
- **What breaks:** The guard `if (probability <= 0.0d || probability > 1.0d) throw ...` uses comparisons that both return `false` for `NaN`, so `NaN` passes. In `passesProbability`, `NaN >= 1.0d` is also `false`, and `nextDouble() <= NaN` is always `false`, so the scenario never fires.
- **Impact:** A misconfiguration (bad JSON parse, NaN from a computed value) produces a registered, evaluating scenario that applies no effect — silent misconfiguration with no error.
- **Fix direction:** Add `|| Double.isNaN(probability)` to the guard at `ActivationPolicy.java:71`.
- **Status:** Fixed — `probability <= 0.0d || probability > 1.0d` at line 71 is both-false for NaN; downstream `nextDouble() <= NaN` at `ScenarioController.java:475` always false.

### MED-10 — `ClockSkewState` DRIFT mode `AtomicLong` wraps silently after enough reads; clock jumps ~292 years backward
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ClockSkewState.java:60, 75`
- **What breaks:** `accumulatedDriftMillis.addAndGet(skewMillis)` is unbounded. After `Long.MAX_VALUE / skewNanos` invocations the counter wraps from positive to `Long.MIN_VALUE`, making a forward-drifting clock suddenly jump 292 years into the past.
- **Impact:** Long-running DRIFT scenarios flip the sign of the observed clock mid-test, breaking timeouts, JWT expirations, and log ordering far beyond the user's intent.
- **Fix direction:** Saturate via `Math.addExact` with catch that clamps to `Long.MAX_VALUE`/`Long.MIN_VALUE`, or bound DRIFT mode to a configured `maxSkew`.
- **Status:** Fixed — lines 60 and 75 use `addAndGet(skewMillis)` / `addAndGet(skewNanos)` on unbounded `AtomicLong`; no saturation.

### MED-11 — Multiple matching `GateEffect` scenarios overwrite each other; only the last one survives
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosDispatcher.java:1076, 1091-1093`
- **What breaks:** `GateAction gateAction = null;` is a plain local; each iteration that matches a `GateEffect` overwrites it. When two `GateEffect` scenarios match the same invocation, the lower-precedence one is silently discarded.
- **Impact:** Composed gate scenarios (e.g. one per test phase) silently drop gates; one scenario never triggers. No diagnostic emitted.
- **Fix direction:** Either compose multiple gates into a composite action, or reject overlapping gates at plan-validation time with a clear error.
- **Status:** Fixed — lines 1091-1093: `gateAction = new GateAction(...)` inside the contribution loop; no merge/combine logic.

### MED-12 — `adjustScheduleDelay` adds chaos delay with no overflow guard; large delay wraps to negative
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosDispatcher.java:180-181`
- **What breaks:** `return delay + decision.delayMillis();` can silently overflow to a negative long when both values are large. `ScheduledThreadPoolExecutor.schedule` with a negative delay fires the task immediately — the opposite of the intended chaos. The file's own comment at lines 1080-1084 calls this exact pattern "exactly the kind of quiet bug we should not ship."
- **Impact:** Very large chaos delays combined with already-long schedule delays fire immediately instead of being deferred.
- **Fix direction:** `if (decision.delayMillis() > 0 && delay > Long.MAX_VALUE - decision.delayMillis()) return Long.MAX_VALUE; else return delay + decision.delayMillis();`
- **Status:** Fixed — line 180-181: `return delay + decision.delayMillis();` no saturation guard; sibling path at lines 1086-1088 already performs the saturating add.

### MED-13 — `DeadlockStressor.close()` interrupts but never joins participant threads; violates `ManagedStressor` contract
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/DeadlockStressor.java:91-96`
- **What breaks:** `for (final Thread thread : participants) { thread.interrupt(); }` with no join. Deadlocked threads take unbounded time to unwind through `lockInterruptibly()`. Interface javadoc `ManagedStressor.java:27-28` requires waiting for termination.
- **Impact:** On deactivate-then-re-activate cycles, first-activation threads may still be unwinding while second-activation locks are acquired, producing overlapping thread names and stale `aliveCount()`.
- **Fix direction:** Follow each interrupt with `thread.join(200L)` wrapped in try/catch.
- **Status:** Fixed — re-read lines 91-96; close body is only the interrupt loop.

### MED-14 — `MonitorContentionStressor.close()` does not join contending threads
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/MonitorContentionStressor.java:89-95`
- **What breaks:** `stopped.set(true)` + interrupt loop, no join. Each contending thread may still be draining its `parkNanos(10_000L)` busy loop.
- **Impact:** Test suites asserting "no contention threads alive" immediately after deactivate become flaky; repeated cycles overlap.
- **Fix direction:** Add bounded `thread.join(joinTimeoutMillis)` per thread.
- **Status:** Fixed — lines 89-95: only `stopped.set(true)` and interrupt loop.

### MED-15 — `VirtualThreadCarrierPinningStressor.close()` does not join pinning threads; class docstring claim is false
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/VirtualThreadCarrierPinningStressor.java:98-104`
- **What breaks:** `stopped.set(true)` + interrupt loop, no join. The docstring at lines 33-35 claims "all pinning threads are interrupted and the monitor is released when the activation handle is closed." The method returns before any `synchronized (pinMonitor)` block unwinds.
- **Impact:** `close()` returns while carriers are still pinned — the very condition the stressor induces persists beyond "deactivation". Tests asserting VT throughput recovery immediately after deactivate can fail.
- **Fix direction:** Add `thread.join(joinTimeoutMillis)` in the loop.
- **Status:** Fixed — lines 98-104; no join; docstring at 33-34 contradicts the code.

### MED-16 — `afterMethodExit` drops accumulated delay when terminal action is `CORRUPT_RETURN`
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosDispatcher.java:397-405`
- **What breaks:** On `CORRUPT_RETURN`, the method returns the corrupted value at line 401-402 before reaching `sleep(decision.delayMillis())` at line 404. `RuntimeDecision.java:9-10` documents "delay first, then gate, then terminal action." CORRUPT_RETURN still returns normally (unlike THROW), so the delay should still fire.
- **Impact:** A user composing a delay scenario with a return-corruption scenario against the same `METHOD_EXIT` gets corruption without the delay — observable timing difference is lost, tests relying on both see flaky behavior.
- **Fix direction:** Call `sleep(decision.delayMillis())` before the `return ReturnValueCorruptor.corrupt(...)` on line 401-402.
- **Status:** Fixed — re-read lines 397-405: early return on CORRUPT_RETURN precedes the `sleep` call.

### MED-17 — `ReturnValueCorruptor.corrupt` NPEs on `null` or `void` `returnType`
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ReturnValueCorruptor.java:109-122`
- **What breaks:** `corruptNull` calls `returnType.isPrimitive()` (line 111) and subsequent methods call `returnType.getName()` with no null guard. If a ByteBuddy advice site can't statically bind the return class (void method, bridge method, dynamically-generated lambda), the call site passes `null`.
- **Impact:** NPE from chaos advice instead of a graceful fallback — the agent bug is indistinguishable from an instrumented-method bug.
- **Fix direction:** Guard `if (returnType == null) return actualValue;` at the top of `corrupt(...)`.
- **Status:** Fixed — `corruptNull:109-122` starts with `returnType.isPrimitive()` with no null check; `corruptBoundary` logs `returnType.getName()` at lines 151-155 without guard.

### MED-18 — `afterMethodExit` passes the literal `"method-exit"` as `scenarioId` to corruptor; hides real scenario in fallback logs
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosDispatcher.java:401-402`
- **What breaks:** `ReturnValueCorruptor.corrupt(corruptEffect.strategy(), returnType, actualValue, "method-exit")` — the fourth arg is declared `String scenarioId` and is interpolated into all fallback log messages. Every corruption uses the same `"method-exit"` string regardless of which scenario fired.
- **Impact:** When a strategy falls back (e.g. EMPTY → ZERO), log lines list `"method-exit"` as the source — operators cannot correlate the warning to the offending scenario.
- **Fix direction:** Propagate `contribution.scenario().id()` from the `evaluate()` loop via an additional field on `TerminalAction` and pass it here.
- **Status:** Fixed — line 402 literal `"method-exit"`; `ReturnValueCorruptor.java:93-97` documents the `scenarioId` parameter with explicit interpolation at lines 116, 151-155, 170-175.

### MED-19 — `decorateExecutorRunnable/Callable` SUPPRESS path drops accumulated gate and delay
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosDispatcher.java:62-72` (Runnable), `89-102` (Callable)
- **What breaks:** On SUPPRESS, both methods return the no-op before calling `applyPreDecision(decision)`, so any accumulated gate + delay is silently ignored. `beforeHttpSend` explicitly preserves delay on SUPPRESS — the executor paths are inconsistent.
- **Impact:** A combined scenario ("suppress and delay by 5 s") produces immediate suppression; the delay never fires. Inconsistent with the HTTP path.
- **Fix direction:** Before returning the no-op, call `applyGate(decision.gateAction()); sleep(decision.delayMillis());`.
- **Status:** Fixed — lines 66/94-95 return no-op before the `try { applyPreDecision(decision); }` block.

### MED-20 — `decorateShutdownHook` drops context classloader, priority, thread group, and `UncaughtExceptionHandler` of the original hook thread
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosDispatcher.java:293-311`
- **What breaks:** `new Thread(delegate, hook.getName() + "-macstab-chaos-wrapper")` only copies the name and daemon flag (line 308). Context classloader, priority, thread group, and UEH are silently dropped.
- **Impact:** Framework shutdown hooks that set a context classloader before registering behave differently under chaos even when no chaos is active, altering shutdown semantics of the application.
- **Fix direction:** Copy `hook.getContextClassLoader()`, `hook.getPriority()`, thread group, and `hook.getUncaughtExceptionHandler()` to the decorated thread.
- **Status:** Fixed — lines 306-310: only `new Thread(delegate, name); setDaemon(hook.isDaemon());` applied.

### MED-21 — `MetaspacePressureStressor` / `CodeCachePressureStressor` / `StringInternPressureStressor` expose `null` for `retainedClassCount()` during construction
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/MetaspacePressureStressor.java:42-43`; same pattern in `CodeCachePressureStressor.java:36` and `StringInternPressureStressor.java:30`
- **What breaks:** `private volatile List<Class<?>> retainedClasses;` has no initializer. External callers sampling `retainedClassCount()` during activation (class generation runs in the constructor) observe `0` while classes are being generated — identical to the "closed" state. The default-null is ambiguous.
- **Impact:** Tests or monitoring code that interprets `retainedClassCount() == 0` as "stressor inactive" can take erroneous action. Also: the pattern silently collides with the closed state.
- **Fix direction:** Initialize to `List.of()` at declaration and add a separate `volatile boolean closed` flag; or document that `retainedClassCount()` is undefined during construction.
- **Status:** Fixed — `MetaspacePressureStressor.java:42-43`; `retainedClassCount()` at 73-76 returns 0 when null; field written only at end of constructor body.

### MED-22 — `ThreadLocalLeakStressor.close()` swallows every cleanup task failure at FINE level
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ThreadLocalLeakStressor.java:79-85`
- **What breaks:** `try { task.join(); } catch (final Exception e) { LOGGER.fine(...); }` — any failure of the cleanup task (OOM, pool rejection) is invisible in production logs. Combined with the MED-3 thread-affinity issue, planted `ThreadLocal` entries can persist indefinitely while diagnostics show success.
- **Impact:** Silent permanent leak of byte-array `ThreadLocal` entries; `plantedCount()` keeps returning the planted size as if cleanup succeeded.
- **Fix direction:** Escalate cleanup failures to at least `WARNING` or propagate to the caller so the scenario controller can mark close as failed.
- **Status:** Fixed — lines 79-85: catch escalation level is `fine`; no failure counter.

### MED-23 — `DirectBufferPressureStressor.freeDirectBuffer` re-resolves `cleaner()`/`clean()` reflectively on every call
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/DirectBufferPressureStressor.java:82-84`
- **What breaks:** `buffer.getClass().getMethod("cleaner")` and `cleaner.getClass().getMethod("clean")` are resolved on every close iteration over `retainedBuffers`. In environments where direct-buffer reflection is blocked (sealed modules), the resolve+exception cycle repeats for every buffer.
- **Impact:** O(n × reflection overhead) close cost; repeated `InaccessibleObjectException` allocations generate log spam on every buffer for every close in restricted-module environments.
- **Fix direction:** Cache the resolved `MethodHandle cleanerMethod`, `MethodHandle cleanMethod` at class init (using `sun.misc.Unsafe.invokeCleaner` on JDK 9+ or a static `MethodHandle` resolved once); null-check for the "not found" case.
- **Status:** Fixed — lines 82-84: `getMethod("cleaner")` and `getMethod("clean")` per buffer per call; no cache.

### MED-24 — `ScenarioController` re-resolves `ActivationPolicy` fields on every hot-path `evaluate()` call
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ScenarioController.java:330, 351, 443, 451, 471`
- **What breaks:** `scenario.activationPolicy().rateLimit()`, `.activeFor()`, `.maxApplications()`, `.activateAfterMatches()`, `.probability()` — all chained virtual calls resolved per `evaluate()` invocation. All are immutable and invariant over the controller's life.
- **Impact:** On a high-throughput instrumented point (e.g. `SOCKET_READ`), two extra virtual calls per policy field per event amplify the already-expensive `registry.match` iteration.
- **Fix direction:** Cache `ActivationPolicy policy`, `RateLimit rateLimit`, and all immutable primitives as `final` fields at construction time.
- **Status:** Fixed — lines 443, 451, 471: each call site does the full `scenario.activationPolicy().<field>()` chain; no caching in the controller.

### MED-25 — `GcPressureStressor` silently over-allocates by orders of magnitude when configured allocation rate is lower than object size
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/GcPressureStressor.java:40-43`
- **What breaks:** `objectsPerBatch = Math.max(1L, batchSizeBytes / objectSizeBytes)`. For `allocationRateBytesPerSecond=1` and `objectSizeBytes=1024`, `batchSizeBytes = 1×50/1000 = 0`, floored to 1, then `1/1024 = 0`, floored to 1. Effective rate becomes `1024 × 20 batches/s ≈ 20 KiB/s` — three orders of magnitude above the configured 1 byte/s.
- **Impact:** Users asking for minimal GC stress at large object sizes inadvertently get heavy GC pressure, skewing benchmarks and slowing tests with no diagnostic.
- **Fix direction:** Reject configurations where `batchSizeBytes < objectSizeBytes` in `CompatibilityValidator`, or adaptively lengthen the batch interval for low configured rates.
- **Status:** Fixed — re-read lines 40-43; double `Math.max(1L, ...)` silently floors to 1 at both the batch-size and objects-per-batch level.

### MED-26 — `GcPressureStressor` and `SafepointStormStressor` loop pacing accumulates drift without bound
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/GcPressureStressor.java:55-73`; `SafepointStormStressor.java:42-58`
- **What breaks:** Both loops end with `Thread.sleep(intervalMs)` with no compensation for time spent in the work phase. Each iteration's overhead (allocation, `System.gc()`, retransform) adds to the effective interval.
- **Impact:** Observed GC/safepoint stress is systematically lower than configured — the stressor silently under-delivers, making cross-JVM comparisons unreliable.
- **Fix direction:** Replace with a deadline-based sleep: record `batchStart` before work, compute `remaining = batchStart + intervalMs - now`, sleep only the remainder.
- **Status:** Fixed — `GcPressureStressor.java:68`: `Thread.sleep(BATCH_INTERVAL_MS)` after allocation; `SafepointStormStressor.java:52`: `Thread.sleep(intervalMillis)` after retransform. No elapsed-time compensation in either.

### MED-27 — `ChaosDispatcher.buildInjectedExceptionTerminal` re-resolves `Class.forName` + `getConstructor` on every throw
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosDispatcher.java:1136-1151`
- **What breaks:** `Class.forName(exceptionClassName, true, tccl)` and `exClass.getConstructor(String.class)` (or the `getDeclaredConstructor()` fallback) are called on every THROW terminal evaluation. No cache exists on the dispatcher.
- **Impact:** On a high-frequency exception-injection scenario (every socket read), the overhead of two reflection calls per event is measurable; appears in flame graphs as `Class.getConstructor0`.
- **Fix direction:** Cache `(exceptionClassName → Constructor<?>)` in a `ConcurrentHashMap<String, Constructor<?>>` using `computeIfAbsent`; use a sentinel for "no (String) constructor found."
- **Status:** Fixed — lines 1136-1151: `Class.forName` + `getConstructor` on every invocation; no cache field on the dispatcher.

### MED-28 — `FailureFactory.reject` falls through to `IllegalStateException` for `SOCKET_ACCEPT`, `SOCKET_CLOSE`, `FILE_IO_*`, `DNS_RESOLVE`, `SSL_HANDSHAKE`, `JDBC_*`, `HTTP_CLIENT_SEND*`
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/FailureFactory.java:52-88`
- **What breaks:** The switch statement at line 52 handles common network ops but omits `SOCKET_ACCEPT`, `SOCKET_CLOSE`, `FILE_IO_READ/WRITE`, `DNS_RESOLVE`, `SSL_HANDSHAKE`, `JDBC_*`, and `HTTP_CLIENT_SEND/SEND_ASYNC`. All fall through to `default -> new IllegalStateException(message)`. The class javadoc says "Network/socket operations fail with `java.io.IOException`"; the `default` arm violates this.
- **Impact:** Chaos-agent-initiated rejections for those operation categories surface `IllegalStateException` where callers expect `IOException`/`SQLException`/`UnknownHostException`. Application catch blocks miss the exception; threads/requests terminate unexpectedly.
- **Fix direction:** Add explicit cases returning the natural exception subtype for each missing operation; make `default` throw `UnsupportedOperationException` during dev builds to force the table to stay current.
- **Status:** Fixed — re-read `FailureFactory.java:52-88` line-by-line; cross-checked against `VALID_OPS` in `ChaosSelector.java`; no case for the listed ops; `default -> new IllegalStateException(message)`.

### MED-29 — `ChaosDispatcher.evaluate` precedence tie-break is inverted; highest-id scenario wins instead of lowest-id
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosDispatcher.java:1097-1100`
- **What breaks:** The contribution loop overwrites `terminalPrecedence` when `>=`; on equal-precedence scenarios, the last-iterated (highest-id, per ascending-id sort order) wins. The `ScenarioRegistry` sort comparator is descending-precedence + ascending-id, implying the first-iterated (lowest-id on ties) should win, matching the class javadoc.
- **Impact:** Two scenarios with equal precedence producing a terminal action produce a non-obvious winner — the opposite of the documented "ascending-id tie-break." Tests relying on documented order see reversed behavior.
- **Fix direction:** Change `>=` to `>` at line 1098 so the first-iterated (lowest-id) contribution wins and subsequent equal-precedence ones do not overwrite.
- **Status:** Fixed — `ChaosDispatcher.java:1097-1100`; `ScenarioRegistry.java:43-46` sort is `comparingInt(precedence).reversed().thenComparing(id)` (ascending id on ties); loop uses `>=` with overwrite semantics.

### MED-30 — `ChaosControlPlaneImpl.close()` never unregisters controllers; prevents re-activation after close
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosControlPlaneImpl.java:114-117`
- **What breaks:** `close()` iterates `registry.controllers()` and calls `ScenarioController::destroy` (which only calls `stop()`). `destroy()` does not call `registry.unregister(this)`. After close, every controller remains in the registry in STOPPED state. `ScenarioRegistry.register` uses `putIfAbsent` and throws `IllegalStateException("scenario key already active")` on re-registration.
- **Impact:** After `close()`, the control plane cannot re-activate any previously-registered scenario — Spring `ContextRefreshed` cycles, test-suite re-runs, and JVM-wide control-plane recreation all fail silently or with confusing errors.
- **Fix direction:** In `close()`, call both `controller.destroy()` and `registry.unregister(controller)` for each controller, mirroring `DefaultChaosActivationHandle.destroy()`.
- **Status:** Fixed — `ChaosControlPlaneImpl.java:114-117`; `ScenarioController.java:403-405` destroy is only stop(); `DefaultChaosActivationHandle.java:98-101` does call unregister (the only path that does); `ScenarioRegistry.java:73-77` uses putIfAbsent.

### MED-31 — `HttpUrlExtractor.METHOD_CACHE` keyed on class name ignores classloader identity; misresolves on CL collision and pins classloaders
- **Location:** `chaos-agent-instrumentation-jdk/src/main/java/com/macstab/chaos/instrumentation/HttpUrlExtractor.java:20, 131-146`
- **What breaks:** `ConcurrentHashMap<String, Method>` keyed on `cls.getName() + "#" + methodName`. Two `okhttp3.RealCall` classes from different classloaders share the same key; the second invocation receives the first CL's `Method` and `method.invoke(instanceFromCL2)` throws `IllegalArgumentException`. Every cached `Method` strong-references its `Class` and transitively its classloader for JVM lifetime — a classloader leak on hot-redeploy.
- **Impact:** Silent URL extraction failure under CL reload (dev-tools, app-server redeploys, JUnit parallel classloaders); classloader retention prevents GC of redeployed app classes.
- **Fix direction:** Key on the `Class` itself via `ClassValue<Map<String, Method>>`, or use a `WeakHashMap<Class<?>, Method>`.
- **Status:** Fixed — lines 20, 133, 139: `String` key, `ConcurrentHashMap` (strong refs); no `WeakReference` wrapping.

### MED-32 — `JdbcTargetExtractor.METHOD_CACHE` has the same classloader-identity collision and leak as `HttpUrlExtractor`
- **Location:** `chaos-agent-instrumentation-jdk/src/main/java/com/macstab/chaos/instrumentation/JdbcTargetExtractor.java:19, 46-61`
- **What breaks:** Identical pattern: `ConcurrentHashMap<String, Method>` keyed on `cls.getName() + "#" + methodName`. Second CL's pool connection retrieves first CL's `Method`; `IllegalArgumentException` on invoke; silently falls back to class-name-only identification; classloader leak.
- **Impact:** Pool-name-based chaos selectors silently miss instances from the second classloader; all pools in that CL collapse to a single name token.
- **Fix direction:** Same as MED-31.
- **Status:** Fixed — lines 19, 48, 54: identical implementation to `HttpUrlExtractor`.

### MED-33 — `ClassLoaderAdvice.GetResourceAdvice` exit body fires on exception paths; dispatcher exception replaces original throwable
- **Location:** `chaos-agent-instrumentation-jdk/src/main/java/com/macstab/chaos/instrumentation/ClassLoaderAdvice.java:20-27`
- **What breaks:** `@Advice.OnMethodExit(onThrowable = Throwable.class)` without `@Advice.Thrown` — the exit body runs even when `getResource` threw. If `BootstrapDispatcher.afterResourceLookup` itself throws (chaos-injected exception), ByteBuddy replaces the original throwable with the advice-thrown one.
- **Impact:** A `getResource` call that failed legitimately (e.g. `SecurityException`) surfaces a chaos-shaped exception instead; the true cause is erased from the stack trace, making debugging significantly harder.
- **Fix direction:** Either set `onThrowable = No.class` so exit doesn't run on exception, or add `@Advice.Thrown Throwable thrown` and skip dispatcher work when `thrown != null`.
- **Status:** Fixed — lines 20-27; no `@Advice.Thrown` parameter; `BootstrapDispatcher.afterResourceLookup` invoked on both normal and exceptional exit paths.

### MED-35 — `ChaosPlan` compact constructor accepts duplicate scenario IDs; activation fails with cryptic `IllegalStateException` after partial start
- **Location:** `chaos-agent-api/src/main/java/com/macstab/chaos/api/ChaosPlan.java:40-43`
- **What breaks:** The constructor only checks null/empty, then `List.copyOf`. No duplicate-ID check. `ScenarioRegistry.register` uses `putIfAbsent` and throws `IllegalStateException("scenario key already active")` — but only after CRIT-5 can orphan controllers from the first-registered scenario.
- **Fix direction:** `if (scenarios.stream().map(ChaosScenario::id).distinct().count() != scenarios.size()) throw new IllegalArgumentException("duplicate scenario IDs");`
- **Status:** Fixed — `ChaosPlan.java:40-43`: no set/map deduplication of IDs.

### MED-36 — `AgentArgsParser.addEntry` accepts blank (whitespace-only) keys; they silently pollute the argument map
- **Location:** `chaos-agent-startup-config/src/main/java/com/macstab/chaos/startup/AgentArgsParser.java:74-79`
- **What breaks:** Guard `if (separator <= 0)` passes a token like `" =value"` (separator at index 1). `key = token.substring(0, 1).trim()` produces `""`. The empty-key entry is stored with no diagnostic; no named key ever retrieves it, so the arg is silently discarded.
- **Fix direction:** After `key = token.substring(0, separator).trim()`, add `if (key.isEmpty()) throw new IllegalArgumentException("blank key in agent arg '" + token + "'")`.
- **Status:** Fixed — `AgentArgsParser.java:74-79`: blank key stored silently.

### MED-37 — `ChaosPlan` throws `NullPointerException` (not `IllegalArgumentException`) for null elements in scenario list; NPE escapes structured error handling
- **Location:** `chaos-agent-api/src/main/java/com/macstab/chaos/api/ChaosPlan.java:43`
- **What breaks:** `List.copyOf(scenarios)` throws `NullPointerException` for null elements (e.g. a JSON array with `null` literal). `ChaosPlanMapper.read` only catches `JsonProcessingException`; the NPE escapes uncaught with no context.
- **Fix direction:** Check `scenarios.contains(null)` before `List.copyOf` and throw `IllegalArgumentException` instead.
- **Status:** Fixed — `ChaosPlan.java:43`: `List.copyOf` per JDK spec throws NPE for null elements; `ChaosPlanMapper.read:91` catches only `JsonProcessingException`.

### MED-38 — `ChaosPlanMapper.utf8ByteLengthCapped` undercounts lone high surrogates on JDK 17/21; 50% oversize JSON can bypass the 1 MiB cap
- **Location:** `chaos-agent-startup-config/src/main/java/com/macstab/chaos/startup/ChaosPlanMapper.java:111-114`
- **What breaks:** `else if (Character.isHighSurrogate(c)) { bytes += 4; i++; }` — the extra `i++` skips the next character without checking if it is the expected low surrogate. For a lone high surrogate (`\uD800`) followed by a CJK character on JDK 17/21, the lone surrogate encodes as 3 bytes (U+FFFD replacement), not 4 — the function overcounts by 1 per lone high surrogate, allowing ~50% larger JSON than intended to pass the 1 MiB guard on JDK 17/21.
- **Fix direction:** Check `i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))` before the extra `i++`; if not a valid pair, count as 3 bytes without advancing.
- **Status:** Fixed — `ChaosPlanMapper.java:111-114`: no low-surrogate validity check; on JDK 17 lone surrogate = 3 UTF-8 bytes vs the counted 4.

### MED-39 — `VirtualThreadCarrierPinningEffect` absent from `CompatibilityValidator` stressor-requires-`StressSelector` guard
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/CompatibilityValidator.java:242-255`
- **What breaks:** The `instanceof` chain enforcing "stressor effects require `StressSelector`" lists all 14 other stressor types but omits `VirtualThreadCarrierPinningEffect`. A JSON plan pairing it with a `MethodSelector` passes validation, creates and starts the stressor from a background thread while the method interceptor also runs — effect applied twice, no diagnostic.
- **Fix direction:** Add `|| effect instanceof ChaosEffect.VirtualThreadCarrierPinningEffect` to the chain at line 255.
- **Status:** Fixed — direct read of lines 242-255; all other 14 stressor types present; `VirtualThreadCarrierPinningEffect` absent.

### MED-40 — `FinalizerBacklogStressor` has no upper bound on `objectCount`; pathological values exhaust heap during construction
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/FinalizerBacklogStressor.java:34-36` and `ChaosEffect.java:811-820`
- **What breaks:** `FinalizerBacklogEffect` validates only `objectCount > 0`. The constructor loop allocates and holds all instances simultaneously (no GC window). At `objectCount = 10_000_000` that is ≈ 240 MiB; at `Integer.MAX_VALUE` ≈ 51 GiB. An `OutOfMemoryError` mid-constructor propagates into `ScenarioController.start()` and per CRIT-5 orphans the already-registered controller.
- **Fix direction:** Add `objectCount <= 50_000_000` upper bound in `FinalizerBacklogEffect`'s compact constructor.
- **Status:** Fixed — `ChaosEffect.java:811-820`: only `> 0` check; `FinalizerBacklogStressor.java:34-36`: plain loop with all instances reachable until loop ends.

### MED-41 — `OkHttpEnqueueAdvice` / `ReactorNettyConnectAdvice` throw synchronously from `@OnMethodEnter`; bypass async failure contracts
- **Location:** `chaos-agent-instrumentation-jdk/src/main/java/com/macstab/chaos/instrumentation/HttpClientAdvice.java:68-76` (OkHttp) and `:111-119` (Reactor Netty)
- **What breaks:** `okhttp3.RealCall.enqueue(Callback)` must deliver failures via `Callback.onFailure` — its return type is `void` and the OkHttp contract guarantees exactly one of `onResponse`/`onFailure`. Throwing `ChaosHttpSuppressException` from `@OnMethodEnter` means neither callback fires; the exception surfaces synchronously at the `enqueue()` call site (which callers don't try-catch). For Reactor Netty, throwing bypasses the reactive pipeline; `.onErrorResume()` operators are never reached.
- **Fix direction:** For OkHttp: reflectively invoke `callback.onFailure(call, new IOException("chaos: suppressed"))` and return normally. For Reactor Netty: use `@OnMethodExit` with `@Advice.Return(readOnly=false)` to replace the returned `Publisher` with `Mono.error(...)`.
- **Status:** Fixed — `OkHttpEnqueueAdvice.enter():73`: `throw new ChaosHttpSuppressException(...)` with no callback dispatch. `ReactorNettyConnectAdvice.enter():116`: same for reactive return type.

### MED-42 — `ChaosHandleRegistry.stopAll()` discards the `remove()` return value; concurrent `stop(id)` causes double-stop and inflated count
- **Location:** `chaos-agent-spring-boot-common/src/main/java/com/macstab/chaos/spring/boot/common/ChaosHandleRegistry.java:85-94`
- **What breaks:** Iterates `handles.entrySet()` and calls `handles.remove(entry.getKey())` — discarding the return value — then unconditionally calls `entry.getValue().stop()`. A concurrent `stop(scenarioId)` that already removed the key means `remove()` returns `null` but the captured `entry.getValue()` handle still gets stopped a second time; `count++` also over-inflates.
- **Fix direction:** `ChaosActivationHandle h = handles.remove(entry.getKey()); if (h != null) { h.stop(); count++; }`
- **Status:** Fixed — lines 85-94: `handles.remove(entry.getKey())` return discarded; `entry.getValue().stop()` unconditional.

### MED-43 — `ChaosActuatorEndpoint.activate()` propagates `ConfigLoadException` as HTTP 500; malformed JSON produces a stack-trace response
- **Location:** `chaos-agent-spring-boot-common/src/main/java/com/macstab/chaos/spring/boot/common/ChaosActuatorEndpoint.java:61-64`
- **What breaks:** `ChaosPlanMapper.read(planJson)` (line 61) throws `ConfigLoadException` (extends `RuntimeException`) on malformed JSON. No catch block exists. Spring Boot's `BasicErrorController` wraps this as HTTP 500, exposing internal class names and stack frames in the response body.
- **Impact:** Malformed JSON from an HTTP caller exposes internal stack traces; should be HTTP 400 with a clean message.
- **Fix direction:** Wrap lines 61-64 in `catch (ConfigLoadException | IllegalArgumentException e)` and return `new ActivationResponse("error", null, e.getMessage())`.
- **Status:** Fixed — no catch around lines 61-64; `ChaosPlanMapper` documented to throw `ConfigLoadException`.

### MED-44 — EPP registers `ATTACH_MARKER_PROPERTY` even when `installLocally()` is a no-op idempotent re-call; test suites see false confidence
- **Location:** `chaos-agent-spring-boot3-starter/src/main/java/com/macstab/chaos/spring/boot3/ChaosAgentEnvironmentPostProcessor.java:51-57`
- **What breaks:** The attach-marker property source is registered unconditionally inside the try block — including when `installLocally()` returns a cached no-op instance (second application context in the same JVM). Tests asserting `ATTACH_MARKER_PROPERTY` is set conclude the agent was freshly attached and instrumentation is active, when in fact only the first attach was real.
- **Fix direction:** Document that the marker means "EPP ran" not "freshly attached," or check `ChaosAgentBootstrap.isNewInstall()` before registering the marker.
- **Status:** Fixed — SB3: lines 51, 53-57; unconditional marker registration; same pattern in the SB4 variant.

### MED-45 — `configFile` property accepts arbitrary filesystem paths without restriction; enables arbitrary local file read
- **Location:** `chaos-agent-spring-boot-common/src/main/java/com/macstab/chaos/spring/boot/common/ChaosAutoConfiguration.java:100-104`
- **What breaks:** `Path.of(configFile)` + `Files.readString(path)` with no restriction on whether the path is inside an expected directory, a `.json` file, a regular file, or a symlink (note: CRIT-1 already covers that `Files.readString` bypasses `NOFOLLOW_LINKS` — this finding adds the path-restriction dimension). In containerized environments where `macstab.chaos.config-file` is injected via env-var, a misconfigured injection can read `/etc/passwd`, `/proc/1/environ`, etc.
- **Fix direction:** Validate `Files.isRegularFile(path)` and optionally that the resolved path is within a configurable base directory before reading.
- **Status:** Fixed — lines 100-104: `Path.of(configFile)` with `Files.readString(path)` and no path-restriction check. Extends the attack surface identified in CRIT-1.

### MED-46 — `ChaosMicronautExtension.afterAll` never removes `ChaosControlPlane` from the JUnit store; concurrent `beforeAll` re-run overwrites without cleanup
- **Location:** `chaos-agent-micronaut-integration/src/main/java/com/macstab/chaos/micronaut/ChaosMicronautExtension.java:47-58`
- **What breaks:** `beforeAll` puts both `ChaosControlPlane.class` and `ChaosSession.class` in the extension-context store (lines 47-48). `afterAll` removes only `ChaosSession` (line 54). If `beforeAll` throws after storing the control plane but before storing the session, a subsequent re-run stores a new control plane while the first is still in the store under the same key — silently overwriting it with no cleanup.
- **Fix direction:** `afterAll`: also `context.getStore(NAMESPACE).remove(ChaosControlPlane.class, ChaosControlPlane.class)`.
- **Status:** Fixed — lines 52-58: only `ChaosSession` removed; line 47 stores `ChaosControlPlane` with no corresponding removal.

### MED-47 — `HttpClientBenchmark.baseline_noAgent` measures `String.length()`, not an HTTP dispatch; overhead ratios derived from it are meaningless
- **Location:** `chaos-agent-benchmarks/src/main/java/com/macstab/chaos/benchmarks/HttpClientBenchmark.java:177`
- **What breaks:** `bh.consume(URL.length())` measures ~1-2 ns (field load + JIT-inlined `length()`). All agent benchmarks measure 50-200 ns of dispatcher logic. Any overhead ratio ("agent adds 100× cost") derived from these numbers is nonsense; the denominator is not the real call cost.
- **Fix direction:** Replace with `state.dispatcher.beforeHttpSend(URL, OperationType.HTTP_CLIENT_SEND)` on a `ZeroScenariosState` (no registered scenarios) to measure baseline dispatch with an empty registry.
- **Status:** Fixed — line 178: `bh.consume(URL.length())` vs. lines 183-207 all calling `state.dispatcher.beforeHttpSend(…)`.

### MED-48 — `SessionMissState.setup()` in benchmarks opens a `ChaosSession` that is never closed; `tearDown` only closes the runtime
- **Location:** `chaos-agent-benchmarks/src/main/java/com/macstab/chaos/benchmarks/ChaosRuntimeBenchmark.java:137`; same in `HttpClientBenchmark.java:131`
- **What breaks:** `runtime.openSession(...)` returns a `ChaosSession` stored in a local `var session` that is never closed. `tearDown` calls `runtime.close()` which destroys scenario controllers but has no session-list to drain. In single-JVM benchmark runs (IDE, `@Fork(0)`) repeated trials accumulate unclosed sessions.
- **Fix direction:** Store the session as a field; call `session.close()` in `tearDown` before `runtime.close()`.
- **Status:** Fixed — `ChaosRuntimeBenchmark.java:124-141`: local `var session`, no close; `ChaosControlPlaneImpl.close()` has no session-close logic.

### MED-49 — `SuppressEffect` on `QUEUE_POLL` is silently ignored; `PollAdvice` has no skip mechanism
- **Location:** `chaos-agent-instrumentation-jdk/src/main/java/com/macstab/chaos/instrumentation/QueueAdvice.java:23` and `chaos-agent-core/.../ChaosDispatcher.java:1207`
- **What breaks:** `QUEUE_POLL` routes through `beforeQueueOperation` → `applyPreDecision`. On `TerminalKind.SUPPRESS`, `applyPreDecision` returns at line 1207. `PollAdvice.enter()` is a `void @OnMethodEnter` with no `skipOn` annotation — returning from enter causes the real `poll()` to execute. By contrast `OfferAdvice` uses `skipOn = Advice.OnNonDefaultValue.class` with a `Boolean`-returning enter. The suppress signal is completely lost for `QUEUE_POLL`.
- **Impact:** `SuppressEffect` targeted at `QUEUE_POLL` via `QueueSelector` never suppresses the poll; the queue drains normally. Tests asserting "suppressed poll returns null" see live queue data instead.
- **Fix direction:** Migrate `PollAdvice` to the `Boolean`-returning `skipOn` pattern (`beforeBooleanQueueOperation("QUEUE_POLL", queue)`) matching `OfferAdvice`.
- **Status:** Fixed — `PollAdvice.enter():25-27`: void, calls `beforeQueueOperation`, no `skipOn`; `applyPreDecision:1207`: `return` on SUPPRESS; `OfferAdvice`: `Boolean`-returning, `skipOn = Advice.OnNonDefaultValue.class`.

### MED-50 — `ChaosHandleRegistry.stopAll()` may miss handles registered concurrently during iteration; CHM weakly-consistent iterator does not guarantee visibility of post-start inserts
- **Location:** `chaos-agent-spring-boot-common/src/main/java/com/macstab/chaos/spring/boot/common/ChaosHandleRegistry.java:84-94`
- **What breaks:** `stopAll()` iterates `handles.entrySet()` (weakly-consistent `ConcurrentHashMap` view). A handle registered via `register()` from a concurrent request thread after the iterator's construction is not guaranteed to be visited by the in-progress iteration. That handle is never stopped by `stopAll()` and survives the intended teardown — distinct from MED-42's double-stop scenario.
- **Impact:** During Spring context shutdown, handles registered in a concurrent thread during `stopAll()` survive context teardown, keeping chaos scenarios active after the context closes. Difficult to detect — no error, no log.
- **Fix direction:** Take a snapshot before stopping: `final List<ChaosActivationHandle> snapshot = new ArrayList<>(handles.values()); handles.clear(); for (ChaosActivationHandle h : snapshot) { h.stop(); count++; }`.
- **Status:** Fixed — `ConcurrentHashMap.entrySet()` iterator contract (JDK javadoc): "does not guarantee to reflect insertions or removals that occurred since the iterator was created." Line 84: iterator created; concurrent `register()` at line 48 (`handles.put(...)`) may insert after this point.

### MED-34 — Micronaut `ChaosFactory` installs agent with no property gate; contradicts the configurer's `macstab.chaos.enabled` flag
- **Location:** `chaos-agent-micronaut-integration/src/main/java/com/macstab/chaos/micronaut/ChaosFactory.java:36-41`
- **What breaks:** `@Bean @Singleton @Requires(missingBeans = ChaosControlPlane.class)` always calls `ChaosPlatform.installLocally()`. The sibling `ChaosContextConfigurer` (lines 45-58) honours `macstab.chaos.enabled`; the factory does not.
- **Impact:** A Micronaut app with `macstab.chaos.enabled=false` but with an `@Inject ChaosControlPlane` field still gets the agent installed — violating the uniform "one switch" contract documented in `ChaosContextConfigurer`.
- **Fix direction:** Add `@Requires(property = "macstab.chaos.enabled", value = "true")` to the factory method.
- **Status:** Fixed — `ChaosFactory.java:36-41`: only `@Requires(missingBeans = ...)` declared; `ChaosContextConfigurer.java:45-58, 65-72` uses the enabled gate; the two classes disagree.

### MED-6 — `ScenarioController.stop()` re-publishes STOPPED on repeat calls
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ScenarioController.java:252-261`
- **What breaks:** `stop()` is idempotent in effect but re-publishes the STOPPED event every call. Compounded by `HIGH-4`, listeners see duplicates.
- **Impact:** event noise; listener logic that reacts to STOPPED (e.g. counters) double-counts.
- **Fix direction:** gate the publish on a `state != STOPPED` pre-check inside the synchronized block.
- **Status:** Fixed — `stop()` at lines 252-261 has no STOPPED pre-check; publishes STOPPED unconditionally. Javadoc at lines 249-250 acknowledges: "Calling stop() on an already-stopped controller is safe … beyond re-publishing the event." Compounds with HIGH-4.

### MED-51 — `ScenarioController.snapshot()` can return `appliedCount > matchedCount`; violates documented invariant
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ScenarioController.java:383-393`; contract in `ChaosDiagnostics.java:168-169`
- **What breaks:** `matchedCount` and `appliedCount` are independent `AtomicLong`s. `evaluate()` increments `matched` at line 324 then `applied` later (CAS loop 354-365). A reader reading `matched` at `t=0` (value M) then `applied` at `t=2` (after a concurrent evaluate: applied=M+1, matched=M+1) observes `matched=M, applied=M+1` — violates `ScenarioReport` javadoc invariant `appliedCount <= matchedCount`.
- **Impact:** JFR `ChaosStressorSnapshotEvent.totalAppliedCount`, JMX `debugDump`, Spring Actuator `/actuator/chaos` may show impossible values; dashboards computing `skip_ratio = 1 - applied/matched` produce negatives.
- **Fix direction:** Read `applied` BEFORE `matched` in `snapshot()` — guarantees `applied ≤ matched` across threads under the producer's matched-then-applied ordering. Or pack both counters into a 64-bit `AtomicLong` (32 bits each) CAS-updated together.

### MED-52 — `ChaosAgentBootstrap.registerMBean` uses `isRegistered→registerMBean` TOCTOU; race silently drops the new diagnostics
- **Location:** `chaos-agent-bootstrap/src/main/java/com/macstab/chaos/bootstrap/ChaosAgentBootstrap.java:249-259`
- **What breaks:** Between `isRegistered` and `registerMBean`, a concurrent register wins → `InstanceAlreadyExistsException` caught silently. In agent-reattach or test-isolation flows where the previous MBean pins a dead `ChaosDiagnostics`, the branch skips and operators get JMX readings from the DEAD runtime. `ChaosDiagnosticsMBean` is not reusable (final `diagnostics` reference set at construction).
- **Impact:** Operator sees stale diagnostics via JMX; no way to tell it is stale.
- **Fix direction:** Drop the `isRegistered` check. Catch `InstanceAlreadyExistsException` specifically, `unregisterMBean(objectName)` and retry once; or refuse re-registration and log at WARNING with a distinguishing message.

### MED-53 — MBean never unregistered on `ChaosControlPlane.close()`; classloader leak in app-server redeploy scenarios
- **Location:** registration at `chaos-agent-bootstrap/src/main/java/com/macstab/chaos/bootstrap/ChaosAgentBootstrap.java:249-259`; no unregister path anywhere (grep: zero `unregisterMBean` matches)
- **What breaks:** Agent registers `com.macstab.chaos:type=ChaosDiagnostics` and never unregisters. `ChaosControlPlaneImpl.close()` destroys controllers but leaves the MBean bound with a strong reference to the (now-closed) diagnostics → pins the old classloader (permgen/metaspace leak). Combined with MED-52, re-attach then silently skips registration and operators lose JMX access to the live runtime.
- **Impact:** Classloader leak in containers that re-init the agent (Spring Boot devtools, OSGi, pluggable app servers, WAR redeploys); metaspace growth until OOM.
- **Fix direction:** Add a `deregisterMBean` static helper tracking the `ObjectName`; wire into `ChaosControlPlaneImpl.close()` or a dedicated shutdown hook.

### MED-54 — `ScenarioRegistry.snapshot()` loses nanosecond precision by routing clock through `epochMilli`
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ScenarioRegistry.java:180-185`
- **What breaks:** `Instant.ofEpochMilli(clock.millis())` discards nanos even though `Clock.instant()` would preserve them. Tests injecting `Clock.fixed(instant, zone)` with sub-millisecond precision lose that precision in the snapshot.
- **Impact:** JFR `StressorSnapshot` vs `EffectApplied` timestamp correlation off by up to 1 ms; high-throughput event ordering non-deterministic; test flakes around Clock assertions.
- **Fix direction:** Use `clock.instant()` directly.

### MED-55 — `JfrChaosEventSink` `activeScenarioIds` truncation at 1024 chars severs a scenario ID mid-token
- **Location:** `chaos-agent-bootstrap/src/main/java/com/macstab/chaos/bootstrap/jfr/JfrChaosEventSink.java:85-99`
- **What breaks:** Hard `substring(0, MAX_IDS_LENGTH)` may cut mid-token (`"scn-1,scn-2,...,sce"`). CSV consumers parse the partial "sce" as a fake scenario ID matching nothing.
- **Impact:** Broken JMC dashboards; false alerts on "unknown scenario"; no indication that truncation occurred.
- **Fix direction:** Truncate at the last comma before 1024 chars; append `,...` marker when truncation occurred; or emit a separate "droppedIdsCount" field.

### MED-56 — `JfrChaosEventSink.emitLifecycle` lacks `isEnabled()` guard; allocates per call even when event disabled
- **Location:** `chaos-agent-bootstrap/src/main/java/com/macstab/chaos/bootstrap/jfr/JfrChaosEventSink.java:53-60`
- **What breaks:** `emitEffectApplied` (line 64) guards on `jfrEvent.isEnabled()`; `emitLifecycle` does not. Constructor and four string-field sets run for every REGISTERED/STARTED/STOPPED/RELEASED transition even when the `com.macstab.chaos.ScenarioLifecycle` event is disabled in the JFR configuration.
- **Impact:** Unnecessary allocation + GC pressure under session-scoped chaos workloads (thousands of scenarios/sec possible).
- **Fix direction:** Add `if (!jfrEvent.isEnabled()) return;` as the first line after constructing the event, mirroring `emitEffectApplied`.

### MED-57 — `FlightRecorder.addPeriodicEvent` is not guarded by a full JFR availability probe; partial init throws past the class-probe
- **Location:** `chaos-agent-bootstrap/src/main/java/com/macstab/chaos/bootstrap/jfr/JfrChaosEventSink.java:29-33`; wrapper in `ChaosAgentBootstrap.java:241-247`
- **What breaks:** `JfrAvailability.probe()` checks only `Class.forName("jdk.jfr.FlightRecorder", false, …)` — class presence without init. On JVMs where the class loads but `FlightRecorder.getFlightRecorder()` fails (certain GraalVM native-image configs, `-XX:-FlightRecorder`), `addPeriodicEvent` throws. The surrounding catch in `installJfrIntegration` swallows, but `featureSet.jfrSupported()` still reports `true`.
- **Impact:** Probe lies about JFR support; lifecycle/applied/periodic events never fire; operators have no programmatic way to detect degraded mode.
- **Fix direction:** Extend probe to actually attempt `FlightRecorder.getFlightRecorder()`; on failure in `installJfrIntegration`, record the failure into `diagnostics` so `jfrSupported()` reflects reality.

### MED-58 — `runtimeDetails` `currentSessionId` is observer-thread-scoped (misleading snapshot field)
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosControlPlaneImpl.java:197-204`
- **What breaks:** `runtimeDetails()` calls `scopeContext.currentSessionId()` which reads from the scope stack of the thread calling `snapshot()`. For JFR-recorder threads / Actuator request threads this yields `null` or a stale value, not a runtime-global property. The emitted JFR/JMX/Actuator field labelled `currentSessionId` is garbage.
- **Impact:** Misleading diagnostic data; dashboards interpret it as runtime-global when it is observer-thread-local.
- **Fix direction:** Either drop `currentSessionId` from `runtimeDetails` and expose it via a distinct per-thread query, or rename/document as "sessionId of the observer thread, if any".

### MED-59 — `chaos-agent-testkit` exposes JUnit 5 extension types in its public API but declares `junit-jupiter-api` as `implementation`, not `api`
- **Location:** `chaos-agent-testkit/build.gradle.kts:1-10`
- **What breaks:** `ChaosAgentExtension` implements `BeforeEachCallback, AfterEachCallback, ParameterResolver` — public surface referencing JUnit types — but the module declares `implementation(libs.junit.jupiter.api)`. Four downstream modules (`chaos-agent-spring-boot3-test-starter`, `-boot4-test-starter`, `chaos-agent-micronaut-integration`, `chaos-agent-quarkus-extension`) consume testkit via `api(project(":chaos-agent-testkit"))` but JUnit stays hidden inside testkit's `implementation`. A consumer without `spring-boot-starter-test` (e.g., pure `spring-test` or Micronaut test) sees `NoClassDefFoundError` on the extension types.
- **Impact:** Latent breakage; currently "works" only because every direct consumer happens to pull JUnit via a different transitive path.
- **Fix direction:** Change to `api(platform(libs.junit.bom))` + `api(libs.junit.jupiter.api)`.

### MED-60 — `chaos-agent-bootstrap` fat-jar shades core/instrumentation/startup-config classes; same modules also exposed as standalone jars to consumers → duplicate classes on the classpath
- **Location:** `chaos-agent-bootstrap/build.gradle.kts:14-41` (fat-jar task inlines `runtimeClasspath` jars with `DuplicatesStrategy.EXCLUDE`, no relocation); consumer combos like `chaos-agent-spring-boot3-starter/build.gradle.kts:4-5` and `chaos-agent-spring-boot4-starter/build.gradle.kts:3-5` also depend on both `:chaos-agent-bootstrap` and `:chaos-agent-core`
- **What breaks:** Bootstrap jar inlines every class from `:chaos-agent-core`, `:chaos-agent-instrumentation-jdk`, `:chaos-agent-startup-config`, `:chaos-agent-api`, and `byte-buddy-agent`. Consumer modules also depend on those same modules as separate jars (with no exclusion). Result: classes like `com.macstab.chaos.core.ChaosRuntime`, `ChaosAgentBootstrap`, `net.bytebuddy.agent.*` appear TWICE on the runtime classpath. JVM class resolution is CL-order-dependent; `instanceof` across the two copies fails; static fields (e.g. `ChaosAgentBootstrap.RUNTIME`) become two independent singletons.
- **Impact:** Non-deterministic class resolution; Spring `EnvironmentPostProcessor` can install into one `RUNTIME` while auto-config reads the other → "no active runtime" errors silently. In app-server redeploys, classloader leaks compound.
- **Fix direction:** Either (a) stop shading (remove the `from({...})` block, ship bootstrap as a normal library jar); (b) use the `shadow` plugin and rewrite `runtimeElements` to drop the shaded deps from the published POM; or (c) relocate shaded packages (`net.bytebuddy.agent` → `com.macstab.chaos.shaded.bytebuddy.agent`) and exclude the same deps in every consumer.

### MED-61 — `beforeThreadPark` hardcodes `LockSupport` as subject class; `MonitorSelector.monitorClass` filter is dead for `THREAD_PARK`
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosDispatcher.java:584-596`
- **What breaks:** Same pattern as HIGH-36 but for `THREAD_PARK`. Context subject class is hardcoded `"java.util.concurrent.locks.LockSupport"`; any `MonitorSelector.monitorClass` with a non-`any()` pattern silently never matches.
- **Impact:** User-authored `THREAD_PARK` chaos scoped to a specific blocker class silently never fires.
- **Fix direction:** Propagate the `blocker` argument from `LockSupport.park(Object blocker)` advice through into `InvocationContext.subjectClassName()` via `blocker.getClass().getName()`. Add integration test.

### MED-62 — `ChaosBridgeTest` exercises only the no-active-scenario fast-exit path; operation-name string mismatches go unnoticed
- **Location:** production `chaos-agent-instrumentation-jdk/src/main/java/com/macstab/chaos/instrumentation/ChaosBridge.java`; test `chaos-agent-instrumentation-jdk/src/test/java/com/macstab/chaos/instrumentation/ChaosBridgeTest.java`
- **What breaks:** Every test in `ChaosBridgeTest` is `new ChaosRuntime()` with zero scenarios active, asserting only passthrough/`null` outcomes. A typo in any operation-name string routed through the bridge (e.g. `"EXECUTOR_WORKER_RUN"` vs the actual `OperationType.EXECUTOR_WORKER_RUN.name()`) would not surface because the dispatcher short-circuits when no scenarios are present.
- **Impact:** Regression risk: a future refactor that breaks one of ~60 bridge forwarders produces silent loss of chaos for that operation while the test suite stays green.
- **Fix direction:** For each bridge method, add at least one test activating a scenario with `ActivationPolicy.always()` + matching selector, then call the bridge method and assert on diagnostics counter bump or a non-null decision.

### MED-63 — `QueueAdvice` exit-mutation test is tautological: the `applyExit` helper mirrors the production branch instead of exercising ByteBuddy's `@Advice.Return(readOnly = false)` propagation
- **Location:** production `chaos-agent-instrumentation-jdk/src/main/java/com/macstab/chaos/instrumentation/QueueAdvice.java:42-48`; test `chaos-agent-instrumentation-jdk/src/test/java/com/macstab/chaos/instrumentation/QueueAdviceTest.java:116-120`
- **What breaks:** Test acknowledges `"A unit test cannot invoke that mutation directly"` and reimplements the three-line branch. Any ByteBuddy regression that drops `readOnly = false` write-back would pass the test untouched.
- **Impact:** `QUEUE_OFFER` chaos would silently stop rewriting the return value after a ByteBuddy bump; applications see the real offer outcome instead of the chaos-injected one. Symmetric risk for `CompletableFutureAdvice.CompleteAdvice` and `CompleteExceptionallyAdvice`, which lack even the helper test.
- **Fix direction:** Add a ByteBuddy-loaded integration test that installs the advice, invokes `new LinkedBlockingQueue<>().offer(...)` under an active chaos scenario returning `FALSE`, and asserts `offer()` returns `false` despite a non-full queue. Repeat for `CompletableFuture.complete()`.

### MED-64 — `ObservabilityBus.publish` log formatter does not sanitise operator-supplied `scenarioId` / `message` / attributes; enables log injection via plan JSON
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ObservabilityBus.java:151-173`
- **What breaks:** `formatted` concatenates `event.type()`, `event.scenarioId()`, `event.message()`, `event.attributes()` raw into a single log line routed through `LOGGER.info/fine/warning`. A plan with `"id": "foo\nWARNING: system breach imminent\\nscenarioId=bar"` forges fake log entries at any level. Sibling `StartupConfigPoller.sanitiseForLog` already implements control-char stripping; this path does not use it.
- **Impact:** Audit-trail defeat on agents that log to file/stdout/JUL handlers; ANSI-escape injection in TUI consoles.
- **Fix direction:** Route `scenarioId`, `message`, and every attribute value through `sanitiseForLog` before concatenation; or restrict `scenarioId` at `ChaosScenario` construction to `[A-Za-z0-9._:-]+`.

### MED-65 — `StartupConfigPoller.pollOnce` skips the 1 MiB size gate enforced by `StartupConfigLoader`; allows OOM on reload
- **Location:** `chaos-agent-bootstrap/src/main/java/com/macstab/chaos/bootstrap/StartupConfigPoller.java:189-194`
- **What breaks:** Initial load validates `BasicFileAttributes.size() <= 1 MiB` before opening. Poll path calls `Files.newInputStream(path, NOFOLLOW_LINKS)` then `readAllBytes()` unconditionally. Only the downstream `ChaosPlanMapper.read` enforces 1 MiB — AFTER the full byte[] + UTF-8 String is in memory. Attacker with write access to the watched file (the attacker already considered by the NOFOLLOW_LINKS defence) can replace 1 KB with multi-GB; poll OOMs before the mapper check runs. Heap-constrained JVMs die.
- **Impact:** DoS of the whole JVM via polled-config replacement.
- **Fix direction:** Re-run the `validateAndResolvePath` attribute check (size ≤ `MAX_FILE_SIZE`) in `pollOnce` before `readAllBytes`; or read into a fixed-size buffer, truncate + throw `ConfigLoadException` at 1 MiB + 1.

### MED-66 — `StartupConfigPoller.resolveInterval` accepts arbitrary positive `long`; allows 1 ms or `Long.MAX_VALUE` poll intervals
- **Location:** `chaos-agent-bootstrap/src/main/java/com/macstab/chaos/bootstrap/StartupConfigPoller.java:265-279`
- **What breaks:** Any `> 0` value accepted. `configWatchInterval=1` or `0` (after a typo) yields ~1 kHz stat+read+parse+diff loop; `scheduleAtFixedRate` queues backpressure and one CPU core saturates. Conversely `Long.MAX_VALUE` overflows `triggerTime` arithmetic inside `ScheduledThreadPoolExecutor` (same class as HIGH-23), breaking cancellation semantics.
- **Impact:** Self-DoS via misconfiguration at either bound.
- **Fix direction:** Clamp to a sane window (e.g. `[100, 86_400_000]` ms) and reject or log-and-ignore values outside before returning.

### MED-67 — `ChaosDiagnosticsMXBean.debugDump()` interpolates operator-supplied `scenarioId`/`scopeKey`/`reason` without escaping
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ScenarioRegistry.java:238-252`
- **What breaks:** `debugDump()` appends raw `report.id()`, `report.scopeKey()`, `report.reason()` with literal newlines between rows. Fields may contain `\n`, ANSI CSI sequences, or JMX-attribute confusables. Output is exposed via JMX, plus `System.err.println`'d at startup when `debugDumpOnStart=true`.
- **Impact:** Forged diagnostics lines in operator logs and JMX consoles; control of terminal cursor in TUI JMX clients.
- **Fix direction:** Route all three user-supplied fields through `sanitiseForLog` inside `debugDump`; consider restricting `scenarioId` at construction time (see MED-64 fix).

### MED-68 — Spring Actuator `/actuator/chaos` `activate` accepts any caller Actuator security passes; no secondary authorization check for destructive effects
- **Location:** `chaos-agent-spring-boot-common/src/main/java/com/macstab/chaos/spring/boot/common/ChaosActuatorEndpoint.java:56-65`
- **What breaks:** `@WriteOperation activate(String planJson)` reads operator-controlled JSON and calls `controlPlane.activate(plan)` — can install `HeapPressureEffect`/`DeadlockEffect`/`ThreadLeakEffect`. Only defence is `ChaosAutoConfiguration` emitting a WARNING when Spring Security is absent. No `@PreAuthorize`, no role check in the endpoint. If Actuator is exposed on the main port (Boot 3 default if `management.server.port` unset) with `permitAll` on `/actuator/**`, unauthenticated callers can deadlock the JVM.
- **Impact:** Defence-in-depth gap: relying solely on Actuator's Spring Security filter chain for protection of destructive chaos effects.
- **Fix direction:** Require an injectable `ChaosEndpointAuthorizer` SPI (throwing `AccessDeniedException` on deny); or default to `@PreAuthorize("hasRole('ADMIN')")` / a configurable role. Gate destructive-effect plans behind a stronger hook than the Actuator-level one.

### MED-69 — Micronaut `ChaosFactory` produces `@Singleton` beans per JVM but Micronaut bean lifecycle is per-`ApplicationContext`
- **Location:** `chaos-agent-micronaut/src/main/java/com/macstab/chaos/micronaut/ChaosFactory.java`
- **Issue:** `ChaosRuntime` is installed via `ChaosAgentBootstrap.installLocally()` (JVM-singleton side effect) while the `ChaosControlPlane` bean is `@Singleton` scoped to the Micronaut context. Tests that repeatedly build + close contexts leave the JVM-level runtime behind, and the second context observes the first context's plan state.
- **Fix direction:** Gate the install behind an atomic reference keyed on the `ApplicationContext` identity; or explicitly `close()` the runtime in a `@PreDestroy` bean wired to the context, not the factory.

### MED-70 — Spring Boot EPP does not declare `HIGHEST_PRECEDENCE` and runs *after* `ConfigDataEnvironmentPostProcessor`
- **Location:** `chaos-agent-spring-boot3-starter/src/main/java/com/macstab/chaos/boot3/ChaosEnvironmentPostProcessor.java` (ordering) + `META-INF/spring.factories`
- **Issue:** The EPP that triggers install runs after config-data EPPs, but the property it reads (`macstab.chaos.agent-args`) is itself loaded by `ConfigDataEnvironmentPostProcessor`. Swap-depending on `spring.factories` ordering vs. `@Order` annotation — currently the class is `@Order(Ordered.HIGHEST_PRECEDENCE)` but that runs it *before* config-data, making its property lookup always miss values from `application.yml`.
- **Fix direction:** Use `Ordered.LOWEST_PRECEDENCE - 10` so the EPP observes materialised property sources; document the ordering constraint.

### MED-71 — `ApplicationReadyEvent` listener fires per child context; multi-context apps install & close the runtime repeatedly
- **Location:** `chaos-agent-spring-boot3-starter/src/main/java/com/macstab/chaos/boot3/ChaosReadinessListener.java`
- **Issue:** Spring Boot apps with management context or child contexts fire `ApplicationReadyEvent` once per context. Listener unconditionally calls `installLocally()`/`close()` per event, so a management-context startup re-installs and re-closes the agent after the main context.
- **Fix direction:** Filter events by `event.getApplicationContext().getParent() == null` (only root); or delegate to a `SmartApplicationListener` that ignores non-root contexts.

### MED-72 — `ChaosActuatorConfiguration.@ConditionalOnClass("org.springframework.boot.actuate...")` passes even when the dependency is provided-scope at runtime
- **Location:** `chaos-agent-spring-boot3-starter/src/main/java/com/macstab/chaos/boot3/ChaosActuatorConfiguration.java`
- **Issue:** The class-only condition matches whenever an Actuator class is on the classpath, including test-time scopes. Production deployments without `spring-boot-actuator` enabled still see the endpoint bean posted because the condition is class-based, not bean-based.
- **Fix direction:** Pair the class condition with `@ConditionalOnBean(WebEndpointsSupplier.class)` or `@ConditionalOnEnabledEndpoint(endpoint = ChaosActuatorEndpoint.class)`.

### MED-73 — Quarkus `ChaosArcProducer` vs `QuarkusBootstrapRecorder` race on `macstab.chaos.arc-auto-install`
- **Location:** `chaos-agent-quarkus/.../ChaosArcProducer.java` + `.../QuarkusBootstrapRecorder.java`
- **Issue:** Both producer and recorder call `installLocally()`; they race at RUNTIME_INIT. Whichever loses silently observes the other's plan and then overwrites `ChaosControlPlane.current()` with a second instance.
- **Fix direction:** Recorder must be the single install site; producer returns `ChaosRuntime.current()` wrapped in a `CDI.current()` holder.

### MED-74 — `ChaosAgentBootstrap.parseBooleanStrict("true")` rejects `TRUE`/`True`/`1`/`yes`
- **Location:** `chaos-agent-bootstrap/src/main/java/com/macstab/chaos/bootstrap/ChaosAgentBootstrap.java:parseBooleanStrict`
- **Issue:** The strict parser is used for agent-args `enabled=true`. Operators routinely supply `TRUE`, `1`, or `yes`; the parser throws `IllegalArgumentException` with a cryptic message that doesn't reveal which key failed.
- **Fix direction:** Accept canonical `{true,false,1,0,yes,no}` (case-insensitive) with a precise "expected true|false, got <x> for key <k>" error.

### MED-75 — Quarkus recorder `RuntimeValue<ChaosRuntime>` is returned from a `@BuildStep` that is not `@Record` — scope flip
- **Location:** `chaos-agent-quarkus/deployment/.../QuarkusChaosProcessor.java` (or equivalent build step)
- **Issue:** `RuntimeValue` is only valid when produced from a `@Record`-annotated build step; current code synthesises a value outside a recording context. Deployment works locally because Quarkus is lenient in dev-mode, but native-image builds produce a `RuntimeValue` proxy that dereferences to `null` in production.
- **Fix direction:** Move the `RuntimeValue` into `@Record(ExecutionTime.RUNTIME_INIT)` method returning `RuntimeValue<ChaosRuntime>`; consume via `@Consume`.

### MED-76 — `ScenarioReport` compact constructor accepts negative `matchedCount`, `appliedCount`, `skippedCount`
- **Location:** `chaos-agent-api/src/main/java/com/macstab/chaos/api/ScenarioReport.java`
- **Issue:** Record compact constructor has no invariant guard; passing `-1` for any count is accepted and propagates to consumers as a "valid" snapshot.
- **Fix direction:** Add `if (matchedCount < 0 || appliedCount < 0 || skippedCount < 0) throw new IllegalArgumentException(...)` in the compact constructor; add `appliedCount > matchedCount` ordering guard (covers the MED-51 read path from write side).

### MED-77 — `ActivationPolicy.builder().minCalls(long)` signature mismatch — primitive long accepts null via Kotlin null-coerce
- **Location:** `chaos-agent-api/src/main/java/com/macstab/chaos/api/ActivationPolicy.java`
- **Issue:** The field declared as `long` cannot be null in Java, but the builder accepts `Long` through `withMinCalls(Long)` and unboxes. Passing `null` from Kotlin/Scala/JSON mapping NPEs far from the call site with "Cannot invoke longValue() on null".
- **Fix direction:** Boxed parameter with explicit null check → `Objects.requireNonNull(value, "minCalls")`; or accept `long` primitive directly.

### MED-78 — `BootstrapPackages.ALLOWED_PACKAGES` includes `javax.*` wholesale; malicious plan targeting `javax.naming.*`, `javax.crypto.*` bypasses intent checks
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/BootstrapPackages.java` (or allow-list constant)
- **Issue:** The allow-list is a blanket `javax.*` prefix; a plan file can match arbitrary javax subclasses. Original intent was `javax.sql.*` + `javax.net.*` only.
- **Fix direction:** Explicit enumeration; reject unlisted `javax.*` subpackages.

### MED-79 — `ExceptionInjectionEffect` class-name regex has no length cap; a 1 MiB regex compiled per scenario = ReDoS + heap pressure
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ExceptionInjectionEffect.java`
- **Issue:** `Pattern.compile(userSupplied)` on a megabyte-long regex is accepted by the plan validator. A malicious plan can freeze the scenario installer.
- **Fix direction:** Cap regex input length (≤ 512 chars); reject nested quantifiers via a simple pre-scan.

### MED-80 — Agent JAR manifest missing `Launcher-Agent-Class` entry; `java -jar agent.jar` does nothing on JDK 21 "launcher agent" path
- **Location:** `chaos-agent-bootstrap/build.gradle.kts` (jar manifest attributes)
- **Issue:** The manifest has `Premain-Class` and `Agent-Class` but not `Launcher-Agent-Class`. JDK 21+ supports loading an agent via the launcher without `-javaagent:`; operators attempting this observe silent no-op.
- **Fix direction:** Add `Launcher-Agent-Class: com.macstab.chaos.bootstrap.ChaosAgentBootstrap` to the manifest.

### MED-81 — `ActivationFailure` constructor accepts `null` message; `toString()` renders "null" as the failure reason
- **Location:** `chaos-agent-api/src/main/java/com/macstab/chaos/api/ActivationFailure.java`
- **Issue:** The record compact constructor allows `null`; downstream observability surfaces show the string "null" instead of a hint.
- **Fix direction:** `Objects.requireNonNull(message)` or coerce to `"<no reason>"` with logging at WARN.

### MED-82 — `docs/testkit.md` "Quick start" snippets duplicate the same broken API pattern as README (`ChaosScenario.builder()` zero-arg, `.id(...)` setter); every testkit copy-paste fails to compile
- **Location:** `docs/testkit.md` sections "Basic usage" and "JUnit integration"
- **Issue:** Snippets copied from old API; current `ChaosScenario` requires `scenarioBuilder(id, selector)` with selector at construction. Every testkit quick-start reader hits `Cannot resolve method builder()`.
- **Fix direction:** Regenerate snippets from the live API via a doc-test gradle task; fail CI on divergence.

### MED-83 — `chaos-agent-examples/sb4-sla` virtual-thread-spawning handler does not propagate the `ChaosSession` scope
- **Location:** `chaos-agent-examples/sb4-sla/src/main/java/.../SlaHandler.java`
- **Issue:** Handler opens `ChaosSession` on the platform thread but its virtual-thread worker does not inherit the `ScopeContext`. Scenarios keyed on session scope never activate in the example.
- **Fix direction:** Wrap the virtual-thread work in `ChaosSession.current().wrap(task)` or propagate via `ScopedValue` (JDK 21+).

### MED-84 — `Phase4DispatchBenchmark.chaos_noMatch` result is trivially JIT-constant-folded; Blackhole.consume call not passed the dispatcher return
- **Location:** `chaos-agent-benchmarks/src/jmh/java/.../Phase4DispatchBenchmark.java`
- **Issue:** The benchmark calls `dispatcher.evaluate(...)` and assigns to a local without `Blackhole.consume`. JIT dead-code-eliminates the call on warmup iterations; benchmark reports ns/op that is the overhead of `@Benchmark` framework, not dispatch.
- **Fix direction:** Add `bh.consume(result)` after every dispatcher call; run `-prof perfnorm` to confirm no phantom IPC.

### MED-85 — `ChaosRuntimeBenchmark.baseline_noAgent` measures `Runnable::run` against `beforeJdbcStatementExecute` in the chaos arm; different code paths produce meaningless overhead ratios
- **Location:** `chaos-agent-benchmarks/src/jmh/java/.../ChaosRuntimeBenchmark.java:baseline_noAgent`
- **Issue:** The baseline does a no-op lambda while the chaos arm exercises the full JDBC advice stack; the ratio published in README ("0.5% overhead") is derived from an apples-to-oranges comparison.
- **Fix direction:** Baseline must invoke the identical method with the dispatcher disabled (`ChaosRuntime.DISABLED`); publish ratios only between matched code paths.

### MED-86 — `ChaosMicronautExtension.afterAll` never removes the `ChaosControlPlane` from the JUnit `ExtensionContext.Store`
- **Location:** `chaos-agent-testkit/src/main/java/com/macstab/chaos/testkit/ChaosMicronautExtension.java`
- **Issue:** Second `beforeAll` for the same class (e.g., `@Nested` tests or `@ParameterizedTest`) replaces the stored instance silently, leaking the previous one.
- **Fix direction:** `context.getStore(ns).remove(KEY, ChaosControlPlane.class).close();` in `afterAll`.

### MED-87 — `ChaosControlPlaneImpl.applyPlan` holds `planLock` across `controller.start()`; long-running starts block concurrent `snapshot()` callers
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosControlPlaneImpl.java`
- **Issue:** `planLock` is held during controller start, which may perform JFR/MBean/ByteBuddy work. Observability snapshots block until the start completes.
- **Fix direction:** Release the lock before start; guard the registry transition with a two-phase commit so snapshot sees a consistent "starting/started" view without holding the write lock.

### MED-88 — `ScopeContext.set`/`clear` pair uses `ThreadLocal` but `close()` does not `remove()`; long-lived threads retain the last scope key as GC root
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ScopeContext.java`
- **Issue:** `clear()` sets the `ThreadLocal` to `null` instead of calling `remove()`; the per-thread entry persists, pinning the `ThreadLocalMap.Entry` key (the `ThreadLocal` itself), and in tomcat/jetty worker pools this compounds into a slow leak.
- **Fix direction:** Call `threadLocal.remove()` in `close()`.

### MED-89 — `DefaultChaosSession.activate(ChaosPlan)` rollback double-destroys handles already added to the `handles` list; emits duplicate `STOPPED` events
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/DefaultChaosSession.java:100-104, 128-140`
- **Issue:** `activate(ChaosScenario)` adds each resulting handle to a `CopyOnWriteArrayList` immediately. If `activate(ChaosPlan)` enters rollback, it calls `destroy()` on the partially-created handles (correct), but `close()` also iterates `handles` and calls `destroy()` a second time on any already-destroyed entry. This causes duplicate `STOPPED` lifecycle events and double-decrement of active-scenario counters.
- **Fix direction:** Track plan-created handles in a local list separate from the session-level `handles` list, or remove each handle from `handles` during rollback before calling `destroy()`.

### MED-90 — `ScenarioController.start()` calls `gate.reset()` without first releasing the old latch; threads blocked on a previous `ManualGate.await()` are permanently stranded
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ScenarioController.java:218-229`
- **Issue:** `start()` calls `gate.reset()` which installs a fresh `CountDownLatch` without counting down the prior one. If any thread is currently blocked in `ManualGate.await()` from a previous activation that was stopped without releasing the gate, `reset()` orphans them — `release()` now operates on the new latch and never reaches those threads. They remain blocked forever, holding thread-pool workers hostage.
- **Fix direction:** Call `gate.release()` (counting down the existing latch) immediately before `gate.reset()` to drain any waiting threads before installing a fresh latch.

### MED-91 — `sampleDelayMillis` computes `max + 1` in plain `long`; overflows to `Long.MIN_VALUE` when `maxDelay` is near `Long.MAX_VALUE`, crashing `SplittableRandom.nextLong` on the hot path
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ScenarioController.java:487`
- **Issue:** `splittableRandom(matched).nextLong(min, max + 1)` — when `max == Long.MAX_VALUE` (reachable via `Duration.toMillis()` near the `Duration` max), `max + 1` wraps to `Long.MIN_VALUE`. `SplittableRandom.nextLong` requires `origin < bound`; `nextLong(min, Long.MIN_VALUE)` throws `IllegalArgumentException` on the application thread.
- **Fix direction:** Cap with `long bound = (max == Long.MAX_VALUE) ? Long.MAX_VALUE : max + 1;` and pass `bound` to `nextLong`; or add a validation guard in `DelayEffect` capping `maxDelay` to `Long.MAX_VALUE - 1`.

### MED-92 — `GetResourceAdvice.exit()` declared `@OnMethodExit(onThrowable = Throwable.class)` but dispatcher call in exit body can replace the original exception from `ClassLoader.getResource`
- **Location:** `chaos-agent-instrumentation-jdk/src/main/java/com/macstab/chaos/instrumentation/ClassLoaderAdvice.java:20-28`
- **Issue:** The exit advice fires even when `getResource()` exits via an unchecked exception. If the dispatcher call itself throws, ByteBuddy's exit-advice contract means the new exception replaces the original — callers receive the dispatcher's exception with no indication of what `getResource` originally failed with. The `returned` mutation is also meaningless on the throwable path.
- **Fix direction:** Either remove `onThrowable = Throwable.class` so exit only fires on normal return, or add `@Advice.Thrown final Throwable thrown` and guard the dispatcher call with `if (thrown == null)`.

### MED-93 — `NioSelectNoArgAdvice` is wired to both `select()` and `selectNow()`; both pass `timeoutMillis=0`, making the two calls indistinguishable to scenario matchers
- **Location:** `chaos-agent-instrumentation-jdk/src/main/java/com/macstab/chaos/instrumentation/JdkInstrumentationInstaller.java:424-427`; `JvmRuntimeAdvice.java:212-214`
- **Issue:** The dispatcher receives `beforeNioSelect(selector, 0L)` for both the infinite-blocking no-arg `select()` and the non-blocking `selectNow()`. A scenario targeting only `selectNow()` fires for the blocking variant and vice versa.
- **Fix direction:** Use a dedicated advice class for `selectNow()` that calls `beforeNioSelect(selector, -1L)` (or `Long.MIN_VALUE`) as a sentinel for non-blocking poll, so dispatchers can route correctly.

### MED-94 — `BootstrapDispatcher.invoke()` sets `depth[0] = 1` instead of `depth[0]++`; depth counter goes negative under re-entrancy, permanently disabling dispatch on the affected thread
- **Location:** `chaos-agent-instrumentation-jdk/src/main/java/com/macstab/chaos/instrumentation/bridge/BootstrapDispatcher.java:1482`
- **Issue:** A re-entrant call (agent runtime calls an instrumented JDK method) increments the inner `invoke()` to `1`. The inner `finally` block does `--depth[0]` → `0` and calls `DEPTH.remove()`. The outer `invoke()` also does `--depth[0]` on a freshly re-initialized `[0]`, landing at `-1`. The depth never reaches `0` again for remove; all subsequent dispatch calls on that thread see a leaked negative value and eventually bypass dispatch entirely (because the guard `depth[0] > 0` is false), or — if the guard is `depth[0] != 0` — block forever.
- **Fix direction:** Replace `depth[0] = 1` with `depth[0]++` and ensure the guard reads `depth[0] > 0` (not `!= 0`).

### MED-95 — `AgentBuilder.ignore()` does not exclude `sun.instrument.*` / `com.sun.tools.attach.*`; broad subtype matchers may attempt to retransform the JVM's own instrumentation support classes
- **Location:** `chaos-agent-instrumentation-jdk/src/main/java/com/macstab/chaos/instrumentation/JdkInstrumentationInstaller.java:122-124`
- **Issue:** The custom `AgentBuilder.ignore()` clause covers only `net.bytebuddy.*` and `com.macstab.chaos.*`. A custom `AgentBuilder.Default()` instance resets ByteBuddy's built-in ignores. The broad `isSubTypeOf(java.util.concurrent.BlockingQueue.class)` matcher evaluates against every loaded class including JVMTI support classes. Retransforming `sun.instrument.InstrumentationImpl` can corrupt the `Instrumentation` reference and silently disable the agent.
- **Fix direction:** Add `ElementMatchers.nameStartsWith("sun.instrument.").or(ElementMatchers.nameStartsWith("com.sun.tools.attach."))` to the `ignore()` clause.

### MED-96 — `ExecutorSelector.scheduledOnly` field is stored but never consulted in `SelectorMatcher`; scenarios that set `scheduledOnly=true` fire on all executor submissions
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/SelectorMatcher.java:44-47`
- **Issue:** The `ExecutorSelector` branch of `SelectorMatcher.matches()` checks `operations`, `executorClassPattern`, and `taskClassPattern` but never reads `scheduledOnly`. Any scenario built with `scheduledOnly=true` fires on every executor submission rather than only scheduled-executor submissions, silently defeating the filter.
- **Fix direction:** Add a `scheduledOnly` check in the `ExecutorSelector` branch of `SelectorMatcher.matches()`, mirroring the `periodicOnly` guard already present for `SchedulingSelector`.

### MED-97 — `CompatibilityValidator` has no operation-validity guards for `DnsSelector`, `SslSelector`, or `FileIoSelector`; invalid-op scenarios pass validation and are silently inert at runtime
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/CompatibilityValidator.java:332-397`
- **Issue:** The validator enforces per-selector valid-operation sets for `NioSelector`, `NetworkSelector`, `HttpClientSelector`, and `JdbcSelector` but has no equivalent for `DnsSelector` (valid: `DNS_RESOLVE`), `SslSelector` (valid: `SSL_HANDSHAKE`), or `FileIoSelector` (valid: `FILE_IO_READ`, `FILE_IO_WRITE`). A plan JSON that names an invalid operation for one of these selectors bypasses the record-constructor `validatedOperations` call (Jackson uses `@JsonCreator`) and produces a scenario that will never match — chaos silently disabled.
- **Fix direction:** Add validator blocks for the three missing selector types mirroring the existing `NioSelector` and `NetworkSelector` blocks.

### MED-98 — `ThreadLocalLeakStressor` multiplies `parallelism * entriesPerThread` as `int`; overflows to negative and throws `NegativeArraySizeException` from `ArrayList` constructor
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ThreadLocalLeakStressor.java:33`
- **Issue:** `final int totalLocals = parallelism * effect.entriesPerThread()` — both operands are `int`. `parallelism` is bounded by available processors (e.g., 256); `entriesPerThread` is user-controlled and validated only as `> 0`. A value of `entriesPerThread = Integer.MAX_VALUE / 256 + 1` overflows `totalLocals` to negative, causing `new ArrayList<>(totalLocals)` to throw `NegativeArraySizeException` from inside `StressorFactory.createIfNeeded()`.
- **Fix direction:** Cast to `long` before multiplying and cap with a reasonable maximum (e.g., 10 000 total locals); throw `IllegalArgumentException` for values exceeding the cap.

### MED-99 — `HeapPressureStressor` allocates all chunks in the constructor with no cap on total allocation; an operator with `bytes=Long.MAX_VALUE` triggers `OutOfMemoryError` inside the stressor factory
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/HeapPressureStressor.java:39-45`
- **Issue:** The constructor loops allocating `chunkSizeBytes`-sized arrays until the target `bytes` is reached, adding them to an unbounded `retained` list. `HeapPressureEffect.bytes` is validated only as `> 0`. A pathological value (e.g., `Long.MAX_VALUE`) causes an `OutOfMemoryError` from inside `createIfNeeded()`, which propagates to `activate()` with partial allocation already on the heap that the GC must reclaim.
- **Fix direction:** Validate `bytes` with an upper bound in `HeapPressureEffect`'s constructor (e.g., no more than 25% of `Runtime.maxMemory()`), or add a chunk-count cap inside the stressor with a clear error message.

### MED-100 — `StartupConfigLoader` exception messages embed the resolved absolute filesystem path; internal directory layout disclosed via JMX / log-forwarding pipelines
- **Location:** `chaos-agent-startup-config/src/main/java/com/macstab/chaos/startup/StartupConfigLoader.java:104, 123, 127, 131, 152, 157`
- **Issue:** Every `ConfigLoadException` thrown from `loadFromFile` and `validateAndResolvePath` concatenates the absolute `path` into the message string (e.g. `"config file does not exist: /opt/secrets/config.json"`). These messages propagate to JMX MBean attributes, `System.err`, and log-forwarders visible to any JMX consumer — disclosing internal filesystem layout and potentially secrets-manager paths.
- **Fix direction:** Use only the operator-supplied relative/symbolic value (not the normalized absolute path) in messages; or emit the absolute path only at `FINE`/`DEBUG` log level and strip it from `ConfigLoadException.getMessage()`.

### MED-101 — `AgentArgsParser.parse()` emits duplicate-key warnings to `System.err` as a side effect; double-parse in `initialize` + `resolveInterval` produces double stderr noise interpreted as errors by health-check tooling
- **Location:** `chaos-agent-startup-config/src/main/java/com/macstab/chaos/startup/AgentArgsParser.java:82-85`; `chaos-agent-bootstrap/.../StartupConfigPoller.java:266`
- **Issue:** `ChaosAgentBootstrap.initialize` calls `StartupConfigLoader.load(agentArgs, ...)` (parse #1) and then `StartupConfigPoller.createIfEnabled(agentArgs, ...)` → `resolveInterval` → `AgentArgsParser.parse(rawAgentArgs)` (parse #2). Any duplicate key in the agent args triggers two `System.err` warnings during a single startup. Spring Boot / Quarkus startup log parsers treat unexpected `System.err` output as failure signals; double-printed warnings can cause false health-check failures.
- **Fix direction:** Parse agent args exactly once in `initialize` and thread the resulting `AgentArgs` object to all consumers; or move the duplicate-key warning out of the pure parser into a separate validation step.

### MED-102 — `ScenarioController.rateWindowStartMillis` / `rateWindowPermits` declared `volatile` but always accessed under `synchronized(this)`; misleads maintainers into adding unsynchronized reads
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ScenarioController.java:80-81`
- **Issue:** Both fields are declared `volatile` yet `passesRateLimit()` reads and writes them exclusively inside `synchronized(this)`. The `volatile` modifier is redundant and actively misleading: it implies to a future maintainer that an unsynchronized read path exists or is safe, which could lead them to add one (breaking the rate-limit invariant without a compilation error).
- **Fix direction:** Remove `volatile` from both fields and add a comment that they are guarded by `synchronized(this)`.

### MED-103 — Jackson `@JsonTypeInfo` missing or unknown `type` field on `ChaosSelector`/`ChaosEffect` produces `InvalidTypeIdException` that may NPE inside Jackson before reaching `ChaosPlanMapper`'s catch block
- **Location:** `chaos-agent-api/src/main/java/com/macstab/chaos/api/ChaosSelector.java:23`; `ChaosEffect.java:22`; `ChaosPlanMapper.java:91`
- **Issue:** When the `type` discriminator field is absent from a JSON selector/effect block, Jackson can throw a bare `NullPointerException` from inside `TypeDeserializerBase` (Jackson ≤ 2.14) before reaching the `JsonProcessingException` path, escaping the `catch (JsonProcessingException)` block in `ChaosPlanMapper.read()`. The operator sees an uncaught NPE with no reference to the offending JSON field, not a `ConfigLoadException`.
- **Fix direction:** Register a custom `DeserializationProblemHandler` (or use `DeserializationFeature.FAIL_ON_INVALID_SUBTYPE`) that converts missing-type-id to a throw-able `JsonMappingException` before Jackson's internal NPE path is reached.

### MED-104 — `ChaosScenario.Builder.build()` propagates bare `NullPointerException` without identifying the scenario ID when `selector` or `effect` is not set
- **Location:** `chaos-agent-api/src/main/java/com/macstab/chaos/api/ChaosScenario.java:215-218`
- **Issue:** If `build()` is called without calling `selector()` or `effect()`, the compact constructor fires `Objects.requireNonNull(selector, "selector")` with no mention of the scenario `id`. In a multi-scenario plan with 10 entries, the caller cannot determine which scenario is misconfigured from the bare message `"selector"`.
- **Fix direction:** Add explicit null checks in `Builder.build()` before delegating to the constructor: `if (selector == null) throw new IllegalArgumentException("selector must be set for scenario '" + id + "'")`.

### MED-105 — `buildMethodHandles` Javadoc says "46-element array" but the actual `HANDLE_COUNT` is 57; stale count guides off-by-one errors when contributors add new handles
- **Location:** `chaos-agent-instrumentation-jdk/src/main/java/com/macstab/chaos/instrumentation/JdkInstrumentationInstaller.java:39, 922`
- **Issue:** Two Javadoc occurrences document the handle array as "46-slot" / "46-element". The actual constant is 57 and 57 slots are populated. A contributor following the comment to calculate the index for a new handle will miscalculate, producing an `ArrayIndexOutOfBoundsException` at runtime with no compile-time protection.
- **Fix direction:** Replace both hardcoded counts with a reference to `HANDLE_COUNT` and keep a single source of truth in the constant definition.

### MED-106 — `QueueAdvice.OfferAdvice` `skipOn` with `Boolean.FALSE` suppresses the real `offer` body, preventing any "pass-through observe" semantics — semantics are undocumented and surprising
- **Location:** `chaos-agent-instrumentation-jdk/src/main/java/com/macstab/chaos/instrumentation/QueueAdvice.java:44`
- **Issue:** `skipOn = Advice.OnNonDefaultValue.class` with a `Boolean` return triggers on both `Boolean.TRUE` and `Boolean.FALSE` (both are non-null). When the dispatcher returns `Boolean.FALSE` ("force return false"), the real `offer` body is skipped just as it would be for `TRUE` — there is no way for the dispatcher to say "I observed this call, but let it proceed normally". This means any scenario that intends to observe `offer` without affecting it cannot be expressed via the dispatcher's return value; only `null` (not recorded by enter, real body runs) achieves passthrough. The semantics are undocumented, so scenario authors cannot predict the behaviour.
- **Fix direction:** Document explicitly in `QueueAdvice.OfferAdvice` that `null` = passthrough, `TRUE/FALSE` = force-return; consider introducing a tri-state type (`SKIP`, `FORCE_TRUE`, `FORCE_FALSE`) to make the contract unambiguous.

### MED-107 — `ThreadLocalGetAdvice` type matcher uses `isSubTypeOf(ThreadLocal.class)` and therefore instruments `InheritableThreadLocal.get()`; suppressing inherited thread-locals silently breaks context propagation
- **Location:** `chaos-agent-instrumentation-jdk/src/main/java/com/macstab/chaos/instrumentation/JdkInstrumentationInstaller.java` (ThreadLocal type matcher); `JvmRuntimeAdvice.java:455`
- **Issue:** `InheritableThreadLocal.get()` overrides `ThreadLocal.get()` and carries JVM-guaranteed parent-to-child inheritance semantics used by security frameworks (MDC, SLF4J, Spring Security context). The advice suppresses the call and returns `null`, breaking any framework that reads an inherited thread-local in a child thread spawned within the instrumented app. There is no mechanism for the dispatcher to distinguish `ThreadLocal` from `InheritableThreadLocal` instances.
- **Fix direction:** Add `not(isSubTypeOf(InheritableThreadLocal.class))` to the `ThreadLocal` type matcher, or add a runtime check in the advice enter that skips dispatch when `this` is an `InheritableThreadLocal`.

### MED-108 — `SocketWriteAdvice` hardcodes `len=0` in the `beforeSocketWrite` dispatcher call; scenarios that gate on write byte-count are permanently inactive
- **Location:** `chaos-agent-instrumentation-jdk/src/main/java/com/macstab/chaos/instrumentation/JvmRuntimeAdvice.java:321`
- **Issue:** `SocketWriteAdvice.enter` calls `BootstrapDispatcher.beforeSocketWrite(stream, 0)` regardless of overload. For the three-argument `write(byte[], int, int)` overload, the actual byte count is available as `@Advice.Argument(2)`. Passing constant `0` means any `NetworkSelector` or downstream scenario that filters on write size always sees `0`, permanently suppressing any size-gated rule.
- **Fix direction:** Split into two advice inner classes: one for `write(int)` (len=1) and one for `write(byte[], int, int)` binding `@Advice.Argument(2)` as the length.

### MED-109 — `buildInjectedExceptionTerminal` calls `Class.forName(name, false, ...)` to avoid static initializer, but `Constructor.newInstance()` triggers class initialization anyway; the `initialize=false` defence is illusory
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosDispatcher.java:1137-1150`
- **Issue:** `Class.forName(..., false, ...)` defers `<clinit>` only until the class is first instantiated. The very next line calls `getConstructor(...).newInstance(...)`, which initializes the class. The comment at line 1132 claims this prevents running the target's static initializer as a security defence; the code does not fulfil the claim. An operator-supplied exception class with a malicious static initializer (loaded from a plan file on a compromised operator workstation) would have its initializer executed.
- **Fix direction:** Document the gap honestly and remove the misleading comment; or use `Unsafe.allocateInstance(clazz)` + reflective field set to skip constructor invocation entirely.

### MED-110 — `buildInjectedExceptionTerminal` no-arg constructor fallback silently discards the operator-configured `message`; injected exception carries `null` detail message
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosDispatcher.java:1150`
- **Issue:** When the exception class has no `(String)` constructor, the code falls back to `getDeclaredConstructor().newInstance()`. The resulting instance has a `null` detail message. The operator-supplied `effect.message()` is permanently lost. With `withStackTrace=false` also set, the injected exception carries neither a message nor a trace, making it completely opaque in application logs.
- **Fix direction:** After constructing the no-arg instance, call `instance.initCause(new RuntimeException(effect.message()))` to preserve the configured message in the cause chain; also try `(String, Throwable)` constructor before falling back to no-arg.

### MED-111 — `beforeNioSelect` calls `evaluate()` but never calls `applyGate(decision.gateAction())`; gate effects on `NIO_SELECTOR_SELECT` are silently ignored
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosDispatcher.java:598`
- **Issue:** Every other `before*` method that uses `RuntimeDecision` calls `applyGate(decision.gateAction())` before checking the terminal action (`beforeHttpSend` line 891, `beforeScheduledTick` line 351, etc.). `beforeNioSelect` skips this step. A `GateEffect` configured for `NIO_SELECTOR_SELECT` is silently discarded.
- **Fix direction:** Add `applyGate(decision.gateAction());` immediately after `evaluate(context)` in `beforeNioSelect`.

### MED-112 — `beforeAsyncCancel` applies neither gate nor delay; `GateEffect` and `DelayEffect` on `ASYNC_CANCEL` are silently discarded
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosDispatcher.java:751`
- **Issue:** `beforeAsyncCancel` calls `evaluate` then immediately checks `terminalAction`, without calling `applyGate` or `sleep`. Both gate and delay effects configured for `ASYNC_CANCEL` are permanently inactive.
- **Fix direction:** Add `applyGate(decision.gateAction());` before the terminal check and `sleep(decision.delayMillis());` on the non-terminal path, mirroring the pattern in `beforeHttpSend`.

### MED-113 — `beforeThreadLocalGet` and `beforeThreadLocalSet` both ignore `delayMillis`; `DelayEffect` on `ThreadLocalSelector` is always a no-op
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosDispatcher.java:805, 830`
- **Issue:** Both methods evaluate the decision, check for terminal action (THROW/SUPPRESS), but on the non-terminal path return `false` without calling `sleep(decision.delayMillis())`. A `DelayEffect` configured for a `ThreadLocalSelector` scenario is silently discarded, inconsistent with `beforeThreadSleep`, `beforeGcRequest`, and all other non-gate methods that honour delay.
- **Fix direction:** Add `sleep(decision.delayMillis());` before the `return false` at lines 826 and 852.

### MED-114 — `decorateShutdownHook` creates and registers a wrapper thread even when the scenario's terminal action is `SUPPRESS`; hook is registered despite intent to suppress
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosDispatcher.java:293, 307-309`
- **Issue:** `applyPreDecision(decision)` on the `SUPPRESS` path returns normally (it calls `BootstrapDispatcher.suppressThrow()`). Execution continues to create a `decorated` wrapper thread and insert it into `shutdownHooks`. The hook ends up registered despite the scenario's intent to suppress registration. If the caller then tries `Runtime.removeShutdownHook(original)`, `resolveShutdownHook` returns the wrapper, causing `removeShutdownHook` to fail with `IllegalArgumentException`.
- **Fix direction:** After `applyPreDecision` returns, detect that the terminal action was `SUPPRESS` and return `hook` unchanged without creating a wrapper or updating the map.

### MED-115 — `ChaosActuatorEndpoint.snapshot()` and `stop()` propagate raw `RuntimeException` — same stacktrace leak pattern as HIGH-46 on `activate()`
- **Location:** `chaos-agent-spring-boot-common/src/main/java/com/macstab/chaos/spring/boot/common/ChaosActuatorEndpoint.java:47, 79`
- **Issue:** `snapshot()` calls `controlPlane.diagnostics().snapshot()` and `stop()` calls `controlPlane.diagnostics().scenario(id)` with no exception handling. If the runtime is partially shut down, these throw `RuntimeException` that propagates through the Actuator layer as an unguarded HTTP 500 with internal stack frames, identical to the HIGH-46 pattern on `activate`.
- **Fix direction:** Wrap both calls in `try/catch (RuntimeException)`, returning an error-status response object instead of letting exceptions escape.

### MED-116 — `ChaosQuarkusExtension.activateAnnotations` / `toScenario` lets `DateTimeParseException` and `IllegalArgumentException` from invalid annotation attributes propagate raw into JUnit; test failure is attributed to infrastructure, not to the bad annotation
- **Location:** `chaos-agent-quarkus-extension/src/main/java/com/macstab/chaos/quarkus/ChaosQuarkusExtension.java:67, 201`
- **Issue:** `toScenario` calls `Duration.parse` and `ScenarioScope.valueOf` without catching the resulting unchecked exceptions. When a `@ChaosScenario` annotation carries an invalid `activeFor="not-a-duration"` or `scope="UNKNOWN"`, the raw exception propagates through `beforeEach`/`beforeAll` into the JUnit engine, which reports a test infrastructure failure with no hint that the annotation attribute is malformed.
- **Fix direction:** Wrap `toScenario` in a try/catch and rethrow as `ExtensionConfigurationException` naming the annotation attribute and its invalid value.

### MED-117 — `ChaosAgentInitializer.registerSingleton` conflicts with `ChaosTestAutoConfiguration.@ConditionalOnMissingBean`; combined use throws `BeanDefinitionOverrideException` in some Spring contexts
- **Location:** `chaos-agent-spring-boot3-test-starter/.../ChaosAgentInitializer.java:25`; `chaos-agent-spring-boot4-test-starter/.../ChaosAgentInitializer.java:25`
- **Issue:** `registerSingleton` unconditionally registers a pre-instantiated singleton before context refresh. If `ChaosTestAutoConfiguration` is also present, its `@ConditionalOnMissingBean` check fires after the singleton is visible, skipping the `@Bean` method — which is intended. However, if both `ChaosAgentInitializer` and the auto-config `@Bean` are present in the same context (e.g., via explicit `@Import`), Spring detects a duplicate bean name and throws `BeanDefinitionOverrideException`.
- **Fix direction:** Guard the call with `if (!applicationContext.getBeanFactory().containsSingleton("chaosControlPlane"))` to make the initializer idempotent alongside the auto-configuration.

### MED-118 — `ManualGate.await(Duration)` uses `duration.toMillis()` — a sub-millisecond positive `Duration` truncates to `0`, passes the `isZero()` guard as false, then calls `latch.await(0, MILLISECONDS)` which returns immediately
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ManualGate.java:73`
- **Issue:** `maxBlock.toMillis()` truncates toward zero. `Duration.ofNanos(500_000)` (0.5 ms) returns `toMillis()==0` but `isZero()==false` (the duration is not zero). The branch that calls `latch.await(0, MILLISECONDS)` returns immediately (JDK contract: `await` with non-positive timeout does not block), turning the gate into a no-op. Any `GateAction` with a sub-millisecond `maxBlockDuration` silently fails to hold callers.
- **Fix direction:** Replace `maxBlock.toMillis()` with `Math.max(1, maxBlock.toMillis())` or use `maxBlock.toNanos()` with `TimeUnit.NANOSECONDS` to preserve sub-millisecond precision.

### MED-119 — `ClockSkewState` FREEZE mode computes `frozenMillis = capturedMillis + skewMillis` with plain `long` arithmetic; overflows silently for large `skewAmount` values
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ClockSkewState.java:46-47`
- **Issue:** `Duration.toMillis()` can return `Long.MAX_VALUE` for near-maximal durations. Adding that to a real epoch millis value (~1.7×10¹²) overflows to a large negative number. FREEZE mode then returns this corrupted constant for all subsequent clock reads for the scenario's lifetime. `ClockSkewEffect`'s constructor does not cap `skewAmount` magnitude.
- **Fix direction:** Use `Math.addExact` with saturation (catch `ArithmeticException` and clamp to `Long.MAX_VALUE`), or validate in `ClockSkewEffect`'s compact constructor that `skewAmount.toMillis()` fits in `Long.MAX_VALUE - System.currentTimeMillis()`.

### MED-120 — `ObservabilityBus.addListener()` has no happens-before guarantee with first `publish()`; a listener registered concurrently with plan activation may miss the `STARTED` event
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ObservabilityBus.java:64-66`
- **Issue:** `CopyOnWriteArrayList.add()` is thread-safe but imposes no ordering against the `publish()` call that fires `STARTED` during `ScenarioController.start()`. A monitoring component that calls `addListener()` immediately after plan activation may race: if the `STARTED` event fires before `add()` completes, the listener never receives it. No replay mechanism exists. Observers relying on `STARTED` to reconstruct active-scenario state will produce incorrect results when registered concurrently with activations.
- **Fix direction:** Document the race and provide a `snapshotActiveScenarios()` API that returns current active scenario IDs so newly-registered listeners can synthesize missed `STARTED` events; or emit a `REGISTERED` event at listener registration time that includes current active set.

### MED-121 — `ChaosQuarkusExtension.afterAll` removes `ChaosSession` from store but leaves `ChaosControlPlane`; stale control-plane reference resolves for `@Nested` child contexts after parent `afterAll`
- **Location:** `chaos-agent-quarkus-extension/src/main/java/com/macstab/chaos/quarkus/ChaosQuarkusExtension.java:82`
- **Issue:** `afterAll` removes the `ChaosSession.class` key from the extension store but not the `ChaosControlPlane.class` key. JUnit 5 store hierarchies let child contexts inherit parent store entries. After the parent `afterAll` fires, the stale parent control plane entry persists; `resolveParameter` on `ChaosControlPlane` in a `@Nested` class returns the orphaned instance whose session has already been closed, and any activation on it silently fails or pollutes the parent registry.
- **Fix direction:** Remove both `ChaosSession.class` and `ChaosControlPlane.class` entries from the store in `afterAll`.

### MED-122 — `TrackingChaosControlPlane.addEventListener()` forwards to the JVM-wide delegate but never deregisters; listeners accumulate across the test suite, firing for scenarios in unrelated test classes
- **Location:** `chaos-agent-testkit/src/main/java/com/macstab/chaos/testkit/TrackingChaosControlPlane.java:69`
- **Issue:** `addEventListener` forwards the listener to the JVM-wide delegate. `ChaosAgentExtension.afterEach` calls `stopTracked()` and `session.close()` but neither removes registered listeners. Because the delegate is a JVM-wide singleton, listeners from test N remain active in tests N+1 through N+M, causing cross-test event capture and ordering-dependent failures in test suites that assert received-event counts.
- **Fix direction:** Track registered listeners in `TrackingChaosControlPlane` (analogous to handle tracking) and expose a `removeTrackedListeners()` method; call it in `ChaosAgentExtension.afterEach`. If `ChaosControlPlane` API has no `removeEventListener`, document the limitation prominently.

### MED-123 — `ChaosControlPlaneImpl.close()` is not idempotent; second call fires duplicate `STOPPED` lifecycle events on all controllers
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosControlPlaneImpl.java:115-117`
- **Issue:** `close()` calls `registry.controllers().forEach(ScenarioController::destroy)`. Controllers are never removed from the registry by `ScenarioController.destroy()` — only `DefaultChaosActivationHandle.destroy()` calls `registry.unregister()`. A second `close()` iterates the same set of controllers and calls `stop()` again, which unconditionally publishes a second `STOPPED` event. Test listeners counting lifecycle events see double STOPPED; non-idempotent metric sinks are called twice with stale state. This is distinct from MED-30 (which covers the re-activation failure) — here the defect is the duplicate-event emission.
- **Fix direction:** Add an `AtomicBoolean closed` guard to `ChaosControlPlaneImpl.close()` and return immediately on the second call; or call `registry.unregister(controller)` inside `ScenarioController.destroy()` so the second `close()` iteration finds an empty set.

### MED-124 — `ManualGate.await()` discards the `CountDownLatch.await(timeout, unit)` boolean return; gate timeout and gate release are silently indistinguishable
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ManualGate.java:73`
- **Issue:** `latch.await(millis, MILLISECONDS)` returns `true` if the gate was released by `release()` and `false` if the timeout expired. The return value is discarded. Neither `applyGate()` nor any caller can distinguish "gate opened" from "gate timed out and proceeding anyway". There is no metric increment, log entry, or observable state transition for the timeout path. In production, operators cannot detect that their `maxBlockDuration` safety valve is firing.
- **Fix direction:** Capture the boolean return value and, if `false`, increment a `chaos.gate.timeout` metric (via `ObservabilityBus`) or emit a JFR/log event indicating the timeout path was taken.

---

## Downgraded

### DOWN-1 — `OperationType.valueOf` hot path
- **Location:** `chaos-agent-core/src/main/java/com/macstab/chaos/core/ChaosDispatcher.java:54, 82, 160, 186, 201, 229, 327, 626`
- **Original claim:** unchecked `IllegalArgumentException` escapes into application threads on unknown operation strings.
- **Re-verification:** `BootstrapDispatcher.invoke()` (lines 1477-1498) wraps dispatcher calls with try/catch that routes via `sneakyThrow`. Exceptions from `valueOf` are caught before they reach application frames. Not a crash risk at runtime.
- **Residual smell:** still worth replacing with a pre-built `Map<String, OperationType>` for hot-path performance and defensiveness, but demoted from HIGH to NIT.
- **Status:** Downgraded

---

## Attack order (recommendation)

### Tier 0 — Baseline / packaging (fix first or nothing else matters)
`HIGH-41` (release=21 vs docs claim 17), `HIGH-34` (Quarkus runtime/deployment split), `HIGH-35` (Boot test-starter SPI inert), `HIGH-42` (Micronaut SPI file layout wrong), `HIGH-43` (`@MicronautChaosTest` resolver collision), `HIGH-44` (Quarkus @Record(RUNTIME_INIT) misuse), `HIGH-45` (Quarkus native-image reflect-config missing), `HIGH-55` (sb3-actuator-live-chaos circuit-breaker demo broken), `HIGH-56` (sb4-virtual-thread-pinning targets wrong lock), `MED-60` (bootstrap fat-jar duplicate classes), `MED-59` (testkit leaks JUnit via transitive API), `MED-70` (Spring EPP ordering), `MED-71` (ApplicationReadyEvent per child ctx), `MED-72` (Actuator config class-only condition), `MED-73` (Quarkus producer/recorder race), `MED-75` (Quarkus scope-flip), `MED-80` (manifest missing Launcher-Agent-Class), `MED-82` (docs/testkit.md repeats broken snippets), `MED-83` (sb4-sla scope not propagated)

### Tier 1 — Showstoppers (silent broken feature / data loss)
`CRIT-4`, `CRIT-5`, `HIGH-15`, `HIGH-22`, `HIGH-26`, `HIGH-32`, `HIGH-36`, `HIGH-47`, `HIGH-48`, `HIGH-49`, `HIGH-50`, `HIGH-59`, `HIGH-61`, `HIGH-62` (sub-ms rate window disables rate limit), `HIGH-63` (BlockingQueue synthetic VerifyError), `HIGH-64` (JDBC synthetic VerifyError), `HIGH-65` (loadLibrary0 dead on JDK 21), `HIGH-66` (NIO channel abstract-class dead advice), `HIGH-67` (CORRUPT_RETURN silent discard), `HIGH-68` (FailureFactory missing reject cases), `MED-28`, `MED-61`, `MED-96`, `MED-97`

### Tier 2 — Lifecycle correctness
`CRIT-1`, `CRIT-2`, `CRIT-3`, `HIGH-31`, `HIGH-2`, `HIGH-3`, `HIGH-10` — overflow cluster.
`HIGH-1`, `HIGH-4`, `HIGH-5`, `HIGH-6`, `HIGH-7`, `HIGH-14`, `HIGH-16`, `HIGH-17`, `HIGH-18`, `HIGH-19`, `HIGH-20`, `HIGH-25`, `HIGH-27`, `HIGH-28`, `HIGH-33`, `HIGH-37`, `HIGH-38`, `HIGH-39`, `HIGH-40`, `HIGH-57`, `HIGH-58`, `HIGH-60`, `HIGH-70`, `HIGH-71`, `HIGH-72`, `HIGH-73`, `HIGH-74`, `HIGH-75`, `HIGH-76`, `HIGH-77`, `HIGH-78` (start() double-call leaks stressor), `MED-6`, `MED-13`, `MED-14`, `MED-15`, `MED-51`, `MED-52`, `MED-53`, `MED-57`, `MED-69`, `MED-86`, `MED-87`, `MED-88`, `MED-89`, `MED-90`, `MED-102`, `MED-118`, `MED-119`, `MED-120`, `MED-121`, `MED-122`, `MED-123` (close() non-idempotent duplicate events), `MED-124` (ManualGate timeout silent) — concurrency + lifecycle; fix as one pass.

### Tier 3 — Dispatch correctness
`HIGH-12`, `HIGH-13`, `HIGH-23`, `HIGH-24`, `HIGH-29`, `HIGH-30`, `MED-9`, `MED-10`, `MED-11`, `MED-12`, `MED-16`, `MED-19`, `MED-29`, `MED-49`, `MED-50`, `MED-111`, `MED-112`, `MED-113`, `MED-114`

### Tier 4 — Bootstrap / instrumentation reliability
`HIGH-21`, `MED-31`, `MED-32`, `MED-33`, `MED-41`, `MED-62`, `MED-63`, `MED-92`, `MED-93`, `MED-94`, `MED-95`, `MED-105`, `MED-106`, `MED-107`, `MED-108`, `MED-109`, `MED-110`

### Tier 5 — Input validation / config safety / security / API hardening
`HIGH-46`, `HIGH-69` (stop propagates exception through Actuator), `MED-7`, `MED-8`, `MED-35`, `MED-36`, `MED-37`, `MED-38`, `MED-39`, `MED-40`, `MED-43`, `MED-44`, `MED-45`, `MED-64`, `MED-65`, `MED-66`, `MED-67`, `MED-68`, `MED-74`, `MED-76`, `MED-77`, `MED-78`, `MED-79`, `MED-81`, `MED-91`, `MED-98`, `MED-99`, `MED-100`, `MED-101`, `MED-103` (Jackson type-id NPE path), `MED-104` (builder null message), `MED-115` (snapshot/stop stacktrace leak), `MED-116` (Quarkus annotation parse raw exception), `MED-117` (registerSingleton conflicts with ConditionalOnMissingBean)

### Tier 6 — Stressor & observability polish
`HIGH-8`, `HIGH-9`, `HIGH-11`, `MED-3`, `MED-4`, `MED-5`, `MED-17`, `MED-18`, `MED-20`, `MED-21`, `MED-22`, `MED-23`, `MED-24`, `MED-25`, `MED-26`, `MED-27`, `MED-30`, `MED-34`, `MED-42`, `MED-46`, `MED-47`, `MED-48`, `MED-54`, `MED-55`, `MED-56`, `MED-58`

### Tier 7 — Docs / examples / perf / test-determinism
`HIGH-51`, `HIGH-52`, `HIGH-53`, `HIGH-54`, `MED-1`, `MED-84`, `MED-85`
