<!--
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Engineered by  Christian Schnapka
                 Embedded Principal+ Engineer
                 Macstab GmbH · Hamburg, Germany
                 https://macstab.com
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-->

# Cross-Layer Mapping — native rules ↔ JVM scenarios

> How a rule of the native chaos libraries (`macstab-chaos-testing-libraries`, `libchaos-<lib>` preloaded into a process) translates into a scenario of this agent, and back. Both layers express a fault as the same triple — selector × effect × activation policy — over different interception points: the native libraries intercept libc calls beneath any runtime, the agent intercepts JVM and library calls above it.
>
> Every JVM name on this page (API methods, enum constants, plan JSON `type` names) and every JSON example is checked against the code by `CrossLayerDocSyncTest`; the same test checks the native library's `docs/DSL.md` when that repository is checked out next to this one.
>
> *Engineered by* **[Christian Schnapka](https://macstab.com)** — Embedded Principal+ Engineer · [Macstab GmbH](https://macstab.com) · Hamburg, Germany

---

## 1. The triple on both layers

| Part     | Native rule (`/tmp/.chaos-<lib>.conf`)                                   | JVM scenario (Java API)                                   | JVM scenario (plan JSON)                     |
|----------|--------------------------------------------------------------------------|-----------------------------------------------------------|----------------------------------------------|
| Selector | path prefix (io), endpoint (net), host name (dns), function (time, process, memory), `*` | `ChaosSelector.fileIo(...)`, `ChaosSelector.network(...)`, `ChaosSelector.dns(...)`, … | `"selector": {"type": "fileIo", ...}`        |
| Effect   | `ERRNO`, `LATENCY`, `TORN`, `CORRUPT`, `TRICKLE`, `RESET`, `OFFSET`, …    | `ChaosEffect.errno(...)`, `ChaosEffect.delay(...)`, …     | `"effect": {"type": "errno", ...}`           |
| Policy   | probability, `@first`, `@from`/`@until`, `@size`, `FAIL_AFTER` counter   | `ActivationPolicy.builder()...build()` plus selector filters | `"activationPolicy": {...}`                 |
| Scope    | the process the library is preloaded into                                | the JVM (`ChaosScenario.ScenarioScope.JVM`) or one session (`ChaosScenario.ScenarioScope.SESSION`) | `"scope": "JVM"` / `"SESSION"` |
| Source   | a text file, re-read when it changes                                     | the API, or a plan file re-read when it changes ([startup-config.md](startup-config.md)) | the plan file                               |
| Status   | `/tmp/.chaos-<lib>.status`, `/tmp/.chaos-<lib>.hits.<n>`               | `/tmp/.chaos-jvm.status`, `/tmp/.chaos-jvm.hits.<pid>` in the same key and line format ([observability.md](observability.md)) | |

The evaluation order of a JVM scenario is in [semantics.md](semantics.md); the native order is in the library's `DSL.md` §7–§8.

---

## 2. Selectors

| Native selector                         | JVM selector                                                                                         | Plan JSON                                                                 |
|-----------------------------------------|------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------|
| `/data` (io path prefix, `/` boundaries) | `new ChaosSelector.FileIoSelector(ops, PathPattern.under("/data"), null, null)`                    | `{"type": "fileIo", "operations": [...], "pathPattern": {"prefix": "/data"}}` |
| `read`/`pread`, `write`/`pwrite`, `fsync`/`fdatasync` (io operations) | `OperationType.FILE_IO_READ`, `OperationType.FILE_IO_WRITE`, `OperationType.FILE_IO_SYNC` | `"operations": ["FILE_IO_SYNC"]`                              |
| `truncate`, `unlink`, `rename_from` (io operations) | `OperationType.FILE_IO_TRUNCATE`, `OperationType.FILE_IO_DELETE`, `OperationType.FILE_IO_MOVE` (by source path; there is no destination match) | `"operations": ["FILE_IO_DELETE"]` |
| `tcp4://10.0.0.5:5432` (net endpoint)   | `remoteCidr` `"10.0.0.5"` (or `"10.0.0.0/24"`, parsed by `CidrBlock.parse`) and `remotePort` `5432` on a `ChaosSelector.NetworkSelector` | `{"type": "network", "operations": [...], "remoteCidr": "10.0.0.5", "remotePort": 5432}` |
| `tcp6://[::1]:6379`                     | `remoteCidr` `"::1"`                                                                                  | `"remoteCidr": "::1"`                                                     |
| `connect`, `recv`, `send`, `close` (net operation) | `OperationType.SOCKET_CONNECT`, `OperationType.SOCKET_READ`, `OperationType.SOCKET_WRITE`, `OperationType.SOCKET_CLOSE` | `"operations": ["SOCKET_READ"]`               |
| `dns://db.internal` (exact)             | `ChaosSelector.dns(ops, NamePattern.exact("db.internal"))`                                            | `"hostnamePattern": {"mode": "EXACT", "value": "db.internal"}`            |
| `dns://*.example.internal` (suffix)     | `NamePattern.glob("*.example.internal")`                                                             | `"hostnamePattern": {"mode": "GLOB", "value": "*.example.internal"}`      |
| `*` (any)                               | `NamePattern.any()`, or leave the filter out                                                         | omit the pattern                                                          |
| `clock_gettime/realtime`                | `ChaosSelector.jvmRuntime(Set.of(OperationType.INSTANT_NOW, OperationType.LOCAL_DATE_TIME_NOW, OperationType.ZONED_DATE_TIME_NOW, OperationType.DATE_NEW))` | `{"type": "jvmRuntime", "operations": ["INSTANT_NOW", ...]}` |
| `pthread_create`                        | `ChaosSelector.thread(Set.of(OperationType.THREAD_START), ChaosSelector.ThreadKind.PLATFORM)`        | `{"type": "thread", "operations": ["THREAD_START"], "kind": "PLATFORM"}`  |

The JVM matches on what the Java call carries: a socket call matches by peer address and port (`remoteCidr`, `remotePort`) and also by the host name the application used (`ChaosSelector.network(ops, NamePattern.exact("db.internal"))`), which the native layer never sees. Path filters are lexical on both layers; neither resolves symbolic links.

---

## 3. Effects

| Native effect                                   | JVM effect                                                         | Plan JSON                                                   | Applies to (JVM operations)                     |
|-------------------------------------------------|--------------------------------------------------------------------|-------------------------------------------------------------|-------------------------------------------------|
| `LATENCY:200`                                   | `ChaosEffect.delay(Duration.ofMillis(200))`                        | `{"type": "delay", "minDelay": "PT0.2S", "maxDelay": "PT0.2S"}` | every interceptor operation                 |
| `ERRNO` with `EIO`, `ENOSPC`, `EDQUOT`, `EROFS`, `EACCES`, `ENOENT`, `EMFILE` | `ChaosEffect.errno(ChaosEffect.Errno.EIO)` and the other `ChaosEffect.Errno` file constants: a `FileSystemException` subclass with the path and the system's reason text | `{"type": "errno", "errno": "EIO"}` | `FILE_IO_*`               |
| `ERRNO` with `ECONNREFUSED`, `ETIMEDOUT`, `ECONNRESET`, `EHOSTUNREACH`, `ENETUNREACH`, `EPIPE`, `EADDRINUSE` | `ChaosEffect.errno(ChaosEffect.Errno.ECONNREFUSED)` and the other network constants: the matching `java.net` exception | `{"type": "errno", "errno": "ECONNREFUSED"}` | `SOCKET_*` |
| any other `ERRNO`                               | `ChaosEffect.reject("...")`: the operation's natural exception (`UnknownHostException` for DNS, `SQLException` for JDBC, …) | `{"type": "reject", "message": "..."}` | every interceptor operation |
| `GAI` (`EAI_NONAME`, `EAI_AGAIN`)               | `ChaosEffect.reject("...")` on `OperationType.DNS_RESOLVE`: `UnknownHostException` | `{"type": "reject", "message": "..."}`          | `DNS_RESOLVE`                                   |
| `TORN`                                          | `ChaosEffect.tornWrite()`                                          | `{"type": "tornWrite"}`                                     | `FILE_IO_WRITE` through `FileChannel`; also `NIO_CHANNEL_WRITE` (short count) and `SOCKET_WRITE` (prefix sent, then a reset), which the native layer does not tear |
| `CORRUPT`                                       | `ChaosEffect.corruptRead()`                                        | `{"type": "corruptRead"}`                                   | `SOCKET_READ`, `FILE_IO_READ`                   |
| `TRICKLE:16`                                    | `ChaosEffect.trickle(16)`                                          | `{"type": "trickle", "maxBytes": 16}`                       | `SOCKET_READ`                                   |
| `TIMEOUT` (net `poll`)                          | `ChaosEffect.timeout()`: waits out the socket's own `SO_TIMEOUT` or connect timeout, then `SocketTimeoutException`; never fires on an infinite timeout | `{"type": "timeout"}` | `SOCKET_READ`, `SOCKET_CONNECT` |
| `RESET`                                         | `ChaosEffect.connectionReset()`                                    | `{"type": "connectionReset"}`                               | `SOCKET_CLOSE`                                  |
| `OFFSET:500` (realtime)                         | `ChaosEffect.skewClock(Duration.ofMillis(500), ChaosEffect.ClockSkewMode.FIXED)` | `{"type": "clockSkew", "skewAmount": "PT0.5S", "mode": "FIXED"}` | `INSTANT_NOW`, `LOCAL_DATE_TIME_NOW`, `ZONED_DATE_TIME_NOW`, `DATE_NEW` |
| `REGRESS:1000`                                  | `ChaosEffect.skewClock(Duration.ofSeconds(1), ChaosEffect.ClockSkewMode.REGRESS)` | `{"type": "clockSkew", "skewAmount": "PT1S", "mode": "REGRESS"}` | as `OFFSET`                        |
| `OVERRIDE:127.0.0.1,[::1]`                      | `ChaosEffect.dnsOverride("127.0.0.1", "::1")`                      | `{"type": "dnsAnswer", "override": ["127.0.0.1", "::1"]}`   | `DNS_ANSWER`                                    |
| `FILTER_FAMILY:inet6`                           | `ChaosEffect.dnsFamily(ChaosEffect.AddressFamily.IPV6)`            | `{"type": "dnsAnswer", "family": "IPV6"}`                   | `DNS_ANSWER`                                    |
| `SHUFFLE`                                       | `ChaosEffect.dnsShuffle()`                                         | `{"type": "dnsAnswer", "shuffle": true}`                    | `DNS_ANSWER`                                    |
| `LIMIT:1`                                       | `ChaosEffect.dnsLimit(1)`                                          | `{"type": "dnsAnswer", "limit": 1}`                         | `DNS_ANSWER`                                    |
| `REWRITE:other.internal`                        | `ChaosEffect.dnsRewrite("other.internal")`                         | `{"type": "dnsRewrite", "host": "other.internal"}`          | `DNS_RESOLVE`                                   |

A native rule's probability field is the policy's probability on the JVM (§4); `SHUFFLE:0.3` is `ChaosEffect.dnsShuffle()` with `probability` `0.3`.

---

## 4. Activation policy and qualifiers

| Native                         | JVM (Java API)                                                       | JVM (plan JSON)                                                   |
|--------------------------------|----------------------------------------------------------------------|-------------------------------------------------------------------|
| probability `1.0`              | `ActivationPolicy.always()`                                          | omit `activationPolicy`                                           |
| probability `0.05`             | `ActivationPolicy.builder().probability(0.05).build()`               | `"activationPolicy": {"probability": 0.05}`                       |
| `@first=3`                     | `ActivationPolicy.count(3)`                                          | `"activationPolicy": {"maxApplications": 3}`                      |
| `@from=T1 @until=T2` (Unix ms) | `ActivationPolicy.window(Instant.ofEpochMilli(T1), Instant.ofEpochMilli(T2))` | `"activationPolicy": {"activeFrom": "2026-11-01T08:00:00Z", "activeUntil": "2026-11-01T09:30:00Z"}` |
| relative window                | `ActivationPolicy.builder().activeFor(Duration.ofMinutes(5)).build()`, measured from activation | `"activationPolicy": {"activeFor": "PT5M"}`            |
| `@size>65536`                  | `minBytes` `65537` on the selector (`minBytes` and `maxBytes` are inclusive) | `"minBytes": 65537`                                       |
| `@size<512`                    | `maxBytes` `511`                                                     | `"maxBytes": 511`                                                 |
| `FAIL_AFTER:EAGAIN,128`        | `ActivationPolicy.builder().activateAfterMatches(128).build()` with the failing effect: the first 128 matches pass | `"activationPolicy": {"activateAfterMatches": 128}` |

Shared rules: the window is read from the real wall clock on both layers, so an injected clock skew never moves it; a call without a byte count never matches a size filter; `@first` and `maxApplications` spend the budget only on calls that actually fire.

Different rules: the native `FAIL_AFTER` counter is per operation and resets on every rule-file publication; `activateAfterMatches` counts the scenario's matches since activation. A native process has one budget per rule; the JVM has one per scenario, and a session-scoped scenario counts only its session's calls.

---

## 5. What does not translate

### 5.1 Native only

| Native                                        | Why the JVM has no equivalent                                                                                     |
|-----------------------------------------------|-------------------------------------------------------------------------------------------------------------------|
| `OFFSET`/`REGRESS` seen by `System.currentTimeMillis()` and `System.nanoTime()` | both are JIT intrinsics; the agent skews `java.time` and `java.util.Date` reads only. Use the native time rule to move the clock beneath the JVM. |
| `clock_gettime/monotonic`                     | `System.nanoTime()` is an intrinsic (as above)                                                                    |
| `DUP`, `SERVICE`                              | no JVM effect duplicates received data or rewrites a resolved service port                                        |
| `unix://` endpoints                           | Unix-domain sockets are not intercepted                                                                           |
| `mmap`, `munmap`, `mprotect`, `madvise`       | the JVM does not expose memory mapping calls; the pressure stressors (`ChaosEffect.heapPressure(...)`, `ChaosEffect.directBufferPressure(...)`) are the JVM-level analogue |
| `fork`, `execve`, `posix_spawn`, `waitpid`    | process faults are not intercepted (planned); a JVM child process reaches the native process rules             |
| `pthread_create:ERRNO:EAGAIN`                 | `ChaosEffect.reject("...")` on `OperationType.THREAD_START` throws `RejectedExecutionException`, not the `OutOfMemoryError: unable to create native thread` the JDK throws; use the native rule for the exact error |
| SECCOMP supervision (`chaos-init`)            | the agent intercepts in-process; static binaries and Go programs are reached by the native supervisor only         |

### 5.2 JVM only

| JVM                                                         | Why the native layer has no equivalent                                       |
|-------------------------------------------------------------|-------------------------------------------------------------------------------|
| sessions (`ChaosScenario.ScenarioScope.SESSION`, `ChaosControlPlane.openSession`) | a native rule applies to the whole process; a session scopes chaos to one test's threads and the work they hand off |
| `ChaosSelector.method(...)`                                 | any Java method by class and method pattern; native rules name libc functions |
| JDBC, HTTP, Kafka, gRPC, Redis, AWS SDK, Netty operations  | protocol-level calls; the native layer sees the bytes on the socket           |
| `ChaosEffect.gate(...)`, `ChaosEffect.suppress()`, `ChaosEffect.corruptReturnValue(...)`, `ChaosEffect.exceptionalCompletion(...)` | they act on Java control flow and return values |
| stressors (`ChaosEffect.deadlock(...)`, `ChaosEffect.safepointStorm(...)`, `ChaosEffect.virtualThreadCarrierPinning(...)`, …) | JVM-internal pressure                        |
| monitors, class loading, executors, virtual threads         | JVM-internal events                                                           |

---

## 6. Worked translations

### 6.1 fsync fails with EIO on 5 % of calls under a directory

Native (`/tmp/.chaos-io.conf`):

```text
/var/lib/app:fsync:EIO:0.05
```

JVM:

```json
{"id": "fsync-eio-5pct", "scope": "JVM",
 "selector": {"type": "fileIo", "operations": ["FILE_IO_SYNC"],
              "pathPattern": {"prefix": "/var/lib/app"}},
 "effect": {"type": "errno", "errno": "EIO"},
 "activationPolicy": {"probability": 0.05}}
```

`FileChannel.force` and `FileDescriptor.sync` then throw a `FileSystemException` naming the file, with the reason `Input/output error`.

### 6.2 Large writes to a broker are slow during an incident window

Native (`/tmp/.chaos-net.conf`):

```text
tcp4://10.0.0.7:9092:send:LATENCY:200 @size>65536 @from=1793520000000 @until=1793525400000
```

JVM:

```json
{"id": "broker-large-writes-slow", "scope": "JVM",
 "selector": {"type": "network", "operations": ["SOCKET_WRITE"],
              "remoteCidr": "10.0.0.7", "remotePort": 9092, "minBytes": 65537},
 "effect": {"type": "delay", "minDelay": "PT0.2S", "maxDelay": "PT0.2S"},
 "activationPolicy": {"activeFrom": "2026-11-01T08:00:00Z", "activeUntil": "2026-11-01T09:30:00Z"}}
```

### 6.3 A replica's name resolves to the wrong address family, then the first three connects fail

Native (`/tmp/.chaos-dns.conf` and `/tmp/.chaos-net.conf`):

```text
dns://replica.internal:FILTER_FAMILY:inet6
tcp6://*:5432:connect:ECONNREFUSED:1.0 @first=3
```

JVM (a plan with two scenarios):

```json
{"metadata": {"name": "replica-failover"},
 "scenarios": [
   {"id": "replica-ipv6-only", "scope": "JVM",
    "selector": {"type": "dns", "operations": ["DNS_ANSWER"],
                 "hostnamePattern": {"mode": "EXACT", "value": "replica.internal"}},
    "effect": {"type": "dnsAnswer", "family": "IPV6"}},
   {"id": "replica-refused-thrice", "scope": "JVM",
    "selector": {"type": "network", "operations": ["SOCKET_CONNECT"],
                 "remoteCidr": "::/0", "remotePort": 5432},
    "effect": {"type": "errno", "errno": "ECONNREFUSED"},
    "activationPolicy": {"maxApplications": 3}}]}
```

### 6.4 A clock that jumps back

Native (`/tmp/.chaos-time.conf`), seen by every clock read in the process, including the JVM's intrinsics:

```text
clock_gettime/realtime:REGRESS:1000
```

JVM, seen by `java.time` and `java.util.Date` reads only (§5.1):

```json
{"id": "clock-regress", "scope": "JVM",
 "selector": {"type": "jvmRuntime",
              "operations": ["INSTANT_NOW", "LOCAL_DATE_TIME_NOW", "ZONED_DATE_TIME_NOW", "DATE_NEW"]},
 "effect": {"type": "clockSkew", "skewAmount": "PT1S", "mode": "REGRESS"}}
```

---

<div align="center">

*Architecture, implementation, and documentation crafted with Love and Passion by*

**[Christian Schnapka](https://macstab.com)**
Embedded Principal+ Engineer
[Macstab GmbH](https://macstab.com) · Hamburg, Germany

*Building systems that operate correctly at the edges — including the ones you deliberately break.*

</div>
