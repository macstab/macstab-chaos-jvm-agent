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

### ⬜ 1.2 Clock Skew — Native Method Prefix Fix
**Priority: high — 1 day**

`System.currentTimeMillis()` and `System.nanoTime()` are `@IntrinsicCandidate` native methods.
After JIT compilation on HotSpot (C2 tier, ~10k invocations), they are replaced with direct
`RDTSC` / `MRS CNTVCT_EL0` hardware reads — ByteBuddy advice on the wrapper is dead code.

Fix: second `AgentBuilder` without `disableClassFormatChanges()` + native method prefix
`$$chaos$$`. JVM renames native to `$$chaos$$currentTimeMillis`, advice wrapper becomes
the public `currentTimeMillis()`.

Requires `Can-Set-Native-Method-Prefix: true` in agent manifest.

Reference: JVMTI §11.14 `SetNativeMethodPrefix` — https://docs.oracle.com/en/java/docs/api/java.instrument/java/lang/instrument/Instrumentation.html

---

### ⬜ 1.3 Missing Concurrency Tests
**Priority: medium — 1 day**

- `TwoScenariosConflictingTerminalActionsTest` — precedence merge under concurrent access
- `StopDuringEvaluationTest` — scenario stopped while `evaluate()` is mid-pipeline
- `RateLimitUnderHighConcurrencyTest` — 100 threads, assert permits never exceeded
- `SessionIsolationParallelTest` — 10 sessions × 10 threads × shared executor

---

## Phase 2 — Ecosystem Integration

> *Drop-in support for every Java backend stack.*

### ⬜ 2.1 Spring Boot Test Starter
**Priority: high — 2 days**

New module: `chaos-agent-spring-boot-test-starter` (`testImplementation` scope)

The test starter builds on `chaos-agent-testkit` and integrates into the `@SpringBootTest` lifecycle.
Its contract is narrow and safe: the agent self-attaches once per test JVM, each test gets an isolated
`ChaosSession`, and everything is cleaned up by the JUnit extension.

Deliverables:
- `@ChaosTest` — meta-annotation combining `@SpringBootTest` + `@ExtendWith(ChaosAgentExtension.class)`
- `ChaosTestAutoConfiguration` — `@ConditionalOnClass` + `@TestConfiguration` exposing `ChaosControlPlane` bean
- `ChaosSession` method parameter injection for `@SpringBootTest` test methods (via `ParameterResolver`)
- `ChaosAgentInitializer` — `ApplicationContextInitializer` that calls `ChaosPlatform.installLocally()` before context refresh

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

### ⬜ 2.2 Spring Boot Runtime Starter
**Priority: medium — 3 days**

New module: `chaos-agent-spring-boot-starter` (`implementation` scope)

The runtime starter is for deployment-time chaos engineering: pre-production soak tests, game days,
and controlled production experiments. It has no test APIs — only externally driven activation via
Actuator or startup config from a config server.

This is explicitly separate from the test starter. Mixing test-lifecycle APIs with runtime deployment
creates blast-radius risk if a scenario configuration is accidentally carried into a long-lived process.

Deliverables:
- `ChaosAutoConfiguration` — `@ConditionalOnProperty("macstab.chaos.enabled")`; disabled by default
- `ChaosProperties` — `@ConfigurationProperties("macstab.chaos")`
- `ChaosActuatorEndpoint` — `/actuator/chaos`
  - `GET /actuator/chaos` → full `ChaosDiagnostics.snapshot()` as JSON
  - `POST /actuator/chaos/activate` → activate a named plan from the registered plan registry
  - `POST /actuator/chaos/stop/{scenarioId}` → stop a running scenario by ID
  - `POST /actuator/chaos/stop-all` → stop all JVM-scoped scenarios
- Spring context lifecycle binding — auto-close all JVM-scoped scenarios on `ApplicationContext` close
- `@ConditionalOnProperty` guard — all chaos beans off unless `macstab.chaos.enabled=true` is explicit

```yaml
macstab:
  chaos:
    enabled: true                          # explicit opt-in required
    config-file: classpath:chaos-plan.json # optional startup plan
    debug-dump-on-start: false
    actuator:
      enabled: true                        # Actuator endpoint, off by default
```

**Security note**: The Actuator endpoint is protected by Spring Security if present. Operators must
not expose `/actuator/chaos` to the public internet without authentication.

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
        ├── ⬜ 1.2  Clock skew native prefix fix           (1d)
        └── ⬜ 1.3  Missing concurrency tests              (1d)

Week 2  ├── ⬜ 2.1  Spring Boot test starter               (2d)
        └── ⬜ 2.2  Spring Boot runtime starter            (3d)

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
