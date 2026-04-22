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

---

## 1. What This Is (and Is Not)

This agent intercepts 57 JDK operations — thread pools, sockets, NIO, JDBC, HTTP clients, clocks, queues, class loaders, and more — by rewriting JVM bytecode at agent attachment time. Every fault it injects is **real**: a real `ConnectException`, a real sleep, a real `OutOfMemoryError`. No mocks.

**This is not a network chaos proxy.** It operates entirely inside one JVM process. For network-layer chaos (packet loss, latency between processes) use a complementary tool like `tc netem` or Toxiproxy.

**This is not a sidecar.** There is no separate daemon, no Kubernetes webhook, no kernel eBPF module. The agent JAR attaches to the target JVM at startup or via the JDK Attach API.

The most novel capability is **CI/CD pipeline integration**: chaos scenarios run in the same JVM fork as the test suite, gate the build on fault-tolerance verification, and produce structured pass/fail output. This makes chaos testing a first-class build step, not an ops-team Game Day.

---

## 2. Installation

### 2.1 Gradle (Kotlin DSL)

```kotlin
dependencies {
    // Required for all usage — the API types and agent bootstrap
    testImplementation("com.macstab.chaos:chaos-agent-testkit:0.1.0-SNAPSHOT")

    // Spring Boot 3 test integration (choose one)
    testImplementation("com.macstab.chaos:chaos-agent-spring-boot3-test-starter:0.1.0-SNAPSHOT")
    // or Spring Boot 4:
    testImplementation("com.macstab.chaos:chaos-agent-spring-boot4-test-starter:0.1.0-SNAPSHOT")

    // Spring Boot 3 runtime starter (Actuator endpoint, live reload)
    implementation("com.macstab.chaos:chaos-agent-spring-boot3-starter:0.1.0-SNAPSHOT")
    // or Spring Boot 4:
    implementation("com.macstab.chaos:chaos-agent-spring-boot4-starter:0.1.0-SNAPSHOT")
}
```

The agent self-attaches at test time via the JDK Attach API — no `-javaagent:` flag required when using `chaos-agent-testkit` or the Spring test starters. For production or CLI usage, see §2.3.

### 2.2 Maven

```xml
<dependency>
    <groupId>com.macstab.chaos</groupId>
    <artifactId>chaos-agent-testkit</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
<!-- Or Spring Boot 3 test starter: -->
<dependency>
    <groupId>com.macstab.chaos</groupId>
    <artifactId>chaos-agent-spring-boot3-test-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

### 2.3 JVM Argument (Production / CI Startup)

For production deployments or CLI-driven chaos, attach at startup:

```
java -javaagent:chaos-agent-bootstrap-0.1.0-SNAPSHOT.jar \
     -jar your-application.jar
```

Pass a startup config via agent args or environment variable (see §7 for the full config reference):

```
java -javaagent:chaos-agent-bootstrap-0.1.0-SNAPSHOT.jar=configFile=/etc/chaos/plan.json \
     -jar your-application.jar
```

### 2.4 JVM Flag for Self-Attach

When the testkit or Spring starters self-attach, the JVM must allow self-attachment. Set this in your build:

```kotlin
// Gradle
tasks.test {
    jvmArgs("-Djdk.attach.allowAttachSelf=true")
}
```

```xml
<!-- Maven Surefire -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <argLine>-Djdk.attach.allowAttachSelf=true</argLine>
    </configuration>
</plugin>
```

The Spring test starters set `jdk.attach.allowAttachSelf` programmatically before self-attach, so you may omit it from the JVM args when using `@ChaosTest`. It is still required if you use `ChaosTestKit.install()` from non-Spring tests and your framework does not preset the property.

---

## 3. Spring Boot 3 / 4 — Test Starter

### 3.1 `@ChaosTest` — Minimal Usage

```java
@ChaosTest                   // meta-annotation: @SpringBootTest + @ExtendWith(ChaosAgentExtension.class)
class PaymentServiceTest {

