package com.macstab.chaos.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.api.ActivationPolicy;
import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.ChaosScenario;
import com.macstab.chaos.api.ChaosSelector;
import com.macstab.chaos.api.OperationType;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Integration tests for {@link ChaosRuntime#beforeThreadPark} (THREAD_PARK interception). */
@DisplayName("THREAD_PARK chaos - runtime integration")
class ThreadParkChaosIntegrationTest {

  // ---------------------------------------------------------------------------
  // Park delay
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Park delay")
  class ParkDelay {

    @Test
    @DisplayName("DelayEffect slows beforeThreadPark (park(Object) path)")
    void delaySlowsPark() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      runtime.activate(
          ChaosScenario.builder("park-delay")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.monitor(Set.of(OperationType.THREAD_PARK)))
              .effect(ChaosEffect.delay(Duration.ofMillis(50)))
              .activationPolicy(ActivationPolicy.always())
              .build());
      final long start = System.nanoTime();
      runtime.beforeThreadPark();
      final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertThat(elapsedMs).isGreaterThanOrEqualTo(30);
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
      final ChaosRuntime runtime = new ChaosRuntime();
      runtime.activate(
          ChaosScenario.builder("park-nanos-delay")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.monitor(Set.of(OperationType.THREAD_PARK)))
              .effect(ChaosEffect.delay(Duration.ofMillis(50)))
              .activationPolicy(ActivationPolicy.always())
              .build());
      final long start = System.nanoTime();
      runtime.beforeThreadPark();
      final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertThat(elapsedMs).isGreaterThanOrEqualTo(30);
    }
  }

  // ---------------------------------------------------------------------------
  // Without scenario
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Without scenario")
  class WithoutScenario {

    @Test
    @DisplayName("beforeThreadPark does not block when no scenario is active")
    void noDelayWithoutScenario() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      final long start = System.nanoTime();
      runtime.beforeThreadPark();
      final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertThat(elapsedMs).isLessThan(20);
    }
  }
}
