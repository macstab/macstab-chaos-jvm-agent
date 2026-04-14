package io.macstab.chaos.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.macstab.chaos.api.ActivationPolicy;
import io.macstab.chaos.api.ChaosEffect;
import io.macstab.chaos.api.ChaosPlan;
import io.macstab.chaos.api.ChaosScenario;
import io.macstab.chaos.api.ChaosSelector;
import io.macstab.chaos.api.OperationType;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StartupConfigLoaderTest {
  @TempDir java.nio.file.Path tempDir;

  @Test
  void agentArgsOverrideEnvironment() throws Exception {
    ChaosPlan filePlan =
        new ChaosPlan(
            new ChaosPlan.Metadata("file-plan", ""),
            null,
            java.util.List.of(executorDelayScenario("file-scenario", 5)));
    java.nio.file.Path configFile = tempDir.resolve("plan.json");
    Files.writeString(configFile, ChaosPlanMapper.write(filePlan));

    String inline =
        ChaosPlanMapper.write(
            new ChaosPlan(
                new ChaosPlan.Metadata("inline-plan", ""),
                null,
                java.util.List.of(executorDelayScenario("inline-scenario", 10))));

    StartupConfigLoader.LoadedPlan loadedPlan =
        StartupConfigLoader.load(
                "configJson=" + inline + ";debugDumpOnStart=true",
                Map.of(StartupConfigLoader.ENV_CONFIG_FILE, configFile.toString()))
            .orElseThrow();

    assertEquals("inline-plan", loadedPlan.plan().metadata().name());
    assertTrue(loadedPlan.debugDumpOnStart());
  }

  private static ChaosScenario executorDelayScenario(String id, long delayMillis) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
        .effect(ChaosEffect.delay(Duration.ofMillis(delayMillis)))
        .activationPolicy(ActivationPolicy.always())
        .build();
  }
}
