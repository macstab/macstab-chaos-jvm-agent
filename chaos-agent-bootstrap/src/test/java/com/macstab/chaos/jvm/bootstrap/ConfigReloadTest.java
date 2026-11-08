package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import com.macstab.chaos.jvm.startup.AgentSettings;
import com.macstab.chaos.jvm.startup.ChaosPlanMapper;
import com.macstab.chaos.jvm.startup.ConfigLoadException;
import com.macstab.chaos.jvm.startup.StartupConfigLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reload robustness: change detection, broken and missing files, Kubernetes ConfigMap links. */
@DisplayName("Config reload")
class ConfigReloadTest {

  @TempDir Path tempDir;

  private final ChaosRuntime runtime = new ChaosRuntime();
  private final List<ChaosEvent> started = new CopyOnWriteArrayList<>();
  private StartupConfigPoller poller;

  @AfterEach
  void tearDown() {
    if (poller != null) {
      poller.close();
    }
    runtime.close();
  }

  private static String plan(final String id) {
    return """
        {"metadata":{"name":"p"},"scenarios":[{"id":"%s","scope":"JVM",
         "selector":{"type":"executor","operations":["EXECUTOR_SUBMIT"]},
         "effect":{"type":"delay","minDelay":"PT0.001S","maxDelay":"PT0.001S"}}]}
        """
        .formatted(id);
  }

  private void start(final Path configured) throws Exception {
    runtime.addEventListener(
        event -> {
          if (event.type() == ChaosEvent.Type.STARTED) {
            started.add(event);
          }
        });
    poller =
        StartupConfigPoller.createIfEnabled(
                AgentSettings.of("configWatchInterval=3600000", Map.of()), configured, runtime)
            .orElseThrow();
    poller.startWithInitialPlan(ChaosPlanMapper.read(Files.readString(configured)), null);
  }

  private ChaosDiagnostics.ScenarioState state(final String id) {
    return runtime
        .diagnostics()
        .scenario(id)
        .map(ChaosDiagnostics.ScenarioReport::state)
        .orElse(null);
  }

