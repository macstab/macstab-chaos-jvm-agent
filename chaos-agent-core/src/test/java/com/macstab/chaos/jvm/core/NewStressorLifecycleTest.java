package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.macstab.chaos.jvm.api.ChaosEffect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the lifecycle of four stressors: CodeCachePressureStressor,
 * SafepointStormStressor, StringInternPressureStressor, and ReferenceQueueFloodStressor.
 */
@DisplayName("New stressor lifecycle")
class NewStressorLifecycleTest {

  @Nested
  @DisplayName("CodeCachePressureStressor")
  class CodeCachePressureTests {

    @Test
    @DisplayName("loads expected number of classes")
    void loadsExpectedNumberOfClasses() {
      final CodeCachePressureStressor stressor =
          new CodeCachePressureStressor(new ChaosEffect.CodeCachePressureEffect(5, 2));
      assertThat(stressor.retainedClassCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("close() releases retained class references")
    void closeReleasesRetainedClasses() {
      final CodeCachePressureStressor stressor =
          new CodeCachePressureStressor(new ChaosEffect.CodeCachePressureEffect(3, 1));
      stressor.close();
      assertThat(stressor.retainedClassCount()).isZero();
    }
  }

  @Nested
  @DisplayName("SafepointStormStressor")
  class SafepointStormTests {

    @Test
    @DisplayName("GC cycle count increments after start")
    void gcCycleCountIncrementsAfterStart() throws Exception {
      final SafepointStormStressor stressor =
          new SafepointStormStressor(
              new ChaosEffect.SafepointStormEffect(Duration.ofMillis(50), 0), Optional.empty());
      try {
        await()
            .atMost(3, TimeUnit.SECONDS)
            .untilAsserted(() -> assertThat(stressor.gcCycleCount()).isGreaterThan(0));
      } finally {
        stressor.close();
      }
    }

    @Test
    @DisplayName("close() stops the storm thread")
    void closeStopsStormThread() throws Exception {
      final SafepointStormStressor stressor =
          new SafepointStormStressor(
              new ChaosEffect.SafepointStormEffect(Duration.ofMillis(20), 0), Optional.empty());
      // Allow at least one cycle.
      await().atMost(2, TimeUnit.SECONDS).until(() -> stressor.gcCycleCount() > 0);
      stressor.close();
      await()
          .atMost(5, TimeUnit.SECONDS)
          .until(() -> !threadAlive("macstab-chaos-safepoint-storm"));
    }
  }

  @Nested
  @DisplayName("StringInternPressureStressor")
  class StringInternPressureTests {

    @Test
    @DisplayName("internedCount matches requested count")
    void internedCountMatchesRequestedCount() {
      final StringInternPressureStressor stressor =
          new StringInternPressureStressor(new ChaosEffect.StringInternPressureEffect(100, 16));
      assertThat(stressor.internedCount()).isEqualTo(100);
    }

    @Test
    @DisplayName("close() nulls the interned string list")
    void closeNullsInternedStringList() {
      final StringInternPressureStressor stressor =
          new StringInternPressureStressor(new ChaosEffect.StringInternPressureEffect(50, 8));
      stressor.close();
      assertThat(stressor.internedCount()).isZero();
    }
  }

  @Nested
  @DisplayName("ReferenceQueueFloodStressor")
  class ReferenceQueueFloodTests {

    @Test
    @DisplayName("flood cycle count increments after start")
    void floodCycleCountIncrementsAfterStart() {
      final ReferenceQueueFloodStressor stressor =
          new ReferenceQueueFloodStressor(
              new ChaosEffect.ReferenceQueueFloodEffect(10, Duration.ofMillis(50)));
      try {
        await()
            .atMost(3, TimeUnit.SECONDS)
            .untilAsserted(() -> assertThat(stressor.floodCycleCount()).isGreaterThan(0));
      } finally {
        stressor.close();
      }
    }

    @Test
    @DisplayName("close() stops the flood thread")
    void closeStopsFloodThread() throws Exception {
      final ReferenceQueueFloodStressor stressor =
          new ReferenceQueueFloodStressor(
              new ChaosEffect.ReferenceQueueFloodEffect(5, Duration.ofMillis(20)));
      await().atMost(2, TimeUnit.SECONDS).until(() -> stressor.floodCycleCount() > 0);
      stressor.close();
      await().atMost(5, TimeUnit.SECONDS).until(() -> !threadAlive("macstab-chaos-refqueue-flood"));
    }

    @Test
    @DisplayName("drains the enqueued references while it floods")
    void drainsEnqueuedReferencesWhileFlooding() {
      final ReferenceQueueFloodStressor stressor =
          new ReferenceQueueFloodStressor(
              new ChaosEffect.ReferenceQueueFloodEffect(100, Duration.ofMillis(10)));
      try {
        await()
            .atMost(10, TimeUnit.SECONDS)
            .untilAsserted(() -> assertThat(stressor.drainedCount()).isGreaterThan(0));
        final long drained = stressor.drainedCount();
        await()
            .atMost(10, TimeUnit.SECONDS)
            .untilAsserted(() -> assertThat(stressor.drainedCount()).isGreaterThan(drained));
      } finally {
        stressor.close();
      }
    }

    @Test
    @DisplayName("keeps flooding for 30 s in a 64 MB heap without running out of memory")
    void keepsFloodingInASmallHeap() throws Exception {
      final Process process =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                  "-Xmx64m",
                  "-cp",
                  System.getProperty("java.class.path"),
                  ReferenceQueueFloodProbeMain.class.getName())
              .redirectErrorStream(true)
              .start();
      final String output;
      try {
        output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
      } finally {
        process.destroyForcibly();
      }

      assertThat(output).doesNotContain("OutOfMemoryError");
      assertThat(probeValue(output, "late")).isGreaterThan(probeValue(output, "early"));
    }
  }

  private static long probeValue(final String output, final String key) {
    return output
        .lines()
        .filter(line -> line.startsWith(key + "="))
        .map(line -> Long.parseLong(line.substring(key.length() + 1)))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no " + key + "= line in:\n" + output));
  }

  private static boolean threadAlive(final String name) {
    return Thread.getAllStackTraces().keySet().stream()
        .anyMatch(thread -> thread.isAlive() && name.equals(thread.getName()));
  }
}
