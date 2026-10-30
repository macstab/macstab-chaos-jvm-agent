package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Session-scoped scenarios on operations whose dispatch used to ignore the session. */
@DisplayName("Session scoping")
class SessionPropagationTest {

  private ChaosRuntime runtime;
  private ChaosSession session;

  @BeforeEach
  void setUp() {
    runtime = new ChaosRuntime();
    session = runtime.openSession("propagation");
  }

  @AfterEach
  void tearDown() {
    session.close();
    runtime.close();
  }

  @Test
  @DisplayName("EXECUTOR_WORKER_RUN matches a task submitted from the session")
  void workerRunMatchesTaskFromSession() throws Exception {
    // Arrange
    session.activate(
        sessionScenario(
            "worker-run",
            ChaosSelector.executor(Set.of(OperationType.EXECUTOR_WORKER_RUN)),
            ChaosEffect.delay(Duration.ofMillis(1))));
    final Runnable submitted;
    try (ChaosSession.ScopeBinding binding = session.bind()) {
      submitted = runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
    }

    // Act: the worker thread is not bound; the session travels with the task.
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final Thread worker =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    runtime.beforeWorkerRun(this, Thread.currentThread(), submitted);
                  } catch (final Throwable throwable) {
                    failure.set(throwable);
                  }
                });
    worker.join();

    // Assert
    assertThat(failure.get()).isNull();
    assertThat(runtime.diagnostics().scenario("worker-run").orElseThrow().matchedCount())
        .isEqualTo(1L);
  }

  @Test
  @DisplayName("a FutureTask submitted from a session is still a Future the caller can cancel")
  void sessionWrappedFutureStaysAFuture() {
    final java.util.concurrent.FutureTask<String> task =
        new java.util.concurrent.FutureTask<>(() -> "result");

    final Runnable queued = runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, task);

    assertThat(queued).isInstanceOf(java.util.concurrent.Future.class);
    assertThat(((java.util.concurrent.Future<?>) queued).cancel(true)).isTrue();
    assertThat(task.isCancelled()).as("cancelling the wrapper cancels the submitted task").isTrue();
  }

  @Test
  @DisplayName("a session-scoped clock skew applies only on threads bound to the session")
  void sessionClockSkewDoesNotLeak() throws Exception {
    // Arrange
    session.activate(
        sessionScenario(
            "skew",
            ChaosSelector.jvmRuntime(Set.of(OperationType.INSTANT_NOW)),
            ChaosEffect.skewClock(Duration.ofHours(1), ChaosEffect.ClockSkewMode.FIXED)));
    final Instant real = Instant.parse("2026-01-01T00:00:00Z");

    // Act: openSession() binds the creating thread, so the unbound read runs on another thread.
    final AtomicReference<Instant> unboundResult = new AtomicReference<>();
    final Thread other =
        Thread.ofPlatform().start(() -> unboundResult.set(runtime.adjustInstantNow(real)));
    other.join();
    final Instant unbound = unboundResult.get();
    final Instant bound = runtime.adjustInstantNow(real);

    // Assert
    assertThat(unbound).as("threads outside the session are not skewed").isEqualTo(real);
    assertThat(bound).isEqualTo(real.plus(Duration.ofHours(1)));
  }

  @Test
  @DisplayName("SYSTEM_GC_REQUEST matches a call made from the session")
  void gcRequestMatchesInSession() throws Throwable {
    session.activate(
        sessionScenario(
            "gc",
            ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_GC_REQUEST)),
            ChaosEffect.suppress()));

    // openSession() binds the creating thread; the call outside the session runs elsewhere.
    final AtomicReference<Boolean> outside = new AtomicReference<>();
    final Thread other =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    outside.set(runtime.beforeGcRequest());
                  } catch (final Throwable throwable) {
                    throw new IllegalStateException(throwable);
                  }
                });
    other.join();
    final boolean suppressedOutside = outside.get();
    final boolean suppressedInside = runtime.beforeGcRequest();

    assertThat(suppressedOutside).isFalse();
    assertThat(suppressedInside).isTrue();
  }

  private static ChaosScenario sessionScenario(
      final String id, final ChaosSelector selector, final ChaosEffect effect) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.SESSION)
        .selector(selector)
        .effect(effect)
        .activationPolicy(ActivationPolicy.always())
        .build();
  }
}
