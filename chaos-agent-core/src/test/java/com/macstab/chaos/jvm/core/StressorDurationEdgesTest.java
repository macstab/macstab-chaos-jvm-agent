package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.macstab.chaos.jvm.api.ChaosEffect;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Stressor durations at their edges (L17): a duration too large for milliseconds lasts until the
 * stressor ends instead of failing its start, and a delay below one millisecond is kept, not
 * truncated to none.
 */
@DisplayName("Stressor durations at their edges")
class StressorDurationEdgesTest {

  private static final Duration FOREVER = Duration.ofSeconds(Long.MAX_VALUE);

  @Test
  @DisplayName("a finalizer delay too large for milliseconds is accepted")
  void hugeFinalizerDelay() {
    assertThatCode(
            () ->
                new FinalizerBacklogStressor(new ChaosEffect.FinalizerBacklogEffect(1, FOREVER))
                    .close())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a finalizer delay below one millisecond is kept")
  void subMillisecondFinalizerDelay() {
    final FinalizerBacklogStressor stressor =
        new FinalizerBacklogStressor(
            new ChaosEffect.FinalizerBacklogEffect(1, Duration.ofNanos(500_000L)));
    stressor.close();

    assertThat(stressor.finalizerDelayNanos()).isEqualTo(500_000L);
  }

  @Test
  @DisplayName("a deadlock acquisition delay too large for milliseconds is accepted")
  void hugeAcquisitionDelay() {
    assertThatCode(
            () ->
                new DeadlockStressor(
                        new ChaosEffect.DeadlockEffect(2, FOREVER), ManagedStressor.NO_END)
                    .close())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a safepoint-storm interval too large for milliseconds is accepted")
  void hugeGcInterval() {
    assertThatCode(
            () ->
                new SafepointStormStressor(
                        new ChaosEffect.SafepointStormEffect(FOREVER, 0),
                        Optional.empty(),
                        ManagedStressor.NO_END)
                    .close())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a reference-queue flood interval too large for milliseconds is accepted")
  void hugeFloodInterval() {
    assertThatCode(
            () ->
                new ReferenceQueueFloodStressor(
                        new ChaosEffect.ReferenceQueueFloodEffect(10, FOREVER),
                        ManagedStressor.NO_END)
                    .close())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("a keep-alive heartbeat too large for milliseconds keeps the thread alive")
  void hugeHeartbeat() throws InterruptedException {
    final List<String> ends = new CopyOnWriteArrayList<>();
    final KeepAliveStressor stressor =
        new KeepAliveStressor(
            new ChaosEffect.KeepAliveEffect("huge-heartbeat", true, FOREVER), ends::add);
    try {
      Thread.sleep(300L);

      assertThat(ends).as("the stressor did not end").isEmpty();
      assertThat(
              Thread.getAllStackTraces().keySet().stream()
                  .anyMatch(thread -> thread.getName().equals("huge-heartbeat")))
          .isTrue();
    } finally {
      stressor.close();
    }
  }
}
