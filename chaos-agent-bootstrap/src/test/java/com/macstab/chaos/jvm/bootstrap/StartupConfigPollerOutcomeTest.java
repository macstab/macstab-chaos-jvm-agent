package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import com.macstab.chaos.jvm.startup.AgentSettings;
import com.macstab.chaos.jvm.startup.ChaosPlanMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a reload tick reports and survives: the status file's reason, a retryable failure written
 * once, an Error in a tick, a reused id refused before anything stops, and the startup plan's
 * observability applied before its scenarios start.
 */
@DisplayName("Plan file reload outcomes")
class StartupConfigPollerOutcomeTest {

  @TempDir Path directory;
  private final ChaosRuntime runtime = new ChaosRuntime();
  private final List<ChaosEvent> events = new CopyOnWriteArrayList<>();
  private ChaosReports reports;
  private StartupConfigPoller poller;
  private Path file;

  @BeforeEach
  void start() {
    runtime.addEventListener(events::add);
    reports =
        ChaosReports.createIfEnabled(
                AgentSettings.of("reports=on;reportsDir=" + directory, Map.of()), runtime)
            .orElseThrow();
    file = directory.resolve("plan.json");
  }

  @AfterEach
  void stop() {
    if (poller != null) {
      poller.close();
    }
    reports.close();
    runtime.close();
  }

  @Test
  @DisplayName("a failure without a message reports its class as the reason, not null")
  void reasonWithoutMessage() throws IOException {
    startPoller(plan(scenario("first", 1, "")), 3_600_000L);
    poller.onRead(
        () -> {
          throw new IllegalStateException();
        });

    poller.pollOnce();

    assertThat(status()).contains("state=error").contains("\nreason=IllegalStateException\n");
  }

  @Test
  @DisplayName("a failure that recurs on every tick writes the status file once")
  void recurringFailureWrittenOnce() throws IOException {
    startPoller(plan(scenario("first", 1, "")), 3_600_000L);
    // A programmatic activation holds the id: a retryable conflict, tried again on every tick.
    runtime.activate(ChaosPlanMapper.read(plan(scenario("taken", 1, ""))));
    write(plan(scenario("taken", 2, "")), 30);
    poller.pollOnce();
    assertThat(status()).contains("state=error").contains("already active");
    final Path statusFile = directory.resolve(ChaosReports.STATUS_FILE);
    final FileTime old = FileTime.from(Instant.now().minusSeconds(3_600));
    Files.setLastModifiedTime(statusFile, old);

    poller.pollOnce();
    poller.pollOnce();

    assertThat(Files.getLastModifiedTime(statusFile)).isEqualTo(old);
  }

  @Test
  @DisplayName("an Error in one tick does not end the watching")
  void errorInTickKeepsWatching() throws Exception {
    final AtomicBoolean thrown = new AtomicBoolean();
    final CountDownLatch failedOnce = new CountDownLatch(1);
    write(plan(scenario("first", 1, "")), 60);
    poller = poller(50L);
    poller.onRead(
        () -> {
          if (thrown.compareAndSet(false, true)) {
            failedOnce.countDown();
            throw new StackOverflowError();
          }
        });
    poller.startWithInitialPlan(ChaosPlanMapper.read(plan(scenario("first", 1, ""))), null);
    assertThat(failedOnce.await(10, TimeUnit.SECONDS)).isTrue();

    write(plan(scenario("second", 1, "")), 30);

    assertThat(eventually(() -> runtime.diagnostics().scenario("second").isPresent()))
        .as("the edit after the Error is applied on the schedule")
        .isTrue();
  }

  @Test
  @DisplayName("a reused id held elsewhere is refused before any changed scenario stops")
  void reusedIdRefusedBeforeStopping() throws IOException {
    startPoller(
        plan(
            scenario("a", 1, ""),
            scenario("b", 1, ", \"activationPolicy\": {\"activeFor\": \"PT0.2S\"}")),
        3_600_000L);
    assertThat(
            eventually(
                () ->
                    runtime
                        .diagnostics()
                        .scenario("b")
                        .map(report -> report.state() == ChaosDiagnostics.ScenarioState.INACTIVE)
                        .orElse(false)))
        .isTrue();
    // The poller's b has ended; a programmatic activation takes its id over.
    runtime.activate(ChaosPlanMapper.read(plan(scenario("b", 3, ""))));
    write(plan(scenario("a", 2, ""), scenario("b", 2, "")), 30);
    events.clear();

    poller.pollOnce();
    poller.pollOnce();

    assertThat(status()).contains("state=error").contains("already active");
    assertThat(events)
        .as("the running a was neither stopped nor restarted")
        .noneMatch(event -> "a".equals(event.scenarioId()));
  }

  @Test
  @DisplayName("structuredLoggingEnabled=false mutes the startup plan's own lines")
  void startupObservabilityBeforeStart() throws IOException {
    final Logger logger = Logger.getLogger("com.macstab.chaos.jvm");
    final List<String> logged = new CopyOnWriteArrayList<>();
    final Handler capture =
        new Handler() {
          @Override
          public void publish(final LogRecord record) {
            logged.add(record.getMessage());
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    final Level previous = logger.getLevel();
    logger.setLevel(Level.ALL);
    logger.addHandler(capture);
    try {
      startPoller(
          """
          {"observability": {"structuredLoggingEnabled": false},
           "scenarios": [%s]}
          """
              .formatted(scenario("quiet", 1, "")),
          3_600_000L);
    } finally {
      logger.removeHandler(capture);
      logger.setLevel(previous);
    }

    assertThat(runtime.diagnostics().scenario("quiet")).isPresent();
    assertThat(logged).noneMatch(line -> line.contains("scenarioId=\"quiet\""));
  }

  private void startPoller(final String json, final long intervalMs) throws IOException {
    write(json, 60);
    poller = poller(intervalMs);
    poller.startWithInitialPlan(ChaosPlanMapper.read(json), null);
  }

  private StartupConfigPoller poller(final long intervalMs) {
    return StartupConfigPoller.createIfEnabled(
            AgentSettings.of("configWatchInterval=" + intervalMs, Map.of()), file, runtime)
        .orElseThrow();
  }

  private void write(final String json, final int secondsAgo) throws IOException {
    Files.writeString(file, json);
    Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(secondsAgo)));
  }

  private String status() throws IOException {
    return Files.readString(directory.resolve(ChaosReports.STATUS_FILE));
  }

  private static String plan(final String... scenarios) {
    return "{\"scenarios\": [" + String.join(",", scenarios) + "]}";
  }

  private static String scenario(final String id, final int delayMillis, final String extra) {
    return """
        {"id": "%s", "scope": "JVM",
         "selector": {"type": "executor", "operations": ["EXECUTOR_SUBMIT"]},
         "effect": {"type": "delay", "minDelay": "PT0.00%dS", "maxDelay": "PT0.00%dS"}%s}
        """
        .formatted(id, delayMillis, delayMillis, extra);
  }

  private static boolean eventually(final BooleanSupplier condition) {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return true;
      }
      try {
        Thread.sleep(20);
      } catch (final InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return condition.getAsBoolean();
  }
}
