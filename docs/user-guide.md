<!--
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Engineered by  Christian Schnapka
                 Embedded Principal+ Engineer
                 Macstab GmbH · Hamburg, Germany
                 https://macstab.com
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-->

# macstab-chaos-jvm-agent — User Guide

> Practical guide from first dependency to CI/CD pipeline chaos gates.
>
> *Engineered by* **[Christian Schnapka](https://macstab.com)** — Embedded Principal+ Engineer · [Macstab GmbH](https://macstab.com) · Hamburg, Germany

---

## Table of Contents

1. [What This Is (and Is Not)](#1-what-this-is-and-is-not)
2. [Installation](#2-installation)
3. [Spring Boot 3 / 4 — Test Starter](#3-spring-boot-3--4--test-starter)
4. [Plain JUnit 5 Without Spring](#4-plain-junit-5-without-spring)
5. [CI/CD Pipeline Chaos Gates](#5-cicd-pipeline-chaos-gates)
6. [Docker and Container Deployments](#6-docker-and-container-deployments)
7. [Runtime Control — JMX, Startup Config, File Watch](#7-runtime-control--jmx-startup-config-file-watch)
8. [Diagnostics and Observability](#8-diagnostics-and-observability)
9. [Effect and Selector Recipes](#9-effect-and-selector-recipes)
10. [Performance Budget](#10-performance-budget)
11. [Limitations and Known Constraints](#11-limitations-and-known-constraints)
12. [Contributing](#12-contributing)

> **Complete configuration reference:** For exhaustive documentation of every selector, effect, operation type, activation policy field, stressor, and JSON plan schema, see [configuration-reference.md](configuration-reference.md).

---

## 1. What This Is (and Is Not)

This agent intercepts JDK operations through 78 interception handles — thread pools, sockets, NIO, JDBC, HTTP clients, clocks, queues, class loaders, and more — by rewriting JVM bytecode on demand, when a scenario first needs a hook. Every fault it injects is **real**: a real `ConnectException`, a real sleep, a real `OutOfMemoryError`. No mocks.

**This is not a network chaos proxy.** It operates entirely inside one JVM process. For network-layer chaos (packet loss, latency between processes) use a complementary tool like `tc netem` or Toxiproxy.

**This is not a sidecar.** There is no separate daemon, no Kubernetes webhook, no kernel eBPF module. The agent JAR attaches to the target JVM at startup or via the JDK Attach API.

The most novel capability is **CI/CD pipeline integration**: chaos scenarios run in the same JVM fork as the test suite, gate the build on fault-tolerance verification, and produce structured pass/fail output. This makes chaos testing a first-class build step, not an ops-team Game Day.

---

## 2. Installation

`<version>` / `VERSION` below is the latest release ([releases](https://github.com/macstab/macstab-chaos-jvm-agent/releases)).

### 2.1 Gradle (Kotlin DSL)

`chaos-agent-bom` aligns the versions of all modules: import it once, then leave the versions out (`testImplementation(platform("com.macstab.chaos.jvm:chaos-agent-bom:<version>"))`, or a Maven `<dependencyManagement>` import with `<type>pom</type>` and `<scope>import</scope>`).

```kotlin
dependencies {
    // Required for all usage — the API types and agent bootstrap
    testImplementation("com.macstab.chaos.jvm:chaos-agent-testkit:<version>")

    // Spring Boot 3 test integration (choose one)
    testImplementation("com.macstab.chaos.jvm:chaos-agent-spring-boot3-test-starter:<version>")
    // or Spring Boot 4:
    testImplementation("com.macstab.chaos.jvm:chaos-agent-spring-boot4-test-starter:<version>")

    // Spring Boot 3 runtime starter (Actuator endpoint, live reload)
    implementation("com.macstab.chaos.jvm:chaos-agent-spring-boot3-starter:<version>")
    // or Spring Boot 4:
    implementation("com.macstab.chaos.jvm:chaos-agent-spring-boot4-starter:<version>")
}
```

The agent self-attaches at test time via the JDK Attach API — no `-javaagent:` flag required when using `chaos-agent-testkit` or the Spring test starters. For production or CLI usage, see §2.3.

### 2.2 Maven

```xml
<dependency>
    <groupId>com.macstab.chaos.jvm</groupId>
    <artifactId>chaos-agent-testkit</artifactId>
    <version>VERSION</version>
    <scope>test</scope>
</dependency>
<!-- Or Spring Boot 3 test starter: -->
<dependency>
    <groupId>com.macstab.chaos.jvm</groupId>
    <artifactId>chaos-agent-spring-boot3-test-starter</artifactId>
    <version>VERSION</version>
    <scope>test</scope>
</dependency>
```

### 2.3 JVM Argument (Production / CI Startup)

For production deployments or CLI-driven chaos, attach at startup:

```
java -javaagent:chaos-agent-bootstrap-<version>.jar \
     -jar your-application.jar
```

Pass a startup config via agent args or environment variable (see §7 for the full config reference):

```
java -javaagent:chaos-agent-bootstrap-<version>.jar=configFile=/etc/chaos/plan.json \
     -jar your-application.jar
```

Use one agent version per JVM. The JVM appends the `-javaagent` jar to the end of the class path, so an agent copy that the application already has on its class path (for example `chaos-agent-bootstrap` pulled in by a starter) is the one that runs. When the copies have different versions, the agent prints one `[chaos-agent] agent copies of different versions are on the class path` warning that names each jar. A copy of another version that finds the bootstrap bridge of a different version already installed refuses to install (`a chaos agent of revision … is already installed in this JVM …; restart with one agent version`). The Quarkus extensions load `chaos-agent-api` and `chaos-agent-bootstrap` parent-first, so a Quarkus application has one copy of each, shared with a `-javaagent` agent.

### 2.4 Self-Attach and the JEP 451 Warning

When the testkit or Spring starters self-attach, no JVM flag is required: when the JVM does not allow a process to attach to itself (`jdk.attach.allowAttachSelf` unset, the default since JDK 9), ByteBuddy attaches from a short-lived helper process, and the agent installs as usual. On JDK 21 and later the JVM then prints the [JEP 451](https://openjdk.org/jeps/451) warning on stderr ("WARNING: A Java agent has been loaded dynamically …"). It is a warning only; chaos works. The agent does not suppress it. To avoid it, either load the agent at startup with `-javaagent:` (no dynamic loading, no warning), or add `-XX:+EnableDynamicAgentLoading` to the test JVM, which allows the dynamic load explicitly. A JVM started with `-XX:-EnableDynamicAgentLoading` refuses the self-attach; `installLocally()` then fails with `IllegalStateException`, and `-javaagent:` is the way to run.

To allow the dynamic load explicitly (and so avoid the warning):

```kotlin
// Gradle
tasks.test {
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}
```

```xml
<!-- Maven Surefire -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <argLine>-XX:+EnableDynamicAgentLoading</argLine>
    </configuration>
</plugin>
```

---

## 3. Spring Boot 3 / 4 — Test Starter

### 3.1 `@ChaosTest` — Minimal Usage

```java
@ChaosTest                   // meta-annotation: @SpringBootTest + @ExtendWith(ChaosAgentExtension.class)
                             //   + @ContextConfiguration(initializers = ChaosAgentInitializer.class)
class PaymentServiceTest {

    @Autowired PaymentService paymentService;

    @Test
    void shouldRetryOnTransientDatabaseFailure(ChaosSession session) throws Exception {
        // Inject a one-shot JDBC connection failure
        try (ChaosActivationHandle h = session.activate(
                ChaosScenario.builder("transient-db-fail")
                    .scope(ChaosScenario.ScenarioScope.SESSION)   // a session takes SESSION scenarios only
                    .selector(ChaosSelector.jdbc(OperationType.JDBC_CONNECTION_ACQUIRE))
                    .effect(ChaosEffect.reject("chaos: pool exhausted"))
                    .activationPolicy(ActivationPolicy.builder()
                        .maxApplications(1L)
                        .build())
                    .build())) {
            try (ChaosSession.ScopeBinding scope = session.bind()) {
                // The first getConnection() call on this thread throws
                // PaymentService must handle it and retry
                assertThatCode(() -> paymentService.charge(order)).doesNotThrowAnyException();
            }
        }
    }
}
```

`@ChaosTest` forwards the `@SpringBootTest` attributes `properties`, `classes`, `webEnvironment` and `args`:

```java
@ChaosTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "spring.datasource.url=jdbc:h2:mem:chaos")
class ApiEndpointChaosTest { ... }
```

### 3.2 Lifecycle Behaviour

The Spring test starter's `ChaosAgentExtension` uses `BeforeAllCallback`/`AfterAllCallback` — one session per test **class**. All test methods in the class share the same session. This is appropriate for `@SpringBootTest` because the application context is shared across methods in the same class.

The testkit module's `ChaosAgentExtension` (for plain JUnit 5 without Spring) uses `BeforeEachCallback`/`AfterEachCallback` — one session per test **method**.

### 3.3 Injecting Diagnostics

`ChaosDiagnostics` is not directly injectable. Obtain it from the control plane:

```java
@Test
void inspectActiveScenarios(ChaosControlPlane controlPlane) {
    ChaosDiagnostics.Snapshot snapshot = controlPlane.diagnostics().snapshot();
    assertThat(snapshot.scenarios())
        .noneMatch(report -> report.state() == ChaosDiagnostics.ScenarioState.ACTIVE);
}
```

### 3.4 Nested Tests

`@Nested` classes within a `@ChaosTest` outer class automatically inherit the same session. The context hierarchy walk in `ParameterResolver.resolveParameter()` finds the session stored in the outer class's `ExtensionContext`:

```java
@ChaosTest
class OrderServiceTest {
    @Nested
    class WhenDatabaseIsFlaky {
        @Test
        void shouldTimeOut(ChaosSession session) {
            // Same session as the outer class
        }
    }
}
```

---

## 4. Plain JUnit 5 Without Spring

### 4.1 `@ExtendWith(ChaosAgentExtension.class)` — Per-Method Sessions

```java
@ExtendWith(ChaosAgentExtension.class)
class ExecutorChaosTest {

    @Test
    void submitIsDelayed(ChaosSession session) throws Exception {
        session.activate(ChaosScenario.builder("slow-pool")
            .scope(ChaosScenario.ScenarioScope.SESSION)
            .selector(ChaosSelector.executor(
                Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.delay(Duration.ofMillis(400)))
            .build());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ChaosSession.ScopeBinding scope = session.bind()) {
            long start = System.nanoTime();
            Future<?> f = executor.submit(() -> {});
            // The submit itself is delayed 400 ms on the submitting thread; the task then runs at once
            assertThat(Duration.ofNanos(System.nanoTime() - start))
                .isGreaterThanOrEqualTo(Duration.ofMillis(400));
            f.get(1, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
        }
    }
}
```

### 4.2 `ChaosTestKit` — Manual Lifecycle

Use `ChaosTestKit` when you are not using JUnit 5 extensions — benchmarks, TestNG, custom test runners:

```java
// In @BeforeAll or equivalent setup:
ChaosControlPlane controlPlane = ChaosTestKit.install();

// In each test:
try (ChaosSession session = controlPlane.openSession("test-name")) {
    session.activate(scenario);
    try (ChaosSession.ScopeBinding scope = session.bind()) {
        // chaos active on this thread
        callSubjectUnderTest();
    }
}   // session.close() stops and unregisters all session-scoped scenarios
```

### 4.3 Gate Effect — Unblocking Assertions

The `GateEffect` blocks the intercepted thread until `handle.release()` is called. `release()` re-arms the gate, so a later matching call blocks again until the next `release()` (or until the scenario is stopped/closed). Use it when you need to assert the system's state **while** an operation is in progress:

```java
ChaosActivationHandle handle = session.activate(
    ChaosScenario.builder("block-socket-connect")
        .scope(ChaosScenario.ScenarioScope.SESSION)
        .selector(ChaosSelector.network(Set.of(OperationType.SOCKET_CONNECT), NamePattern.any()))
        .effect(ChaosEffect.gate(Duration.ofSeconds(10)))   // the call proceeds after 10 s at the latest
        .build());

// Run the connect on a thread bound to the session
Thread worker = new Thread(session.wrap(() -> service.connect()));
worker.start();

// Wait until the connect is held at the gate: the worker parks there with the gate's timeout
while (worker.getState() != Thread.State.TIMED_WAITING) {
    Thread.onSpinWait();
}
assertThat(worker.isAlive()).isTrue();   // service.connect() is in progress, not complete

// Release the gate; service.connect() resumes
handle.release();
worker.join(5_000);
assertThat(worker.isAlive()).isFalse();
```

A release that comes before the call reaches the gate does not let that call through: on an active scenario the gate closes again at once, so wait until the call is held (as above) before releasing. A gate with a `maxBlock` lets the call proceed when the time is up; it does not throw.

---

## 5. CI/CD Pipeline Chaos Gates

### 5.1 The Concept

Traditional chaos engineering happens during Game Days: an operations team fires scenarios at a running production system and observes the outcome. This requires coordination, monitoring, and manual sign-off. There is no build artifact that captures "this version of the code passed chaos testing."

This agent enables a different model: **chaos as a build gate**. The chaos scenarios run in the same JVM fork as the JUnit test suite. If fault-tolerance assertions fail, the test fails, and the build fails. No coordination required. Every PR that touches a service boundary can include a chaos test that verifies the retry and timeout logic is intact.

This is genuinely novel: the combination of bytecode-level in-process chaos injection, per-test session isolation, and standard JUnit 5 assertions creates a reproducible, deterministic chaos test that runs in CI with no external dependencies.

### 5.2 Structuring a Pipeline Chaos Test

A good pipeline chaos test has three parts:

1. **Activate the scenario** — define the fault and when it fires
2. **Exercise the subject** — call the code under normal test conditions
3. **Assert fault-tolerance behaviour** — verify retries, fallbacks, circuit breakers, timeout logic

```java
@ChaosTest
class PaymentGatewayResilienceTest {

    @Autowired PaymentGatewayClient client;

    @Test
    @DisplayName("HTTP send to payment gateway retries on transient 5xx")
    void shouldRetryOnTransient5xx(ChaosSession session) throws Exception {
        // Reject the first HTTP call: the client sees an IOException, as for a failed connection
        session.activate(ChaosScenario.builder("payment-5xx")
            .scope(ChaosScenario.ScenarioScope.SESSION)
            .selector(ChaosSelector.httpClient(
                Set.of(OperationType.HTTP_CLIENT_SEND),
                NamePattern.glob("https://payments.internal/*")))
            .effect(ChaosEffect.reject("chaos: 503"))
            .activationPolicy(ActivationPolicy.builder()
                .maxApplications(1L)    // fire exactly once
                .build())
            .build());

        try (ChaosSession.ScopeBinding scope = session.bind()) {
            // Client must retry internally; call must succeed on the second attempt
            PaymentResult result = client.charge(testOrder());
            assertThat(result.status()).isEqualTo(PaymentStatus.APPROVED);
        }
    }

    @Test
    @DisplayName("Database connection exhaustion triggers circuit-breaker open")
    void shouldOpenCircuitBreakerOnConnectionExhaustion(ChaosSession session) {
        // Reject every JDBC connection acquisition: the pool's caller sees an SQLException
        session.activate(ChaosScenario.builder("db-pool-exhausted")
            .scope(ChaosScenario.ScenarioScope.SESSION)
            .selector(ChaosSelector.jdbc(OperationType.JDBC_CONNECTION_ACQUIRE))
            .effect(ChaosEffect.reject("chaos: pool full"))
            .activationPolicy(ActivationPolicy.always())
            .build());

        try (ChaosSession.ScopeBinding scope = session.bind()) {
            // After N failures, the circuit breaker should be OPEN
            for (int i = 0; i < 5; i++) {
                assertThatThrownBy(() -> client.charge(testOrder()))
                    .isInstanceOf(ServiceUnavailableException.class);
            }
            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }
    }
}
```

### 5.3 Gradle CI Configuration

```kotlin
// build.gradle.kts
tasks.test {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading") // optional: avoids the JEP 451 warning

    // Separate the chaos test suite so it can be run in parallel with unit tests
    // but excluded from the fast feedback loop if desired
    filter {
        // All tests in packages ending in .chaos or .resilience
        includeTestsMatching("*.chaos.*")
        includeTestsMatching("*.resilience.*")
    }
}

tasks.register<Test>("chaosTest") {
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    jvmArgs("-XX:+EnableDynamicAgentLoading") // optional: avoids the JEP 451 warning
    // Run after unit tests so failures are attributed correctly
    shouldRunAfter(tasks.test)
}
```

### 5.4 GitHub Actions Example

```yaml
jobs:
  chaos-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Unit tests
        run: ./gradlew test

      - name: Chaos tests (resilience gate)
        run: ./gradlew chaosTest
        # Fail the build if any chaos test fails
        # No additional flags needed: JUnit failure = non-zero exit code

      - name: Archive chaos test results
        uses: actions/upload-artifact@v4
        if: always()
        with:
          name: chaos-test-results
          path: build/reports/tests/chaosTest/
```

### 5.5 Why This Matters

Every commit that passes the chaos gate carries an implicit proof: **the fault-tolerance logic is correct for the scenarios in the suite**. When a developer weakens a retry policy, removes a timeout, or introduces a new call site without chaos coverage, the gate catches it before merge.

This shifts chaos engineering from episodic (quarterly Game Days, manual sign-off) to continuous (every PR, automated assertion). The feedback loop shrinks from days to minutes.

---

## 6. Docker and Container Deployments

### 6.1 Adding the Agent to a Docker Image

```dockerfile
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

COPY build/libs/your-application.jar app.jar
COPY build/libs/chaos-agent-bootstrap-<version>.jar chaos-agent.jar

# Mount a chaos plan at runtime via environment variable or file
ENV MACSTAB_CHAOS_CONFIG_FILE=/etc/chaos/plan.json

ENTRYPOINT ["java", \
    "-javaagent:/app/chaos-agent.jar", \
    "-jar", "/app/app.jar"]
```

### 6.2 Passing Config at Runtime

Prefer environment variables over file mounts for immutable container images:

```bash
# Inline JSON (small plans)
docker run \
  -e MACSTAB_CHAOS_CONFIG_JSON='{"metadata":{"name":"delay-db"},"scenarios":[...]}' \
  your-image:latest

# Base64-encoded JSON (avoids shell quoting issues with complex plans)
PLAN_B64=$(base64 -w0 < chaos-plan.json)
docker run \
  -e MACSTAB_CHAOS_CONFIG_BASE64="${PLAN_B64}" \
  your-image:latest

# File mount (for large plans or dynamic updates)
docker run \
  -v $(pwd)/chaos-plan.json:/etc/chaos/plan.json \
  -e MACSTAB_CHAOS_CONFIG_FILE=/etc/chaos/plan.json \
  your-image:latest
```

### 6.3 Kubernetes Pod Spec

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: order-service
spec:
  template:
    spec:
      initContainers:
        - name: copy-chaos-agent
          image: your-registry/chaos-agent:1.0.0
          command: ["cp", "/chaos-agent-bootstrap.jar", "/shared/chaos-agent.jar"]
          volumeMounts:
            - name: agent-volume
              mountPath: /shared
      containers:
        - name: order-service
          image: your-registry/order-service:latest
          env:
            - name: JAVA_TOOL_OPTIONS
              value: "-javaagent:/shared/chaos-agent.jar"
            - name: MACSTAB_CHAOS_CONFIG_FILE
              value: /etc/chaos/plan.json
          volumeMounts:
            - name: agent-volume
              mountPath: /shared
            - name: chaos-config
              mountPath: /etc/chaos
      volumes:
        - name: agent-volume
          emptyDir: {}
        - name: chaos-config
          configMap:
            name: chaos-plan
```

### 6.4 Soak Test Container

For load-soak testing with chaos, the agent can be loaded into a running JVM through the JDK Attach API. The agent ships no attach command line tool; any program that uses `com.sun.tools.attach.VirtualMachine` (module `jdk.attach`) loads it, and `agentmain` reads the same agent arguments as `-javaagent:`. The target JVM must opt in at its start, or `agentmain` does nothing:

```bash
# On the target JVM, at startup (JAVA_TOOL_OPTIONS or the container entrypoint):
#   -Dmacstab.chaos.allow-dynamic-attach=true   (or MACSTAB_CHAOS_ALLOW_DYNAMIC_ATTACH=true)
```

```java
// Run as the target's user, from a process that sees the target's PID (same container, or a shared PID namespace);
// the jar and plan paths are read by the target JVM.
VirtualMachine vm = VirtualMachine.attach("<target-pid>");
try {
    vm.loadAgent("/agents/chaos-agent-bootstrap.jar", "configFile=/plans/soak-plan.json");
} finally {
    vm.detach();
}
```

On JDK 21 and later the target prints the JEP 451 warning for the dynamically loaded agent (§2.4); a target started with `-XX:-EnableDynamicAgentLoading` refuses the load.

---

## 7. Runtime Control — JMX, Startup Config, File Watch

### 7.1 Startup Configuration Sources

Sources are evaluated in priority order; first match wins:

| Priority | Source                                 | How to provide                                          |
|----------|----------------------------------------|---------------------------------------------------------|
| 1        | Agent arg `configJson`                 | `-javaagent:...=configJson={"scenarios":[...]}`         |
| 2        | System property `macstab.chaos.configJson` | `-Dmacstab.chaos.configJson='...'`                  |
| 3        | Env `MACSTAB_CHAOS_CONFIG_JSON`        | `export MACSTAB_CHAOS_CONFIG_JSON='...'`                |
| 4        | Agent arg `configBase64`               | `-javaagent:...=configBase64=<b64>`                     |
| 5        | System property `macstab.chaos.configBase64` | `-Dmacstab.chaos.configBase64=<b64>`              |
| 6        | Env `MACSTAB_CHAOS_CONFIG_BASE64`      | `export MACSTAB_CHAOS_CONFIG_BASE64=<b64>`              |
| 7        | Agent arg `configFile`                 | `-javaagent:...=configFile=/etc/chaos/plan.json`        |
| 8        | System property `macstab.chaos.configFile` | `-Dmacstab.chaos.configFile=/etc/chaos/plan.json`   |
| 9        | Env `MACSTAB_CHAOS_CONFIG_FILE`        | `export MACSTAB_CHAOS_CONFIG_FILE=/etc/chaos/plan.json` |

If no source is present, the agent starts with zero active scenarios.

### 7.2 Minimal JSON Plan

```json
{
  "metadata": { "name": "db-delay-soak" },
  "scenarios": [
    {
      "id": "delay-jdbc-acquire",
      "scope": "JVM",
      "selector": {
        "type": "jdbc",
        "operations": ["JDBC_CONNECTION_ACQUIRE"]
      },
      "effect": {
        "type": "delay",
        "minDelay": "PT0.1S",
        "maxDelay": "PT0.5S"
      },
      "activationPolicy": {
        "probability": 0.1
      }
    }
  ]
}
```

Duration fields use ISO-8601: `"PT0.5S"` = 500 ms, `"PT30S"` = 30 s, `"PT1M"` = 1 min.

### 7.3 Debug Dump at Startup

Add `debugDumpOnStart` (alias `debugDump`) to the agent args to print a diagnostics dump to stderr after the startup plan is activated:

```
-javaagent:chaos-agent-bootstrap.jar=configFile=/etc/chaos/plan.json;debugDumpOnStart=true
```

Agent args are separated by `;`, so quote the argument in a shell. `MACSTAB_CHAOS_DEBUG_DUMP_ON_START=true` (also `1`, `yes`, `on`) does the same.

### 7.4 JMX MBean

The agent registers a `ChaosDiagnostics` MBean at `com.macstab.chaos.jvm:type=ChaosDiagnostics`. Connect with `jconsole`, `jvisualvm`, or any JMX client:

```
# Via jconsole
Connect to: service:jmx:rmi:///jndi/rmi://localhost:9999/jmxrmi
MBean: com.macstab.chaos.jvm:type=ChaosDiagnostics
  → debugDump()          — full human-readable diagnostic text
  → listScenarios()      — one "<id> state=<state> matched=<n> applied=<n>" line per scenario
  → ControlEnabled       — attribute: whether the operations below are allowed
  → activatePlan(json)   — activates a plan (startup-config JSON)
  → stopScenario(id)     — stops a scenario activated through activatePlan
  → stopAll()            — stops every scenario activated through activatePlan
```

`debugDump()`, `listScenarios()` and `ControlEnabled` are always available. The control operations (`activatePlan`, `stopScenario`, `stopAll`) are off by default, since anyone who can reach the MBean server could inject faults: enable them with the agent argument `jmxControl=true`, `-Dmacstab.chaos.jmx.control=true` or `MACSTAB_CHAOS_JMX_CONTROL=true`, and secure the JMX connector. See [observability.md §4](observability.md#4-jmx). For structured data use `controlPlane.diagnostics().snapshot()` in code.

### 7.5 Spring Actuator Endpoint (Runtime Starter)

Add the runtime starter, with `spring-boot-starter-actuator` on the class path, to expose a `/chaos` Actuator endpoint. Both `macstab.chaos.enabled` and `macstab.chaos.actuator.enabled` default to `false`:

```kotlin
implementation("com.macstab.chaos.jvm:chaos-agent-spring-boot3-starter:<version>")
```

```yaml
# application.yml
macstab:
  chaos:
    enabled: true
    actuator:
      enabled: true
management:
  endpoints:
    web:
      exposure:
        include: chaos
```

Both switches are honoured only from a launch-time local source (the application's own config files, system properties, environment variables, the command line); from a config server only once `macstab.chaos.allow-remote-enable=true` comes from such a source. See [SECURITY.md](../SECURITY.md#where-enabling-switches-may-come-from).

```bash
# Get current scenario state
curl http://localhost:8080/actuator/chaos

# Activate a plan — the body wraps the plan JSON as an escaped string in "planJson"
curl -X POST http://localhost:8080/actuator/chaos \
  -H "Content-Type: application/json" \
  -d '{"planJson": "{\"metadata\":{\"name\":\"live-delay\"},\"scenarios\":[{\"id\":\"jdbc-delay\",\"scope\":\"JVM\",\"selector\":{\"type\":\"jdbc\",\"operations\":[\"JDBC_STATEMENT_EXECUTE\"]},\"effect\":{\"type\":\"delay\",\"minDelay\":\"PT0.2S\",\"maxDelay\":\"PT0.2S\"}}]}"}'
# → {"status":"activated","handleId":"plan:live-delay",...}; on failure 400 invalid, 409 conflict or 422 rejected (see observability.md §4a)

# Stop that plan (by handle id "plan:live-delay" or by plan name)
curl -X DELETE http://localhost:8080/actuator/chaos/live-delay

# Stop every plan activated through the endpoint; later activations work normally
curl -X DELETE http://localhost:8080/actuator/chaos
```

Stopping by a scenario id that belongs to a plan answers `"unmanaged"` with a hint to stop the plan instead.

---

## 8. Diagnostics and Observability

### 8.1 Snapshot API

```java
ChaosDiagnostics.Snapshot snapshot = controlPlane.diagnostics().snapshot();

for (ChaosDiagnostics.ScenarioReport report : snapshot.scenarios()) {
    System.out.printf("%-30s  state=%-10s  matched=%d  applied=%d%n",
        report.id(),
        report.state(),
        report.matchedCount(),
        report.appliedCount());
}
```

`matchedCount` counts how many times the selector matched an intercepted call. `appliedCount` counts how many times the effect actually fired (after probability, rate limit, warm-up, and max-applications checks). The difference is the number of calls suppressed by policy.

### 8.2 Event Listener

```java
controlPlane.addEventListener(event -> {
    if (event.type() == ChaosEvent.Type.APPLIED) {
        metrics.increment("chaos.effect.applied",
            "scenario", event.scenarioId(),
            "operation", event.attributes().get("operation"));
    }
});
```

Events fire synchronously on the intercepting thread. Keep listeners fast; do not block or call instrumented JDK methods from inside a listener.

Published event types:

| Type       | Fired when                                                                                                                            |
|------------|---------------------------------------------------------------------------------------------------------------------------------------|
| `STARTED`  | Scenario transitions to ACTIVE                                                                                                        |
| `APPLIED`  | A contribution took effect on an intercepted call — every matching delay, the winning gate, the winning terminal action by precedence |
| `RELEASED` | `GateEffect` gate opened via `handle.release()`                                                                                       |
| `STOPPED`  | `handle.stop()`, `handle.close()` or `controlPlane.close()`, once per scenario                                                        |
| `INACTIVE` | The scenario ended on its own: `reason` is `expired` (`activeFor`, `activeUntil`) or `max applications reached`                       |

The runtime also publishes `REGISTERED` (before a scenario starts), `SKIPPED` (the policy kept a matched effect, with the check as `reason`; only while a listener is registered) and `FAILED` (an activation failed, with its `category`); see [observability.md §2](observability.md#2-events). A scenario that loses on precedence publishes no `APPLIED` event and does not consume its `maxApplications` budget.

### 8.3 Debug Dump

```java
String dump = controlPlane.diagnostics().debugDump();
System.out.println(dump);
// Outputs a full human-readable report of all registered scenarios,
// their state, counters, and the current interception surface.
```

Use `debugDump()` for incident investigation. The JMX MBean exposes the same string.

---

## 9. Effect and Selector Recipes

### 9.1 Selectors

```java
// All executor submits matching any thread pool
ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT))

// Platform thread starts whose thread name matches a glob (the factory takes no name filter; the record does)
new ChaosSelector.ThreadSelector(
    Set.of(OperationType.THREAD_START), ChaosSelector.ThreadKind.PLATFORM, NamePattern.glob("worker-*"), null)

// Socket connects to a specific host prefix
ChaosSelector.network(Set.of(OperationType.SOCKET_CONNECT), NamePattern.prefix("db.internal."))

// JDBC connection acquires from any pool
ChaosSelector.jdbc(OperationType.JDBC_CONNECTION_ACQUIRE)

// Synchronous HTTP sends to a specific URL pattern
ChaosSelector.httpClient(
    Set.of(OperationType.HTTP_CLIENT_SEND),
    NamePattern.glob("https://payments.internal/*"))

// Both sync and async HTTP sends
ChaosSelector.httpClient(
    Set.of(OperationType.HTTP_CLIENT_SEND, OperationType.HTTP_CLIENT_SEND_ASYNC),
    NamePattern.any())

// System clock reads
ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_CLOCK_MILLIS, OperationType.INSTANT_NOW))
```

### 9.2 Effects

```java
// Fixed delay
ChaosEffect.delay(Duration.ofMillis(200))

// Random delay between 100 ms and 500 ms
ChaosEffect.delay(Duration.ofMillis(100), Duration.ofMillis(500))

// Reject with exception (type depends on selector: IOException for network, SQLException for JDBC)
ChaosEffect.reject("chaos: connection refused")

// Suppress (return null / 0 / false / empty as appropriate for the return type)
ChaosEffect.suppress()

// Block until handle.release() is called (at most 30 s, then the call proceeds)
ChaosEffect.gate(Duration.ofSeconds(30))

// Inject an exception of a java./javax. class, checked ones too (MethodSelector with METHOD_ENTER only)
ChaosEffect.injectException("java.io.IOException", "chaos injected")

// Corrupt a return value (MethodSelector with METHOD_EXIT only)
ChaosEffect.corruptReturnValue(ChaosEffect.ReturnValueStrategy.NULL)

// Clock skew: advance java.time and java.util.Date reads by 5 seconds
ChaosEffect.skewClock(Duration.ofSeconds(5), ChaosEffect.ClockSkewMode.FIXED)

// Heap pressure: allocate and retain 64 MiB until scenario stops
ChaosEffect.heapPressure(64L * 1024 * 1024, 1024 * 1024)

// Deadlock: spawn two threads that acquire locks in opposite order
// (a StressSelector, JVM scope, and ActivationPolicy.withDestructiveEffects())
ChaosEffect.deadlock(2)
```

### 9.3 Activation Policies

```java
// Always fire
ActivationPolicy.always()

// Fire on 10% of matching calls
ActivationPolicy.builder().probability(0.1).build()

// Fire at most 5 times per second
ActivationPolicy.builder().rateLimit(5, Duration.ofSeconds(1)).build()

// Skip the first 10 matches (warm-up), then fire
ActivationPolicy.builder().activateAfterMatches(10).build()

// Only fire within the first 30 seconds after activation
ActivationPolicy.builder().activeFor(Duration.ofSeconds(30)).build()

// Fire exactly once
ActivationPolicy.builder().maxApplications(1L).build()

// Manual start: scenario registered but not active until handle.start() is called
ActivationPolicy.builder().startMode(ActivationPolicy.StartMode.MANUAL).build()

// Compose: fire 20% of the time, at most 100 total applications, over 60 seconds
ActivationPolicy.builder()
    .probability(0.2)
    .maxApplications(100L)
    .activeFor(Duration.ofSeconds(60))
    .build()
```

---

## 10. Performance Budget

Dispatch cost per intercepted call, measured with JMH on JDK 25 (Apple Silicon; `ChaosRuntimeBenchmark`, see `docs/benchmarks.md` for the full methodology and the targets):

| Scenario                                                   | Measured | Production impact                                  |
|------------------------------------------------------------|----------|----------------------------------------------------|
| Agent installed, zero active scenarios                     | ~2 ns    | Invisible; below noise floor of any I/O operation  |
| One scenario on the operation, its regex doesn't match     | ~90 ns   | < 0.5% of a 20 µs loopback TCP call                |
| One exhausted scenario left registered                     | ~16 ns   | Invisible; close the handle anyway                 |
| Ten scenarios on the operation (nine regex non-matches)    | ~295 ns  | 3% of a 10 µs pool acquire — use only transiently  |
| Session-scoped scenario, calling thread outside the session | ~17 ns  | Invisible; about 14 ns over zero scenarios         |

The agent is designed to be permanently installed in production. A woven call with no scenario for its operation costs 7–12 ns through the `BootstrapDispatcher` path and allocates nothing (`BridgeDispatchBenchmark`, [benchmarks.md §8](benchmarks.md#8-bridgedispatchbenchmark--the-woven-path)). Hook groups are woven on demand, so call sites whose hook group no scenario has needed yet are not instrumented at all.

---

## 11. Limitations and Known Constraints

### `System.currentTimeMillis()` and `System.nanoTime()` Are Skewed Only at Opted-In Call Sites

Both methods are JVM intrinsics with `@IntrinsicCandidate` annotations. The JIT replaces their call sites with CPU instructions — no method body is executed, so advice cannot be woven into them. The clock skew effect targets the higher-level `java.time` APIs (`Instant.now()`, `LocalDateTime.now()`, `ZonedDateTime.now()`) and `new Date()` out of the box. Direct `System.currentTimeMillis()` / `nanoTime()` calls are skewed when their callers are named with `clockCallSites=com.example.,org.acme.` (agent arg; `-Dmacstab.chaos.clockCallSites` or `MACSTAB_CHAOS_CLOCK_CALL_SITES`) and a `SYSTEM_CLOCK_MILLIS` / `SYSTEM_CLOCK_NANOS` scenario is active: the agent rewrites the call sites in those packages (see [configuration-reference.md](configuration-reference.md)). JDK and library code outside the prefixes keeps the real clock.

### `java.net.http.HttpClient` Is Intercepted Out of the Box

The agent self-grants the required `--add-opens java.net.http/jdk.internal.net.http=ALL-UNNAMED` at install time — via the `Add-Opens` manifest attribute on `-javaagent:` startup attach, and via `Instrumentation#redefineModule` for runtime self-attach (`agentmain`, test starters, `ByteBuddyAgent.install`). **Users do not need to add `--add-opens` JVM flags for any built-in chaos selector**, including `HttpClientSelector(HTTP_CLIENT_SEND/_ASYNC)` which targets `jdk.internal.net.http.HttpClientImpl`. See [Module access strategy](instrumentation.md#module-access-strategy) for the full set of opens granted.

### Hooks Are Woven on Demand

The agent does not weave its hooks at start. Every interception point belongs to a hook group (`THREAD_POOL_EXECUTOR`, `SOCKET`, `HTTP_CLIENT`, `JDBC`, …) that is woven by retransformation the first time a scenario needing one of its operations is activated (or a session is opened, for executor decoration). A JVM without scenarios runs unmodified JDK classes; the first activation of a group pays its one-time weaving cost, and a weaving failure fails that activation with failure category `INSTRUMENTATION_FAILURE`. Set `instrumentation=eager` (agent argument), `-Dmacstab.chaos.instrumentation=eager` or `MACSTAB_CHAOS_INSTRUMENTATION=eager` to weave every group at start, as in 1.x. The woven groups are listed under `wovenHookGroups` in `ChaosDiagnostics.snapshot().runtimeDetails()`.

Because every group is woven through retransformation, dynamic attach via `VirtualMachine.attach()` (the `agentmain` path, which still requires its explicit opt-in) and self-attach get exactly the same hooks as `-javaagent:` — sockets, NIO, HTTP, JDBC, clock, queues and the rest.

### Instrumentation Stays Installed for the JVM Lifetime

Stopping or closing a scenario removes its effect from the dispatch hot path, but once a hook group has been woven its advice bytecode remains in the JDK methods — there is no public uninstall. Internally, `JdkInstrumentationInstaller.uninstall(Instrumentation)` resets the ByteBuddy transformations of every woven group and clears the bridge delegate; it is used to fully roll back a failed initialization, so a later attempt starts from a clean JVM. Classes already loaded through the bootstrap bridge jar stay on the bootstrap class path either way.

### One Copy of the Agent per JVM

If a second copy of the agent, loaded by a different class loader (for example an agent jar on the system class path plus a starter bundled child-first in a web application), tries to install, it is refused with an `IllegalStateException`. Remove the duplicate jar or use the copy that is already installed.

### Thread Safety of Scenarios

All `ChaosActivationHandle` methods are thread-safe. Scenario evaluation (`ScenarioController.evaluate()`) is mostly lock-free (AtomicLong for counters; a short lock only for the `maxApplications` reservation and the fixed rate-limit window). Multiple threads can match the same scenario simultaneously.

---

## 12. Contributing

[CONTRIBUTING.md](../CONTRIBUTING.md) is the reference for building, the quality gates, adding an interception point or a stressor, and pull requests. In short:

### Build Requirements

- JDK 25 installed for building (the Gradle toolchain; Gradle finds installed JDKs but does not download one); JDK 21 or later to run the agent
- Gradle wrapper included (`./gradlew`)

### Build and Test

```bash
./gradlew build                        # compile, format check, tests, coverage floors
./gradlew test -PtestJavaVersion=21    # run the tests on another installed JDK
./gradlew :chaos-agent-benchmarks:run  # JMH benchmarks (takes several minutes)
```

### Code Style

The project uses [Google Java Format](https://github.com/google/google-java-format) enforced by [Spotless](https://github.com/diffplug/spotless). Before committing:

```bash
./gradlew spotlessApply     # auto-format all sources
./gradlew spotlessCheck     # verify formatting (run by build)
```

The CI build runs `spotlessCheck` and fails on any formatting violation. The Google Java Format version is pinned in `gradle/libs.versions.toml`.

### Module Structure

| Module                                      | Role                                                                                                                                                |
|---------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------|
| `chaos-agent-api`                           | Public API types: `ChaosScenario`, `ChaosSelector`, `ChaosEffect`, `ActivationPolicy`, `ChaosControlPlane`, `ChaosSession`, `ChaosActivationHandle` |
| `chaos-agent-core`                          | Scenario evaluation engine: `ChaosRuntime`, `ScenarioRegistry`, `ScenarioController`, the stressors                                                 |
| `chaos-agent-instrumentation-jdk`           | ByteBuddy advice, bootstrap bridge, `BootstrapDispatcher`, `BridgeDelegate`, `ChaosBridge`, `JdkInstrumentationInstaller`                           |
| `chaos-agent-bootstrap`                     | Agent entry point: `premain`, `agentmain`, self-attach, JMX MBean, config poller                                                                    |
| `chaos-agent-startup-config`                | JSON plan loading, `AgentArgsParser`                                                                                                                |
| `chaos-agent-testkit`                       | JUnit 5 `ChaosAgentExtension`, `@WithChaos`, `ChaosTestKit`, `ChaosAssertions`                                                                      |
| `chaos-agent-spring-boot-common`            | Spring logic shared by the Boot 3 and Boot 4 starters                                                                                               |
| `chaos-agent-spring-boot3-test-starter`     | Spring Boot 3 `@ChaosTest`, `ChaosAgentExtension` (one session per test class), `ChaosAgentInitializer`                                             |
| `chaos-agent-spring-boot4-test-starter`     | Spring Boot 4 equivalent                                                                                                                            |
| `chaos-agent-spring-boot3-starter`          | Spring Boot 3 Actuator endpoint, auto-configuration                                                                                                 |
| `chaos-agent-spring-boot4-starter`          | Spring Boot 4 equivalent                                                                                                                            |
| `chaos-agent-quarkus-extension`             | Quarkus extension (runtime + deployment modules; api and agent parent-first); CDI producer for `ChaosControlPlane`; `macstab.chaos.enabled` read via MicroProfile Config |
| `chaos-agent-quarkus-management`            | Optional Quarkus `/q/chaos` route (runtime + deployment modules)                                                                                    |
| `chaos-agent-micronaut-integration`         | Micronaut DI integration and `/chaos` management endpoint; `macstab.chaos.enabled` from system properties or the environment attaches the agent during context bootstrap; from a trusted config source (`application.yml`, `micronaut.config.files`, `@Property`) it attaches when the `ChaosControlPlane` bean is created |
| `chaos-agent-micrometer`                    | `ChaosMeterBinder`: the chaos metrics in Micrometer                                                                                                 |
| `chaos-agent-opentelemetry`                 | `ChaosOpenTelemetry`: the chaos metrics and span events in OpenTelemetry                                                                            |
| `chaos-agent-bom`                           | BOM that aligns the versions of the published modules                                                                                               |
| `chaos-agent-benchmarks`                    | JMH benchmark suite — not published                                                                                                                 |
| `chaos-agent-examples`                      | Reference applications — not published                                                                                                              |

### Writing a New Interception Point

Follow [CONTRIBUTING.md — Adding a new interception point](../CONTRIBUTING.md#adding-a-new-interception-point): the operation tag, the `BootstrapDispatcher` entry point and its slot, the advice class, its hook group, and the domain dispatcher.

### Running a Single Test Class

```bash
./gradlew :chaos-agent-core:test --tests "com.macstab.chaos.jvm.core.ScenarioControllerStateTest"
```

---

<div align="center">

*Architecture, implementation, and documentation crafted with Love and Passion by*

**[Christian Schnapka](https://macstab.com)**  
Embedded Principal+ Engineer  
[Macstab GmbH](https://macstab.com) · Hamburg, Germany

*Building systems that operate correctly at the edges — including the ones you deliberately break.*

</div>
