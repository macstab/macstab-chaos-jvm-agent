package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosActivationHandle;
import com.macstab.chaos.api.ChaosDiagnostics;

/**
 * Default package-private implementation of {@link com.macstab.chaos.api.ChaosActivationHandle}.
 *
 * <p>Each instance corresponds to a single registered chaos scenario. It wraps a {@link
 * ScenarioController} and provides the public lifecycle API:
 *
 * <ul>
 *   <li>{@link #start()} — transitions the controller to ACTIVE.
 *   <li>{@link #stop()} — transitions the controller to STOPPED.
 *   <li>{@link #release()} — releases any held {@link ManualGate}.
 *   <li>{@link #destroy()} — permanently unregisters the scenario from the registry and stops the
 *       controller. After {@code destroy()}, the scenario can no longer participate in chaos
 *       decisions.
 * </ul>
 *
 * <h2>Thread safety</h2>
 *
 * <p>All methods delegate to thread-safe internals. Instances may be used from multiple threads.
 */
final class DefaultChaosActivationHandle implements ChaosActivationHandle {
  private final ScenarioController controller;
  private final ScenarioRegistry registry;

  /**
   * Creates a handle that wraps the given controller and registry.
   *
   * @param controller the scenario controller that manages the scenario's lifecycle state machine
   * @param registry the registry from which the scenario will be removed on {@link #destroy()}
   */
  DefaultChaosActivationHandle(
      final ScenarioController controller, final ScenarioRegistry registry) {
    this.controller = controller;
    this.registry = registry;
  }

  /**
   * Returns the unique key that identifies the underlying scenario.
   *
   * @return the scenario key as reported by {@link ScenarioController#key()}
   */
  @Override
  public String id() {
    return controller.key();
  }

  /** Transitions the underlying scenario to the ACTIVE state, enabling chaos injection. */
  @Override
  public void start() {
    controller.start();
  }

  /** Transitions the underlying scenario to the STOPPED state, suspending chaos injection. */
  @Override
  public void stop() {
    controller.stop();
  }

  /**
   * Releases any {@code ManualGate} held by the underlying scenario, allowing a blocked intercepted
   * call to proceed.
   */
  @Override
  public void release() {
    controller.release();
  }

  /**
   * Returns the current lifecycle state of the underlying scenario.
   *
   * @return the scenario state as reported by {@link ScenarioController#state()}
   */
  @Override
  public ChaosDiagnostics.ScenarioState state() {
    return controller.state();
  }

  /**
   * Permanently stops the scenario and removes it from the {@link ScenarioRegistry}.
   *
   * <p>Calling this method ensures that the scenario no longer appears in registry lookups and
   * cannot participate in future chaos decisions. The registry removal is safe to call concurrently
   * because {@link java.util.concurrent.ConcurrentHashMap#remove} is thread-safe.
   */
  void destroy() {
    controller.destroy();
    registry.unregister(controller);
  }
}
