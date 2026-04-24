package com.macstab.chaos.jvm.examples.sb4sla;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosScenario.ScenarioScope;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

@ChaosTest(classes = SlaApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class SlaValidationTest {

  private static WireMockServer wireMockA;
  private static WireMockServer wireMockB;
  private static WireMockServer wireMockC;

  @LocalServerPort private int port;

  @BeforeAll
  static void startWireMocks() {
    wireMockA = new WireMockServer(WireMockConfiguration.options().port(18081));
    wireMockB = new WireMockServer(WireMockConfiguration.options().port(18082));
    wireMockC = new WireMockServer(WireMockConfiguration.options().port(18083));

    wireMockA.start();
    wireMockB.start();
    wireMockC.start();

    wireMockA.stubFor(get(urlEqualTo("/a")).willReturn(aResponse().withStatus(200).withBody("ok")));
    wireMockB.stubFor(get(urlEqualTo("/b")).willReturn(aResponse().withStatus(200).withBody("ok")));
    wireMockC.stubFor(get(urlEqualTo("/c")).willReturn(aResponse().withStatus(200).withBody("ok")));
  }

  @AfterAll
  static void stopWireMocks() {
    if (wireMockA != null) wireMockA.stop();
    if (wireMockB != null) wireMockB.stop();
    if (wireMockC != null) wireMockC.stop();
  }

  @Test
  void p99LatencyUnder500msWithSocketReadDelayAt40PercentProbability(ChaosSession session) {
    ChaosScenario readDelay =
        ChaosScenario.builder("socket-read-delay")
            .description(
                "Inject 20-80ms SOCKET_READ delay at 40% probability to simulate adverse network")
            .scope(ScenarioScope.SESSION)
            .selector(ChaosSelector.network(Set.of(OperationType.SOCKET_READ)))
            .effect(ChaosEffect.delay(Duration.ofMillis(20), Duration.ofMillis(80)))
            .activationPolicy(
                new ActivationPolicy(
                    ActivationPolicy.StartMode.AUTOMATIC, 0.4, 0, null, null, null, 0L, false))
            .build();

    session.activate(readDelay);

    int iterations = 50;
    long[] nanos = new long[iterations];

    final RestClient client = RestClient.create("http://localhost:" + port);

    for (int i = 0; i < iterations; i++) {
      long start = System.nanoTime();
      ResponseEntity<FanOutResult> response;
      try (ChaosSession.ScopeBinding binding = session.bind()) {
        response = client.get().uri("/fanout").retrieve().toEntity(FanOutResult.class);
      }
      nanos[i] = System.nanoTime() - start;

      assertThat(response.getStatusCode())
          .as("Iteration %d: GET /fanout must return 200", i)
          .isEqualTo(HttpStatus.OK);
    }

    Arrays.sort(nanos);
    long p99Nanos = LatencyStats.percentile(nanos, 99.0);
    long p99Millis = p99Nanos / 1_000_000;

    System.out.printf(
        "[SlaValidationTest] 50 iterations, P99 latency = %d ms (SLA: 500 ms)%n", p99Millis);

    assertThat(p99Nanos)
        .as("P99 latency must remain under 500 ms even with 40%% SOCKET_READ delay injected")
        .isLessThan(Duration.ofMillis(500).toNanos());
  }
}
