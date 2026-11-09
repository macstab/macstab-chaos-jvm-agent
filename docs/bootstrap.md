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

# 2. Architectural Context

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

The bootstrap bridge classes (`BridgeInjector.BRIDGE_CLASSES`) are stored in the agent JAR under `META-INF/macstab-chaos-bridge/`, not under their class names; `BridgeInjector` reads them from there when it writes the bridge jar. So a class loader that reads the agent JAR before its parent (the JAR in a web application's `WEB-INF/lib`) cannot define a `BootstrapDispatcher` of its own, and the hooks in its classes reach the agent (`ChildFirstLoaderIntegrationTest`).

## Premain (startup attachment)

```
-javaagent:chaos-agent-bootstrap.jar[=agentArgs]
  ↓
JVM calls: ChaosAgentBootstrap.premain(agentArgs, instrumentation)
  ↓
AgentLog.hold()                   // the agent's log lines are held, not written (see below)
  ↓
initialize(agentArgs, instrumentation, System.getenv())
  ↓
claim the INITIALIZATION slot (AtomicReference<CompletableFuture<ChaosRuntime>>)
  — already completed  → return the existing runtime (idempotent)
  — in progress        → wait for the other initializer's outcome
  ↓
ChaosRuntime runtime = new ChaosRuntime()
  ↓
JdkInstrumentationInstaller.install(instrumentation, runtime, eager, …, bridgeDir)
  — eager = eagerInstrumentation(agentArgs, env): agent arg instrumentation=eager,
    else -Dmacstab.chaos.instrumentation, else MACSTAB_CHAOS_INSTRUMENTATION
  — bridgeDir = agent arg bridgeDir, else -Dmacstab.chaos.bridgeDir, else
    MACSTAB_CHAOS_BRIDGE_DIR; checked by DirectoryTrust (not world-writable without
    the sticky bit, not another user's, parent likewise), a refused one aborts the
    start; unset: java.io.tmpdir, then /dev/shm
  — refuses to install if the bootstrap class path already holds the bridge of
    another agent revision (claimed by another version, or unclaimed), or if a
    copy of the agent loaded by another class loader already owns the bridge
    delegate (one copy per JVM); checked first, so a refused copy appends no
    bridge jar and opens no module
  — injects the bridge, opens the JDK-internal packages, then installs the
    delegate, sets the session-header hosts and registers the HookDemand
    callback; a failure from the delegate on removes the delegate again
  — on demand (default): no hook group woven yet; each group is woven (retransformed)
    the first time an activated scenario needs one of its operations
  — eager: every hook group woven now (the 1.x behaviour)
  ↓
registerMBean(runtime)            // skipped with a warning if the name is taken;
                                  // premain defers it (see below)
  ↓
JfrIntegration.installIfAvailable(runtime)
  ↓
ChaosReports.createIfEnabled(settings, runtime)   // status and hits files, reports=on
  ↓
Optional<StartupConfigLoader.LoadedPlan> plan =
    StartupConfigLoader.load(settings)   // agent args, system properties, environment
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
  ↓
premain only: the agent thread macstab-chaos-logging-start waits until the
application has initialised java.util.logging (at most 30 s), then registers
the MBean and writes the held log lines
```

**`java.util.logging` stays the application's**: the first JUL logger, and the platform MBean server, initialise the `LogManager`, and the class that becomes the `LogManager` is read from `java.util.logging.manager` at that moment. An application that sets the property in its `main` (WildFly's JBoss Modules, Quarkus in production) needs the agent to leave JUL alone during `premain`. So no agent class holds a static JUL logger (each logs through `AgentLog`, which looks its logger up when the first line is written), `premain` holds the agent's lines in a buffer of 256 (the oldest dropped and counted in one line), and the MBean is not registered yet. An agent thread reads whether the `LogManager` class has been initialised, without initialising it (`jdk.internal.misc.Unsafe.shouldBeInitialized`, through the `java.base/jdk.internal.misc` open the agent holds), every 20 ms; once it has, or after 30 s, the thread registers the MBean and writes the held lines with their original time. A JVM that exits before then writes the lines still held from a shutdown hook, straight to `System.err` (`<time> <LEVEL> <logger>: <message>`), since JUL's own shutdown hook may already have closed its handlers. A JVM whose application never initialises JUL gets the MBean after 30 s; attaching a JMX client starts the JDK's management agent, which initialises JUL and so ends the wait. `agentmain` and self-attach run after `main` has started and register the MBean at once.

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

