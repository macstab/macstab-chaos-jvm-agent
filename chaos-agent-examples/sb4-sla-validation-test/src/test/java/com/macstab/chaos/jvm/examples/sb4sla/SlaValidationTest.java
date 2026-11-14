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

  /**
   * Fixed delay applied per stacked scenario (120 ms). Sized to give the assertion threshold
   * meaningful absolute headroom over OS scheduler imprecision: macOS {@code mach_wait_until}
   * commonly returns 1–5 ms early, Windows {@code Thread.sleep} can fall back to ~16 ms timer-tick
   * granularity. With a 120 ms delay and 96 ms threshold (0.8×), absolute headroom is 24 ms — wide
   * enough to absorb a single Windows tick or a small GC pause without flaking, while keeping the
   * full ladder run under ~25 s.
   */
  private static final long STEP_NANOS = Duration.ofMillis(120).toNanos();

  /**
   * Lower bound on the P99 increment we expect per stacked scenario. {@code 0.8 × STEP_NANOS}
   * mirrors the absorption ratio used in {@code InstrumentationIntegrationTest.DELAY_MIN_MS} and
   * accounts for {@code Thread.sleep} returning slightly early on every supported platform.
   */
  private static final long STEP_MIN_NANOS = (long) (STEP_NANOS * 0.8);

  /**
   * How far the P99 under the probabilistic delay may rise above the baseline. The three calls of a
   * request run in parallel, so the delay adds at most 80 ms to it; the rest is room for a slow
   * runner's GC and scheduling pauses. The budget is that wide for shared CI runners, so only a
   * gross error exceeds it, such as a delay of seconds (a unit mix-up); a delay a little outside
   * its 20–80 ms range, or applied twice per call, stays within it.
   */
  private static final Duration SLA_BUDGET = Duration.ofSeconds(2);

  private static WireMockServer wireMockA;
  private static WireMockServer wireMockB;
  private static WireMockServer wireMockC;

  @LocalServerPort private int port;

  @BeforeAll
  static void startWireMocks() {
    if (wireMockA != null) {
      return;
    }
    // Free ports, not fixed ones: modules run in parallel and may run this test at once.
    wireMockA =
        new WireMockServer(WireMockConfiguration.options().bindAddress("127.0.0.1").dynamicPort());
    wireMockB =
        new WireMockServer(WireMockConfiguration.options().bindAddress("127.0.0.1").dynamicPort());
    wireMockC =
        new WireMockServer(WireMockConfiguration.options().bindAddress("127.0.0.1").dynamicPort());

    wireMockA.start();
    wireMockB.start();
    wireMockC.start();

    wireMockA.stubFor(get(urlEqualTo("/a")).willReturn(aResponse().withStatus(200).withBody("ok")));
    wireMockB.stubFor(get(urlEqualTo("/b")).willReturn(aResponse().withStatus(200).withBody("ok")));
    wireMockC.stubFor(get(urlEqualTo("/c")).willReturn(aResponse().withStatus(200).withBody("ok")));
  }

  @org.springframework.test.context.DynamicPropertySource
  static void downstreams(final org.springframework.test.context.DynamicPropertyRegistry registry) {
    if (wireMockA == null) {
      startWireMocks();
    }
    registry.add("downstream.a.url", () -> "http://127.0.0.1:" + wireMockA.port() + "/a");
    registry.add("downstream.b.url", () -> "http://127.0.0.1:" + wireMockB.port() + "/b");
    registry.add("downstream.c.url", () -> "http://127.0.0.1:" + wireMockC.port() + "/c");
  }

  @AfterAll
  static void stopWireMocks() {
    if (wireMockA != null) wireMockA.stop();
    if (wireMockB != null) wireMockB.stop();
    if (wireMockC != null) wireMockC.stop();
    wireMockA = null;
    wireMockB = null;
    wireMockC = null;
  }

  /**
   * Verifies that a 40% probabilistic outbound HTTP delay stays within {@link #SLA_BUDGET} of the
   * clean baseline.
   *
   * <p>Uses {@link OperationType#HTTP_CLIENT_SEND} which fires once per {@code
   * java.net.http.HttpClient.send(...)} call (the agent self-grants the required JDK module open at
   * install time, so no {@code --add-opens} JVM flag is required). The fanout service issues 3
   * outbound HTTP calls per request, each running in its own virtual thread; with probability 0.4
   * each call independently gets a 20–80 ms delay.
   *
   * <p>The budget is relative to the measured baseline so the assertion holds regardless of worker
   * speed.
   */
  @Test
  void probabilisticDelayStaysWithinBudgetOfBaseline(final ChaosControlPlane controlPlane) {
    final RestClient client = RestClient.create("http://localhost:" + port);

    warmup(client);
    quiesce();
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
      quiesce();
      final long p99WithChaos = measureP99(client);

      log.info(
          "baseline P99={}ms  chaos P99={}ms", p99Baseline / 1_000_000, p99WithChaos / 1_000_000);

      assertThat(p99WithChaos)
          .as("P99 with 40%% HTTP-send delay must stay within %s of baseline P99", SLA_BUDGET)
          .isLessThan(p99Baseline + SLA_BUDGET.toNanos());
      assertThat(handle.appliedCount())
          .as("the delay applied to some of the %d outbound calls", 3 * ITERATIONS)
          .isPositive();
    } finally {
      handle.stop();
    }
  }

  /**
   * Verifies that stacking outbound-HTTP delay scenarios raises P99 additively.
   *
   * <p>Each scenario injects a fixed 120 ms delay on every {@link OperationType#HTTP_CLIENT_SEND}
   * across the entire JVM. The fanout service issues 3 parallel HTTP calls per request; because the
   * calls run on virtual threads in parallel, each stacked scenario adds one additional 120 ms wait
   * to the slowest leg, lifting P99 by approximately 120 ms per added scenario.
   *
   * <p><b>Threshold:</b> assertions use {@link #STEP_MIN_NANOS} (96 ms) rather than the full
   * configured delay. The chaos agent applies the delay via {@code Thread.sleep(120)}, and OS
   * scheduler granularity (especially on macOS and Windows) can return slightly under the requested
   * duration. The 0.8× factor mirrors {@code InstrumentationIntegrationTest} and gives 24 ms
   * absolute headroom — large enough to absorb one Windows timer tick or a young-gen GC pause
   * without producing false negatives.
   *
   * <p><b>Per-step quiescence:</b> {@link #quiesce()} is called before every measurement so an
   * incidental GC pause that happens to fall inside one of the 30-iteration windows does not
   * inflate that step's P99 above its real value. All assertions are anchored to the single
   * baseline measurement so per-step measurement noise does not compound. The baseline is the
   * median, not the P99: with a fixed delay every request of step {@code n} takes at least {@code
   * n} steps longer than a typical baseline request, while a baseline P99 is its slowest outlier,
   * which a loaded machine can push above the delayed step's P99.
   */
  @Test
  void graduatedDelayLadderStacksAdditively(final ChaosControlPlane controlPlane) {
    final RestClient client = RestClient.create("http://localhost:" + port);

    warmup(client);
    quiesce();
    final long baselineMedian = measurePercentile(client, 50.0);

    final List<ChaosActivationHandle> handles = new ArrayList<>();
    try {
      handles.add(controlPlane.activate(fixedDelayScenario("delay-1")));
      quiesce();
      final long p99_1 = measureP99(client);

      handles.add(controlPlane.activate(fixedDelayScenario("delay-2")));
      quiesce();
      final long p99_2 = measureP99(client);

      handles.add(controlPlane.activate(fixedDelayScenario("delay-3")));
      quiesce();
      final long p99_3 = measureP99(client);

      log.info(
          "P99 ladder (0 = baseline median): 0={}ms  1={}ms  2={}ms  3={}ms  (threshold/step={}ms)",
          baselineMedian / 1_000_000,
          p99_1 / 1_000_000,
          p99_2 / 1_000_000,
          p99_3 / 1_000_000,
          STEP_MIN_NANOS / 1_000_000);

      assertThat(p99_1)
          .as(
              "1 delay scenario must raise P99 by at least %d ms over baseline",
              STEP_MIN_NANOS / 1_000_000)
          .isGreaterThanOrEqualTo(baselineMedian + STEP_MIN_NANOS);
      assertThat(p99_2)
          .as(
              "2 stacked scenarios must raise P99 by at least %d ms over baseline",
              2 * STEP_MIN_NANOS / 1_000_000)
          .isGreaterThanOrEqualTo(baselineMedian + 2 * STEP_MIN_NANOS);
      assertThat(p99_3)
          .as(
              "3 stacked scenarios must raise P99 by at least %d ms over baseline",
              3 * STEP_MIN_NANOS / 1_000_000)
          .isGreaterThanOrEqualTo(baselineMedian + 3 * STEP_MIN_NANOS);
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
    return measurePercentile(client, 99.0);
  }

  private long measurePercentile(final RestClient client, final double percentile) {
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
    return LatencyStats.percentile(nanos, percentile);
  }

  private static ChaosScenario fixedDelayScenario(final String id) {
    final long stepMs = STEP_NANOS / 1_000_000;
    return ChaosScenario.builder(id)
        .scope(ScenarioScope.JVM)
        .selector(ChaosSelector.httpClient(Set.of(OperationType.HTTP_CLIENT_SEND)))
        .effect(ChaosEffect.delay(Duration.ofMillis(stepMs), Duration.ofMillis(stepMs)))
        .activationPolicy(ActivationPolicy.always())
        .build();
  }

  /**
   * Best-effort quiescence between P99 measurements: triggers GC three times with short pauses so
   * deferred collections do not land inside the next 30-iteration window and inflate its P99.
   * {@code System.gc()} is a hint only; three rounds with 50 ms gaps gives G1 / ZGC enough time to
   * actually run on every supported JDK. Cost: ~150 ms per call, ~600 ms total for the ladder.
   */
  private static void quiesce() {
    for (int i = 0; i < 3; i++) {
      System.gc();
      try {
        Thread.sleep(50);
      } catch (final InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }
}
