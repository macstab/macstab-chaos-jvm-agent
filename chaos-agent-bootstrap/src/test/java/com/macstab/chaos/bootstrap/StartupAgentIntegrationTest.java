package com.macstab.chaos.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.api.ActivationPolicy;
import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.ChaosPlan;
import com.macstab.chaos.api.ChaosScenario;
import com.macstab.chaos.api.ChaosSelector;
import com.macstab.chaos.api.NamePattern;
import com.macstab.chaos.api.OperationType;
import com.macstab.chaos.startup.ChaosPlanMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("StartupAgent integration")
class StartupAgentIntegrationTest {

  private static final long DELAY_MS = 70L;
  private static final long DELAY_MIN_MS = (long) (DELAY_MS * 0.8);

  @TempDir Path tempDir;

  @Nested
  @DisplayName("executor submit delay")
  class ExecutorSubmitDelay {

    @Test
    @DisplayName("startup agent delays executor submit")
    void startupAgentDelaysExecutorSubmit() throws Exception {
      final ChaosPlan plan =
          new ChaosPlan(
              new ChaosPlan.Metadata("startup-executor", ""),
              null,
              List.of(
                  ChaosScenario.builder("startup-delay")
                      .scope(ChaosScenario.ScenarioScope.JVM)
                      .selector(
                          new ChaosSelector.ExecutorSelector(
                              Set.of(OperationType.EXECUTOR_SUBMIT),
                              NamePattern.exact("java.util.concurrent.ThreadPoolExecutor"),
                              NamePattern.any(),
                              null))
                      .effect(ChaosEffect.delay(Duration.ofMillis(70)))
                      .activationPolicy(ActivationPolicy.always())
                      .build()));

      final String output = runProbe(plan, "executor");
      final long elapsed =
          Long.parseLong(
              output
                  .substring(output.indexOf("elapsedMillis=") + "elapsedMillis=".length())
                  .trim());
      assertThat(elapsed)
          .as("delay effect should add at least 80%% of configured %dms", DELAY_MS)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }
  }

  @Nested
  @DisplayName("resource lookup suppression")
  class ResourceLookupSuppression {

    @Test
    @DisplayName("startup agent suppresses resource lookup")
    void startupAgentCanSuppressResourceLookup() throws Exception {
      final ChaosPlan plan =
          new ChaosPlan(
              new ChaosPlan.Metadata("startup-resource", ""),
              null,
              List.of(
                  ChaosScenario.builder("resource-suppress")
                      .scope(ChaosScenario.ScenarioScope.JVM)
                      .selector(
                          ChaosSelector.classLoading(
                              Set.of(OperationType.RESOURCE_LOAD),
                              NamePattern.exact("probe-resource.txt")))
                      .effect(ChaosEffect.suppress())
                      .activationPolicy(ActivationPolicy.always())
                      .build()));

      final String output = runProbe(plan, "resource");
      assertThat(output).contains("resource=missing");
    }
  }

  @Nested
  @DisplayName("thread start rejection")
  class ThreadStartRejection {

    @Test
    @DisplayName("startup agent rejects thread start matching selector")
    void startupAgentRejectsMatchingThreadStart() throws Exception {
      final ChaosPlan plan =
          new ChaosPlan(
              new ChaosPlan.Metadata("startup-thread", ""),
              null,
              List.of(
                  ChaosScenario.builder("thread-reject")
                      .scope(ChaosScenario.ScenarioScope.JVM)
                      .selector(
                          new ChaosSelector.ThreadSelector(
                              Set.of(OperationType.THREAD_START),
                              ChaosSelector.ThreadKind.ANY,
                              NamePattern.exact("chaos-probe-thread"),
                              null))
                      .effect(ChaosEffect.reject("chaos-thread-rejection"))
                      .activationPolicy(ActivationPolicy.always())
                      .build()));

      final String output = runProbe(plan, "thread");
      assertThat(output).contains("thread=rejected");
    }
  }

  @Nested
  @DisplayName("class load rejection")
  class ClassLoadRejection {

    @Test
    @DisplayName("startup agent rejects class load matching selector")
    void startupAgentRejectsMatchingClassLoad() throws Exception {
      final ChaosPlan plan =
          new ChaosPlan(
              new ChaosPlan.Metadata("startup-classload", ""),
              null,
              List.of(
                  ChaosScenario.builder("classload-reject")
                      .scope(ChaosScenario.ScenarioScope.JVM)
                      .selector(
                          ChaosSelector.classLoading(
                              Set.of(OperationType.CLASS_LOAD),
                              NamePattern.exact("org.assertj.core.api.Assertions")))
                      .effect(ChaosEffect.reject("chaos-classload-rejection"))
                      .activationPolicy(ActivationPolicy.always())
                      .build()));

      final String output = runProbe(plan, "classload");
      assertThat(output).contains("classload=rejected");
    }
  }

  @Nested
  @DisplayName("GC suppression")
  class GcSuppression {

    @Test
    @DisplayName("startup agent suppresses System.gc()")
    void startupAgentSuppressesSystemGc() throws Exception {
      final ChaosPlan plan =
          new ChaosPlan(
              new ChaosPlan.Metadata("startup-gc", ""),
              null,
              List.of(
                  ChaosScenario.builder("gc-suppress")
                      .scope(ChaosScenario.ScenarioScope.JVM)
                      .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_GC_REQUEST)))
                      .effect(ChaosEffect.suppress())
                      .activationPolicy(ActivationPolicy.always())
                      .build()));

      final String output = runProbe(plan, "gc");
      assertThat(output).contains("gc=returned");
    }
  }

  @Nested
  @DisplayName("Exit suppression")
  class ExitSuppression {

    @Test
    @DisplayName("startup agent suppresses System.exit() and throws SecurityException")
    void startupAgentSuppressesSystemExit() throws Exception {
      final ChaosPlan plan =
          new ChaosPlan(
              new ChaosPlan.Metadata("startup-exit", ""),
              null,
              List.of(
                  ChaosScenario.builder("exit-suppress")
                      .scope(ChaosScenario.ScenarioScope.JVM)
                      .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_EXIT_REQUEST)))
                      .effect(ChaosEffect.suppress())
                      .activationPolicy(ActivationPolicy.always())
                      .build()));

      final String output = runProbe(plan, "exit");
      assertThat(output).contains("exit=suppressed");
    }
  }

  private String runProbe(final ChaosPlan plan, final String mode) throws Exception {
    final Path configFile = tempDir.resolve(mode + "-plan.json");
    Files.writeString(configFile, ChaosPlanMapper.write(plan));
    final String agentJar = System.getProperty("chaos.bootstrap.agentJar");
    final Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-javaagent:" + agentJar + "=configFile=" + configFile,
                "-cp",
                System.getProperty("java.class.path"),
                AgentProcessProbeMain.class.getName(),
                mode)
            .redirectErrorStream(true)
            .start();
    final boolean finished = process.waitFor(30, TimeUnit.SECONDS);
    assertThat(finished).as("probe JVM should finish").isTrue();
    try (final BufferedReader reader =
        new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      final String output = reader.lines().reduce("", (left, right) -> left + right);
      assertThat(process.exitValue()).as("probe JVM exit value: " + output).isZero();
      return output;
    }
  }
}
