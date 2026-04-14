package io.macstab.chaos.bootstrap;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.macstab.chaos.api.ActivationPolicy;
import io.macstab.chaos.api.ChaosEffect;
import io.macstab.chaos.api.ChaosPlan;
import io.macstab.chaos.api.ChaosScenario;
import io.macstab.chaos.api.ChaosSelector;
import io.macstab.chaos.api.NamePattern;
import io.macstab.chaos.api.OperationType;
import io.macstab.chaos.startup.ChaosPlanMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StartupAgentIntegrationTest {
  @TempDir Path tempDir;

  @Test
  void startupAgentDelaysExecutorSubmit() throws Exception {
    ChaosPlan plan =
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

    String output = runProbe(plan, "executor");
    long elapsed =
        Long.parseLong(
            output.substring(output.indexOf("elapsedMillis=") + "elapsedMillis=".length()).trim());
    assertTrue(elapsed >= 50, "expected startup agent to delay executor submission");
  }

  @Test
  void startupAgentCanSuppressResourceLookup() throws Exception {
    ChaosPlan plan =
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

    String output = runProbe(plan, "resource");
    assertTrue(
        output.contains("resource=missing"), "expected startup agent to suppress resource lookup");
  }

  private String runProbe(ChaosPlan plan, String mode) throws Exception {
    Path configFile = tempDir.resolve(mode + "-plan.json");
    Files.writeString(configFile, ChaosPlanMapper.write(plan));
    String agentJar = System.getProperty("chaos.bootstrap.agentJar");
    Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-javaagent:" + agentJar + "=configFile=" + configFile,
                "-cp",
                System.getProperty("java.class.path"),
                AgentProcessProbeMain.class.getName(),
                mode)
            .redirectErrorStream(true)
            .start();
    boolean finished = process.waitFor(30, TimeUnit.SECONDS);
    assertTrue(finished, "probe JVM should finish");
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String output = reader.lines().reduce("", (left, right) -> left + right);
      assertTrue(process.exitValue() == 0, "probe JVM should exit successfully: " + output);
      return output;
    }
  }
}
