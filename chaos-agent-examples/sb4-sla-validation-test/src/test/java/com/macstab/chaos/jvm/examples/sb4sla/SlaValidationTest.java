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

  private static final int ITERATIONS = 100;
  private static final long STEP_NANOS = Duration.ofMillis(80).toNanos();

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

  /**
   * Verifies that 40% probabilistic socket-read delay stays within 500 ms of the clean baseline.
   *
   * <p>The 500 ms budget is relative to the measured baseline so the assertion holds regardless of
   * worker speed.
   */
  @Test
  void probabilisticDelayStaysWithin500msOfBaseline(ChaosSession session) {
    final RestClient client = RestClient.create("http://localhost:" + port);

    final long p99Baseline = measureP99(session, client);

    session.activate(
        ChaosScenario.builder("socket-read-delay-40pct")
            .description("40% probability of 20-80ms SOCKET_READ delay")
            .scope(ScenarioScope.SESSION)
            .selector(ChaosSelector.network(Set.of(OperationType.SOCKET_READ)))
            .effect(ChaosEffect.delay(Duration.ofMillis(20), Duration.ofMillis(80)))
            .activationPolicy(
                new ActivationPolicy(
                    ActivationPolicy.StartMode.AUTOMATIC, 0.4, 0, null, null, null, 0L, false))
            .build());

    final long p99WithChaos = measureP99(session, client);

    System.out.printf(
        "[SlaValidationTest] baseline P99=%dms  chaos P99=%dms%n",
        p99Baseline / 1_000_000, p99WithChaos / 1_000_000);

    assertThat(p99WithChaos)
        .as("P99 with 40%% socket-read delay must stay within 500 ms of baseline P99")
        .isLessThan(p99Baseline + Duration.ofMillis(500).toNanos());
  }

  /**
   * Verifies that stacking delay scenarios raises P99 additively.
   *
   * <p>Each scenario injects a fixed 80 ms delay on every SOCKET_READ. Because each HTTP exchange
   * triggers multiple reads, each new scenario adds more than 80 ms to the measured P99. The
   * ordering assertion is worker-speed-independent: it relies on relative differences, not absolute
   * thresholds.
   */
  @Test
  void graduatedDelayLadderStacksAdditively(ChaosSession session) {
    final RestClient client = RestClient.create("http://localhost:" + port);

    final long p99_0 = measureP99(session, client);

    session.activate(fixedDelayScenario("delay-1"));
    final long p99_1 = measureP99(session, client);

    session.activate(fixedDelayScenario("delay-2"));
    final long p99_2 = measureP99(session, client);

    session.activate(fixedDelayScenario("delay-3"));
    final long p99_3 = measureP99(session, client);

    System.out.printf(
        "[SlaValidationTest] P99 ladder: 0=%dms  1=%dms  2=%dms  3=%dms%n",
        p99_0 / 1_000_000, p99_1 / 1_000_000, p99_2 / 1_000_000, p99_3 / 1_000_000);

    assertThat(p99_1)
        .as("1 delay scenario must raise P99 by at least 80 ms over baseline")
        .isGreaterThan(p99_0 + STEP_NANOS);
    assertThat(p99_2)
        .as("2 stacked scenarios must raise P99 by at least 80 ms over 1-scenario P99")
        .isGreaterThan(p99_1 + STEP_NANOS);
    assertThat(p99_3)
        .as("3 stacked scenarios must raise P99 by at least 80 ms over 2-scenario P99")
        .isGreaterThan(p99_2 + STEP_NANOS);
  }

  private long measureP99(final ChaosSession session, final RestClient client) {
    final long[] nanos = new long[ITERATIONS];
    for (int i = 0; i < ITERATIONS; i++) {
      final long start = System.nanoTime();
      final ResponseEntity<FanOutResult> response;
      try (ChaosSession.ScopeBinding binding = session.bind()) {
        response = client.get().uri("/fanout").retrieve().toEntity(FanOutResult.class);
      }
      nanos[i] = System.nanoTime() - start;

      assertThat(response.getStatusCode())
          .as("Iteration %d: GET /fanout must return 200", i)
          .isEqualTo(HttpStatus.OK);
    }
    Arrays.sort(nanos);
    return LatencyStats.percentile(nanos, 99.0);
  }

  private static ChaosScenario fixedDelayScenario(final String id) {
    return ChaosScenario.builder(id)
        .scope(ScenarioScope.SESSION)
        .selector(ChaosSelector.network(Set.of(OperationType.SOCKET_READ)))
        .effect(ChaosEffect.delay(Duration.ofMillis(80), Duration.ofMillis(80)))
        .activationPolicy(ActivationPolicy.always())
        .build();
  }
}
