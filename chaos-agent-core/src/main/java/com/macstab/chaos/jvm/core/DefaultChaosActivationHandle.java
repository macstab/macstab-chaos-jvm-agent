package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.Internal;

/**
 * Default implementation of {@link com.macstab.chaos.jvm.api.ChaosActivationHandle}.
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
 * <p>This class is public rather than package-private so that {@code StartupConfigPoller} (in the
 * {@code bootstrap} package) can call {@link #destroy()} — an operation not exposed on the public
 * {@link ChaosActivationHandle} API. Constructors remain package-private so only core code can
 * instantiate handles.
 *
 * <h2>Thread safety</h2>
 *
 * <p>All methods delegate to thread-safe internals. Instances may be used from multiple threads.
 */
@Internal
public final class DefaultChaosActivationHandle implements ChaosActivationHandle {
  private final ScenarioController controller;
  private final ScenarioRegistry registry;
  private final java.util.function.Consumer<Throwable> startFailure;
  private final java.util.concurrent.atomic.AtomicBoolean destroyed =
      new java.util.concurrent.atomic.AtomicBoolean(false);

  /**
   * Creates a handle that wraps the given controller and registry.
   *
   * @param controller the scenario controller that manages the scenario's lifecycle state machine
   * @param registry the registry from which the scenario will be removed on {@link #destroy()}
   * @param startFailure records a failure of {@link #start()}
   */
  DefaultChaosActivationHandle(
      final ScenarioController controller,
      final ScenarioRegistry registry,
      final java.util.function.Consumer<Throwable> startFailure) {
    this.controller = controller;
    this.registry = registry;
    this.startFailure = startFailure;
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

  /**
   * Transitions the underlying scenario to the ACTIVE state, enabling chaos injection. A failure is
   * recorded as an activation failure and rethrown; the scenario stays {@code REGISTERED} and the
   * handle stays the caller's to close.
   */
  @Override
  public void start() {
    try {
      controller.start();
    } catch (final RuntimeException | Error failure) {
      startFailure.accept(failure);
      throw failure;
    }
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

  @Override
  public long matchedCount() {
    return controller.matchedCount();
  }

  @Override
  public long appliedCount() {
    return controller.appliedCount();
  }

  /**
   * Stops the scenario and removes it from the registry, so its id can be activated again and it no
   * longer appears in diagnostics. Equivalent to {@link #destroy()}.
   */
  @Override
  public void close() {
    destroy();
  }

  /**
   * Whether {@link #destroy()} has run; a session drops such a handle.
   *
   * @return {@code true} once destroyed
   */
  boolean destroyed() {
    return destroyed.get();
  }

  /**
   * Permanently stops the scenario and removes it from the {@link ScenarioRegistry}.
   *
   * <p>Calling this method ensures that the scenario no longer appears in registry lookups and
   * cannot participate in future chaos decisions. The registry removal is safe to call concurrently
   * because {@link java.util.concurrent.ConcurrentHashMap#remove} is thread-safe.
   *
   * <p>Public rather than package-private so that {@code StartupConfigPoller} (in the {@code
   * bootstrap} package) can fully unregister scenarios during config-diff application — calling
   * {@code stop()} alone would leak entries into the registry indefinitely across reloads.
   */
  public void destroy() {
    if (!destroyed.compareAndSet(false, true)) {
      return;
    }
    try {
      controller.destroy();
    } finally {
      // Unregistered even when the stop fails: a retry does nothing, so the id would stay taken.
      registry.unregister(controller);
    }
  }
}
