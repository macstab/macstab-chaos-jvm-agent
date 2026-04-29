package com.macstab.chaos.jvm.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("StartupConfigLoader")
class StartupConfigLoaderTest {

  @TempDir Path tempDir;

  @Nested
  @DisplayName("source resolution")
  class SourceResolution {

    @Test
    @DisplayName("returns empty when no config source is provided")
    void noConfigReturnsEmpty() {
      Optional<StartupConfigLoader.LoadedPlan> result = StartupConfigLoader.load(null, Map.of());
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("inline JSON from agent args is loaded")
    void inlineJsonFromArgs() {
      String json = writeSampleJson("inline-test");
      StartupConfigLoader.LoadedPlan loaded =
          StartupConfigLoader.load("configJson=" + json, Map.of()).orElseThrow();

      assertThat(loaded.plan().metadata().name()).isEqualTo("inline-test");
      assertThat(loaded.source()).isEqualTo("inline-json");
    }

    @Test
    @DisplayName("inline JSON from environment is loaded")
    void inlineJsonFromEnv() {
      String json = writeSampleJson("env-test");
      StartupConfigLoader.LoadedPlan loaded =
          StartupConfigLoader.load(null, Map.of(StartupConfigLoader.ENV_CONFIG_JSON, json))
              .orElseThrow();

      assertThat(loaded.plan().metadata().name()).isEqualTo("env-test");
    }

    @Test
    @DisplayName("base64-encoded JSON from agent args is loaded")
    void base64FromArgs() {
      String json = writeSampleJson("b64-test");
      String encoded = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
      StartupConfigLoader.LoadedPlan loaded =
          StartupConfigLoader.load("configBase64=" + encoded, Map.of()).orElseThrow();

      assertThat(loaded.plan().metadata().name()).isEqualTo("b64-test");
      assertThat(loaded.source()).isEqualTo("base64");
    }

    @Test
    @DisplayName("file path from agent args is loaded")
    void fileFromArgs() throws Exception {
      Path configFile = tempDir.resolve("plan.json");
      Files.writeString(configFile, writeSampleJson("file-test"));

      StartupConfigLoader.LoadedPlan loaded =
          StartupConfigLoader.load("configFile=" + configFile, Map.of()).orElseThrow();

      assertThat(loaded.plan().metadata().name()).isEqualTo("file-test");
      assertThat(loaded.source()).startsWith("file:");
    }

    @Test
    @DisplayName("agent args take precedence over environment variables")
    void argsPrecedence() throws Exception {
      Path envFile = tempDir.resolve("env-plan.json");
      Files.writeString(envFile, writeSampleJson("env-plan"));

      String argsJson = writeSampleJson("args-plan");

      StartupConfigLoader.LoadedPlan loaded =
          StartupConfigLoader.load(
                  "configJson=" + argsJson,
                  Map.of(StartupConfigLoader.ENV_CONFIG_FILE, envFile.toString()))
              .orElseThrow();

      assertThat(loaded.plan().metadata().name()).isEqualTo("args-plan");
    }

    @Test
    @DisplayName("inline JSON takes precedence over base64 and file")
    void inlineBeatsBase64AndFile() throws Exception {
      String inlineJson = writeSampleJson("inline-wins");
      String base64 =
          Base64.getEncoder()
              .encodeToString(writeSampleJson("b64-loses").getBytes(StandardCharsets.UTF_8));
      Path file = tempDir.resolve("file-loses.json");
      Files.writeString(file, writeSampleJson("file-loses"));

      StartupConfigLoader.LoadedPlan loaded =
          StartupConfigLoader.load(
                  "configJson=" + inlineJson + ";configBase64=" + base64,
                  Map.of(StartupConfigLoader.ENV_CONFIG_FILE, file.toString()))
              .orElseThrow();

      assertThat(loaded.plan().metadata().name()).isEqualTo("inline-wins");
    }
  }

  @Nested
  @DisplayName("debug dump flag")
  class DebugDump {

    @Test
    @DisplayName("debugDumpOnStart from agent args")
    void debugFromArgs() {
      String json = writeSampleJson("debug-test");
      StartupConfigLoader.LoadedPlan loaded =
          StartupConfigLoader.load("configJson=" + json + ";debugDumpOnStart=true", Map.of())
              .orElseThrow();
      assertThat(loaded.debugDumpOnStart()).isTrue();
    }

