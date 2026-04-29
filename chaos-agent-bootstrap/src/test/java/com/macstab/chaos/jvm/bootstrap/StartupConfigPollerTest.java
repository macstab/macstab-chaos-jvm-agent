package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import com.macstab.chaos.jvm.startup.ChaosPlanMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("StartupConfigPoller")
class StartupConfigPollerTest {

  @TempDir Path tempDir;

  private ChaosRuntime runtime;
  private ChaosActivationHandle handle1;
  private ChaosActivationHandle handle2;

  @BeforeEach
  void setUp() {
    runtime = mock(ChaosRuntime.class);
    handle1 = mock(ChaosActivationHandle.class);
    handle2 = mock(ChaosActivationHandle.class);
  }

  // ── factory / resolveInterval ──────────────────────────────────────────────

  @Nested
  @DisplayName("createIfEnabled")
  class CreateIfEnabled {

    @Test
    @DisplayName("returns empty when no interval is configured")
    void emptyWhenNoInterval() throws IOException {
      final Path config = tempDir.resolve("plan.json");
      Files.writeString(config, "{}");
      final Optional<StartupConfigPoller> result =
          StartupConfigPoller.createIfEnabled(null, Map.of(), config, runtime);
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("returns empty when interval is zero")
    void emptyWhenIntervalIsZero() throws IOException {
      final Path config = tempDir.resolve("plan.json");
      Files.writeString(config, "{}");
      final Optional<StartupConfigPoller> result =
          StartupConfigPoller.createIfEnabled("configWatchInterval=0", Map.of(), config, runtime);
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("returns poller when agent arg configures interval")
    void presentWhenAgentArgSetsInterval() throws IOException {
      final Path config = tempDir.resolve("plan.json");
      Files.writeString(config, "{}");
      final Optional<StartupConfigPoller> result =
          StartupConfigPoller.createIfEnabled("configWatchInterval=500", Map.of(), config, runtime);
      assertThat(result).isPresent();
      assertThat(result.get().intervalMs()).isEqualTo(500L);
      result.get().close();
    }

    @Test
    @DisplayName("returns poller when env var configures interval")
    void presentWhenEnvVarSetsInterval() throws IOException {
      final Path config = tempDir.resolve("plan.json");
      Files.writeString(config, "{}");
      final Optional<StartupConfigPoller> result =
          StartupConfigPoller.createIfEnabled(
              null, Map.of(StartupConfigPoller.ENV_WATCH_INTERVAL, "1000"), config, runtime);
      assertThat(result).isPresent();
      assertThat(result.get().intervalMs()).isEqualTo(1000L);
      result.get().close();
    }

    @Test
    @DisplayName("agent arg takes precedence over env var")
    void agentArgTakesPrecedenceOverEnvVar() throws IOException {
      final Path config = tempDir.resolve("plan.json");
      Files.writeString(config, "{}");
      final Optional<StartupConfigPoller> result =
          StartupConfigPoller.createIfEnabled(
              "configWatchInterval=200",
              Map.of(StartupConfigPoller.ENV_WATCH_INTERVAL, "9999"),
              config,
              runtime);
      assertThat(result).isPresent();
      assertThat(result.get().intervalMs()).isEqualTo(200L);
      result.get().close();
    }
  }

  // ── resolveInterval ────────────────────────────────────────────────────────

  @Nested
  @DisplayName("resolveInterval")
  class ResolveInterval {

    @Test
    @DisplayName("returns 0 when both sources are absent")
    void zeroWhenAbsent() {
      assertThat(StartupConfigPoller.resolveInterval(null, Map.of())).isEqualTo(0L);
    }

    @Test
    @DisplayName("returns 0 when env var is not a number")
    void zeroWhenEnvVarNonNumeric() {
      assertThat(
              StartupConfigPoller.resolveInterval(
                  null, Map.of(StartupConfigPoller.ENV_WATCH_INTERVAL, "notanumber")))
          .isEqualTo(0L);
    }

    @Test
    @DisplayName("returns env var value when agent arg is absent")
    void envVarWhenArgAbsent() {
      assertThat(
              StartupConfigPoller.resolveInterval(
                  null, Map.of(StartupConfigPoller.ENV_WATCH_INTERVAL, "750")))
          .isEqualTo(750L);
    }

    @Test
    @DisplayName("agent arg overrides env var")
    void argOverridesEnvVar() {
      assertThat(
              StartupConfigPoller.resolveInterval(
                  "configWatchInterval=300",
                  Map.of(StartupConfigPoller.ENV_WATCH_INTERVAL, "9999")))
          .isEqualTo(300L);
    }
  }

  // ── applyDiff ──────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("applyDiff")
  class ApplyDiff {

    private StartupConfigPoller poller;

    @BeforeEach
    void createPoller() throws IOException {
      final Path config = tempDir.resolve("plan.json");
      Files.writeString(config, "{}");
      poller =
          StartupConfigPoller.createIfEnabled(
                  "configWatchInterval=60000", Map.of(), config, runtime)
              .orElseThrow();
    }

    @Test
    @DisplayName("activates all scenarios on initial diff from empty state")
    void activatesInitialScenarios() {
      when(runtime.activate(scenario("a"))).thenReturn(handle1);
      when(runtime.activate(scenario("b"))).thenReturn(handle2);

      poller.applyDiff(List.of(scenario("a"), scenario("b")));

      verify(runtime).activate(scenario("a"));
      verify(runtime).activate(scenario("b"));
    }

    @Test
    @DisplayName("identical scenarios are kept — not stopped or re-activated")
    void identicalScenariosAreKept() {
      when(runtime.activate(scenario("a"))).thenReturn(handle1);
      poller.applyDiff(List.of(scenario("a")));

      // second diff with same scenario
      poller.applyDiff(List.of(scenario("a")));

      verify(runtime, times(1)).activate(any(ChaosScenario.class));
      verify(handle1, never()).stop();
    }

    @Test
    @DisplayName("removed scenario is stopped")
    void removedScenarioIsStopped() {
      when(runtime.activate(scenario("a"))).thenReturn(handle1);
      poller.applyDiff(List.of(scenario("a")));

      poller.applyDiff(List.of());

      verify(handle1).stop();
    }

    @Test
    @DisplayName("changed scenario is stopped then re-activated")
    void changedScenarioIsStoppedAndReactivated() {
      final ChaosScenario v1 = scenarioWithMaxApplications("x", 1);
      final ChaosScenario v2 = scenarioWithMaxApplications("x", 2);
      when(runtime.activate(v1)).thenReturn(handle1);
      when(runtime.activate(v2)).thenReturn(handle2);

      poller.applyDiff(List.of(v1));
      poller.applyDiff(List.of(v2));

      verify(handle1).stop();
      verify(runtime).activate(v1);
      verify(runtime).activate(v2);
    }

    @Test
    @DisplayName("new scenario is activated without touching existing ones")
    void newScenarioActivatedAlongsideExisting() {
      when(runtime.activate(scenario("a"))).thenReturn(handle1);
      when(runtime.activate(scenario("b"))).thenReturn(handle2);

      poller.applyDiff(List.of(scenario("a")));
      poller.applyDiff(List.of(scenario("a"), scenario("b")));

      verify(runtime, times(1)).activate(scenario("a"));
      verify(runtime, times(1)).activate(scenario("b"));
      verify(handle1, never()).stop();
    }
  }

  // ── pollOnce ──────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("pollOnce")
  class PollOnce {

    private Path config;
    private StartupConfigPoller poller;

    @BeforeEach
    void createPollerWithEmptyPlan() throws IOException {
      config = tempDir.resolve("plan.json");
      Files.writeString(config, "{\"scenarios\":[]}");
      poller =
          StartupConfigPoller.createIfEnabled(
                  "configWatchInterval=60000", Map.of(), config, runtime)
              .orElseThrow();
      // First pollOnce seeds lastModified; empty plan → no activate calls.
      poller.pollOnce();
    }

    @Test
    @DisplayName("does nothing when file has not been modified")
    void noOpWhenFileUnchanged() {
      poller.pollOnce(); // same mtime → no-op

      verify(runtime, never()).activate(any(ChaosScenario.class));
    }

    @Test
    @DisplayName("applies diff when file modification time changes")
    void applyDiffWhenFileChanges() throws IOException {
      final com.macstab.chaos.jvm.api.ChaosPlan updated =
          new com.macstab.chaos.jvm.api.ChaosPlan(null, null, List.of(scenario("s1")));
      Files.writeString(config, ChaosPlanMapper.write(updated));
      Files.setLastModifiedTime(config, FileTime.from(Instant.now().plusSeconds(2)));

      when(runtime.activate(any(ChaosScenario.class))).thenReturn(handle1);
      poller.pollOnce();

      verify(runtime).activate(any(ChaosScenario.class));
    }
  }

  // ── close ──────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("close")
  class Close {

    @Test
    @DisplayName("stops all managed handles on close")
    void stopsAllHandlesOnClose() throws IOException {
      final Path config = tempDir.resolve("plan.json");
      Files.writeString(config, "{}");

      final StartupConfigPoller poller =
          StartupConfigPoller.createIfEnabled(
                  "configWatchInterval=60000", Map.of(), config, runtime)
              .orElseThrow();

      when(runtime.activate(scenario("a"))).thenReturn(handle1);
      when(runtime.activate(scenario("b"))).thenReturn(handle2);

      poller.applyDiff(List.of(scenario("a"), scenario("b")));
      poller.close();

      verify(handle1).stop();
      verify(handle2).stop();
    }

    @Test
    @DisplayName("close is idempotent — no exception on double close")
    void doubleCloseDoesNotThrow() throws IOException {
      final Path config = tempDir.resolve("plan.json");
      Files.writeString(config, "{}");

      final StartupConfigPoller poller =
          StartupConfigPoller.createIfEnabled(
                  "configWatchInterval=60000", Map.of(), config, runtime)
              .orElseThrow();

      poller.close();
      poller.close();
    }
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static ChaosScenario scenario(final String id) {
    return ChaosScenario.builder(id)
        .selector(ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_READ)))
        .effect(ChaosEffect.delay(Duration.ofMillis(1)))
        .activationPolicy(ActivationPolicy.always())
        .build();
  }

  private static ChaosScenario scenarioWithMaxApplications(final String id, final long max) {
    return ChaosScenario.builder(id)
        .selector(ChaosSelector.fileIo(Set.of(OperationType.FILE_IO_READ)))
        .effect(ChaosEffect.delay(Duration.ofMillis(1)))
        .activationPolicy(
            new ActivationPolicy(
                ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 0L, max, null, null, null, false))
        .build();
  }
}
