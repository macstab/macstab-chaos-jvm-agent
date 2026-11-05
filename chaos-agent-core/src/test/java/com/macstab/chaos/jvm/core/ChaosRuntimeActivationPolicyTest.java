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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ChaosRuntime — ActivationPolicy")
class ChaosRuntimeActivationPolicyTest {

  private static final long DELAY_MIN_MS = 48L;
  private static final long NO_DELAY_MAX_MS = 150L;

  @Nested
  @DisplayName("activateAfterMatches")
  class ActivateAfterMatches {

    @Test
    @DisplayName("activateAfterMatches=3 skips first three matches")
    void activateAfterMatchesThreeSkipsFirstThreeMatches() {
      ChaosRuntime runtime = new ChaosRuntime();
      runtime.activate(
          ChaosScenario.builder("warm-up")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ofMillis(80)))
              .activationPolicy(
                  new ActivationPolicy(
                      ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 3, null, null, null, null, false))
              .build());

      for (int i = 0; i < 3; i++) {
        long elapsed =
            measureMillis(
                () -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}));
        assertThat(elapsed).as("match %d should not be delayed", i).isLessThan(NO_DELAY_MAX_MS);
      }

      long elapsed =
          measureMillis(() -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}));
      assertThat(elapsed).as("4th match should incur delay").isGreaterThanOrEqualTo(60);
    }
  }

  @Nested
  @DisplayName("maxApplications")
  class MaxApplications {

    @Test
    @DisplayName("maxApplications=2 fires exactly twice")
    void maxApplicationsTwoFiresExactlyTwice() {
      ChaosRuntime runtime = new ChaosRuntime();
      runtime.activate(
          ChaosScenario.builder("max-two")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ofMillis(60)))
              .activationPolicy(
                  new ActivationPolicy(
                      ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 0, 2L, null, null, null, false))
              .build());

      long first =
          measureMillis(() -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}));
      long second =
          measureMillis(() -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}));
      long third =
          measureMillis(() -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}));

      assertThat(first).as("first should be delayed").isGreaterThanOrEqualTo(DELAY_MIN_MS);
      assertThat(second).as("second should be delayed").isGreaterThanOrEqualTo(DELAY_MIN_MS);
      assertThat(third).as("third should not be delayed").isLessThan(NO_DELAY_MAX_MS);
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
      MutableClock clock = new MutableClock();
      ChaosRuntime runtime = new ChaosRuntime(clock, ChaosMetricsSink.NOOP);
      runtime.activate(
          ChaosScenario.builder("time-bounded")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ofMillis(60)))
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

      long firstElapsed =
          measureMillis(() -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}));
      assertThat(firstElapsed)
          .as("delay within active window")
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);

      clock.advance(Duration.ofSeconds(10));

      long secondElapsed =
          measureMillis(() -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}));
      assertThat(secondElapsed).as("no delay after activeFor expiry").isLessThan(NO_DELAY_MAX_MS);
    }
  }

  @Nested
  @DisplayName("rateLimit enforcement")
  class RateLimitEnforcement {

    @Test
    @DisplayName("rateLimit=1/window allows only one application per window")
    void rateLimitOnePerWindowAllowsOneApplication() {
      ChaosRuntime runtime = new ChaosRuntime();
      runtime.activate(
          ChaosScenario.builder("rate-limited")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ofMillis(60)))
              .activationPolicy(
                  new ActivationPolicy(
                      ActivationPolicy.StartMode.AUTOMATIC,
                      1.0d,
                      0,
                      null,
                      null,
                      new ActivationPolicy.RateLimit(1L, Duration.ofSeconds(10)),
                      null,
                      false))
              .build());

      long first =
          measureMillis(() -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}));
      long second =
          measureMillis(() -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}));

      assertThat(first)
          .as("first call within window should be delayed")
          .isGreaterThanOrEqualTo(DELAY_MIN_MS);
      assertThat(second)
          .as("second call exceeds rate limit, should not be delayed")
          .isLessThan(NO_DELAY_MAX_MS);
    }

    @Test
    @DisplayName("rateLimit=2/window fires first two then blocks until window expires")
    void rateLimitTwoPerWindowFiresTwiceThenBlocks() {
      ChaosRuntime runtime = new ChaosRuntime();
      runtime.activate(
          ChaosScenario.builder("rate-limited-2")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ofMillis(60)))
              .activationPolicy(
                  new ActivationPolicy(
                      ActivationPolicy.StartMode.AUTOMATIC,
                      1.0d,
                      0,
                      null,
                      null,
                      new ActivationPolicy.RateLimit(2L, Duration.ofSeconds(10)),
                      null,
                      false))
              .build());

      long first =
          measureMillis(() -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}));
      long second =
          measureMillis(() -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}));
      long third =
          measureMillis(() -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}));

      assertThat(first).as("first should be delayed").isGreaterThanOrEqualTo(DELAY_MIN_MS);
      assertThat(second).as("second should be delayed").isGreaterThanOrEqualTo(DELAY_MIN_MS);
      assertThat(third)
          .as("third exceeds rate limit, should not be delayed")
          .isLessThan(NO_DELAY_MAX_MS);
    }
  }

  @Nested
  @DisplayName("probability sampling")
  class ProbabilitySampling {

    @Test
    @DisplayName("probability=1.0 fires on every match")
    void probabilityOneFiresOnEveryMatch() {
      ChaosRuntime runtime = new ChaosRuntime();
      runtime.activate(
          ChaosScenario.builder("prob-always")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ofMillis(60)))
              .activationPolicy(
                  new ActivationPolicy(
                      ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 0, null, null, null, null, false))
              .build());

      for (int i = 0; i < 5; i++) {
        long elapsed =
            measureMillis(
                () -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}));
        assertThat(elapsed)
            .as("call %d should always be delayed", i)
            .isGreaterThanOrEqualTo(DELAY_MIN_MS);
      }
    }

    @Test
    @DisplayName("probability=0.1 with fixed seed fires on some but not all matches")
    void probabilityLowWithFixedSeedFiresSometimes() {
      // With probability 0.1 and a large number of calls, some should fire and some should not.
      ChaosRuntime runtime = new ChaosRuntime();
      runtime.activate(
          ChaosScenario.builder("prob-low")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ofMillis(60)))
              .activationPolicy(
                  new ActivationPolicy(
                      ActivationPolicy.StartMode.AUTOMATIC, 0.1d, 0, null, null, null, 42L, false))
              .build());

      int delayed = 0;
      int notDelayed = 0;
      for (int i = 0; i < 20; i++) {
        long elapsed =
            measureMillis(
                () -> runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {}));
        if (elapsed >= 40) {
          delayed++;
        } else {
          notDelayed++;
        }
      }
      assertThat(delayed).as("some calls should be delayed with 0.1 probability").isGreaterThan(0);
      assertThat(notDelayed)
          .as("many calls should NOT be delayed with 0.1 probability")
          .isGreaterThan(5);
    }
  }

  private long measureMillis(Runnable runnable) {
    long start = System.nanoTime();
    runnable.run();
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
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
