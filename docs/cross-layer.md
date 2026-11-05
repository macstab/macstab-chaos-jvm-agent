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
| Status   | `/tmp/.chaos-<lib>.status` (`state`, `rules`, `line`, `reason`, `text`, `pid`), `/tmp/.chaos-<lib>.hits.<n>` | `/tmp/.chaos-jvm.status`, `/tmp/.chaos-jvm.hits.<pid>`: the same `key=value` line format, with `scenarios` for `rules`, `column` beside `line`, `source` (the plan) and no `text` ([observability.md](observability.md)) | |

The evaluation order of a JVM scenario is in [semantics.md](semantics.md); the native order is in the library's `DSL.md` §7–§8.

---

## 2. Selectors

| Native selector                         | JVM selector                                                                                         | Plan JSON                                                                 |
|-----------------------------------------|------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------|
| `/data` (io path prefix, `/` boundaries) | `new ChaosSelector.FileIoSelector(ops, PathPattern.under("/data"), null, null)`                    | `{"type": "fileIo", "operations": [...], "pathPattern": {"prefix": "/data"}}` |
| `read`/`pread`, `write`/`pwrite`, `fsync`/`fdatasync` (io operations) | `OperationType.FILE_IO_READ`, `OperationType.FILE_IO_WRITE`, `OperationType.FILE_IO_SYNC` | `"operations": ["FILE_IO_SYNC"]`                              |
| `mmap` of a file, `msync`               | `OperationType.FILE_IO_MAP` (`FileChannel.map`; `ENOMEM` is `IOException("Map failed")`), `OperationType.FILE_IO_SYNC` (`MappedByteBuffer.force`) | `"operations": ["FILE_IO_MAP"]` |
| `open`/`openat` (io operation) | `OperationType.FILE_IO_OPEN`: the `java.io` stream and `RandomAccessFile` constructors, `Files.newInputStream`/`newOutputStream`/`newByteChannel`, `FileChannel.open` | `"operations": ["FILE_IO_OPEN"]` |
| `truncate`, `unlink`, `rename_from`, `rename_to`, `close` (io operations) | `OperationType.FILE_IO_TRUNCATE`, `OperationType.FILE_IO_DELETE`, `OperationType.FILE_IO_MOVE` (by source), `OperationType.FILE_IO_MOVE_TARGET` (by destination), `OperationType.FILE_IO_CLOSE` (after the close, which releases the descriptor) | `"operations": ["FILE_IO_DELETE"]` |
| `tcp4://10.0.0.5:5432` (net endpoint)   | `remoteCidr` `"10.0.0.5"` (or `"10.0.0.0/24"`, parsed by `CidrBlock.parse`) and `remotePort` `5432` on a `ChaosSelector.NetworkSelector` | `{"type": "network", "operations": [...], "remoteCidr": "10.0.0.5", "remotePort": 5432}` |
| `tcp6://[::1]:6379`                     | `remoteCidr` `"::1"`                                                                                  | `"remoteCidr": "::1"`                                                     |
| `connect`, `recv`, `send`, `close` (net operation) | `OperationType.SOCKET_CONNECT`, `OperationType.SOCKET_READ`, `OperationType.SOCKET_WRITE`, `OperationType.SOCKET_CLOSE` | `"operations": ["SOCKET_READ"]`               |
| `shutdown`, `poll` (net operation)      | `OperationType.SOCKET_SHUTDOWN`; `OperationType.NIO_SELECTOR_SELECT` on a `ChaosSelector.NioSelector`, where `ChaosEffect.timeout()` waits out `select(timeout)` and reports no ready channel | `"operations": ["SOCKET_SHUTDOWN"]` |
| `waitpid` (process)                     | `ChaosSelector.ProcessSelector` with `OperationType.PROCESS_WAIT`: `Process.waitFor`; `ChaosEffect.timeout()` on a timed wait reports a child that has not exited | `{"type": "process", "operations": ["PROCESS_WAIT"]}` |
| `accept`, `bind` (net operation)        | `OperationType.SOCKET_ACCEPT`, `OperationType.SOCKET_BIND` (`remoteCidr` and `remotePort` match the local address being bound) | `"operations": ["SOCKET_BIND"]` |
| `dns://db.internal` (exact)             | `ChaosSelector.dns(ops, NamePattern.exact("db.internal"))`                                            | `"hostnamePattern": {"mode": "EXACT", "value": "db.internal"}`            |
| `getnameinfo` (reverse lookup)          | `ChaosSelector.dns(Set.of(OperationType.DNS_REVERSE), NamePattern.exact("10.0.0.5"))`: `InetAddress.getHostName` / `getCanonicalHostName`; a rejection returns the IP literal, as the JDK reports a failed lookup | `{"type": "dns", "operations": ["DNS_REVERSE"]}` |
| `udp4://10.0.0.5:53` (`sendto`, `recvfrom`) | `OperationType.DATAGRAM_SEND`, `OperationType.DATAGRAM_RECEIVE` on a `ChaosSelector.NetworkSelector` (`DatagramSocket`, `DatagramChannel`); `ChaosEffect.suppress()` drops a datagram | `"operations": ["DATAGRAM_SEND"]` |
| `dns://*.example.internal` (suffix)     | `NamePattern.glob("*.example.internal")`                                                             | `"hostnamePattern": {"mode": "GLOB", "value": "*.example.internal"}`      |
| `*` (any)                               | `NamePattern.any()`, or leave the filter out                                                         | omit the pattern                                                          |
| `clock_gettime/realtime`                | `ChaosSelector.jvmRuntime(Set.of(OperationType.INSTANT_NOW, OperationType.CLOCK_READ, OperationType.LOCAL_DATE_TIME_NOW, OperationType.ZONED_DATE_TIME_NOW, OperationType.DATE_NEW))`: `CLOCK_READ` covers `Clock.systemUTC()` and every other `java.time` `now()` | `{"type": "jvmRuntime", "operations": ["INSTANT_NOW", ...]}` |
| `pthread_create`                        | `ChaosSelector.thread(Set.of(OperationType.THREAD_START), ChaosSelector.ThreadKind.PLATFORM)`; `ChaosEffect.errno(ChaosEffect.Errno.EAGAIN)` there is the JDK's `OutOfMemoryError: unable to create native thread` | `{"type": "thread", "operations": ["THREAD_START"], "kind": "PLATFORM"}`  |
| `posix_spawn`, `fork`, `execve` (process) | `ChaosSelector.process(NamePattern.exact("git"))`: `ProcessBuilder.start` and `Runtime.exec` (`OperationType.PROCESS_START`) | `{"type": "process", "operations": ["PROCESS_START"], "commandPattern": {"mode": "EXACT", "value": "git"}}` |

