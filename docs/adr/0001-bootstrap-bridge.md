<!--
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Engineered by  Christian Schnapka
                 Embedded Principal+ Engineer
                 Macstab GmbH · Hamburg, Germany
                 https://macstab.com
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
-->

# 0001 — Advice reaches the agent through a bootstrap-loaded bridge of method-handle slots

**Status:** Accepted

## Context

Advice is inlined into JDK classes (`Thread`, `SocketChannelImpl`, `FileChannelImpl`, …) that the bootstrap class loader defines. Code there can only resolve types the bootstrap loader sees; the agent's classes live in the system class loader. A direct call from inlined advice to `ChaosRuntime` fails with `NoClassDefFoundError` inside the JDK class.

## Decision

A small set of classes (`BridgeInjector.BRIDGE_CLASSES`, led by `BootstrapDispatcher`) is written into a jar appended to the bootstrap class path. Advice calls static methods of `BootstrapDispatcher`; those invoke a table of `MethodHandle`s, built with `MethodHandles.publicLookup()` against the `BridgeDelegate` interface and bound to the agent at install time. Each slot is a `public static final int` constant `FOO_BAR` served by the delegate method `fooBar`; the table is derived and checked in full at install. A per-thread depth counter returns the unmodified result for calls the agent itself makes (L3) and for nested calls of the same logical operation (L4).

## Consequences

- JDK classes reach the agent without a type reference to it; the agent's classes can be replaced in tests without touching the bootstrap side.
- Every bridge class and every type it references must be in the bridge jar. `ArchitectureTest` checks the jar is closed, and that inlined advice uses only public members.
- One indirection per intercepted call: an idle dispatch costs about 7 ns and allocates nothing (`BridgeDispatchBenchmark`).
- The slot table is documented in `docs/instrumentation.md` §7 and checked by `HandleTableDocTest`.

## Alternatives rejected

- **Injecting the whole agent into the bootstrap loader**: puts ByteBuddy, Jackson and every agent class on the bootstrap class path for the JVM's lifetime and makes them visible to every JDK class.
- **Reflection per call**: allocates and is an order of magnitude slower than an invoked `MethodHandle`.
