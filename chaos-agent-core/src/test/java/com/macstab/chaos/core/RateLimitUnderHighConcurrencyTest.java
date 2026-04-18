package com.macstab.chaos.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.api.ActivationPolicy;
import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.ChaosScenario;
import com.macstab.chaos.api.ChaosSelector;
import com.macstab.chaos.api.ChaosDiagnostics;
import com.macstab.chaos.api.OperationType;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link ActivationPolicy.RateLimit} never allows more permits than configured when
 * many threads race to consume the same rate-limit window simultaneously.
 *
 * <h2>Invariant under test</h2>
 *
 * <p>Given a rate limit of {@code N} permits per window, no matter how many threads fire
 * concurrently, the runtime must apply the effect at most {@code N} times within the same window.
 * Without a proper atomic CAS / token-bucket guard, multiple threads can read the permit count
 * below the cap at the same instant, each decide to consume a token, and collectively overshoot.
 *
 * <h2>Measurement strategy</h2>
 *
 * <p>We use {@link ChaosDiagnostics.ScenarioReport#appliedCount()} as the authoritative counter.
 * The window is kept long enough (10 s) that no thread can legitimately enter a second window
 * during the test.
 */
@DisplayName("RateLimit correctness under high concurrency")
class RateLimitUnderHighConcurrencyTest {

  @Nested
  @DisplayName("permits-per-window invariant")
  class PermitsPerWindowInvariant {

    private static final int THREAD_COUNT = 100;
    private static final long PERMITS = 7L;

    @Test
    @DisplayName(
        "appliedCount never exceeds "
            + PERMITS
            + " permits across "
            + THREAD_COUNT
            + " concurrent threads")
    void appliedCountNeverExceedsPermitsUnderConcurrency() throws Exception {
      final ChaosRuntime runtime = new ChaosRuntime();
      runtime.activate(
          ChaosScenario.builder("rate-limit-concurrent")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ZERO)) // zero delay — fastest possible effect
              .activationPolicy(
                  new ActivationPolicy(
                      ActivationPolicy.StartMode.AUTOMATIC,
                      1.0d,
                      0,
                      null,
                      null,
                      new ActivationPolicy.RateLimit(PERMITS, Duration.ofSeconds(10)),
                      null,
                      false))
              .build());

      final CountDownLatch startLatch = new CountDownLatch(1);
      final var executor = Executors.newFixedThreadPool(THREAD_COUNT);
      try {
        for (int i = 0; i < THREAD_COUNT; i++) {
          executor.submit(
              () -> {
                try {
                  startLatch.await();
                } catch (final InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
              });
        }
        startLatch.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
      } finally {
        executor.shutdownNow();
      }

      final long applied =
          runtime.diagnostics().snapshot().scenarios().stream()
              .filter(r -> "rate-limit-concurrent".equals(r.id()))
              .mapToLong(ChaosDiagnostics.ScenarioReport::appliedCount)
              .sum();

      assertThat(applied)
          .as(
              "appliedCount must not exceed permit count %d (got %d with %d threads racing)",
              PERMITS, applied, THREAD_COUNT)
          .isLessThanOrEqualTo(PERMITS);
    }

    @Test
    @DisplayName(
        "exactly PERMITS applications across "
            + THREAD_COUNT
            + " threads — no permit is wasted")
    void exactlyPermitsApplicationsWhenMoreThreadsThanPermits() throws Exception {
      final ChaosRuntime runtime = new ChaosRuntime();
      final long permits = 10L;

      runtime.activate(
          ChaosScenario.builder("rate-limit-exact")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ZERO))
              .activationPolicy(
                  new ActivationPolicy(
                      ActivationPolicy.StartMode.AUTOMATIC,
                      1.0d,
                      0,
                      null,
                      null,
                      new ActivationPolicy.RateLimit(permits, Duration.ofSeconds(10)),
                      null,
                      false))
              .build());

      final CountDownLatch startLatch = new CountDownLatch(1);
      final var executor = Executors.newFixedThreadPool(THREAD_COUNT);
      try {
        for (int i = 0; i < THREAD_COUNT; i++) {
          executor.submit(
              () -> {
                try {
                  startLatch.await();
                } catch (final InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
              });
        }
        startLatch.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
      } finally {
        executor.shutdownNow();
      }

      final long applied =
          runtime.diagnostics().snapshot().scenarios().stream()
              .filter(r -> "rate-limit-exact".equals(r.id()))
              .mapToLong(ChaosDiagnostics.ScenarioReport::appliedCount)
              .sum();

      assertThat(applied)
          .as(
              "with %d threads competing for %d permits, exactly %d permits should be consumed",
              THREAD_COUNT, permits, permits)
          .isEqualTo(permits);
    }
  }

  @Nested
  @DisplayName("combined rate-limit and maxApplications guard")
  class CombinedRateLimitAndMaxApplications {

    @Test
    @DisplayName(
        "maxApplications caps total count even when rate-limit window permits more")
    void maxApplicationsCapsAbsoluteCountBelowRateLimit() throws Exception {
      final ChaosRuntime runtime = new ChaosRuntime();
      final long maxApplications = 3L;
      final long permitsPerWindow = 20L;
      final int threads = 50;

      runtime.activate(
          ChaosScenario.builder("combined-cap")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ZERO))
              .activationPolicy(
                  new ActivationPolicy(
                      ActivationPolicy.StartMode.AUTOMATIC,
                      1.0d,
                      0,
                      maxApplications,
                      null,
                      new ActivationPolicy.RateLimit(permitsPerWindow, Duration.ofSeconds(10)),
                      null,
                      false))
              .build());

      final CountDownLatch startLatch = new CountDownLatch(1);
      final var executor = Executors.newFixedThreadPool(threads);
      try {
        for (int i = 0; i < threads; i++) {
          executor.submit(
              () -> {
                try {
                  startLatch.await();
                } catch (final InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
              });
        }
        startLatch.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
      } finally {
        executor.shutdownNow();
      }

      final long applied =
          runtime.diagnostics().snapshot().scenarios().stream()
              .filter(r -> "combined-cap".equals(r.id()))
              .mapToLong(ChaosDiagnostics.ScenarioReport::appliedCount)
              .sum();

      assertThat(applied)
          .as(
              "maxApplications=%d must cap total applications even with rate-limit permitting "
                  + "up to %d, got %d",
              maxApplications, permitsPerWindow, applied)
          .isLessThanOrEqualTo(maxApplications);
    }
  }
}
