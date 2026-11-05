package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import com.macstab.chaos.jvm.startup.ChaosPlanMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reload under load: a writer swaps a Kubernetes-style ConfigMap volume, rewrites the plan in place
 * byte by byte, and publishes syntactically and semantically invalid plans, while the poller runs
 * at its fastest interval and a sampler watches the active scenarios.
 *
 * <p>Laws checked: no scenario of an invalid plan is ever active, an invalid plan never interrupts
 * the plan before it, and once the writes stop the active set is exactly the last valid plan.
 */
@DisplayName("Config reload under concurrent writes")
class ConfigReloadTortureTest {

  private static final Duration RUN = Duration.ofSeconds(3);
  private static final long POLL_MILLIS = 50L;

  @TempDir Path tempDir;

  private final ChaosRuntime runtime = new ChaosRuntime();
  private StartupConfigPoller poller;
  private ChaosReports reports;

  @AfterEach
  void tearDown() {
    if (poller != null) {
      poller.close();
    }
    if (reports != null) {
      reports.close();
    }
    runtime.close();
  }

  @Test
  @DisplayName("invalid and torn plans are never applied, valid ones always are")
  void torture() throws Exception {
    reports =
        ChaosReports.createIfEnabled("reportsDir=" + tempDir, Map.of(), runtime).orElseThrow();
    final Path volume = Files.createDirectories(tempDir.resolve("configmap"));
    int generation = 0;
    publish(volume, generation, validPlan("g0"));
    Files.createSymbolicLink(volume.resolve("plan.json"), Path.of("..data", "plan.json"));
    poller =
        StartupConfigPoller.createIfEnabled(
                "configWatchInterval=" + POLL_MILLIS,
                Map.of(),
                volume.resolve("plan.json"),
                runtime)
            .orElseThrow();
    poller.startWithInitialPlan(ChaosPlanMapper.read(validPlan("g0")), null);

    final List<String> violations = new CopyOnWriteArrayList<>();
    final AtomicBoolean writing = new AtomicBoolean(true);
    final Thread sampler =
        new Thread(
            () -> {
              while (writing.get()) {
                for (final String id : activeIds()) {
                  if (id.startsWith("bad-") || id.startsWith("half-")) {
                    violations.add("invalid plan's scenario active: " + id);
                  }
                }
                Thread.onSpinWait();
              }
            },
            "reload-sampler");
    sampler.start();

    String lastValid = "g0";
    final long deadline = System.nanoTime() + RUN.toNanos();
    int round = 0;
    try {
      while (System.nanoTime() < deadline) {
        round++;
        generation++;
        switch (round % 4) {
          case 0 -> {
            // A syntax error after a well-formed scenario: nothing of it may apply.
            final String broken = validPlan("bad-" + round);
            publish(volume, generation, broken.substring(0, broken.length() - 3));
            assertStillActive(lastValid, violations);
          }
          case 1 -> {
            // Well-formed JSON whose second scenario fails validation: the first may not apply.
            publish(volume, generation, halfInvalidPlan("half-" + round));
            assertStillActive(lastValid, violations);
          }
          case 2 -> {
            // An editor rewriting the target in place, in chunks: torn reads must not apply.
            lastValid = "g" + round;
            rewriteInPlace(volume, validPlan(lastValid));
            requireApplied(lastValid, violations);
          }
          default -> {
            lastValid = "g" + round;
            publish(volume, generation, validPlan(lastValid));
            requireApplied(lastValid, violations);
          }
        }
      }
      // End on a known valid plan and let the poller catch up.
      generation++;
      lastValid = "final";
      publish(volume, generation, validPlan(lastValid));
      awaitActive(lastValid);
    } finally {
      writing.set(false);
      sampler.join();
    }

    assertThat(violations).isEmpty();
    assertThat(activeIds()).containsExactlyInAnyOrderElementsOf(ids(lastValid));
    final String status = Files.readString(tempDir.resolve(ChaosReports.STATUS_FILE));
    assertThat(status).contains("state=ok").contains("scenarios=3");
  }

  @Test
  @DisplayName("a broken plan reports its line and column in the status file")
  void statusReportsPosition() throws Exception {
    reports =
        ChaosReports.createIfEnabled("reportsDir=" + tempDir, Map.of(), runtime).orElseThrow();
    final Path file = tempDir.resolve("plan.json");
    Files.writeString(file, validPlan("ok"));
    Files.setLastModifiedTime(
        file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 60_000));
    poller =
        StartupConfigPoller.createIfEnabled("configWatchInterval=3600000", Map.of(), file, runtime)
            .orElseThrow();
    poller.startWithInitialPlan(ChaosPlanMapper.read(validPlan("ok")), null);