    @Autowired PaymentService paymentService;

    @Test
    void shouldRetryOnTransientDatabaseFailure(ChaosSession session) throws Exception {
        // Inject a one-shot JDBC connection failure
        try (ChaosActivationHandle h = session.activate(
                ChaosScenario.builder("transient-db-fail")
                    .selector(ChaosSelector.jdbc(
                        Set.of(OperationType.JDBC_CONNECTION_ACQUIRE),
                        NamePattern.any()))
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

`@ChaosTest` accepts all `@SpringBootTest` attributes unchanged:

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
    assertThat(snapshot.activeScenarios()).isEmpty();
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
    void delayedExecutorShouldTriggerTimeout(ChaosSession session) throws Exception {
        session.activate(ChaosScenario.builder("slow-pool")
            .selector(ChaosSelector.executor(
                Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.delay(Duration.ofMillis(400)))
            .build());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ChaosSession.ScopeBinding scope = session.bind()) {
            Future<?> f = executor.submit(() -> Thread.sleep(10));
            // The submit itself is delayed 400 ms on this thread
            assertThatThrownBy(() -> f.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
        }
        executor.shutdown();
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
}   // session.close() stops all session-scoped scenarios
```

### 4.3 Gate Effect — Unblocking Assertions

The `GateEffect` blocks the intercepted thread until `handle.release()` is called. Use it when you need to assert the system's state **while** an operation is in progress:

```java
ChaosActivationHandle handle = session.activate(
    ChaosScenario.builder("block-socket-connect")
        .selector(ChaosSelector.network(Set.of(OperationType.SOCKET_CONNECT), NamePattern.any()))
        .effect(ChaosEffect.gate())
        .build());

// Trigger the connect in a background thread
CompletableFuture<Void> future = CompletableFuture.runAsync(() -> service.connect());

// Assert the connect is blocked (service.connect() is in progress but not complete)
assertThat(handle.state()).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);

// Release the gate; service.connect() resumes
handle.release();
future.get(5, TimeUnit.SECONDS);
```

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
        // Fire a suppress effect on the first HTTP call (simulates a 5xx that causes IOException)
        session.activate(ChaosScenario.builder("payment-5xx")
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
        // Suppress all JDBC connection acquisitions continuously
        session.activate(ChaosScenario.builder("db-pool-exhausted")
            .selector(ChaosSelector.jdbc(
                Set.of(OperationType.JDBC_CONNECTION_ACQUIRE), NamePattern.any()))
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
    jvmArgs("-Djdk.attach.allowAttachSelf=true")

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
    jvmArgs("-Djdk.attach.allowAttachSelf=true")
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
COPY build/libs/chaos-agent-bootstrap-0.1.0-SNAPSHOT.jar chaos-agent.jar

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
  -e MACSTAB_CHAOS_CONFIG_JSON='{"name":"delay-db","scenarios":[...]}' \
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
          image: your-registry/chaos-agent:0.1.0-SNAPSHOT
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

For load-soak testing with chaos, run a dedicated container that attaches via dynamic attach:

```bash
# Enable dynamic attach on the target JVM (must be set at JVM startup)
# Either via JAVA_TOOL_OPTIONS or in the container entrypoint:
# -Dmacstab.chaos.agentmain.enable=true

# Attach from a separate container sharing the target's PID namespace
kubectl exec -n chaos-lab <chaos-runner-pod> -- \
  java -cp chaos-agent-bootstrap.jar \
    com.macstab.chaos.bootstrap.ChaosAgentAttachCli \
    --pid <target-pid> \
    --config /plans/soak-plan.json
```

---

## 7. Runtime Control — JMX, Startup Config, File Watch

### 7.1 Startup Configuration Sources

Sources are evaluated in priority order; first match wins:

| Priority | Source | How to provide |
|----------|--------|----------------|
| 1 | Agent arg `configJson` | `-javaagent:...=configJson={"name":"..."}` |
| 2 | Env `MACSTAB_CHAOS_CONFIG_JSON` | `export MACSTAB_CHAOS_CONFIG_JSON='...'` |
| 3 | Agent arg `configBase64` | `-javaagent:...=configBase64=<b64>` |
| 4 | Env `MACSTAB_CHAOS_CONFIG_BASE64` | `export MACSTAB_CHAOS_CONFIG_BASE64=<b64>` |
| 5 | Agent arg `configFile` | `-javaagent:...=configFile=/etc/chaos/plan.json` |
| 6 | Env `MACSTAB_CHAOS_CONFIG_FILE` | `export MACSTAB_CHAOS_CONFIG_FILE=/etc/chaos/plan.json` |

If no source is present, the agent starts with zero active scenarios.

### 7.2 Minimal JSON Plan

```json
{
  "name": "db-delay-soak",
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

Add `debugDump=true` to the agent args to print the full plan on startup:

```
-javaagent:chaos-agent-bootstrap.jar=configFile=/etc/chaos/plan.json,debugDump=true
```

### 7.4 JMX MBean

The agent registers a `ChaosDiagnostics` MBean at `com.macstab.chaos:type=ChaosDiagnostics`. Connect with `jconsole`, `jvisualvm`, or any JMX client:

```
# Via jconsole
Connect to: service:jmx:rmi:///jndi/rmi://localhost:9999/jmxrmi
MBean: com.macstab.chaos:type=ChaosDiagnostics
  → debugDump()   — full human-readable diagnostic text
  → snapshot()    — structured Snapshot composite data
```

The MBean is read-only: it provides diagnostics but does not accept scenario activation commands. To activate scenarios at runtime, use the programmatic API or the Spring Actuator endpoint (runtime starter).

### 7.5 Spring Actuator Endpoint (Runtime Starter)

Add the runtime starter to expose a `/chaos` Actuator endpoint:

```kotlin
implementation("com.macstab.chaos:chaos-agent-spring-boot3-starter:0.1.0-SNAPSHOT")
```

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: chaos
```

```bash
# Get current scenario state
curl http://localhost:8080/actuator/chaos

# Activate a scenario
curl -X POST http://localhost:8080/actuator/chaos/scenarios \
  -H "Content-Type: application/json" \
  -d '{"id":"live-delay","scope":"JVM","selector":{"type":"jdbc","operations":["JDBC_STATEMENT_EXECUTE"]},"effect":{"type":"delay","minDelay":"PT0.2S","maxDelay":"PT0.2S"}}'

# Stop a scenario
curl -X DELETE http://localhost:8080/actuator/chaos/scenarios/live-delay
```

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
            "operation", event.operationType().name());
    }
});
```

Events fire synchronously on the intercepting thread. Keep listeners fast; do not block or call instrumented JDK methods from inside a listener.

All seven event types:

| Type | Fired when |
|------|-----------|
| `REGISTERED` | `activate()` is called; scenario is registered but not yet started |
| `STARTED` | Scenario transitions to ACTIVE |
| `APPLIED` | Effect fired on an intercepted call |
| `SKIPPED` | Selector matched but policy suppressed the effect (probability, rate limit, warm-up) |
| `FAILED` | Exception during effect execution — effect threw unexpectedly |
| `RELEASED` | `GateEffect` gate opened via `handle.release()` |
| `STOPPED` | `handle.stop()` or `close()` called |

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

// Thread starts with name matching a glob
ChaosSelector.thread(Set.of(OperationType.THREAD_START), NamePattern.glob("worker-*"))

// Socket connects to a specific host prefix
ChaosSelector.network(Set.of(OperationType.SOCKET_CONNECT), NamePattern.prefix("db.internal."))

// JDBC connection acquires from any pool
ChaosSelector.jdbc(Set.of(OperationType.JDBC_CONNECTION_ACQUIRE), NamePattern.any())

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

// Block until handle.release() is called (max 30 s, then throw)
ChaosEffect.gate(Duration.ofSeconds(30))

// Inject an arbitrary checked exception
ChaosEffect.exception("java.io.IOException", "chaos injected", true)

// Corrupt a return value
ChaosEffect.corruptReturnValue(ChaosEffect.ReturnValueStrategy.NULL)

// Clock skew: advance all clock reads by 5 seconds
ChaosEffect.clockSkew(ClockSkewEffect.Mode.FIXED, Duration.ofSeconds(5))

// Heap pressure: allocate and retain 64 MiB until scenario stops
ChaosEffect.heapPressure(64 * 1024 * 1024)

// Deadlock: spawn two threads that acquire locks in opposite order
ChaosEffect.deadlock()
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

Measured with JMH on JDK 21, x86-64 (see `docs/benchmarks.md` for the full methodology):

| Scenario | Overhead | Production impact |
|----------|----------|-----------------|
| Agent installed, zero active scenarios | < 50 ns | Invisible; below noise floor of any I/O operation |
| One scenario, selector doesn't match | < 100 ns | < 0.5% of a 20 µs loopback TCP call |
| One scenario, selector matches, no effect fires | < 300 ns | < 3% of a 10 µs HikariCP pool acquire |
| Ten scenarios, one match | < 1 µs | 10% of a 10 µs pool acquire — use only transiently |

The agent is designed to be permanently installed in production. With zero active scenarios, every intercepted call site costs approximately 5–15 ns for the `BootstrapDispatcher.invoke()` fast path.

---

## 11. Limitations and Known Constraints

### `System.currentTimeMillis()` and `System.nanoTime()` Are Not Directly Interceptable

Both methods are JVM intrinsics with `@IntrinsicCandidate` annotations. The JIT replaces their call sites with CPU instructions — no method body is executed, so advice cannot be woven. The clock skew effect targets the higher-level `java.time` APIs (`Instant.now()`, `LocalDateTime.now()`, `ZonedDateTime.now()`) and `new Date()` instead. Application code reading the clock through `System.currentTimeMillis()` directly is not skewed.

### `java.net.http.HttpClient` Is Not Intercepted

`jdk.internal.net.http.HttpClientImpl` lives in a non-exported JDK module. Including it in the same `AgentBuilder` pass as the rest of Phase 2 instrumentation causes other transformations to be silently dropped by the JVM's retransformation serialization pipeline. A dedicated second pass with `--add-opens java.net.http/jdk.internal.net.http=ALL-UNNAMED` is required and is not yet implemented. Use OkHttp, Apache HttpClient 4/5, or Spring `WebClient` (Reactor Netty) for interceptable HTTP.

### Dynamic Attach Installs Phase 1 Only

When attaching via `VirtualMachine.attach()` (the `agentmain` path), only Phase 1 instrumentation is installed: `ThreadPoolExecutor`, `Thread`, `ScheduledThreadPoolExecutor`. Phase 2 (socket, NIO, HTTP, JDBC, clock, queues, etc.) is skipped because those JDK classes are already loaded and retransforming them from a dynamic attach path risks silent bytecode corruption in live production JVMs. Use `-javaagent:` at startup for full Phase 2 coverage.

### Instrumentation Is Permanent

ByteBuddy transformations cannot be reversed within a JVM lifetime. Stopping a scenario removes its effect from the dispatch hot path, but the advice bytecode remains woven into the JDK methods. This is intentional: unweaving bytecode on a live JVM is not safe and is not a use case this agent supports.

### Thread Safety of Scenarios

All `ChaosActivationHandle` methods are thread-safe. Scenario evaluation (`ScenarioController.evaluate()`) is lock-free (AtomicLong for counters, CAS for maxApplications, synchronized only for rate-limit sliding window). Multiple threads can match the same scenario simultaneously.

---

## 12. Contributing

### Build Requirements

- JDK 21+
- Gradle wrapper included (`./gradlew`)
- No additional toolchain required

### Build and Test

```bash
./gradlew build             # compile, test, spotless check
./gradlew test              # unit tests only
./gradlew :chaos-agent-benchmarks:run  # JMH benchmarks (takes several minutes)
```

### Code Style

The project uses [Google Java Format](https://github.com/google/google-java-format) enforced by [Spotless](https://github.com/diffplug/spotless). Line length is 100 characters. Before committing:

```bash
./gradlew spotlessApply     # auto-format all sources
./gradlew spotlessCheck     # verify formatting (run by build)
```

The CI build runs `spotlessCheck` and fails on any formatting violation.

### Module Structure

| Module | Role |
|--------|------|
| `chaos-agent-api` | Public API types: `ChaosScenario`, `ChaosSelector`, `ChaosEffect`, `ActivationPolicy`, `ChaosControlPlane`, `ChaosSession`, `ChaosActivationHandle` |
| `chaos-agent-core` | Scenario evaluation engine: `ChaosRuntime`, `ChaosDispatcher`, `ScenarioRegistry`, `ScenarioController` |
| `chaos-agent-instrumentation-jdk` | ByteBuddy advice, bootstrap bridge, `BootstrapDispatcher`, `BridgeDelegate`, `ChaosBridge` |
| `chaos-agent-bootstrap` | Agent entry point: `premain`, `agentmain`, JMX MBean, singleton `ChaosRuntime` |
| `chaos-agent-startup-config` | JSON plan loading, `AgentArgsParser`, `ChaosPlanMapper` |
| `chaos-agent-testkit` | JUnit 5 extension, `ChaosTestKit`, `TrackingChaosControlPlane` |
| `chaos-agent-spring-boot3-test-starter` | Spring Boot 3 `@ChaosTest`, `ChaosAgentExtension` (BeforeAllCallback), `ChaosAgentInitializer` |
| `chaos-agent-spring-boot4-test-starter` | Spring Boot 4 equivalent |
| `chaos-agent-spring-boot3-starter` | Spring Boot 3 Actuator endpoint, auto-configuration |
| `chaos-agent-spring-boot4-starter` | Spring Boot 4 equivalent |
| `chaos-agent-quarkus-extension` | Quarkus CDI integration |
| `chaos-agent-micronaut-integration` | Micronaut DI integration |
| `chaos-agent-benchmarks` | JMH benchmark suite |

### Writing a New Interception Point

1. Add a constant to `BootstrapDispatcher` (increment `HANDLE_COUNT`)
2. Add the corresponding method to `BridgeDelegate`
3. Implement the method in `ChaosBridge` (thin delegation to `ChaosRuntime`)
4. Add the `@Advice` class in `JdkInstrumentationInstaller`
5. Wire the `AgentBuilder` matcher in `JdkInstrumentationInstaller.install()`
6. Add a new `OperationType` enum value in `chaos-agent-api`
7. Handle the new type in `SelectorMatcher` and `ChaosDispatcher`
8. Add a test in the appropriate `*Test` class

The HANDLE_COUNT is checked at startup via `buildMethodHandles()`: a mismatch between the constant count and the number of methods on `BridgeDelegate` throws immediately, so a missing step is caught at the first agent load.

### Running a Single Test Class

```bash
./gradlew :chaos-agent-core:test --tests "com.macstab.chaos.core.ScenarioControllerTest"
```

### Spotless and Google Java Format Version

The Google Java Format version is pinned in `gradle/libs.versions.toml`. Do not change it without updating the IntelliJ plugin version to match — format divergence between IDE and Spotless produces spurious diffs on every save.

---

<div align="center">

*Architecture, implementation, and documentation crafted with Love and Passion by*

**[Christian Schnapka](https://macstab.com)**  
Embedded Principal+ Engineer  
[Macstab GmbH](https://macstab.com) · Hamburg, Germany

*Building systems that operate correctly at the edges — including the ones you deliberately break.*

</div>
