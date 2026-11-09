package com.macstab.chaos.jvm.core;

import java.util.function.Consumer;

/**
 * Lifecycle contract for long-running background stressors.
 *
 * <p>A {@code ManagedStressor} represents a background workload — such as continuous heap
 * allocation ({@link HeapPressureStressor}) or a keep-alive heartbeat thread ({@link
 * KeepAliveStressor}) — that is started when a chaos scenario becomes active and must be cleaned up
 * when the scenario stops.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>Stressors are constructed and started as part of {@link ScenarioController#start()}. The
 * {@link #close()} method is called by {@link ScenarioController#stop()} or {@link
 * ScenarioController#destroy()}, which is guaranteed to call it exactly once. Implementations must
 * be idempotent on {@code close()} (calling it multiple times is safe).
 *
 * <p>A stressor that stops producing load on its own reports it through the end callback it is
 * built with ({@link StressorFactory#createIfNeeded}): a thread that failed ({@code "stressor
 * failed: <type>"}), a {@code duration} or a {@code lifespan} that elapsed. The scenario then ends
 * {@code INACTIVE} with that reason (L17). The callback only hands the end over, so a stressor
 * thread may call it.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Implementations must be thread-safe: {@link #close()} may be called from a different thread
 * than the one that constructed the stressor.
 */
interface ManagedStressor extends AutoCloseable {
  /** The end callback of a stressor built outside a scenario (in a test): reports to no one. */
  Consumer<String> NO_END = reason -> {};

  /**
   * Returns the failure report of a stressor thread: it ends the scenario with the reason {@code
   * "stressor failed: <type>"}.
   *
   * @param onEnd the scenario's end callback
   * @param type the stressor's name, such as {@code ReferenceQueueFlood}
   * @return the report to hand to {@link AgentThreads}
   */
  static Runnable failure(final Consumer<String> onEnd, final String type) {
    return () -> onEnd.accept("stressor failed: " + type);
  }

  /**
   * Shuts down all background activity started by this stressor and releases retained resources.
   *
   * <p>Implementations must interrupt any background threads they own and wait for them to
   * terminate (within a reasonable timeout) before returning. After {@code close()} returns, the
   * stressor must not hold strong references to application objects.
   *
   * <p>This method is idempotent: calling it multiple times must be safe.
   */
  @Override
  void close();

  /**
   * Returns a duration in nanoseconds, saturated at {@link Long#MAX_VALUE}: a duration too large
   * for nanoseconds (about 292 years) lasts until the stressor is stopped. Compare it with the time
   * elapsed since a start ({@code System.nanoTime() - start < nanos}), never with a deadline sum,
   * which overflows.
   *
   * @param duration a non-negative duration
   * @return its nanoseconds, or {@link Long#MAX_VALUE}
   */
  static long nanos(final java.time.Duration duration) {
    try {
      return duration.toNanos();
    } catch (final ArithmeticException tooLarge) {
      return Long.MAX_VALUE;
    }
  }
}
