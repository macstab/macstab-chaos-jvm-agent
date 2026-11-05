package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import com.macstab.chaos.jvm.instrumentation.JdkInstrumentationInstaller;
import java.lang.instrument.Instrumentation;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Probe main that drives {@link JdkInstrumentationInstaller} directly in a self-attached JVM,
 * without the bootstrap's own installation, and prints {@code key=value} lines.
 */
public final class InstallerProbeMain {

  /** Held so the configured logger is not collected before the installer looks it up. */
  private static final Logger AGENT_LOGGER = Logger.getLogger("com.macstab.chaos.jvm");

  private InstallerProbeMain() {}

  public static void main(final String[] args) throws Exception {
    switch (args[0]) {
      case "lifecycle" -> lifecycle();
      default -> throw new IllegalArgumentException("unknown probe mode: " + args[0]);
    }
  }

  /**
   * An uninstall before any install; an install whose bridge cannot be written (the temp directory
   * does not exist) and its retry; a second install of the same copy for another runtime; an
   * uninstall that removes the woven hooks, and a second uninstall.
   */
  private static void lifecycle() throws Exception {
    final Instrumentation instrumentation = net.bytebuddy.agent.ByteBuddyAgent.install();
    final List<String> fineMessages = recordFineMessages();

    System.out.println(
        "uninstallBeforeInstall=" + JdkInstrumentationInstaller.uninstall(instrumentation));

    final String tmpDir = System.getProperty("java.io.tmpdir");
    final java.nio.file.Path missing =
        java.nio.file.Path.of(tmpDir, "missing-" + System.nanoTime());
    System.setProperty("java.io.tmpdir", missing.toString());
    try {
      JdkInstrumentationInstaller.install(instrumentation, new ChaosRuntime(), false);
      System.out.println("injectFailure=none");
    } catch (final IllegalStateException failed) {
      System.out.println("injectFailure=" + failed.getMessage());
    } finally {
      System.setProperty("java.io.tmpdir", tmpDir);
    }

    final ChaosRuntime first = new ChaosRuntime();
    JdkInstrumentationInstaller.install(instrumentation, first, false);
    final ChaosRuntime second = new ChaosRuntime();
    JdkInstrumentationInstaller.install(instrumentation, second, false, List.of());
    first.activate(sleepDelay("first-sleep"));
    second.activate(sleepDelay("second-sleep"));
    System.out.println("installedSleepMillis=" + sleepMillis());
    System.out.println("appliedFirst=" + applied(first, "first-sleep"));
    System.out.println("appliedSecond=" + applied(second, "second-sleep"));

    System.out.println("uninstalled=" + JdkInstrumentationInstaller.uninstall(instrumentation));
    System.out.println("uninstalledSleepMillis=" + sleepMillis());
    System.out.println(
        "uninstalledAgain=" + JdkInstrumentationInstaller.uninstall(instrumentation));

    System.out.println(
        "sweepSkippedLogged="
            + fineMessages.stream().anyMatch(m -> m.startsWith("bridge jar sweep skipped")));
    System.out.println(
        "wovenLogged=" + fineMessages.stream().anyMatch(m -> m.startsWith("chaos hooks woven")));
  }

  private static ChaosScenario sleepDelay(final String id) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(
            ChaosSelector.thread(Set.of(OperationType.THREAD_SLEEP), ChaosSelector.ThreadKind.ANY))
        .effect(ChaosEffect.delay(Duration.ofMillis(300L)))
        .activationPolicy(ActivationPolicy.always())
        .build();
  }

  private static long applied(final ChaosRuntime runtime, final String id) {
    return runtime.diagnostics().snapshot().scenarios().stream()
        .filter(report -> report.id().equals(id))
        .mapToLong(report -> report.appliedCount())
        .sum();
  }

  private static long sleepMillis() throws InterruptedException {
    final long start = System.nanoTime();
    Thread.sleep(1L);
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }

  /** Records the agent's FINE log messages. */
  private static List<String> recordFineMessages() {
    final List<String> messages = new CopyOnWriteArrayList<>();
    AGENT_LOGGER.setLevel(Level.FINE);
    AGENT_LOGGER.addHandler(
        new Handler() {
          @Override
          public void publish(final LogRecord logRecord) {
            messages.add(String.valueOf(logRecord.getMessage()));
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        });
    return messages;
  }
}
