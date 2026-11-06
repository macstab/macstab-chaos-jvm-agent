package com.macstab.chaos.jvm.bootstrap.jfr;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The JFR sink writes what a flight recording should show: one lifecycle event per scenario
 * transition, one event per applied effect with its operation and session, nothing for skipped or
 * failed evaluations, and a periodic snapshot of the active scenarios, whose id list is bounded.
 */
@DisplayName("JFR event sink")
class JfrChaosEventSinkTest {

  private static final String LIFECYCLE = "com.macstab.chaos.jvm.ScenarioLifecycle";
  private static final String APPLIED = "com.macstab.chaos.jvm.EffectApplied";
  private static final String SNAPSHOT = "com.macstab.chaos.jvm.StressorSnapshot";

  private final ChaosRuntime runtime = new ChaosRuntime();
  @TempDir Path directory;

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  @Test
  @DisplayName("lifecycle and applied events are recorded with their fields; others are not")
  void discreteEvents() throws IOException {
    final List<RecordedEvent> events;
    try (JfrChaosEventSink sink = new JfrChaosEventSink(runtime.diagnostics());
        Recording recording = new Recording()) {
      recording.enable(LIFECYCLE);
      recording.enable(APPLIED);
      recording.start();
      sink.onEvent(event(ChaosEvent.Type.STARTED, "s1", Map.of("scope", "jvm")));
      sink.onEvent(
          event(
              ChaosEvent.Type.APPLIED,
              "s1",
              Map.of(
                  "operation", "SOCKET_READ",
                  "effectType", "DelayEffect",
                  "scope", "session:abc",
                  "sessionId", "abc")));
      sink.onEvent(event(ChaosEvent.Type.SKIPPED, "s1", Map.of("reason", "probability")));
      sink.onEvent(
          event(ChaosEvent.Type.FAILED, "s1", Map.of("category", "INVALID_CONFIGURATION")));
      sink.onEvent(event(ChaosEvent.Type.STOPPED, "s1", Map.of()));
      recording.stop();
      events = read(recording);
    }

    assertThat(events.stream().filter(e -> e.getEventType().getName().equals(LIFECYCLE)))
        .extracting(e -> e.getString("eventType"), e -> e.getString("scopeKey"))
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("STARTED", "jvm"),
            org.assertj.core.groups.Tuple.tuple("STOPPED", ""));
    assertThat(events.stream().filter(e -> e.getEventType().getName().equals(APPLIED)))
        .singleElement()
        .satisfies(
            applied -> {
              assertThat(applied.getString("scenarioId")).isEqualTo("s1");
              assertThat(applied.getString("operationType")).isEqualTo("SOCKET_READ");
              assertThat(applied.getString("effectType")).isEqualTo("DelayEffect");
              assertThat(applied.getString("sessionId")).isEqualTo("abc");
            });
  }

  @Test
  @DisplayName("the periodic snapshot counts active scenarios and bounds their id list")
  void periodicSnapshot() throws Exception {
    for (int i = 0; i < 40; i++) {
      runtime.activate(
          ChaosScenario.builder("scenario-with-a-long-identifier-" + i)
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ofMillis(1)))
              .activationPolicy(ActivationPolicy.always())
              .build());
    }
    // Other runtimes' sinks in this JVM (from other tests) emit their own snapshots; this one's
    // is the snapshot of 40 active scenarios.
    try (JfrChaosEventSink sink = new JfrChaosEventSink(runtime.diagnostics())) {
      awaitSnapshot(
          snapshot ->
              snapshot.getInt("activeScenarioCount") == 40
                  && snapshot.getString("activeScenarioIds").length() == 1024);
    }
  }

  @Test
  @DisplayName("the periodic snapshot leaves stopped scenarios out of the active count and ids")
  void snapshotSkipsStoppedScenarios() throws Exception {
    runtime.activate(snapshotScenario("snapshot-running"));
    runtime.activate(snapshotScenario("snapshot-stopped")).stop();
    try (JfrChaosEventSink sink = new JfrChaosEventSink(runtime.diagnostics())) {
      awaitSnapshot(
          snapshot ->
              snapshot.getInt("activeScenarioCount") == 1
                  && "snapshot-running".equals(snapshot.getString("activeScenarioIds")));
    }
  }

  private static ChaosScenario snapshotScenario(final String id) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
        .effect(ChaosEffect.delay(Duration.ofMillis(1)))
        .activationPolicy(ActivationPolicy.always())
        .build();
  }

  @Test
  @DisplayName("without an enabled recording nothing is committed and nothing fails")
  void disabled() {
    try (JfrChaosEventSink sink = new JfrChaosEventSink(runtime.diagnostics())) {
      sink.onEvent(event(ChaosEvent.Type.STARTED, "s1", Map.of()));
      sink.onEvent(event(ChaosEvent.Type.APPLIED, "s1", Map.of()));
    }
  }

  /** Streams periodic snapshots until one that {@code wanted} accepts arrives. */
  private static void awaitSnapshot(final java.util.function.Predicate<RecordedEvent> wanted)
      throws InterruptedException {
    final java.util.concurrent.CountDownLatch seen = new java.util.concurrent.CountDownLatch(1);
    try (jdk.jfr.consumer.RecordingStream stream = new jdk.jfr.consumer.RecordingStream()) {
      stream.enable(SNAPSHOT).withPeriod(Duration.ofMillis(50));
      stream.onEvent(
          SNAPSHOT,
          event -> {
            if (wanted.test(event)) {
              seen.countDown();
            }
          });
      stream.startAsync();
      assertThat(seen.await(10, java.util.concurrent.TimeUnit.SECONDS))
          .as("a matching snapshot was recorded")
          .isTrue();
    }
  }

  private List<RecordedEvent> read(final Recording recording) throws IOException {
    final Path file = directory.resolve("chaos-" + System.nanoTime() + ".jfr");
    recording.dump(file);
    return RecordingFile.readAllEvents(file);
  }

  private static ChaosEvent event(
      final ChaosEvent.Type type, final String id, final Map<String, String> attributes) {
    return new ChaosEvent(Instant.now(), type, id, type + " " + id, attributes);
  }
}