A `unix:///run/app.sock` endpoint is `ChaosSelector.network(ops, NamePattern.exact("/run/app.sock"))`: the peer of a Unix-domain `SocketChannel` is its path. Socket events cover `java.net.Socket` and `SocketChannel` alike (single, scattering and gathering reads and writes), so the filters and data effects below reach NIO clients too.

The JVM matches on what the Java call carries: a socket call matches by peer address and port (`remoteCidr`, `remotePort`) and also by the host name the application used (`ChaosSelector.network(ops, NamePattern.exact("db.internal"))`), which the native layer never sees. Path filters are lexical on both layers; neither resolves symbolic links.

---

## 3. Effects

| Native effect                                   | JVM effect                                                         | Plan JSON                                                   | Applies to (JVM operations)                     |
|-------------------------------------------------|--------------------------------------------------------------------|-------------------------------------------------------------|-------------------------------------------------|
| `LATENCY:200`                                   | `ChaosEffect.delay(Duration.ofMillis(200))`                        | `{"type": "delay", "minDelay": "PT0.2S", "maxDelay": "PT0.2S"}` | every interceptor operation                 |
| `ERRNO` with `EIO`, `ENOSPC`, `EDQUOT`, `EROFS`, `EACCES`, `ENOENT`, `EMFILE`, `ENFILE` | `ChaosEffect.errno(ChaosEffect.Errno.EIO)` and the other `ChaosEffect.Errno` file constants: a `FileSystemException` subclass with the path and the system's reason text; a `java.io` open fails with `FileNotFoundException("<path> (<reason>)")`. fsync failures are `SyncFailedException` | `{"type": "errno", "errno": "EIO"}` | `FILE_IO_*`               |
| `ERRNO` with `ECONNREFUSED`, `ETIMEDOUT`, `ECONNRESET`, `EHOSTUNREACH`, `ENETUNREACH`, `EPIPE`, `EADDRINUSE`, `EADDRNOTAVAIL`, `ENOTCONN`, `ENOBUFS` | `ChaosEffect.errno(ChaosEffect.Errno.ECONNREFUSED)` and the other network constants: the matching `java.net` exception (`BindException` for bind errors) | `{"type": "errno", "errno": "ECONNREFUSED"}` | `SOCKET_*` |
| `ERRNO` with `EMFILE`, `ENFILE` on `accept`/`connect`, `EACCES` on `bind` | the same constants on `SOCKET_ACCEPT`, `SOCKET_CONNECT`, `SOCKET_BIND`: `IOException("Too many open files")` from an accept, as the JDK throws | `{"type": "errno", "errno": "EMFILE"}` | `SOCKET_ACCEPT`, `SOCKET_CONNECT`, `SOCKET_BIND` |
| `ERRNO` with `EAGAIN`, `ENOMEM`, `ENOENT`, `EACCES` on process starts | `ChaosEffect.errno(ChaosEffect.Errno.EAGAIN)` on `OperationType.PROCESS_START`: `IOException: Cannot run program "x": error=11, Resource temporarily unavailable`, the JDK's own failure | `{"type": "errno", "errno": "EAGAIN"}` | `PROCESS_START` |
| any other `ERRNO`                               | `ChaosEffect.reject("...")`: the operation's natural exception (`UnknownHostException` for DNS, `SQLException` for JDBC, …) | `{"type": "reject", "message": "..."}` | every interceptor operation |
| `GAI` (`EAI_NONAME`, `EAI_AGAIN`)               | `ChaosEffect.reject("...")` on `OperationType.DNS_RESOLVE`: `UnknownHostException`. The agent sees every lookup, before the JDK's own `InetAddress` cache; the native layer sees only the lookups that miss it (positive answers are cached 30 s by default, `networkaddress.cache.ttl`), so a native GAI rule may not reach a JVM for a name it resolved recently | `{"type": "reject", "message": "..."}`          | `DNS_RESOLVE`                                   |
| `TORN`                                          | `ChaosEffect.tornWrite()`                                          | `{"type": "tornWrite"}`                                     | `FILE_IO_WRITE` through `FileChannel`; also `NIO_CHANNEL_WRITE` (short count) and `SOCKET_WRITE` (prefix sent, then a real reset: `SO_LINGER` 0 and close), which the native layer does not tear |
| `CORRUPT`                                       | `ChaosEffect.corruptRead()`                                        | `{"type": "corruptRead"}`                                   | `SOCKET_READ`, `FILE_IO_READ`                   |
| `TRICKLE:16`                                    | `ChaosEffect.trickle(16)`                                          | `{"type": "trickle", "maxBytes": 16}`                       | `SOCKET_READ`                                   |
| `TIMEOUT` (net `poll`)                          | `ChaosEffect.timeout()`: waits out the socket's own `SO_TIMEOUT` or connect timeout, then `SocketTimeoutException`; on a select or a process wait, waits out its timeout and reports nothing ready / not exited; never fires on an infinite timeout | `{"type": "timeout"}` | `SOCKET_READ`, `SOCKET_CONNECT`, `NIO_SELECTOR_SELECT`, `PROCESS_WAIT` |
| `RESET`                                         | `ChaosEffect.connectionReset()`                                    | `{"type": "connectionReset"}`                               | `SOCKET_CLOSE`                                  |
| `OFFSET:500` (realtime)                         | `ChaosEffect.skewClock(Duration.ofMillis(500), ChaosEffect.ClockSkewMode.FIXED)` | `{"type": "clockSkew", "skewAmount": "PT0.5S", "mode": "FIXED"}` | `INSTANT_NOW`, `CLOCK_READ`, `LOCAL_DATE_TIME_NOW`, `ZONED_DATE_TIME_NOW`, `DATE_NEW` |
| `REGRESS:1000`                                  | `ChaosEffect.skewClock(Duration.ofSeconds(1), ChaosEffect.ClockSkewMode.REGRESS)` | `{"type": "clockSkew", "skewAmount": "PT1S", "mode": "REGRESS"}` | as `OFFSET`                        |
| `OVERRIDE:127.0.0.1,[::1]`                      | `ChaosEffect.dnsOverride("127.0.0.1", "::1")`                      | `{"type": "dnsAnswer", "override": ["127.0.0.1", "::1"]}`   | `DNS_ANSWER`                                    |
| `FILTER_FAMILY:inet6`                           | `ChaosEffect.dnsFamily(ChaosEffect.AddressFamily.IPV6)`            | `{"type": "dnsAnswer", "family": "IPV6"}`                   | `DNS_ANSWER`                                    |
| `SHUFFLE`                                       | `ChaosEffect.dnsShuffle()`                                         | `{"type": "dnsAnswer", "shuffle": true}`                    | `DNS_ANSWER`                                    |
| `LIMIT:1`                                       | `ChaosEffect.dnsLimit(1)`                                          | `{"type": "dnsAnswer", "limit": 1}`                         | `DNS_ANSWER`                                    |
| `REWRITE:other.internal`                        | `ChaosEffect.dnsRewrite("other.internal")`                         | `{"type": "dnsRewrite", "host": "other.internal"}`          | `DNS_RESOLVE`                                   |
| `SERVICE` (connect elsewhere), endpoint redirect | `ChaosEffect.redirect("standby.internal", 5432)`: the connect goes to that endpoint; without a port the requested one is kept | `{"type": "redirect", "host": "standby.internal", "port": 5432}` | `SOCKET_CONNECT`                       |

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
| `OFFSET`/`REGRESS` seen by every `System.currentTimeMillis()` and `System.nanoTime()` read, JDK and libraries included | both are JIT intrinsics; the agent skews `java.time` and `java.util.Date` reads, and direct calls only at the call sites in the packages named with `clockCallSites` (`OperationType.SYSTEM_CLOCK_MILLIS`, `OperationType.SYSTEM_CLOCK_NANOS`). Use the native time rule to move the clock beneath everything in the JVM. |
| `clock_gettime/monotonic` beneath the JDK     | `System.nanoTime()` is an intrinsic (as above); only opted-in call sites are skewed |
| `DUP`                                         | no JVM effect duplicates received data; a changed service endpoint is `ChaosEffect.redirect(...)` on the connect (§3) |
| `munmap`, `mprotect`, `madvise`, and `mmap` of anonymous memory | the JVM exposes only file mappings (`FileChannel.map`, `OperationType.FILE_IO_MAP`); the pressure stressors (`ChaosEffect.heapPressure(...)`, `ChaosEffect.directBufferPressure(...)`) are the JVM-level analogue for memory |
| `fork`, failing `execve` of the child         | the agent fails the start itself (`PROCESS_START`) and the wait (`PROCESS_WAIT`); what the child does reaches the native process rules |
| net `EINTR`, `EAGAIN`, `ENOMEM`               | the JDK retries an interrupted call itself and reports a would-block non-blocking call as `0`, not as an error; there is no Java failure to raise |
| distinct GAI codes (`EAI_AGAIN`, `EAI_FAIL`, `EAI_MEMORY`, `EAI_SYSTEM`) | Java reports every failed lookup as `UnknownHostException`; `ChaosEffect.reject(...)` on `DNS_RESOLVE` is all of them |
| `socket`, `listen`, `allocate` (fallocate)    | the JDK opens sockets and listens inside `bind`/`connect`/`accept`, which the agent intercepts; Java has no `fallocate` |
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

`FileChannel.force` and `FileDescriptor.sync` then throw a `SyncFailedException` with the reason `Input/output error` (`FileDescriptor.sync` declares no other exception).

The same scenario through the Java API (this block is compiled by the documentation test):

```java verified
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.api.PathPattern;
import java.util.Set;

public final class FsyncEio {
  public static ChaosScenario scenario() {
    return ChaosScenario.builder("fsync-eio-5pct")
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(
            new ChaosSelector.FileIoSelector(
                Set.of(OperationType.FILE_IO_SYNC), PathPattern.under("/var/lib/app"), null, null))
        .effect(ChaosEffect.errno(ChaosEffect.Errno.EIO))
        .activationPolicy(ActivationPolicy.builder().probability(0.05).build())
        .build();
  }
}
```

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

JVM, seen by every `java.time` and `java.util.Date` read, but not by `System.currentTimeMillis()` outside the opted-in call sites (§5.1):

```json
{"id": "clock-regress", "scope": "JVM",
 "selector": {"type": "jvmRuntime",
              "operations": ["INSTANT_NOW", "CLOCK_READ", "LOCAL_DATE_TIME_NOW", "ZONED_DATE_TIME_NOW", "DATE_NEW"]},
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
