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
- Calling `JdkInstrumentationInstaller.install()` with the correct premain/agentmain mode flag
- Loading the `ChaosPlan` from startup configuration via `StartupConfigLoader`
- Registering the `ChaosDiagnostics` JMX MBean
- `ChaosPlatform.installLocally()` — self-attach path used by `chaos-agent-testkit`

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

## Premain (startup attachment)

```
-javaagent:chaos-agent-bootstrap.jar[=agentArgs]
  ↓
JVM calls: ChaosAgentBootstrap.premain(agentArgs, instrumentation)
  ↓
if (singleton ChaosRuntime already set) → return (idempotent guard)
  ↓
ChaosRuntime runtime = new ChaosRuntime()
  ↓
JdkInstrumentationInstaller.install(instrumentation, runtime, premainMode=true)
  — Phase 1 + Phase 2 instrumentation
  ↓
Optional<StartupConfigLoader.LoadedPlan> plan =
    StartupConfigLoader.load(agentArgs, System.getenv())
  if plan present:
    runtime.activate(plan.get().plan())
    if plan.get().debugDumpOnStart():
      System.out.println(runtime.diagnostics().debugDump())
  ↓
registerMBean(runtime.diagnostics())
  ↓
runtime.setInstrumentation(instrumentation)
  ↓
RUNTIME_REF.set(runtime)   // AtomicReference; last write; visible to all threads
```

## Agentmain (dynamic attach)

```
VirtualMachine.attach(pid).loadAgent("chaos-agent-bootstrap.jar", agentArgs)
  ↓
JVM calls: ChaosAgentBootstrap.agentmain(agentArgs, instrumentation)
  ↓
Same flow as premain EXCEPT:
  JdkInstrumentationInstaller.install(instrumentation, runtime, premainMode=false)
  — Phase 1 instrumentation ONLY (no Phase 2 retransformation)
```

## Idempotency

`premain`/`agentmain` first checks `RUNTIME_REF.get() != null`. If the runtime is already installed, the method returns immediately. This prevents double-installation in test environments where multiple classes call `ChaosTestKit.install()` concurrently before the first install completes.

**Race condition**: Two threads calling `installLocally()` concurrently could both observe `RUNTIME_REF.get() == null` and both attempt installation. The `AtomicReference.compareAndSet()` in the final step ensures only one succeeds; the loser's `ChaosRuntime` is discarded. However, `JdkInstrumentationInstaller.install()` may run twice in this race, attempting to register ByteBuddy transformations twice. ByteBuddy handles duplicate registrations gracefully (no-op for already-woven methods), but this is a latent inefficiency. In practice, concurrent `installLocally()` calls are rare.

---

# 4. ChaosPlatform — Self-Attach Path

`ChaosPlatform.installLocally()` is called by `ChaosTestKit.install()` in test environments that do not use `-javaagent:`. The self-attach sequence:

```
if (RUNTIME_REF.get() != null) return existing runtime

// Locate the agent JAR (own classpath)
URL agentJar = findAgentJar()

// Use the Attach API to self-attach
VirtualMachine vm = VirtualMachine.attach(ProcessHandle.current().pid().toString())
vm.loadAgent(agentJar.getPath())
vm.detach()

// After loadAgent(), agentmain() has run → RUNTIME_REF is set
return RUNTIME_REF.get()
```

**JDK requirement**: Self-attach requires `com.sun.tools.attach.VirtualMachine` and the Attach API, available in the JDK (not JRE). On JDK 9+, self-attach may be restricted by the JVM flag `-Djdk.attach.allowAttachSelf=true` (which must be set on the target JVM). `ChaosAgentExtension` sets this system property before attempting self-attach.

**Fallback**: If self-attach fails (e.g., on a JRE without tools.jar, or when the attach API is not available), `installLocally()` throws `IllegalStateException`.

---

# 5. JMX MBean

The MBean is registered at `com.macstab.chaos:type=ChaosDiagnostics`.

**Interface**: The MBean exposes `ChaosDiagnostics`:
- `snapshot()` → `Snapshot` (serialized as JMX composite data)
- `debugDump()` → `String` (full human-readable diagnostic text)
- `scenario(String id)` → `Optional<ScenarioReport>` (may not be directly JMX-composable; use `debugDump()` for operator inspection)

**Registration**: Uses `ManagementFactory.getPlatformMBeanServer()`. If the MBean is already registered (e.g., from a prior `installLocally()` call in the same JVM), the existing registration is reused.

**Deregistration**: Not performed automatically. The MBean persists for the JVM lifetime. This is consistent with the agent's design principle that instrumentation is permanent.

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

`RUNTIME_REF` is the authoritative reference. Once set, it is never cleared or replaced. `ChaosControlPlane.close()` stops all active scenarios but does not null out `RUNTIME_REF`.

---

# 7. Error Handling

| Failure | Behavior |
|---------|----------|
| Config file not found / parse error | `ConfigLoadException` thrown; agent startup fails; JVM may not start (depends on JVM's `-javaagent` error policy) |
| ByteBuddy transformation failure for a class | Warning logged; specific class uninstrumented; agent continues |
| Bootstrap bridge injection failure | `IllegalStateException`; agent startup fails |
| MBean registration failure | Warning logged; diagnostics accessible only via API; agent continues |
| `installLocally()` self-attach failure | `IllegalStateException` propagated to `ChaosTestKit.install()` caller |

---

# 8. References

- Reference: `java.lang.instrument.Instrumentation` — `premain`, `agentmain`, manifest attributes (`Premain-Class`, `Agent-Class`, `Can-Retransform-Classes`) — https://docs.oracle.com/en/java/docs/api/java.instrument/java/lang/instrument/Instrumentation.html
- Reference: Java SE API — `com.sun.tools.attach.VirtualMachine` (self-attach via Attach API) — https://docs.oracle.com/en/java/docs/api/jdk.attach/com/sun/tools/attach/VirtualMachine.html
- Reference: Java SE API — `javax.management.MBeanServer`, `ManagementFactory.getPlatformMBeanServer()` — https://docs.oracle.com/en/java/docs/api/java.management/javax/management/MBeanServer.html
- Reference: Java SE API — `java.util.concurrent.atomic.AtomicReference` — https://docs.oracle.com/en/java/docs/api/java.base/java/util/concurrent/atomic/AtomicReference.html
- Reference: JEP 451 — `jdk.attach.allowAttachSelf` restriction (JDK 9+) — https://openjdk.org/jeps/451

---

<div align="center">

*Engineerure, implementation, and documentation crafted by*

**[Christian Schnapka](https://macstab.com)**  
Embedded Principal+ Engineer  
[Macstab GmbH](https://macstab.com) · Hamburg, Germany

*Building systems that operate correctly at the edges — including the ones you deliberately break.*

</div>