  private static void backdate(final Path file) throws Exception {
    Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - 60_000));
  }

  @Test
  @DisplayName("a touch without a content change does not reload")
  void touchDoesNotReload() throws Exception {
    final Path file = tempDir.resolve("plan.json");
    Files.writeString(file, plan("a"));
    backdate(file);
    start(file);

    Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - 30_000));
    poller.pollOnce();

    assertThat(started).hasSize(1);
  }

  @Test
  @DisplayName("a change under the same stamp is caught while the read is provisional")
  void provisionalRead() throws Exception {
    final Path file = tempDir.resolve("plan.json");
    Files.writeString(file, plan("a"));
    final FileTime fresh = FileTime.fromMillis(System.currentTimeMillis());
    Files.setLastModifiedTime(file, fresh);
    start(file);

    Files.writeString(file, plan("b"));
    Files.setLastModifiedTime(file, fresh);
    poller.pollOnce();

    assertThat(state("b")).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
  }

  @Test
  @DisplayName("broken contents keep the current plan and are parsed once")
  void brokenContentsKeepPlan() throws Exception {
    final Path file = tempDir.resolve("plan.json");
    Files.writeString(file, plan("a"));
    backdate(file);
    start(file);

    Files.writeString(file, "{ broken");
    poller.pollOnce();
    poller.pollOnce();

    assertThat(state("a")).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);

    Files.writeString(file, plan("c"));
    poller.pollOnce();
    assertThat(state("c")).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
  }

  @Test
  @DisplayName("removing the file stops its scenarios; bringing it back loads it again")
  void missingFile() throws Exception {
    final Path file = tempDir.resolve("plan.json");
    Files.writeString(file, plan("a"));
    backdate(file);
    start(file);

    Files.delete(file);
    poller.pollOnce();
    poller.pollOnce(); // missing on two ticks in a row
    assertThat(state("a")).isNotEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);

    Files.writeString(file, plan("a"));
    poller.pollOnce();
    assertThat(state("a")).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
  }

  @Test
  @DisplayName("a Kubernetes ConfigMap update (..data swap) is followed and reloaded")
  void configMapSwap() throws Exception {
    final Path volume = Files.createDirectories(tempDir.resolve("configmap"));
    final Path first = Files.createDirectories(volume.resolve("..2026_10_30_12_00_00.1"));
    Files.writeString(first.resolve("plan.json"), plan("first"));
    Files.createSymbolicLink(volume.resolve("..data"), Path.of(first.getFileName().toString()));
    Files.createSymbolicLink(volume.resolve("plan.json"), Path.of("..data", "plan.json"));
    start(volume.resolve("plan.json"));
    assertThat(state("first")).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);

    final Path second = Files.createDirectories(volume.resolve("..2026_10_30_12_05_00.2"));
    Files.writeString(second.resolve("plan.json"), plan("second"));
    final Path swap = volume.resolve("..data_tmp");
    Files.createSymbolicLink(swap, Path.of(second.getFileName().toString()));
    Files.move(
        swap,
        volume.resolve("..data"),
        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    poller.pollOnce();

    assertThat(state("second")).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
  }

  @Test
  @DisplayName("any other symbolic link is still rejected")
  void otherSymlinkRejected() throws Exception {
    final Path real = tempDir.resolve("real.json");
    Files.writeString(real, plan("a"));
    final Path link = tempDir.resolve("plan.json");
    Files.createSymbolicLink(link, real);

    assertThatThrownBy(() -> StartupConfigLoader.readPlanFile(link.toString()))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining("symbolic link");
  }

  private StartupConfigPoller poller(final Path configured) {
    return StartupConfigPoller.createIfEnabled(
            AgentSettings.of("configWatchInterval=3600000", Map.of()), configured, runtime)
        .orElseThrow();
  }

  @Test
  @DisplayName("a change between the startup read and the poller start is applied on the next tick")
  void changeDuringStartup() throws Exception {
    final Path file = tempDir.resolve("plan.json");
    Files.writeString(file, plan("startup"));
    backdate(file);
    final StartupConfigLoader.PlanFile read =
        StartupConfigLoader.readPlanFile(file.toString()).orElseThrow();
    Files.writeString(file, plan("changed"));
    Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - 30_000));
    poller = poller(file);
    poller.startWithInitialPlan(ChaosPlanMapper.read(read.json()), read);
    assertThat(state("startup")).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);

    poller.pollOnce();

    assertThat(state("changed")).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
    assertThat(state("startup")).isNull();
  }

  @Test
  @DisplayName("a conflict is retried with the same content once the conflicting scenario stopped")
  void transientConflictRetried() throws Exception {
    final Path file = tempDir.resolve("plan.json");
    Files.writeString(file, plan("a"));
    backdate(file);
    start(file);
    final com.macstab.chaos.jvm.api.ChaosActivationHandle elsewhere =
        runtime.activate(ChaosPlanMapper.read(plan("taken")).scenarios().get(0));
    final java.util.concurrent.atomic.AtomicInteger restarts =
        new java.util.concurrent.atomic.AtomicInteger();
    runtime.addEventListener(
        event -> {
          if (event.type() == com.macstab.chaos.jvm.api.ChaosEvent.Type.STARTED
              && "a".equals(event.scenarioId())) {
            restarts.incrementAndGet();
          }
        });

    Files.writeString(file, plan("taken"));
    poller.pollOnce();
    poller.pollOnce();
    assertThat(state("a"))
        .as("the old plan stays")
        .isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
    assertThat(restarts).as("and is not stopped and restarted on each tick").hasValue(0);

    elsewhere.close();
    poller.pollOnce();
    assertThat(state("taken")).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
  }

  @Test
  @DisplayName("a plan whose hooks cannot be woven leaves the running plan untouched on every tick")
  void weavingFailureKeepsPlan() throws Exception {
    final Path file = tempDir.resolve("plan.json");
    Files.writeString(file, plan("a"));
    backdate(file);
    start(file);
    final java.util.concurrent.atomic.AtomicInteger weaves =
        new java.util.concurrent.atomic.AtomicInteger();
    runtime.setHookDemand(
        operations -> {
          if (operations.contains(com.macstab.chaos.jvm.api.OperationType.QUEUE_PUT)) {
            weaves.incrementAndGet();
            throw new IllegalStateException("weaving failed");
          }
        });
    final java.util.concurrent.atomic.AtomicInteger restarts =
        new java.util.concurrent.atomic.AtomicInteger();
    runtime.addEventListener(
        event -> {
          if (event.type() == ChaosEvent.Type.STARTED && "a".equals(event.scenarioId())) {
            restarts.incrementAndGet();
          }
        });

    Files.writeString(
        file,
        """
        {"metadata":{"name":"p"},"scenarios":[{"id":"b","scope":"JVM",
         "selector":{"type":"queue","operations":["QUEUE_PUT"]},
         "effect":{"type":"delay","minDelay":"PT0.001S","maxDelay":"PT0.001S"}}]}
        """);
    poller.pollOnce();
    poller.pollOnce();

    assertThat(state("a"))
        .as("the old plan stays")
        .isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
    assertThat(restarts).as("and is not stopped and restarted on each tick").hasValue(0);
    assertThat(state("b")).isNull();
    assertThat(weaves).as("the same content is not woven again").hasValue(1);
  }

  @Test
  @DisplayName("a plan whose method selector cannot be woven is not retried on every tick")
  void methodWeavingFailureNotRetried() throws Exception {
    final Path file = tempDir.resolve("plan.json");
    Files.writeString(file, plan("a"));
    backdate(file);
    start(file);
    final java.util.concurrent.atomic.AtomicInteger weaves =
        new java.util.concurrent.atomic.AtomicInteger();
    runtime.setHookDemand(
        new com.macstab.chaos.jvm.core.HookDemand() {
          @Override
          public void require(final java.util.Set<com.macstab.chaos.jvm.api.OperationType> ops) {}

          @Override
          public void methods(
              final java.util.Set<com.macstab.chaos.jvm.api.ChaosSelector.MethodSelector> all) {
            if (!all.isEmpty()) {
              weaves.incrementAndGet();
              throw new IllegalStateException("weaving failed");
            }
          }
        });
    final java.util.concurrent.atomic.AtomicInteger restarts =
        new java.util.concurrent.atomic.AtomicInteger();
    runtime.addEventListener(
        event -> {
          if (event.type() == ChaosEvent.Type.STARTED && "a".equals(event.scenarioId())) {
            restarts.incrementAndGet();
          }
        });

    Files.writeString(
        file,
        """
        {"metadata":{"name":"p"},"scenarios":[{"id":"m","scope":"JVM",
         "selector":{"type":"method","operations":["METHOD_ENTER"],
          "classPattern":{"mode":"EXACT","value":"com.example.Service"},
          "methodNamePattern":{"mode":"ANY"}},
         "effect":{"type":"delay","minDelay":"PT0.001S","maxDelay":"PT0.001S"}}]}
        """);
    poller.pollOnce();
    poller.pollOnce();

    assertThat(state("a")).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
    assertThat(state("m")).isNull();
    assertThat(weaves).as("the same content is not woven again").hasValue(1);
    assertThat(restarts).as("restored once, not on every tick").hasValue(1);
  }

  @Test
  @DisplayName("a file missing for one tick keeps its scenarios; missing for two stops them")
  void missingGracePeriod() throws Exception {
    final Path file = tempDir.resolve("plan.json");
    Files.writeString(file, plan("a"));
    backdate(file);
    start(file);

    Files.delete(file);
    poller.pollOnce();
    assertThat(state("a")).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
    poller.pollOnce();
    assertThat(state("a")).isNull();
  }

  @Test
  @DisplayName("a plan file owned by another user is refused")
  void foreignOwnerRefused() throws Exception {
    final Path file = tempDir.resolve("plan.json");
    Files.writeString(file, plan("a"));
    final String self = System.getProperty("user.name");
    System.setProperty("user.name", "chaos-test-someone-else");
    try {
      assertThatThrownBy(() -> StartupConfigLoader.readPlanFile(file.toString()))
          .isInstanceOf(ConfigLoadException.class)
          .hasMessageContaining("is owned by");
    } finally {
      System.setProperty("user.name", self);
    }
  }
}
