package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.core.AgentThreads;
import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Waits on an agent thread until the application has initialised {@code java.util.logging}, then
 * runs what {@code premain} deferred: the platform MBean registration and the agent's held log
 * lines both initialise the {@code LogManager}, and doing so before {@code main} sets {@code
 * java.util.logging.manager} (WildFly, Quarkus) would fix the JDK's {@code LogManager} instead of
 * the application's. A JVM whose application does not initialise JUL within {@link #LIMIT} gets the
 * deferred work then.
 */
final class LoggingStart {

  /** How long the deferred work waits for the application at most. */
  static final Duration LIMIT = Duration.ofSeconds(30);

  /** How often the {@code LogManager}'s state is read while waiting. */
  static final long POLL_MILLIS = 20L;

  private LoggingStart() {}

  /**
   * Starts a daemon thread that runs {@code deferred} once {@code initialised} answers {@code true}
   * or {@code limit} has passed, with the dispatch depth raised: its work is the agent's own, never
   * a target. The start runs with the depth raised too, so a {@code THREAD_START} scenario of a
   * startup plan never fails {@code premain} (L3).
   *
   * @param initialised whether the application has initialised JUL
   * @param limit how long to wait at most
   * @param deferred the deferred work
   * @return the started thread
   */
  static Thread runAfter(
      final BooleanSupplier initialised, final Duration limit, final Runnable deferred) {
    // AgentThreads: the thread keeps no context class loader, no inheritable thread-local and (on
    // JDK 21 to 23) no access-control context of the thread that calls premain, which can be a
    // framework starter's self-attach.
    final Thread waiter =
        AgentThreads.unstarted(
            "macstab-chaos-logging-start",
            true,
            () -> {
              BootstrapDispatcher.beginInternalCall();
              try {
                final long deadline = System.nanoTime() + limit.toNanos();
                while (!initialised.getAsBoolean() && System.nanoTime() - deadline < 0) {
                  Thread.sleep(POLL_MILLIS);
                }
              } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              } finally {
                try {
                  deferred.run();
                } finally {
                  BootstrapDispatcher.endInternalCall();
                }
              }
            });
    BootstrapDispatcher.beginInternalCall();
    try {
      waiter.start();
    } finally {
      BootstrapDispatcher.endInternalCall();
    }
    return waiter;
  }

  /**
   * Whether the {@code LogManager} class has been initialised, read without initialising it: {@code
   * jdk.internal.misc.Unsafe.shouldBeInitialized}, reachable through the {@code java.base/
   * jdk.internal.misc} open the agent holds. Loading the class is no sign: every {@code
   * ManagementFactory} use loads it. Answers {@code true} on a runtime without {@code
   * java.logging}, and {@code false} for good when the state cannot be read, so the work waits for
   * the limit. The lookups run with the dispatch depth raised: premain calls this on its own thread
   * after the startup plan is active, and the agent's {@code Method.invoke} is no {@code
   * REFLECTION_INVOKE} target (L3).
   *
   * @return the probe
   */
  static BooleanSupplier logManagerInitialised() {
    BootstrapDispatcher.beginInternalCall();
    try {
      return probe();
    } finally {
      BootstrapDispatcher.endInternalCall();
    }
  }

  /**
   * Builds the probe {@link #logManagerInitialised()} returns.
   *
   * @return the probe
   */
  private static BooleanSupplier probe() {
    final Class<?> logManager;
    try {
      logManager = Class.forName("java.util.logging.LogManager", false, null);
    } catch (final ClassNotFoundException noJul) {
      return () -> true;
    }
    final Object unsafe;
    final Method shouldBeInitialized;
    try {
      final Class<?> unsafeClass = Class.forName("jdk.internal.misc.Unsafe");
      unsafe = unsafeClass.getMethod("getUnsafe").invoke(null);
      shouldBeInitialized = unsafeClass.getMethod("shouldBeInitialized", Class.class);
    } catch (final ReflectiveOperationException | RuntimeException unreadable) {
      return () -> false;
    }
    return () -> {
      try {
        return !(Boolean) shouldBeInitialized.invoke(unsafe, logManager);
      } catch (final ReflectiveOperationException | RuntimeException unreadable) {
        return false;
      }
    };
  }
}
