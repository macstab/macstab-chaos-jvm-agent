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

/** Integration tests for {@link ChaosRuntime#beforeThreadPark} (THREAD_PARK interception). */
@DisplayName("THREAD_PARK chaos - runtime integration")
class ThreadParkChaosIntegrationTest {

  /**
   * The configured delay. Each check asserts the applied count first, then the time against the
   * delay itself: a delayed call takes all of it, an undelayed one less than half.
   */
  private static final Duration DELAY = Duration.ofMillis(200);

  private static final long DELAY_NANOS = DELAY.toNanos();

  // ---------------------------------------------------------------------------
  // Park delay
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Park delay")
  class ParkDelay {

    @Test
    @DisplayName("DelayEffect slows beforeThreadPark (park(Object) path)")
    void delaySlowsPark() throws Throwable {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        final ChaosActivationHandle handle = runtime.activate(parkDelay("park-delay"));

        final long elapsed = parkNanos(runtime);

        assertThat(handle.appliedCount()).isEqualTo(1L);
        assertThat(elapsed)
            .as("the call takes the whole delay")
            .isGreaterThanOrEqualTo(DELAY_NANOS);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Park nanos delay
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Park nanos delay")
  class ParkNanosDelay {

    @Test
    @DisplayName("DelayEffect slows parkNanos — dispatches through same beforeThreadPark path")
    void delaySlowsParkNanos() throws Throwable {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        final ChaosActivationHandle handle = runtime.activate(parkDelay("park-nanos-delay"));

        final long elapsed = parkNanos(runtime) + parkNanos(runtime);

        assertThat(handle.appliedCount()).isEqualTo(2L);
        assertThat(elapsed).isGreaterThanOrEqualTo(2 * DELAY_NANOS);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Without scenario
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Without scenario")
  class WithoutScenario {

    @Test
    @DisplayName("beforeThreadPark does not block once its only scenario is stopped")
    void noDelayWithoutScenario() throws Throwable {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        final ChaosActivationHandle handle = runtime.activate(parkDelay("park-stopped"));
        handle.stop();

        final long elapsed = parkNanos(runtime);

        assertThat(handle.appliedCount())
            .as("without active scenario, operation should complete without delay")
            .isZero();
        assertThat(elapsed).isLessThan(DELAY_NANOS / 2);
      }
    }
  }

  private static ChaosScenario parkDelay(final String id) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(ChaosSelector.monitor(Set.of(OperationType.THREAD_PARK)))
        .effect(ChaosEffect.delay(DELAY))
        .activationPolicy(ActivationPolicy.always())
        .build();
  }

  private static long parkNanos(final ChaosRuntime runtime) throws Throwable {
    final long start = System.nanoTime();
    runtime.beforeThreadPark();
    return System.nanoTime() - start;
  }
}
