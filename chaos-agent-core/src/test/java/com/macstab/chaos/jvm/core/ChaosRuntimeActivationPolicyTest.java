package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosEvent;
import com.macstab.chaos.jvm.api.ChaosMetricsSink;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ChaosRuntime — ActivationPolicy")
class ChaosRuntimeActivationPolicyTest {

  /**
   * The delay of the timed scenarios. Each check asserts the applied count first; the elapsed time
   * is checked against the delay itself: a delayed call takes at least all of it, an undelayed one
   * less than half of it.
   */
  private static final Duration DELAY = Duration.ofMillis(200);

  private static final long DELAY_NANOS = DELAY.toNanos();

  /** Loads and runs the evaluation path once, so no first call pays for class loading. */
  @BeforeAll
  static void warmUp() {
    try (ChaosRuntime runtime = new ChaosRuntime()) {
      runtime.activate(
          ChaosScenario.builder("warm-up")
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ZERO))
              .build());
      runtime.decorateExecutorRunnable(
          "EXECUTOR_SUBMIT", ChaosRuntimeActivationPolicyTest.class, () -> {});
    }
  }

  @Nested
  @DisplayName("activateAfterMatches")
  class ActivateAfterMatches {

    @Test
    @DisplayName("activateAfterMatches=3 skips first three matches")
    void activateAfterMatchesThreeSkipsFirstThreeMatches() {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        runtime.activate(
            ChaosScenario.builder("warm-up")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                .effect(ChaosEffect.delay(DELAY))
                .activationPolicy(
                    new ActivationPolicy(
                        ActivationPolicy.StartMode.AUTOMATIC,
                        1.0d,
                        3,
                        null,
                        null,
                        null,
                        null,
                        false))
                .build());

        for (int i = 0; i < 3; i++) {
          final long elapsed = submitNanos(runtime);
          assertThat(applied(runtime, "warm-up")).as("match %d is not applied", i).isZero();
          assertThat(elapsed).as("match %d is not delayed", i).isLessThan(DELAY_NANOS / 2);
        }

        final long elapsed = submitNanos(runtime);
        assertThat(applied(runtime, "warm-up")).as("the 4th match is applied").isEqualTo(1L);
        assertThat(elapsed).as("the 4th match is delayed").isGreaterThanOrEqualTo(DELAY_NANOS);
      }
    }
  }

  @Nested
  @DisplayName("maxApplications")
  class MaxApplications {

    @Test
    @DisplayName("maxApplications=2 fires exactly twice")
    void maxApplicationsTwoFiresExactlyTwice() {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        runtime.activate(
            ChaosScenario.builder("max-two")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                .effect(ChaosEffect.delay(DELAY))
                .activationPolicy(
                    new ActivationPolicy(
                        ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 0, 2L, null, null, null, false))
                .build());

        final long first = submitNanos(runtime);
        final long second = submitNanos(runtime);
        final long third = submitNanos(runtime);

        assertThat(applied(runtime, "max-two")).as("applied exactly twice").isEqualTo(2L);
        assertThat(first).as("first is delayed").isGreaterThanOrEqualTo(DELAY_NANOS);
        assertThat(second).as("second is delayed").isGreaterThanOrEqualTo(DELAY_NANOS);
        assertThat(third).as("third is not delayed").isLessThan(DELAY_NANOS / 2);
      }
    }
  }

  @Nested
  @DisplayName("addEventListener")
  class EventListenerTests {

    @Test
    @DisplayName("receives STARTED and APPLIED events")
    void addEventListenerReceivesStartedAndAppliedEvents() {
      ChaosRuntime runtime = new ChaosRuntime();
      List<ChaosEvent> events = new ArrayList<>();
      runtime.addEventListener(events::add);

      runtime.activate(
          ChaosScenario.builder("listen-test")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ofMillis(1)))
              .activationPolicy(ActivationPolicy.always())
              .build());

      runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});

      assertThat(events)
          .anyMatch(
              e -> e.type() == ChaosEvent.Type.STARTED && "listen-test".equals(e.scenarioId()));
      assertThat(events)
          .anyMatch(
              e -> e.type() == ChaosEvent.Type.APPLIED && "listen-test".equals(e.scenarioId()));
    }
  }

  @Nested
  @DisplayName("diagnostics snapshot counters")
  class DiagnosticsCounters {

    @Test
    @DisplayName("snapshot reflects matched and applied count")
    void diagnosticsSnapshotReflectsMatchedAndAppliedCount() {
      ChaosRuntime runtime = new ChaosRuntime();
      runtime.activate(
          ChaosScenario.builder("counter-test")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ofMillis(1)))
              .activationPolicy(ActivationPolicy.always())
              .build());

      runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
      runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});

      ChaosDiagnostics.Snapshot snapshot = runtime.diagnostics().snapshot();
      ChaosDiagnostics.ScenarioReport report =
          snapshot.scenarios().stream()
              .filter(r -> "counter-test".equals(r.id()))
              .findFirst()
              .orElseThrow(() -> new AssertionError("scenario not found in snapshot"));

      assertThat(report.matchedCount()).as("matched count").isEqualTo(2);
      assertThat(report.appliedCount()).as("applied count").isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("activeFor with clock injection")
  class ActiveForClockInjection {

    @Test
    @DisplayName("effect expires after clock advance past activeFor window")
    void activeForExpiresAfterClockAdvance() {
      final MutableClock clock = new MutableClock();
      try (ChaosRuntime runtime = new ChaosRuntime(clock, ChaosMetricsSink.NOOP)) {
        runtime.activate(
            ChaosScenario.builder("time-bounded")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                .effect(ChaosEffect.delay(DELAY))
                .activationPolicy(
                    new ActivationPolicy(
                        ActivationPolicy.StartMode.AUTOMATIC,
                        1.0d,
                        0,
                        null,
                        Duration.ofSeconds(5),
                        null,
                        null,
                        false))
                .build());

        final long firstElapsed = submitNanos(runtime);
        assertThat(applied(runtime, "time-bounded")).as("applied in the window").isEqualTo(1L);
        assertThat(firstElapsed)
            .as("delay within active window")
            .isGreaterThanOrEqualTo(DELAY_NANOS);

        clock.advance(Duration.ofSeconds(10));

        final long secondElapsed = submitNanos(runtime);
        assertThat(applied(runtime, "time-bounded"))
            .as("not applied after activeFor expiry")
            .isEqualTo(1L);
        assertThat(secondElapsed).as("no delay after activeFor expiry").isLessThan(DELAY_NANOS / 2);
      }
    }
  }

  @Nested
  @DisplayName("rateLimit enforcement")
  class RateLimitEnforcement {

    @Test
    @DisplayName("a contribution that does not take effect gives its permit back")
    void permitOfAnInapplicableContributionIsReturned() throws Throwable {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        runtime.activate(
            ChaosScenario.builder("trickle-limited")
                .selector(ChaosSelector.network(Set.of(OperationType.SOCKET_READ)))
                .effect(ChaosEffect.trickle(16))
                .activationPolicy(
                    ActivationPolicy.builder()
                        .rateLimit(new ActivationPolicy.RateLimit(1L, Duration.ofHours(1)))
                        .build())
                .build());

        // A read no longer than the trickle cap cannot be trickled: it must not use the permit.
        assertThat(runtime.beforeSocketRead(this, 8, null, 0, 0)).isEqualTo(8);
        assertThat(runtime.beforeSocketRead(this, 100, null, 0, 0)).isEqualTo(16);
        assertThat(runtime.beforeSocketRead(this, 100, null, 0, 0))
            .as("the window's one permit is used")
            .isEqualTo(100);
      }
    }

    @Test
    @DisplayName("rateLimit=1/window allows only one application per window")
    void rateLimitOnePerWindowAllowsOneApplication() {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        runtime.activate(rateLimited("rate-limited", 1L));

        final long first = submitNanos(runtime);
        final long second = submitNanos(runtime);

        assertThat(applied(runtime, "rate-limited")).as("one application").isEqualTo(1L);
        assertThat(first)
            .as("first call within window should be delayed")
            .isGreaterThanOrEqualTo(DELAY_NANOS);
        assertThat(second)
            .as("second call exceeds rate limit, should not be delayed")
            .isLessThan(DELAY_NANOS / 2);
      }
    }

    @Test
    @DisplayName("rateLimit=2/window fires first two then blocks until window expires")
    void rateLimitTwoPerWindowFiresTwiceThenBlocks() {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        runtime.activate(rateLimited("rate-limited-2", 2L));

        final long first = submitNanos(runtime);
        final long second = submitNanos(runtime);
        final long third = submitNanos(runtime);

        assertThat(applied(runtime, "rate-limited-2")).as("two applications").isEqualTo(2L);
        assertThat(first).as("first should be delayed").isGreaterThanOrEqualTo(DELAY_NANOS);
        assertThat(second).as("second should be delayed").isGreaterThanOrEqualTo(DELAY_NANOS);
        assertThat(third)
            .as("third exceeds rate limit, should not be delayed")
            .isLessThan(DELAY_NANOS / 2);
      }
    }

    private ChaosScenario rateLimited(final String id, final long permits) {
      return ChaosScenario.builder(id)
          .scope(ChaosScenario.ScenarioScope.JVM)
          .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
          .effect(ChaosEffect.delay(DELAY))
          .activationPolicy(
              new ActivationPolicy(
                  ActivationPolicy.StartMode.AUTOMATIC,
                  1.0d,
                  0,
                  null,
                  null,
                  new ActivationPolicy.RateLimit(permits, Duration.ofSeconds(10)),
                  null,
                  false))
          .build();
    }
  }

  @Nested
  @DisplayName("probability sampling")
  class ProbabilitySampling {

    @Test
    @DisplayName("probability=1.0 fires on every match")
    void probabilityOneFiresOnEveryMatch() {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        runtime.activate(
            ChaosScenario.builder("prob-always")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                .effect(ChaosEffect.delay(DELAY))
                .activationPolicy(
                    new ActivationPolicy(
                        ActivationPolicy.StartMode.AUTOMATIC,
                        1.0d,
                        0,
                        null,
                        null,
                        null,
                        null,
                        false))
                .build());

        for (int i = 0; i < 5; i++) {
          final long elapsed = submitNanos(runtime);
          assertThat(applied(runtime, "prob-always")).as("call %d is applied", i).isEqualTo(i + 1L);
          assertThat(elapsed)
              .as("call %d should always be delayed", i)
              .isGreaterThanOrEqualTo(DELAY_NANOS);
        }
      }
    }

    @Test
    @DisplayName("probability=0.1 with fixed seed fires on some but not all matches")
    void probabilityLowWithFixedSeedFiresSometimes() {
      try (ChaosRuntime runtime = new ChaosRuntime()) {
        runtime.activate(
            ChaosScenario.builder("prob-low")
                .scope(ChaosScenario.ScenarioScope.JVM)
                .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
                .effect(ChaosEffect.delay(Duration.ZERO))
                .activationPolicy(
                    new ActivationPolicy(
                        ActivationPolicy.StartMode.AUTOMATIC,
                        0.1d,
                        0,
                        null,
                        null,
                        null,
                        42L,
                        false))
                .build());

        for (int i = 0; i < 100; i++) {
          runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
        }

        final ChaosDiagnostics.ScenarioReport report =
            runtime.diagnostics().scenario("prob-low").orElseThrow();
        assertThat(report.matchedCount()).isEqualTo(100L);
        assertThat(report.appliedCount())
            .as("some, but far from all, of 100 matches apply at probability 0.1")
            .isBetween(1L, 30L);
      }
    }
  }

  /** Submits one task through the runtime and returns how long the submission took. */
  private long submitNanos(final ChaosRuntime runtime) {
    final long start = System.nanoTime();
    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
    return System.nanoTime() - start;
  }

  private static long applied(final ChaosRuntime runtime, final String id) {
    return runtime.diagnostics().scenario(id).orElseThrow().appliedCount();
  }

  static final class MutableClock extends Clock {
    private volatile Instant now = Instant.now();

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
