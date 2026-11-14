package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ChaosActivationException;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The diagnostics keep a bounded failure history and report only what is there. */
@DisplayName("Diagnostics history and runtime details")
class DiagnosticsHistoryTest {

  private final ChaosRuntime runtime = new ChaosRuntime();

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  @Test
  @DisplayName("the 256 most recent failures are kept and the older ones counted")
  void failureHistoryIsBounded() {
    for (int i = 0; i < 300; i++) {
      refuse("f-" + i);
    }

    final ChaosDiagnostics.Snapshot snapshot = runtime.diagnostics().snapshot();

    assertThat(snapshot.failures()).hasSize(256);
    assertThat(snapshot.failures().get(0).scenarioId()).isEqualTo("f-44");
    assertThat(snapshot.failures().get(255).scenarioId()).isEqualTo("f-299");
    assertThat(snapshot.runtimeDetails()).containsEntry("droppedActivationFailures", "44");
  }

  @Test
  @DisplayName("concurrent failures above the cap keep exactly 256 and count every dropped one")
  void failureHistoryIsExactUnderConcurrency() throws InterruptedException {
    final int threads = 8;
    final int perThread = 75;
    final CountDownLatch start = new CountDownLatch(1);
    final List<Thread> workers = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      final int thread = t;
      final Thread worker =
          new Thread(
              () -> {
                try {
                  start.await();
                } catch (final InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  return;
                }
                for (int i = 0; i < perThread; i++) {
                  refuse("c-" + thread + "-" + i);
                }
              });
      worker.start();
      workers.add(worker);
    }
    start.countDown();
    for (final Thread worker : workers) {
      worker.join();
    }

    final ChaosDiagnostics.Snapshot snapshot = runtime.diagnostics().snapshot();

    assertThat(snapshot.failures()).hasSize(256);
    assertThat(snapshot.runtimeDetails())
        .containsEntry("droppedActivationFailures", String.valueOf(threads * perThread - 256));
  }

  @Test
  @DisplayName("debugDump lists the activation failures")
  void debugDumpListsFailures() {
    refuse("refused-one");

    assertThat(runtime.diagnostics().debugDump())
        .contains("failure refused-one category=INVALID_CONFIGURATION message=");
  }

  @Test
  @DisplayName("currentSessionId is reported only while a session is bound")
  void currentSessionIdOnlyWhenBound() {
    assertThat(runtime.diagnostics().snapshot().runtimeDetails())
        .doesNotContainKey("currentSessionId");
    assertThat(runtime.diagnostics().debugDump()).doesNotContain("currentSessionId");

    try (ChaosSession session = runtime.openSession("bound");
        ChaosSession.ScopeBinding binding = session.bind()) {
      assertThat(runtime.diagnostics().snapshot().runtimeDetails())
          .containsEntry("currentSessionId", session.id());
    }
  }

  /** A session-scoped scenario validated for the JVM scope: refused and recorded. */
  private void refuse(final String id) {
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .scope(ChaosScenario.ScenarioScope.SESSION)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.delay(Duration.ofMillis(1)))
            .build();
    assertThatThrownBy(() -> runtime.validate(scenario))
        .isInstanceOf(ChaosActivationException.class);
  }
}
