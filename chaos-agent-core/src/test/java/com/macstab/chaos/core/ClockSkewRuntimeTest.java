package com.macstab.chaos.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.api.ActivationPolicy;
import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.ChaosScenario;
import com.macstab.chaos.api.ChaosSelector;
import com.macstab.chaos.api.OperationType;
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
}
