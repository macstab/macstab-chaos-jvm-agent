<!--
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Engineered by  Christian Schnapka
                 Embedded Principal+ Engineer
                 Macstab GmbH · Hamburg, Germany
                 https://macstab.com
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-->

# Migrating from 1.x to 2.0

> Every change in 2.0 that can break a 1.x setup, who it affects, and what to change. The full list of changes is in the [CHANGELOG](../CHANGELOG.md).
>
> *Engineered by* **[Christian Schnapka](https://macstab.com)** — Embedded Principal+ Engineer · [Macstab GmbH](https://macstab.com) · Hamburg, Germany

---

## Checklist

| # | Change | Affects you if… | Action |
|---|--------|-----------------|--------|
| 1 | Socket scenarios also match socket channels | a `NetworkSelector` has no peer filter | add `remoteCidr` / `remotePort` (§1) |
| 2 | Legacy packages removed | you import `com.macstab.chaos.*` outside `.jvm` | switch to `com.macstab.chaos.jvm.*` (§2) |
| 3 | Plans parse only through `ChaosPlanMapper` | you parse plans with your own `ObjectMapper` | use `ChaosPlanMapper` (§3) |
| 4 | Clock-skew and stressor policies are validated; clock reads take only a skew | a clock skew sets probability, a budget or a rate limit; a stressor sets per-call policy fields; a clock read has another effect | remove them, use a time window (§4) |
| 5 | Hooks are woven on demand | you rely on hooks being in place before activation, or on start-up cost | nothing, or `instrumentation=eager` (§5) |
| 6 | The agent JAR is self-contained and shades its libraries | your code used the agent's ByteBuddy or Jackson | depend on them yourself (§6) |
| 7 | New abstract methods on API interfaces | you implement `ChaosControlPlane` or `ChaosActivationHandle` | implement them (§7) |
| 8 | `ChaosEffect.Errno.code()` is the current OS's number | you used a 2.0 pre-release (`Errno` is new in 2.0) and compare it with Linux numbers on macOS | use `linuxCode()` (§8) |
| 9 | `OperationType` gained constants between existing ones | you persist ordinals | persist names (§9) |
| 10 | Bridge handle slots renumbered | you call `BootstrapDispatcher` directly | none for applications (§10) |
| 11 | A glob's `*` also matches line terminators | a GLOB must not match across a line break | use a REGEX (§11) |
| 12 | `/actuator/chaos` answers with the operation's HTTP status | a client reads `status = "error"` from a 200 answer of the Spring endpoint, or stops the startup plan through it | check the HTTP status or the `invalid`, `conflict`, `rejected` outcomes (§12) |
| 13 | `reject` is refused where no real call fails | a plan rejects `THREAD_PARK`, `MONITOR_ENTER`, `SYSTEM_GC_REQUEST`, `SYSTEM_EXIT_REQUEST`, `THREAD_LOCAL_*`, `ASYNC_CANCEL`, `ZIP_DEFLATE`, `EXECUTOR_SHUTDOWN`, a worker or fork-join run, or a queue take or poll | use the effect the refusal names (§13) |
| 14 | Enabling switches are honoured only from local sources on Quarkus and Micronaut | a config server sets `macstab.chaos.enabled` or a management switch | set `macstab.chaos.allow-remote-enable=true` locally (§14) |
| 15 | `reject` / `suppress` on `QUEUE_OFFER` skip queues without a capacity limit | you saturate a pool on an unbounded queue through `QUEUE_OFFER` | reject `EXECUTOR_SUBMIT`, or give the queue a capacity (§15) |
| 16 | A pool task carries a session only once the session has a scenario | another service relies on `X-Chaos-Session` from pool tasks of a session without local scenarios | activate a scenario in the session first, or set the header from `session.id()` (§16) |
| 17 | Direct-buffer pressure above the direct memory limit is refused | a `directBufferPressure` asks for more than `-XX:MaxDirectMemorySize` (by default the maximum heap) | lower `totalBytes` or raise the limit (§17) |
| 18 | `suppress` on HTTP and JDBC is refused; `ChaosHttpSuppressException` / `ChaosJdbcSuppressException` are removed | a plan suppresses `HTTP_CLIENT_*` or `JDBC_*`, or code catches those exceptions | use `reject` and catch `IOException` / `SQLException` (§18) |
| 19 | `reject` and `errno` raise the real call's exception; errnos apply per operation | a test catches the 1.x type of a reject or reads its top stack frame; with a 2.0 pre-release (errnos are new in 2.0), a test catches the type of a file errno or applies one to a call the kernel never reports it for | catch the type in §19; pick an errno the call can report (§19) |
| 20 | The destructive-effect refusal is a `ChaosValidationException`; `suppress` is refused where a call cannot be skipped | code catches `ChaosActivationException` for a deadlock or thread leak without `allowDestructiveEffects`, or matches `plan rejected:` in the HTTP/JMX answer; a plan suppresses an operation outside the suppress list | catch `ChaosValidationException`, match `invalid plan:`; delay or reject instead (§20) |
| 21 | Stressor parameters are refused instead of reduced | a `heapPressure`, a `threadLocalLeak` (parallelism × `entriesPerThread` × (`valueSizeBytes` + about 176 bytes per entry)), a `referenceQueueFlood` cycle (`referenceCount` × 64 bytes) or a `stringInternPressure` (`internCount` × (`stringLengthBytes` + about 56 bytes)) asks for more than the free heap; a `stringInternPressure` exceeds its caps or its length's distinct strings; a `floodInterval`, `gcInterval` or `heartbeat` is below 1 ms | lower the value or raise `-Xmx`, lengthen the strings, use 1 ms or more (§21) |
| 22 | Unused `@Internal` `ChaosRuntime` overloads removed | code calls `beforeThreadStart(Thread)`, `beforeSocketRead(Object)`, `beforeSocketWrite(Object, int)` or `beforeFileIo(String, Object)` on `ChaosRuntime` | call the full forms (§22) |
| 23 | `@WithChaos` is active around the test method only | a `@BeforeEach` / `@AfterEach` method relies on a method-level (testkit: any) `@WithChaos` scenario | activate it in the set-up method instead (§23) |
| 24 | `ActivationPolicy.fromJson` is no longer public | you call it from code | use the constructor or `ActivationPolicy.builder()`, or read JSON with `ChaosPlanMapper` (§24) |
| 25 | Plan JSON is read strictly and written in ISO-8601 | a plan gives a duration or instant as a number, a number as a string, a fraction for an integer, `0`/`1` for a boolean, an ordinal for an enum or a field twice; or you read `GET /q/chaos` (`/chaos`, `/actuator/chaos`) or `ChaosPlanMapper.toJson` output as numbers | write ISO-8601 strings and plain JSON values; read `capturedAt` and durations as ISO-8601 strings (§25) |
| 26 | A name pattern is no longer canonicalised | a pattern has a mode without a value (`{"mode": "GLOB"}`, `NamePattern.glob(null)`), a value without a mode, or `ANY` with a value other than `"*"` | give the mode its value, or use `ANY` (`NamePattern.any()`) (§26) |
| 27 | The API's nullness is published (`@NullMarked`) | Kotlin code passes `null` where a type is non-null, or treats a non-null result as nullable | pass a value, or use the parameters marked `@Nullable` (§27) |
| 28 | Testkit count assertions under `within` hold for the whole duration | a test uses `within(t).never()` / `atMost(n)` as an instant check, calls `hasNoFailuresFor()` without ids, or passes a negative bound | drop `within` for an instant check; pass the ids or use `hasNoFailures()` (§28) |
| 29 | `chaos-agent-testkit` no longer brings JUnit | you get `junit-jupiter-api` (or the JUnit BOM's versions) only through the testkit or a starter that depends on it | declare JUnit in your test dependencies (§29) |
| 30 | `ChaosEvent.Type.INACTIVE`; lifecycle events once per transition | a `switch` over `ChaosEvent.Type` has no `default`; a listener counts `STOPPED` per `stop()` / `close()` call, or waits for `STOPPED` to learn that a scenario expired or used up `maxApplications` | add `case INACTIVE`; count one `STOPPED` per scenario; wait for `INACTIVE` (§30) |
| 31 | Apache HttpClient is evaluated at `doExecute`, with one URL rule | a scenario counts Apache HttpClient calls made from a response handler, a `urlPattern` matches the path-only URL of `executeOpen(null, …)` or the doubled URL of HC4 `execute(host, absoluteRequest)`, or you subclass `CloseableHttpClient` yourself | expect one more evaluation per request a handler sends; match the full URL; hook your own client differently (§31) |
| 32 | Client rejects fail through the library's own failure channel | a test expects a rejected asynchronous client call (a gRPC future or async stub, a Kafka send, a Lettuce async command, a JDK `sendAsync`, an OkHttp `enqueue`, a Reactor Netty request, an R2DBC `execute()`) to throw from the call that starts it, or counts gRPC `RPC_CALL` evaluations at `ClientCall.start` | expect the failure in the future, observer, callback or publisher (§32) |
| 33 | The JDK's own use of a general primitive is not a target (L3a) | a scenario with an unnarrowed selector expects to hit class loading, the `CompletableFuture` timers, `/dev/urandom`, the JDK `HttpClient`'s selector, JDK-private ThreadLocals, the serialization hooks or another `Method.invoke` of a JDK class, a pool worker's lock, or the agent's MBean; a test counts those evaluations | target your own code's calls; name `/dev/urandom` in a path pattern or a JDK `ThreadLocal` / `ThreadPoolExecutor$Worker` class in the pattern to reach it (§33) |
| 34 | Method hooks never weave JDK classes; match-all method selectors are refused | a `MethodSelector` names a JDK class (`java.*`, `jdk.*`, `sun.*`, a platform-loader module), or pairs two match-all patterns such as `glob("*")` and `regex(".*")` | target your own classes; narrow the class or the method pattern (§34) |
| 35 | `EXECUTOR_SUBMIT` covers scheduled and virtual-thread executors | a scenario counts or delays `EXECUTOR_SUBMIT` expecting only `ThreadPoolExecutor`, matches an STPE's `execute` / `submit` as `SCHEDULE_SUBMIT` | narrow by `executorClassPattern`; select the STPE submission as `EXECUTOR_SUBMIT` (§35) |
| 36 | Injected exceptions are JDK classes, resolved at activation | an `ExceptionInjectionEffect` names a `jakarta.` or agent class, or a class that is abstract, not public, not a `Throwable` or has no public `(String)` / no-argument constructor | name a public JDK exception class (§36) |
| 37 | Stressors without load are refused; a stressor that stops ends its scenario | a plan runs a `safepointStorm` with `retransformClassCount = 0` on a JVM with `-XX:+DisableExplicitGC`, a `finalizerBacklog` with `--finalization=disabled`, or a `virtualThreadCarrierPinning` without `allowDestructiveEffects` on a single-carrier scheduler; a test expects a `gcPressure` or `threadLeak` scenario to stay `ACTIVE` after its `duration` / `lifespan` | set `retransformClassCount` above 0, set `allowDestructiveEffects` for the carrier pinning, or drop the stressor on that JVM; expect `INACTIVE` (§37) |
| 38 | The event log line is quoted and escaped | you parse the `com.macstab.chaos.jvm` log lines (`type=… scenarioId=… attributes={k=v}`) | read the quoted, escaped form (§38) |
| 39 | A gate read from JSON needs `maxBlock` | a plan file, an HTTP or JMX plan, or `@WithChaos` JSON has a `gate` without `maxBlock` | set `maxBlock` (§39) |
| 40 | The JFR field `description` is `message` | a JFR query, a JMC view or a `RecordedEvent.getString("description")` reads `com.macstab.chaos.jvm.ScenarioLifecycle` | read `message` (§40) |
| 41 | A test's sessions and listeners end with it | a test opens a session or adds a listener through the injected `ChaosControlPlane` and a later test relies on it | open or add it again in that test, or through `ChaosPlatform.current()` (§41) |
| 42 | `spuriousWakeup` selects `NIO_SELECTOR_SELECT` alone | a `spuriousWakeup` scenario's `NioSelector` also lists a channel operation (`NIO_CHANNEL_READ`, `NIO_CHANNEL_WRITE`, `NIO_CHANNEL_CONNECT`, `NIO_CHANNEL_ACCEPT`) | remove the other operations, or put them in a scenario of their own (§42) |
| 43 | The Quarkus and Micronaut modules no longer bring `chaos-agent-testkit` | a test uses `@QuarkusChaosTest`, `@MicronautChaosTest`, `@WithChaos` or `ChaosAssertions` and gets the testkit only through `chaos-agent-quarkus-extension` or `chaos-agent-micronaut-integration` | add `chaos-agent-testkit` to the test dependencies (§43) |
| 44 | Of `chaos-agent-bootstrap`, only `ChaosPlatform` is API | you reference `StartupConfigPoller`, `ChaosAgentBootstrap`, `ChaosDiagnosticsMXBean`, `JfrIntegration` or the JFR event classes | use `ChaosPlatform` and the `configWatchInterval` agent argument (§44) |

---

## 1. Socket scenarios also match socket channels

`SOCKET_CONNECT`, `SOCKET_READ`, `SOCKET_WRITE`, `SOCKET_CLOSE` and `SOCKET_ACCEPT` now cover `SocketChannel` and `ServerSocketChannel` as well as `java.net.Socket`. The JDK `HttpClient`, NIO clients and servers, and Spring's `TestRestTemplate` where it uses the JDK client all connect through channels. A selector without a peer filter therefore reaches more connections than in 1.x — including, in a test, the test's own request to the application.

Before (1.x): every `java.net.Socket` connect is failed.

```java
ChaosSelector.network(Set.of(OperationType.SOCKET_CONNECT))
```

After: name the peer the fault is meant for.

```java
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.util.Set;

public final class DownstreamOnly {
  public static ChaosScenario scenario() {
    return ChaosScenario.builder("downstream-refused")
        .selector(
            new ChaosSelector.NetworkSelector(
                Set.of(OperationType.SOCKET_CONNECT), NamePattern.any(), null, 18080, null, null))
        .effect(ChaosEffect.errno(ChaosEffect.Errno.ECONNREFUSED))
        .build();
  }
}
```

The channel-level operations (`NIO_CHANNEL_*` on a `NioSelector`) still fire as before.

## 2. Legacy packages removed

The duplicate package tree `com.macstab.chaos.*` outside `com.macstab.chaos.jvm.*` is gone. Replace the imports:

```text
com.macstab.chaos.api.ChaosScenario      →  com.macstab.chaos.jvm.api.ChaosScenario
com.macstab.chaos.core.ChaosRuntime      →  com.macstab.chaos.jvm.core.ChaosRuntime
```

## 3. Plans parse only through `ChaosPlanMapper`

`chaos-agent-api` no longer depends on `jackson-annotations`: the JSON shape of plans (the `type` discriminators, the creators of `ActivationPolicy` and `NamePattern`) lives in mix-ins of `chaos-agent-startup-config`. A plain `ObjectMapper` no longer knows the discriminators.

```java
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.startup.ChaosPlanMapper;

public final class ReadPlan {
  public static ChaosPlan read(final String json) {
    // Before: new ObjectMapper().readValue(json, ChaosPlan.class)
    return ChaosPlanMapper.read(json);
  }

  public static String write(final ChaosPlan plan) {
    return ChaosPlanMapper.write(plan);
  }
}
```

## 4. Clock-skew policies are validated

1.x ignored `probability`, `maxApplications`, `rateLimit` and `activateAfterMatches` on a clock skew: a clock read is not an event to sample. 2.0 rejects such a scenario with `ChaosValidationException`. A time window still applies.

```java
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.Set;

public final class ClockSkewForFiveMinutes {
  public static ChaosScenario scenario() {
    return ChaosScenario.builder("clock-ahead")
        .selector(
            ChaosSelector.jvmRuntime(Set.of(OperationType.INSTANT_NOW, OperationType.CLOCK_READ)))
        .effect(ChaosEffect.skewClock(Duration.ofMinutes(5), ChaosEffect.ClockSkewMode.FIXED))
        // Before: .probability(0.5) — silently ignored in 1.x, rejected in 2.0.
        .activationPolicy(ActivationPolicy.builder().activeFor(Duration.ofMinutes(5)).build())
        .build();
  }
}
```

A stress scenario is validated the same way. A stressor runs from its start until it is stopped or its `activeFor` / `activeUntil` ends; it is never evaluated per call, so `maxApplications`, `rateLimit`, `activateAfterMatches`, `probability < 1.0`, `activeFrom` and `replayMatches` never applied to it. 2.0 refuses them with `ChaosValidationException`: remove them, and bound the stressor with `activeFor` or `activeUntil`.

A clock read (`SYSTEM_CLOCK_MILLIS`, `SYSTEM_CLOCK_NANOS`, `INSTANT_NOW`, `CLOCK_READ`, `LOCAL_DATE_TIME_NOW`, `ZONED_DATE_TIME_NOW`, `DATE_NEW`) takes only a clock skew; a delay, reject or suppress on it never applied and is refused, as is a clock skew on any other operation.

## 5. Hooks are woven on demand

1.x wove every hook at start. 2.0 weaves a hook group the first time an activated scenario (or an opened session) needs one of its operations: a JVM without scenarios runs unmodified JDK classes. Two consequences:

- the first activation of a group pays its one-time retransformation cost (milliseconds to a few hundred milliseconds), and a weaving failure fails that activation with failure category `INSTRUMENTATION_FAILURE`;
- `diagnostics().snapshot().runtimeDetails()` lists the woven groups (`wovenHookGroups`) and any hook that did not apply (`unmatchedHooks`, `hookFailures`).

To weave everything at start as in 1.x, set the agent argument `instrumentation=eager`, the system property `-Dmacstab.chaos.instrumentation=eager` or `MACSTAB_CHAOS_INSTRUMENTATION=eager`. Dynamic attach and `installForLocalTests()` now get the same hook set as `-javaagent:`; nothing is premain-only any more.

## 6. The agent JAR is self-contained

`chaos-agent-bootstrap` relocates ByteBuddy and Jackson to `com.macstab.chaos.jvm.shaded.*` and its POM declares no dependencies, so it can no longer clash with the application's copies (Mockito, Hibernate, Spring). If your code used ByteBuddy or Jackson through the agent's transitive dependencies, declare them yourself. The Spring Boot starters no longer pull `chaos-agent-core` and `chaos-agent-startup-config` at run time; those classes come from the agent JAR.

## 7. New abstract methods on API interfaces

Implementations of the API interfaces (rare: test doubles, decorators) must add:

```java
// ChaosControlPlane
boolean removeEventListener(ChaosEventListener listener);
ChaosSession.ScopeBinding bindSession(@Nullable String sessionId); // a decorator delegates

// ChaosActivationHandle
long matchedCount();
long appliedCount();
```

## 8. `ChaosEffect.Errno.code()` is the current OS's number

Relevant only to code written against a 2.0 pre-release: `ChaosEffect.Errno` and the errno effect are new in 2.0, so a 1.x setup has nothing to change here.

`code()` returns the number the JDK prints in `error=N` on the running system: `EAGAIN` is 11 on Linux and 35 on macOS. Compare with Linux numbers through `linuxCode()`.

## 9. `OperationType` gained constants between existing ones

New operations (`CLOCK_READ`, `FILE_IO_OPEN`, `SOCKET_BIND`, `DATAGRAM_SEND`, …) sit next to their families, so the ordinals of later constants moved. Plans and the API use names and are unaffected; code that stored `ordinal()` must store `name()`.

## 10. Bridge handle slots renumbered

The unused slot `ADJUST_SCHEDULE_DELAY` was removed and new slots were appended. The slot `RESOLVE_SHUTDOWN_HOOK` (`removeShutdownHook`, which only returned its argument) was removed too, and `BEFORE_SOCKET_SHUTDOWN` took its index 12; `DECORATE_SHUTDOWN_HOOK` returns nothing. The slots `IDLE` (index 76), `AFTER_PROCESS_START` (77) and `BEFORE_RPC_CALL` (78) were appended. `BootstrapDispatcher.HANDLE_COUNT` is 79. Only code that calls `BootstrapDispatcher` or implements `BridgeDelegate` directly — the agent itself — is affected. `ChaosAgentBootstrap.initialize` lost its `premainMode` parameter; it is package-private.


## 11. A glob's `*` also matches line terminators

In 1.x a GLOB was a regex in which `*` was `.*`, which stops at a line break, while `?` matched any single character, a line break included. In 2.0 a glob is matched by code points: `*` matches any sequence and `?` exactly one character, a line terminator in both cases, and a surrogate pair counts as one character. Names the agent matches (hosts, URLs, classes, threads, topics, paths) rarely contain a line break, so a plan is affected only when it relied on `*` not crossing one.

Before (1.x): `NamePattern.glob("a*b")` does not match `"a\nb"`. Now it does; `NamePattern.regex("a.*b")` keeps the old behaviour.

## 12. `/actuator/chaos` answers with the operation's HTTP status

The Spring actuator endpoint answers like the Micronaut and Quarkus endpoints: a failed activation is 400 `invalid`, 409 `conflict` or 422 `rejected` instead of 200 with `status = "error"`; a stop is 404 `not-found`, 409 `unmanaged`, or 409 `conflict` while the plan is still being activated (it keeps running; stop it again once its activation answered). The stop answer names the id it was asked for `scenarioId`, as in 1.x (earlier 2.0 builds wrote `id`). Clients that read `status = "error"` from a 200 must check the HTTP status (or the `invalid`, `conflict`, `rejected` outcomes). The startup plan (`macstab.chaos.config-file`) is no longer stoppable through the endpoint, which stops only the plans activated through it.

## 13. `reject` is refused where no real call fails

A `RejectEffect` on `EXECUTOR_WORKER_RUN` or `FORK_JOIN_TASK_RUN` fails activation with `ChaosValidationException`. In 1.x it threw on the pool worker before the pool recorded the task's outcome: the worker died and the task's future never completed (`Future.get()` and `CompletableFuture.get()` hung). Reject `EXECUTOR_SUBMIT` to fail the submission, or delay or gate the run.

The same holds for `QUEUE_TAKE` and `QUEUE_POLL`: a `RejectEffect` on them fails activation. The JDK's `take()` and `poll()` fail only by interruption; in 1.x a thread pool's worker taking its next task died on the injected exception and was replaced at once, thousands of times a second, while no task ran. Delay or gate them, suppress `QUEUE_POLL`, or reject `QUEUE_PUT` / `QUEUE_OFFER` on the producer side.

A `RejectEffect` is refused the same way wherever no real call fails: on `THREAD_PARK`, `MONITOR_ENTER`, `SYSTEM_GC_REQUEST`, `SYSTEM_EXIT_REQUEST`, `THREAD_LOCAL_GET`, `THREAD_LOCAL_SET`, `ASYNC_CANCEL`, `ZIP_DEFLATE` and `EXECUTOR_SHUTDOWN`. In 1.x it threw an `IllegalStateException` (a `RuntimeException` on a park) that the real call never throws. The refusal names what to use instead:

| Operation | Use instead |
|-----------|-------------|
| `THREAD_PARK`, `MONITOR_ENTER` | `delay` or `gate`; a `monitorContention` stressor for contended locks |
| `SYSTEM_GC_REQUEST`, `THREAD_LOCAL_GET`, `THREAD_LOCAL_SET` | `suppress` or `delay` |
| `SYSTEM_EXIT_REQUEST` | `suppress` (the exit is refused) or `delay` |
| `ASYNC_CANCEL` | `suppress` or `delay`; `exceptionalCompletion` to fail the future |
| `ZIP_DEFLATE` | `delay`; `reject` on `ZIP_INFLATE` fails with `DataFormatException` |
| `EXECUTOR_SHUTDOWN` | `delay` or `gate` |

`METHOD_ENTER`, `METHOD_EXIT` and `SCHEDULE_TICK` still take a reject.

## 14. Where enabling switches may come from

Quarkus and Micronaut follow the rule the Spring starters already applied: a switch that enables fault injection or a management endpoint (`macstab.chaos.enabled`, `macstab.chaos.management.enabled`, `macstab.chaos.management.allow-anonymous`) is honoured only from a launch-time local source — system properties, environment variables, the application's own config files, test properties ([configuration-reference §10.1](configuration-reference.md#101-configuration-trust) lists them per framework). A switch from a config server (Consul, a Kubernetes config client, Spring Cloud Config) is ignored with a log line naming the source.

- Keep the switch where it comes from and set `macstab.chaos.allow-remote-enable=true` through a system property, an environment variable or the application's config file; or move the switch there.
- Quarkus: the switch is read at run time only. It is no longer needed when the application is built, and the `ChaosControlPlane` bean is always registered: injecting it without the switch and without an installed agent fails on first use with `IllegalStateException` instead of failing the build. Quarkus' `application.yaml` is not a local source; use `application.properties`, a system property or an environment variable.
- Micronaut: `macstab.chaos.enabled` in `application.yml` switches chaos on, as in 1.x. A test that composes `@MicronautTest` and `@ExtendWith(ChaosMicronautExtension.class)` itself, instead of `@MicronautChaosTest`, adds `@Property(name = "macstab.chaos.test.enabled", value = "true")` to get the tracking control plane; a plain `@MicronautTest` gets the application's bean.
- Every switch reads `true|false|1|0|yes|no|on|off`; any other value leaves it off with a warning (Spring read `on` and `yes` as off).
- Spring: `SPRING_APPLICATION_JSON`, config files read from a local path (`./config/application.yml`, `spring.config.location=file:…`) and config trees (`configtree:`) now count as local.

## 15. `reject` and `suppress` on `QUEUE_OFFER` skip queues without a capacity limit

A queue whose `remainingCapacity()` is `Integer.MAX_VALUE` (an unbounded `LinkedBlockingQueue`, a `PriorityBlockingQueue`, a `ScheduledThreadPoolExecutor`'s delay queue) is never full, so a `RejectEffect` or `SuppressEffect` on `QUEUE_OFFER` no longer answers `false` from it: the offer runs and counts no application. In 1.x every scheduled pool silently dropped its tasks under such a scenario. A delay or gate on the offer still applies.

Before (1.x): a reject on `QUEUE_OFFER` made `Executors.newFixedThreadPool(n)` throw `RejectedExecutionException` once its workers were busy. Now it does not, because that pool's queue is unbounded. To fail submissions, reject `EXECUTOR_SUBMIT`; to model a full queue, build the pool on a bounded one (`new ThreadPoolExecutor(n, n, 0, MILLISECONDS, new ArrayBlockingQueue<>(capacity))`).

## 16. A pool task carries a session only once the session has a scenario

A task submitted to a thread pool from a thread bound to a session is wrapped to carry the session only while the session has a registered scenario. In 1.x every task submitted inside an open session was wrapped, which broke priority pools (the wrapper is not `Comparable`) and `remove(task)`.

Before (1.x): a test opened a session without activating anything in it, and the HTTP calls its pool tasks made carried `X-Chaos-Session`, so another service could scope its own scenarios to the session. Now those tasks run outside any session and their calls carry no header. Activate a scenario in the session before submitting, or add the header yourself from `session.id()`.

## 17. Direct-buffer pressure above the direct memory limit is refused

A `DirectBufferPressureEffect` whose `totalBytes` exceeds the JVM's direct memory limit (`-XX:MaxDirectMemorySize`; when it is not set, the maximum heap) is refused at activation with `ChaosUnsupportedFeatureException`, recorded as `UNSUPPORTED_RUNTIME`. A stressor that runs out of memory or cannot start a thread while it starts fails the same way, after freeing what it took.

Before (1.x): the activation allocated until the JVM threw `OutOfMemoryError: Cannot reserve … direct buffer memory`, which escaped unrecorded and left a registered scenario and the buffers already allocated behind. Lower `totalBytes`, or raise `-XX:MaxDirectMemorySize` on the JVM under test; catch `ChaosUnsupportedFeatureException` where you caught `OutOfMemoryError`.

## 18. `suppress` on HTTP and JDBC is refused

A `SuppressEffect` on `HTTP_CLIENT_SEND`, `HTTP_CLIENT_SEND_ASYNC`, `JDBC_CONNECTION_ACQUIRE`, `JDBC_STATEMENT_EXECUTE`, `JDBC_PREPARED_STATEMENT`, `JDBC_TRANSACTION_COMMIT` or `JDBC_TRANSACTION_ROLLBACK` fails activation with `ChaosValidationException` ("use reject"). In 1.x it threw the agent's `ChaosHttpSuppressException` / `ChaosJdbcSuppressException` (the JDK's `HttpURLConnection` threw `ConnectException`), which no real client throws, so the application's error handling met a failure it never sees in production (ADR 0005). Both classes are removed from `chaos-agent-api`.

- In plans: replace `{"type": "suppress"}` on these operations with `{"type": "reject", "message": "…"}`; in code, `ChaosEffect.suppress()` with `ChaosEffect.reject("…")`.
- In tests: catch `java.io.IOException` for HTTP (an `UncheckedIOException` or your client's wrapper where it wraps one) and `java.sql.SQLException` for JDBC (Spring: `DataAccessException`, `CannotGetJdbcConnectionException`) instead of `ChaosHttpSuppressException` / `ChaosJdbcSuppressException`.
- Code that calls `BootstrapDispatcher` or `BridgeDelegate` directly: `beforeHttpSend`, `beforeHttpSendAsync` and the five `beforeJdbc*` return `void`; a reject throws from them.
- The deprecated Quarkus `@ChaosScenario` shorthands `httpClient` + `suppress` and `jdbc` + `suppress` are refused when the annotation is read, with `IllegalArgumentException` ("use delay"): use `effect = "delay:<duration>"`, or a `@WithChaos` JSON scenario with a `reject`.

## 19. `reject` and `errno` raise the real call's exception

A `RejectEffect` fails the call with the exception the real call raises, and every injected failure's stack trace starts at the intercepted method (the agent's frames are removed). The types that changed:

| Operation | 1.x | 2.0 |
|-----------|-----|-----|
| `DNS_ANSWER` | `IllegalStateException` | `UnknownHostException` |
| `NIO_SELECTOR_SELECT` | `ClosedSelectorException` | `IOException` |
| `SOCKET_READ` without `SO_TIMEOUT` (and every socket-channel read) | `SocketTimeoutException` | `SocketException` |
| `SOCKET_WRITE` | `IOException` | `SocketException` |
| `ZIP_INFLATE` | `IllegalStateException` | `DataFormatException` |
| `REFLECTION_INVOKE` | `IllegalStateException` | `InvocationTargetException`, its cause a `RuntimeException` with the message |
| `SCHEDULE_SUBMIT` | `IllegalStateException` | `RejectedExecutionException` |
| `EXECUTOR_AWAIT_TERMINATION`, `QUEUE_PUT` | `IllegalStateException` | `InterruptedException` |
| platform `THREAD_START` | `RejectedExecutionException` | `OutOfMemoryError("unable to create native thread: …")` |
| `SHUTDOWN_HOOK_REGISTER` | `RejectedExecutionException` | `IllegalStateException("Shutdown in progress")` |
| `java.nio` `FILE_IO_OPEN` | `FileNotFoundException` | `FileSystemException` naming the path |
| Kafka, gRPC, Redis, R2DBC, AWS without the library's type visible | `IllegalStateException` | `IOException` |

`METHOD_ENTER`, `METHOD_EXIT` and `SCHEDULE_TICK` keep `IllegalStateException`. The two messages of the JVM (thread start, shutdown hook) replace the reject's message.

The errno changes below are relevant only to code written against a 2.0 pre-release: the errno effect is new in 2.0, so a 1.x setup has none.

An `ErrnoEffect` on a call on an open file — `FILE_IO_READ`, `FILE_IO_WRITE`, `FILE_IO_TRUNCATE`, `FILE_IO_MAP`, `FILE_IO_CLOSE` — fails with `IOException("<reason>")`, as the JDK reports it, instead of a `FileSystemException` naming the path; an open, delete or move still names it. An errno applies only to the calls the kernel reports it for (the table in the configuration reference); `ENOENT` or `EACCES` on a read, `EROFS` on a truncate or `ENOSPC` on a delete is refused at activation with `ChaosValidationException`.

- In tests: catch the 2.0 type (`IOException` covers the file and socket rows); with a 2.0 pre-release, read a file errno's reason from `getMessage()` instead of `FileSystemException.getReason()`.
- In plans written for a 2.0 pre-release: an errno a call cannot report becomes one it can: `EIO` for a failing read or truncate, `ENOENT` / `EACCES` on `FILE_IO_OPEN`.

## 20. The destructive-effect refusal is a validation error; `suppress` is refused where a call cannot be skipped

A `DeadlockEffect` or `ThreadLeakEffect` without `allowDestructiveEffects` fails activation with `ChaosValidationException`, like every other validation error; 1.x threw `ChaosActivationException`. Over HTTP and JMX the outcome stays `invalid`, and the message now starts with `invalid plan:` instead of `plan rejected:`.

- In code: catch `ChaosValidationException` where you caught `ChaosActivationException` for this refusal.
- In scripts that read the management answer: match `invalid plan:`.

A `SuppressEffect` applies only to operations whose call can be skipped or answered empty (the `suppress` row of the configuration reference's compatibility matrix). 1.x accepted it everywhere and it did nothing on the other operations; 2.0 refuses it with `ChaosValidationException`, naming `delay` (and `reject` where the call can fail) instead.

Sessions gained the other way: a session-scoped scenario may now select thread starts and sleeps and executor shutdowns. Only `CLASS_LOAD`, `CLASS_DEFINE`, `RESOURCE_LOAD`, `SHUTDOWN_HOOK_REGISTER` and stressors are refused in a session.

## 21. Stressor parameters are refused instead of reduced

1.x reduced some stressor parameters with at most a warning, so a scenario ran with less load than it asked for. 2.0 refuses them at activation:

| Parameter | 1.x | 2.0 |
|-----------|-----|-----|
| `heapPressure.bytes` above the free heap (`-Xmx` less what is in use) | capped at half the maximum heap, with a warning | `ChaosUnsupportedFeatureException` (`UNSUPPORTED_RUNTIME`) |
| `stringInternPressure.internCount` above 1,000,000, `stringLengthBytes` above 1 MiB | clamped, with a warning | `ChaosValidationException` |
| `stringInternPressure.internCount` above 256^`stringLengthBytes` (257 strings of 1 byte, 65,537 of 2) | duplicates interned (below 32 characters strings collided) | `ChaosValidationException` |
| `referenceQueueFlood.floodInterval`, `safepointStorm.gcInterval`, `keepAlive.heartbeat` below 1 ms | the stressor spun | `ChaosValidationException` |
| `threadLocalLeak` entries (the common pool's parallelism × `entriesPerThread` × (`valueSizeBytes` with its 16-byte header, rounded up to 8, + 160 bytes for the entry's `ThreadLocal`, holder, map entry and planting task)) above the free heap | allocated until an `OutOfMemoryError`, which application threads allocating meanwhile received too; then refused as `UNSUPPORTED_RUNTIME` | `ChaosUnsupportedFeatureException` (`UNSUPPORTED_RUNTIME`) before anything is allocated |
| `threadLocalLeak` entries (parallelism × `entriesPerThread`) above `Integer.MAX_VALUE` | an `IllegalArgumentException` when the stressor started; `ChaosRuntime.validate` accepted it | `ChaosValidationException`, also from `ChaosRuntime.validate` |
| `referenceQueueFlood.referenceCount` × 64 bytes (a `WeakReference` and its anchor) above the free heap | the scenario started, and its thread ran out of memory (`stressor failed`) | `ChaosUnsupportedFeatureException` (`UNSUPPORTED_RUNTIME`) |
| `stringInternPressure.internCount` × (`stringLengthBytes` with its 16-byte header, rounded up to 8, + 40 bytes for the `String` and its list slots) above the free heap | interned until an `OutOfMemoryError`, which application threads allocating meanwhile received too | `ChaosUnsupportedFeatureException` (`UNSUPPORTED_RUNTIME`) before any string is interned |

- Lower `bytes` (`entriesPerThread` or `valueSizeBytes`, `referenceCount`, `internCount` or `stringLengthBytes`), or run the JVM under test with a larger `-Xmx`; catch `ChaosUnsupportedFeatureException` where a plan's heap pressure depends on the machine.
- Lengthen the strings (4 bytes allow the 1,000,000 maximum) or intern fewer.
- Use an interval of 1 ms or more.

`gcPressure` now allocates at its rate instead of rounding up to one object per 50 ms: a low rate of large objects, which allocated far more than asked in 1.x, now allocates what it asks for. Raise `allocationRateBytesPerSecond` if a test relied on the extra load.

## 22. Unused `@Internal` `ChaosRuntime` overloads are removed

`ChaosRuntime` is `@Internal`: the agent's bridge calls it, applications do not. Four short overloads that nothing in the agent called are removed; a test harness that drove the runtime directly calls the full forms:

| Removed | Call instead |
|---------|--------------|
| `beforeThreadStart(thread)` | `beforeThreadStart(thread, false)` |
| `beforeSocketRead(stream)` | `beforeSocketRead(stream, -1, null, 0, 0)` (unknown length, no peer, no `SO_TIMEOUT`) |
| `beforeSocketWrite(stream, len)` | `beforeSocketWrite(stream, len, null, 0)` |
| `beforeFileIo(operation, stream)` | `beforeFileIo(operation, stream, null, -1)` (no path, unknown byte count) |

The documented manual method hooks, `beforeMethodEnter(className, methodName)` and `afterMethodExit(className, methodName, returnType, value)`, stay.

## 23. `@WithChaos` is active around the test method only

The test extensions activate `@WithChaos` scenarios right before the test method runs and stop them right after it (`beforeTestExecution` → `afterTestExecution`), so the test's own `@BeforeEach` and `@AfterEach` methods run without them. In 1.x they were activated in `beforeEach` and stopped in `afterEach`, so set-up and tear-down ran under the test's chaos. This applies to every `@WithChaos` of the testkit `ChaosAgentExtension` and to method-level `@WithChaos` (and Quarkus `@ChaosScenario`) of the Spring Boot 3/4, Micronaut and Quarkus extensions; class-level declarations of those three still span the whole class (`beforeAll` → `afterAll`).

The testkit also binds the thread that runs the test method rather than the thread of `beforeEach`: a body on the separate thread of `@Timeout(threadMode = SEPARATE_THREAD)` and each dynamic test of a `@TestFactory` now run in the session. Under concurrent execution a dynamic test may run after its factory's teardown, outside the session. `ChaosAgentExtension` (testkit, Spring Boot 3/4), `ChaosMicronautExtension` and `ChaosQuarkusExtension` implement `BeforeTestExecutionCallback` / `AfterTestExecutionCallback` (the framework ones no longer implement `BeforeEachCallback` / `AfterEachCallback`), and the testkit one implements `InvocationInterceptor`.

Before (1.x): a `@BeforeEach` method that warmed a cache through a call the test's `@WithChaos` delays was delayed too. Now it is not. Where set-up must run under a scenario, activate it there yourself with the injected `ChaosSession` or `ChaosControlPlane` (and bind the thread for a session-scoped one).

## 24. `ActivationPolicy.fromJson` is no longer public

`ActivationPolicy.fromJson` was the JSON creator of the policy and public in 1.x. It is private in 2.0: the JSON mix-in in `chaos-agent-startup-config` still binds it, and the effect records and `ChaosPlan.Observability` gained private JSON constructors of the same kind, so an omitted field takes its documented default (`withStackTrace` and `retain` `true`, `objectSizeBytes` 1024, `acquisitionDelay` one second, the observability switches `true`, `true`, `false`) instead of `false` or `0`.

Before (1.x): `ActivationPolicy.fromJson(null, null, 0L, …)` built a policy with the JSON defaults. Now build it with `new ActivationPolicy(…)` or `ActivationPolicy.builder()`, or read the JSON with `ChaosPlanMapper`.

## 25. Plan JSON is read strictly and written in ISO-8601

`ChaosPlanMapper` (every plan source: agent arguments, plan files, the poller, the management endpoints, the MBean, `@WithChaos`) refuses what 1.x coerced: a duration or an instant given as a number (`"minDelay": 1` was one second, `1e100` silently zero, `"activeFrom": 1700000000` an epoch second), a fraction for an integer (`"maxApplications": 1.5` was 1), a number in a string (`"5"`), `0`/`1` for a boolean, an enum ordinal (`"scope": 0`), and a field given twice (the last value won). Each fails with `ConfigLoadException`.

Its JSON output changed too: durations and instants are ISO-8601 strings (`"PT0.005S"`, `"capturedAt": "2026-11-08T10:15:30Z"`) instead of decimal seconds, in `ChaosPlanMapper.write`, `toJson` and the diagnostics the management endpoints return; `write` leaves out absent (`null`) values.

Before (1.x): `"minDelay": 0.005` was read as 5 ms and the diagnostics snapshot carried `"capturedAt": 1794132930.000000000`. Now write `"minDelay": "PT0.005S"`, and read `capturedAt` and durations as ISO-8601 strings.

## 26. A name pattern is no longer canonicalised

`NamePattern` refuses with `IllegalArgumentException` (through the plan reader, `ConfigLoadException`) what 1.x silently turned into a pattern that matches everything: a mode other than `ANY` without a value (`{"mode": "GLOB"}`, `NamePattern.glob(null)`), a value without a mode (`{"value": "com.example.*"}`), and `ANY` with a value other than blank or `"*"` (`new NamePattern(ANY, "x")`). A pattern without both mode and value is still `ANY`, and `ANY` with `"*"` (what `NamePattern.any()` and every written plan carry) still works.

Before (1.x): `{"mode": "GLOB"}` without a value matched every name, so a selector meant to narrow its scenario applied it everywhere. Now give the mode its value, or write `{"mode": "ANY"}`.

## 27. The API's nullness is published

Every production package is JSpecify `@NullMarked`: a type is non-null unless it is annotated `@Nullable`, and `chaos-agent-api` brings the annotations to the consumer's class path. Kotlin reads them, so the API's types are no longer platform types (`String!`) but non-null (`String`) or nullable (`String?`). The parameters a constructor or factory normalises from `null` (a selector's patterns, `ChaosScenario`'s description, scope and policy, `ChaosPlan`'s metadata and observability, `NamePattern`'s mode and value) are `@Nullable`; the accessors returning the normalised values are not.

Before (1.x): Kotlin let `selector.destinationPattern()` be treated as nullable and accepted `null` for any parameter. Now pass a value where the type is non-null, and drop the null checks on non-null results.

## 28. Testkit count assertions under `within` hold for the whole duration

`ChaosAssertions`: `within(t).never()` and `within(t).atMost(n)` now hold for the whole of `t` and fail at the first count past the bound; in 1.x they checked once, at once. `hasNoFailuresFor` takes at least one id (`hasNoFailuresFor(String, String...)`), and a repeated id no longer throws. Negative bounds and negative `within` timeouts throw `IllegalArgumentException`. Messages name a scenario from `scenario(id)` by its key, `jvm::<id>` or `session:<id>::<id>`, as the handle assertions already did.

Before (1.x): `assertThat(handle).applied().within(Duration.ofSeconds(5)).never()` returned at once. Now it takes 5 s; drop `within` for an instant check. A call `hasNoFailuresFor()` without ids, which passed, no longer compiles: pass the ids, or use `hasNoFailures()`.

## 29. `chaos-agent-testkit` no longer brings JUnit

`chaos-agent-testkit` compiles against `junit-jupiter-api` but no longer exports it, nor the JUnit BOM: its POM has no JUnit dependency. The same holds for the modules built on it (`chaos-agent-spring-boot3-test-starter`, `chaos-agent-spring-boot4-test-starter`, `chaos-agent-quarkus-extension`, `chaos-agent-micronaut-integration`), so JUnit no longer reaches the production class path of an application that ships the Quarkus or Micronaut module, and the testkit no longer pins your JUnit version.

Before (1.x): the testkit brought `junit-jupiter-api` and imported the JUnit BOM into every consumer. Now declare JUnit where your tests run (`spring-boot-starter-test`, `quarkus-junit5` and `micronaut-test-junit5` already bring it); with Gradle, for example, `testImplementation(platform("org.junit:junit-bom:<version>"))` and `testImplementation("org.junit.jupiter:junit-jupiter")`.

## 30. `ChaosEvent.Type.INACTIVE` and one event per transition

`ChaosEvent.Type` has a new constant, `INACTIVE`: a scenario that ends on its own publishes it once, with the attribute `reason` (`expired` when `activeFor` or `activeUntil` passed, `max applications reached` after the `APPLIED` that spent the budget). Stopping it afterwards still publishes `STOPPED`.

- A `switch` over `ChaosEvent.Type` without a `default` branch no longer compiles (switch expression) or misses the new type: add `case INACTIVE`.
- A listener that counted `STOPPED` per `stop()` or `close()` call: `STOPPED` is now published once per scenario, on the transition into `STOPPED`; a second `stop()`, or `close()` after `stop()`, publishes nothing.
- A listener that waited for `STOPPED` to learn that a scenario expired or used up `maxApplications`: wait for `INACTIVE`.
- `STARTED` is published before the scenario fires; a call made while a listener still handles `STARTED` is neither matched nor applied.

## 31. Apache HttpClient is evaluated at `doExecute`, with one URL rule

Apache HttpClient 4 and 5 are hooked at `doExecute(HttpHost, request, HttpContext)` of the `CloseableHttpClient` implementations (`InternalHttpClient`, `MinimalHttpClient`; in HC4 also `AbstractHttpClient`, so `DefaultHttpClient` and its siblings), which every public `execute` and `executeOpen` overload reaches once per request, instead of at the public `execute` overloads.

- A response handler runs after `doExecute` returns, outside the evaluation: a request the handler sends is now evaluated as a call of its own (1.x and earlier 2.0 builds counted one).
- The URL (`targetName`, matched by `urlPattern`) follows one rule: an absolute request target wins; else the host's URI followed by the target; else, without a host, the request's full URI. `executeOpen(null, request, context)` (Spring's `HttpComponentsClientHttpRequestFactory`) used to report the path only (`/v1/orders`), and HC4 `execute(host, new HttpGet("https://h/v1"))` a doubled URL (`https://hhttps://h/v1`); both now report `https://h/v1`. A `urlPattern` written against the old forms must match the full URL.
- A `CloseableHttpClient` subclass of your own, outside `org.apache.http.impl.client` / `org.apache.hc.client5.http.impl.classic`, is not hooked; its calls through a library client still are.

## 32. Client rejects fail through the library's own failure channel

A reject on a client-library call fails it the way the library itself fails it, through the same channel, instead of throwing from the method the advice is woven into. A throw where the library does not expect one panicked gRPC's channel, hung Netty writes and reached asynchronous callers synchronously.

- **gRPC (`RPC_CALL`)**: evaluated when the call opens its stream (`newStream` of the channel's `ClientCallImpl.ClientStreamProvider`), no longer at `ClientCallImpl.start`; a call started before the channel's name resolved is evaluated when it resolves (it panicked the channel's synchronization context before). A reject opens no stream and fails the call through its listener with `UNAVAILABLE`, through gRPC's own `FailingClientStream`: a blocking stub still throws `StatusRuntimeException`, while a future stub's future and an async stub's `StreamObserver.onError` now receive it instead of a throw from the stub call. A gRPC whose `io.grpc.internal.FailingClientStream` has none of the known constructors is refused at activation when it is visible to the activating thread's context class loader or the system class loader; one that is not visible then is accepted, and if the stream cannot be built the status is thrown from `newStream` and the miss is named in the diagnostics' `hookFailures`.
- **Netty (`NETTY_CHANNEL_WRITE`)**: evaluated at the channel's `doWrite(ChannelOutboundBuffer)`, no longer at `AbstractUnsafe.flush0()`. A reject fails the flushed writes' futures with the `IOException` and closes the channel, as a real write error does; before, the write's future never completed and a pending `OP_WRITE` looped in the selector. An empty flush is no longer evaluated, and a buffer the socket takes in several rounds is evaluated once per round. A datagram channel's write is not evaluated at all: a real error there fails one datagram and keeps the channel open, which a reject cannot reproduce; fail UDP through `DATAGRAM_SEND`, which a NIO datagram write reaches.
- **Kafka (`MESSAGE_SEND`)**: evaluated at the producer's metadata wait inside `send(record, callback)`. A reject no longer throws from `send()`: Kafka's `TimeoutException` reaches the callback, the producer interceptors' `onAcknowledgement` and the returned future (`get()` throws `ExecutionException`), as a real metadata timeout does. `partitionsFor` is not evaluated.
- **Lettuce (`REDIS_COMMAND`)**: a reject completes the command exceptionally with `RedisCommandTimeoutException` instead of throwing from `dispatch`: an asynchronous or reactive command's future fails (the call that issued it returns), the synchronous API still throws. A batch sent through `dispatch(Collection)` (pipelining helpers, command batches) was not evaluated; it now is, once per batch, by its first command's name, and a reject fails every command in it.
- **JDK `HttpClient.sendAsync` (`HTTP_CLIENT_SEND_ASYNC`)**: a reject returns a future failed with the `IOException` instead of throwing it from `sendAsync`.
- **OkHttp (`HTTP_CLIENT_SEND`, `HTTP_CLIENT_SEND_ASYNC`)**: evaluated in the call chain (`RealCall.getResponseWithInterceptorChain`), no longer at `execute()` / `enqueue()`. An `enqueue`d call is evaluated on OkHttp's dispatcher thread and a reject reaches `Callback.onFailure` instead of being thrown from `enqueue`. A request an interceptor sends is now evaluated as a call of its own (it was inside the outer call's scope).
- **Reactor Netty / `WebClient` (`HTTP_CLIENT_SEND_ASYNC`)**: evaluated once per subscription of the request (`HttpClientConnect$MonoHttpConnect.subscribe`), no longer once at `HttpClientConnect.connect()`: a request retried twice is three evaluations, and a `uri(Mono)` is matched on its resolved URL. A reject is an error signal to the subscriber instead of a throw while the request is assembled, so `retry` and `onErrorResume` see it.
- **R2DBC (`R2DBC_STATEMENT_EXECUTE`)**: a reject returns a publisher that signals `R2dbcTransientResourceException` (`Mono.error`, or `Flux.error` where the driver declares a `Flux`) instead of throwing from `execute()`; a driver whose class loader cannot see Reactor still gets the throw. The AWS SDK's asynchronous clients already turned the throw into a failed future and are unchanged.
- Evaluation counts: a gRPC call is still evaluated once; a Lettuce batch counts one evaluation.

## 33. The JDK's own use of a general primitive is not a target

A scenario on a general primitive (file reads, ZIP inflation, direct buffers, GC requests, scheduling, completions, ThreadLocals, reflection, monitors, NIO selects) used to hit the JDK's own internal use of it: a `FILE_IO_READ` or `ZIP_INFLATE` reject broke class loading and `UUID.randomUUID()`, an `ASYNC_COMPLETE*` or `SCHEDULE_*` suppress stopped every `orTimeout`, a `NIO_SELECTOR_SELECT` reject killed every JDK `HttpClient`, a `THREAD_LOCAL_GET` suppress broke `ReentrantReadWriteLock`, a `REFLECTION_INVOKE` reject broke serialization, `EnumSet`, `SSLContext` and every OkHttp client, and a `SYSTEM_GC_REQUEST` suppress made direct buffers run out. The enumerated list in [semantics L3a](semantics.md) is no longer a target.

- Evaluation counts drop by those calls; a test that counted them counts the application's own calls only.
- To reach a listed use on purpose, name it: a path pattern matching `/dev/urandom`, a `ThreadLocalSelector` or `MonitorSelector` pattern naming the JDK class. The other entries (class loading, the timers, the `HttpClient` selector, `jdk.internal.*` futures, the serialization hooks, a `Method.invoke` called by a JDK class) cannot be targeted.
- `CompletableFuture.delayedExecutor` stays evaluated. On JDK 25 its tasks run on the pool's `DelayScheduler` thread, so a `FORK_JOIN_TASK_RUN` delay on them also delays the pool's `orTimeout` timers.
- The agent's own MBeans (`com.macstab.chaos.jvm`) are never a JMX target. A JMX event's subject is the `ObjectName`, `MBeanServer.getAttributes(ObjectName, String[])` is a `JMX_GET_ATTR` too, and an `ASYNC_*` event names the future's class instead of `CompletableFuture`.

## 34. Method hooks never weave JDK classes; match-all method selectors are refused

- A `MethodSelector` no longer weaves classes of the bootstrap or platform class loader: `exact("java.util.HashMap")` with `exact("get")` was woven into every `HashMap.get` of the JVM, the agent's own included. Such a scenario now matches nothing. Application classes, also in `javax.*` packages on the class path, are woven as before.
- The guard against weaving every method of every class refused only `ANY` for both patterns. A pattern that matches everything in another form now counts too: a `GLOB` of `*` characters only (`glob("*")`, `glob(null)`, `**`) and the `REGEX` `.*`, `^.*$` and `.*+`. Such a pair is refused at construction with `IllegalArgumentException` (`ConfigLoadException` when a plan is read). Narrow the class or the method pattern.

## 35. `EXECUTOR_SUBMIT` covers scheduled and virtual-thread executors

- `EXECUTOR_SUBMIT` was raised by `ThreadPoolExecutor.execute` only. It is now also raised once per `execute` / `submit` of a `ScheduledThreadPoolExecutor` (also behind `Executors.newScheduledThreadPool` / `newSingleThreadScheduledExecutor`) and of the virtual-thread-per-task executor (`Executors.newVirtualThreadPerTaskExecutor()`, `Executors.newThreadPerTaskExecutor(factory)`). An `EXECUTOR_SUBMIT` scenario without an `executorClassPattern` therefore counts, delays or rejects these submissions too; narrow it to `java.util.concurrent.ThreadPoolExecutor` to keep the 1.x reach.
- An STPE's `execute` / `submit` is no longer a `SCHEDULE_SUBMIT` (it used to be, through the `schedule` it calls). Its task still fires `SCHEDULE_TICK`, and `schedule*` calls are still `SCHEDULE_SUBMIT`. Select the submission with an `ExecutorSelector` on `EXECUTOR_SUBMIT` instead.
- A `suppress` on such a submission replaces the task with a no-op: the future completes with `null` and is not cancelled (a `ThreadPoolExecutor`'s `submit` future is cancelled, as before).
- `ForkJoinPool` (the common pool, `CompletableFuture.*Async`, JDK 25's `ForkJoinPool.schedule*`) is not hooked for `EXECUTOR_SUBMIT`.

## 36. Injected exceptions are JDK classes, resolved at activation

- `ExceptionInjectionEffect` accepts `java.` and `javax.` class names only. A `jakarta.` or `com.macstab.chaos.jvm.` name is refused when the effect is built (`IllegalArgumentException`; `ConfigLoadException` when a plan is read). Inject the JDK exception the application handles (`java.io.IOException`, `java.sql.SQLException`, `java.lang.IllegalStateException`, …).
- The class is resolved when the scenario is activated, not on the first intercepted call: a class the agent's class loader cannot find, a class that is not a `Throwable`, a non-public or abstract class (`java.lang.VirtualMachineError`) or one without a public `(String)` or no-argument constructor (`java.util.concurrent.CompletionException`) fails the activation with `ChaosValidationException`. 1.x threw an `IllegalArgumentException` or `RuntimeException` from the woven method instead.

## 37. Stressors without load are refused, and a stressor that stops ends its scenario

- A `safepointStorm` with `retransformClassCount = 0` on a JVM started with `-XX:+DisableExplicitGC`, and a `finalizerBacklog` on a JVM started with `--finalization=disabled`, are refused at activation with `ChaosUnsupportedFeatureException` (`UNSUPPORTED_RUNTIME`; over HTTP and JMX the outcome is `rejected`). 1.x accepted them, and they produced no safepoint and no finalizer backlog. Give the safepoint storm a `retransformClassCount` above 0 (retransforms reach a safepoint under every flag), or leave the stressor out of the plan on such a JVM.
- A `virtualThreadCarrierPinning` without `allowDestructiveEffects` on a virtual-thread scheduler with a single carrier (`-Djdk.virtualThreadScheduler.parallelism=1`, a one-CPU container) is refused the same way. 1.0.0 accepted it without a WARNING: its stressor held monitors on platform threads of its own, which pin no carrier of the virtual-thread scheduler. 2.0 pins carriers with virtual threads and leaves one carrier free, so on a single carrier it would pin nothing. Set `allowDestructiveEffects` to let it pin the only carrier, or leave the stressor out of the plan on such a JVM.
- Not refused: every stressor under ZGC, Shenandoah or `-XX:+ExplicitGCInvokesConcurrent` (the pauses are shorter), and `referenceQueueFlood` under `-XX:+DisableExplicitGC` (only the application's own collections enqueue its references).
- A stressor scenario no longer stays `ACTIVE` once its load has stopped: when a `gcPressure` `duration` or a `threadLeak` `lifespan` elapses, or a stressor thread fails, the scenario becomes `INACTIVE` with reason `duration elapsed`, `lifespan elapsed` or `stressor failed: <type>`, and publishes `INACTIVE`. A test that asserted `ACTIVE` afterwards asserts `INACTIVE`.

## 38. The event log line is quoted and escaped

- Each event the agent logs (logger `com.macstab.chaos.jvm`) is one line `type=X scenarioId="…" message="…" attributes={k="v", …}`. 1.x wrote `scenarioId=` and the attribute values unquoted and nothing escaped, so a line break in an id or a message split the line.
- The id, the message and the attribute keys and values are escaped: `\\`, `\"`, `\r`, `\n`, and `\uXXXX` for the other C0 controls, DEL, the C1 controls and U+2028/U+2029. A log parser reads the quoted fields and unescapes them.
- The attributes come in a fixed order per event type (see [observability.md](observability.md#2-events)), the same in every JVM; 1.x printed them in an order that changed between runs.

## 39. A gate read from JSON needs `maxBlock`

`ChaosPlanMapper` refuses a `gate` effect without `maxBlock` with `ConfigLoadException` ("gate needs maxBlock"), whatever the JSON source: the plan file, inline or Base64 startup JSON, the HTTP and JMX management endpoints and `@WithChaos` (which reports it as `IllegalArgumentException`, its cause the `ConfigLoadException`). A gate is released only through its `ChaosActivationHandle`, which the author of a JSON plan never holds, so a gate without a bound held every caller until the scenario was stopped. `ChaosEffect.gate(null)` through the Java API is unchanged.

Before (1.x): `{"type": "gate"}` held the matched calls with nothing to release them. Now give the gate its bound, for example `{"type": "gate", "maxBlock": "PT30S"}`, or activate it through the Java API and call `handle.release()`.

The seed default is unchanged and now stated exactly: an `activationPolicy` that omits `randomSeed` (or sets it to `null`) draws a random seed; a scenario without `activationPolicy` gets `ActivationPolicy.always()`, whose seed is `0`.


## 40. The JFR field `description` is `message`

The `com.macstab.chaos.jvm.ScenarioLifecycle` event's field `description` is renamed `message`: it held the event's message (`ChaosEvent.message()`), never a description of the scenario. A query or view that reads `description` reads `message` instead, for example `jfr print --events com.macstab.chaos.jvm.ScenarioLifecycle` shows `message = "scenario started"`.

`com.macstab.chaos.jvm.EffectApplied` gained `match` and `delayNanos`; the other fields are unchanged.

## 41. A test's sessions and listeners end with it

The `TrackingChaosControlPlane` a test extension injects as `ChaosControlPlane` releases everything the test created through it, not only the scenarios it activated: `stopTracked()` also closes the sessions opened with `openSession` and removes the event listeners added with `addEventListener`. The testkit `ChaosAgentExtension` does so after each test, the Spring Boot 3/4, Micronaut and Quarkus extensions after the test class. A listener the test removed itself is left alone. The control plane bean that an application context (Spring, Micronaut) or a Quarkus application gets keeps the application's listeners and sessions (`new TrackingChaosControlPlane(delegate, false)`); only what it activated is stopped.

Before (1.x): a listener added in one test kept receiving the events of every later test in the JVM, and a session opened through the injected plane stayed open. Now add the listener, or open the session, in each test that needs it, or through `ChaosPlatform.current()` when it must outlive the test.

## 42. `spuriousWakeup` selects `NIO_SELECTOR_SELECT` alone

A `spuriousWakeup` effect is refused with `ChaosValidationException` unless its `NioSelector` lists `NIO_SELECTOR_SELECT` as its only operation. 1.x required `NIO_SELECTOR_SELECT` among the operations but accepted others beside it, and on a channel read, write, connect or accept the effect skipped the call instead of waking a selector.

Before (1.x): `new NioSelector(Set.of(NIO_SELECTOR_SELECT, NIO_CHANNEL_READ), …)` with `spuriousWakeup()` was accepted. Now select `NIO_SELECTOR_SELECT` alone; give a channel operation a scenario of its own with an effect it supports (a delay, `reject`, `errno`).

## 43. The Quarkus and Micronaut modules no longer bring `chaos-agent-testkit`

`chaos-agent-quarkus-extension` and `chaos-agent-micronaut-integration` compile against `chaos-agent-testkit` but no longer export it: their POMs do not list it. An application ships these modules for its `ChaosControlPlane` bean and its management endpoint, and the testkit (with `TrackingChaosControlPlane`, `@WithChaos` and `ChaosAssertions`) is test support, so it no longer reaches the production class path. The test support in the two modules (`@QuarkusChaosTest` and `ChaosQuarkusExtension`; `@MicronautChaosTest`, `ChaosMicronautExtension` and the tracked control-plane bean of a chaos test's context) is built on it and needs it in the test dependencies.

Before (1.x): the testkit came with the module, also into the packaged application. Now declare it where your tests run; with Gradle, for example, `testImplementation("com.macstab.chaos.jvm:chaos-agent-testkit")` next to the module (its version from `chaos-agent-bom`). An application that uses neither the test annotations nor the testkit's classes changes nothing.

## 44. Of `chaos-agent-bootstrap`, only `ChaosPlatform` is API

`ChaosPlatform` is the one supported type of the agent jar's module (`chaos-agent-bootstrap`); `apiCheck` and `japicmp` guard it. `StartupConfigPoller`, which was `public` in 1.x (`createIfEnabled`, `startWithInitialPlan`, `intervalMs`, `close`, and the constants `ENV_WATCH_INTERVAL` and `ARG_WATCH_INTERVAL`), is package-private. `ChaosAgentBootstrap`, `ChaosDiagnosticsMXBean`, `JfrIntegration` and the JFR event classes (`ChaosEffectAppliedEvent`, `ChaosScenarioLifecycleEvent`, `ChaosStressorSnapshotEvent`) stay `public` for the agent's own use but are `@Internal`: they can change in any release.

Before (1.x): code could start a poller with `StartupConfigPoller.createIfEnabled(...)` or read its constants. Now get the control plane through `ChaosPlatform` (`ChaosPlatform.installLocally()` or `ChaosPlatform.current()`), and have the agent reload its plan file with the `configWatchInterval` agent argument (`-Dmacstab.chaos.configWatchInterval`, `MACSTAB_CHAOS_CONFIG_WATCH_INTERVAL`; [configuration reference](configuration-reference.md#supported-agent-args)). The MXBean is still registered and read through JMX by its object name; only compiling against its interface is unsupported. The JFR events keep their names and fields (§40), so JFR recordings and JMC are unaffected.

---

<div align="center">

*Architecture, implementation, and documentation crafted with Love and Passion by*

**[Christian Schnapka](https://macstab.com)**
Embedded Principal+ Engineer
[Macstab GmbH](https://macstab.com) · Hamburg, Germany

*Building systems that operate correctly at the edges — including the ones you deliberately break.*

</div>
