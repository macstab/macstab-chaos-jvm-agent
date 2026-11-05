package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosMetricsSink;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisplayName("Activation-policy arithmetic")
class PolicyArithmeticTest {

  @Nested
  @DisplayName("rate limit")
  class RateLimitWindow {

    private final MutableClock clock = new MutableClock(Instant.ofEpochMilli(10_500L));
    private final ChaosRuntime runtime = new ChaosRuntime(clock, ChaosMetricsSink.NOOP);

    @Test
    @Tag("law-L10")
    @DisplayName("the first window starts when the scenario starts, not at an epoch boundary")
    void windowIsAnchoredAtStart() {
      runtime.activate(rateLimited(1, Duration.ofSeconds(1)));
      submit();

      // 11_100 ms crosses the epoch-aligned boundary 11_000 but is still inside the window that
      // started at 10_500 ms.
      clock.set(Instant.ofEpochMilli(11_100L));
      submit();

      assertThat(applied()).as("one permit per window starting at activation").isEqualTo(1L);

      clock.set(Instant.ofEpochMilli(11_600L));
      submit();
      assertThat(applied()).isEqualTo(2L);
    }

    @Test
    @Tag("law-L10")
    @DisplayName("a clock that steps back re-anchors the window instead of blocking it")
    void clockStepBackReAnchors() {
      runtime.activate(rateLimited(1, Duration.ofSeconds(1)));
      submit();

      clock.set(Instant.ofEpochMilli(5_000L));
      submit();

      assertThat(applied()).isEqualTo(2L);
    }

    private void submit() {
      runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
    }

    private long applied() {
      return runtime.diagnostics().scenario("rate").orElseThrow().appliedCount();
    }

    private ChaosScenario rateLimited(final long permits, final Duration window) {
      return ChaosScenario.builder("rate")
          .scope(ChaosScenario.ScenarioScope.JVM)
          .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
          .effect(ChaosEffect.delay(Duration.ZERO))
          .activationPolicy(
              new ActivationPolicy(
                  ActivationPolicy.StartMode.AUTOMATIC,
                  1.0d,
                  0L,
                  null,
                  null,
                  new ActivationPolicy.RateLimit(permits, window),
                  0L,
                  false))
          .build();
    }
  }

  @Test
  @Tag("law-L9")
  @DisplayName("the sampled delay is independent of the probability draw")
  void delayIsIndependentOfProbabilityDraw() {
    // With probability 0.5 only draws below 0.5 apply. When the delay came from the same
    // generator output as the probability draw, every applied delay fell in the lower half of
    // the range.
    final ChaosScenario scenario =
        ChaosScenario.builder("independent")
            .scope(ChaosScenario.ScenarioScope.JVM)
            .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
            .effect(ChaosEffect.delay(Duration.ZERO, Duration.ofSeconds(1)))
            .activationPolicy(
                new ActivationPolicy(
                    ActivationPolicy.StartMode.AUTOMATIC, 0.5d, 0L, null, null, null, 42L, false))
            .build();
    final ScenarioController controller =
        new ScenarioController(
            scenario,
            "jvm",
            null,
            Clock.systemUTC(),
            new ObservabilityBus(ChaosMetricsSink.NOOP),
            Optional::empty);
    controller.start();
    final InvocationContext context =
        new InvocationContext(
            OperationType.EXECUTOR_SUBMIT, "executor", "task", null, false, null, null, null);

    long upperHalf = 0L;
    long applied = 0L;
    for (int i = 0; i < 400; i++) {
      final ScenarioContribution contribution = controller.evaluate(context);
      if (contribution != null) {
        applied++;
        if (contribution.delayMillis() > 500L) {
          upperHalf++;
        }
        contribution.release();
      }
    }

    assertThat(applied).isPositive();
    assertThat(upperHalf)
        .as("about half of the applied delays lie in the upper half of the range")
        .isGreaterThan(applied / 4);
  }

  @Test
  @DisplayName("exceptional-completion kinds carry the documented cause")
  void completionFailuresCarryCause() {
    assertThat(FailureFactory.completionFailure(ChaosEffect.FailureKind.CLASS_NOT_FOUND, "missing"))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(ClassNotFoundException.class);
    assertThat(FailureFactory.completionFailure(ChaosEffect.FailureKind.INTERRUPTED, "stopped"))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(InterruptedException.class);
  }

  /** A clock whose instant the test sets. */
  private static final class MutableClock extends Clock {
    private volatile Instant instant;

    MutableClock(final Instant instant) {
      this.instant = instant;
    }

    void set(final Instant newInstant) {
      instant = newInstant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
