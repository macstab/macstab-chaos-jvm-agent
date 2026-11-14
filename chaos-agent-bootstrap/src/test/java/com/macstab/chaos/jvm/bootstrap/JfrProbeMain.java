package com.macstab.chaos.jvm.bootstrap;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jdk.jfr.Recording;

/**
 * Runs in a JVM started with {@code -javaagent:}: records the agent's JFR events (the sink the
 * agent installed at start) while a scenario delays three real {@code ThreadPoolExecutor}
 * submissions, long enough for the periodic snapshot, and prints where the recording is.
 */
public final class JfrProbeMain {

  static final String SCENARIO_ID = "jfr-submit-delay";
  static final int CALLS = 3;

  private JfrProbeMain() {}

  public static void main(final String[] args) throws Exception {
    final Path file = Files.createTempFile("chaos-agent-", ".jfr");
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    try (Recording recording = new Recording()) {
      recording.enable("com.macstab.chaos.jvm.ScenarioLifecycle");
      recording.enable("com.macstab.chaos.jvm.EffectApplied");
      recording.enable("com.macstab.chaos.jvm.StressorSnapshot").withPeriod(Duration.ofMillis(100));
      recording.start();
      try (ChaosActivationHandle handle =
          ChaosPlatform.current()
              .activate(
                  ChaosScenario.builder(SCENARIO_ID)
                      .scope(ChaosScenario.ScenarioScope.JVM)
                      .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                      .effect(ChaosEffect.delay(Duration.ofMillis(5)))
                      .activationPolicy(ActivationPolicy.always())
                      .build())) {
        for (int i = 0; i < CALLS; i++) {
          executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
        }
        System.out.println("handleApplied=" + handle.appliedCount());
        // Several snapshot periods while the scenario is active.
        Thread.sleep(500);
      }
      recording.stop();
      recording.dump(file);
    } finally {
      executor.shutdownNow();
    }
    System.out.println("recording=" + file);
  }
}
