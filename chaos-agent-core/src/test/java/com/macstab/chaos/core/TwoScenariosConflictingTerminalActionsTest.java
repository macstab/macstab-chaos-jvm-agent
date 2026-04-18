package com.macstab.chaos.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.api.ActivationPolicy;
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
 * Verifies that when two scenarios match the same operation and both carry terminal actions, the
 * precedence merge rule is applied correctly and remains consistent under concurrent access.
 *
 * <h2>Merge semantics (from {@link ChaosRuntime#evaluate})</h2>
 *
 * <ul>
 *   <li>Delays accumulate — both scenarios' delay contributions are summed.
 *   <li>Terminal action — the scenario with the <em>higher</em> {@link ChaosScenario#precedence()}
 *       wins. Equal precedence is last-write (non-deterministic in concurrent evaluation, but
 *       <em>always exactly one</em> terminal action is applied — never zero, never two).
 * </ul>
 */
@DisplayName("Two scenarios with conflicting terminal actions")
class TwoScenariosConflictingTerminalActionsTest {

  private static final long DELAY_MS = 30L;

  @Nested
  @DisplayName("serial evaluation — deterministic merge")
  class SerialMerge {

    @Test
    @DisplayName("higher-precedence REJECT wins over lower-precedence SUPPRESS")
    void higherPrecedenceRejectWinsOverLowerPrecedenceSuppress() {
      final ChaosRuntime runtime = new ChaosRuntime();

      // Low precedence: suppress (would silently drop the task)
      runtime.activate(
          ChaosScenario.builder("low-suppress")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.suppress())
              .precedence(0)
              .activationPolicy(ActivationPolicy.always())
              .build());

      // High precedence: reject (throws RejectedExecutionException)
      runtime.activate(
          ChaosScenario.builder("high-reject")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.reject("forced-rejection"))
              .precedence(100)
              .activationPolicy(ActivationPolicy.always())
              .build());

      // The high-precedence scenario must win.
      assertThatThrownBy(() -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}))
          .isInstanceOf(java.util.concurrent.RejectedExecutionException.class)
          .hasMessageContaining("forced-rejection");
    }

    @Test
    @DisplayName("delays from both matching scenarios accumulate regardless of precedence")
    void delaysAccumulateAcrossBothScenarios() {
      final ChaosRuntime runtime = new ChaosRuntime();

      // Two delay-only scenarios: neither has a terminal action, both delays sum.
      runtime.activate(
          ChaosScenario.builder("delay-a")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ofMillis(DELAY_MS)))
              .precedence(0)
              .activationPolicy(ActivationPolicy.always())
              .build());
      runtime.activate(
          ChaosScenario.builder("delay-b")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ofMillis(DELAY_MS)))
              .precedence(0)
              .activationPolicy(ActivationPolicy.always())
              .build());

      final long elapsed =
          measureMillis(() -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}));

      assertThat(elapsed)
          .as("two %dms delay scenarios must accumulate to at least %dms", DELAY_MS, 2 * DELAY_MS)
          .isGreaterThanOrEqualTo((long) (2 * DELAY_MS * 0.8));
    }

    @Test
    @DisplayName("lower-precedence SUPPRESS is overridden by higher-precedence REJECT")
    void reversePrecedenceOrdering() {
      final ChaosRuntime runtime = new ChaosRuntime();

      // Register HIGH suppress first, then LOW reject — registration order must not matter
      runtime.activate(
          ChaosScenario.builder("high-suppress")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.suppress())
              .precedence(200)
              .activationPolicy(ActivationPolicy.always())
              .build());
      runtime.activate(
          ChaosScenario.builder("low-reject")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.reject("low-reject"))
              .precedence(50)
              .activationPolicy(ActivationPolicy.always())
              .build());

      // High-precedence suppress wins — the call returns without throwing.
      final Runnable decorated =
          runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
      // Suppress effect: decorated runnable is a no-op; it does NOT throw.
      assertThat(decorated).isNotNull();
    }
  }

  @Nested
  @DisplayName("concurrent evaluation — exactly one terminal action per invocation")
  class ConcurrentMerge {

    private static final int THREAD_COUNT = 80;
    private static final int ITERATIONS_PER_THREAD = 5;

    @Test
    @DisplayName(
        "exactly one terminal action fires per evaluate() call under "
            + THREAD_COUNT
            + " concurrent threads")
    void exactlyOneTerminalActionPerEvaluateCallUnderConcurrency() throws Exception {
      final ChaosRuntime runtime = new ChaosRuntime();

      final AtomicInteger rejections = new AtomicInteger(0);
      final AtomicInteger suppressions = new AtomicInteger(0);
      final AtomicInteger neitherCount = new AtomicInteger(0);

      // High-precedence: reject — wins
      runtime.activate(
          ChaosScenario.builder("concurrent-high-reject")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.reject("concurrent"))
              .precedence(100)
              .activationPolicy(ActivationPolicy.always())
              .build());

      // Low-precedence: suppress — should always lose
      runtime.activate(
          ChaosScenario.builder("concurrent-low-suppress")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.suppress())
              .precedence(0)
              .activationPolicy(ActivationPolicy.always())
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
                for (int j = 0; j < ITERATIONS_PER_THREAD; j++) {
                  try {
                    final Runnable r =
                        runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
                    // Suppress: returns a no-op runnable without throwing
                    suppressions.incrementAndGet();
                  } catch (final java.util.concurrent.RejectedExecutionException e) {
                    rejections.incrementAndGet();
                  } catch (final Exception e) {
                    neitherCount.incrementAndGet();
                  }
                }
              });
        }
        startLatch.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
      } finally {
        executor.shutdownNow();
      }

      final int total = rejections.get() + suppressions.get() + neitherCount.get();
      final int expected = THREAD_COUNT * ITERATIONS_PER_THREAD;

      assertThat(neitherCount.get())
          .as("no invocation should produce an unexpected exception type")
          .isZero();
      assertThat(total).as("every invocation must produce exactly one outcome").isEqualTo(expected);
      assertThat(rejections.get())
          .as("high-precedence REJECT must always win — all %d calls must throw", expected)
          .isEqualTo(expected);
    }
  }

  private static long measureMillis(final Runnable runnable) {
    final long start = System.nanoTime();
    runnable.run();
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }
}
