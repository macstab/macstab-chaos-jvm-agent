<!--
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Engineered by  Christian Schnapka
                 Embedded Principal+ Engineer
                 Macstab GmbH · Hamburg, Germany
                 https://macstab.com
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-->

# Roadmap — macstab-chaos-jvm-agent

> Path from 0.1.0-SNAPSHOT → production-grade 1.0.0 on Maven Central.

---

## Status Legend

| Symbol | Meaning |
|--------|---------|
| ✅ | Done |
| 🔄 | In progress |
| ⬜ | Not started |
| 🔴 | Blocked |

---

## Phase 1 — Correctness and Safety

> *No new features. Make what exists airtight.*

### ✅ 1.1 DeadlockStressor / ThreadLeakStressor Safeguard
**Priority: immediate — 2 hours**

`deadlock()` and `threadLeak()` create non-recoverable JVM state. Currently nothing prevents
accidental activation in long-lived processes.

- Add `allowDestructiveEffects(boolean)` flag to `ActivationPolicy`
- `CompatibilityValidator` throws `ChaosActivationException` unless flag is explicitly set
- Affects: `DeadlockStressor`, `ThreadLeakStressor`

---

### ✅ 1.2 Clock Skew — JVM Limitation Documented
**Investigated and documented — 1 day**

**Finding: direct `System.currentTimeMillis()` interception is not feasible via a standard
Java instrumentation agent.** Two independent JVM constraints block every known approach:

1. **Retransformation cannot add methods or change native modifiers** — JVMTI `SetNativeMethodPrefix`
   requires (a) adding a renamed native method and (b) removing the `native` modifier from the
   original. Both are prohibited under JVM retransformation restrictions.
   `java.lang.System` is loaded before `premain` runs, so class-load-time interception is
   unavailable.

2. **`@IntrinsicCandidate` JIT bypass** — HotSpot C2 recognises `java.lang.System.currentTimeMillis`
   by class+method name and emits a direct `RDTSC` / `MRS CNTVCT_EL0` hardware read after
   ~10 000 invocations, bypassing any Java-level wrapper that might exist.

**What was done:**
- Investigated `AgentBuilder.enableNativeMethodPrefix("$chaos$")` + `RETRANSFORMATION` empirically.
  ByteBuddy fires a TRANSFORM event but the JVM silently retains the original native binding;
  `currentTimeMillis()` remains native. No error is surfaced.
- Added thorough documentation of both constraints in `JdkInstrumentationInstaller.java`.
- `Can-Set-Native-Method-Prefix: true` remains in the manifest for future use with
  non-bootstrap, non-pre-loaded classes.

**Clock skew works via two supported paths today:**
- Code explicitly wired through `BootstrapDispatcher.adjustClockMillis` / `adjustClockNanos`.
- `java.time.Instant.now()` and higher-level APIs (see item 1.4 below).

**Hard limitation:** workarounds require `-Xpatch:java.base` or a C-level JVMTI agent — out of scope.

### ✅ 1.4 Clock Skew — Higher-Level Java Time API Interception
**Completed — 1 day**

Partial clock skew coverage via non-native, non-intrinsified Java methods:

- `java.time.Instant.now()` — calls `Clock.systemUTC().instant()`; regular Java, instrumentable
- `java.time.LocalDateTime.now()` / `ZonedDateTime.now()` — similar
- `java.util.Date()` constructor — calls `System.currentTimeMillis()` internally but is a
  regular constructor that ByteBuddy can wrap

These intercept the most common modern Java time usage. Direct raw `System.currentTimeMillis()`
calls remain outside reach (documented in 1.2).

**What was done:**
- Added `OperationType` values: `INSTANT_NOW`, `LOCAL_DATE_TIME_NOW`, `ZONED_DATE_TIME_NOW`,
  `DATE_NEW`. Each is documented and wired into `ChaosSelector.JvmRuntimeSelector` without
  additional selector validation (the selector already accepts arbitrary JVM-runtime ops).
- `ChaosRuntime.applyClockSkew` now treats all four as millisecond-channel operations;
  `SYSTEM_CLOCK_NANOS` remains the only nanosecond-channel operation. New runtime entry points
  `adjustInstantNow`, `adjustLocalDateTimeNow`, `adjustZonedDateTimeNow`, `adjustDateNew`
  preserve sub-millisecond precision (via `Instant.plusMillis(delta)`), zone metadata (for
  `ZonedDateTime`), and default-zone semantics (for `LocalDateTime`).
- Four new ByteBuddy advice classes in `JvmRuntimeAdvice` (`InstantNowAdvice`,
  `LocalDateTimeNowAdvice`, `ZonedDateTimeNowAdvice`, `DateNewAdvice`), registered in the
  Phase 2 block of `JdkInstrumentationInstaller`. `DateNewAdvice` uses `@Advice.This Object`
  with an internal cast to avoid a `ClassCircularityError` on `java.util.Date` during premain
  logging (`java.util.logging.SimpleFormatter` constructs a `Date` while scenarios activate).
- Bootstrap bridge grew from 42 to 46 slots: `ADJUST_INSTANT_NOW`, `ADJUST_LOCAL_DATE_TIME_NOW`,
  `ADJUST_ZONED_DATE_TIME_NOW`, `ADJUST_DATE_NEW`.
- Tests added: `ClockSkewRuntimeTest$HigherLevelTimeApis` covers FIXED skew on `Instant.now`,
  `LocalDateTime.now`, `ZonedDateTime.now` (zone preservation), `Date`, FREEZE mode on
  `Instant.now`, isolation between `INSTANT_NOW` and `DATE_NEW`, and passthrough when no
  scenario matches. `ChaosBridgeTest` and `BootstrapDispatcherTest` gained no-scenario
  passthrough coverage for the four new entry points. `JdkInstrumentationInstallerTest`
  continues to assert every handle slot resolves (now 46).

