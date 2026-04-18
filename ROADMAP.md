<!--
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Engineered by  Christian Schnapka
                 Principal+ Embedded Systems Architect
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
  `com.macstab.chaos.agent.spring.boot3.test`; package
  `com.macstab.chaos.spring.boot3.test`.
- `chaos-agent-spring-boot4-test-starter` — compiled against `spring-boot-dependencies:4.0.5`
  (latest stable 4.x at release time); `Automatic-Module-Name`
  `com.macstab.chaos.agent.spring.boot4.test`; package
  `com.macstab.chaos.spring.boot4.test`.

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
  `Automatic-Module-Name` `com.macstab.chaos.agent.spring.boot3`; package
  `com.macstab.chaos.spring.boot3`.
- `chaos-agent-spring-boot4-starter` — compiled against `spring-boot-dependencies:4.0.5`;
  `Automatic-Module-Name` `com.macstab.chaos.agent.spring.boot4`; package
  `com.macstab.chaos.spring.boot4`.

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

### ⬜ 2.3 HTTP Client Selectors
**Priority: high — 5 days**

New `OperationType` values: `HTTP_CLIENT_SEND`, `HTTP_CLIENT_SEND_ASYNC`

New selector: `ChaosSelector.httpClient()`

Instrumentation targets:

| Client | Target class | Method |
|--------|-------------|--------|
| Java 11+ `HttpClient` | `jdk.internal.net.http.HttpClientImpl` | `send()`, `sendAsync()` |
| OkHttp 3/4 | `okhttp3.RealCall` | `execute()`, `enqueue()` |
| Apache HC 4.x | `org.apache.http.impl.client.CloseableHttpClient` | `execute()` |
| Apache HC 5.x | `org.apache.hc.client5.http.impl.classic.CloseableHttpClient` | `execute()` |
| Spring WebClient | `reactor.netty.http.client.HttpClientConnect` | `connect()` |

Pattern matching on URL host + path via `targetName` field of `InvocationContext`.

---

### ⬜ 2.4 JDBC / Connection Pool Selectors
**Priority: high — 4 days**

New `OperationType` values:
`JDBC_CONNECTION_ACQUIRE`, `JDBC_STATEMENT_EXECUTE`, `JDBC_PREPARED_STATEMENT`,
`JDBC_TRANSACTION_COMMIT`, `JDBC_TRANSACTION_ROLLBACK`

New selector: `ChaosSelector.jdbc()`

Instrumentation targets:

| Target | Class | Method |
|--------|-------|--------|
| HikariCP | `com.zaxxer.hikari.pool.HikariPool` | `getConnection(long)` |
| c3p0 | `com.mchange.v2.c3p0.impl.C3P0PooledConnectionPool` | `checkoutPooledConnection()` |
| JDBC standard | `java.sql.Statement` | `execute*()` |
| JDBC standard | `java.sql.Connection` | `commit()`, `rollback()`, `prepareStatement()` |

---

## Phase 3 — Performance and Quality

### ⬜ 3.1 JMH Benchmark Suite
**Priority: medium — 1 day**

New module: `chaos-agent-benchmarks`

Baselines to establish and document:

| Scenario | Target |
|----------|--------|
| No agent, raw executor submit | baseline |
| Agent installed, zero scenarios | < 50 ns overhead |
| Agent installed, one scenario, no match | < 100 ns |
| Agent installed, one match, no effect | < 300 ns |
| Session ID miss (wrong session) | < 20 ns additional |
| 10 active scenarios, one match | < 1 µs |

---

### ⬜ 3.2 ChaosRuntime Refactor
**Priority: low — 1 day**

Split the god class:

```
ChaosRuntime (current, ~800 LOC)
    ↓
ChaosDispatcher       — hot path: before*/after*/adjust*/decorate*
ChaosControlPlaneImpl — control: activate(), openSession(), diagnostics(), close()
```

No behavior change. Pure structural refactor for readability and independent profiling.

---

### ⬜ 3.3 Quarkus Integration
**Priority: low — 2 days**

New module: `chaos-agent-quarkus-extension`

- Quarkus build-time processor
- Dev Services integration (auto-activate scenarios in `@QuarkusTest`)
- `@ChaosScenario` annotation support for declarative activation

---

### ⬜ 3.4 Micronaut Integration
**Priority: low — 2 days**

New module: `chaos-agent-micronaut-integration`

- `@Factory` bean for `ChaosControlPlane`
- `@MicronautTest` lifecycle integration

---

## Phase 4 — Release

### ⬜ 4.1 Release Process + 1.0.0
**Priority: last — 3 days**

- GitHub Actions CI matrix: JDK 17, 21, 25 + Linux/macOS
- Maven Central publishing via Sonatype OSSRH
- GPG signing
- `CHANGELOG.md` ([Keep a Changelog](https://keepachangelog.com) format)
- `io.macstab` group ID
- Tag `v1.0.0`

---

## Full Timeline

```
Week 1  ├── ✅ 1.1  DeadlockStressor safeguard            (2h)
        ├── ✅ 1.2  Clock skew — JVM limitation documented (1d)
        ├── ✅ 1.3  Missing concurrency tests              (1d)
        └── ✅ 1.4  Higher-level time API interception     (1d)

Week 2  ├── ✅ 2.1  Spring Boot test starter               (2d)
        └── ✅ 2.2  Spring Boot runtime starter            (3d)

Week 3  └── ⬜ 2.3  HTTP client selectors                  (5d)

Week 4  └── ⬜ 2.4  JDBC / HikariCP selectors              (4d)

Week 5  ├── ⬜ 3.1  JMH benchmarks                        (1d)
        └── ⬜ 3.2  ChaosRuntime refactor                  (1d)

Week 6  ├── ⬜ 3.3  Quarkus integration                    (2d)
        ├── ⬜ 3.4  Micronaut integration                   (2d)
        └──         Final integration testing

Week 7  └── ⬜ 4.1  Release process + v1.0.0 tag           (3d)
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
Principal+ Embedded Systems Architect
[Macstab GmbH](https://macstab.com) · Hamburg, Germany

</div>
