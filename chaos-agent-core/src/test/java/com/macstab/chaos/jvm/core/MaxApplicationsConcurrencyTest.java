package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosMetricsSink;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.nio.channels.Selector;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the CAS loop in {@link ScenarioController} prevents {@code appliedCount} from
 * exceeding {@code maxApplications} under concurrent evaluation.
 *
 * <p>Without the CAS loop, multiple threads could simultaneously read the counter below the cap,
 * both increment it, and both receive a non-null contribution — overshooting the cap.
 */
@DisplayName("maxApplications CAS correctness under concurrency")
@SuppressWarnings("removal") // ChaosMetricsSink, deprecated, until it is removed
class MaxApplicationsConcurrencyTest {

  private static final int MAX_APPLICATIONS = 5;
  private static final int THREAD_COUNT = 50;

  @Test
  @DisplayName("applied count never exceeds maxApplications under concurrent evaluation")
  void appliedCountNeverExceedsMaxApplicationsUnderConcurrency() throws Exception {
    try (ChaosRuntime runtime = new ChaosRuntime()) {
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
                      null,
                      false))
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
                    // Each thread triggers one evaluation; if a non-no-op runnable is returned the
                    // effect was applied. We measure applied count via diagnostics after the run.
                    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
                  }));
        }
        startLatch.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        // A worker that failed reports it here, not in a future nobody reads.
        for (final Future<?> future : futures) {
          future.get(15, TimeUnit.SECONDS);
        }
      } finally {
        executor.shutdownNow();
      }

      final long appliedCount =
          runtime.diagnostics().snapshot().scenarios().stream()
              .filter(r -> "cas-test".equals(r.id()))
              .mapToLong(ChaosDiagnostics.ScenarioReport::appliedCount)
              .sum();

      // 50 threads compete for 5 applications: exactly the budget is spent, never more, and the
      // reservations of the losers never leave it short.
      assertThat(appliedCount)
          .as("applied count must be exactly maxApplications")
          .isEqualTo(MAX_APPLICATIONS);
    }
  }

  @Nested
  @DisplayName("a candidate that throws")
  class ThrowingCandidate {

    @Test
    @DisplayName("returns the reservations of the candidates evaluated before it")
    void throwingCandidateReturnsEarlierReservations() {
      final ScenarioRegistry registry = new ScenarioRegistry(Clock.systemUTC(), Map::of);
      // Evaluated first (id order): one application allowed, and its clock is never read.
      final ScenarioController capped =
          controller(
              "a-capped",
              ActivationPolicy.builder().maxApplications(1L).build(),
              Clock.systemUTC());
      // Evaluated second: its time bound makes evaluate() read the clock, which fails when armed.
      final ArmableClock failingClock = new ArmableClock();
      final ScenarioController failing =
          controller(
              "b-failing",
              ActivationPolicy.builder().activeFor(Duration.ofHours(1)).build(),
              failingClock);
      registry.register(capped);
      registry.register(failing);
      capped.start();
      failing.start();
      final InvocationContext context =
          new InvocationContext(
              OperationType.EXECUTOR_SUBMIT, "executor", "task", null, false, null, null, null);

      failingClock.armed = true;
      assertThatThrownBy(() -> registry.match(context))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("clock failure");
      failingClock.armed = false;

      final List<ScenarioContribution> contributions = registry.match(context);
      assertThat(contributions)
          .as("the capped scenario's reservation was returned when the later candidate threw")
          .extracting(contribution -> contribution.scenario().id())
          .containsExactly("a-capped", "b-failing");
      contributions.forEach(ScenarioContribution::commit);
      assertThat(capped.appliedCount()).isEqualTo(1L);
    }

    private ScenarioController controller(
        final String id, final ActivationPolicy policy, final Clock clock) {
      return new ScenarioController(
          ChaosScenario.builder(id)
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ofMillis(1)))
              .activationPolicy(policy)
              .build(),
          "jvm",
          null,
          clock,
          new ObservabilityBus(ChaosMetricsSink.NOOP),
          Optional::empty);
    }
  }

  /** A system clock whose {@code instant()} throws while the test has armed it. */
  private static final class ArmableClock extends Clock {
    private volatile boolean armed;

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      if (armed) {
        throw new IllegalStateException("clock failure");
      }
      return Instant.now();
    }
  }

  static final class ConcurrentThreadLocal extends ThreadLocal<String> {}

  @Nested
  @DisplayName("ThreadLocal concurrent access")
  class ThreadLocalConcurrency {

    @Test
    @DisplayName(
        "100 threads suppressing ThreadLocal.get(): every call is suppressed, without a"
            + " StackOverflowError")
    void everyConcurrentThreadLocalGetIsSuppressed() throws Exception {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        final int threadCount = 100;
        final int opsPerThread = 50;
        final ChaosActivationHandle handle =
            runtime.activate(
                ChaosScenario.builder("tl-concurrency")
                    .scope(ChaosScenario.ScenarioScope.JVM)
                    .selector(
                        ChaosSelector.threadLocal(
                            Set.of(OperationType.THREAD_LOCAL_GET),
                            NamePattern.prefix(
                                "com.macstab.chaos.jvm.core.MaxApplicationsConcurrencyTest")))
                    .effect(ChaosEffect.suppress())
                    .activationPolicy(ActivationPolicy.always())
                    .build());

        final CountDownLatch ready = new CountDownLatch(threadCount);
        final CountDownLatch go = new CountDownLatch(1);
        final AtomicInteger errors = new AtomicInteger(0);
        final AtomicInteger stackOverflows = new AtomicInteger(0);
        final AtomicInteger suppressed = new AtomicInteger(0);
        final List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
          final int threadId = i;
          threads.add(
              Thread.ofPlatform()
                  .start(
                      () -> {
                        final ConcurrentThreadLocal tl = new ConcurrentThreadLocal();
                        tl.set("thread-" + threadId);
                        ready.countDown();
                        try {
                          go.await();
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                        try {
                          for (int op = 0; op < opsPerThread; op++) {
                            if (runtime.beforeThreadLocalGet(tl)) {
                              suppressed.incrementAndGet();
                            }
                            tl.set("v-" + op);
                          }
                        } catch (StackOverflowError soe) {
                          stackOverflows.incrementAndGet();
                        } catch (Throwable e) {
                          errors.incrementAndGet();
                        }
                      }));
        }

        ready.await();
        go.countDown();
        for (final Thread t : threads) {
          t.join(5000);
          assertThat(t.isAlive()).as("%s finished", t.getName()).isFalse();
        }

        try {
          assertThat(stackOverflows.get())
              .as("reentrancy fix must hold under concurrency — no StackOverflowError")
              .isZero();
          assertThat(errors.get()).as("no unexpected exceptions").isZero();
          assertThat(suppressed)
              .as("every get() is suppressed")
              .hasValue(threadCount * opsPerThread);
        } finally {
          handle.stop();
        }
      }
    }
  }

  @Nested
  @DisplayName("NIO Selector concurrent spurious wakeup")
  class NioSelectorConcurrency {

    @Test
    @DisplayName("10 concurrent Selectors all receive spurious wakeup simultaneously")
    void tenConcurrentSelectorsAllReceiveSpuriousWakeup() throws Exception {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        final int selectorCount = 10;
        final ChaosActivationHandle handle =
            runtime.activate(
                ChaosScenario.builder("nio-concurrency")
                    .selector(ChaosSelector.nio(Set.of(OperationType.NIO_SELECTOR_SELECT)))
                    .effect(ChaosEffect.suppress())
                    .activationPolicy(ActivationPolicy.always())
                    .build());

        final CountDownLatch ready = new CountDownLatch(selectorCount);
        final CountDownLatch go = new CountDownLatch(1);
        final AtomicInteger spuriousCount = new AtomicInteger(0);
        final AtomicInteger errors = new AtomicInteger(0);
        final List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < selectorCount; i++) {
          threads.add(
              Thread.ofPlatform()
                  .start(
                      () -> {
                        try (final Selector selector = Selector.open()) {
                          ready.countDown();
                          go.await();
                          try {
                            if (runtime.beforeNioSelect(selector, 5000L)) {
                              spuriousCount.incrementAndGet();
                            }
                          } catch (Throwable t) {
                            errors.incrementAndGet();
                          }
                        } catch (Exception e) {
                          errors.incrementAndGet();
                        }
                      }));
        }

        ready.await();
        go.countDown();
        for (final Thread t : threads) {
          t.join(6000);
        }

        try {
          assertThat(errors.get()).as("no errors in selector threads").isZero();
          assertThat(spuriousCount.get())
              .as("all selectors should receive spurious wakeup")
              .isEqualTo(selectorCount);
        } finally {
          handle.stop();
        }
      }
    }
  }
}
