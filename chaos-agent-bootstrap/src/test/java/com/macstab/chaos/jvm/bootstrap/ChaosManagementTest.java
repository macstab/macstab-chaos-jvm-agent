package com.macstab.chaos.jvm.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.core.ChaosRuntime;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ChaosManagement")
class ChaosManagementTest {

  private static final String PLAN =
      """
      {"metadata":{"name":"mgmt"},"scenarios":[{"id":"mgmt-delay","scope":"JVM",
       "selector":{"type":"executor","operations":["EXECUTOR_SUBMIT"]},
       "effect":{"type":"delay","minDelay":"PT0.001S","maxDelay":"PT0.001S"}}]}
      """;

  private final ChaosRuntime runtime = new ChaosRuntime();
  private final ChaosManagement management = new ChaosManagement(runtime);

  @AfterEach
  void tearDown() {
    management.close();
    runtime.close();
  }

  private ChaosDiagnostics.ScenarioState state(final String id) {
    return runtime
        .diagnostics()
        .scenario(id)
        .map(ChaosDiagnostics.ScenarioReport::state)
        .orElse(null);
  }

  @Test
  @DisplayName("activates a plan and stops it by handle id or plan name")
  void activateAndStop() {
    final ChaosManagement.ActivationResponse activated = management.activate(PLAN);
    assertThat(activated.status()).isEqualTo("activated");
    assertThat(activated.handleId()).isEqualTo("plan:mgmt");
    assertThat(state("mgmt-delay")).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);

    assertThat(management.stop("mgmt").status()).isEqualTo("stopped");
    assertThat(state("mgmt-delay")).isNull();
    assertThat(management.stop("mgmt").status()).isEqualTo("not-found");

    management.activate(PLAN);
    assertThat(management.stop("plan:mgmt").status()).isEqualTo("stopped");
  }

  @Test
  @DisplayName("rejects blank, malformed and invalid plans with a reason")
  void rejects() {
    assertThat(management.activate(" ").status()).isEqualTo("invalid");
    assertThat(management.activate("{ broken").message()).startsWith("invalid plan");
    management.activate(PLAN);
    final ChaosManagement.ActivationResponse conflict =
        management.activate(PLAN.replace("mgmt\"}", "other\"}"));
    assertThat(conflict.status()).isEqualTo("conflict");
    assertThat(conflict.message()).contains("already active");
  }

  @Test
  @DisplayName("leaves scenarios activated elsewhere alone, and stops all of its own")
  void unmanagedAndStopAll() {
    runtime.activate(
        ChaosScenario.builder("app-owned")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.delay(Duration.ofMillis(1)))
            .build());
    assertThat(management.stop("app-owned").status()).isEqualTo("unmanaged");

    management.activate(PLAN);
    assertThat(management.stopAll().stoppedCount()).isEqualTo(1);
    assertThat(state("app-owned")).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);
  }

  @Test
  @DisplayName("serves the HTTP operations with status codes and JSON bodies")
  void http() {
    final ChaosManagement.HttpResult activated = management.handle("POST", "", PLAN);
    assertThat(activated.status()).isEqualTo(200);
    assertThat(activated.json()).contains("\"handleId\":\"plan:mgmt\"");

    final ChaosManagement.HttpResult snapshot = management.handle("GET", "/", null);
    assertThat(snapshot.status()).isEqualTo(200);
    assertThat(snapshot.json()).contains("\"id\":\"mgmt-delay\"").contains("\"state\":\"ACTIVE\"");

    assertThat(management.handle("POST", "", "{ broken").status()).isEqualTo(400);
    assertThat(management.handle("DELETE", "/nope", null).status()).isEqualTo(404);
    assertThat(management.handle("POST", "", PLAN.replace("mgmt\"}", "again\"}")).status())
        .as("conflict")
        .isEqualTo(409);
    assertThat(management.handle("DELETE", "/plan%3Amgmt", null).status())
        .as("decoded")
        .isEqualTo(200);
    assertThat(management.handle("DELETE", "", null).json()).contains("\"stoppedCount\":0");
    assertThat(management.handle("PUT", "", null).status()).isEqualTo(405);
    assertThat(management.handle("GET", "/x", null).status()).isEqualTo(404);
    assertThat(management.handle("POST", "/x", PLAN).status()).isEqualTo(404);
  }
}
