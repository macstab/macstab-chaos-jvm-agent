<!--
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Engineered by  Christian Schnapka
                 Embedded Principal+ Engineer
                 Macstab GmbH · Hamburg, Germany
                 https://macstab.com
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-->

# JMH Benchmark Suite — macstab-chaos-jvm-agent

> Latency characterisation of the chaos dispatch hot path under controlled JVM conditions.
>
> *Engineered by* **[Christian Schnapka](https://macstab.com)** — Embedded Principal+ Engineer · [Macstab GmbH](https://macstab.com) · Hamburg, Germany

---

## 1. Overview

### Why This Module Exists

The chaos agent intercepts hundreds of JVM operations per second in production. Most of those interceptions will fire with zero active scenarios — the agent is installed but no chaos is currently scheduled. In that condition, every call site touched by a ByteBuddy advice class bears a small overhead: the advice bytecode executes, `BootstrapDispatcher.invoke()` runs, the DEPTH guard fires, the delegate null-check passes, and control returns. That round-trip must stay below the noise floor of the I/O operation it guards. A connection acquisition that takes 500 µs must not see 10 µs of agent overhead — that would be a 2% tax on every database call without any chaos being active.

The JMH suite in `chaos-agent-benchmarks` exists to verify that claim with numbers. It measures the cost of the `ChaosDispatcher` hot path — the code executed on every instrumented call — across a spectrum of scenario configurations, from zero scenarios to ten, and from a complete miss to a selector hit with no terminal effect. The benchmarks do not measure the cost of the ByteBuddy advice wrapper itself (that is an instrumentation concern tested separately); they measure the dispatch logic that runs unconditionally after the advice calls `BootstrapDispatcher`.

### What Is Measured

The two benchmark classes cover the two hot-path entry points added in roadmap 2.3 and 2.4:

- `ChaosRuntimeBenchmark` — the JDBC dispatch path (`ChaosDispatcher.beforeJdbcStatementExecute()`)
- `HttpClientBenchmark` — the HTTP dispatch path (`ChaosDispatcher.beforeHttpSend()`)

Both measure `ChaosDispatcher` method calls directly, bypassing the `BootstrapDispatcher` → `ChaosBridge` indirection. This isolates the dispatch logic (scenario registry match, selector evaluation, decision building) from the fixed overhead of the classloader bridge.

### Module and Execution

Module: `chaos-agent-benchmarks`

```
./gradlew :chaos-agent-benchmarks:run
```

The `application` plugin sets `mainClass = "org.openjdk.jmh.Main"`. No additional flags are required for the default suite. To run a specific benchmark:

```
./gradlew :chaos-agent-benchmarks:run --args="ChaosRuntimeBenchmark"
```

To add profiling (GC, async-profiler, flight recorder):

```
./gradlew :chaos-agent-benchmarks:run --args="-prof gc ChaosRuntimeBenchmark"
```

---

## 2. Benchmark Architecture

### 2.1 JMH 1.37 — Mode and Time Unit

```java
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
```

`Mode.AverageTime` reports the arithmetic mean time per operation across all measurement iterations. The alternative, `Mode.Throughput`, reports operations per second — which is mathematically equivalent but psychologically different. Throughput numbers are hard to compare against real-world I/O costs ("1.2 million ops/sec" is not obviously comparable to "a socket read takes 10 µs"). Nanosecond average time is directly comparable: "this dispatch path costs 45 ns, which is 2% of a 2 µs I/O operation."

`OutputTimeUnit.NANOSECONDS` selects the unit for the report output. JMH still measures in the most precise unit available (typically CPU cycle counts via `System.nanoTime()` under the covers) and converts at report time. This choice does not affect measurement accuracy.

### 2.2 `@State(Scope.Benchmark)` — Setup Per Trial

Both `ChaosRuntimeBenchmark` and `HttpClientBenchmark` declare their state classes with `@State(Scope.Benchmark)`. JMH recognises three scopes:

- `Scope.Thread` — a separate state instance per benchmark thread; useful for isolating thread-local state
- `Scope.Benchmark` — a single instance shared across all threads of a benchmark fork; appropriate when the state is thread-safe and you want multiple threads competing on the same registry (realistic for a production scenario)
- `Scope.Group` — used with `@Group` benchmarks (not used here)

`Scope.Benchmark` is the correct choice because `ScenarioRegistry` — the data structure being read on every hot-path call — is a shared, thread-safe structure backed by `CopyOnWriteArrayList`. In production, multiple application threads concurrently call `registry.match()`. Using `Scope.Benchmark` reproduces this contention pattern accurately even in the single-thread JMH fork used here.

### 2.3 `@Setup(Level.Trial)` — Scenario Activation Outside Measurement

The `@Setup` annotations on state classes use `Level.Trial`:

```java
@Setup(Level.Trial)
public void setup() {
    runtime = new ChaosRuntime();
    runtime.activate(/* scenario */);
    dispatcher = runtime.dispatcher();
}
```

`Level.Trial` runs once per benchmark fork, before any warmup or measurement iterations begin. The alternatives — `Level.Iteration` (once per iteration) and `Level.Invocation` (once per benchmark method call) — would both include scenario activation cost in the measurement. Scenario activation modifies `ScenarioRegistry` via a CAS on a `CopyOnWriteArrayList`, which involves an array copy: a few hundred nanoseconds. Including that cost would make the benchmark measure scenario management overhead rather than dispatch overhead, which is not the intent.

With `Level.Trial` setup, by the time JMH begins the warmup iterations, all scenarios are fully activated and the `ScenarioRegistry` snapshot is in its final stable state. The measurement iterations see exactly the runtime cost of traversing and matching against a static scenario list.

### 2.4 `Blackhole.consume()` — Dead Code Prevention

Every benchmark method passes its return value to `bh.consume()`:

```java
@Benchmark
public void agentInstalled_zeroScenarios(ZeroScenariosState state, Blackhole bh)
        throws Throwable {
    bh.consume(state.dispatcher.beforeJdbcStatementExecute("SELECT 1"));
}
```

`beforeJdbcStatementExecute` returns `boolean`. If that return value were discarded (i.e., the benchmark method returned `void` without consuming it), the JIT compiler's escape analysis would determine that the result has no observable effect. Given that `beforeJdbcStatementExecute` with zero active scenarios reduces to a few comparisons and a return of `false`, the entire method body could be legally eliminated. The resulting benchmark would measure nothing — the compiled benchmark loop body would contain no meaningful work.

`Blackhole.consume(boolean)` creates a data dependency that prevents the JIT from eliminating the call: the value must be computed because it is consumed by an opaque call that the JIT cannot reason about. `Blackhole` itself is carefully designed to prevent speculative optimisation: its implementation uses volatile writes and double-checking patterns that force the compiler to treat the consumed value as observable.

### 2.5 `@Fork(1)` — Single JVM Fork for CI

```java
@Fork(1)
```

`Fork(1)` runs the benchmarks in a single fresh JVM process per benchmark class (not per individual benchmark method — JMH forks per class when `Fork` is set at the class level). A fork creates a fresh JVM, avoids JIT profile pollution from previous benchmarks in the same JVM, and ensures each benchmark starts from a clean JIT compilation state.

`Fork(1)` is the right choice for CI pipelines: one fork is fast enough to run in a CI job and still isolates each class from the others. For release-level profiling or publication, increase to `Fork(3)` or `Fork(5)` to reduce variance across JVM startup conditions and to collect more GC/JIT data.

### 2.6 `@Warmup(3, 1s)` / `@Measurement(5, 1s)` — JIT Saturation Reasoning

```java
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
```

A 1-second warmup iteration at typical benchmark throughput (~10–50 million calls/second for a method that returns in ~20–100 ns) executes between 10 million and 50 million invocations per iteration. After the first warmup iteration (~10M calls), HotSpot's C1 compiler has long since compiled the hot path (C1 threshold: ~1000–2000 invocations). After the second iteration (~20M calls), C2's profiling threshold (~10 000–15 000 invocations for a tier-3 compiled stub, ~100 000–200 000 for full C2 optimization) is crossed by several orders of magnitude. By the third warmup iteration, the benchmark method body, `beforeJdbcStatementExecute`, `evaluateJdbc`, `registry.match`, `SelectorMatcher.matches`, and the entire dispatch chain are fully C2-compiled with all polymorphic inline caches populated and speculative optimisations locked in.

The five 1-second measurement iterations therefore capture pure C2-compiled performance. Three warmup iterations is deliberately conservative — two would suffice for C2 saturation, but three provides a safety margin for the JVM's own background recompilation and deoptimisation/recompilation cycles that may occur in the first iteration.

---

## 3. `ChaosRuntimeBenchmark` — JDBC Hot Path

### 3.1 What It Measures

`ChaosRuntimeBenchmark` calls `ChaosDispatcher.beforeJdbcStatementExecute("SELECT 1")` across six scenario configurations. This method is the entry point for all `Statement.execute(String)` interceptions. It calls `evaluateJdbc()`, which calls `registry.match()`, which scans the active scenario list and evaluates each `JdbcSelector` against the `InvocationContext` for `JDBC_STATEMENT_EXECUTE` with `targetName = "SELECT 1"`.

The benchmark entry point bypasses `BootstrapDispatcher` and calls `ChaosDispatcher` directly via `state.dispatcher.beforeJdbcStatementExecute(...)`. This isolates the dispatch evaluation cost from the fixed bridge overhead. In production, the bridge overhead (one `MethodHandle.invoke()` through `ChaosBridge`) adds approximately 5–15 ns at JIT saturation.

### 3.2 State Classes and Scenario Configuration

Each benchmark variant has its own `@State` inner class that pre-activates a specific set of scenarios.

**`NoAgentState`**: No `ChaosRuntime` is created. The baseline variant executes `state.executor.execute(DUMMY_TASK)` with a trivial `Executor` (`Runnable::run`). This is the zero-overhead reference point: the cost of a method call through a functional interface with no agent involvement.

**`ZeroScenariosState`**: A live `ChaosRuntime` is created with no scenarios activated. The `ScenarioRegistry` is empty. `registry.match()` returns an empty list on the first registry snapshot read. The dispatch terminates immediately at the `contributions.isEmpty()` check and returns `RuntimeDecision.none()`.

**`OneScenarioNoMatchState`**: One scenario is activated targeting `JDBC_STATEMENT_EXECUTE` with a regex pattern `"SELECT\\s+\\*\\s+FROM\\s+no_match_table"`. The scenario is active but will never match `"SELECT 1"`. `registry.match()` returns one contribution, `SelectorMatcher` evaluates the regex and fails, the contribution list is empty after filtering, and `evaluateJdbc` returns `false`. This measures the cost of a full registry traverse including regex evaluation.

**`OneMatchNoEffectState`**: One scenario is activated targeting `JDBC_STATEMENT_EXECUTE` with `NamePattern.any()`. The scenario uses `ONE_SHOT_POLICY` — `ActivationPolicy` constructed with `probability=1.0d` and `maxApplications=1L` — and a `ChaosEffect.delay(Duration.ofMillis(0))` (zero-delay). The scenario matches every call; on the very first application it fires a 0ms delay (no observable pause), then `maxApplications` is reached and the scenario transitions to INACTIVE. All warmup and measurement iterations after the first see the INACTIVE fast path. This measures the cost of a full selector match plus the INACTIVE state check once the quota is exhausted.

**`SessionMissState`**: A session-scoped scenario is activated on a session that is never joined on the calling thread. `ChaosDispatcher.evaluateJdbc()` calls `scopeContext.currentSessionId()` to get the current thread's session ID. If the current thread is not inside a session (`currentSessionId()` returns `null`), session-scoped scenarios are filtered out by `registry.match()` at the session-scope check. The scenario exists in the registry but never contributes. This measures the additional cost of the session scope check: approximately one `ThreadLocal.get()` call.

**`TenScenariosState`**: Nine scenarios are activated targeting `JDBC_CONNECTION_ACQUIRE` (a different operation type) and one scenario targets `JDBC_STATEMENT_EXECUTE` with a zero-probability activation. `registry.match()` must traverse all ten scenario entries. Nine are discarded at the `operationType` check; one matches but produces no terminal effect. This measures the linear scan cost across ten scenarios.

### 3.3 Benchmark Variants and Targets

| Benchmark method                     | State class               | What it measures                                                    | Design target                           |
|--------------------------------------|---------------------------|---------------------------------------------------------------------|-----------------------------------------|
| `baseline_noAgent`                   | `NoAgentState`            | Raw executor call + `Blackhole.consume()` with no agent on the path | Reference: ~1–5 ns                      |
| `agentInstalled_zeroScenarios`       | `ZeroScenariosState`      | Empty registry fast path                                            | < 50 ns                                 |
| `agentInstalled_oneScenario_noMatch` | `OneScenarioNoMatchState` | One scenario, regex non-match                                       | < 100 ns                                |
| `agentInstalled_oneMatch_noEffect`   | `OneMatchNoEffectState`   | Selector match, zero-probability effect                             | < 300 ns                                |
| `sessionIdMiss`                      | `SessionMissState`        | Session scope check short-circuit                                   | < 20 ns additional over `zeroScenarios` |
| `tenScenarios_oneMatch`              | `TenScenariosState`       | Linear scan through 10 entries                                      | < 1 µs                                  |

### 3.4 What the Targets Mean in Production Terms

A thread park (`LockSupport.park`) costs approximately 200–500 ns on a warm Linux kernel with no scheduler contention. A userspace context switch (voluntary yield via `Thread.yield()`) costs 1–5 µs. A loopback TCP round-trip costs 20–200 µs. A database connection acquisition from HikariCP's idle pool (no actual JDBC driver call needed) costs 2–10 µs.

The agent's overhead targets are calibrated against these real I/O costs:

- **< 50 ns** (zero scenarios): below one thread park. The agent installed with no active chaos is essentially invisible — it does not register in the timing of any I/O operation that takes more than a microsecond.
- **< 100 ns** (one scenario, no match): still below one thread park. A single inactive scenario costs less than half a percent of a 20 µs loopback TCP call.
- **< 300 ns** (one match, no effect): 3% of a 10 µs HikariCP connection acquire. Acceptable for a scenario that is structurally active. In practice, a zero-probability scenario is used only during ramp-up or ramp-down; steady-state chaos will either have the scenario inactive or the effect firing (in which case the latency is dominated by the effect, not the dispatch).
- **< 1 µs** (ten scenarios, one match): 10% of a 10 µs pool acquire. Ten simultaneously active scenarios is an unusually high number for a single operation type; typical production chaos game days run one to three scenarios.

### 3.5 The `baseline_noAgent` Baseline

```java
@Benchmark
public void baseline_noAgent(NoAgentState state, Blackhole bh) {
    state.executor.execute(DUMMY_TASK);
    bh.consume(state.executor);
}
```

`DUMMY_EXECUTOR = Runnable::run` and `DUMMY_TASK = () -> {}`. The benchmark executes a trivial `Runnable` through a functional-interface executor. There is no `ChaosRuntime`, no `BootstrapDispatcher`, and no advice on the path. At JIT saturation, this reduces to an `invokeinterface` call to the lambda and a `Blackhole.consume` call. The measured cost is the irreducible overhead of calling a method through an interface reference and consuming a pointer — approximately 1–5 ns.

The `baseline_noAgent` result is the floor below which no agent measurement can go. If `agentInstalled_zeroScenarios` measures below `baseline_noAgent`, it indicates measurement noise or a JIT artifact and the warmup should be increased.

---

## 4. `HttpClientBenchmark` — HTTP Hot Path

### 4.1 What It Measures

`HttpClientBenchmark` mirrors `ChaosRuntimeBenchmark` in structure but targets `ChaosDispatcher.beforeHttpSend(URL, OperationType.HTTP_CLIENT_SEND)`. This is the entry point for all synchronous HTTP client interceptions (OkHttp `execute()`, Apache HC 4/5 `execute()`).

The HTTP dispatch path has slightly more overhead than the JDBC path for one reason: URL pattern matching. For a `JdbcSelector` without a SQL pattern, the target name match is a single `NamePattern.any()` fast path. For an `HttpClientSelector`, the `urlPattern` may be a glob or regex matched against the full URL string. Even for `NamePattern.any()`, the `SelectorMatcher` must read the URL string from `InvocationContext.targetName` and invoke `urlPattern.matches()`. The constant URL `"https://example.com/api/v1/orders"` used throughout the HTTP benchmark ensures the string is interned and does not allocate on each call.

### 4.2 Scenario Configurations

The HTTP benchmark replicates all five non-baseline variants from `ChaosRuntimeBenchmark`:

**`ZeroScenariosState`**: Empty registry, fast path. No HTTP scenarios registered.

**`OneScenarioNoMatchState`**: One HTTP scenario targeting `HTTP_CLIENT_SEND` with `NamePattern.regex("https://other\\.example\\.net/.*")`. The benchmark URL `"https://example.com/api/v1/orders"` does not match. The regex is compiled once during activation and evaluated per call.

**`OneMatchNoEffectState`**: One HTTP scenario targeting `HTTP_CLIENT_SEND` with `NamePattern.any()`, zero-probability activation. The selector matches the URL; the effect does not fire.

**`SessionMissState`**: Session-scoped HTTP scenario. Current thread carries no session ID; the scenario is filtered out.

**`TenScenariosState`**: Nine scenarios targeting `HTTP_CLIENT_SEND_ASYNC` (operation type mismatch) and one targeting `HTTP_CLIENT_SEND` with `NamePattern.any()` and zero-probability activation. All nine are discarded at the operation type filter; one matches but does not fire.

### 4.3 Benchmark Variants and Targets

| Benchmark method                     | Target             | Notes                                                                        |
|--------------------------------------|--------------------|------------------------------------------------------------------------------|
| `baseline_noAgent`                   | Reference: ~1–3 ns | `bh.consume(URL.length())` — string length read as proxy for "no agent work" |
| `agentInstalled_zeroScenarios`       | < 50 ns            | Empty registry fast path                                                     |
| `agentInstalled_oneScenario_noMatch` | < 150 ns           | Regex non-match against URL string                                           |
| `agentInstalled_oneMatch_noEffect`   | < 300 ns           | `any()` URL match, zero-probability                                          |
| `sessionIdMiss`                      | < 20 ns additional | Session scope short-circuit                                                  |
| `tenScenarios_oneMatch`              | < 1 µs             | Linear scan, operation-type filter dominant                                  |

The HTTP `oneScenario_noMatch` target is 50 ns higher than the JDBC equivalent (150 ns vs 100 ns) because the regex evaluation against a URL string is measurably more expensive than a regex evaluation against a short SQL verb string (`"SELECT 1"`). The URL string `"https://example.com/api/v1/orders"` is 36 characters; `"SELECT \\s+\\*\\s+FROM\\s+no_match_table"` must scan farther before it fails than a short SQL non-match.

### 4.4 The `baseline_noAgent` Difference

The HTTP baseline is `bh.consume(URL.length())` rather than an executor call, because the HTTP benchmark has no natural "zero-agent equivalent" of an HTTP call — the actual OkHttp or Apache HC call would dominate the measurement. `URL.length()` is a deterministic, O(1) operation on an interned `String`. Its cost is essentially a pointer dereference and an `int` load — 1–3 ns. This is the measurement floor for the HTTP benchmark class.

---

## 5. How to Interpret Results

### 5.1 Reading the JMH Output

JMH outputs a table similar to:

```
Benchmark                                        Mode  Cnt    Score    Error  Units
ChaosRuntimeBenchmark.baseline_noAgent           avgt    5    2.341 ±  0.012  ns/op
ChaosRuntimeBenchmark.agentInstalled_zeroSc...   avgt    5   38.412 ±  1.203  ns/op
ChaosRuntimeBenchmark.agentInstalled_oneSc...    avgt    5   87.819 ±  2.441  ns/op
ChaosRuntimeBenchmark.agentInstalled_oneM...     avgt    5  241.330 ±  9.118  ns/op
ChaosRuntimeBenchmark.sessionIdMiss              avgt    5   52.104 ±  1.877  ns/op
ChaosRuntimeBenchmark.tenScenarios_oneMatch      avgt    5  618.271 ± 18.345  ns/op
```

`Score` is the average time per operation in nanoseconds. `Error` is the 99.9% confidence interval half-width across the five measurement iterations. A large `Error` relative to `Score` (more than 20%) indicates high variance — usually caused by JIT deoptimisation, GC activity, or thermal throttling. Re-run with `-prof gc` to check if GC is contributing.

The `Cnt` column shows how many sample points were collected (iterations × JMH internal samples per iteration). For 1-second measurement iterations, `Cnt = 5` means five 1-second windows.

### 5.2 What "< 50 ns" Means in Context

A 50 ns agent overhead budget for the zero-scenario path is:
- Below one thread park (~200–500 ns on Linux with no contention)
- Below half a CPU cache-line fill (~64 bytes at ~100 ns on a cold L3 miss)
- 0.25% of a 20 µs loopback TCP packet round-trip
- 0.5% of a 10 µs HikariCP connection borrow
- Unmeasurable against a 1 ms network call or a 10 ms database query

At these scales, the agent overhead in the zero-scenario path is below the precision floor of any business-logic latency SLO. An SLA requiring p99 < 100 ms for a database call will not be affected by 50 ns of agent overhead.

### 5.3 Measurement Scope and What Is Not Covered

The benchmarks measure the **dispatch fast path only**. They do not measure:

- **Scenario activation cost**: `ChaosRuntime.activate()` performs a `CopyOnWriteArrayList` copy. This is a one-time cost per activation, not a per-call cost.
- **Session open/close cost**: `ChaosRuntime.openSession()` and `ChaosSession.close()` touch `ScopeContext`, which uses `InheritableThreadLocal`. These are test-lifecycle operations.
- **Effect execution cost**: When a `DelayEffect` fires, the benchmark measures `Thread.sleep()` latency which dominates everything else. These are intentional delays, not overhead.
- **`BootstrapDispatcher` bridge overhead**: The `MethodHandle.invoke()` through `ChaosBridge` to `ChaosDispatcher` adds ~5–15 ns at JIT saturation. This is not included in the benchmark measurements but is included in any production callsite.
- **ByteBuddy advice bytecode cost**: The inlined advice method (`@Advice.OnMethodEnter`) adds a few bytecode instructions to the instrumented method's prolog. This is a single-digit-nanosecond cost for the null-check and call. It is not measured by these benchmarks.

### 5.4 JVM Flags That Affect Results

**`-XX:+PrintCompilation`**: Append to `--args` to see JIT compilation events. If compilation events appear during measurement iterations (identified by the timestamp column), increase `@Warmup(iterations = 5)` to push compilation into the warmup phase.

**`-prof gc`**: Adds GC pause and allocation rate columns to the output. If `alloc/op` is non-zero for the zero-scenario path, a `String` or `InvocationContext` allocation is escaping the stack. This would indicate a JIT escape analysis failure — uncommon but possible after a deoptimisation.

**`-prof async`**: Uses async-profiler to produce a flamegraph of the hot path. Requires async-profiler on the classpath. The most illuminating result is the zero-scenario flamegraph: at JIT saturation it should show `beforeJdbcStatementExecute` → `evaluateJdbc` → `registry.match` → `isEmpty()` return as the dominant path with `contributions.isEmpty()` visible at the top.

**`-XX:-TieredCompilation`**: Forces all methods to compile directly to C2, skipping C1 intermediate compilation. This makes warmup slower but gives a cleaner measurement of fully-optimised code. Useful when comparing results across JVM versions.

---

## 6. `ChaosDispatcher` vs `ChaosRuntime` — The Post-3.2 Refactor

Prior to the 3.2 refactor, `ChaosRuntime` was a monolithic class containing both control-plane methods (scenario activation, session management, diagnostics) and hot-path dispatch methods. Profiling a benchmark that called `ChaosRuntime.beforeJdbcStatementExecute()` was obstructed by the presence of control-plane methods in the same class — the JIT's inline budget for a class is finite, and the control-plane methods competed with the hot-path methods for inlining headroom.

The 3.2 refactor split `ChaosRuntime` into:

- `ChaosControlPlaneImpl` — owns scenario lifecycle, session management, `ScenarioRegistry` construction, diagnostics, event listeners, and the `Instrumentation` handle
- `ChaosDispatcher` — owns all `before*` / `after*` / `adjust*` / `decorate*` dispatch methods

`ChaosRuntime` is now a thin facade that delegates to both components. `ChaosBridge` (the bootstrap bridge delegate) calls `ChaosRuntime` methods, which forward to the appropriate component.

The benchmarks reference `ChaosDispatcher` directly:

```java
@Setup(Level.Trial)
public void setup() {
    runtime = new ChaosRuntime();
    runtime.activate(/* scenario */);
    dispatcher = runtime.dispatcher();
}

@Benchmark
public void agentInstalled_zeroScenarios(ZeroScenariosState state, Blackhole bh)
        throws Throwable {
    bh.consume(state.dispatcher.beforeJdbcStatementExecute("SELECT 1"));
}
```

Calling `state.dispatcher.beforeJdbcStatementExecute()` instead of `state.runtime.beforeJdbcStatementExecute()` removes the `ChaosRuntime` facade delegation layer from the measurement. The JIT no longer needs to inline through the facade; it can inline `ChaosDispatcher.beforeJdbcStatementExecute` → `evaluateJdbc` → `registry.match` in a single inlining chain without interference from control-plane methods.

This makes the benchmark results cleaner and more stable across JMH runs: the C2 inlining decisions for the hot path are no longer dependent on which control-plane methods happened to be compiled first in the same JVM fork.

The `ChaosRuntime.dispatcher()` accessor is `public` and returns the live `ChaosDispatcher` instance. External callers (test code, benchmarks, integration shims) that need direct access to the dispatch layer without going through the facade should use this accessor.

---

---

## 7. `Phase4DispatchBenchmark` — Thread · DNS · SSL · File I/O Hot Paths

### 7.1 What It Measures

`Phase4DispatchBenchmark` measures the `ChaosDispatcher` hot path for the four Phase 4 operation
types — `THREAD_SLEEP`, `DNS_RESOLVE`, `SSL_HANDSHAKE`, `FILE_IO_READ`, and `FILE_IO_WRITE` — that
were added in the roadmap 4.x instrumentation wave. The benchmark entry points map to:

| Benchmark family  | Dispatcher method                       | Instrumented JDK target                                             |
|-------------------|-----------------------------------------|---------------------------------------------------------------------|
| `threadSleep_*`   | `beforeThreadSleep(long millis)`        | `Thread.sleep(long)`                                                |
| `dnsResolve_*`    | `beforeDnsResolve(String hostname)`     | `InetAddress.getByName(String)`                                     |
| `sslHandshake_*`  | `beforeSslHandshake(Object engine)`     | `SSLEngineImpl.beginHandshake()` / `SSLSocketImpl.startHandshake()` |
| `fileIoRead_*`    | `beforeFileIo("FILE_IO_READ", Object)`  | `FileInputStream.read()`                                            |
| `fileIoWrite_*`   | `beforeFileIo("FILE_IO_WRITE", Object)` | `FileOutputStream.write(int)`                                       |

Like the JDBC and HTTP benchmarks in sections 3–4, the measurement does **not** include
`BootstrapDispatcher` bridge overhead (~5–15 ns) or ByteBuddy advice prolog cost. It isolates the
`ChaosDispatcher` evaluation logic: registry lookup, selector evaluation, and activation-policy check.

### 7.2 Three Scenario States

Each operation type is benchmarked against three states, yielding 15 benchmarks (5 types × 3 states):

**`ZeroScenariosState`** — the runtime is live but the registry is empty. This is the theoretical
lower bound for any installed agent. Dispatch terminates at the first `registry.isEmpty()` check.

**`OneScenarioNoMatchState`** — one JDBC scenario is active. All Phase 4 dispatch calls miss it on
`operationType` mismatch before any pattern evaluation. This isolates the cost of a non-empty
registry with a guaranteed early reject.

**`FourScenariosExhaustedState`** — one scenario per Phase 4 operation type, each activated with
`maxApplications=1`. During `@Setup`, each scenario is consumed once, exhausting it. The measurement
loop therefore hits the "full selector evaluation + exhaustion check" path — the most expensive
*non-firing* steady state in production (scenarios that have reached their application cap but still
sit in the registry until the handle is stopped).

### 7.3 Measured Results

Run on OpenJDK 25.0.1 (Apple Silicon), `-f 0 -wi 2 -i 3 -bm avgt -tu ns`:

```
Benchmark                                              Mode  Cnt    Score    Error  Units
──────────────────────────────────────────────────────────────────────────────────────────
Phase4DispatchBenchmark.dnsResolve_zeroScenarios        avgt    3   56.0 ±  13.1  ns/op
Phase4DispatchBenchmark.fileIoRead_zeroScenarios        avgt    3   58.9 ±   5.0  ns/op
Phase4DispatchBenchmark.fileIoWrite_zeroScenarios       avgt    3   58.9 ±   0.7  ns/op
Phase4DispatchBenchmark.sslHandshake_zeroScenarios      avgt    3   57.7 ±   4.5  ns/op
Phase4DispatchBenchmark.threadSleep_zeroScenarios       avgt    3   55.8 ±   2.0  ns/op

Phase4DispatchBenchmark.dnsResolve_oneScenarioNoMatch   avgt    3   89.4 ±   6.4  ns/op
Phase4DispatchBenchmark.fileIoRead_oneScenarioNoMatch   avgt    3   85.8 ±   2.0  ns/op
Phase4DispatchBenchmark.fileIoWrite_oneScenarioNoMatch  avgt    3   85.7 ±  11.0  ns/op
Phase4DispatchBenchmark.sslHandshake_oneScenarioNoMatch avgt    3   85.1 ±   0.9  ns/op
Phase4DispatchBenchmark.threadSleep_oneScenarioNoMatch  avgt    3   82.5 ±   2.1  ns/op

Phase4DispatchBenchmark.dnsResolve_fourScenariosExh.    avgt    3  306.2 ±   8.1  ns/op
Phase4DispatchBenchmark.fileIoRead_fourScenariosExh.    avgt    3  333.4 ±  24.3  ns/op
Phase4DispatchBenchmark.fileIoWrite_fourScenariosExh.   avgt    3  120.8 ±   4.4  ns/op
Phase4DispatchBenchmark.sslHandshake_fourScenariosExh.  avgt    3  325.8 ±   3.0  ns/op
Phase4DispatchBenchmark.threadSleep_fourScenariosExh.   avgt    3  332.9 ±   8.9  ns/op
```

### 7.4 Practical Throughput Impact — What the Agent Costs You

Numbers only matter in context. The question a developer needs answered is not "how many
nanoseconds?" but "how many fewer operations per second does my service handle?"

The analysis below takes two representative operations — a 1 KB page-cache file read (the fastest
realistic File I/O workload) and a DNS resolution — and expresses the agent overhead as lost
throughput.

#### A Word on What These Numbers Actually Represent

Before reading the tables below, understand what the extreme cases require — and why they are
rarely if ever reached in production.

**"1 KB file read, page cache hot"** assumes the same file page lives permanently in the Linux
page cache. In a containerised environment (Kubernetes, Docker) with memory limits and multiple
co-located services competing for RAM, the page cache is continuously evicted under pressure.
A file read that hits the page cache in a dedicated VM takes ~1 µs; the same read after eviction
triggers a page fault and costs 50–500 µs depending on storage latency. The hot-cache scenario
is realistic only for a file that is accessed thousands of times per second by the same process —
e.g., a hot configuration file or a frequently-read binary resource. Reading one million distinct
1 KB files per second guarantees that none of them are cached.

**"DNS lookup, JVM cache hot, 2M/sec"** requires the same hostname to be resolved 2 million times
per second by the same JVM process. The JVM `InetAddress` cache makes this a pure HashMap lookup
after the first resolution — but a workload that loops on `InetAddress.getByName("same-host")`
two million times per second is not a DNS workload, it is a benchmark. A real service resolves
distinct hostnames at 100–5 000/sec. Most of those resolve from the JVM cache within its TTL,
making the per-lookup cost ~500 ns — but the total DNS work in a busy service is measured in
microseconds per second, not milliseconds.

**In both cases, the extreme scenario is constructed to make the agent overhead look as large
as possible relative to the baseline.** The practical section below uses realistic call rates
instead.

---

#### Reference: Baseline Operation Costs

| Operation                                                       | Realistic cost           | Note                                                             |
|-----------------------------------------------------------------|--------------------------|------------------------------------------------------------------|
| `FileInputStream.read()`, 1 KB, page cache hot                  | ~1 000 ns (1 µs)         | Only if the same file page is accessed repeatedly by one process |
| `FileInputStream.read()`, 1 KB, container under memory pressure | ~50 000–200 000 ns       | Page cache evicted; kernel fetches from storage                  |
| `InetAddress.getByName()`, JVM cache hit                        | ~500 ns                  | JVM-internal HashMap; no OS call; requires same host, within TTL |
| `InetAddress.getByName()`, real DNS via local resolver          | ~500 000 ns (500 µs)     | UDP to a caching resolver; cache miss at OS level                |
| SSL/TLS handshake (TLS 1.3)                                     | ~1 000 000–10 000 000 ns | Asymmetric crypto; ~100–1 000 handshakes/sec per core maximum    |

---

#### Scenario A — File I/O Read (1 KB, page cache hot — stress test)

This is the worst-case scenario for the agent: the intercepted operation is as cheap as a syscall
can be, and the registry contains exhausted scenarios. Real world applicability is low, but it
defines the ceiling.

**Baseline maximum throughput (no agent):**
1 000 000 000 ns / 1 000 ns per read = **1 000 000 reads/sec**

| Active chaos scenarios                  | Cost per read          | Max reads/sec   | Throughput loss   |
|-----------------------------------------|------------------------|-----------------|-------------------|
| 0 (agent installed, no scenarios)       | 1 000 + 58 = 1 058 ns  | 945 180         | **−5.5 %**        |
| 1 scenario, different type (type-miss)  | 1 000 + 87 = 1 087 ns  | 919 960         | **−8.0 %**        |
| 1 scenario, FILE_IO_READ, exhausted     | 1 000 + 333 = 1 333 ns | 750 188         | **−25.0 %**       |
| 4 scenarios, all exhausted (worst case) | 1 000 + 333 = 1 333 ns | 750 188         | **−25.0 %**       |

> **The −25% figure is the maximum realistic worst case for File I/O dispatch overhead.**
> It requires: page-cache-hot reads (rare in containers), exhausted scenarios left resident in the
> registry (avoidable with `ChaosActivationHandle.stop()`), and an application doing nothing but
> reading files at maximum speed. Remove exhausted scenarios and this drops to −5.5% or better.

---

#### Scenario B — DNS Resolution, JVM Cache Hot (stress test)

The agent intercepts before the JVM cache check, so even a cached lookup pays the full dispatch
cost. This produces the highest relative impact of any Phase 4 path — because the baseline
operation is also a HashMap lookup, roughly the same complexity as the dispatch itself.

**Baseline maximum throughput (no agent, JVM cache hot):**
1 000 000 000 ns / 500 ns per lookup = **2 000 000 lookups/sec**

| Active chaos scenarios                 | Cost per lookup    | Max lookups/sec   | Throughput loss  |
|----------------------------------------|--------------------|-------------------|------------------|
| 0 (agent installed, no scenarios)      | 500 + 56 = 556 ns  | 1 798 561         | **−10.1 %**      |
| 1 scenario, different type (type-miss) | 500 + 89 = 589 ns  | 1 697 793         | **−15.1 %**      |
| 1 scenario, DNS_RESOLVE, exhausted     | 500 + 306 = 806 ns | 1 240 695         | **−37.9 %**      |

2 million DNS lookups per second on the same host from one JVM is not DNS traffic — it is a tight
loop. In practice, achieving this rate requires the application to do essentially nothing else.

---

#### Scenario C — DNS Resolution, Real Network (practical case)

When the hostname is not in the JVM cache and the OS resolver is queried, the operation costs
~500 µs.

**Baseline maximum throughput:** 1 000 000 000 ns / 500 000 ns = **2 000 lookups/sec**

| Active chaos scenarios                  | Cost per lookup   | Max lookups/sec   | Throughput loss  |
|-----------------------------------------|-------------------|-------------------|------------------|
| 0 (agent installed, no scenarios)       | 500 000 + 56 ns   | 1 999.8           | **−0.01 %**      |
| 4 scenarios, all exhausted (worst case) | 500 000 + 306 ns  | 1 998.8           | **−0.06 %**      |

The agent is invisible. The same applies to SSL handshakes (1–10 ms) and any `Thread.sleep` call
by definition. **Any operation that crosses a network boundary makes the dispatch overhead
immeasurable.**

---

#### Scenario D — Realistic Mixed Microservice

A typical Java HTTP microservice handling 2 000 requests/sec with the following per-request
profile:

| Operation                                                        | Calls/request   | Total calls/sec   |
|------------------------------------------------------------------|-----------------|-------------------|
| File reads (config, templates, classpath resources)              | 2               | 4 000             |
| DNS resolutions (distinct upstream hostnames, mostly JVM-cached) | 0.5             | 1 000             |
| SSL handshakes (TLS keep-alive; connections reused)              | 0.05            | 100               |
| Thread.sleep (retry backoff, health check timers)                | 0.1             | 200               |
| **Total intercepted calls/sec**                                  |                 | **5 300**         |

**Total agent overhead — no chaos scenarios active:**
5 300 calls/sec × 58 ns/call = **307 400 ns = 0.31 ms per second**

A service spending 2 000 req/sec × say 5 ms average latency = 10 000 ms of CPU time per second.
The agent costs **0.31 ms out of 10 000 ms — 0.003% of one CPU core.**

**Total agent overhead — 4 exhausted scenarios, worst-case paths:**
- 4 000 file reads × 333 ns = 1 332 000 ns
- 1 000 DNS lookups × 306 ns = 306 000 ns  
- 100 SSL handshakes × 326 ns = 32 600 ns
- 200 thread sleeps × 333 ns = 66 600 ns
- **Total: 1 737 200 ns = 1.74 ms per second**

1.74 ms out of 10 000 ms = **0.017% of one CPU core.**

Even with four exhausted scenarios sitting in the registry across all operation types, the chaos
agent consumes less than two hundredths of one percent of one CPU core in a realistic microservice
workload. The dominant cost is always the business logic, the I/O, and the network — not the
dispatch overhead.

**The one rule that matters:** call `ChaosActivationHandle.stop()` when a scenario is done.
An exhausted scenario costs as much to evaluate as one that is actively firing, and delivers
nothing in return. Everything else is noise.

---

#### Summary: When Agent Overhead Actually Matters

| Situation                                                 | Impact                            | Verdict                              |
|-----------------------------------------------------------|-----------------------------------|--------------------------------------|
| Agent installed, no active scenarios                      | −5–10% throughput on cheapest ops | Acceptable; unavoidable              |
| Real network operations (DNS query, SSL, HTTP)            | < 0.1% on any load                | Negligible                           |
| Cheap ops (page-cache reads) + active/exhausted scenarios | −8–25% on that specific call type | Manageable; stop exhausted scenarios |
| Typical mixed microservice, any scenario count            | < 0.02% total CPU                 | Irrelevant                           |
| Pathological: same-host JVM-cached DNS at 2M/sec          | −10–38% on that loop              | Artificial; not a real workload      |

### 7.5 Design Targets and Compliance

| Path                        | Target   | Measured     | Compliant   |
|-----------------------------|----------|--------------|-------------|
| Empty registry              | < 100 ns | 55.8–58.9 ns | ✓           |
| One mismatching scenario    | < 150 ns | 82.5–89.4 ns | ✓           |
| Four exhausted (type-miss)  | < 200 ns | 120.8 ns     | ✓           |
| Four exhausted (type-match) | < 500 ns | 306–333 ns   | ✓           |

All four paths meet their design targets. The exhausted-type-match path (306–333 ns) has the largest
error bar for `fileIoRead` (±24 ns), attributable to `AtomicLong` memory-fence variance under
macOS's ARM memory model. On x86 Linux (production target), variance is expected to be lower.

### 7.6 Operational Guidance

**Remove exhausted scenarios.** The most actionable result from this benchmark is that an exhausted
scenario sitting in the registry costs 250 ns more per matching call than a scenario that was never
there. `ChaosActivationHandle.stop()` removes a scenario from the live registry in O(n) time with a
single `CopyOnWriteArrayList` copy. Call it as soon as `maxApplications` has been reached and the
scenario is no longer needed.

**Keep selector types narrow.** A `FileIoSelector(READ_ONLY)` scenario adds ~35 ns to write calls
(type-mismatch reject) but 0 ns overhead beyond that to DNS, SSL, and thread operations (they fail
at operation-type level before even reaching the file-type check). Wide selectors that cover multiple
operation types increase the per-call cost across more code paths.

**Benchmark with `-f 1` for publication.** The results above use `-f 0` (no fork) for speed. For
published benchmarks, use `-f 1 -wi 3 -i 5` to isolate JIT state across classes and collect tighter
confidence intervals.

---

<div align="center">

*Architecture, implementation, and documentation crafted with Love and Passion by*

**[Christian Schnapka](https://macstab.com)**
Embedded Principal+ Engineer
[Macstab GmbH](https://macstab.com) · Hamburg, Germany

*Building systems that operate correctly at the edges — including the ones you deliberately break.*

</div>
