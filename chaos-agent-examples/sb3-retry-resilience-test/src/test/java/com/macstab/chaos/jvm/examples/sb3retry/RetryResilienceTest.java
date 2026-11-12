package com.macstab.chaos.jvm.examples.sb3retry;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosScenario.ScenarioScope;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.NamePattern;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.spring.boot3.test.ChaosTest;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ChaosTest(classes = RetryDemoApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class RetryResilienceTest {

  private static WireMockServer wireMock;

  @Autowired private TestRestTemplate restTemplate;

  @BeforeAll
  static void startWireMock() {
    if (wireMock != null) {
      return;
    }
    // A free port, not a fixed one: modules run in parallel and may run this test at once.
    wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    wireMock.start();
    wireMock.stubFor(
        get(urlEqualTo("/ping"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/plain")
                    .withBody("pong")));
  }

  @org.springframework.test.context.DynamicPropertySource
  static void downstream(final org.springframework.test.context.DynamicPropertyRegistry registry) {
    if (wireMock == null) {
      startWireMock();
    }
    registry.add("downstream.url", () -> "http://localhost:" + wireMock.port());
  }

  @AfterAll
  static void stopWireMock() {
    if (wireMock != null) {
      wireMock.stop();
      wireMock = null;
    }
  }

  @Test
  void retryEventuallySucceedsDespiteInjectedConnectFailures(ChaosControlPlane chaos) {
    ChaosScenario transientConnectFailures =
        ChaosScenario.builder("transient-connect-failures")
            .description(
                "Fail the first two SOCKET_CONNECT operations to the downstream with a ConnectException; "
                    + "third and subsequent connects fall through to the real backend. "
                    + "Proves Resilience4j @Retry absorbs a bounded burst of transient faults.")
            .scope(ScenarioScope.JVM)
            // The downstream only: the test's own request to the application is a connect too.
            .selector(
                new ChaosSelector.NetworkSelector(
                    Set.of(OperationType.SOCKET_CONNECT),
                    NamePattern.any(),
                    null,
                    wireMock.port(),
                    null,
                    null))
            .effect(ChaosEffect.reject("chaos: transient connect failure"))
            .activationPolicy(
                new ActivationPolicy(
                    ActivationPolicy.StartMode.AUTOMATIC, 1.0, 0L, 2L, null, null, 0L, false))
            .build();

    try (ChaosActivationHandle handle = chaos.activate(transientConnectFailures)) {
      ResponseEntity<String> response = restTemplate.getForEntity("/fetch", String.class);

      assertThat(response.getStatusCode())
          .as("GET /fetch must return 200 — @Retry absorbed the 2 injected ConnectExceptions")
          .isEqualTo(HttpStatus.OK);
      assertThat(response.getBody())
          .as("Response body must be 'pong' from WireMock once chaos is exhausted")
          .isEqualTo("pong");
      assertThat(handle.appliedCount())
          .as("the first two connects to the downstream were rejected")
          .isEqualTo(2);
      assertThat(handle.matchedCount())
          .as("the scenario ended with its second application: the third connect was not matched")
          .isEqualTo(2);
    }
  }
}
