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
| 8 | `ChaosEffect.Errno.code()` is the current OS's number | you compare it with Linux numbers on macOS | use `linuxCode()` (§8) |
| 9 | `OperationType` gained constants between existing ones | you persist ordinals | persist names (§9) |
| 10 | Bridge handle slots renumbered | you call `BootstrapDispatcher` directly | none for applications (§10) |
| 11 | A glob's `*` also matches line terminators | a GLOB must not match across a line break | use a REGEX (§11) |
| 13 | `reject` is refused where no real call fails | a plan rejects `THREAD_PARK`, `MONITOR_ENTER`, `SYSTEM_GC_REQUEST`, `SYSTEM_EXIT_REQUEST`, `THREAD_LOCAL_*`, `ASYNC_CANCEL`, `ZIP_DEFLATE`, `EXECUTOR_SHUTDOWN`, a worker or fork-join run, or a queue take or poll | use the effect the refusal names (§13) |
| 14 | Enabling switches are honoured only from local sources on Quarkus and Micronaut | a config server sets `macstab.chaos.enabled` or a management switch | set `macstab.chaos.allow-remote-enable=true` locally (§14) |
| 15 | `reject` / `suppress` on `QUEUE_OFFER` skip queues without a capacity limit | you saturate a pool on an unbounded queue through `QUEUE_OFFER` | reject `EXECUTOR_SUBMIT`, or give the queue a capacity (§15) |
| 16 | A pool task carries a session only once the session has a scenario | another service relies on `X-Chaos-Session` from pool tasks of a session without local scenarios | activate a scenario in the session first, or set the header from `session.id()` (§16) |
| 17 | Direct-buffer pressure above the direct memory limit is refused | a `directBufferPressure` asks for more than `-XX:MaxDirectMemorySize` (by default the maximum heap) | lower `totalBytes` or raise the limit (§17) |
| 18 | `suppress` on HTTP and JDBC is refused; `ChaosHttpSuppressException` / `ChaosJdbcSuppressException` are removed | a plan suppresses `HTTP_CLIENT_*` or `JDBC_*`, or code catches those exceptions | use `reject` and catch `IOException` / `SQLException` (§18) |
| 19 | `reject` and `errno` raise the real call's exception; errnos apply per operation | a test catches the 1.x type of a reject or a file errno, reads its top stack frame, or applies a file errno to a call the kernel never reports it for | catch the type in §19; pick an errno the call can report (§19) |
| 20 | The destructive-effect refusal is a `ChaosValidationException`; `suppress` is refused where a call cannot be skipped | code catches `ChaosActivationException` for a deadlock or thread leak without `allowDestructiveEffects`, or matches `plan rejected:` in the HTTP/JMX answer; a plan suppresses an operation outside the suppress list | catch `ChaosValidationException`, match `invalid plan:`; delay or reject instead (§20) |
| 21 | Stressor parameters are refused instead of reduced | a `heapPressure` asks for more than the free heap; a `stringInternPressure` exceeds its caps or its length's distinct strings; a `floodInterval`, `gcInterval` or `heartbeat` is below 1 ms | lower the value or raise `-Xmx`, lengthen the strings, use 1 ms or more (§21) |
| 22 | Unused `@Internal` `ChaosRuntime` overloads removed | code calls `beforeThreadStart(Thread)`, `beforeSocketRead(Object)`, `beforeSocketWrite(Object, int)` or `beforeFileIo(String, Object)` on `ChaosRuntime` | call the full forms (§22) |
| 23 | `@WithChaos` is active around the test method only | a `@BeforeEach` / `@AfterEach` method relies on a method-level (testkit: any) `@WithChaos` scenario | activate it in the set-up method instead (§23) |

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

`code()` returns the number the JDK prints in `error=N` on the running system: `EAGAIN` is 11 on Linux and 35 on macOS. Compare with Linux numbers through `linuxCode()`.

## 9. `OperationType` gained constants between existing ones

New operations (`CLOCK_READ`, `FILE_IO_OPEN`, `SOCKET_BIND`, `DATAGRAM_SEND`, …) sit next to their families, so the ordinals of later constants moved. Plans and the API use names and are unaffected; code that stored `ordinal()` must store `name()`.

## 10. Bridge handle slots renumbered

The unused slot `ADJUST_SCHEDULE_DELAY` was removed and new slots were appended; `BootstrapDispatcher.HANDLE_COUNT` is 77. Only code that calls `BootstrapDispatcher` or implements `BridgeDelegate` directly — the agent itself — is affected. `ChaosAgentBootstrap.initialize` lost its `premainMode` parameter; it is package-private.


## 11. A glob's `*` also matches line terminators

In 1.x a GLOB was a regex in which `*` was `.*`, which stops at a line break, while `?` matched any single character, a line break included. In 2.0 a glob is matched by code points: `*` matches any sequence and `?` exactly one character, a line terminator in both cases, and a surrogate pair counts as one character. Names the agent matches (hosts, URLs, classes, threads, topics, paths) rarely contain a line break, so a plan is affected only when it relied on `*` not crossing one.

Before (1.x): `NamePattern.glob("a*b")` does not match `"a\nb"`. Now it does; `NamePattern.regex("a.*b")` keeps the old behaviour.

## 12. `/actuator/chaos` answers with the operation's HTTP status

The Spring actuator endpoint answers like the Micronaut and Quarkus endpoints: a failed activation is 400 `invalid`, 409 `conflict` or 422 `rejected` instead of 200 with `status = "error"`; a stop is 404 `not-found` or 409 `unmanaged`. Clients that read `status = "error"` from a 200 must check the HTTP status (or the `invalid`, `conflict`, `rejected` outcomes). The startup plan (`macstab.chaos.config-file`) is no longer stoppable through the endpoint, which stops only the plans activated through it.

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

An `ErrnoEffect` on a call on an open file — `FILE_IO_READ`, `FILE_IO_WRITE`, `FILE_IO_TRUNCATE`, `FILE_IO_MAP`, `FILE_IO_CLOSE` — fails with `IOException("<reason>")`, as the JDK reports it, instead of a `FileSystemException` naming the path; an open, delete or move still names it. An errno applies only to the calls the kernel reports it for (the table in the configuration reference); `ENOENT` or `EACCES` on a read, `EROFS` on a truncate or `ENOSPC` on a delete is refused at activation with `ChaosValidationException`.

- In tests: catch the 2.0 type (`IOException` covers the file and socket rows), and read the reason from `getMessage()` instead of `FileSystemException.getReason()`.
- In plans: an errno a call cannot report becomes one it can: `EIO` for a failing read or truncate, `ENOENT` / `EACCES` on `FILE_IO_OPEN`.

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

- Lower `bytes`, or run the JVM under test with a larger `-Xmx`; catch `ChaosUnsupportedFeatureException` where a plan's heap pressure depends on the machine.
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

---

<div align="center">

*Architecture, implementation, and documentation crafted with Love and Passion by*

**[Christian Schnapka](https://macstab.com)**
Embedded Principal+ Engineer
[Macstab GmbH](https://macstab.com) · Hamburg, Germany

*Building systems that operate correctly at the edges — including the ones you deliberately break.*

</div>
