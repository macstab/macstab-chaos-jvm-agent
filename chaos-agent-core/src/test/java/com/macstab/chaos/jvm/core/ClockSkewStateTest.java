package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosEffect;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.zone.ZoneOffsetTransition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The skewed clock values: each mode's arithmetic, saturating at the ends of the {@code long} range
 * instead of wrapping a forward skew into the deep past.
 */
@DisplayName("Clock skew arithmetic")
class ClockSkewStateTest {

  private static ClockSkewState state(final Duration skew, final ChaosEffect.ClockSkewMode mode) {
    return new ClockSkewState(new ChaosEffect.ClockSkewEffect(skew, mode), 1_000L, 2_000L, null);
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
            7L,
            null);

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

  @Test
  @DisplayName(
      "a freeze with a future activeFrom freezes both clocks at activeFrom, not at activation")
  void freezeAnchorsAtActiveFrom() {
    final long now = 1_700_000_000_000L;
    final long nanoRef = 5_000L;
    final Instant activeFrom = Instant.ofEpochMilli(now).plus(Duration.ofHours(1));
    final ClockSkewState frozen =
        new ClockSkewState(
            new ChaosEffect.ClockSkewEffect(
                Duration.ofSeconds(2), ChaosEffect.ClockSkewMode.FREEZE),
            now,
            nanoRef,
            activeFrom);

    assertThat(frozen.applyMillis(ChaosEffect.ClockSkewMode.FREEZE, now))
        .isEqualTo(activeFrom.toEpochMilli() + 2_000L);
    assertThat(frozen.applyInstant(ChaosEffect.ClockSkewMode.FREEZE, Instant.ofEpochMilli(now)))
        .isEqualTo(activeFrom.plusSeconds(2));
    assertThat(frozen.applyNanos(ChaosEffect.ClockSkewMode.FREEZE, nanoRef) - nanoRef)
        .isEqualTo(Duration.ofHours(1).plusSeconds(2).toNanos());
  }

  @Test
  @DisplayName("a freeze whose activeFrom has passed freezes at activation")
  void freezeAfterActiveFrom() {
    final ClockSkewState frozen =
        new ClockSkewState(
            new ChaosEffect.ClockSkewEffect(Duration.ofMillis(1), ChaosEffect.ClockSkewMode.FREEZE),
            1_000_000L,
            7L,
            Instant.ofEpochMilli(1_000L));

    assertThat(frozen.applyMillis(ChaosEffect.ClockSkewMode.FREEZE, 0L)).isEqualTo(1_000_001L);
    assertThat(frozen.applyNanos(ChaosEffect.ClockSkewMode.FREEZE, 0L)).isEqualTo(1_000_007L);
  }

  @Test
  @DisplayName("a freeze with an activeFrom far in the future saturates the nano clock")
  void freezeFarFutureSaturates() {
    final ClockSkewState frozen =
        new ClockSkewState(
            new ChaosEffect.ClockSkewEffect(Duration.ofNanos(1), ChaosEffect.ClockSkewMode.FREEZE),
            0L,
            Long.MAX_VALUE - 10L,
            Instant.ofEpochMilli(0L).plus(Duration.ofDays(365L * 1000)));

    assertThat(frozen.applyNanos(ChaosEffect.ClockSkewMode.FREEZE, 0L)).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  @DisplayName("a sub-millisecond fixed or regressing skew moves millis and Instant reads alike")
  void millisAndInstantAgree() {
    // the real clock is 0.7 ms into a millisecond; the skew is 0.5 ms
    final long realMillis = 1_000L;
    final Instant realInstant = Instant.ofEpochMilli(realMillis).plusNanos(700_000L);
    for (final ChaosEffect.ClockSkewMode mode :
        new ChaosEffect.ClockSkewMode[] {
          ChaosEffect.ClockSkewMode.FIXED, ChaosEffect.ClockSkewMode.REGRESS
        }) {
      final ClockSkewState millisState = state(Duration.ofNanos(500_000L), mode);
      final ClockSkewState instantState = state(Duration.ofNanos(500_000L), mode);
      for (int read = 0; read < 3; read++) {
        assertThat(instantState.applyInstant(mode, realInstant).toEpochMilli())
            .as("%s read %d", mode, read)
            .isEqualTo(millisState.applyMillis(mode, realMillis));
      }
    }
  }

  @Test
  @DisplayName("a sub-millisecond drift still drifts: 0.4 ms per read is 1 ms by the third read")
  void subMillisecondDriftDrifts() {
    final ClockSkewState drift = state(Duration.ofNanos(400_000L), ChaosEffect.ClockSkewMode.DRIFT);

    assertThat(drift.applyMillis(ChaosEffect.ClockSkewMode.DRIFT, 1_000L)).isEqualTo(1_000L);
    assertThat(drift.applyMillis(ChaosEffect.ClockSkewMode.DRIFT, 1_000L)).isEqualTo(1_000L);
    assertThat(drift.applyMillis(ChaosEffect.ClockSkewMode.DRIFT, 1_000L)).isEqualTo(1_001L);
    assertThat(drift.applyNanos(ChaosEffect.ClockSkewMode.DRIFT, 0L)).isEqualTo(400_000L);
  }

  @Test
  @DisplayName("without a clock-skew scenario a local date-time is returned before any conversion")
  void idleLocalDateTimeIsNotConverted() {
    try (ChaosRuntime runtime = new ChaosRuntime()) {
      final LocalDateTime value = LocalDateTime.of(2026, 1, 1, 0, 0);
      // no zone: converting would fail, so the idle check must come first
      assertThat(runtime.adjustLocalDateTimeNowInZone(value, null)).isSameAs(value);
    }
  }

  @Test
  @DisplayName(
      "a local date-time in a DST overlap is skewed from the occurrence in force, not the earlier")
  void localDateTimeInADstOverlap() {
    // Tokyo is at +09:00 for good; its last DST overlap (1951) ended on +09:00, so the occurrence
    // in force now is the later one.
    final ZoneId tokyo = ZoneId.of("Asia/Tokyo");
    final ZoneOffsetTransition overlap =
        tokyo.getRules().getTransitions().stream()
            .filter(ZoneOffsetTransition::isOverlap)
            .reduce((first, second) -> second)
            .orElseThrow();
    final LocalDateTime real = overlap.getDateTimeAfter().plusMinutes(30);
    try (ChaosRuntime runtime = new ChaosRuntime()) {
      runtime.activate(
          com.macstab.chaos.jvm.api.ChaosScenario.builder("skew")
              .scope(com.macstab.chaos.jvm.api.ChaosScenario.ScenarioScope.JVM)
              .selector(
                  com.macstab.chaos.jvm.api.ChaosSelector.jvmRuntime(
                      java.util.Set.of(
                          com.macstab.chaos.jvm.api.OperationType.LOCAL_DATE_TIME_NOW)))
              .effect(
                  ChaosEffect.skewClock(Duration.ofMinutes(45), ChaosEffect.ClockSkewMode.FIXED))
              .activationPolicy(com.macstab.chaos.jvm.api.ActivationPolicy.always())
              .build());

      // 45 minutes after the later 00:30 is 01:15; from the earlier one it crosses the
      // transition and reads 00:15
      assertThat(runtime.adjustLocalDateTimeNowInZone(real, tokyo)).isEqualTo(real.plusMinutes(45));
    }
  }
}
