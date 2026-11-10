package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ChaosDiagnostics;

/**
 * Probe main for a runtime without optional JDK modules ({@code --limit-modules}, a jlink image).
 * References no class of {@code java.sql}: it runs where that module is absent. Prints {@code
 * javaSql} (whether the module is present), the state of scenario {@code jdbc} and the failures the
 * diagnostics recorded, then {@code done=true}.
 */
public final class LimitedRuntimeProbeMain {

  private LimitedRuntimeProbeMain() {}

  public static void main(final String[] args) {
    System.out.println("javaSql=" + ModuleLayer.boot().findModule("java.sql").isPresent());
    final ChaosDiagnostics.Snapshot snapshot = ChaosPlatform.current().diagnostics().snapshot();
    System.out.println(
        "jdbc="
            + snapshot.scenarios().stream()
                .filter(report -> report.id().equals("jdbc"))
                .map(report -> report.state().name())
                .findFirst()
                .orElse("missing"));
    System.out.println(
        "failures="
            + snapshot.failures().stream()
                .map(failure -> failure.scenarioId() + ":" + failure.category())
                .toList());
    System.out.println("done=true");
  }
}
