package io.macstab.chaos.core;

import io.macstab.chaos.api.ChaosActivationHandle;
import io.macstab.chaos.api.ChaosDiagnostics;

final class DefaultChaosActivationHandle implements ChaosActivationHandle {
  private final ScenarioController controller;

  DefaultChaosActivationHandle(ScenarioController controller) {
    this.controller = controller;
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

  void destroy() {
    controller.destroy();
  }
}
