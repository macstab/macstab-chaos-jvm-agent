package com.macstab.chaos.jvm.spring.boot4.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.testkit.ChaosAssertions;
import com.macstab.chaos.jvm.testkit.WithChaos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ChaosAgentExtension.class)
@WithChaos(
    """
    {"id": "sb4-class", "scope": "JVM",
     "selector": {"type": "executor", "operations": ["EXECUTOR_SHUTDOWN"]},
     "effect": {"type": "delay", "minDelay": "PT0.001S", "maxDelay": "PT0.001S"}}
    """)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("@WithChaos with ChaosAgentExtension")
class WithChaosIntegrationTest {

  @Test
  @Order(1)
  @WithChaos(
      """
      {"id": "sb4-method", "scope": "JVM",
       "selector": {"type": "executor", "operations": ["EXECUTOR_SHUTDOWN"]},
       "effect": {"type": "delay", "minDelay": "PT0.001S", "maxDelay": "PT0.001S"}}
      """)
  @DisplayName("activates class- and method-level scenarios")
  void activatesBothLevels(final ChaosControlPlane controlPlane) {
    ChaosAssertions.assertThat(controlPlane.diagnostics())
        .hasNoFailuresFor("sb4-class", "sb4-method")
        .scenario("sb4-class")
        .hasState(ChaosDiagnostics.ScenarioState.ACTIVE);
    ChaosAssertions.assertThat(controlPlane.diagnostics())
        .scenario("sb4-method")
        .hasState(ChaosDiagnostics.ScenarioState.ACTIVE);
  }

  @Test
  @Order(2)
  @DisplayName("stops method-level scenarios after their method, keeps class-level ones")
  void methodLevelEndsWithMethod(final ChaosControlPlane controlPlane) {
    assertThat(
            controlPlane
                .diagnostics()
                .scenario("sb4-method")
                .filter(r -> r.state() == ChaosDiagnostics.ScenarioState.ACTIVE))
        .isEmpty();
    ChaosAssertions.assertThat(controlPlane.diagnostics())
        .scenario("sb4-class")
        .hasState(ChaosDiagnostics.ScenarioState.ACTIVE);
  }
}
