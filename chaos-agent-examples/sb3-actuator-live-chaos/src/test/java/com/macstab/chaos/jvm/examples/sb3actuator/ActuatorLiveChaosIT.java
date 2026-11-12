package com.macstab.chaos.jvm.examples.sb3actuator;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.spring.boot3.test.ChaosTest;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ChaosTest(classes = PaymentServiceApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class ActuatorLiveChaosIT {

  private static final String SCENARIO_ID = "reject-gateway-connects";

  private static WireMockServer gateway;

  @Autowired private PaymentGatewayClient paymentGatewayClient;

  @Autowired private TestRestTemplate restTemplate;

  @BeforeAll
  static void startGateway() {
    if (gateway != null) {
      return;
    }
    gateway = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    gateway.start();
    // Connection: close, so every charge opens a connection of its own: a pooled keep-alive
    // connection would reach the gateway without a SOCKET_CONNECT for the scenario to reject.
    gateway.stubFor(
        post(urlEqualTo("/charge"))
            .willReturn(
                aResponse().withStatus(200).withHeader("Connection", "close").withBody("charged")));
  }

  @DynamicPropertySource
  static void gatewayUrl(final DynamicPropertyRegistry registry) {
    startGateway();
    registry.add("payment.gateway.url", () -> "http://localhost:" + gateway.port());
  }

  @AfterAll
  static void stopGateway() {
    if (gateway != null) {
      gateway.stop();
      gateway = null;
    }
  }

  @Test
  void circuitBreakerOpensUnderConnectFailuresActivatedThroughTheActuator(
      final ChaosControlPlane chaos) {
    assertThat(paymentGatewayClient.charge("order-clean"))
        .as("without chaos the gateway charges the order")
        .isEqualTo("charged");

    // The gateway's port only: the test's own requests to the application connect too.
    final String plan =
        """
        {"metadata": {"name": "gateway-down"}, "scenarios": [{"id": "%s", "scope": "JVM",
          "selector": {"type": "network", "operations": ["SOCKET_CONNECT"], "remotePort": %d},
          "effect": {"type": "reject", "message": "chaos: gateway unreachable"}}]}
        """
            .formatted(SCENARIO_ID, gateway.port());
    final HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    final ResponseEntity<String> activated =
        restTemplate.postForEntity(
            "/actuator/chaos", new HttpEntity<>(Map.of("planJson", plan), headers), String.class);
    assertThat(activated.getStatusCode()).as(activated.getBody()).isEqualTo(HttpStatus.OK);
    assertThat(activated.getBody()).contains("\"handleId\":\"plan:gateway-down\"");

    try {
      int openCircuitFallbacks = 0;
      for (int i = 0; i < 6; i++) {
        // The fallback tells "breaker open" (CallNotPermittedException) from any other failure.
        if ("fallback:circuit-open".equals(paymentGatewayClient.charge("order-" + i))) {
          openCircuitFallbacks++;
        }
      }

      assertThat(chaos.diagnostics().scenario(SCENARIO_ID).orElseThrow().appliedCount())
          .as("the actuator-activated scenario rejected the gateway connects")
          .isPositive();
      assertThat(openCircuitFallbacks)
          .as(
              "the circuit opens under the rejected connects (minimumNumberOfCalls=3,"
                  + " slidingWindowSize=5, failureRateThreshold=60%%)")
          .isPositive();
    } finally {
      restTemplate.delete("/actuator/chaos/plan:gateway-down");
    }
    assertThat(chaos.diagnostics().scenario(SCENARIO_ID))
        .as("stopped through DELETE /actuator/chaos/{id}")
        .isEmpty();
  }
}
