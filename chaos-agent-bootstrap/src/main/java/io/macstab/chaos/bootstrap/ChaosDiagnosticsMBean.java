package io.macstab.chaos.bootstrap;

import io.macstab.chaos.api.ChaosDiagnostics;

final class ChaosDiagnosticsMBean implements ChaosDiagnosticsMXBean {
  private final ChaosDiagnostics diagnostics;

  ChaosDiagnosticsMBean(ChaosDiagnostics diagnostics) {
    this.diagnostics = diagnostics;
  }

  @Override
  public String debugDump() {
    return diagnostics.debugDump();
  }
}