---

### ✅ 1.3 Missing Concurrency Tests
**Completed — 1 day**

- `TwoScenariosConflictingTerminalActionsTest` — precedence merge (serial + 80-thread concurrent); delays accumulate, higher-precedence terminal action always wins
- `StopDuringEvaluationTest` — `handle.stop()` transitions state before any subsequent evaluate(); no exception when stop() races with evaluate() across 60 threads; state never reverts from STOPPED
- `RateLimitUnderHighConcurrencyTest` — 100 threads, `appliedCount` never exceeds permit count; exactly N permits consumed when N < thread count; `maxApplications` caps below `rateLimit` when tighter
- `SessionIsolationParallelTest` — 10 sessions × 10 threads × 5 iterations; each session's appliedCount == 50 with zero cross-session bleed; unbound threads invisible to all session scenarios; post-close effect stops immediately

---

## Phase 2 — Ecosystem Integration

> *Drop-in support for every Java backend stack.*

### ✅ 2.1 Spring Boot Test Starter
**Priority: high — 2 days**

Two new modules, one per Spring Boot major: `chaos-agent-spring-boot3-test-starter` and
`chaos-agent-spring-boot4-test-starter`. Both expose the same contract, compiled against the
corresponding Spring Boot BOM and wired to the version-specific autoconfiguration mechanism.

**What was done:**

Modules:
- `chaos-agent-spring-boot3-test-starter` — compiled against `spring-boot-dependencies:3.5.13`
  (latest stable 3.x at release time); `Automatic-Module-Name`
  `com.macstab.chaos.jvm.agent.spring.boot3.test`; package
  `com.macstab.chaos.jvm.spring.boot3.test`.
- `chaos-agent-spring-boot4-test-starter` — compiled against `spring-boot-dependencies:4.0.5`
  (latest stable 4.x at release time); `Automatic-Module-Name`
  `com.macstab.chaos.jvm.agent.spring.boot4.test`; package
  `com.macstab.chaos.jvm.spring.boot4.test`.

Spring Boot dependencies are declared `compileOnly` so the starters are inert at production
runtime: the user's Spring Boot application supplies Spring itself on the test classpath.

Each module ships:
- `@ChaosTest` — meta-annotation wrapping `@SpringBootTest` and
  `@ExtendWith(ChaosAgentExtension.class)`. Forwards `properties`, `classes`, `webEnvironment`,
  `args`, and `initializers` attributes so callers can configure `@SpringBootTest` through a
  single annotation.
- `ChaosAgentExtension` — JUnit 5 extension implementing `BeforeAllCallback`, `AfterAllCallback`,
  and `ParameterResolver`. Calls `ChaosPlatform.installLocally()` (idempotent) in `beforeAll`,
  opens a class-scoped `ChaosSession` stored in `ExtensionContext.Store`, and closes it in
  `afterAll`. The resolver walks parent contexts so `@Nested` classes inherit the session.
- `ChaosTestAutoConfiguration` — `@TestConfiguration(proxyBeanMethods = false)` guarded by
  `@ConditionalOnClass(ChaosAgentExtension.class)` that exposes `ChaosControlPlane` as a bean
  (`@ConditionalOnMissingBean`).
- `ChaosAgentInitializer` — `ApplicationContextInitializer<ConfigurableApplicationContext>` that
  installs the agent before context refresh and registers the live control plane as the
  `chaosControlPlane` singleton in the bean factory.

Autoconfiguration registration (different mechanism per Boot major):
- Boot 3:
  `META-INF/spring/org.springframework.boot.test.autoconfigure.ImportAutoConfiguration.imports`
  for the auto-configuration; classic `META-INF/spring.factories` for the
  `ApplicationContextInitializer`.
- Boot 4: same `.imports` for auto-configuration; the initializer is registered via
  `META-INF/spring/org.springframework.context.ApplicationContextInitializer.imports` (Boot 4
  replaces `spring.factories` with `.imports` files for most factory mechanisms).

Tests (per module): `ChaosAgentExtensionTest` exercises the extension directly (no Spring context
is required to verify the extension contract itself): parameter injection of `ChaosSession` and
`ChaosControlPlane`, session being open and `bind()` usable during tests, stable session identity
across methods within a class, and clean `@Nested` + `@TestInstance(PER_CLASS)` lifecycle.

Usage:
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

---

### ✅ 2.2 Spring Boot Runtime Starter
**Priority: medium — 3 days**

Two new modules, one per Spring Boot major: `chaos-agent-spring-boot3-starter` and
`chaos-agent-spring-boot4-starter`. Both compiled `compileOnly` against the corresponding Spring
Boot BOM so the starters are inert at production runtime until the host application supplies
Spring Boot itself.

The runtime starters target deployment-time chaos engineering: pre-production soak tests, game
days, and controlled production experiments. They expose no test APIs — only externally driven
activation via Actuator or startup config — and are intentionally separate from the `2.1` test
starters to prevent blast-radius risk.

**What was done:**

Modules:
- `chaos-agent-spring-boot3-starter` — compiled against `spring-boot-dependencies:3.5.13`;
  `Automatic-Module-Name` `com.macstab.chaos.jvm.agent.spring.boot3`; package
  `com.macstab.chaos.jvm.spring.boot3`.
- `chaos-agent-spring-boot4-starter` — compiled against `spring-boot-dependencies:4.0.5`;
  `Automatic-Module-Name` `com.macstab.chaos.jvm.agent.spring.boot4`; package
  `com.macstab.chaos.jvm.spring.boot4`.

Each module ships:
- `ChaosProperties` — `@ConfigurationProperties("macstab.chaos")` with `enabled=false`,
  `configFile=null`, `debugDumpOnStart=false`, and a nested `actuator` block with
  `enabled=false`. All toggles default off so the starter is inert unless operators opt in.