    @Test
    @DisplayName("debugDumpOnStart from environment")
    void debugFromEnv() {
      String json = writeSampleJson("debug-env");
      StartupConfigLoader.LoadedPlan loaded =
          StartupConfigLoader.load(
                  "configJson=" + json, Map.of(StartupConfigLoader.ENV_DEBUG_DUMP, "true"))
              .orElseThrow();
      assertThat(loaded.debugDumpOnStart()).isTrue();
    }

    @Test
    @DisplayName("debug dump defaults to false")
    void debugDefaultFalse() {
      String json = writeSampleJson("no-debug");
      StartupConfigLoader.LoadedPlan loaded =
          StartupConfigLoader.load("configJson=" + json, Map.of()).orElseThrow();
      assertThat(loaded.debugDumpOnStart()).isFalse();
    }
  }

  @Nested
  @DisplayName("error paths")
  class ErrorPaths {

    @Test
    @DisplayName("non-existent file throws ConfigLoadException")
    void missingFile() {
      assertThatThrownBy(
              () -> StartupConfigLoader.load("configFile=/does/not/exist.json", Map.of()))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("does not exist");
    }

    @Test
    @DisplayName("invalid base64 throws ConfigLoadException")
    void invalidBase64() {
      assertThatThrownBy(() -> StartupConfigLoader.load("configBase64=!!!not-base64!!!", Map.of()))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("invalid base64");
    }

    @Test
    @DisplayName("invalid JSON in file throws ConfigLoadException")
    void invalidJsonInFile() throws Exception {
      Path bad = tempDir.resolve("bad.json");
      Files.writeString(bad, "{not valid}");

      assertThatThrownBy(() -> StartupConfigLoader.load("configFile=" + bad, Map.of()))
          .isInstanceOf(ConfigLoadException.class);
    }

    @Test
    @DisplayName("directory path is rejected")
    void directoryPathRejected() {
      assertThatThrownBy(() -> StartupConfigLoader.load("configFile=" + tempDir, Map.of()))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("not a regular file");
    }
  }

  @Nested
  @DisplayName("file path security")
  class FilePathSecurity {

    @Test
    @DisplayName("traversal path normalized to non-existent file throws does-not-exist")
    void traversalPathNormalizedToNonExistent() {
      // normalize() resolves '..' before any check; the resolved path is validated normally.
      // This path normalizes to a non-existent absolute path, triggering the existence guard.
      assertThatThrownBy(
              () ->
                  StartupConfigLoader.load(
                      "configFile=/no/such/path/../../../../../no/such/plan.json", Map.of()))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("does not exist");
    }

    @Test
    @DisplayName("symlink is rejected")
    void symlinkRejected() throws Exception {
      Path real = tempDir.resolve("real.json");
      Files.writeString(real, writeSampleJson("real"));
      Path link = tempDir.resolve("link.json");
      Files.createSymbolicLink(link, real);

      assertThatThrownBy(() -> StartupConfigLoader.load("configFile=" + link, Map.of()))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("symbolic link");
    }

    @Test
    @DisplayName("oversized file is rejected")
    void oversizedFileRejected() throws Exception {
      Path huge = tempDir.resolve("huge.json");
      byte[] data = new byte[1_048_577];
      java.util.Arrays.fill(data, (byte) ' ');
      Files.write(huge, data);

      assertThatThrownBy(() -> StartupConfigLoader.load("configFile=" + huge, Map.of()))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("exceeds maximum size");
    }
  }

  private static String writeSampleJson(String planName) {
    ChaosPlan plan =
        new ChaosPlan(
            new ChaosPlan.Metadata(planName, ""),
            null,
            List.of(
                ChaosScenario.builder("s1")
                    .scope(ChaosScenario.ScenarioScope.JVM)
                    .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                    .effect(ChaosEffect.delay(Duration.ofMillis(10)))
                    .activationPolicy(ActivationPolicy.always())
                    .build()));
    return ChaosPlanMapper.write(plan);
  }
}
