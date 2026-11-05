package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosEffect;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The skewed clock values: each mode's arithmetic, saturating at the ends of the {@code long} range
 * instead of wrapping a forward skew into the deep past.
 */
@DisplayName("Clock skew arithmetic")
class ClockSkewStateTest {

  private static ClockSkewState state(final Duration skew, final ChaosEffect.ClockSkewMode mode) {
    return new ClockSkewState(new ChaosEffect.ClockSkewEffect(skew, mode), 1_000L, 2_000L);
  }

  @Test
  @DisplayName("a fixed skew saturates at the ends of the range in both directions")
  void fixedSaturates() {
    final ClockSkewState forward = state(Duration.ofHours(1), ChaosEffect.ClockSkewMode.FIXED);
    final ClockSkewState backward = state(Duration.ofHours(-1), ChaosEffect.ClockSkewMode.FIXED);

    assertThat(forward.applyMillis(ChaosEffect.ClockSkewMode.FIXED, Long.MAX_VALUE - 1))
        .isEqualTo(Long.MAX_VALUE);
    assertThat(forward.applyNanos(ChaosEffect.ClockSkewMode.FIXED, Long.MAX_VALUE - 1))
        .isEqualTo(Long.MAX_VALUE);
    assertThat(backward.applyMillis(ChaosEffect.ClockSkewMode.FIXED, Long.MIN_VALUE + 1))
        .isEqualTo(Long.MIN_VALUE);
    assertThat(forward.applyNanos(ChaosEffect.ClockSkewMode.FIXED, 5L))
        .isEqualTo(5L + Duration.ofHours(1).toNanos());
  }

  @Test
  @DisplayName("a frozen clock returns the skewed activation instant, saturated")
  void freezeSaturates() {
    final ClockSkewState frozen =
        new ClockSkewState(
            new ChaosEffect.ClockSkewEffect(Duration.ofHours(1), ChaosEffect.ClockSkewMode.FREEZE),
            Long.MAX_VALUE - 1,
            7L);

    assertThat(frozen.applyMillis(ChaosEffect.ClockSkewMode.FREEZE, 0L)).isEqualTo(Long.MAX_VALUE);
    assertThat(frozen.applyNanos(ChaosEffect.ClockSkewMode.FREEZE, 0L))
        .isEqualTo(7L + Duration.ofHours(1).toNanos());
  }

  @Test
  @DisplayName("a regressing nano clock reads real once, then steps back by the skew")
  void regressNanos() {
    final ClockSkewState regress = state(Duration.ofNanos(500L), ChaosEffect.ClockSkewMode.REGRESS);

    assertThat(regress.applyNanos(ChaosEffect.ClockSkewMode.REGRESS, 10_000L)).isEqualTo(10_000L);
    assertThat(regress.applyNanos(ChaosEffect.ClockSkewMode.REGRESS, 10_000L)).isEqualTo(9_500L);
    assertThat(regress.applyNanos(ChaosEffect.ClockSkewMode.REGRESS, 10_000L)).isEqualTo(9_500L);
  }

  @Test
  @DisplayName("a drift accumulates per read and saturates instead of wrapping")
  void driftSaturates() {
    final long half = Long.MAX_VALUE / 2 + 1;
    final ClockSkewState drift = state(Duration.ofNanos(half), ChaosEffect.ClockSkewMode.DRIFT);

    assertThat(drift.applyNanos(ChaosEffect.ClockSkewMode.DRIFT, 0L)).isEqualTo(half);
    assertThat(drift.applyNanos(ChaosEffect.ClockSkewMode.DRIFT, 0L)).isEqualTo(Long.MAX_VALUE);
    assertThat(drift.applyNanos(ChaosEffect.ClockSkewMode.DRIFT, 0L)).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  @DisplayName("a backward drift saturates at the start of the range")
  void backwardDriftSaturates() {
    final long half = Long.MIN_VALUE / 2 - 1;
    final ClockSkewState drift = state(Duration.ofNanos(half), ChaosEffect.ClockSkewMode.DRIFT);

    assertThat(drift.applyNanos(ChaosEffect.ClockSkewMode.DRIFT, 0L)).isEqualTo(half);
    assertThat(drift.applyNanos(ChaosEffect.ClockSkewMode.DRIFT, 0L)).isEqualTo(Long.MIN_VALUE);
    assertThat(drift.applyNanos(ChaosEffect.ClockSkewMode.DRIFT, 0L)).isEqualTo(Long.MIN_VALUE);
  }
}
