package com.macstab.chaos.jvm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosDiagnostics;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosEvent;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The absolute wall-clock window of a policy: {@code activeFrom} and {@code activeUntil}. */
@DisplayName("Wall-clock activation window")
@SuppressWarnings("removal") // ChaosMetricsSink, deprecated, until it is removed
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
    @DisplayName(
        "date-time reads outside the window return the caller's own value, inside it skewed")
    void dateTimeReadsOutsideTheWindowAreUntouched() {
      runtime.activate(
          ChaosScenario.builder("date-skew")
              .scope(ChaosScenario.ScenarioScope.JVM)
              .selector(
                  ChaosSelector.jvmRuntime(
                      Set.of(OperationType.LOCAL_DATE_TIME_NOW, OperationType.ZONED_DATE_TIME_NOW)))
              .effect(
                  new ChaosEffect.ClockSkewEffect(
                      Duration.ofSeconds(5), ChaosEffect.ClockSkewMode.FIXED))
              .activationPolicy(ActivationPolicy.window(T0.plusSeconds(10), null))
              .build());
      final java.time.LocalDateTime local = java.time.LocalDateTime.of(2026, 10, 30, 12, 0);
      final java.time.ZonedDateTime zoned = local.atZone(ZoneOffset.UTC);

      assertThat(runtime.adjustLocalDateTimeNowInZone(local, ZoneOffset.UTC)).isSameAs(local);
      assertThat(runtime.adjustZonedDateTimeNow(zoned)).isSameAs(zoned);

      clock.set(T0.plusSeconds(10));
      assertThat(runtime.adjustLocalDateTimeNowInZone(local, ZoneOffset.UTC))
          .isEqualTo(local.plusSeconds(5));
      assertThat(runtime.adjustZonedDateTimeNow(zoned)).isEqualTo(zoned.plusSeconds(5));
    }

    @Test
    @DisplayName("honours activeFor")
    void honoursActiveFor() {
      runtime.activate(
          clockSkew(ActivationPolicy.builder().activeFor(Duration.ofSeconds(5)).build()));

      assertThat(skewed()).isEqualTo(REAL + 5_000L);
      clock.set(T0.plusSeconds(5));
      assertThat(skewed()).isEqualTo(REAL);
    }

    @Test
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
    @DisplayName("end at activeUntil, by the runtime's clock")
    void endAtActiveUntil() throws InterruptedException {
      runtime.activate(keepAlive(ActivationPolicy.window(null, T0.plusMillis(200))));
      // The expiry check runs about 200 ms from now and reads the runtime's clock.
      clock.set(T0.plusMillis(200));

      await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> state() == ChaosDiagnostics.ScenarioState.INACTIVE);
    }

    @Test
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

  @Nested
  @DisplayName("expiry without calls")
  class ExpiryWithoutCalls {

    @Test
    @DisplayName("a time-bounded scenario ends with its INACTIVE event without any call or read")
    void endsWithoutTraffic() {
      try (ChaosRuntime real = new ChaosRuntime()) {
        final List<ChaosEvent> inactive = new CopyOnWriteArrayList<>();
        real.addEventListener(
            event -> {
              if (event.type() == ChaosEvent.Type.INACTIVE) {
                inactive.add(event);
              }
            });
        real.activate(
            submitScenario(ActivationPolicy.builder().activeFor(Duration.ofMillis(100)).build()));

        await().atMost(Duration.ofSeconds(5)).until(() -> !inactive.isEmpty());

        assertThat(inactive)
            .singleElement()
            .satisfies(event -> assertThat(event.attributes()).containsEntry("reason", "expired"));
        assertThatCode(() -> real.activate(submitScenario(ActivationPolicy.always())))
            .as("the expired id can be activated again")
            .doesNotThrowAnyException();
      }
    }

    @Test
    @DisplayName("a check that runs before the runtime's clock reached the end runs again later")
    void earlyCheckRunsAgain() {
      final List<ChaosEvent> inactive = new CopyOnWriteArrayList<>();
      runtime.addEventListener(
          event -> {
            if (event.type() == ChaosEvent.Type.INACTIVE) {
              inactive.add(event);
            }
          });
      runtime.activate(submitScenario(ActivationPolicy.window(null, T0.plusMillis(50))));

      // The first check runs about 50 ms from now and finds the runtime's clock still at T0.
      await().pollDelay(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1)).until(() -> true);
      assertThat(inactive).as("not ended by an early check").isEmpty();
      clock.set(T0.plusMillis(50));

      await().atMost(Duration.ofSeconds(5)).until(() -> !inactive.isEmpty());
      assertThat(inactive.get(0).attributes()).containsEntry("reason", "expired");
    }

    @Test
    @DisplayName(
        "activating an id whose window has passed ends the old scenario first, outside the"
            + " registry's map update")
    void registerOverExpired() {
      final List<String> events = new CopyOnWriteArrayList<>();
      final List<Boolean> insideCompute = new CopyOnWriteArrayList<>();
      runtime.addEventListener(
          event -> {
            events.add(event.type().name());
            if (event.type() == ChaosEvent.Type.INACTIVE) {
              insideCompute.add(
                  java.util.Arrays.stream(Thread.currentThread().getStackTrace())
                      .anyMatch(
                          frame ->
                              frame.getClassName().equals("java.util.concurrent.ConcurrentHashMap")
                                  && frame.getMethodName().equals("compute")));
            }
          });
      runtime.activate(submitScenario(ActivationPolicy.window(null, T0.plusSeconds(60))));
      clock.set(T0.plusSeconds(60));
      events.clear();

      assertThatCode(() -> runtime.activate(submitScenario(ActivationPolicy.always())))
          .doesNotThrowAnyException();

      assertThat(events).startsWith("INACTIVE", "STOPPED");
      assertThat(insideCompute).containsExactly(false);
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
