package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The agent's own JFR sink in an agent JVM, fed by calls the agent wove: a flight recording of a
 * scenario that delays real executor submissions holds its STARTED lifecycle event, one
 * EffectApplied per submission with the delay, and periodic snapshots counting it as active.
 */
@DisplayName("JFR events from woven calls")
class JfrFromWovenCallsIntegrationTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("a recording holds the lifecycle, one EffectApplied per call and the snapshot")
  void recordsWovenCalls() throws Exception {
    final ChildJvmProbe.ProbeResult result =
        new ChildJvmProbe(tempDir).runWithoutPlan(JfrProbeMain.class, "jfr");
    final Path recording = Path.of(result.value("recording"));
    final List<RecordedEvent> events;
    try {
      events = RecordingFile.readAllEvents(recording);
    } finally {
      Files.deleteIfExists(recording);
    }

    assertThat(result.longValue("handleApplied")).isEqualTo(JfrProbeMain.CALLS);
    assertThat(named(events, "com.macstab.chaos.jvm.ScenarioLifecycle"))
        .anySatisfy(
            event -> {
              assertThat(event.getString("scenarioId")).isEqualTo(JfrProbeMain.SCENARIO_ID);
              assertThat(event.getString("eventType")).isEqualTo("STARTED");
            });
    assertThat(
            named(events, "com.macstab.chaos.jvm.EffectApplied").stream()
                .filter(e -> JfrProbeMain.SCENARIO_ID.equals(e.getString("scenarioId")))
                .toList())
        .hasSize(JfrProbeMain.CALLS)
        .allSatisfy(
            event -> {
              assertThat(event.getString("operationType")).isEqualTo("EXECUTOR_SUBMIT");
              assertThat(event.getLong("delayNanos")).isGreaterThanOrEqualTo(5_000_000L);
            });
    assertThat(named(events, "com.macstab.chaos.jvm.StressorSnapshot"))
        .anySatisfy(
            event -> {
              assertThat(event.getInt("activeScenarioCount")).isPositive();
              assertThat(event.getString("activeScenarioIds")).contains(JfrProbeMain.SCENARIO_ID);
            });
  }

  private static List<RecordedEvent> named(final List<RecordedEvent> events, final String name) {
    return events.stream().filter(e -> e.getEventType().getName().equals(name)).toList();
  }
}
