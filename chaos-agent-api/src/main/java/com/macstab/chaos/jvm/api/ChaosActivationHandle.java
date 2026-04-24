package com.macstab.chaos.jvm.api;

/**
 * Live control handle for an activated chaos scenario.
 *
 * <p>Returned by {@link ChaosControlPlane#activate(ChaosScenario)} and {@link
 * ChaosControlPlane#activate(ChaosPlan)}. Implements {@link AutoCloseable} for safe use in
 * try-with-resources blocks, which guarantees the scenario is stopped even if the test throws.
 *
 * <p>Lifecycle state machine:
 *
 * <pre>
 * REGISTERED
 *   │
 *   ├─ [AUTOMATIC] → ACTIVE ──┐
 *   │                         │
 *   └─ [MANUAL] → INACTIVE    │
 *        │                    │
 *        └── start() ─────────┤
 *                             │
 *                          stop() / close()
 *                             │
 *                          STOPPED
 * </pre>
 *
 * <p>Once {@code STOPPED}, the handle cannot be restarted. Create a new activation to re-inject the
 * same scenario.
 *
 * <p><b>Thread safety:</b> all methods are thread-safe and can be called from any thread.
 */
public interface ChaosActivationHandle extends AutoCloseable {

  /**
   * Returns the unique identifier of the activated scenario. Matches {@link ChaosScenario#id()}.
   * For plan activations this is the composite plan handle ID.
   */
  String id();

  /**
   * Transitions the scenario from {@link ChaosDiagnostics.ScenarioState#INACTIVE INACTIVE} to
   * {@link ChaosDiagnostics.ScenarioState#ACTIVE ACTIVE}. For scenarios with {@link
   * ActivationPolicy.StartMode#AUTOMATIC}, this is a no-op as they start immediately on activation.
   *
   * <p>For stressor effects (heap pressure, deadlock, etc.), this also spawns the stressor
   * background task.
   */
  void start();

  /**
   * Transitions the scenario to {@link ChaosDiagnostics.ScenarioState#STOPPED STOPPED}, halting all
   * effect applications. Cleans up stressor resources (background threads, retained memory, JFR
   * periodic hooks).
   *
   * <p>After {@code stop()}, no further effect applications will occur even if the selector still
   * matches operations.
   */
  void stop();

  /**
   * Opens a blocked {@link ChaosEffect.GateEffect} gate, releasing all threads currently blocked by
   * this scenario without stopping the scenario itself.
   *
   * <p>Calling {@code release()} on a scenario that does not use {@link ChaosEffect.GateEffect} is
   * a no-op. Calling it on a stopped scenario is also a no-op.
   *
   * <p>Useful in test code to unblock operations after assertions:
   *
   * <pre>{@code
   * handle.release(); // unblock the service call that was waiting on the gate
   * assertThat(response).isNotNull();
   * }</pre>
   */
  void release();

  /**
   * Returns the current lifecycle state of the scenario. The returned value is a snapshot; the
   * actual state may change concurrently.
   *
   * @see ChaosDiagnostics.ScenarioState
   */
  ChaosDiagnostics.ScenarioState state();

  /**
   * Delegates to {@link #stop()}. Enables use in try-with-resources.
   *
   * <pre>{@code
   * try (ChaosActivationHandle handle = controlPlane.activate(scenario)) {
   *     // chaos is active here
   * }
   * // handle is stopped; stressors and gates are cleaned up
   * }</pre>
   */
  @Override
  default void close() {
    stop();
  }
}