- `ChaosAutoConfiguration` — `@AutoConfiguration` gated by
  `@ConditionalOnProperty("macstab.chaos.enabled", havingValue="true")`; publishes a
  `ChaosControlPlane` bean (via `ChaosPlatform.installLocally()`) with `destroyMethod = "close"`
  so JVM-scoped scenarios are released when the `ApplicationContext` closes, plus a
  `ChaosHandleRegistry` and an `ApplicationListener<ApplicationReadyEvent>` that loads the
  optional startup plan file and emits the diagnostics dump. Every bean is
  `@ConditionalOnMissingBean` so a user-supplied implementation backs the auto-configuration
  off.
- `ChaosActuatorEndpoint` — `@Endpoint(id="chaos")` exposed at `/actuator/chaos` via a nested
  `ActuatorConfiguration` gated by `@ConditionalOnClass(Endpoint.class)` and
  `@ConditionalOnProperty("macstab.chaos.actuator.enabled", havingValue="true")`. Operations:
  - `@ReadOperation` → `GET /actuator/chaos` returns
    `ChaosDiagnostics.Snapshot` as JSON.
  - `@WriteOperation` → `POST /actuator/chaos/activate` reads a JSON-encoded `ChaosPlan` from
    the request body and activates it, registering the handle with the local
    `ChaosHandleRegistry`.
  - `@DeleteOperation` with `@Selector String scenarioId` → `DELETE /actuator/chaos/{scenarioId}`
    stops a running scenario by ID.
  - `@WriteOperation` → `POST /actuator/chaos/stopAll` stops every scenario tracked by the local
    handle registry. Responses are typed record wrappers (`ActivationResponse`, `StopResponse`,
    `StopAllResponse`) instead of raw strings.
- `ChaosHandleRegistry` — thread-safe map of activation handles so the endpoint can stop
  scenarios by ID without reaching into core-internal registries.

Autoconfiguration registration (same mechanism for both Boot majors):
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

Tests (per module):
- `ChaosPropertiesTest` verifies the default values (`enabled=false`, `actuator.enabled=false`,
  `configFile=null`, `debugDumpOnStart=false`).
- `ChaosAutoConfigurationTest` uses `ApplicationContextRunner` to verify:
  - the `ChaosControlPlane` bean is present when `macstab.chaos.enabled=true`;
  - the `ChaosHandleRegistry` bean is present when enabled;
  - no `ChaosControlPlane` bean exists by default or when `enabled=false`;
  - a user-defined `ChaosControlPlane` bean backs off the auto-configuration
    (`@ConditionalOnMissingBean`).
- `ChaosActuatorEndpointTest` uses `ApplicationContextRunner` with a stub `ChaosControlPlane`
  bean to verify:
  - the endpoint bean is present when `macstab.chaos.actuator.enabled=true` and absent
    otherwise;
  - `snapshot()` returns a non-null diagnostics snapshot;
  - `stop()` reports `not-found` for unknown IDs; `stopAll()` returns zero when no handles are
    tracked.

Configuration:
```yaml
macstab:
  chaos:
    enabled: true                          # explicit opt-in required
    config-file: /etc/chaos/plan.json      # optional startup plan
    debug-dump-on-start: false
    actuator:
      enabled: true                        # Actuator endpoint, off by default
```

**Security note**: The Actuator endpoint is protected by Spring Security if present. Operators
must not expose `/actuator/chaos` to the public internet without authentication.

---

### ✅ 2.3 HTTP Client Selectors
**Priority: high — 5 days**

New `OperationType` values: `HTTP_CLIENT_SEND`, `HTTP_CLIENT_SEND_ASYNC`

New selector: `ChaosSelector.httpClient(Set<OperationType>)` and
`ChaosSelector.httpClient(Set<OperationType>, NamePattern urlPattern)`.

Instrumentation targets wired via `instrumentOptional` (no hard runtime dependency):

| Client | Target class | Method |
|--------|-------------|--------|
| OkHttp 3/4 | `okhttp3.RealCall` | `execute()`, `enqueue()` |
| Apache HC 4.x | `org.apache.http.impl.client.CloseableHttpClient` | `execute(HttpHost, HttpRequest)` |
| Apache HC 5.x | `org.apache.hc.client5.http.impl.classic.CloseableHttpClient` | `execute(ClassicHttpRequest, HttpClientResponseHandler)` |
| Spring WebClient | `reactor.netty.http.client.HttpClientConnect` | `connect()` |

Pattern matching on URL via `targetName` field of `InvocationContext`, populated reflectively by
`HttpUrlExtractor` so that the agent never hard-links against the HTTP client libraries.

**What was done**

- `OperationType` gained `HTTP_CLIENT_SEND` and `HTTP_CLIENT_SEND_ASYNC` entries with targeted
  JavaDoc and the `ChaosSelector.HttpClientSelector` pairing documented.
- New sealed-interface record `ChaosSelector.HttpClientSelector(Set<OperationType> operations,
  NamePattern urlPattern)` plus factory methods `httpClient(operations)` and
  `httpClient(operations, urlPattern)` with Jackson polymorphic registration under `httpClient`.
- `ChaosHttpSuppressException` (unchecked) thrown by advice classes when a scenario suppresses an
  HTTP call.
- `ChaosRuntime.beforeHttpSend(String url, OperationType)` feeds the request URL as `targetName`
  through the 8-check evaluation pipeline and returns `true` on suppression.
- Bridge slots `BEFORE_HTTP_SEND = 46` and `BEFORE_HTTP_SEND_ASYNC = 47` added to
  `BootstrapDispatcher` (new `HANDLE_COUNT = 48`), with matching `BridgeDelegate` /
  `ChaosBridge` methods and `MethodHandle` wiring.
- `HttpUrlExtractor` reflectively pulls the request URL from each client type (Java HttpClient,
  OkHttp, Apache HC 4 / 5, Reactor Netty) so the compileOnly dependencies are not required at
  runtime.
