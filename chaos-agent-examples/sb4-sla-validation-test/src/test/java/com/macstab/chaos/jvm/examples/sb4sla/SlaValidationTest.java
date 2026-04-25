package com.macstab.chaos.jvm.examples.sb4sla;

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
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.spring.boot4.test.ChaosTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

@ChaosTest(classes = SlaApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class SlaValidationTest {

  private static final Logger log = LoggerFactory.getLogger(SlaValidationTest.class);

  private static final int WARMUP = 10;
  private static final int ITERATIONS = 30;
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
   * Verifies that a 40% probabilistic outbound HTTP delay stays within 500 ms of the clean
   * baseline.
   *
   * <p>Uses {@link OperationType#HTTP_CLIENT_SEND} which fires once per
   * {@code java.net.http.HttpClient.send(...)} call (the agent self-grants the required JDK module
   * open at install time, so no {@code --add-opens} JVM flag is required). The fanout service
   * issues 3 outbound HTTP calls per request, each running in its own virtual thread; with
   * probability 0.4 each call independently gets a 20–80 ms delay.
   *
   * <p>The 500 ms budget is relative to the measured baseline so the assertion holds regardless of
   * worker speed.
   */
  @Test
  void probabilisticDelayStaysWithin500msOfBaseline(final ChaosControlPlane controlPlane) {
    final RestClient client = RestClient.create("http://localhost:" + port);

    warmup(client);
    final long p99Baseline = measureP99(client);

    final ChaosActivationHandle handle =
        controlPlane.activate(
            ChaosScenario.builder("http-client-send-delay-40pct")
                .description("40% probability of 20-80ms HTTP_CLIENT_SEND delay")
                .scope(ScenarioScope.JVM)
                .selector(ChaosSelector.httpClient(Set.of(OperationType.HTTP_CLIENT_SEND)))
                .effect(ChaosEffect.delay(Duration.ofMillis(20), Duration.ofMillis(80)))
                .activationPolicy(
                    new ActivationPolicy(
                        ActivationPolicy.StartMode.AUTOMATIC, 0.4, 0, null, null, null, 0L, false))
                .build());

    try {
      final long p99WithChaos = measureP99(client);

      log.info(
          "baseline P99={}ms  chaos P99={}ms", p99Baseline / 1_000_000, p99WithChaos / 1_000_000);

      assertThat(p99WithChaos)
          .as("P99 with 40%% HTTP-send delay must stay within 500 ms of baseline P99")
          .isLessThan(p99Baseline + Duration.ofMillis(500).toNanos());
    } finally {
      handle.stop();
    }
  }

  /**
   * Verifies that stacking outbound-HTTP delay scenarios raises P99 additively.
   *
   * <p>Each scenario injects a fixed 80 ms delay on every {@link OperationType#HTTP_CLIENT_SEND}
   * across the entire JVM. The fanout service issues 3 parallel HTTP calls per request; because
   * the calls run on virtual threads in parallel, each stacked scenario adds at least one
   * additional 80 ms wait to the slowest leg, lifting P99 by at least 80 ms per added scenario.
   *
   * <p>All assertions are anchored to the single baseline measurement so per-step measurement
   * noise (GC pauses, scheduler jitter on slow runners) does not compound: if {@code p99_1}
   * happens to spike, only its own assertion is at risk — {@code p99_2} and {@code p99_3} are
   * still compared against the stable baseline, not the noisy intermediate measurement.
   */
  @Test
  void graduatedDelayLadderStacksAdditively(final ChaosControlPlane controlPlane) {
    final RestClient client = RestClient.create("http://localhost:" + port);

    warmup(client);
    final long p99_0 = measureP99(client);

    final List<ChaosActivationHandle> handles = new ArrayList<>();
    try {
      handles.add(controlPlane.activate(fixedDelayScenario("delay-1")));
      final long p99_1 = measureP99(client);

      handles.add(controlPlane.activate(fixedDelayScenario("delay-2")));
      final long p99_2 = measureP99(client);

      handles.add(controlPlane.activate(fixedDelayScenario("delay-3")));
      final long p99_3 = measureP99(client);

      log.info(
          "P99 ladder: 0={}ms  1={}ms  2={}ms  3={}ms",
          p99_0 / 1_000_000,
          p99_1 / 1_000_000,
          p99_2 / 1_000_000,
          p99_3 / 1_000_000);

      assertThat(p99_1)
          .as("1 delay scenario must raise P99 by at least 80 ms over baseline")
          .isGreaterThan(p99_0 + STEP_NANOS);
      assertThat(p99_2)
          .as("2 stacked scenarios must raise P99 by at least 160 ms over baseline")
          .isGreaterThan(p99_0 + 2 * STEP_NANOS);
      assertThat(p99_3)
          .as("3 stacked scenarios must raise P99 by at least 240 ms over baseline")
          .isGreaterThan(p99_0 + 3 * STEP_NANOS);
    } finally {
      handles.forEach(ChaosActivationHandle::stop);
    }
  }

  private void warmup(final RestClient client) {
    for (int i = 0; i < WARMUP; i++) {
      client.get().uri("/fanout").retrieve().toEntity(FanOutResult.class);
    }
  }

  private long measureP99(final RestClient client) {
    final long[] nanos = new long[ITERATIONS];
    for (int i = 0; i < ITERATIONS; i++) {
      final long start = System.nanoTime();
      final ResponseEntity<FanOutResult> response =
          client.get().uri("/fanout").retrieve().toEntity(FanOutResult.class);
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
        .scope(ScenarioScope.JVM)
        .selector(ChaosSelector.httpClient(Set.of(OperationType.HTTP_CLIENT_SEND)))
        .effect(ChaosEffect.delay(Duration.ofMillis(80), Duration.ofMillis(80)))
        .activationPolicy(ActivationPolicy.always())
        .build();
  }
}
