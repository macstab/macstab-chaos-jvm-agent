package com.macstab.chaos.jvm.spring.boot.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.bootstrap.ChaosManagement;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;

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
  private ChaosActuatorEndpoint endpoint;

  @BeforeEach
  void setUp() {
    runtime = new ChaosRuntime();
    endpoint = new ChaosActuatorEndpoint(runtime);
  }

  @AfterEach
  void tearDown() {
    endpoint.close();
    runtime.close();
  }

  @Test
  @DisplayName("activations after stopAll() stay active")
  void activationAfterStopAllStaysActive() {
    endpoint.activate(PLAN);
    endpoint.stopAll();

    final WebEndpointResponse<ChaosManagement.ActivationResponse> response =
        endpoint.activate(PLAN);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getBody().status()).isEqualTo("activated");
    assertThat(runtime.diagnostics().scenario("executor-latency"))
        .get()
        .extracting(ChaosDiagnostics.ScenarioReport::state)
        .isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
  }

  @Test
  @DisplayName("a missing, blank or malformed body is a 400, nothing activated")
  void unusableBodies() {
    assertThat(endpoint.activate(null).getStatus()).isEqualTo(400);
    assertThat(endpoint.activate("  ").getBody().status()).isEqualTo("invalid");
    final WebEndpointResponse<ChaosManagement.ActivationResponse> malformed =
        endpoint.activate("{\"scenarios\": [secret");
    assertThat(malformed.getStatus()).isEqualTo(400);
    assertThat(malformed.getBody().message()).doesNotContain("secret");
    assertThat(runtime.diagnostics().snapshot().scenarios()).isEmpty();
  }

  @Test
  @DisplayName("a stopped plan can be activated again")
  void planCanBeReactivatedAfterStop() {
    final String handleId = endpoint.activate(PLAN).getBody().handleId();
    endpoint.stop(handleId);

    assertThat(endpoint.activate(PLAN).getBody().status()).isEqualTo("activated");
  }

  @Test
  @DisplayName("stop() accepts the plan handle id and the plan name")
  void stopAcceptsHandleIdAndPlanName() {
    final String handleId = endpoint.activate(PLAN).getBody().handleId();
    assertThat(endpoint.stop(handleId).getStatus()).isEqualTo(200);

    endpoint.activate(PLAN);
    assertThat(endpoint.stop("latency").getBody().status()).isEqualTo("stopped");
  }

  @Test
  @DisplayName("a scenario not activated as a plan here is a 409 that says where to stop it")
  void stopByScenarioIdIsUnmanaged() {
    endpoint.activate(PLAN);

    final WebEndpointResponse<ChaosManagement.StopResponse> response =
        endpoint.stop("executor-latency");

    assertThat(response.getStatus()).isEqualTo(409);
    assertThat(response.getBody().status()).isEqualTo("unmanaged");
  }

  @Test
  @DisplayName("an invalid scenario is a 400 with the validation reason")
  void invalidScenarioIsRejected() {
    final String invalid =
        PLAN.replace(
            "\"type\": \"delay\", \"minDelay\": \"PT0.001S\", \"maxDelay\": \"PT0.001S\"",
            "\"type\": \"spuriousWakeup\"");

    final WebEndpointResponse<ChaosManagement.ActivationResponse> response =
        endpoint.activate(invalid);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getBody().status()).isEqualTo("invalid");
  }

  @Test
  @DisplayName("closing the endpoint stops the plans activated through it")
  void closeStopsItsPlans() {
    endpoint.activate(PLAN);

    endpoint.close();

    assertThat(runtime.diagnostics().scenario("executor-latency"))
        .map(ChaosDiagnostics.ScenarioReport::state)
        .isNotEqualTo(java.util.Optional.of(ChaosDiagnostics.ScenarioState.ACTIVE));
  }
}