- `HttpClientAdvice` provides ByteBuddy `@Advice.OnMethodEnter` classes per client that call
  the dispatcher and throw `ChaosHttpSuppressException` when suppressed.
- `SelectorMatcher` and `CompatibilityValidator` extended for the new selector + operation types,
  including a cross-selector guard that rejects HTTP operations used with non-HTTP selectors.
- Build wires `compileOnly` dependencies (OkHttp 4.12.0, Apache HttpClient 4.5.14, Apache
  HttpClient 5 3.1, Reactor Netty HTTP 1.1.21) so the agent jar itself remains free of HTTP
  client code.

**Known limitation** — `jdk.internal.net.http.HttpClientImpl` is NOT wired into the ByteBuddy
chain. Attempting to transform it without `--add-opens
java.net.http/jdk.internal.net.http=ALL-UNNAMED` corrupts subsequent transformations in the same
AgentBuilder installation (observed via the `StartupAgentIntegrationTest` probe JVMs, where
unrelated scenarios silently stopped applying). A dedicated `--add-opens` pathway is deferred to
a follow-up; the advice class `HttpClientAdvice.JavaHttpClientSendAdvice` is retained for future
wiring.

---

### ✅ 2.4 JDBC / Connection Pool Selectors
**Priority: high — 4 days**

New `OperationType` values:
`JDBC_CONNECTION_ACQUIRE`, `JDBC_STATEMENT_EXECUTE`, `JDBC_PREPARED_STATEMENT`,
`JDBC_TRANSACTION_COMMIT`, `JDBC_TRANSACTION_ROLLBACK`

New selector: `ChaosSelector.jdbc()` / `ChaosSelector.jdbc(OperationType...)`

Instrumentation targets:

| Target | Class | Method |
|--------|-------|--------|
| HikariCP | `com.zaxxer.hikari.pool.HikariPool` | `getConnection(long)` |
| c3p0 | `com.mchange.v2.c3p0.impl.C3P0PooledConnectionPool` | `checkoutPooledConnection()` |
| JDBC standard | `java.sql.Statement` (concrete subtypes) | `execute(String)`, `executeQuery(String)`, `executeUpdate(String)` |
| JDBC standard | `java.sql.Connection` (concrete subtypes) | `prepareStatement(String)`, `commit()`, `rollback()` |

**What was done**

- `OperationType` gained `JDBC_CONNECTION_ACQUIRE`, `JDBC_STATEMENT_EXECUTE`,
  `JDBC_PREPARED_STATEMENT`, `JDBC_TRANSACTION_COMMIT`, and `JDBC_TRANSACTION_ROLLBACK` entries
  with targeted JavaDoc and the `ChaosSelector.JdbcSelector` pairing documented.
- New sealed-interface record `ChaosSelector.JdbcSelector(Set<OperationType> operations,
  NamePattern targetPattern)` plus factory methods `jdbc()` (matches all 5 JDBC op types) and
  `jdbc(OperationType...)` (matches the specified ops). Jackson polymorphic registration under
  `jdbc`.
- `ChaosJdbcSuppressException` (unchecked) thrown by advice classes when a scenario suppresses a
  JDBC call.
- `ChaosRuntime.beforeJdbcConnectionAcquire(String)`, `beforeJdbcStatementExecute(String)`,
  `beforeJdbcPreparedStatement(String)`, `beforeJdbcTransactionCommit()`, and
  `beforeJdbcTransactionRollback()` feed the pool identifier or SQL snippet (first 200
  characters) as `targetName` through the 8-check evaluation pipeline and return `true` on
  suppression.
- Bridge slots 48–52 added to `BootstrapDispatcher` (new `HANDLE_COUNT = 53`), with matching
  `BridgeDelegate` / `ChaosBridge` methods and `MethodHandle` wiring.
- `JdbcTargetExtractor` reflectively pulls the pool name from a HikariCP `HikariPool` via
  `getPoolName()` so the compileOnly dependency is not required at runtime.
- `JdbcAdvice` provides ByteBuddy `@Advice.OnMethodEnter` classes per target: HikariCP,
  c3p0, `Statement.execute*(String)`, `Connection.prepareStatement(String)`, `Connection.commit()`,
  `Connection.rollback()`. HikariCP and c3p0 use `instrumentOptional`; the JDBC interfaces use
  `isSubTypeOf(...).and(not(isInterface()))` so advice binds only to concrete driver classes.
- `SelectorMatcher` and `CompatibilityValidator` extended for the new selector + operation
  types, including a cross-selector guard that rejects JDBC operations used with non-JDBC
  selectors. The HTTP cross-selector guard was refactored to share the new helpers.
- Build wires `compileOnly` dependencies (HikariCP 5.1.0, c3p0 0.10.1) so the agent jar itself
  remains free of JDBC pool code.

---

## Phase 3 — Performance and Quality

### ✅ 3.1 JMH Benchmark Suite
**Completed — 1 day**

New module: `chaos-agent-benchmarks` targeting the clean `ChaosDispatcher` hot path exposed by the
3.2 refactor.

Baselines to establish and document:

| Scenario | Target |
|----------|--------|
| No agent, raw executor submit | baseline |
| Agent installed, zero scenarios | < 50 ns overhead |
| Agent installed, one scenario, no match | < 100 ns |
| Agent installed, one match, no effect | < 300 ns |
| Session ID miss (wrong session) | < 20 ns additional |
| 10 active scenarios, one match | < 1 µs |

**What was done:**

- New Gradle module `chaos-agent-benchmarks` added to `settings.gradle.kts`; applies the
  `application` plugin with `org.openjdk.jmh.Main` as the main class. JMH version `1.37` declared
  in `gradle/libs.versions.toml` as `jmh-core` (`implementation`) and `jmh-generator-annprocess`
  (`annotationProcessor`). No tests live in the module — the benchmarks themselves are the
  artefact.
