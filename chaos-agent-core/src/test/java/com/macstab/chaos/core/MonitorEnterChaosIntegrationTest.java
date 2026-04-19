package com.macstab.chaos.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.api.ActivationPolicy;
import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.ChaosScenario;
import com.macstab.chaos.api.ChaosSelector;
import com.macstab.chaos.api.NamePattern;
import com.macstab.chaos.api.OperationType;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link ChaosRuntime#beforeMonitorEnter} (MONITOR_ENTER / AQS interception).
 */
@DisplayName("MONITOR_ENTER chaos - runtime integration")
class MonitorEnterChaosIntegrationTest {

  private static final long DELAY_MS = 50L;
  private static final long DELAY_MIN_MS = (long) (DELAY_MS * 0.8);
  private static final long NO_DELAY_MAX_MS = 150L;

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
      final Object lock = new ReentrantLock();
      final long start = System.nanoTime();
      runtime.beforeMonitorEnter(lock);
      final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertThat(elapsedMs)
          .as("delay effect should add at least 80%% of configured %dms", DELAY_MS)
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
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
      final Object lock = new ReentrantLock();
      final long start = System.nanoTime();
      runtime.beforeMonitorEnter(lock);
      runtime.beforeMonitorEnter(lock);
      final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertThat(elapsedMs)
          .as("two lock() calls should accumulate at least 2x delay")
          .isGreaterThanOrEqualTo(DELAY_MIN_MS * 2);
    }
  }

  // ---------------------------------------------------------------------------
  // Monitor class filter (HIGH-36 regression guard)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Monitor class filter")
  class MonitorClassFilter {

    /**
     * Positive end-to-end test: selector restricts to a specific lock class and the concrete lock
     * instance passed to {@code beforeMonitorEnter} is an instance of that class, so the scenario
     * must fire.
     */
    @Test
    @DisplayName("exact monitorClassPattern fires on matching lock class")
    void exactPatternFiresOnMatch() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      runtime.activate(
          ChaosScenario.builder("monitor-enter-filter-match")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(
                  new ChaosSelector.MonitorSelector(
                      Set.of(OperationType.MONITOR_ENTER),
                      NamePattern.exact("java.util.concurrent.locks.ReentrantLock")))
              .effect(ChaosEffect.delay(Duration.ofMillis(50)))
              .activationPolicy(ActivationPolicy.always())
              .build());
      final Object lock = new ReentrantLock();
      final long start = System.nanoTime();
      runtime.beforeMonitorEnter(lock);
      final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertThat(elapsedMs)
          .as("monitorClassPattern matches ReentrantLock → delay must apply")
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
    }

    /**
     * Negative end-to-end test: selector restricts to a class the lock is NOT an instance of. The
     * scenario must not fire — proving the filter does real work rather than silently matching
     * everything.
     */
    @Test
    @DisplayName("non-matching monitorClassPattern does NOT apply")
    void nonMatchingPatternDoesNotFire() throws Throwable {
      final ChaosRuntime runtime = new ChaosRuntime();
      runtime.activate(
          ChaosScenario.builder("monitor-enter-filter-miss")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(
                  new ChaosSelector.MonitorSelector(
                      Set.of(OperationType.MONITOR_ENTER),
                      NamePattern.exact("com.example.NotTheLockClass")))
              .effect(ChaosEffect.delay(Duration.ofMillis(50)))
              .activationPolicy(ActivationPolicy.always())
              .build());
      final Object lock = new ReentrantLock();
      final long start = System.nanoTime();
      runtime.beforeMonitorEnter(lock);
      final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertThat(elapsedMs)
          .as("monitorClassPattern does not match → delay must NOT apply")
          .isLessThan(NO_DELAY_MAX_MS);
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
      final Object lock = new ReentrantLock();
      final long start = System.nanoTime();
      runtime.beforeMonitorEnter(lock);
      final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertThat(elapsedMs)
          .as("without active scenario, operation should complete without delay")
          .isLessThan(NO_DELAY_MAX_MS);
    }
  }
}
