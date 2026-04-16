package io.macstab.chaos.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.macstab.chaos.api.ActivationPolicy;
import io.macstab.chaos.api.ChaosDiagnostics;
import io.macstab.chaos.api.ChaosEffect;
import io.macstab.chaos.api.ChaosScenario;
import io.macstab.chaos.api.ChaosSelector;
import io.macstab.chaos.api.OperationType;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ScenarioController — state machine")
class ScenarioControllerStateTest {

  private static ScenarioController makeController(final String id) {
    final ChaosScenario scenario =
        ChaosScenario.builder(id)
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.delay(Duration.ofMillis(1)))
            .activationPolicy(ActivationPolicy.always())
            .build();
    return new ScenarioController(
        scenario,
        "jvm",
        null,
        Clock.systemUTC(),
        new ObservabilityBus(io.macstab.chaos.api.ChaosMetricsSink.NOOP));
  }

  @Test
  @DisplayName("initial state is REGISTERED regardless of StartMode")
  void initialStateIsRegistered() {
    final ScenarioController controller = makeController("init-state");
    // Task 1: Must be REGISTERED, not ACTIVE, before start() is called.
    assertThat(controller.state()).isEqualTo(ChaosDiagnostics.ScenarioState.REGISTERED);
  }

  @Test
  @DisplayName("start() transitions to ACTIVE")
  void startTransitionsToActive() {
    final ScenarioController controller = makeController("start-active");
    controller.start();
    assertThat(controller.state()).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
  }

  @Test
  @DisplayName("stop() transitions to STOPPED")
  void stopTransitionsToStopped() {
    final ScenarioController controller = makeController("stop-stopped");
    controller.start();
    controller.stop();
    assertThat(controller.state()).isEqualTo(ChaosDiagnostics.ScenarioState.STOPPED);
  }

  @Test
  @DisplayName("evaluate() returns null before start()")
  void evaluateReturnsNullBeforeStart() {
    final ScenarioController controller = makeController("eval-null");
    final InvocationContext context =
        new InvocationContext(
            OperationType.EXECUTOR_SUBMIT,
            "java.util.concurrent.ThreadPoolExecutor",
            null,
            null,
            false,
            null,
            null,
            null);
    assertThat(controller.evaluate(context)).isNull();
  }

  @Test
  @DisplayName("evaluate() returns non-null after start()")
  void evaluateReturnsContributionAfterStart() {
    final ScenarioController controller = makeController("eval-active");
    controller.start();
    final InvocationContext context =
        new InvocationContext(
            OperationType.EXECUTOR_SUBMIT,
            "java.util.concurrent.ThreadPoolExecutor",
            null,
            null,
            false,
            null,
            null,
            null);
    assertThat(controller.evaluate(context)).isNotNull();
  }

  @Test
  @DisplayName("evaluate() returns null after stop()")
  void evaluateReturnsNullAfterStop() {
    final ScenarioController controller = makeController("eval-stopped");
    controller.start();
    controller.stop();
    final InvocationContext context =
        new InvocationContext(
            OperationType.EXECUTOR_SUBMIT,
            "java.util.concurrent.ThreadPoolExecutor",
            null,
            null,
            false,
            null,
            null,
            null);
    assertThat(controller.evaluate(context)).isNull();
  }
}
