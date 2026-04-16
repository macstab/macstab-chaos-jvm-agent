package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosActivationHandle;
import com.macstab.chaos.api.ChaosDiagnostics;
import java.util.List;

final class CompositeActivationHandle implements ChaosActivationHandle {
  private final String id;
  private final List<ChaosActivationHandle> delegates;

  CompositeActivationHandle(final String id, final List<ChaosActivationHandle> delegates) {
    this.id = id;
    this.delegates = List.copyOf(delegates);
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public void start() {
    delegates.forEach(ChaosActivationHandle::start);
  }

  @Override
  public void stop() {
    delegates.forEach(ChaosActivationHandle::stop);
  }

  @Override
  public void release() {
    delegates.forEach(ChaosActivationHandle::release);
  }

  @Override
  public ChaosDiagnostics.ScenarioState state() {
    return delegates.stream()
            .anyMatch(handle -> handle.state() == ChaosDiagnostics.ScenarioState.ACTIVE)
        ? ChaosDiagnostics.ScenarioState.ACTIVE
        : ChaosDiagnostics.ScenarioState.INACTIVE;
  }
}
