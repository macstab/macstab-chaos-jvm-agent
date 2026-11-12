package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.util.List;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Of two terminal actions on one call, the one with the higher precedence wins, in any order. */
@DisplayName("Competing terminal actions")
class TerminalPrecedenceTest {

  private static ChaosScenario reject(final String id, final int precedence) {
    return ChaosScenario.builder(id)
        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
        .effect(ChaosEffect.reject(id))
        .activationPolicy(ActivationPolicy.always())
        .precedence(precedence)
        .build();
  }

  @ParameterizedTest(name = "activated high first: {0}")
  @ValueSource(booleans = {true, false})
  @DisplayName("the reject with precedence 5 wins over the one with precedence 1")
  void higherPrecedenceWins(final boolean highFirst) {
    try (ChaosRuntime runtime = new ChaosRuntime()) {
      final List<ChaosScenario> order =
          highFirst
              ? List.of(reject("high", 5), reject("low", 1))
              : List.of(reject("low", 1), reject("high", 5));
      order.forEach(runtime::activate);

      assertThatThrownBy(
              () -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", new Object(), () -> {}))
          .isInstanceOf(RejectedExecutionException.class)
          .hasMessageContaining("high");
    }
  }
}