- `ChaosRuntimeBenchmark` covers the `beforeJdbcStatementExecute` hot path with six variants
  matching the table above: `baseline_noAgent`, `agentInstalled_zeroScenarios`,
  `agentInstalled_oneScenario_noMatch`, `agentInstalled_oneMatch_noEffect`, `sessionIdMiss`,
  `tenScenarios_oneMatch`. Each variant uses a dedicated `@State(Scope.Benchmark)` class to
  install scenarios via `@Setup(Level.Trial)` so activation cost is excluded from the measurement
  window. `@Fork(1)`, `@Warmup(iterations = 3, time = 1)`, `@Measurement(iterations = 5, time =
  1)`, `@BenchmarkMode(Mode.AverageTime)`, `@OutputTimeUnit(NANOSECONDS)`.
- `HttpClientBenchmark` mirrors the same six-variant structure against the `beforeHttpSend` hot
  path, feeding a representative `https://example.com/api/v1/orders` URL through the dispatcher.
- All benchmarks call `dispatcher = runtime.dispatcher()` in `@Setup` and exercise the dispatcher
  directly. Return values are consumed with `Blackhole.consume` to prevent dead-code elimination.
- CI does not execute the benchmarks (too slow); `./gradlew :chaos-agent-benchmarks:compileJava`
  is the gating build task and succeeds cleanly.

---

### ✅ 3.2 ChaosRuntime Refactor
**Completed — 1 day**

Split the god class:

```
ChaosRuntime (facade, thin delegate layer)
    ↓
ChaosDispatcher       — hot path: before*/after*/adjust*/decorate*/beforeHttp*/beforeJdbc*
ChaosControlPlaneImpl — control: activate(), openSession(), diagnostics(), close()
```

**What was done:**

- `ChaosControlPlaneImpl` (new, package-private) implements `ChaosControlPlane` and owns the
  control-plane state: `clock`, `featureSet`, `scopeContext`, `observabilityBus`, `registry`,
  the shutdown-hook tracking map, and the instrumentation reference. It implements `activate`,
  `activate(ChaosPlan)`, `openSession` (via package-private helper that takes the facade
  reference), `diagnostics`, `addEventListener`, `close`, `setInstrumentation`,
  `activateInSession`, and `registerScenario` with its diagnostic failure-category mapping. It
  also exposes package-private accessors so `ChaosDispatcher` can read the shared state.
- `ChaosDispatcher` (new, public) holds every hot-path entry point — `decorate*`, `before*`,
  `after*`, `adjust*` — including all Phase 1 and Phase 2 dispatch methods, the JDBC and HTTP
  entry points, `applyClockSkew`, `beforeMethodEnter`/`afterMethodExit`, `currentSessionId`, and
  the private evaluation helpers (`evaluate`, `applyPreDecision`, `applyGate`, `sleep`,
  `propagate`, `terminalActionFor`, `rejectTerminal`, `suppressTerminal`,
  `buildInjectedExceptionTerminal`, `evaluateJdbc`, `snippet`, `extractRemoteHost`). It is
  constructed by `ChaosControlPlaneImpl` and receives references to the four pieces of state it
  needs (`featureSet`, `scopeContext`, `registry`, `shutdownHooks`).
- `ChaosRuntime` is now a thin facade that composes `ChaosControlPlaneImpl` + `ChaosDispatcher`.
  `ChaosControlPlane` methods delegate to the control-plane impl; hot-path methods delegate to
  the dispatcher. Package-private accessors `registry()`, `instrumentation()`,
  `activateInSession()` are preserved for backwards compatibility with `DefaultChaosSession` and
  tests. New public accessors `dispatcher()` and `controlPlane()` let callers (notably JMH
  benchmarks) target the refactored halves directly.
- All existing tests pass unchanged (`./gradlew build` succeeds). `ChaosBridge`, test
  constructors (`new ChaosRuntime()`), and bootstrap wiring in `ChaosAgentBootstrap` continue to
  work without modification.

---

### ✅ 3.3 Quarkus Integration
**Completed — 1 day**

New module: `chaos-agent-quarkus-extension` targeting the Quarkus 3.x extension surface
(`quarkus-core`, `quarkus-arc`, `quarkus-junit5`, `quarkus-core-deployment`).

**What was done:**

- New Gradle module `chaos-agent-quarkus-extension` compiled against the
  `io.quarkus:quarkus-bom` at version `3.21.3` (declared in `gradle/libs.versions.toml` as
  `quarkusBom` + `quarkus-bom` library alias). Every Quarkus dependency (`quarkus-core`,
  `quarkus-core-deployment`, `quarkus-arc`, `quarkus-junit5`) is `compileOnly` so the jar
  remains inert until a host application supplies Quarkus on its own classpath. The jar carries
  `Automatic-Module-Name` `com.macstab.chaos.jvm.agent.quarkus`; the package is
  `com.macstab.chaos.jvm.quarkus`.
- `@ChaosScenario` — declarative annotation (`TYPE`, `METHOD`) with `id`, `selector`, `effect`,
  `scope` attributes. Selector identifiers: `executor`, `jvmRuntime`, `httpClient`, `jdbc`.
  Effect identifiers: `delay:<iso-duration>` (ISO-8601 duration, e.g. `delay:PT0.1S`),
  `suppress`, `freeze` (clock-skew freeze).
- `ChaosQuarkusExtension` — JUnit 5 extension (`BeforeAllCallback`, `BeforeEachCallback`,
  `AfterAllCallback`, `ParameterResolver`) that self-attaches the agent via
  `ChaosPlatform.installLocally()` in `beforeAll`, opens a class-scoped `ChaosSession` stored in
  the extension-context store, reads class-level `@ChaosScenario` annotations on `beforeAll`
  and method-level annotations on `beforeEach`, activates each scenario on either the session
  (session-scope) or the control plane (JVM-scope), and closes the session in `afterAll`. The
  resolver walks parent contexts so `@Nested` classes inherit the session (matches the Spring
  Boot class-scope behaviour).
