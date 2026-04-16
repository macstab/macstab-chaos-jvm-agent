package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosActivationHandle;
import com.macstab.chaos.api.ChaosDiagnostics;

final class DefaultChaosActivationHandle implements ChaosActivationHandle {
  private final ScenarioController controller;
  private final ScenarioRegistry registry;

  DefaultChaosActivationHandle(
      final ScenarioController controller, final ScenarioRegistry registry) {
    this.controller = controller;
    this.registry = registry;
  }

  @Override
  public String id() {
    return controller.key();
  }

  @Override
  public void start() {
    controller.start();
  }

  @Override
  public void stop() {
    controller.stop();
  }

  @Override
  public void release() {
    controller.release();
  }

  @Override
  public ChaosDiagnostics.ScenarioState state() {
    return controller.state();
  }

  /**
   * Stops the controller and removes it from the registry.
   *
   * <p>Task 4: Previously only {@link ScenarioController#destroy()} was called, which stopped the
   * scenario but left its entry in {@link ScenarioRegistry}. Over many test cycles this caused the
   * registry to grow unbounded. {@link ScenarioRegistry#unregister(ScenarioController)} is safe to
   * call concurrently because {@link java.util.concurrent.ConcurrentHashMap#remove} is thread-safe.
   */
  void destroy() {
    controller.destroy();
    registry.unregister(controller);
  }
}
