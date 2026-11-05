package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationException;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Scenario re-activation and handle lifecycle")
class ScenarioReactivationTest {

  private ChaosRuntime runtime;

  @BeforeEach
  void setUp() {
    runtime = new ChaosRuntime();
  }

  @Nested
  @DisplayName("re-activating the same scenario id")
  class Reactivation {

    @Test
    @DisplayName("is possible after close() (try-with-resources)")
    void afterClose() {
      // Arrange
      try (ChaosActivationHandle handle = runtime.activate(scenario("reuse"))) {
        assertThat(handle.state()).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
      }

      // Act
      final ChaosActivationHandle again = runtime.activate(scenario("reuse"));

      // Assert
      assertThat(again.state()).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
    }

    @Test
    @DisplayName("is possible after stop(), replacing the stopped scenario")
    void afterStop() {
      // Arrange
      final ChaosActivationHandle first = runtime.activate(scenario("reuse"));
      first.stop();

      // Act
      final ChaosActivationHandle second = runtime.activate(scenario("reuse"));

      // Assert
      assertThat(second.state()).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
      assertThat(runtime.diagnostics().snapshot().scenarios())
          .as("the stopped scenario is replaced, not kept as a ghost entry")
          .filteredOn(report -> "reuse".equals(report.id()))
          .singleElement()
          .extracting(ChaosDiagnostics.ScenarioReport::state)
          .isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
    }

    @Test
    @DisplayName("a replaced handle can no longer start the old scenario")
    void replacedHandleCannotRestart() {
      // Arrange
      final ChaosActivationHandle first = runtime.activate(scenario("reuse"));
      first.stop();
      runtime.activate(scenario("reuse"));

      // Act
      first.start();

      // Assert
      assertThat(first.state()).isEqualTo(ChaosDiagnostics.ScenarioState.STOPPED);
    }

    @Test
    @DisplayName("is possible for a plan after the plan handle is closed")
    void planAfterClose() {
      // Arrange
      final ChaosPlan plan =
          new ChaosPlan(
              new ChaosPlan.Metadata("plan", ""),
              null,
              List.of(scenario("plan-a"), scenario("plan-b")));
      runtime.activate(plan).close();

      // Act
      final ChaosActivationHandle again = runtime.activate(plan);

      // Assert
      assertThat(again.state()).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
    }
  }

  @Nested
  @DisplayName("activating an id that is still active")
  class DuplicateActivation {

    @Test
    @DisplayName("throws ChaosActivationException as documented")
    void throwsActivationException() {
      runtime.activate(scenario("dup"));

      assertThatThrownBy(() -> runtime.activate(scenario("dup")))
          .isInstanceOf(ChaosActivationException.class)
          .hasMessageContaining("already active");
    }

    @Test
    @DisplayName("does not start the rejected scenario or publish events for the live id")
    void rejectedScenarioHasNoSideEffects() {
      // Arrange
      runtime.activate(scenario("dup"));
      final List<ChaosEvent> events = new CopyOnWriteArrayList<>();
      runtime.addEventListener(events::add);

      // Act
      assertThatThrownBy(() -> runtime.activate(scenario("dup")))
          .isInstanceOf(ChaosActivationException.class);

      // Assert
      assertThat(events)
          .as("no STARTED/STOPPED for the rejected duplicate; only its FAILED report")
          .extracting(ChaosEvent::type)
          .containsExactly(ChaosEvent.Type.FAILED);
      assertThat(runtime.diagnostics().scenario("dup"))
          .get()
          .extracting(ChaosDiagnostics.ScenarioReport::state)
          .isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
    }

    @Test
    @DisplayName("is reported as an activation conflict in diagnostics")
    void recordedAsConflict() {
      runtime.activate(scenario("dup"));

      assertThatThrownBy(() -> runtime.activate(scenario("dup")))
          .isInstanceOf(ChaosActivationException.class);

      assertThat(runtime.diagnostics().snapshot().failures())
          .extracting(ChaosDiagnostics.ActivationFailure::category)
          .containsExactly(ChaosDiagnostics.FailureCategory.ACTIVATION_CONFLICT);
    }
  }

  @Nested
  @DisplayName("a closed control plane")
  class ClosedControlPlane {

    @Test
    @DisplayName("rejects new activations instead of accepting scenarios nobody cleans up")
    void rejectsActivation() {
      runtime.close();

      assertThatThrownBy(() -> runtime.activate(scenario("late")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("closed");
    }

    @Test
    @DisplayName("rejects new sessions and listeners")
    void rejectsSessionsAndListeners() {
      runtime.close();

      assertThatThrownBy(() -> runtime.openSession("late"))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> runtime.addEventListener(event -> {}))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  private static ChaosScenario scenario(final String id) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
        .effect(ChaosEffect.delay(Duration.ofMillis(1)))
        .activationPolicy(ActivationPolicy.always())
        .build();
  }
}
