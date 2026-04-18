package com.macstab.chaos.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.api.ActivationPolicy;
import com.macstab.chaos.api.ChaosActivationHandle;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that calling {@link ChaosActivationHandle#stop()} concurrently with active {@code
 * evaluate()} calls does not produce inconsistent state, data corruption, or missed transitions.
 *
 * <h2>Invariants under test</h2>
 *
 * <ul>
 *   <li>A scenario that is STOPPED must not contribute effects to <em>new</em> evaluations after
 *       the stop is visible. It may legally contribute to in-flight evaluations that already read
 *       the pre-stop state.
 *   <li>{@code handle.state()} transitions monotonically toward STOPPED — it never reverts.
 *   <li>No evaluation call throws an unexpected exception due to mid-flight scenario teardown.
 * </ul>
 */
@DisplayName("stop() called while evaluate() is mid-pipeline")
class StopDuringEvaluationTest {

  @Nested
  @DisplayName("stop() transitions state before any subsequent evaluate()")
  class StateTransition {

    @Test
    @DisplayName("state is STOPPED immediately after stop() returns on calling thread")
    void stateIsStoppedAfterStopReturns() {
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder("stop-transition")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                  .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                  .activationPolicy(ActivationPolicy.always())
                  .build());

      // Trigger at least one evaluation to advance state to ACTIVE.
      runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
      assertThat(handle.state()).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);

      handle.stop();

      assertThat(handle.state())
          .as("state must be STOPPED after stop() returns")
          .isEqualTo(ChaosDiagnostics.ScenarioState.STOPPED);
    }

    @Test
    @DisplayName("evaluate() after stop() returns null contribution — no effect applied")
    void evaluateAfterStopReturnsNoContribution() {
      final ChaosRuntime runtime = new ChaosRuntime();
      final AtomicInteger delayAppliedCount = new AtomicInteger(0);

      // Use maxApplications=1 so we can see the difference cleanly
      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder("stop-no-effect")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                  .effect(ChaosEffect.delay(Duration.ofMillis(80)))
                  .activationPolicy(ActivationPolicy.always())
                  .build());

      // One evaluation before stop — delay is applied.
      final long before =
          measureMillis(() -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}));
      assertThat(before)
          .as("first call before stop should experience delay")
          .isGreaterThanOrEqualTo(60L);

      handle.stop();

      // All subsequent evaluations should NOT be delayed.
      for (int i = 0; i < 5; i++) {
        final long after =
            measureMillis(
                () -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}));
        assertThat(after).as("call %d after stop() must not be delayed", i + 1).isLessThan(50L);
      }
    }
  }

  @Nested
  @DisplayName("concurrent stop() and evaluate()")
  class ConcurrentStopAndEvaluate {

    private static final int THREAD_COUNT = 60;
    private static final int ITERATIONS_PER_THREAD = 10;

    @Test
    @DisplayName(
        "no unexpected exception when stop() races with evaluate() across "
            + THREAD_COUNT
            + " threads")
    void noExceptionWhenStopRacesWithEvaluate() throws Exception {
      final ChaosRuntime runtime = new ChaosRuntime();

      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder("concurrent-stop")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                  .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                  .activationPolicy(ActivationPolicy.always())
                  .build());

      final CountDownLatch startLatch = new CountDownLatch(1);
      final AtomicInteger unexpectedErrors = new AtomicInteger(0);
      final AtomicInteger successfulEvals = new AtomicInteger(0);

      final var executor = Executors.newFixedThreadPool(THREAD_COUNT + 1);
      try {
        // Evaluation threads
        for (int i = 0; i < THREAD_COUNT; i++) {
          executor.submit(
              () -> {
                try {
                  startLatch.await();
                } catch (final InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                for (int j = 0; j < ITERATIONS_PER_THREAD; j++) {
                  try {
                    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
                    successfulEvals.incrementAndGet();
                  } catch (final Exception e) {
                    // RejectedExecutionException from a reject effect is expected;
                    // any other exception type is a bug.
                    if (!(e instanceof java.util.concurrent.RejectedExecutionException)) {
                      unexpectedErrors.incrementAndGet();
                    }
                  }
                }
              });
        }

        // Stop thread: calls stop() after a brief head start for evaluations
        executor.submit(
            () -> {
              try {
                startLatch.await();
                Thread.sleep(2); // Let evaluations begin
              } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }
              handle.stop();
            });

        startLatch.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
      } finally {
        executor.shutdownNow();
      }

      assertThat(unexpectedErrors.get())
          .as(
              "no unexpected exception type should occur when stop() races with evaluate(). "
                  + "Got %d unexpected errors, %d successful evals",
              unexpectedErrors.get(), successfulEvals.get())
          .isZero();

      assertThat(handle.state())
          .as("scenario must be STOPPED after the stop thread completes")
          .isEqualTo(ChaosDiagnostics.ScenarioState.STOPPED);
    }

    @Test
    @DisplayName("state monotonically advances toward STOPPED under concurrent reads")
    void stateMonotonicallyAdvancesUnderConcurrentReads() throws Exception {
      final ChaosRuntime runtime = new ChaosRuntime();

      final ChaosActivationHandle handle =
          runtime.activate(
              ChaosScenario.builder("monotone-stop")
                  .scope(ChaosScenario.ScenarioScope.JVM)
                  .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                  .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                  .activationPolicy(ActivationPolicy.always())
                  .build());

      final CountDownLatch startLatch = new CountDownLatch(1);
      final AtomicInteger reversals = new AtomicInteger(0);

      // Reader threads: each polls state repeatedly. A reversal (STOPPED → ACTIVE) is a bug.
      final int readers = 20;
      final var executor = Executors.newFixedThreadPool(readers + 1);
      try {
        for (int i = 0; i < readers; i++) {
          executor.submit(
              () -> {
                try {
                  startLatch.await();
                } catch (final InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                ChaosDiagnostics.ScenarioState prev = null;
                for (int j = 0; j < 50; j++) {
                  final ChaosDiagnostics.ScenarioState current = handle.state();
                  // Once STOPPED is observed, it must never revert to anything else.
                  if (prev == ChaosDiagnostics.ScenarioState.STOPPED
                      && current != ChaosDiagnostics.ScenarioState.STOPPED) {
                    reversals.incrementAndGet();
                  }
                  prev = current;
                  Thread.yield();
                }
              });
        }

        executor.submit(
            () -> {
              try {
                startLatch.await();
                Thread.sleep(5);
              } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }
              handle.stop();
            });

        startLatch.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      } finally {
        executor.shutdownNow();
      }

      assertThat(reversals.get())
          .as("ScenarioState must never revert after STOPPED is observed")
          .isZero();
    }
  }

  private static long measureMillis(final Runnable runnable) {
    final long start = System.nanoTime();
    runnable.run();
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }
}
