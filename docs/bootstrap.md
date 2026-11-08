<!--
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Engineered by  Christian Schnapka
                 Embedded Principal+ Engineer
                 Macstab GmbH · Hamburg, Germany
                 https://macstab.com
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-->

# chaos-agent-bootstrap — Agent Entry Point Reference

> Internal reference for agent initialization, singleton management, premain/agentmain dispatch, and JMX MBean registration.
> 
> *Engineered by* **[Christian Schnapka](https://macstab.com)** — Embedded Principal+ Engineer · [Macstab GmbH](https://macstab.com) · Hamburg, Germany

---

# 1. Overview

## Purpose

`chaos-agent-bootstrap` is the JVM agent entry point. It owns:
- `premain(agentArgs, instrumentation)` — JVM calls this when the agent is specified via `-javaagent:`
- `agentmain(agentArgs, instrumentation)` — JVM calls this for dynamic attach via the Attach API
- Singleton `ChaosRuntime` creation and lifecycle
- Calling `JdkInstrumentationInstaller.install()` with the weaving mode (`eager` or the default on-demand weaving, from the `instrumentation` agent arg, `-Dmacstab.chaos.instrumentation` or `MACSTAB_CHAOS_INSTRUMENTATION`); premain, agentmain and self-attach take the same path
- Loading the `ChaosPlan` from startup configuration via `StartupConfigLoader`
- Registering the `ChaosDiagnostics` JMX MBean
- `ChaosPlatform.installLocally()` — self-attach path used by `chaos-agent-testkit`

**Supported entry point.** `ChaosPlatform` is the only supported API of this module: `installLocally()` installs the agent (self-attach) and returns the control plane, `current()` returns the installed control plane or throws `IllegalStateException`, and `installed()` returns it as an `Optional`, empty while no agent is installed, without throwing — use it to ask whether the agent is installed. `ChaosAgentBootstrap` and the other bootstrap types are the agent's implementation; their signatures can change in any release.

## Scope

In scope:
- Agent manifest entry points
- Singleton `ChaosRuntime` management (AtomicReference)
- JMX MBean registration/deregistration
- Startup config delegation to `StartupConfigLoader`

Out of scope:
- Scenario policy (delegated to `chaos-agent-core`)
- Instrumentation wiring (delegated to `chaos-agent-instrumentation-jdk`)
- Configuration parsing (delegated to `chaos-agent-startup-config`)

## Non-Goals

- Multi-instance support (one `ChaosRuntime` per JVM, enforced by singleton pattern)
- Remote control plane (no network listener)

---

# 2. Engineerural Context

**Dependencies** (outbound):
- `chaos-agent-core` (`ChaosRuntime`)
- `chaos-agent-instrumentation-jdk` (`JdkInstrumentationInstaller`)
- `chaos-agent-startup-config` (`StartupConfigLoader`)
- `chaos-agent-api`

**Called by**:
- JVM agent infrastructure (`premain`, `agentmain`)
- `chaos-agent-testkit` (`ChaosPlatform.installLocally()`)

---

# 3. Initialization Sequence

## Packaging

The published `chaos-agent-bootstrap` JAR is the agent. It is built by the Shadow plugin (`./gradlew :chaos-agent-bootstrap:shadowJar`) and contains every module it needs (`chaos-agent-api`, `-core`, `-instrumentation-jdk`, `-startup-config`) together with ByteBuddy and Jackson. Both libraries are relocated to `com.macstab.chaos.jvm.shaded.*`, so an application's own ByteBuddy (Mockito, Hibernate) or Jackson can neither replace nor be replaced by the agent's copy. The published POM therefore declares no dependencies.

The API classes reference neither library (the JSON mapping lives in `chaos-agent-startup-config` as Jackson mix-ins), so the copy inside the agent JAR is identical to `chaos-agent-api`. Integrations (Spring Boot, Quarkus, Micronaut, testkit) depend on `chaos-agent-api` and the agent JAR only; they reach core and startup-config classes through the agent JAR.

Inside this build, project dependencies on `:chaos-agent-bootstrap` resolve to its thin JAR (classifier `plain`) plus the module dependencies, unshaded. The child-JVM tests in this module run against the shaded JAR.

## Premain (startup attachment)

```
-javaagent:chaos-agent-bootstrap.jar[=agentArgs]
  ↓
JVM calls: ChaosAgentBootstrap.premain(agentArgs, instrumentation)
  ↓
initialize(agentArgs, instrumentation, System.getenv())
  ↓
claim the INITIALIZATION slot (AtomicReference<CompletableFuture<ChaosRuntime>>)
  — already completed  → return the existing runtime (idempotent)
  — in progress        → wait for the other initializer's outcome
  ↓
ChaosRuntime runtime = new ChaosRuntime()
  ↓
JdkInstrumentationInstaller.install(instrumentation, runtime, eager)
  — eager = eagerInstrumentation(agentArgs, env): agent arg instrumentation=eager,
    else -Dmacstab.chaos.instrumentation, else MACSTAB_CHAOS_INSTRUMENTATION
  — refuses to install if a copy of the agent loaded by another class loader
    already owns the bridge delegate (one copy per JVM); checked first, so a
    refused copy appends no bridge jar and opens no module
  — injects the bridge, opens the JDK-internal packages, then installs the
    delegate, sets the session-header hosts and registers the HookDemand
    callback; a failure from the delegate on removes the delegate again
  — on demand (default): no hook group woven yet; each group is woven (retransformed)
    the first time an activated scenario needs one of its operations
  — eager: every hook group woven now (the 1.x behaviour)
  ↓
registerMBean(runtime)            // skipped with a warning if the name is taken
  ↓
JfrIntegration.installIfAvailable(runtime)
  ↓
Optional<StartupConfigLoader.LoadedPlan> plan =
    StartupConfigLoader.load(agentArgs, System.getenv())
  if plan present:
    file source + configWatchInterval → StartupConfigPoller.startWithInitialPlan(...)
    otherwise                         → runtime.activate(plan.get().plan())
                                         (weaves the hook groups the plan needs)
    if plan.get().debugDumpOnStart():
      System.err.println(runtime.diagnostics().debugDump())
  ↓
complete the INITIALIZATION future   // publishes the runtime to current()

on any failure: woven hook groups and bridge delegate removed (uninstall), MBean
unregistered, runtime closed, slot freed — a later call can retry cleanly
```

## Agentmain (dynamic attach)

```
VirtualMachine.attach(pid).loadAgent("chaos-agent-bootstrap.jar", agentArgs)
  ↓
Opt-in check: System.getProperty("macstab.chaos.allow-dynamic-attach") /
              MACSTAB_CHAOS_ALLOW_DYNAMIC_ATTACH env var must be true
              (true|1|yes|on, case-insensitive).
An attach after the agent is installed changes nothing; when its args differ
from the installed ones, one stderr line names the ignored keys.
Without the opt-in, agentmain is a no-op (security: prevents drive-by injection
from any process with jcmd/attach permission).
  ↓
JVM calls: ChaosAgentBootstrap.agentmain(agentArgs, instrumentation)
  ↓
initialize(agentArgs, instrumentation, env)
  ↓
Same installation as premain. Every hook group is woven through retransformation
(on demand, or all at once with instrumentation=eager), so dynamic attach gets the
full hook set: executors, queues, Socket/NIO/HTTP/JDBC/TLS/DNS/clock/GC/JNDI/JMX/
Serialization/... — nothing is premain-only.
```

The opt-in remains the safeguard against rewriting a live production JVM: without it, `agentmain` does nothing. `ChaosPlatform.installLocally()` (below) goes through `installForLocalTests()` and gets the same hook set; it is intended for test-JVM self-attach, not for attaching to an unknown production process.

## Idempotency

`initialize()` claims a single `INITIALIZATION` slot with a compare-and-exchange before doing any work. Exactly one caller performs the installation; concurrent callers wait for its outcome and receive the same runtime (or the same failure). A completed initialization is returned as-is. If the installation fails, everything it installed is rolled back and the slot is freed, so a later call starts from a clean JVM. `installForLocalTests()` additionally serialises the self-attach under a lock so the attach work is not done twice.

**One copy per JVM**: if a second copy of the agent, loaded by a different class loader (for example an agent jar on the system class path plus a starter bundled child-first in a web application), tries to install, it is refused with an `IllegalStateException` — installing would silently cut the first copy off from every intercepted call. The check runs before anything that cannot be undone: the refused copy appends no bridge jar to the bootstrap class path and opens no module. `JdkInstrumentationInstaller.connectDispatcher` (benchmarks and tests on the plain `chaos-agent-instrumentation-jdk` classes, `@Internal`) makes the same check, and closing it removes only its own delegate.

---

# 4. ChaosPlatform — Self-Attach Path

`ChaosPlatform.installLocally()` is a thin facade over `ChaosAgentBootstrap.installForLocalTests()`.

Self-attach sequence:

```
if (RUNTIME.get() != null) return existing runtime           // idempotent guard

Instrumentation instrumentation = ByteBuddyAgent.install()   // JVM Attach API via ByteBuddy
initialize("", instrumentation, Map.of())                    // same hook set as premain

// hook groups retransform already-loaded JDK classes (Socket, NIO, HTTP, JDBC, ...)
// on demand via Instrumentation.retransformClasses() under RETRANSFORMATION strategy
```

**JDK requirement**: `ByteBuddyAgent.install()` uses the JVM Attach API (`com.sun.tools.attach.VirtualMachine`). On JDK 9+, self-attach requires the JVM to be started with `-Djdk.attach.allowAttachSelf=true`. `ChaosAgentExtension` (in the test starters) handles this automatically. For production dynamic attach, the host process must permit self-attachment or the attach must originate from a separate process.

`ByteBuddyAgent.install()` uses `VirtualMachine` internally but is a higher-level wrapper that also handles the JAR creation/location concerns automatically.

**Hooks under self-attach**: `JdkInstrumentationInstaller` weaves every hook group — on demand, when a scenario first needs it — using `AgentBuilder` configured with `RedefinitionStrategy.RETRANSFORMATION` and `disableClassFormatChanges()`, exactly as for `-javaagent:`. JDK classes already loaded into the JVM (including `java.net.Socket`, `java.nio.channels.SocketChannel`, `java.net.http.HttpClient`, JDBC drivers) are retransformed in-place. Future calls to their methods fire the chaos instrumentation.

**Fallback**: If self-attach fails (JRE without the Attach API, security manager prohibition, or `allowAttachSelf` not set), `installForLocalTests()` throws `IllegalStateException`. In that case, run with `-javaagent:chaos-agent-bootstrap.jar` instead.

---

# 5. JMX MBean

The MBean is registered at `com.macstab.chaos.jvm:type=ChaosDiagnostics`.

**Interface**: `ChaosDiagnosticsMXBean` exposes a single operation:
- `debugDump()` → `String` (full human-readable diagnostic text)

There are no attributes; structured data (`snapshot()`, `scenario(id)`) is available only through the `ChaosDiagnostics` API in code.

**Registration**: Uses `ManagementFactory.getPlatformMBeanServer()`. If an MBean with that name is already registered, registration is skipped with a warning on stderr and the agent continues without its own MBean.

**Deregistration**: Not performed on a successful start; the MBean persists for the JVM lifetime. If initialization fails, the MBean registered by that attempt is unregistered as part of the rollback.

---

# 6. Singleton and Lifecycle

```java
private static final AtomicReference<ChaosRuntime> RUNTIME_REF = new AtomicReference<>();

public static ChaosControlPlane current() {
    ChaosRuntime runtime = RUNTIME_REF.get();
    if (runtime == null) throw new IllegalStateException("agent not installed");
    return runtime;
}
```

The `INITIALIZATION` future is the authoritative reference. Once completed successfully, it is never cleared or replaced. `ChaosControlPlane.close()` stops all scenarios and makes the control plane reject further `activate`/`openSession`/`addEventListener` calls, but does not clear the published reference.

---

# 7. Error Handling

| Failure                                      | Behavior                                                                                                          |
|----------------------------------------------|-------------------------------------------------------------------------------------------------------------------|
| Config file not found / parse error          | `ConfigLoadException` thrown; agent startup fails; JVM may not start (depends on JVM's `-javaagent` error policy) |
| ByteBuddy transformation failure for a class | Warning logged; specific class uninstrumented; agent continues                                                    |
| Bootstrap bridge injection failure           | `IllegalStateException`; agent startup fails                                                                      |
| Hook group weaving failure                   | `ChaosInstrumentationException` from the `activate()` needing the group; recorded as `INSTRUMENTATION_FAILURE`    |
| MBean registration failure                   | Warning logged; diagnostics accessible only via API; agent continues                                              |
| `installLocally()` self-attach failure       | `IllegalStateException` propagated to `ChaosTestKit.install()` caller                                             |

---

# 8. References

- Reference: `java.lang.instrument.Instrumentation` — `premain`, `agentmain`, manifest attributes (`Premain-Class`, `Agent-Class`, `Can-Retransform-Classes`) — https://docs.oracle.com/en/java/docs/api/java.instrument/java/lang/instrument/Instrumentation.html
- Reference: Java SE API — `com.sun.tools.attach.VirtualMachine` (self-attach via Attach API) — https://docs.oracle.com/en/java/docs/api/jdk.attach/com/sun/tools/attach/VirtualMachine.html
- Reference: Java SE API — `javax.management.MBeanServer`, `ManagementFactory.getPlatformMBeanServer()` — https://docs.oracle.com/en/java/docs/api/java.management/javax/management/MBeanServer.html
- Reference: Java SE API — `java.util.concurrent.atomic.AtomicReference` — https://docs.oracle.com/en/java/docs/api/java.base/java/util/concurrent/atomic/AtomicReference.html
- Reference: JEP 451 — `jdk.attach.allowAttachSelf` restriction (JDK 9+) — https://openjdk.org/jeps/451

---

<div align="center">

*Architecture, implementation, and documentation crafted with Love and Passion by*

**[Christian Schnapka](https://macstab.com)**  
Embedded Principal+ Engineer  
[Macstab GmbH](https://macstab.com) · Hamburg, Germany

*Building systems that operate correctly at the edges — including the ones you deliberately break.*

</div>
