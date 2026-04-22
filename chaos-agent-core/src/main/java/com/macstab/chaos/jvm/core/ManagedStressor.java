package com.macstab.chaos.jvm.core;

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
 * <h2>Thread safety</h2>
 *
 * <p>Implementations must be thread-safe: {@link #close()} may be called from a different thread
 * than the one that constructed the stressor.
 */
interface ManagedStressor extends AutoCloseable {
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
}
