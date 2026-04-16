package com.macstab.chaos.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import com.macstab.chaos.api.ChaosEffect;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the four new stressor implementations introduced in Phase 2 Task 11:
 * CodeCachePressureStressor, SafepointStormStressor, StringInternPressureStressor, and
 * ReferenceQueueFloodStressor.
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

    @Test
    @DisplayName("close() does not throw")
    void closeDoesNotThrow() {
      final CodeCachePressureStressor stressor =
          new CodeCachePressureStressor(new ChaosEffect.CodeCachePressureEffect(2, 1));
      assertThatCode(stressor::close).doesNotThrowAnyException();
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
      final long countAtClose = stressor.gcCycleCount();
      stressor.close();
      Thread.sleep(150);
      assertThat(stressor.gcCycleCount()).isLessThanOrEqualTo(countAtClose + 1);
    }

    @Test
    @DisplayName("totalGcCount() returns non-negative value")
    void totalGcCountIsNonNegative() {
      assertThat(SafepointStormStressor.totalGcCount()).isGreaterThanOrEqualTo(0);
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

    @Test
    @DisplayName("close() does not throw")
    void closeDoesNotThrow() {
      final StringInternPressureStressor stressor =
          new StringInternPressureStressor(new ChaosEffect.StringInternPressureEffect(10, 4));
      assertThatCode(stressor::close).doesNotThrowAnyException();
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
      final long countAtClose = stressor.floodCycleCount();
      stressor.close();
      Thread.sleep(150);
      assertThat(stressor.floodCycleCount()).isLessThanOrEqualTo(countAtClose + 1);
    }

    @Test
    @DisplayName("close() does not throw")
    void closeDoesNotThrow() {
      final ReferenceQueueFloodStressor stressor =
          new ReferenceQueueFloodStressor(
              new ChaosEffect.ReferenceQueueFloodEffect(5, Duration.ofMillis(100)));
      assertThatCode(stressor::close).doesNotThrowAnyException();
    }
  }
}