The opt-in remains the safeguard against rewriting a live production JVM: without it, `agentmain` does nothing. This holds for an executable jar whose manifest names `Launcher-Agent-Class: com.macstab.chaos.jvm.bootstrap.ChaosAgentBootstrap` too: the JVM starts that agent through `agentmain`, so it needs the same opt-in. Security outranks convenience here, so the gate is kept: the jar's launcher sets `MACSTAB_CHAOS_ALLOW_DYNAMIC_ATTACH=true` (or `-Dmacstab.chaos.allow-dynamic-attach=true`). `ChaosPlatform.installLocally()` (below) goes through `installForLocalTests()` and gets the same hook set; it is intended for test-JVM self-attach, not for attaching to an unknown production process.

## Idempotency

`initialize()` claims a single `INITIALIZATION` slot with a compare-and-exchange before doing any work. Exactly one caller performs the installation; concurrent callers wait for its outcome and receive the same runtime (or the same failure). A completed initialization is returned as-is. If the installation fails, everything it installed is rolled back and the slot is freed, so a later call starts from a clean JVM. `installForLocalTests()` additionally serialises the self-attach under a lock so the attach work is not done twice.

**One copy per JVM**: if a second copy of the agent, loaded by a different class loader (for example an agent jar on the system class path plus a starter bundled child-first in a web application), tries to install, it is refused with an `IllegalStateException` — installing would silently cut the first copy off from every intercepted call. The check runs before anything that cannot be undone: the refused copy appends no bridge jar to the bootstrap class path and opens no module. `JdkInstrumentationInstaller.connectDispatcher` (benchmarks and tests on the plain `chaos-agent-instrumentation-jdk` classes, `@Internal`) makes the same check, and closing it removes only its own delegate.

**One agent version per JVM**: the bridge on the bootstrap class path is shared by every copy, and a copy's delegate and advice only fit its own bridge classes. The copy that appends the bridge claims it with its revision, a digest of the bridge class bytes, through `BootstrapDispatcher.claimRevision` (set-once: a later claim returns the first one, which also settles two copies that both appended a bridge). A later install compares its own revision with the claimed one before anything else; another revision, a bridge that nobody claimed (placed with `-Xbootclasspath/a`) or a dispatcher without `claimRevision` (an agent version that predates it) refuses the install with `a chaos agent of revision … is already installed in this JVM, and this copy is revision …; restart with one agent version`. `-javaagent` appends its jar at the end of the system class path, so an agent copy on the class path itself runs in its place: `premain` compares the `Implementation-Version` of every `ChaosAgentBootstrap` copy the system class loader sees and prints one `[chaos-agent]` warning naming each jar when they differ (one version: FINE only; a classes directory has no version and is not compared). The Quarkus extensions declare `chaos-agent-api` and `chaos-agent-bootstrap` as `parent-first-artifacts` and `runner-parent-first-artifacts` (in the packaged application they move to `lib/boot`), so Quarkus' class loader defines no copy of its own.

---

# 4. ChaosPlatform — Self-Attach Path

`ChaosPlatform.installLocally()` is a thin facade over `ChaosAgentBootstrap.installForLocalTests()`.

Self-attach sequence:

```
if (installedRuntime() != null) return it                    // idempotent guard (INITIALIZATION)
synchronized (INSTALL_LOCK) { check again, then:
  Instrumentation instrumentation = ByteBuddyAgent.install() // JVM Attach API via ByteBuddy
  initialize("", instrumentation, System.getenv())           // same hook set as premain;
}                                                            // MACSTAB_CHAOS_* switches apply

// hook groups retransform already-loaded JDK classes (Socket, NIO, HTTP, JDBC, ...)
// on demand via Instrumentation.retransformClasses() under RETRANSFORMATION strategy
```

