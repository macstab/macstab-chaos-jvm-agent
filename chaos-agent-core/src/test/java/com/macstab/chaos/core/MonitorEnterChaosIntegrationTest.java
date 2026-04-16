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

/**
 * Integration tests for {@link ChaosRuntime#beforeMonitorEnter} (MONITOR_ENTER / AQS interception).
 */
@DisplayName("MONITOR_ENTER chaos - runtime integration")
class MonitorEnterChaosIntegrationTest {

  // ---------------------------------------------------------------------------
  // Lock acquire delay
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Lock acquire delay")
  class LockAcquireDelay {

    @Test
    @DisplayName("DelayEffect slows beforeMonitorEnter (ReentrantLock.lock() path)")
    void delaySlowsMonitorEnter() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      runtime.activate(
          ChaosScenario.builder("monitor-enter-delay")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.monitor(Set.of(OperationType.MONITOR_ENTER)))
              .effect(ChaosEffect.delay(Duration.ofMillis(50)))
              .activationPolicy(ActivationPolicy.always())
              .build());
      final long start = System.nanoTime();
      runtime.beforeMonitorEnter();
      final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertThat(elapsedMs).isGreaterThanOrEqualTo(30);
    }

    @Test
    @DisplayName("delay is additive across multiple lock() calls")
    void delayIsAdditiveAcrossMultipleCalls() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      runtime.activate(
          ChaosScenario.builder("monitor-enter-delay-multi")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.monitor(Set.of(OperationType.MONITOR_ENTER)))
              .effect(ChaosEffect.delay(Duration.ofMillis(50)))
              .activationPolicy(ActivationPolicy.always())
              .build());
      final long start = System.nanoTime();
      runtime.beforeMonitorEnter();
      runtime.beforeMonitorEnter();
      final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertThat(elapsedMs).isGreaterThanOrEqualTo(80);
    }
  }

  // ---------------------------------------------------------------------------
  // Without scenario
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Without scenario")
  class WithoutScenario {

    @Test
    @DisplayName("beforeMonitorEnter does not block when no scenario is active")
    void noDelayWithoutScenario() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      final long start = System.nanoTime();
      runtime.beforeMonitorEnter();
      final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertThat(elapsedMs).isLessThan(20);
    }
  }
}
