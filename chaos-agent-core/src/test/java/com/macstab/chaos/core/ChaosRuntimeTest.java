package com.macstab.chaos.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.api.ActivationPolicy;
import com.macstab.chaos.api.ChaosActivationHandle;
import com.macstab.chaos.api.ChaosDiagnostics;
import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.ChaosEvent;
import com.macstab.chaos.api.ChaosScenario;
import com.macstab.chaos.api.ChaosSelector;
import com.macstab.chaos.api.ChaosValidationException;
import com.macstab.chaos.api.OperationType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ChaosRuntime")
class ChaosRuntimeTest {

  @Nested
  @DisplayName("session scope isolation")
  class SessionScopeIsolation {

    @Test
    @DisplayName("SESSION-scoped executor delay does not bleed across sessions")
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

        assertThat(delayedMillis).isGreaterThanOrEqualTo(60);
        assertThat(plainMillis).isLessThan(40);
      }
    }

    @Test
    @DisplayName("SESSION-scoped thread chaos is rejected")
    void sessionScopedThreadChaosIsRejected() {
      ChaosRuntime runtime = new ChaosRuntime();
      try (var session = runtime.openSession("invalid")) {
        assertThatThrownBy(
                () ->
                    session.activate(
                        ChaosScenario.builder("thread")
                            .scope(ChaosScenario.ScenarioScope.SESSION)
                            .selector(
                                ChaosSelector.thread(
                                    Set.of(OperationType.THREAD_START),
                                    ChaosSelector.ThreadKind.ANY))
                            .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                            .activationPolicy(ActivationPolicy.always())
                            .build()))
            .isInstanceOf(ChaosValidationException.class);
      }
    }
  }

  @Nested
  @DisplayName("JVM global delay composition")
  class JvmGlobalDelayComposition {

    @Test
    @DisplayName("multiple matching JVM scenarios accumulate delay")
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
          measureMillis(() -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}));
      assertThat(elapsed).isGreaterThanOrEqualTo(45);
    }
  }

  @Nested
  @DisplayName("GateEffect")
  class GateEffectTests {

    @Test
    @DisplayName("blocks until release")
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

        assertThatThrownBy(() -> future.get(150, TimeUnit.MILLISECONDS))
            .isInstanceOf(java.util.concurrent.TimeoutException.class);
        handle.release();
        assertThatCode(() -> future.get(1, TimeUnit.SECONDS)).doesNotThrowAnyException();
      } finally {
        executor.shutdownNow();
      }
    }
  }

  @Nested
  @DisplayName("RejectEffect")
  class RejectEffectTests {

    @Test
    @DisplayName("throws on executor submit")
    void rejectEffectThrowsOnExecutorSubmit() {
      ChaosRuntime runtime = new ChaosRuntime();
      runtime.activate(
          ChaosScenario.builder("reject-submit")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.reject("executor overloaded"))
              .activationPolicy(ActivationPolicy.always())
              .build());

      assertThatThrownBy(() -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}))
          .isInstanceOf(RejectedExecutionException.class);
    }
  }

  @Nested
  @DisplayName("SuppressEffect")
  class SuppressEffectTests {

    @Test
    @DisplayName("returns false on QUEUE_OFFER")
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
      assertThat(result).isFalse();
    }
  }

  @Nested
  @DisplayName("stop()")
  class StopTests {

    @Test
    @DisplayName("halts effect application")
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

      handle.stop();

      long elapsed =
          measureMillis(() -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}));
      assertThat(elapsed).isLessThan(50);
    }
  }

  @Nested
  @DisplayName("diagnostics")
  class DiagnosticsTests {

    @Test
    @DisplayName("snapshot reflects applied count")
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
      long totalApplied =
          snapshot.scenarios().stream()
              .mapToLong(ChaosDiagnostics.ScenarioReport::appliedCount)
              .sum();
      assertThat(totalApplied).isGreaterThanOrEqualTo(1);
    }
  }

  @Nested
  @DisplayName("event listener")
  class EventListenerTests {

    @Test
    @DisplayName("receives APPLIED event")
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

      ChaosEvent appliedEvent =
          events.stream()
              .filter(
                  e -> e.type() == ChaosEvent.Type.APPLIED && "event-test".equals(e.scenarioId()))
              .findFirst()
              .orElse(null);

      assertThat(appliedEvent).isNotNull();
      assertThat(appliedEvent.type()).isEqualTo(ChaosEvent.Type.APPLIED);
      assertThat(appliedEvent.scenarioId()).isEqualTo("event-test");
    }
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
