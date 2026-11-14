package com.macstab.chaos.jvm.core;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The bootstrap dispatcher's reentrancy depth, raised around the agent's own work so no chaos
 * interception applies to it (L3): the clock reads, parks, sleeps, thread starts, log lines and
 * stressors of the agent.
 *
 * <p>The dispatcher lives on the bootstrap class path, which this module cannot compile against
 * (the core does not depend on the instrumentation), so it is reached by name through method
 * handles. The names are the constants below; {@code DispatchDepthLinkTest} in the instrumentation
 * module fails when the bridge class or its two methods are renamed, which would otherwise turn L3
 * off silently (the lookup failing reads as "not installed").
 */
final class DispatchDepth {

  /** The bootstrap dispatcher's class: {@code BootstrapDispatcher}. */
  static final String BOOTSTRAP_DISPATCHER =
      "com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher";

  /** The dispatcher's {@code public static void} method that raises the depth. */
  static final String BEGIN_INTERNAL_CALL = "beginInternalCall";

  /** The dispatcher's {@code public static void} method that lowers the depth again. */
  static final String END_INTERNAL_CALL = "endInternalCall";

  /**
   * The bootstrap dispatcher's {@code beginInternalCall} / {@code endInternalCall}, resolved on
   * first use; holds {@code null} without the agent (a plain runtime in a unit test).
   */
  private static volatile java.util.concurrent.atomic.@Nullable AtomicReference<
          @Nullable InternalCalls>
      internalCalls = null;

  private record InternalCalls(MethodHandle begin, MethodHandle end) {}

  private DispatchDepth() {}

  /**
   * Forgets a dispatcher found absent, so the next use looks it up again: called when a runtime is
   * connected to the instrumentation, which injects the dispatcher. A runtime used before that (a
   * scenario started before the install) would otherwise never raise the depth.
   */
  static void forgetAbsent() {
    synchronized (DispatchDepth.class) {
      final var resolved = internalCalls;
      if (resolved != null && resolved.get() == null) {
        internalCalls = null;
      }
    }
  }

  private static @Nullable InternalCalls resolveInternalCalls() {
    final var resolved = internalCalls;
    if (resolved != null) {
      return resolved.get();
    }
    synchronized (DispatchDepth.class) {
      if (internalCalls != null) {
        return internalCalls.get();
      }
      InternalCalls calls;
      try {
        final Class<?> dispatcher =
            Class.forName(BOOTSTRAP_DISPATCHER, false, ClassLoader.getPlatformClassLoader());
        final MethodType noArgs = MethodType.methodType(void.class);
        final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        calls =
            new InternalCalls(
                lookup.findStatic(dispatcher, BEGIN_INTERNAL_CALL, noArgs),
                lookup.findStatic(dispatcher, END_INTERNAL_CALL, noArgs));
      } catch (final ReflectiveOperationException notInstalled) {
        calls = null;
      }
      internalCalls = new java.util.concurrent.atomic.AtomicReference<>(calls);
      return calls;
    }
  }

  /**
   * Returns whether the bootstrap dispatcher is installed, so {@link #increment()} raises a depth.
   *
   * @return {@code true} when the dispatcher is installed
   */
  static boolean installed() {
    return resolveInternalCalls() != null;
  }

  /**
   * Raises the bootstrap dispatcher's depth on the current thread, so no chaos interception applies
   * to the agent's own calls (clock reads, park, sleep, …) until the matching {@link #decrement()}.
   * {@code ScenarioController.start()} uses it so a concurrently active clock skew cannot shift the
   * {@code startedAt} anchor of the {@code activeFor} window.
   */
  static void increment() {
    final InternalCalls calls = resolveInternalCalls();
    if (calls != null) {
      invoke(calls.begin());
    }
  }

  /** Paired decrement for {@link #increment()}. Always call in a {@code finally}. */
  static void decrement() {
    final InternalCalls calls = resolveInternalCalls();
    if (calls != null) {
      invoke(calls.end());
    }
  }

  private static void invoke(final MethodHandle handle) {
    try {
      handle.invokeExact();
    } catch (final RuntimeException | Error unchecked) {
      throw unchecked;
    } catch (final Throwable checked) {
      throw new IllegalStateException(checked);
    }
  }

  /**
   * Reads the real time: with the dispatch depth raised, so an active clock-skew scenario does not
   * apply to the agent's own timestamps.
   *
   * @param clock the clock to read
   * @return the clock's instant
   */
  static Instant unskewedInstant(final java.time.Clock clock) {
    increment();
    try {
      return clock.instant();
    } finally {
      decrement();
    }
  }
}
