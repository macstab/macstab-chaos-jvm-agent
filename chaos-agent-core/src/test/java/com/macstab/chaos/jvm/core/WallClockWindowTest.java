package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosMetricsSink;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosValidationException;
import com.macstab.chaos.jvm.api.OperationType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The absolute wall-clock window of a policy: {@code activeFrom} and {@code activeUntil}. */
@DisplayName("Wall-clock activation window")
class WallClockWindowTest {

  private static final Instant T0 = Instant.parse("2026-10-30T12:00:00Z");

  private final MutableClock clock = new MutableClock(T0);
  private final ChaosRuntime runtime = new ChaosRuntime(clock, ChaosMetricsSink.NOOP);

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  @Nested
  @DisplayName("interceptor scenarios")
  class Interceptors {

    @Test
    @DisplayName("do not fire, or count matches, before activeFrom")
    void notBeforeActiveFrom() {
      runtime.activate(submitScenario(ActivationPolicy.window(T0.plusSeconds(60), null)));

      submit();

      assertThat(report().matchedCount()).isZero();
      assertThat(report().state()).isEqualTo(ChaosDiagnostics.ScenarioState.ACTIVE);

      clock.set(T0.plusSeconds(60));
      submit();

      assertThat(report().appliedCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("stop firing and become INACTIVE at activeUntil")
    void expireAtActiveUntil() {
      runtime.activate(submitScenario(ActivationPolicy.window(null, T0.plusSeconds(60))));
      submit();

      clock.set(T0.plusSeconds(60));
      submit();

      assertThat(report().appliedCount()).isEqualTo(1L);
      assertThat(report().state()).isEqualTo(ChaosDiagnostics.ScenarioState.INACTIVE);
      assertThat(report().reason()).isEqualTo("expired");
    }

    @Test
    @DisplayName("count(n) applies exactly n times")
    void countAlias() {
      runtime.activate(submitScenario(ActivationPolicy.count(2)));

      submit();
      submit();
      submit();

      assertThat(report().appliedCount()).isEqualTo(2L);
      assertThat(report().state()).isEqualTo(ChaosDiagnostics.ScenarioState.INACTIVE);
    }
  }

  @Nested
  @DisplayName("clock skew")
  class ClockSkew {

    private static final long REAL = 1_700_000_000_000L;

    @Test
    @Tag("law-L16")
    @DisplayName("honours activeFrom and activeUntil, which it used to ignore")
    void honoursWindow() {
      runtime.activate(clockSkew(ActivationPolicy.window(T0.plusSeconds(10), T0.plusSeconds(20))));

      assertThat(skewed()).as("before activeFrom").isEqualTo(REAL);
      clock.set(T0.plusSeconds(10));
      assertThat(skewed()).as("inside the window").isEqualTo(REAL + 5_000L);
      clock.set(T0.plusSeconds(20));
      assertThat(skewed()).as("from activeUntil on").isEqualTo(REAL);
      assertThat(runtime.diagnostics().scenario("skew").orElseThrow().state())
          .isEqualTo(ChaosDiagnostics.ScenarioState.INACTIVE);
    }

    @Test
    @Tag("law-L16")
    @DisplayName("honours activeFor")
    void honoursActiveFor() {
      runtime.activate(
          clockSkew(ActivationPolicy.builder().activeFor(Duration.ofSeconds(5)).build()));

      assertThat(skewed()).isEqualTo(REAL + 5_000L);
      clock.set(T0.plusSeconds(5));
      assertThat(skewed()).isEqualTo(REAL);
    }

    @Test
    @Tag("law-L16")
    @DisplayName("rejects counting and sampling policy fields instead of ignoring them")
    void rejectsCountingFields() {
      assertThatThrownBy(() -> runtime.activate(clockSkew(ActivationPolicy.count(3))))
          .isInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("maxApplications");
      assertThatThrownBy(
              () ->
                  runtime.activate(clockSkew(ActivationPolicy.builder().probability(0.5).build())))
          .isInstanceOf(ChaosValidationException.class);
      assertThatThrownBy(
              () ->
                  runtime.activate(
                      clockSkew(
                          ActivationPolicy.builder().rateLimit(5, Duration.ofSeconds(1)).build())))
          .isInstanceOf(ChaosValidationException.class);
      assertThatThrownBy(
              () ->
                  runtime.activate(
                      clockSkew(ActivationPolicy.builder().activateAfterMatches(2).build())))
          .isInstanceOf(ChaosValidationException.class);
    }

    private long skewed() {
      return runtime.applyClockSkew(REAL, OperationType.SYSTEM_CLOCK_MILLIS);
    }

    private ChaosScenario clockSkew(final ActivationPolicy policy) {
      return ChaosScenario.builder("skew")
          .scope(ChaosScenario.ScenarioScope.JVM)
          .selector(ChaosSelector.jvmRuntime(Set.of(OperationType.SYSTEM_CLOCK_MILLIS)))
          .effect(
              new ChaosEffect.ClockSkewEffect(
                  Duration.ofSeconds(5), ChaosEffect.ClockSkewMode.FIXED))
          .activationPolicy(policy)
          .build();
    }
  }

  @Nested
  @DisplayName("stressors")
  class Stressors {

    @Test
    @Tag("law-L17")
    @DisplayName("end at activeUntil")
    void endAtActiveUntil() throws InterruptedException {
      runtime.activate(keepAlive(ActivationPolicy.window(null, T0.plusMillis(200))));

      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (state() != ChaosDiagnostics.ScenarioState.INACTIVE && System.nanoTime() < deadline) {
        Thread.sleep(20L);
      }

      assertThat(state()).isEqualTo(ChaosDiagnostics.ScenarioState.INACTIVE);
    }

    @Test
    @Tag("law-L17")
    @DisplayName("reject activeFrom: a stressor starts when it is activated")
    void rejectActiveFrom() {
      assertThatThrownBy(
              () -> runtime.activate(keepAlive(ActivationPolicy.window(T0.plusSeconds(1), null))))
          .isInstanceOf(ChaosValidationException.class)
          .hasMessageContaining("activeFrom");
    }

    private ChaosDiagnostics.ScenarioState state() {
      return runtime.diagnostics().scenario("keep-alive").orElseThrow().state();
    }

    private ChaosScenario keepAlive(final ActivationPolicy policy) {
      return ChaosScenario.builder("keep-alive")
          .scope(ChaosScenario.ScenarioScope.JVM)
          .selector(ChaosSelector.stress(ChaosSelector.StressTarget.KEEPALIVE))
          .effect(new ChaosEffect.KeepAliveEffect("window-keep-alive", true, Duration.ofMillis(50)))
          .activationPolicy(policy)
          .build();
    }
  }

  private void submit() {
    runtime.decorateExecutorRunnable("EXECUTOR_SUBMIT", this, () -> {});
  }

  private ChaosDiagnostics.ScenarioReport report() {
    return runtime.diagnostics().scenario("window").orElseThrow();
  }

  private static ChaosScenario submitScenario(final ActivationPolicy policy) {
    return ChaosScenario.builder("window")
        .scope(ChaosScenario.ScenarioScope.JVM)
        .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
        .effect(ChaosEffect.delay(Duration.ofMillis(1)))
        .activationPolicy(policy)
        .build();
  }

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
