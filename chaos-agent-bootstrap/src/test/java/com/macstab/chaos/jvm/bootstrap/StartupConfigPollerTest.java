package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import com.macstab.chaos.jvm.startup.AgentSettings;
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
import org.junit.jupiter.api.parallel.ResourceLock;

@DisplayName("StartupConfigPoller")
@ResourceLock("System.err")
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
          StartupConfigPoller.createIfEnabled(AgentSettings.of(null, Map.of()), config, runtime);
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("returns empty when interval is zero")
    void emptyWhenIntervalIsZero() throws IOException {
      final Path config = tempDir.resolve("plan.json");
      Files.writeString(config, "{}");
      final Optional<StartupConfigPoller> result =
          StartupConfigPoller.createIfEnabled(
              AgentSettings.of("configWatchInterval=0", Map.of()), config, runtime);
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("returns poller when agent arg configures interval")
    void presentWhenAgentArgSetsInterval() throws IOException {
      final Path config = tempDir.resolve("plan.json");
      Files.writeString(config, "{}");
      final Optional<StartupConfigPoller> result =
          StartupConfigPoller.createIfEnabled(
              AgentSettings.of("configWatchInterval=500", Map.of()), config, runtime);
      assertThat(result).isPresent();
      result.get().close();
    }

    @Test
    @DisplayName("returns poller when env var configures interval")
    void presentWhenEnvVarSetsInterval() throws IOException {
      final Path config = tempDir.resolve("plan.json");
      Files.writeString(config, "{}");
      final Optional<StartupConfigPoller> result =
          StartupConfigPoller.createIfEnabled(
              AgentSettings.of(null, Map.of(StartupConfigPoller.ENV_WATCH_INTERVAL, "1000")),
              config,
              runtime);
      assertThat(result).isPresent();
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
      assertThat(StartupConfigPoller.resolveInterval(AgentSettings.of(null, Map.of())))
          .isEqualTo(0L);
    }

    @Test
    @DisplayName("returns 0 when env var is not a number")
    void zeroWhenEnvVarNonNumeric() {
      assertThat(
              StartupConfigPoller.resolveInterval(
                  AgentSettings.of(
                      null, Map.of(StartupConfigPoller.ENV_WATCH_INTERVAL, "notanumber"))))
          .isEqualTo(0L);
    }

    @Test
    @DisplayName("returns env var value when agent arg is absent")
    void envVarWhenArgAbsent() {
      assertThat(
              StartupConfigPoller.resolveInterval(
                  AgentSettings.of(null, Map.of(StartupConfigPoller.ENV_WATCH_INTERVAL, "750"))))
          .isEqualTo(750L);
    }

    @Test
    @DisplayName("an agent arg of 0 turns watching off over the env var")
    void zeroArgOverridesEnvVar() {
      assertThat(
              StartupConfigPoller.resolveInterval(
                  AgentSettings.of(
                      "configWatchInterval=0",
                      Map.of(StartupConfigPoller.ENV_WATCH_INTERVAL, "500"))))
          .isEqualTo(0L);
    }

    @Test
    @DisplayName("agent arg overrides env var")
    void argOverridesEnvVar() {
      assertThat(
              StartupConfigPoller.resolveInterval(
                  AgentSettings.of(
                      "configWatchInterval=300",
                      Map.of(StartupConfigPoller.ENV_WATCH_INTERVAL, "9999"))))
          .isEqualTo(300L);
    }

    @Test
    @DisplayName("the system property is read when the agent arg is absent")
    void systemProperty() {
      System.setProperty("macstab.chaos.configWatchInterval", "100");
      try {
        assertThat(StartupConfigPoller.resolveInterval(AgentSettings.of(null, Map.of())))
            .isEqualTo(100L);
      } finally {
        System.clearProperty("macstab.chaos.configWatchInterval");
      }
    }

    @Test
    @DisplayName("MACSTAB_CHAOS_CONFIG_WATCH_INTERVAL is read")
    void newEnvironmentName() {
      assertThat(
              StartupConfigPoller.resolveInterval(
                  AgentSettings.of(null, Map.of("MACSTAB_CHAOS_CONFIG_WATCH_INTERVAL", "100"))))
          .isEqualTo(100L);
    }

    @Test
    @DisplayName("an unparsable interval turns watching off with one line naming the value")
    void unparsableIsReported() {
      final java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
      final java.io.PrintStream original = System.err;
      final long interval;
      System.setErr(
          new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
      try {
        interval =
            StartupConfigPoller.resolveInterval(
                AgentSettings.of(null, Map.of(StartupConfigPoller.ENV_WATCH_INTERVAL, "abc")));
      } finally {
        System.setErr(original);
      }

      assertThat(interval).isZero();
      assertThat(captured.toString(java.nio.charset.StandardCharsets.UTF_8).lines())
          .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
          .startsWith("[chaos-agent]")
          .contains(StartupConfigPoller.ENV_WATCH_INTERVAL, "'abc'");
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
                  AgentSettings.of("configWatchInterval=60000", Map.of()), config, runtime)
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
    @DisplayName("a changed scenario that fails to activate restores the previous version")
    void failedChangeRestoresPreviousVersion() {
      final ChaosScenario v1 = scenarioWithMaxApplications("x", 1);
      final ChaosScenario v2 = scenarioWithMaxApplications("x", 2);
      final ChaosActivationHandle restored = mock(ChaosActivationHandle.class);
      when(runtime.activate(v1)).thenReturn(handle1, restored);
      when(runtime.activate(v2)).thenThrow(new IllegalArgumentException("invalid v2"));
      poller.applyDiff(List.of(v1));

      assertThatThrownBy(() -> poller.applyDiff(List.of(v2)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("previous scenarios kept");

      verify(runtime, times(2)).activate(v1);
    }

    @Test
    @DisplayName("a plan with one failing scenario activates none of its new scenarios")
    void failingPlanActivatesNothing() {
      when(runtime.activate(scenario("a"))).thenReturn(handle1);
      when(runtime.activate(scenario("b"))).thenThrow(new IllegalArgumentException("invalid b"));

      assertThatThrownBy(() -> poller.applyDiff(List.of(scenario("a"), scenario("b"))))
          .isInstanceOf(IllegalStateException.class);

      verify(handle1).stop();
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
                  AgentSettings.of("configWatchInterval=60000", Map.of()), config, runtime)
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
    @DisplayName("a touch of an applied plan reloads nothing and calls the runtime no more")
    void touchCallsNothing() throws Exception {
      Files.writeString(
          config,
          ChaosPlanMapper.write(
              new com.macstab.chaos.jvm.api.ChaosPlan(null, null, List.of(scenario("s1")))));
      Files.setLastModifiedTime(config, FileTime.from(Instant.now().minusSeconds(30)));
      when(runtime.activate(any(ChaosScenario.class))).thenReturn(handle1);
      poller.pollOnce();
      verify(runtime).activate(scenario("s1"));
      clearInvocations(runtime);

      // Same content, a newer modification time.
      Files.setLastModifiedTime(config, FileTime.from(Instant.now().minusSeconds(20)));
      final String stderr = StderrCapture.of(poller::pollOnce);

      assertThat(stderr).doesNotContain("config reloaded");
      verifyNoMoreInteractions(runtime);
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

    @Test
    @DisplayName("a file holding an array of scenarios is applied like a plan")
    void appliesAnArrayOfScenarios() throws IOException {
      Files.writeString(config, "[" + ChaosPlanMapper.toJson(scenario("s1")) + "]");
      Files.setLastModifiedTime(config, FileTime.from(Instant.now().plusSeconds(2)));

      when(runtime.activate(any(ChaosScenario.class))).thenReturn(handle1);
      poller.pollOnce();

      verify(runtime).activate(scenario("s1"));
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
                  AgentSettings.of("configWatchInterval=60000", Map.of()), config, runtime)
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
                  AgentSettings.of("configWatchInterval=60000", Map.of()), config, runtime)
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
