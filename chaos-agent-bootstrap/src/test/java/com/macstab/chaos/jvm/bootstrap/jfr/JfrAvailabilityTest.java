package com.macstab.chaos.jvm.bootstrap.jfr;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.macstab.chaos.jvm.core.ChaosRuntime;
import org.junit.jupiter.api.Test;

class JfrAvailabilityTest {

  @Test
  void probeReturnsTrueOnCurrentJvm() {
    // JDK 25 always includes the jdk.jfr module; probe must return true
    assertTrue(JfrAvailability.probe(), "expected JFR to be available on JDK 25");
  }

  @Test
  void jfrIntegrationRecordsTheRuntimesLifecycle() throws Exception {
    // Closing the runtime closes the JFR sink it holds, which removes its periodic JFR hook.
    final java.util.List<jdk.jfr.consumer.RecordedEvent> events;
    try (ChaosRuntime runtime = new ChaosRuntime();
        jdk.jfr.Recording recording = new jdk.jfr.Recording()) {
      JfrIntegration.installIfAvailable(runtime);
      recording.enable("com.macstab.chaos.jvm.ScenarioLifecycle");
      recording.start();
      runtime
          .activate(
              com.macstab.chaos.jvm.api.ChaosScenario.builder("jfr-installed")
                  .selector(
                      com.macstab.chaos.jvm.api.ChaosSelector.executor(
                          java.util.Set.of(
                              com.macstab.chaos.jvm.api.OperationType.EXECUTOR_SUBMIT)))
                  .effect(
                      com.macstab.chaos.jvm.api.ChaosEffect.delay(java.time.Duration.ofMillis(1)))
                  .build())
          .close();
      recording.stop();
      final java.nio.file.Path dump = java.nio.file.Files.createTempFile("jfr-installed", ".jfr");
      try {
        recording.dump(dump);
        events = jdk.jfr.consumer.RecordingFile.readAllEvents(dump);
      } finally {
        java.nio.file.Files.deleteIfExists(dump);
      }
    }

    org.assertj.core.api.Assertions.assertThat(events)
        .filteredOn(event -> "jfr-installed".equals(event.getString("scenarioId")))
        .extracting(event -> event.getString("eventType"))
        .contains("STARTED", "STOPPED");
  }
}
