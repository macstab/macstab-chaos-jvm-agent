package io.macstab.chaos.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.macstab.chaos.api.ActivationPolicy;
import io.macstab.chaos.api.ChaosDiagnostics;
import io.macstab.chaos.api.ChaosEffect;
import io.macstab.chaos.api.ChaosScenario;
import io.macstab.chaos.api.ChaosSelector;
import io.macstab.chaos.api.OperationType;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for Task 4 — {@link DefaultChaosActivationHandle#destroy()} must call {@link
 * ScenarioRegistry#unregister(ScenarioController)} so that closed session scenarios do not
 * accumulate indefinitely in the registry.
 */
@DisplayName("Registry cleanup on session close")
class RegistryCleanupTest {

  @Test
  @DisplayName("session scenario is removed from registry diagnostics after session close")
  void sessionScenarioRemovedFromRegistryAfterClose() {
    final ChaosRuntime runtime = new ChaosRuntime();

    try (final var session = runtime.openSession("cleanup-test")) {
      session.activate(
          ChaosScenario.builder("session-cleanup")
              .scope(ChaosScenario.ScenarioScope.SESSION)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ofMillis(1)))
              .activationPolicy(ActivationPolicy.always())
              .build());

      // Confirm it appears in diagnostics while the session is open.
      final boolean appearsInDiagnostics =
          runtime.diagnostics().snapshot().scenarios().stream()
              .anyMatch(r -> "session-cleanup".equals(r.id()));
      assertThat(appearsInDiagnostics).as("scenario in diagnostics before close").isTrue();
    }
    // After close() the handle's destroy() must have called registry.unregister().
    final boolean stillInDiagnostics =
        runtime.diagnostics().snapshot().scenarios().stream()
            .anyMatch(r -> "session-cleanup".equals(r.id()));
    assertThat(stillInDiagnostics).as("scenario removed from registry after close").isFalse();
  }

  @Test
  @DisplayName("JVM-scoped scenario persists in registry after stop()")
  void jvmScenarioPersistsAfterStop() {
    final ChaosRuntime runtime = new ChaosRuntime();
    final var handle =
        runtime.activate(
            ChaosScenario.builder("jvm-persist")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                .activationPolicy(ActivationPolicy.always())
                .build());

    handle.stop();

    // stop() does NOT remove from registry — only destroy() (called by session close) does.
    final boolean inDiagnostics =
        runtime.diagnostics().snapshot().scenarios().stream()
            .anyMatch(r -> "jvm-persist".equals(r.id()));
    assertThat(inDiagnostics).as("JVM scenario remains in registry after stop").isTrue();
    assertThat(handle.state())
        .as("state is STOPPED after stop()")
        .isEqualTo(ChaosDiagnostics.ScenarioState.STOPPED);
  }

  @Test
  @DisplayName("multiple sessions each clean up independently")
  void multipleSessionsCleanUpIndependently() {
    final ChaosRuntime runtime = new ChaosRuntime();

    try (final var sessionA = runtime.openSession("session-a")) {
      sessionA.activate(
          ChaosScenario.builder("scenario-a")
              .scope(ChaosScenario.ScenarioScope.SESSION)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ofMillis(1)))
              .activationPolicy(ActivationPolicy.always())
              .build());

      try (final var sessionB = runtime.openSession("session-b")) {
        sessionB.activate(
            ChaosScenario.builder("scenario-b")
                .scope(ChaosScenario.ScenarioScope.SESSION)
                .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                .effect(ChaosEffect.delay(Duration.ofMillis(1)))
                .activationPolicy(ActivationPolicy.always())
                .build());

        // Both present while both sessions are open.
        final ChaosDiagnostics.Snapshot snap1 = runtime.diagnostics().snapshot();
        assertThat(snap1.scenarios().stream().anyMatch(r -> "scenario-a".equals(r.id()))).isTrue();
        assertThat(snap1.scenarios().stream().anyMatch(r -> "scenario-b".equals(r.id()))).isTrue();
      }

      // sessionB closed: scenario-b must be removed; scenario-a must remain.
      final ChaosDiagnostics.Snapshot snap2 = runtime.diagnostics().snapshot();
      assertThat(snap2.scenarios().stream().anyMatch(r -> "scenario-a".equals(r.id()))).isTrue();
      assertThat(snap2.scenarios().stream().anyMatch(r -> "scenario-b".equals(r.id()))).isFalse();
    }

    // sessionA closed: scenario-a must now also be removed.
    final ChaosDiagnostics.Snapshot snap3 = runtime.diagnostics().snapshot();
    assertThat(snap3.scenarios().stream().anyMatch(r -> "scenario-a".equals(r.id()))).isFalse();
  }
}
