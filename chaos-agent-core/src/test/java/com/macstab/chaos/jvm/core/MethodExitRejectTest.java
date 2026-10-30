package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RejectEffect on METHOD_EXIT")
class MethodExitRejectTest {

  @Test
  @DisplayName("fails the call after the method body ran instead of being ignored")
  void rejectOnExitThrows() {
    final ChaosRuntime runtime = new ChaosRuntime();
    try {
      runtime.activate(
          ChaosScenario.builder("exit-reject")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(
                  new ChaosSelector.MethodSelector(
                      Set.of(OperationType.METHOD_EXIT),
                      NamePattern.exact("com.example.Service"),
                      NamePattern.exact("save"),
                      NamePattern.any()))
              .effect(ChaosEffect.reject("lost response"))
              .activationPolicy(ActivationPolicy.always())
              .build());

      assertThatThrownBy(
              () -> runtime.afterMethodExit("com.example.Service", "save", String.class, "ok"))
          .hasMessageContaining("lost response");
    } finally {
      runtime.close();
    }
  }
}