- `@QuarkusChaosTest` — meta-annotation combining `@QuarkusTest` and
  `@ExtendWith(ChaosQuarkusExtension.class)` so a single annotation opts a Quarkus test into
  chaos instrumentation.
- `ChaosQuarkusRecorder` — Quarkus `@Recorder` that installs the chaos agent at runtime
  initialization via a `RuntimeValue<Boolean>` marker.
- `ChaosQuarkusBuildStep` — `@BuildStep` annotated with `@Record(ExecutionTime.RUNTIME_INIT)`
  that produces a `FeatureBuildItem("macstab-chaos-agent")` and delegates agent installation to
  the recorder so the feature appears in the Quarkus startup banner.
- `ChaosArcProducer` — `@ApplicationScoped` CDI producer exposing `ChaosControlPlane` as a
  `@DefaultBean` so user-supplied producers win (analogous to `@ConditionalOnMissingBean` in
  Spring Boot).
- `META-INF/quarkus-extension.yaml` — extension descriptor with the artifact coordinate, human
  name, description, and keywords (`chaos`, `testing`, `resilience`).
- `ChaosQuarkusExtensionTest` — plain JUnit 5 tests (no Quarkus context required, matching the
  pattern used by `ChaosAgentExtensionTest` in the Spring Boot and Micronaut starters):
  parameter injection of `ChaosSession` and `ChaosControlPlane`, session being open and
  `bind()` usable, annotation parsing for `delay`/`suppress`/`freeze` effects, and method-level
  `@ChaosScenario` activation verified through `ChaosDiagnostics.scenario(id)`. All 9 tests
  pass via `./gradlew :chaos-agent-quarkus-extension:build`.

Usage:
```java
@QuarkusChaosTest
@ChaosScenario(id = "slow-jdbc", selector = "jdbc", effect = "delay:PT0.1S")
class OrderServiceChaosTest {

    @Test
    @ChaosScenario(id = "reject-http", selector = "httpClient", effect = "suppress")
    void placesOrderUnderChaos(ChaosSession chaos) {
        try (var binding = chaos.bind()) {
            assertThrows(OrderTimeoutException.class,
                () -> orderService.placeOrder(testOrder));
        }
    }
}
```

---

### ✅ 3.4 Micronaut Integration
**Completed — 1 day**

New module: `chaos-agent-micronaut-integration` targeting the Micronaut 4.x bean container and the
`micronaut-test-junit5` surface.

**What was done:**

- New Gradle module `chaos-agent-micronaut-integration` compiled against the
  `io.micronaut.platform:micronaut-platform` BOM at version `4.7.6` (declared in
  `gradle/libs.versions.toml` as `micronautBom` + `micronaut-bom` library alias). Every Micronaut
  dependency (`micronaut-inject`, `micronaut-context`, `micronaut-test-junit5`) is `compileOnly` so
  the jar remains inert until a host application supplies Micronaut on its own classpath. The jar
  carries `Automatic-Module-Name` `com.macstab.chaos.jvm.agent.micronaut`; the package is
  `com.macstab.chaos.jvm.micronaut`.
- `ChaosFactory` — `@Factory` bean factory exposing `ChaosControlPlane` as a `@Singleton` guarded
  by `@Requires(missingBeans = ChaosControlPlane.class)`. Mirrors the
  `@ConditionalOnMissingBean` contract of the Spring Boot starters: user-supplied beans win.
- `ChaosMicronautExtension` — JUnit 5 extension (`BeforeAllCallback`, `AfterAllCallback`,
  `ParameterResolver`) that self-attaches the agent via `ChaosPlatform.installLocally()` in
  `beforeAll`, opens a class-scoped `ChaosSession` stored in the extension-context store, closes
  it in `afterAll`, and resolves `ChaosSession` + `ChaosControlPlane` parameters. The resolver
  walks parent contexts so `@Nested` classes inherit the session (matches the Spring Boot
  class-scope behaviour).
- `@MicronautChaosTest` — meta-annotation combining `@MicronautTest` and
  `@ExtendWith(ChaosMicronautExtension.class)` so a single annotation opts a Micronaut test into
  chaos instrumentation.
- `ChaosContextConfigurer` — `ApplicationContextConfigurer` that invokes
  `ChaosPlatform.installLocally()` before the Micronaut `ApplicationContext` starts. Registered
  via `META-INF/micronaut/io.micronaut.context.ApplicationContextConfigurer` so Micronaut picks it
  up automatically whenever the jar is on the classpath. Idempotent; safe to combine with
  `-javaagent:` startup.
- `ChaosMicronautExtensionTest` — plain JUnit 5 tests (no Micronaut context required, matching the
  pattern used by `ChaosAgentExtensionTest` in the Spring Boot test starters): parameter
  injection of `ChaosSession` and `ChaosControlPlane`, session being open and `bind()` usable,
  session identity stable across methods in the class, and clean `@Nested` +
  `@TestInstance(PER_CLASS)` lifecycle. All 7 tests pass via
  `./gradlew :chaos-agent-micronaut-integration:build`.

Usage:
```java
@MicronautChaosTest
class OrderServiceChaosTest {

    @Test
    void slowDatabaseRejectsOrdersGracefully(ChaosSession chaos) {
        chaos.activate(ChaosScenario.builder()
            .id("slow-jdbc")
            .selector(ChaosSelector.jdbc())
            .effect(ChaosEffect.delay(Duration.ofSeconds(3)))
            .build());

        try (var binding = chaos.bind()) {
            assertThrows(OrderTimeoutException.class,
                () -> orderService.placeOrder(testOrder));
        }
    }
}
```

---

## Phase 4 — JVM Internals Interception

