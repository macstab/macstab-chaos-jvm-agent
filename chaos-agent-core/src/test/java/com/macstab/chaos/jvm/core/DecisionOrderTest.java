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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Delay, gate and terminal action are applied in that order, and the delay is never dropped. */
@DisplayName("Decision application order")
class DecisionOrderTest {

  @Test
  @DisplayName("a delay from one scenario is applied before another scenario's reject is thrown")
  void delayIsAppliedBeforeThrow() {
    // Arrange
    try (ChaosRuntime runtime = new ChaosRuntime()) {
      runtime.activate(scenario("delay", ChaosEffect.delay(Duration.ofMillis(200)), 0));
      runtime.activate(scenario("reject", ChaosEffect.reject("rejected"), 10));

      // Act
      final long start = System.nanoTime();
      assertThatThrownBy(() -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}))
          .isInstanceOf(RejectedExecutionException.class);
      final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

      // Assert
      assertThat(elapsedMillis)
          .as("the call fails after the delay, not instantly")
          .isGreaterThanOrEqualTo(180L);
    }
  }

  @Test
  @DisplayName("a terminal at the lowest precedence still takes effect when it is the only one")
  void lowestPrecedenceTerminalApplies() {
    // Arrange
    try (ChaosRuntime runtime = new ChaosRuntime()) {
      final ChaosActivationHandle handle =
          runtime.activate(scenario("reject", ChaosEffect.reject("rejected"), Integer.MIN_VALUE));

      // Act / Assert
      assertThatThrownBy(() -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}))
          .isInstanceOf(RejectedExecutionException.class);
      assertThat(handle.appliedCount()).isEqualTo(1L);
    }
  }

  @Test
  @DisplayName("a gate at the lowest precedence still holds the call when it is the only one")
  void lowestPrecedenceGateApplies() {
    // Arrange
    try (ChaosRuntime runtime = new ChaosRuntime()) {
      final ChaosActivationHandle handle =
          runtime.activate(
              scenario("gate", ChaosEffect.gate(Duration.ofMillis(200)), Integer.MIN_VALUE));

      // Act
      final long start = System.nanoTime();
      runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
      final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

      // Assert
      assertThat(handle.appliedCount()).isEqualTo(1L);
      assertThat(elapsedMillis).as("the gate held the call").isGreaterThanOrEqualTo(180L);
    }
  }

  private static ChaosScenario scenario(
      final String id, final ChaosEffect effect, final int precedence) {
    return ChaosScenario.builder(id)
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
        .effect(effect)
        .precedence(precedence)
        .activationPolicy(ActivationPolicy.always())
        .build();
  }
}
