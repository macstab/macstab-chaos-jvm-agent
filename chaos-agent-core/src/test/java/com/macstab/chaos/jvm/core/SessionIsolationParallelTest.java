package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosScenario.ScenarioScope;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link ChaosSession} provides strict isolation: effects registered in one session
 * must not bleed into threads bound to a different session, and threads with no session binding
 * must remain unaffected by any session-scoped scenario.
 *
 * <h2>Invariants under test</h2>
 *
 * <ul>
 *   <li>Each session's {@code appliedCount} must equal exactly the number of in-session invocations
 *       — no more (no bleed-in from other sessions) and no fewer (no missed applications within the
 *       session).
 *   <li>Threads with no session binding are never counted by any session-scoped scenario.
 *   <li>After a session is {@link ChaosSession#close() closed}, its effect is no longer observed by
 *       any thread.
 * </ul>
 */
@DisplayName("Session isolation under parallel access")
class SessionIsolationParallelTest {

  private static final int SESSION_COUNT = 10;
  private static final int THREADS_PER_SESSION = 10;
  private static final int ITERATIONS_PER_THREAD = 5;
  private static final long EXPECTED_PER_SESSION =
      (long) THREADS_PER_SESSION * ITERATIONS_PER_THREAD;

  @Nested
  @DisplayName("per-session appliedCount correctness")
  class PerSessionAppliedCount {

    @Test
    @DisplayName(
        SESSION_COUNT
            + " concurrent sessions × "
            + THREADS_PER_SESSION
            + " threads × "
            + ITERATIONS_PER_THREAD
            + " iterations — each session accumulates exactly "
            + EXPECTED_PER_SESSION
            + " applications")
    void eachSessionAccumulatesExactlyItsOwnApplications() throws Exception {
      final ChaosRuntime runtime = new ChaosRuntime();

      final List<ChaosSession> sessions = new ArrayList<>(SESSION_COUNT);
      for (int s = 0; s < SESSION_COUNT; s++) {
        final ChaosSession session = runtime.openSession("session-" + s);
        session.activate(
            ChaosScenario.builder("scenario-" + s)
                .scope(ScenarioScope.SESSION)
                .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                .effect(ChaosEffect.delay(Duration.ZERO))
                .activationPolicy(ActivationPolicy.always())
                .build());
        sessions.add(session);
      }

      final CountDownLatch startLatch = new CountDownLatch(1);
      final var executor = Executors.newFixedThreadPool(SESSION_COUNT * THREADS_PER_SESSION);
      final List<Future<?>> futures = new ArrayList<>();

      try {
        for (int s = 0; s < SESSION_COUNT; s++) {
          final ChaosSession session = sessions.get(s);
          for (int t = 0; t < THREADS_PER_SESSION; t++) {
            futures.add(
                executor.submit(
                    session.wrap(
                        () -> {
                          try {
                            startLatch.await();
                          } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                          }
                          for (int i = 0; i < ITERATIONS_PER_THREAD; i++) {
                            runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
                          }
                        })));
          }
        }

        startLatch.countDown();
        for (final Future<?> f : futures) {
          f.get(15, TimeUnit.SECONDS);
        }
      } finally {
        executor.shutdownNow();
      }

      // Capture counts while scenarios are still registered, before closing sessions.
      final Map<String, Long> countsByScenario = new HashMap<>();
      for (int s = 0; s < SESSION_COUNT; s++) {
        final String scenarioId = "scenario-" + s;
        final long applied =
            runtime.diagnostics().snapshot().scenarios().stream()
                .filter(r -> scenarioId.equals(r.id()))
                .mapToLong(ChaosDiagnostics.ScenarioReport::appliedCount)
                .sum();
        countsByScenario.put(scenarioId, applied);
      }

      for (final ChaosSession s : sessions) {
        s.close();
      }

      for (int s = 0; s < SESSION_COUNT; s++) {
        final String scenarioId = "scenario-" + s;
        final long applied = countsByScenario.get(scenarioId);

        assertThat(applied)
            .as(
                "session %d scenario '%s' must have exactly %d applications — "
                    + "more means bleed-in from other sessions, fewer means missed applications",
                s, scenarioId, EXPECTED_PER_SESSION)
            .isEqualTo(EXPECTED_PER_SESSION);
      }
    }
  }

  @Nested
  @DisplayName("unbound threads are invisible to session-scoped scenarios")
  class UnboundThreadIsolation {

    @Test
    @DisplayName("threads without session binding leave all session appliedCounts at zero")
    void unboundThreadsDoNotTriggerSessionScenarios() throws Exception {
      final ChaosRuntime runtime = new ChaosRuntime();

      final List<ChaosSession> sessions = new ArrayList<>(SESSION_COUNT);
      for (int s = 0; s < SESSION_COUNT; s++) {
        final ChaosSession session = runtime.openSession("unbound-session-" + s);
        session.activate(
            ChaosScenario.builder("unbound-scenario-" + s)
                .scope(ScenarioScope.SESSION)
                .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                .effect(ChaosEffect.delay(Duration.ZERO))
                .activationPolicy(ActivationPolicy.always())
                .build());
        sessions.add(session);
      }

      final int unboundThreads = 50;
      final CountDownLatch startLatch = new CountDownLatch(1);
      final var executor = Executors.newFixedThreadPool(unboundThreads);
      try {
        for (int t = 0; t < unboundThreads; t++) {
          executor.submit(
              () -> {
                try {
                  startLatch.await();
                } catch (final InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                // No session.wrap() — this thread is unbound
                for (int i = 0; i < ITERATIONS_PER_THREAD; i++) {
                  runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
                }
              });
        }
        startLatch.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
      } finally {
        executor.shutdownNow();
      }

      // Capture counts while sessions are still open.
      final Map<String, Long> counts = new HashMap<>();
      for (int s = 0; s < SESSION_COUNT; s++) {
        final String scenarioId = "unbound-scenario-" + s;
        final long applied =
            runtime.diagnostics().snapshot().scenarios().stream()
                .filter(r -> scenarioId.equals(r.id()))
                .mapToLong(ChaosDiagnostics.ScenarioReport::appliedCount)
                .sum();
        counts.put(scenarioId, applied);
      }

      for (final ChaosSession s : sessions) {
        s.close();
      }

      for (int s = 0; s < SESSION_COUNT; s++) {
        final String scenarioId = "unbound-scenario-" + s;
        assertThat(counts.get(scenarioId))
            .as(
                "session-scoped scenario '%s' must record zero applications "
                    + "when all threads are unbound (no session.wrap() used)",
                scenarioId)
            .isZero();
      }
    }
  }

  @Nested
  @DisplayName("post-close isolation")
  class PostCloseIsolation {

    @Test
    @DisplayName("effects applied before close() are observed; after close(), the effect stops")
    void effectObservedBeforeCloseAndAbsentAfterClose() {
      final ChaosRuntime runtime = new ChaosRuntime();
      final ChaosSession session = runtime.openSession("close-test");
      session.activate(
          ChaosScenario.builder("close-scenario")
              .scope(ScenarioScope.SESSION)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ofMillis(80)))
              .activationPolicy(ActivationPolicy.always())
              .build());

      // Pre-close: the opening thread is automatically bound (root binding in constructor).
      final long beforeClose =
          measureMillis(() -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}));
      assertThat(beforeClose)
          .as("call before close() must experience the session delay")
          .isGreaterThanOrEqualTo(60L);

      session.close();

      // Post-close: session is gone; thread no longer bound; effect must not apply.
      for (int i = 0; i < 5; i++) {
        final long afterClose =
            measureMillis(
                () -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}));
        assertThat(afterClose)
            .as("call %d after close() must not be delayed", i + 1)
            .isLessThan(50L);
      }
    }
  }

  private static long measureMillis(final Runnable runnable) {
    final long start = System.nanoTime();
    runnable.run();
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }
}