Phase 4 extends the interception surface to five additional JVM-level operations that are highly
demanded in resilience and performance-testing scenarios: `Thread.sleep()` suppression,
DNS resolution latency/error injection, SSL/TLS handshake delay injection, File I/O
read/write delay injection, and virtual-thread carrier-thread pinning simulation.

All five features follow the established agent architecture: new `OperationType` enum values,
new `ChaosSelector` implementations with `@JsonSubTypes` support, and — where applicable —
new `BootstrapDispatcher` dispatch handles wired through `BridgeDelegate` → `ChaosBridge` →
`ChaosRuntime` → `ChaosDispatcher` with matching `JvmRuntimeAdvice` inner classes and
`JdkInstrumentationInstaller` Phase 2 wiring.

---

### ✅ 4.1 Thread.sleep() Suppression
**Completed — 2026-04-18**

Intercepts `Thread.sleep(long)` at the JVM level and optionally skips the sleep entirely,
simulating a spurious-wakeup or a time-skip without modifying the caller's clock perception.

**New API surface:**

- `OperationType.THREAD_SLEEP` — operation type representing a `Thread.sleep(long)` call.
- `ChaosSelector.thread(Set.of(THREAD_SLEEP), ...)` — use the existing `ThreadSelector` to target
  sleep calls by thread name or kind; or any selector that includes `THREAD_SLEEP`.

**How it works:**

The `ChaosEffect.SUPPRESS` outcome causes `ThreadSleepAdvice.enter()` — annotated with
`@Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)` — to return `true`,
which instructs ByteBuddy to skip the real `Thread.sleep()` body entirely. Any other outcome
(DELAY, EXCEPTION) applies the standard dispatcher logic. The bootstrap dispatch handle
index is `BootstrapDispatcher.BEFORE_THREAD_SLEEP = 53`.

**Example:**

```java
// Suppress all Thread.sleep() calls on any thread — useful for speeding up
// time-based retry loops in tests.
chaos.activate(ChaosScenario.builder()
    .id("suppress-sleep")
    .selector(ChaosSelector.thread(Set.of(OperationType.THREAD_SLEEP)))
    .effect(ChaosEffect.suppress())
    .policy(ActivationPolicy.always())
    .build());
```

---

### ✅ 4.2 DNS Resolution Injection
**Completed — 2026-04-18**

Intercepts `InetAddress.getByName(String)`, `InetAddress.getAllByName(String)`, and
`InetAddress.getLocalHost()` to inject latency or surface `UnknownHostException`-equivalent
exceptions, simulating DNS outages and slow resolvers.

**New API surface:**

- `OperationType.DNS_RESOLVE` — operation type representing any `InetAddress` resolution call.
- `ChaosSelector.DnsSelector(Set<OperationType> operations, NamePattern hostnamePattern)` —
  matches resolution calls by operation type and optionally by resolved hostname prefix/regex.
- Factory: `ChaosSelector.dns(Set<OperationType>)` and
  `ChaosSelector.dns(Set<OperationType>, NamePattern)`.

**How it works:**

Three separate `@Advice` inner classes handle the three static `InetAddress` methods:
`DnsResolveAdvice` for the two `String`-argument overloads (capturing `hostname` via
`@Advice.Argument(0)`) and `DnsLocalHostAdvice` for the zero-argument `getLocalHost()` (passes
`null` hostname to the dispatcher, which selectors treat as a wildcard match). The
`InvocationContext.targetName` carries the hostname for `DnsSelector.hostnamePattern` matching.
Bootstrap dispatch handle: `BootstrapDispatcher.BEFORE_DNS_RESOLVE = 54`.

**Example:**

```java
// Inject 2 s delay on all DNS lookups for hosts matching "*.internal"
chaos.activate(ChaosScenario.builder()
    .id("slow-dns-internal")
    .selector(ChaosSelector.dns(
        Set.of(OperationType.DNS_RESOLVE),
        NamePattern.prefix("*.internal")))
    .effect(ChaosEffect.delay(Duration.ofSeconds(2)))
    .policy(ActivationPolicy.always())
    .build());
```

---

### ✅ 4.3 SSL/TLS Handshake Injection
**Completed — 2026-04-18**

Intercepts `SSLSocket.startHandshake()` and `SSLEngine.beginHandshake()` to inject delays,
simulating slow TLS negotiation or certificate chain validation timeouts.

**New API surface:**

- `OperationType.SSL_HANDSHAKE` — operation type representing a TLS handshake initiation.
- `ChaosSelector.SslSelector(Set<OperationType> operations)` — matches SSL handshake calls.
- Factory: `ChaosSelector.ssl(Set<OperationType>)`.

**How it works:**

`SslHandshakeAdvice` is an `@Advice.OnMethodEnter` class that captures `@Advice.This` (the
`SSLSocket` or `SSLEngine` instance) and forwards it as `Object socket` to
`BootstrapDispatcher.beforeSslHandshake(socket)`, which populates
`InvocationContext.targetClassName` with `socket.getClass().getName()`. Both `SSLSocket` and
`SSLEngine` are instrumented via `instrumentOptional(...)` so the agent degrades gracefully in
environments where the JSSE provider is absent or the class has been shaded. Bootstrap dispatch
handle: `BootstrapDispatcher.BEFORE_SSL_HANDSHAKE = 55`.

**Example:**

```java
// Delay TLS handshakes by 500 ms to test connection-timeout handling
chaos.activate(ChaosScenario.builder()
    .id("slow-tls")
    .selector(ChaosSelector.ssl(Set.of(OperationType.SSL_HANDSHAKE)))
    .effect(ChaosEffect.delay(Duration.ofMillis(500)))
    .policy(ActivationPolicy.always())
    .build());
```

---

### ✅ 4.4 File I/O Injection
**Completed — 2026-04-18**

