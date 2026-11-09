<!--
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Engineered by  Christian Schnapka
                 Embedded Principal+ Engineer
                 Macstab GmbH · Hamburg, Germany
                 https://macstab.com
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-->

# 0002 — Hook groups are woven on the first activation that needs them

**Status:** Accepted (2.0; supersedes the 1.x weave-everything-at-start); amended (2.0): opening a session weaves the executor hook groups, but a session alters no call until it has a registered scenario — only then is a pool task wrapped to carry it (L1); amended (2.0): the L3a brackets add weave targets to existing groups — `sun.nio.ch.Util.getTemporaryDirectBuffer` / `getTemporaryAlignedDirectBuffer` (DIRECT_BUFFER), `java.nio.Bits.reserveMemory` and `sun.nio.ch.FileChannelImpl.mapInternal` (RUNTIME_LIFECYCLE), `CompletableFuture$Delayer.delay` on JDK 21 (SCHEDULED_EXECUTOR), `CompletableFuture$Timeout.run` / `$DelayedCompleter.run` (COMPLETABLE_FUTURE), `MBeanServer.getAttributes` (JNDI_JMX) — and the internal group `JDK_INTERNAL_CLASS_LOAD` (`ClassLoader.loadClass`), which raises no operation and is woven with the FILE_IO and ZIP groups only

## Context

1.x wove every hook at agent start. A JVM that never activated a scenario still ran modified JDK classes, paid the retransformation of dozens of classes at start, and exposed every hook to every JDK and library change even when unused.

## Decision

Interception points are grouped into `JdkInstrumentationInstaller.HookGroup`s, each tagged with the `OperationType`s it serves. Activating a scenario (or opening a session) weaves the groups its operations need, once; they stay woven until the agent is uninstalled, except method hooks, which are restored when their last scenario goes (L2). `instrumentation=eager` (agent argument, `-Dmacstab.chaos.instrumentation`, `MACSTAB_CHAOS_INSTRUMENTATION`) restores weaving everything at start.

## Consequences

- With no scenario, the JVM runs unmodified JDK classes (L1).
- The first activation of a group pays its weaving; a weaving failure fails that activation with `INSTRUMENTATION_FAILURE` (L21) instead of the agent start.
- A class loaded while another group weaves can escape earlier groups' transformers; every weave therefore ends with a retransformation of the known targets.
- `wovenHookGroups` in the diagnostics shows what is woven.

## Alternatives rejected

- **Weave at start (1.x)**: kept as the `eager` mode, for JVMs that must not pay weaving during a test.
- **Weave per scenario and unweave on stop**: retransforming JDK classes back and forth under load is the riskiest thing an agent does; only method hooks, which target application classes, are restored.
