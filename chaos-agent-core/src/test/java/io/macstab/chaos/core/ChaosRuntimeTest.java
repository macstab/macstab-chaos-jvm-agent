package io.macstab.chaos.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.macstab.chaos.api.ActivationPolicy;
import io.macstab.chaos.api.ChaosActivationHandle;
import io.macstab.chaos.api.ChaosEffect;
import io.macstab.chaos.api.ChaosEvent;
import io.macstab.chaos.api.ChaosScenario;
import io.macstab.chaos.api.ChaosSelector;
import io.macstab.chaos.api.ChaosDiagnostics;
import io.macstab.chaos.api.ChaosValidationException;
import io.macstab.chaos.api.OperationType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ChaosRuntimeTest {
  @Test
  void sessionScopedExecutorDelayDoesNotBleedAcrossSessions() {
    ChaosRuntime runtime = new ChaosRuntime();
    try (var delayedSession = runtime.openSession("delayed");
        var plainSession = runtime.openSession("plain")) {
      delayedSession.activate(
          ChaosScenario.builder("session-delay")
              .scope(ChaosScenario.ScenarioScope.SESSION)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ofMillis(80)))
              .activationPolicy(ActivationPolicy.always())
              .build());

      long delayedMillis =
          measureMillis(
              () ->
                  delayedSession
                      .wrap(
                          () -> {
                            runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
                          })
                      .run());
      long plainMillis =
          measureMillis(
              () ->
                  plainSession
                      .wrap(
                          () -> {
                            runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
                          })
                      .run());

      assertTrue(delayedMillis >= 60, "expected delayed session to incur submit delay");
      assertTrue(plainMillis < 40, "expected plain session to avoid delayed submit");
    }
  }

  @Test
  void jvmGlobalDelayCompositionAccumulates() {
    ChaosRuntime runtime = new ChaosRuntime();
    runtime.activate(
        ChaosScenario.builder("slow-a")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.delay(Duration.ofMillis(30)))
            .activationPolicy(ActivationPolicy.always())
            .precedence(10)
            .build());
    runtime.activate(
        ChaosScenario.builder("slow-b")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.delay(Duration.ofMillis(20)))
            .activationPolicy(ActivationPolicy.always())
            .precedence(5)
            .build());

    long elapsed =
        measureMillis(
            () -> {
              runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
            });
    assertTrue(elapsed >= 45, "expected composed delay across matching scenarios");
  }

  @Test
  void gateEffectBlocksUntilRelease() throws Exception {
    ChaosRuntime runtime = new ChaosRuntime();
    var handle =
        runtime.activate(
            ChaosScenario.builder("gate")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_WORKER_RUN)))
                .effect(ChaosEffect.gate(null))
                .activationPolicy(ActivationPolicy.always())
                .build());

    CompletableFuture<Void> future = new CompletableFuture<>();
    var executor = Executors.newSingleThreadExecutor();
    try {
      executor.submit(
          () -> {
            try {
              runtime.beforeWorkerRun(this, Thread.currentThread(), () -> {});
              future.complete(null);
            } catch (Throwable throwable) {
              future.completeExceptionally(throwable);
            }
          });

      assertThrows(
          java.util.concurrent.TimeoutException.class,
          () -> future.get(150, TimeUnit.MILLISECONDS));
      handle.release();
      assertDoesNotThrow(() -> future.get(1, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void sessionScopedThreadChaosIsRejected() {
    ChaosRuntime runtime = new ChaosRuntime();
    try (var session = runtime.openSession("invalid")) {
      assertThrows(
          ChaosValidationException.class,
          () ->
              session.activate(
                  ChaosScenario.builder("thread")
                      .scope(ChaosScenario.ScenarioScope.SESSION)
                      .selector(
                          ChaosSelector.thread(
                              Set.of(OperationType.THREAD_START), ChaosSelector.ThreadKind.ANY))
                      .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                      .activationPolicy(ActivationPolicy.always())
                      .build()));
    }
  }

  // ── new tests ─────────────────────────────────────────────────────────────

  @Test
  void rejectEffectThrowsOnExecutorSubmit() {
    ChaosRuntime runtime = new ChaosRuntime();
    runtime.activate(
        ChaosScenario.builder("reject-submit")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.reject("executor overloaded"))
            .activationPolicy(ActivationPolicy.always())
            .build());

    assertThrows(
        RejectedExecutionException.class,
        () -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}));
  }

  @Test
  void suppressEffectOnQueueOfferReturnsFalse() throws Throwable {
    ChaosRuntime runtime = new ChaosRuntime();
    runtime.activate(
        ChaosScenario.builder("suppress-offer")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.queue(Set.of(OperationType.QUEUE_OFFER)))
            .effect(ChaosEffect.suppress())
            .activationPolicy(ActivationPolicy.always())
            .build());

    Boolean result = runtime.beforeBooleanQueueOperation("QUEUE_OFFER", new Object());
    assertTrue(Boolean.FALSE.equals(result),
        "expected suppress to return false for QUEUE_OFFER");
  }

  @Test
  void stopHaltsEffectApplication() {
    ChaosRuntime runtime = new ChaosRuntime();
    ChaosActivationHandle handle =
        runtime.activate(
            ChaosScenario.builder("stoppable")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                .effect(ChaosEffect.delay(Duration.ofMillis(80)))
                .activationPolicy(ActivationPolicy.always())
                .build());

    // Stop the scenario
    handle.stop();

    // After stop, the effect should not fire
    long elapsed = measureMillis(
        () -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}));
    assertTrue(elapsed < 50,
        "expected no delay after stop(), but elapsed was " + elapsed + "ms");
  }

  @Test
  void diagnosticsSnapshotReflectsAppliedCount() {
    ChaosRuntime runtime = new ChaosRuntime();
    runtime.activate(
        ChaosScenario.builder("count-a")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.delay(Duration.ofMillis(1)))
            .activationPolicy(ActivationPolicy.always())
            .build());
    runtime.activate(
        ChaosScenario.builder("count-b")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_WORKER_RUN)))
            .effect(ChaosEffect.delay(Duration.ofMillis(1)))
            .activationPolicy(ActivationPolicy.always())
            .build());

    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});

    ChaosDiagnostics.Snapshot snapshot = runtime.diagnostics().snapshot();
    long totalApplied = snapshot.scenarios().stream()
        .mapToLong(ChaosDiagnostics.ScenarioReport::appliedCount)
        .sum();
    assertTrue(totalApplied >= 1, "expected at least 1 applied effect in snapshot");
  }

  @Test
  void eventListenerReceivesAppliedEvent() {
    ChaosRuntime runtime = new ChaosRuntime();
    List<ChaosEvent> events = new ArrayList<>();
    runtime.addEventListener(events::add);

    runtime.activate(
        ChaosScenario.builder("event-test")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.delay(Duration.ofMillis(1)))
            .activationPolicy(ActivationPolicy.always())
            .build());

    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});

    ChaosEvent appliedEvent = events.stream()
        .filter(e -> e.type() == ChaosEvent.Type.APPLIED
            && "event-test".equals(e.scenarioId()))
        .findFirst()
        .orElse(null);

    assertTrue(appliedEvent != null, "expected APPLIED event for event-test");
    assertEquals(ChaosEvent.Type.APPLIED, appliedEvent.type());
    assertEquals("event-test", appliedEvent.scenarioId());
  }

  private long measureMillis(ThrowingRunnable runnable) {
    long start = System.nanoTime();
    try {
      runnable.run();
    } catch (Throwable throwable) {
      throw new IllegalStateException(throwable);
    }
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Throwable;
  }
}
