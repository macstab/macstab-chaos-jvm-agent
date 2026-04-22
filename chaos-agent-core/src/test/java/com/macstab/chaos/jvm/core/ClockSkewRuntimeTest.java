package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for the ClockSkewEffect runtime path via {@link ChaosRuntime#applyClockSkew}. */
@DisplayName("ClockSkewEffect runtime")
class ClockSkewRuntimeTest {

  private static final long REAL_MILLIS = 1_700_000_000_000L;
  private static final long REAL_NANOS = 100_000_000_000L;
  private static final long SKEW_MS = 5_000L;

  private static ChaosRuntime runtimeWithClockSkew(
      final Duration skewAmount, final ChaosEffect.ClockSkewMode mode) {
    final ChaosRuntime runtime = new ChaosRuntime();
    runtime.activate(
        ChaosScenario.builder("clock-skew")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_CLOCK_MILLIS)))
            .effect(new ChaosEffect.ClockSkewEffect(skewAmount, mode))
            .activationPolicy(ActivationPolicy.always())
            .build());
    return runtime;
  }

  @Nested
  @DisplayName("FIXED mode")
  class FixedMode {

    @Test
    @DisplayName("adds constant positive skew to millis")
    void addsConstantPositiveSkew() {
      final ChaosRuntime runtime =
          runtimeWithClockSkew(Duration.ofMillis(SKEW_MS), ChaosEffect.ClockSkewMode.FIXED);
      final long skewed = runtime.applyClockSkew(REAL_MILLIS, OperationType.SYSTEM_CLOCK_MILLIS);
      assertThat(skewed).isEqualTo(REAL_MILLIS + SKEW_MS);
    }

    @Test
    @DisplayName("adds constant negative skew to millis")
    void addsConstantNegativeSkew() {
      final ChaosRuntime runtime =
          runtimeWithClockSkew(Duration.ofMillis(-SKEW_MS), ChaosEffect.ClockSkewMode.FIXED);
      final long skewed = runtime.applyClockSkew(REAL_MILLIS, OperationType.SYSTEM_CLOCK_MILLIS);
      assertThat(skewed).isEqualTo(REAL_MILLIS - SKEW_MS);
    }

    @Test
    @DisplayName("returns same offset on repeated calls")
    void returnsSameOffsetOnRepeatedCalls() {
      final ChaosRuntime runtime =
          runtimeWithClockSkew(Duration.ofMillis(SKEW_MS), ChaosEffect.ClockSkewMode.FIXED);
      final long first = runtime.applyClockSkew(REAL_MILLIS, OperationType.SYSTEM_CLOCK_MILLIS);
      final long second = runtime.applyClockSkew(REAL_MILLIS, OperationType.SYSTEM_CLOCK_MILLIS);
      assertThat(first).isEqualTo(second);
    }
  }

  @Nested
  @DisplayName("DRIFT mode")
  class DriftMode {

    @Test
    @DisplayName("each call adds another skew increment")
    void eachCallAddsAnotherSkewIncrement() {
      final ChaosRuntime runtime =
          runtimeWithClockSkew(Duration.ofMillis(1_000L), ChaosEffect.ClockSkewMode.DRIFT);
      final long first = runtime.applyClockSkew(REAL_MILLIS, OperationType.SYSTEM_CLOCK_MILLIS);
      final long second = runtime.applyClockSkew(REAL_MILLIS, OperationType.SYSTEM_CLOCK_MILLIS);
      // First call: REAL + 1000; second call: REAL + 2000
      assertThat(second - first).isEqualTo(1_000L);
    }

    @Test
    @DisplayName("accumulated drift grows monotonically for positive skew")
    void driftGrowsMonotonically() {
      final ChaosRuntime runtime =
          runtimeWithClockSkew(Duration.ofMillis(500L), ChaosEffect.ClockSkewMode.DRIFT);
      long previous = Long.MIN_VALUE;
      for (int i = 0; i < 5; i++) {
        final long current = runtime.applyClockSkew(REAL_MILLIS, OperationType.SYSTEM_CLOCK_MILLIS);
        assertThat(current).isGreaterThan(previous);
        previous = current;
      }
    }
  }

  @Nested
  @DisplayName("FREEZE mode")
  class FreezeMode {

    @Test
    @DisplayName("all calls return the same frozen value")
    void allCallsReturnSameFrozenValue() {
      final ChaosRuntime runtime =
          runtimeWithClockSkew(Duration.ofMillis(SKEW_MS), ChaosEffect.ClockSkewMode.FREEZE);
      final long first = runtime.applyClockSkew(REAL_MILLIS, OperationType.SYSTEM_CLOCK_MILLIS);
      final long second =
          runtime.applyClockSkew(REAL_MILLIS + 1_000L, OperationType.SYSTEM_CLOCK_MILLIS);
      assertThat(first).isEqualTo(second);
    }
  }

  @Nested
  @DisplayName("no matching scenario")
  class NoMatchingScenario {

    @Test
    @DisplayName("returns real value unchanged when no clock-skew scenario is active")
    void returnsRealValueUnchangedWhenNoScenario() {
      final ChaosRuntime runtime = new ChaosRuntime();
      final long skewed = runtime.applyClockSkew(REAL_MILLIS, OperationType.SYSTEM_CLOCK_MILLIS);
      assertThat(skewed).isEqualTo(REAL_MILLIS);
    }
  }

  // ---------------------------------------------------------------------------
  // Runtime API tests: adjustClockMillis / adjustClockNanos
  // These methods are the exact targets that ByteBuddy advice delegates to
  // when intercepting System.currentTimeMillis() and System.nanoTime().
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Runtime API: adjustClockMillis / adjustClockNanos")
  class AdjustClockRuntimeApiTests {

    @Test
    @DisplayName("FIXED skew: adjustClockMillis returns value offset by skewAmount")
    void fixedSkewAppliedToAdjustClockMillis() {
      final ChaosRuntime runtime = new ChaosRuntime();
      final long realMillis = System.currentTimeMillis();
      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder("clock-millis-e2e")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_CLOCK_MILLIS)))
                  .effect(
                      ChaosEffect.skewClock(
                          Duration.ofSeconds(30), ChaosEffect.ClockSkewMode.FIXED))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      try {
        final long skewed = runtime.adjustClockMillis(realMillis);
        assertThat(skewed).isGreaterThanOrEqualTo(realMillis + 25_000L);
      } finally {
        handle.stop();
      }
    }

    @Test
    @DisplayName("FIXED skew: adjustClockNanos returns value offset by skewAmount in nanos")
    void fixedSkewAppliedToAdjustClockNanos() {
      final ChaosRuntime runtime = new ChaosRuntime();
      final long realNanos = System.nanoTime();
      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder("clock-nanos-e2e")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_CLOCK_NANOS)))
                  .effect(
                      ChaosEffect.skewClock(
                          Duration.ofSeconds(10), ChaosEffect.ClockSkewMode.FIXED))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      try {
        final long skewed = runtime.adjustClockNanos(realNanos);
        assertThat(skewed).isGreaterThanOrEqualTo(realNanos + 9_000_000_000L);
      } finally {
        handle.stop();
      }
    }

    @Test
    @DisplayName("FREEZE mode: repeated adjustClockMillis calls return same value")
    void freezeModeReturnsSameValue() {
      final ChaosRuntime runtime = new ChaosRuntime();
      final long realMillis = System.currentTimeMillis();
      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder("clock-freeze-e2e")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_CLOCK_MILLIS)))
                  .effect(
                      ChaosEffect.skewClock(Duration.ofMillis(1), ChaosEffect.ClockSkewMode.FREEZE))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      try {
        final long first = runtime.adjustClockMillis(realMillis);
        final long second = runtime.adjustClockMillis(realMillis + 100L);
        assertThat(first).isEqualTo(second);
      } finally {
        handle.stop();
      }
    }

    @Test
    @DisplayName("after handle.stop(), adjustClockMillis returns the real value unchanged")
    void afterStopReturnsRealValue() {
      final ChaosRuntime runtime = new ChaosRuntime();
      final long realMillis = System.currentTimeMillis();
      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder("clock-stop-e2e")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_CLOCK_MILLIS)))
                  .effect(
                      ChaosEffect.skewClock(Duration.ofHours(1), ChaosEffect.ClockSkewMode.FIXED))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      handle.stop();
      final long afterStop = runtime.adjustClockMillis(realMillis);
      assertThat(afterStop).isEqualTo(realMillis);
    }
  }

  // ---------------------------------------------------------------------------
  // Higher-level time API runtime tests: adjustInstantNow / adjustLocalDateTimeNow /
  // adjustZonedDateTimeNow / adjustDateNew. These cover the exit path used by the
  // ByteBuddy advice that intercepts java.time.Instant.now(), java.time.LocalDateTime.now(),
  // java.time.ZonedDateTime.now(), and the java.util.Date() constructor.
  // ---------------------------------------------------------------------------

  @org.junit.jupiter.api.Nested
  @DisplayName("Higher-level time APIs")
  class HigherLevelTimeApis {

    @Test
    @DisplayName("adjustInstantNow shifts the returned Instant when INSTANT_NOW scenario is active")
    void adjustInstantNowShiftsWhenActive() {
      final ChaosRuntime runtime = new ChaosRuntime();
      final java.time.Instant real = java.time.Instant.ofEpochSecond(1_700_000_000L, 123_456_789);
      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder("instant-skew")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.INSTANT_NOW)))
                  .effect(
                      ChaosEffect.skewClock(
                          Duration.ofSeconds(30), ChaosEffect.ClockSkewMode.FIXED))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      try {
        final java.time.Instant adjusted = runtime.adjustInstantNow(real);
        assertThat(adjusted.toEpochMilli() - real.toEpochMilli()).isEqualTo(30_000L);
        // Nanosecond-of-second component must be preserved by plusMillis.
        assertThat(adjusted.getNano() % 1_000_000).isEqualTo(real.getNano() % 1_000_000);
      } finally {
        handle.stop();
      }
    }

    @Test
    @DisplayName("adjustInstantNow returns the real instant when no scenario matches")
    void adjustInstantNowPassthroughWithoutScenario() {
      final ChaosRuntime runtime = new ChaosRuntime();
      final java.time.Instant real = java.time.Instant.ofEpochSecond(1_700_000_000L);
      assertThat(runtime.adjustInstantNow(real)).isEqualTo(real);
    }

    @Test
    @DisplayName(
        "adjustInstantNow ignores scenarios that target other operation types (e.g. DATE_NEW)")
    void adjustInstantNowIsolatedFromOtherClockOps() {
      final ChaosRuntime runtime = new ChaosRuntime();
      final java.time.Instant real = java.time.Instant.ofEpochSecond(1_700_000_000L);
      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder("date-only-skew")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.DATE_NEW)))
                  .effect(
                      ChaosEffect.skewClock(Duration.ofMinutes(5), ChaosEffect.ClockSkewMode.FIXED))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      try {
        assertThat(runtime.adjustInstantNow(real)).isEqualTo(real);
      } finally {
        handle.stop();
      }
    }

    @Test
    @DisplayName("adjustDateNew shifts the embedded millis when DATE_NEW scenario is active")
    void adjustDateNewShiftsWhenActive() {
      final ChaosRuntime runtime = new ChaosRuntime();
      final long real = 1_700_000_000_000L;
      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder("date-skew")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.DATE_NEW)))
                  .effect(
                      ChaosEffect.skewClock(
                          Duration.ofMinutes(-10), ChaosEffect.ClockSkewMode.FIXED))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      try {
        assertThat(runtime.adjustDateNew(real)).isEqualTo(real - 600_000L);
      } finally {
        handle.stop();
      }
    }

    @Test
    @DisplayName("adjustDateNew returns the real millis when no scenario matches")
    void adjustDateNewPassthroughWithoutScenario() {
      final ChaosRuntime runtime = new ChaosRuntime();
      assertThat(runtime.adjustDateNew(1_700_000_000_000L)).isEqualTo(1_700_000_000_000L);
    }

    @Test
    @DisplayName(
        "adjustLocalDateTimeNow shifts the returned local date-time when scenario is active")
    void adjustLocalDateTimeNowShiftsWhenActive() {
      final ChaosRuntime runtime = new ChaosRuntime();
      final java.time.LocalDateTime real = java.time.LocalDateTime.of(2026, 4, 18, 12, 0, 0);
      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder("ldt-skew")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.LOCAL_DATE_TIME_NOW)))
                  .effect(
                      ChaosEffect.skewClock(Duration.ofHours(2), ChaosEffect.ClockSkewMode.FIXED))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      try {
        final java.time.LocalDateTime adjusted = runtime.adjustLocalDateTimeNow(real);
        assertThat(adjusted).isEqualTo(real.plusHours(2));
      } finally {
        handle.stop();
      }
    }

    @Test
    @DisplayName("adjustZonedDateTimeNow shifts the instant while preserving the original zone")
    void adjustZonedDateTimeNowShiftsAndPreservesZone() {
      final ChaosRuntime runtime = new ChaosRuntime();
      final java.time.ZoneId zone = java.time.ZoneId.of("America/New_York");
      final java.time.ZonedDateTime real =
          java.time.ZonedDateTime.of(2026, 4, 18, 12, 0, 0, 0, zone);
      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder("zdt-skew")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.ZONED_DATE_TIME_NOW)))
                  .effect(
                      ChaosEffect.skewClock(
                          Duration.ofMinutes(45), ChaosEffect.ClockSkewMode.FIXED))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      try {
        final java.time.ZonedDateTime adjusted = runtime.adjustZonedDateTimeNow(real);
        assertThat(adjusted.toInstant()).isEqualTo(real.toInstant().plus(Duration.ofMinutes(45)));
        assertThat(adjusted.getZone()).isEqualTo(zone);
      } finally {
        handle.stop();
      }
    }

    @Test
    @DisplayName("INSTANT_NOW FREEZE mode returns the same instant on repeated calls")
    void instantNowFreezeReturnsSameValue() {
      final ChaosRuntime runtime = new ChaosRuntime();
      final java.time.Instant a = java.time.Instant.ofEpochSecond(1_700_000_000L);
      final java.time.Instant b = java.time.Instant.ofEpochSecond(1_700_000_100L);
      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder("instant-freeze")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.INSTANT_NOW)))
                  .effect(
                      ChaosEffect.skewClock(Duration.ofMillis(1), ChaosEffect.ClockSkewMode.FREEZE))
                  .activationPolicy(ActivationPolicy.always())
                  .build());
      try {
        // FREEZE pins the skewed millis at activation time. Both inputs therefore produce the
        // same toEpochMilli(), even though the original instants are 100 s apart.
        assertThat(runtime.adjustInstantNow(a).toEpochMilli())
            .isEqualTo(runtime.adjustInstantNow(b).toEpochMilli());
      } finally {
        handle.stop();
      }
    }
  }
}
