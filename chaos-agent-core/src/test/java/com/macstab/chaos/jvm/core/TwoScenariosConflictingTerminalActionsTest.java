package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that when two scenarios match the same operation and both carry terminal actions, the
 * precedence merge rule is applied correctly and remains consistent under concurrent access.
 *
 * <h2>Merge semantics (from {@code DispatchEngine.evaluate})</h2>
 *
 * <ul>
 *   <li>Delays accumulate — both scenarios' delay contributions are summed.
 *   <li>Terminal action — the scenario with the <em>higher</em> {@link ChaosScenario#precedence()}
 *       wins; on equal precedence the smaller scenario id wins. Exactly one terminal action is
 *       applied — never zero, never two.
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
      try (ChaosRuntime runtime = new ChaosRuntime()) {

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
        assertThatThrownBy(
                () -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}))
            .isInstanceOf(java.util.concurrent.RejectedExecutionException.class)
            .hasMessageContaining("forced-rejection");
      }
    }

    @Test
    @DisplayName("delays from both matching scenarios accumulate regardless of precedence")
    void delaysAccumulateAcrossBothScenarios() {
      try (ChaosRuntime runtime = new ChaosRuntime()) {

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
            measureMillis(
                () -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}));

        assertThat(elapsed)
            .as("two %dms delay scenarios must accumulate to at least %dms", DELAY_MS, 2 * DELAY_MS)
            .isGreaterThanOrEqualTo(2 * DELAY_MS);
        assertThat(applied(runtime)).as("the scenario applied").isPositive();
      }
    }

    @Test
    @DisplayName(
        "higher-precedence SUPPRESS wins over a lower-precedence REJECT registered after it")
    void higherPrecedenceSuppressWinsOverLowerPrecedenceReject() {
      try (ChaosRuntime runtime = new ChaosRuntime()) {

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

        // High-precedence suppress wins: the call returns without throwing, and the task it
        // returns in place of the submitted one never runs it.
        final AtomicBoolean ran = new AtomicBoolean();
        final Runnable task = () -> ran.set(true);
        final AtomicReference<Runnable> decorated = new AtomicReference<>();
        assertThatCode(
                () ->
                    decorated.set(runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, task)))
            .doesNotThrowAnyException();
        assertThat(decorated.get()).isNotSameAs(task);
        decorated.get().run();
        assertThat(ran).as("the suppressed task runs").isFalse();
      }
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
      try (ChaosRuntime runtime = new ChaosRuntime()) {

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
        final List<Future<?>> futures = new ArrayList<>();
        final var executor = Executors.newFixedThreadPool(THREAD_COUNT);
        try {
          for (int i = 0; i < THREAD_COUNT; i++) {
            futures.add(
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
                          // Suppress: returns a no-op runnable without throwing
                          suppressions.incrementAndGet();
                        } catch (final java.util.concurrent.RejectedExecutionException e) {
                          rejections.incrementAndGet();
                        } catch (final Exception e) {
                          neitherCount.incrementAndGet();
                        }
                      }
                    }));
          }
          startLatch.countDown();
          executor.shutdown();
          assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
          // A worker that failed reports it here, not in a future nobody reads.
          for (final Future<?> future : futures) {
            future.get(15, TimeUnit.SECONDS);
          }
        } finally {
          executor.shutdownNow();
        }

        final int total = rejections.get() + suppressions.get() + neitherCount.get();
        final int expected = THREAD_COUNT * ITERATIONS_PER_THREAD;

        assertThat(neitherCount.get())
            .as("no invocation should produce an unexpected exception type")
            .isZero();
        assertThat(total)
            .as("every invocation must produce exactly one outcome")
            .isEqualTo(expected);
        assertThat(rejections.get())
            .as("high-precedence REJECT must always win — all %d calls must throw", expected)
            .isEqualTo(expected);
      }
    }
  }

  private static long measureMillis(final Runnable runnable) {
    final long start = System.nanoTime();
    runnable.run();
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }

  /** How often the runtime's scenarios applied, all together. */
  private static long applied(final ChaosRuntime runtime) {
    return runtime.diagnostics().snapshot().scenarios().stream()
        .mapToLong(com.macstab.chaos.jvm.api.ChaosDiagnostics.ScenarioReport::appliedCount)
        .sum();
  }
}