**JDK requirement**: `ByteBuddyAgent.install()` uses the JVM Attach API (`com.sun.tools.attach.VirtualMachine`, module `jdk.attach`). No JVM flag is required: when the JVM does not allow a process to attach to itself (`jdk.attach.allowAttachSelf` unset, the default since JDK 9), ByteBuddy attaches from a short-lived helper process, and the agent installs as usual. On JDK 21 and later the JVM then prints the [JEP 451](https://openjdk.org/jeps/451) warning on stderr ("WARNING: A Java agent has been loaded dynamically …"). It is a warning only; chaos works. The agent does not suppress it. To avoid it, either load the agent at startup with `-javaagent:` (no dynamic loading, no warning), or add `-XX:+EnableDynamicAgentLoading` to the test JVM, which allows the dynamic load explicitly. A JVM started with `-XX:-EnableDynamicAgentLoading` refuses the self-attach; `installLocally()` then fails with `IllegalStateException`, and `-javaagent:` is the way to run.

`ByteBuddyAgent.install()` uses `VirtualMachine` internally but is a higher-level wrapper that also handles the JAR creation/location concerns automatically.

**Hooks under self-attach**: `JdkInstrumentationInstaller` weaves every hook group — on demand, when a scenario first needs it — using `AgentBuilder` configured with `RedefinitionStrategy.RETRANSFORMATION` and `disableClassFormatChanges()`, exactly as for `-javaagent:`. JDK classes already loaded into the JVM (including `java.net.Socket`, `java.nio.channels.SocketChannel`, `java.net.http.HttpClient`, JDBC drivers) are retransformed in-place. Future calls to their methods fire the chaos instrumentation.

**Which jar**: self-attach, like `-javaagent:`, needs the agent JAR (`chaos-agent-bootstrap`). The thin `chaos-agent-core` and `chaos-agent-instrumentation-jdk` jars are published for builds and benchmarks that use the plain classes; never bundle them in an application. The thin instrumentation jar carries the bridge classes under their class names, so a class loader that reads it before its parent defines a `BootstrapDispatcher` of its own, without the agent's delegate. The agent does not weave that loader's classes and names it in the diagnostics' `hookFailures`. `JdkInstrumentationInstaller.connectDispatcher` (benchmarks) works on the thin classes only.

**Fallback**: If self-attach fails (a runtime without the `jdk.attach` module, `-XX:+DisableAttachMechanism`, or `-XX:-EnableDynamicAgentLoading`), `installForLocalTests()` throws `IllegalStateException`. In that case, run with `-javaagent:chaos-agent-bootstrap.jar` instead.

---

# 5. JMX MBean

The MBean is registered at `com.macstab.chaos.jvm:type=ChaosDiagnostics`.

**Interface**: `ChaosDiagnosticsMXBean` exposes:
- `debugDump()` → `String` (full human-readable diagnostic text; scenarios of a plan with `jmxEnabled=false` are left out)
- `listScenarios()` → `String[]` (`<id> state=<state> matched=<n> applied=<n>` per scenario)
- the attribute `ControlEnabled` (`isControlEnabled()`): whether the control operations below are allowed
- `activatePlan(planJson)` → `String`, `stopScenario(id)` → `boolean`, `stopAll()` → `int`: refused unless the agent was started with `jmxControl=true` (`-Dmacstab.chaos.jmx.control=true`, `MACSTAB_CHAOS_JMX_CONTROL=true`); see [observability.md §4](observability.md#4-jmx)

Structured data (`snapshot()`, `scenario(id)`) is available only through the `ChaosDiagnostics` API in code.

**Registration**: Uses `ManagementFactory.getPlatformMBeanServer()`, after `premain` once the application has initialised `java.util.logging` (section 3). If an MBean with that name is already registered, registration is skipped with a warning on stderr and the agent continues without its own MBean.

**Deregistration**: Not performed on a successful start; the MBean persists for the JVM lifetime. If initialization fails, the MBean registered by that attempt is unregistered as part of the rollback.

---

# 6. Singleton and Lifecycle

```java
private static final AtomicReference<CompletableFuture<ChaosRuntime>> INITIALIZATION = new AtomicReference<>();

public static ChaosControlPlane current() {
    ChaosRuntime runtime = installedRuntime();   // the completed INITIALIZATION future, else null
    if (runtime == null) throw new IllegalStateException("the chaos agent is not installed");
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
| MBean registration failure                   | One `[chaos-agent] MBean registration skipped: …` line on stderr; diagnostics accessible only via API; agent continues |
| `installLocally()` self-attach failure       | `IllegalStateException` propagated to `ChaosTestKit.install()` caller                                             |

---

# 8. References

- Reference: `java.lang.instrument.Instrumentation` — `premain`, `agentmain`, manifest attributes (`Premain-Class`, `Agent-Class`, `Can-Retransform-Classes`) — https://docs.oracle.com/en/java/docs/api/java.instrument/java/lang/instrument/Instrumentation.html
- Reference: Java SE API — `com.sun.tools.attach.VirtualMachine` (self-attach via Attach API) — https://docs.oracle.com/en/java/docs/api/jdk.attach/com/sun/tools/attach/VirtualMachine.html
- Reference: Java SE API — `javax.management.MBeanServer`, `ManagementFactory.getPlatformMBeanServer()` — https://docs.oracle.com/en/java/docs/api/java.management/javax/management/MBeanServer.html
- Reference: Java SE API — `java.util.concurrent.atomic.AtomicReference` — https://docs.oracle.com/en/java/docs/api/java.base/java/util/concurrent/atomic/AtomicReference.html
- Reference: JEP 451 — Prepare to Disallow the Dynamic Loading of Agents (JDK 21+) — https://openjdk.org/jeps/451

---

<div align="center">

*Architecture, implementation, and documentation crafted with Love and Passion by*

**[Christian Schnapka](https://macstab.com)**  
Embedded Principal+ Engineer  
[Macstab GmbH](https://macstab.com) · Hamburg, Germany

*Building systems that operate correctly at the edges — including the ones you deliberately break.*

</div>