Intercepts `FileInputStream.read()` / `read(byte[], int, int)` and
`FileOutputStream.write(int)` / `write(byte[], int, int)` to inject read and write latency,
simulating slow disks, NFS stalls, or I/O throttling.

**New API surface:**

- `OperationType.FILE_IO_READ` — represents a `FileInputStream.read(...)` call.
- `OperationType.FILE_IO_WRITE` — represents a `FileOutputStream.write(...)` call.
- `ChaosSelector.FileIoSelector(Set<OperationType> operations)` — matches file I/O by operation
  direction; include `FILE_IO_READ`, `FILE_IO_WRITE`, or both.
- Factory: `ChaosSelector.fileIo(Set<OperationType>)`.

**How it works:**

Two separate advice classes — `FileReadAdvice` and `FileWriteAdvice` — are applied to the
corresponding `FileInputStream` and `FileOutputStream` overloads. Each captures `@Advice.This`
and forwards the stream instance and an operation tag (`"FILE_IO_READ"` / `"FILE_IO_WRITE"`)
to `BootstrapDispatcher.beforeFileIo(String operation, Object stream)`, which routes to
`FILE_IO_READ` or `FILE_IO_WRITE` in the dispatcher based on the tag. Bootstrap dispatch
handle: `BootstrapDispatcher.BEFORE_FILE_IO = 56`.

**Example:**

```java
// Slow down all file reads by 100 ms — useful for testing read-timeout paths
chaos.activate(ChaosScenario.builder()
    .id("slow-file-read")
    .selector(ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_READ)))
    .effect(ChaosEffect.delay(Duration.ofMillis(100)))
    .policy(ActivationPolicy.always())
    .build());
```

---

### ✅ 4.5 Virtual Thread Carrier Pinning Simulation
**Completed — 2026-04-18**

Simulates the JDK 21+ behaviour where a virtual thread inside a `synchronized` block pins its
carrier platform thread, preventing the carrier from being reused for other virtual threads.
This stressor is used without a selector — it is a background stressor activated via
`ChaosSelector.stress(StressTarget.VIRTUAL_THREAD_CARRIER_PINNING)`.

**New API surface:**

- `ChaosEffect.VirtualThreadCarrierPinningEffect(int pinnedThreadCount, Duration pinDuration)` —
  creates the stressor effect.
- `ChaosSelector.StressTarget.VIRTUAL_THREAD_CARRIER_PINNING` — stress target enum value.
- Factory: `ChaosEffect.virtualThreadCarrierPinning(int pinnedThreadCount, Duration pinDuration)`.

**How it works:**

`VirtualThreadCarrierPinningStressor` spawns `pinnedThreadCount` platform-thread daemons
(via `Thread.ofPlatform().daemon(true)`). Each thread runs a tight loop: acquire a shared
`synchronized(PIN_MONITOR)` lock, then park in `LockSupport.parkNanos(10_000L)` chunks for
`pinDuration` nanoseconds, then release and re-acquire. When a JVM running virtual threads
schedules a virtual thread onto a carrier that one of these platform threads is occupying while
holding `PIN_MONITOR`, the carrier is pinned and cannot be yielded. A `CountDownLatch ready`
synchronises all threads before the stressor signals start; `AtomicBoolean stopped` and
`Thread.interrupt()` in `close()` provide clean teardown.

`StressorFactory` registers `VirtualThreadCarrierPinningEffect` →
`new VirtualThreadCarrierPinningStressor(e)`.

**Example:**

```java
// Pin 4 carrier threads for 100 ms at a time — stresses virtual-thread scheduler
// and exposes code that deadlocks when all carriers are pinned
chaos.activate(ChaosScenario.builder()
    .id("carrier-pin")
    .selector(ChaosSelector.stress(StressTarget.VIRTUAL_THREAD_CARRIER_PINNING))
    .effect(ChaosEffect.virtualThreadCarrierPinning(4, Duration.ofMillis(100)))
    .policy(ActivationPolicy.always())
    .build());
```

---

## Full Timeline

```
Week 1  ├── ✅ 1.1  DeadlockStressor safeguard            (2h)
        ├── ✅ 1.2  Clock skew — JVM limitation documented (1d)
        ├── ✅ 1.3  Missing concurrency tests              (1d)
        └── ✅ 1.4  Higher-level time API interception     (1d)

Week 2  ├── ✅ 2.1  Spring Boot test starter               (2d)
        └── ✅ 2.2  Spring Boot runtime starter            (3d)

Week 3  └── ✅ 2.3  HTTP client selectors                  (5d)

Week 4  └── ✅ 2.4  JDBC / HikariCP selectors              (4d)

Week 5  ├── ✅ 3.1  JMH benchmarks                        (1d)
        └── ✅ 3.2  ChaosRuntime refactor                  (1d)

Week 6  ├── ✅ 3.3  Quarkus integration                    (2d)
        ├── ✅ 3.4  Micronaut integration                   (1d)
        └──         Phase 3 complete — ecosystem coverage done

Week 7  ├── ✅ 4.1  Thread.sleep() suppression             (0.5d)
        ├── ✅ 4.2  DNS resolution injection               (0.5d)
        ├── ✅ 4.3  SSL/TLS handshake injection            (0.5d)
        ├── ✅ 4.4  File I/O injection                     (0.5d)
        ├── ✅ 4.5  Virtual thread carrier pinning         (0.5d)
        └──         Phase 4 complete — JVM internals coverage done
```

---

## Out of Scope (Intentional)

- Remote control plane / HTTP API (separate product layer)
- Multi-JVM coordination (separate product layer)
- Agent uninstallation (JVM instrumentation is permanent by design)
- Non-JVM language support (separate agents per runtime)

---

<div align="center">

*Architecture, implementation, and documentation crafted by*

**[Christian Schnapka](https://macstab.com)**
Embedded Principal+ Engineer
[Macstab GmbH](https://macstab.com) · Hamburg, Germany

</div>
