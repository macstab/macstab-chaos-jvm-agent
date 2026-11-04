package com.macstab.chaos.jvm.spring.boot.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Actuator endpoint behaviour against a real (non-instrumenting) {@link ChaosRuntime}. */
@DisplayName("ChaosActuatorEndpoint lifecycle")
class ChaosActuatorLifecycleTest {

  private static final String PLAN =
      """
      {
        "metadata": {"name": "latency"},
        "scenarios": [
          {
            "id": "executor-latency",
            "scope": "JVM",
            "selector": {"type": "executor", "operations": ["EXECUTOR_SUBMIT"]},
            "effect": {"type": "delay", "minDelay": "PT0.001S", "maxDelay": "PT0.001S"}
          }
        ]
      }
      """;

  private ChaosRuntime runtime;
  private ChaosHandleRegistry registry;
  private ChaosActuatorEndpoint endpoint;

  @BeforeEach
  void setUp() {
    runtime = new ChaosRuntime();
    registry = new ChaosHandleRegistry();
    endpoint = new ChaosActuatorEndpoint(runtime, registry);
  }

  @AfterEach
  void tearDown() {
    registry.destroy();
    runtime.close();
  }

  @Test
  @DisplayName("activations after stopAll() stay active")
  void activationAfterStopAllStaysActive() {
    // Arrange
    endpoint.activate(PLAN);
    endpoint.stopAll();

    // Act
    final ChaosActuatorEndpoint.ActivationResponse response = endpoint.activate(PLAN);

    // Assert
    assertThat(response.status()).isEqualTo("activated");
    assertThat(runtime.diagnostics().scenario("executor-latency"))
        .get()
        .extracting(ChaosDiagnostics.ScenarioReport::state)
        .isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
  }

  @Test
  @DisplayName("a missing, blank or malformed body is answered with an error, nothing activated")
  void unusableBodies() {
    assertThat(endpoint.activate(null))
        .isEqualTo(
            new ChaosActuatorEndpoint.ActivationResponse(
                "error", null, "plan JSON must not be blank"));
    assertThat(endpoint.activate("  "))
        .isEqualTo(
            new ChaosActuatorEndpoint.ActivationResponse(
                "error", null, "plan JSON must not be blank"));
    // The parser's detail stays in the server log; the response does not echo the input.
    assertThat(endpoint.activate("{\"scenarios\": [secret"))
        .isEqualTo(new ChaosActuatorEndpoint.ActivationResponse("error", null, "invalid plan"));
    assertThat(runtime.diagnostics().snapshot().scenarios()).isEmpty();
    assertThat(registry.ids()).isEmpty();
  }

  @Test
  @DisplayName("a stopped plan can be activated again")
  void planCanBeReactivatedAfterStop() {
    // Arrange
    final String handleId = endpoint.activate(PLAN).handleId();
    endpoint.stop(handleId);

    // Act
    final ChaosActuatorEndpoint.ActivationResponse response = endpoint.activate(PLAN);

    // Assert
    assertThat(response.status()).isEqualTo("activated");
  }

  @Test
  @DisplayName("stop() accepts the plan handle id and the plan name")
  void stopAcceptsHandleIdAndPlanName() {
    final String handleId = endpoint.activate(PLAN).handleId();
    assertThat(endpoint.stop(handleId).status()).isEqualTo("stopped");

    endpoint.activate(PLAN);
    assertThat(endpoint.stop("latency").status()).isEqualTo("stopped");
  }

  @Test
  @DisplayName("stop() by scenario id of a plan points to the plan instead of failing silently")
  void stopByScenarioIdExplainsPlanStop() {
    endpoint.activate(PLAN);

    final ChaosActuatorEndpoint.StopResponse response = endpoint.stop("executor-latency");

    assertThat(response.status()).isEqualTo("unmanaged");
    assertThat(response.message()).contains("plan:<name>");
  }

  @Test
  @DisplayName("an invalid scenario is rejected with a redacted error instead of an exception")
  void invalidScenarioIsRejected() {
    final String invalid =
        PLAN.replace(
            "\"type\": \"delay\", \"minDelay\": \"PT0.001S\", \"maxDelay\": \"PT0.001S\"",
            "\"type\": \"spuriousWakeup\"");

    final ChaosActuatorEndpoint.ActivationResponse response = endpoint.activate(invalid);

    assertThat(response.status()).isEqualTo("error");
  }
}
