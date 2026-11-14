package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The agent started with {@code -javaagent:} leaves {@code java.util.logging} to the application:
 * nothing in {@code premain} initialises the {@code LogManager}, so a {@code main} that sets {@code
 * java.util.logging.manager} gets its own, and the MBean and the agent's start-up lines follow once
 * the application has initialised it.
 */
@DisplayName("premain and java.util.logging")
class LogManagerIntegrationTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("a startup plan leaves the LogManager to main; the MBean and the held lines follow")
  void startupPlanLeavesTheLogManager() throws Exception {
    final ChildJvmProbe.ProbeResult result =
        run(
            "plain",
            ChaosScenario.builder("logging-delay")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                .activationPolicy(ActivationPolicy.always())
                .build());

    assertApplicationOwnsLogging(result);
    assertThat(result.output())
        .as("the held STARTED line is written through the application's LogManager")
        .contains("type=STARTED scenarioId=\"logging-delay\"");
  }

  @Test
  @DisplayName("a startup plan with stressors leaves the LogManager to main")
  void stressorPlanLeavesTheLogManager() throws Exception {
    final ChildJvmProbe.ProbeResult result =
        run(
            "stressors",
            ChaosScenario.builder("logging-gc")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.stress(ChaosSelector.StressTarget.GC_PRESSURE))
                .effect(ChaosEffect.gcPressure(1_000_000L, Duration.ofSeconds(30)))
                .build(),
            ChaosScenario.builder("logging-direct")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.stress(ChaosSelector.StressTarget.DIRECT_BUFFER))
                .effect(ChaosEffect.directBufferPressure(1_048_576L, 65_536))
                .build());

    assertApplicationOwnsLogging(result);
  }

  @Test
  @DisplayName("a startup plan with a method selector leaves the LogManager to main")
  void methodPlanLeavesTheLogManager() throws Exception {
    final ChildJvmProbe.ProbeResult result =
        run(
            "method",
            ChaosScenario.builder("logging-method")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(
                    ChaosSelector.method(
                        Set.of(OperationType.METHOD_ENTER),
                        NamePattern.exact("com.example.NotLoaded"),
                        NamePattern.exact("run")))
                .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                .activationPolicy(ActivationPolicy.always())
                .build());

    assertApplicationOwnsLogging(result);
  }

  @Test
  @DisplayName("a JVM that exits before JUL is initialised still prints the held lines")
  void exitBeforeJulWritesTheHeldLines() throws Exception {
    final ChildJvmProbe.ProbeResult result =
        run(
            "exit-at-once",
            ChaosScenario.builder("logging-exit")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                .activationPolicy(ActivationPolicy.always())
                .build());

    assertThat(result.value("exited")).isEqualTo("true");
    assertThat(result.output())
        .as("the held STARTED line is written at exit")
        .contains("INFO com.macstab.chaos.jvm: type=STARTED scenarioId=\"logging-exit\"");
  }

  @Test
  @DisplayName("a failed premain writes the lines it held before the JVM exits")
  void failedPremainWritesTheHeldLines() throws Exception {
    final ChaosPlan plan =
        new ChaosPlan(
            new ChaosPlan.Metadata("logging-refused", ""),
            null,
            List.of(
                ChaosScenario.builder("logging-refused")
                    .scope(ChaosScenario.ScenarioScope.JVM)
                    .selector(ChaosSelector.monitor(Set.of(OperationType.THREAD_PARK)))
                    .effect(ChaosEffect.reject("refused at activation"))
                    .activationPolicy(ActivationPolicy.always())
                    .build()));
    final Path configFile = tempDir.resolve("refused-plan.json");
    java.nio.file.Files.writeString(
        configFile, com.macstab.chaos.jvm.startup.ChaosPlanMapper.write(plan));
    final List<String> command = new java.util.ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.addAll(ChildJvmProbe.coverageAgent());
    command.add(
        "-javaagent:"
            + System.getProperty("chaos.bootstrap.agentJar")
            + "=configFile="
            + configFile);
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(LogManagerProbeMain.class.getName());

    final ChildJvmProbe.BoundedRun run =
        ChildJvmProbe.runBounded(command, tempDir.resolve("refused.log"), Duration.ofSeconds(60));

    assertThat(run.finished()).as("the JVM exits: %s", run.threads()).isTrue();
    assertThat(run.exitValue()).as(run.output()).isNotZero();
    assertThat(run.output())
        .as("the held FAILED line is written before the JVM exits")
        .contains("type=FAILED scenarioId=\"logging-refused\"")
        .doesNotContain("logManager=");
  }

  private ChildJvmProbe.ProbeResult run(final String mode, final ChaosScenario... scenarios)
      throws Exception {
    final ChaosPlan plan =
        new ChaosPlan(new ChaosPlan.Metadata("logging-" + mode, ""), null, List.of(scenarios));
    return new ChildJvmProbe(tempDir).run(plan, LogManagerProbeMain.class, mode, null);
  }

  private static void assertApplicationOwnsLogging(final ChildJvmProbe.ProbeResult result) {
    assertThat(result.value("logManager"))
        .as("the LogManager main chose")
        .isEqualTo(LogManagerProbeMain.ProbeLogManager.class.getName());
    assertThat(result.value("mbeanRegistered"))
        .as("the agent's MBean, afterwards")
        .isEqualTo("true");
  }
}