    Files.writeString(file, "{\n  \"scenarios\": [\n    {\"id\": oops}\n  ]\n}\n");
    poller.pollOnce();

    final String status = Files.readString(tempDir.resolve(ChaosReports.STATUS_FILE));
    assertThat(status).contains("state=error").contains("line=3").contains("column=");
    assertThat(activeIds()).containsExactlyInAnyOrderElementsOf(ids("ok"));
  }

  private void assertStillActive(final String group, final List<String> violations)
      throws InterruptedException {
    Thread.sleep(3 * POLL_MILLIS);
    final Set<String> active = activeIds();
    if (!active.containsAll(ids(group))) {
      violations.add("previous plan " + group + " interrupted; active=" + active);
    }
  }

  private void requireApplied(final String group, final List<String> violations)
      throws InterruptedException {
    if (!awaitActive(group)) {
      violations.add("valid plan " + group + " not applied; active=" + activeIds());
    }
  }

  private boolean awaitActive(final String group) throws InterruptedException {
    final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!activeIds().equals(ids(group)) && System.nanoTime() < deadline) {
      Thread.sleep(5L);
    }
    return activeIds().equals(ids(group));
  }

  private Set<String> activeIds() {
    return runtime.diagnostics().snapshot().scenarios().stream()
        .filter(report -> report.state() == ChaosDiagnostics.ScenarioState.ACTIVE)
        .map(ChaosDiagnostics.ScenarioReport::id)
        .collect(Collectors.toSet());
  }

  private static Set<String> ids(final String group) {
    return Set.of(group + "-a", group + "-b", group + "-c");
  }

  /** Publishes a new ConfigMap generation and swaps {@code ..data} to it atomically. */
  private static void publish(final Path volume, final int generation, final String json)
      throws Exception {
    final Path dir = Files.createDirectories(volume.resolve("..gen_" + generation));
    Files.writeString(dir.resolve("plan.json"), json);
    final Path swap = volume.resolve("..data_tmp");
    Files.deleteIfExists(swap);
    Files.createSymbolicLink(swap, Path.of(dir.getFileName().toString()));
    Files.move(
        swap,
        volume.resolve("..data"),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING);
  }

  /** Rewrites the current target file in place, in three chunks with pauses in between. */
  private static void rewriteInPlace(final Path volume, final String json) throws Exception {
    final Path target = volume.resolve("..data").toRealPath().resolve("plan.json");
    final byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    try (java.io.OutputStream out = Files.newOutputStream(target)) {
      final int third = bytes.length / 3;
      out.write(bytes, 0, third);
      out.flush();
      Thread.sleep(POLL_MILLIS / 2);
      out.write(bytes, third, third);
      out.flush();
      Thread.sleep(POLL_MILLIS / 2);
      out.write(bytes, 2 * third, bytes.length - 2 * third);
    }
  }

  private static String scenario(final String id, final String effect) {
    return """
        {"id": "%s", "scope": "JVM",
         "selector": {"type": "executor", "operations": ["EXECUTOR_SUBMIT"]},
         "effect": %s}"""
        .formatted(id, effect);
  }

  private static String validPlan(final String group) {
    final String delay =
        "{\"type\": \"delay\", \"minDelay\": \"PT0.001S\", \"maxDelay\": \"PT0.001S\"}";
    return "{\"metadata\": {\"name\": \""
        + group
        + "\"}, \"scenarios\": ["
        + scenario(group + "-a", delay)
        + ", "
        + scenario(group + "-b", delay)
        + ", "
        + scenario(group + "-c", delay)
        + "]}";
  }

  private static String halfInvalidPlan(final String group) {
    final String delay =
        "{\"type\": \"delay\", \"minDelay\": \"PT0.001S\", \"maxDelay\": \"PT0.001S\"}";
    // tornWrite applies to writes only: activating it on executor submissions fails validation.
    return "{\"metadata\": {\"name\": \""
        + group
        + "\"}, \"scenarios\": ["
        + scenario(group + "-ok", delay)
        + ", "
        + scenario(group + "-bad", "{\"type\": \"tornWrite\"}")
        + "]}";
  }
}
