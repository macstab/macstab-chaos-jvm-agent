package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Probe main for a runtime without optional JDK modules ({@code --limit-modules}, a jlink image).
 * References no class of {@code java.sql}: it runs where that module is absent. Prints {@code
 * javaSql} (whether the module is present), the state of scenario {@code jdbc}, the failures the
 * diagnostics recorded and the hook failures, then {@code done=true}. Initialises {@code
 * java.util.logging} first, which is when the agent registers its MBean, and prints {@code
 * mbeanSkipped} with the agent's line when that registration was skipped. In mode {@code
 * minimal-code-cache} it also activates a code-cache stressor and prints {@code codeCache}.
 */
public final class LimitedRuntimeProbeMain {

  private LimitedRuntimeProbeMain() {}

  public static void main(final String[] args) throws InterruptedException {
    final PrintStream stderr = System.err;
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    Logger.getLogger(LimitedRuntimeProbeMain.class.getName()).info("logging started");
    String skipped = "";
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      skipped =
          captured
              .toString(StandardCharsets.UTF_8)
              .lines()
              .filter(line -> line.contains("MBean registration skipped"))
              .findFirst()
              .orElse("");
      if (!skipped.isEmpty() || ModuleLayer.boot().findModule("java.management").isPresent()) {
        break;
      }
      Thread.sleep(20);
    }
    System.setErr(stderr);
    stderr.print(captured.toString(StandardCharsets.UTF_8));
    System.out.println("mbeanSkipped=" + skipped);
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
    System.out.println(
        "hookFailures=" + snapshot.runtimeDetails().getOrDefault("hookFailures", ""));
    if (args.length > 0 && "minimal-code-cache".equals(args[0])) {
      System.out.println("codeCache=" + activateCodeCachePressure());
    }
    System.out.println("done=true");
  }

  /** Activates and stops a small code-cache stressor: {@code activated}, or what it threw. */
  private static String activateCodeCachePressure() {
    try (ChaosActivationHandle handle =
        ChaosPlatform.current()
            .activate(
                ChaosScenario.builder("code-cache")
                    .selector(ChaosSelector.stress(ChaosSelector.StressTarget.CODE_CACHE_PRESSURE))
                    .effect(ChaosEffect.codeCachePressure(2, 2))
                    .activationPolicy(ActivationPolicy.always())
                    .build())) {
      return "activated:" + handle.state();
    } catch (final RuntimeException | Error thrown) {
      return thrown.getClass().getName() + ": " + thrown.getMessage();
    }
  }
}
