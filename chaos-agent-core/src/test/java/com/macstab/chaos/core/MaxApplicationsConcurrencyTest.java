package com.macstab.chaos.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.api.ActivationPolicy;
import com.macstab.chaos.api.ChaosDiagnostics;
import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.ChaosScenario;
import com.macstab.chaos.api.ChaosSelector;
import com.macstab.chaos.api.OperationType;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the CAS loop in {@link ScenarioController} prevents {@code appliedCount} from
 * exceeding {@code maxApplications} under concurrent evaluation.
 *
 * <p>Without the CAS fix (Task 2), multiple threads could simultaneously read the counter below the
 * cap, both increment it, and both receive a non-null contribution — overshooting the cap.
 */
@DisplayName("maxApplications CAS correctness under concurrency")
class MaxApplicationsConcurrencyTest {

  private static final int MAX_APPLICATIONS = 5;
  private static final int THREAD_COUNT = 50;

  @Test
  @DisplayName("applied count never exceeds maxApplications under concurrent evaluation")
  void appliedCountNeverExceedsMaxApplicationsUnderConcurrency() throws Exception {
    final ChaosRuntime runtime = new ChaosRuntime();
    runtime.activate(
        ChaosScenario.builder("cas-test")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.delay(Duration.ZERO))
            .activationPolicy(
                new ActivationPolicy(
                    ActivationPolicy.StartMode.AUTOMATIC,
                    1.0d,
                    0,
                    (long) MAX_APPLICATIONS,
                    null,
                    null,
                    null))
            .build());

    final CountDownLatch startLatch = new CountDownLatch(1);
    final AtomicLong totalApplied = new AtomicLong(0);
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
              // Each thread triggers one evaluation; if a non-no-op runnable is returned the
              // effect was applied. We measure applied count via diagnostics after the run.
              runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
            });
      }
      startLatch.countDown();
      executor.shutdown();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    } finally {
      executor.shutdownNow();
    }

    final long appliedCount =
        runtime.diagnostics().snapshot().scenarios().stream()
            .filter(r -> "cas-test".equals(r.id()))
            .mapToLong(ChaosDiagnostics.ScenarioReport::appliedCount)
            .sum();

    assertThat(appliedCount)
        .as("applied count must not exceed maxApplications")
        .isLessThanOrEqualTo(MAX_APPLICATIONS);
  }
}
