package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The registry evaluates only the scenarios indexed under the intercepted operation, in precedence
 * order, and keeps the index in step with activation, stop and replacement.
 */
@DisplayName("Scenario index by operation type")
class ScenarioIndexTest {

  private ChaosRuntime runtime;

  @BeforeEach
  void setUp() {
    runtime = new ChaosRuntime();
  }

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  @Test
  @DisplayName("a scenario for another operation is not evaluated")
  void otherOperationIsNotEvaluated() {
    runtime.activate(
        scenario("schedule-only", OperationType.SCHEDULE_SUBMIT, ChaosEffect.reject("no"), 0));

    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});

    assertThat(runtime.diagnostics().scenario("schedule-only").orElseThrow().matchedCount())
        .isZero();
  }

  @Test
  @DisplayName("the higher-precedence scenario wins regardless of activation order")
  void precedenceOrderWithoutSorting() {
    runtime.activate(
        scenario("z-high", OperationType.EXECUTOR_SUBMIT, ChaosEffect.reject("high"), 100));
    runtime.activate(scenario("a-low", OperationType.EXECUTOR_SUBMIT, ChaosEffect.suppress(), 0));

    assertThatThrownBy(() -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}))
        .isInstanceOf(RejectedExecutionException.class)
        .hasMessageContaining("high");
  }

  @Test
  @DisplayName("a closed scenario leaves the index and a replacement enters it")
  void indexFollowsLifecycle() {
    final ChaosActivationHandle first =
        runtime.activate(
            scenario("reuse", OperationType.EXECUTOR_SUBMIT, ChaosEffect.reject("first"), 0));
    first.close();

    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});

    runtime.activate(
        scenario("reuse", OperationType.EXECUTOR_SUBMIT, ChaosEffect.reject("second"), 0));
    assertThatThrownBy(() -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}))
        .isInstanceOf(RejectedExecutionException.class)
        .hasMessageContaining("second");
  }

  @Test
  @DisplayName("a stopped scenario replaced under the same id is evaluated only once")
  void replacedScenarioIsNotEvaluatedTwice() {
    runtime.activate(scenario("reuse", OperationType.EXECUTOR_SUBMIT, delay(), 0)).stop();
    runtime.activate(scenario("reuse", OperationType.EXECUTOR_SUBMIT, delay(), 0));

    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});

    assertThat(runtime.diagnostics().scenario("reuse").orElseThrow().matchedCount()).isEqualTo(1L);
  }

  private static ChaosEffect delay() {
    return ChaosEffect.delay(Duration.ofMillis(1));
  }

  private static ChaosScenario scenario(
      final String id,
      final OperationType operation,
      final ChaosEffect effect,
      final int precedence) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(
            operation == OperationType.SCHEDULE_SUBMIT
                ? new ChaosSelector.SchedulingSelector(Set.of(operation), null, null)
                : ChaosSelector.executor(Set.of(operation)))
        .effect(effect)
        .precedence(precedence)
        .activationPolicy(ActivationPolicy.always())
        .build();
  }
}
