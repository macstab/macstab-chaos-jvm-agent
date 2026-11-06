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
| 4 | Clock-skew policies are validated | a clock skew sets probability, a budget or a rate limit | remove them, use a time window (§4) |
| 5 | Hooks are woven on demand | you rely on hooks being in place before activation, or on start-up cost | nothing, or `instrumentation=eager` (§5) |
| 6 | The agent JAR is self-contained and shades its libraries | your code used the agent's ByteBuddy or Jackson | depend on them yourself (§6) |
| 7 | New abstract methods on API interfaces | you implement `ChaosControlPlane` or `ChaosActivationHandle` | implement them (§7) |
| 8 | `ChaosEffect.Errno.code()` is the current OS's number | you compare it with Linux numbers on macOS | use `linuxCode()` (§8) |
| 9 | `OperationType` gained constants between existing ones | you persist ordinals | persist names (§9) |
| 10 | Bridge handle slots renumbered | you call `BootstrapDispatcher` directly | none for applications (§10) |
| 11 | A glob's `*` also matches line terminators | a GLOB must not match across a line break | use a REGEX (§11) |

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

---

<div align="center">

*Architecture, implementation, and documentation crafted with Love and Passion by*

**[Christian Schnapka](https://macstab.com)**
Embedded Principal+ Engineer
[Macstab GmbH](https://macstab.com) · Hamburg, Germany

*Building systems that operate correctly at the edges — including the ones you deliberately break.*

</div>
