package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link ChaosRuntime#beforeMonitorEnter} (MONITOR_ENTER / AQS interception).
 */
@DisplayName("MONITOR_ENTER chaos - runtime integration")
class MonitorEnterChaosIntegrationTest {

  /**
   * The configured delay. Each check asserts the applied count first, then the time against the
   * delay itself: a delayed call takes all of it, an undelayed one less than half.
   */
  private static final Duration DELAY = Duration.ofMillis(200);

  private static final long DELAY_NANOS = DELAY.toNanos();

  // ---------------------------------------------------------------------------
  // Lock acquire delay
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Lock acquire delay")
  class LockAcquireDelay {

    @Test
    @DisplayName("DelayEffect slows beforeMonitorEnter (ReentrantLock.lock() path)")
    void delaySlowsMonitorEnter() throws Throwable {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        final ChaosActivationHandle handle =
            runtime.activate(monitorDelay("monitor-enter-delay", NamePattern.any()));

        final long elapsed = enterNanos(runtime, new ReentrantLock());

        assertThat(handle.appliedCount()).isEqualTo(1L);
        assertThat(elapsed)
            .as("the call takes the whole delay")
            .isGreaterThanOrEqualTo(DELAY_NANOS);
      }
    }

    @Test
    @DisplayName("delay is additive across multiple lock() calls")
    void delayIsAdditiveAcrossMultipleCalls() throws Throwable {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        final ChaosActivationHandle handle =
            runtime.activate(monitorDelay("monitor-enter-delay-multi", NamePattern.any()));
        final Object lock = new ReentrantLock();

        final long elapsed = enterNanos(runtime, lock) + enterNanos(runtime, lock);

        assertThat(handle.appliedCount()).isEqualTo(2L);
        assertThat(elapsed)
            .as("two lock() calls accumulate twice the delay")
            .isGreaterThanOrEqualTo(2 * DELAY_NANOS);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Monitor class filter
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
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        final ChaosActivationHandle handle =
            runtime.activate(
                monitorDelay(
                    "monitor-enter-filter-match",
                    NamePattern.exact("java.util.concurrent.locks.ReentrantLock")));

        final long elapsed = enterNanos(runtime, new ReentrantLock());

        assertThat(handle.appliedCount())
            .as("monitorClassPattern matches ReentrantLock → delay must apply")
            .isEqualTo(1L);
        assertThat(elapsed).isGreaterThanOrEqualTo(DELAY_NANOS);
      }
    }

    /**
     * Negative end-to-end test: selector restricts to a class the lock is NOT an instance of. The
     * scenario must not fire — proving the filter does real work rather than silently matching
     * everything.
     */
    @Test
    @DisplayName("non-matching monitorClassPattern does NOT apply")
    void nonMatchingPatternDoesNotFire() throws Throwable {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        final ChaosActivationHandle handle =
            runtime.activate(
                monitorDelay(
                    "monitor-enter-filter-miss", NamePattern.exact("com.example.NotTheLockClass")));

        final long elapsed = enterNanos(runtime, new ReentrantLock());

        assertThat(handle.matchedCount())
            .as("monitorClassPattern does not match → not even matched")
            .isZero();
        assertThat(handle.appliedCount()).isZero();
        assertThat(elapsed).isLessThan(DELAY_NANOS / 2);
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
    @DisplayName("beforeMonitorEnter does not block once its only scenario is stopped")
    void noDelayWithoutScenario() throws Throwable {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        final ChaosActivationHandle handle =
            runtime.activate(monitorDelay("monitor-enter-stopped", NamePattern.any()));
        handle.stop();

        final long elapsed = enterNanos(runtime, new ReentrantLock());

        assertThat(handle.appliedCount())
            .as("without active scenario, operation should complete without delay")
            .isZero();
        assertThat(elapsed).isLessThan(DELAY_NANOS / 2);
      }
    }
  }

  private static ChaosScenario monitorDelay(final String id, final NamePattern monitorClass) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(
            new ChaosSelector.MonitorSelector(Set.of(OperationType.MONITOR_ENTER), monitorClass))
        .effect(ChaosEffect.delay(DELAY))
        .activationPolicy(ActivationPolicy.always())
        .build();
  }

  private static long enterNanos(final ChaosRuntime runtime, final Object lock) throws Throwable {
    final long start = System.nanoTime();
    runtime.beforeMonitorEnter(lock);
    return System.nanoTime() - start;
  }
}
