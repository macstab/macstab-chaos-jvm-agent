package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.macstab.chaos.jvm.api.ChaosEffect;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GC pressure stressor pacing")
class GcPressureStressorTest {

  @Test
  @DisplayName("1,000 B/s for a simulated 10 s allocates 10,000 bytes, within one object")
  void rateHoldsOverTime() {
    // Simulated time: each pause moves the clock on, so the loop never sleeps for real.
    final AtomicLong now = new AtomicLong(1_000_000L);
    final GcPressureStressor stressor =
        new GcPressureStressor(
            new ChaosEffect.GcPressureEffect(1_000L, 1024, false, Duration.ofSeconds(10)),
            now::get,
            now::addAndGet);
    try {
      await().atMost(Duration.ofSeconds(30)).until(() -> !stressor.isRunning());
    } finally {
      stressor.close();
    }

    assertThat(stressor.allocatedBytes()).isBetween(10_000L - 1024L, 10_000L + 1024L);
  }

  @Test
  @DisplayName("a huge rate of small objects allocates instead of wrapping to nothing")
  void hugeRateSaturates() {
    final AtomicLong now = new AtomicLong(1_000_000L);
    final GcPressureStressor stressor =
        new GcPressureStressor(
            new ChaosEffect.GcPressureEffect(Long.MAX_VALUE, 1, false, Duration.ofSeconds(10)),
            now::get,
            now::addAndGet);
    try {
      await().atMost(Duration.ofSeconds(30)).until(() -> stressor.allocatedBytes() > 0);
    } finally {
      stressor.close();
    }

    assertThat(stressor.isRunning()).isFalse();
  }
}
