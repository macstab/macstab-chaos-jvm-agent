package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import com.macstab.chaos.jvm.startup.AgentSettings;
import com.macstab.chaos.jvm.startup.ChaosPlanMapper;
import com.macstab.chaos.jvm.startup.StartupConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reloads against a real runtime and real files: a plan with a duplicate id is refused and the
 * running one kept, a touch without a change reloads nothing, a file gone for two ticks stops its
 * scenarios once, and nothing is applied after close. The poller is driven tick by tick; its own
 * schedule is set far beyond the test.
 */
@DisplayName("Plan file reloads")
class StartupConfigPollerReloadTest {

  private static final String PLAN =
      """
      {"scenarios": [{"id": "%s", "scope": "JVM",
        "selector": {"type": "executor", "operations": ["EXECUTOR_SUBMIT"]},
        "effect": {"type": "delay", "minDelay": "PT0.001S", "maxDelay": "PT0.001S"}}]}
      """;

  @TempDir Path directory;
  private final ChaosRuntime runtime = new ChaosRuntime();
  private final List<ChaosEvent> registered = new CopyOnWriteArrayList<>();
  private Path file;
  private StartupConfigPoller poller;

  @BeforeEach
  void start() throws IOException {
    runtime.addEventListener(
        event -> {
          if (event.type() == ChaosEvent.Type.REGISTERED) {
            registered.add(event);
          }
        });
    file = Files.writeString(directory.resolve("plan.json"), PLAN.formatted("first"));
    // Old enough that the first read is not provisional (a file being written right now).
    Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(60)));
    poller =
        StartupConfigPoller.createIfEnabled(
                AgentSettings.of("configWatchInterval=3600000", Map.of()), file, runtime)
            .orElseThrow();
    final StartupConfigLoader.PlanFile baseline =
        StartupConfigLoader.readPlanFile(file.toString()).orElseThrow();
    poller.startWithInitialPlan(ChaosPlanMapper.read(baseline.json()), baseline);
  }

  @AfterEach
  void stop() {
    poller.close();
    runtime.close();
  }

  @Test
  @DisplayName("a plan with a duplicate id is refused; the running plan stays")
  void duplicateIdsAreRefused() throws IOException {
    Files.writeString(
        file,
        """
        {"scenarios": [
          {"id": "twice", "selector": {"type": "executor", "operations": ["EXECUTOR_SUBMIT"]},
           "effect": {"type": "suppress"}},
          {"id": "twice", "selector": {"type": "executor", "operations": ["EXECUTOR_SUBMIT"]},
           "effect": {"type": "suppress"}}]}
        """);
    Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(30)));
    poller.pollOnce();

    assertThat(runtime.diagnostics().scenario("first")).isPresent();
    assertThat(runtime.diagnostics().scenario("twice")).isEmpty();
  }

  @Test
  @DisplayName("a touch without a change in content reloads nothing")
  void touchIsNotAReload() throws IOException {
    final int before = registered.size();
    Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(20)));
    poller.pollOnce();
    assertThat(registered).hasSize(before);

    Files.writeString(file, PLAN.formatted("second"));
    Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(10)));
    poller.pollOnce();
    assertThat(runtime.diagnostics().scenario("second")).isPresent();
    assertThat(runtime.diagnostics().scenario("first")).isEmpty();
  }

  @Test
  @DisplayName("a file gone for two ticks stops its scenarios, and is reported once")
  void missingFileStopsScenarios() throws IOException {
    Files.delete(file);
    poller.pollOnce();
    assertThat(runtime.diagnostics().scenario("first"))
        .as("one tick may be an editor's rename; the plan stays")
        .isPresent();
    poller.pollOnce();
    assertThat(runtime.diagnostics().scenario("first")).isEmpty();
    poller.pollOnce();
    assertThat(runtime.diagnostics().scenario("first")).isEmpty();
  }

  @Test
  @DisplayName("after close nothing more is applied")
  void closedPollerAppliesNothing() throws IOException {
    poller.close();
    Files.writeString(file, PLAN.formatted("after-close"));
    Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(10)));
    poller.pollOnce();
    assertThat(runtime.diagnostics().scenario("after-close")).isEmpty();
  }

  @Test
  @DisplayName("a blank interval is no interval; a tiny one is raised to the minimum")
  void intervalBounds() {
    assertThat(
            StartupConfigPoller.resolveInterval(
                AgentSettings.of(null, Map.of("MACSTAB_CHAOS_WATCH_INTERVAL", " "))))
        .isZero();
    assertThat(
            StartupConfigPoller.resolveInterval(
                AgentSettings.of("configWatchInterval=10", Map.of())))
        .as("raised to the minimum")
        .isEqualTo(StartupConfigPoller.MIN_WATCH_INTERVAL_MS);
  }
}
