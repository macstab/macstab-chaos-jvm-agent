package com.macstab.chaos.jvm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The edges of the API's value types: the largest accepted stressor sizes and the first rejected
 * ones, out-of-range durations, and the defaults a {@code null} component falls back to.
 */
@DisplayName("API boundary values")
class ApiBoundaryValuesTest {

  /** Past ~292 years a Duration no longer fits in long nanoseconds. */
  private static final Duration BEYOND_NANOS = Duration.ofSeconds(Long.MAX_VALUE);

  @Nested
  @DisplayName("stressor upper bounds")
  class StressorBounds {

    @Test
    @DisplayName("heap pressure accepts its maxima and rejects one past each")
    void heapPressure() {
      assertThatCode(
              () ->
                  new ChaosEffect.HeapPressureEffect(
                      ChaosEffect.HeapPressureEffect.MAX_BYTES,
                      ChaosEffect.HeapPressureEffect.MAX_CHUNK_SIZE_BYTES))
          .doesNotThrowAnyException();
      assertThatThrownBy(() -> new ChaosEffect.HeapPressureEffect(Long.MAX_VALUE, 1024))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(
              "bytes must be <= "
                  + ChaosEffect.HeapPressureEffect.MAX_BYTES
                  + " (got "
                  + Long.MAX_VALUE
                  + ")");
      assertThatThrownBy(
              () ->
                  new ChaosEffect.HeapPressureEffect(
                      1024L, ChaosEffect.HeapPressureEffect.MAX_CHUNK_SIZE_BYTES + 1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith("chunkSizeBytes must be <= ");
    }

    @Test
    @DisplayName("finalizer backlog rejects more objects than its maximum")
    void finalizerBacklog() {
      assertThatThrownBy(
              () ->
                  new ChaosEffect.FinalizerBacklogEffect(
                      ChaosEffect.FinalizerBacklogEffect.MAX_OBJECT_COUNT + 1,
                      Duration.ofMillis(1)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith("objectCount must be <= ");
    }

    @Test
    @DisplayName("deadlock rejects more participants than its maximum")
    void deadlock() {
      assertThatThrownBy(
              () ->
                  new ChaosEffect.DeadlockEffect(
                      ChaosEffect.DeadlockEffect.MAX_PARTICIPANT_COUNT + 1, Duration.ofMillis(1)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith("participantCount must be <= ");
    }

    @Test
    @DisplayName("thread leak rejects more threads than its maximum")
    void threadLeak() {
      assertThatThrownBy(
              () ->
                  new ChaosEffect.ThreadLeakEffect(
                      ChaosEffect.ThreadLeakEffect.MAX_THREAD_COUNT + 1, "leak-", true, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith("threadCount must be <= ");
    }

    @Test
    @DisplayName("monitor contention rejects more contending threads than its maximum")
    void monitorContention() {
      assertThatThrownBy(
              () ->
                  new ChaosEffect.MonitorContentionEffect(
                      Duration.ofMillis(1),
                      ChaosEffect.MonitorContentionEffect.MAX_CONTENDING_THREAD_COUNT + 1,
                      false))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith("contendingThreadCount must be <= ");
    }
  }

  @Nested
  @DisplayName("durations beyond long nanoseconds")
  class Overflow {

    @Test
    @DisplayName("a clock skew too large for nanoseconds fails at construction")
    void clockSkew() {
      assertThatThrownBy(
              () -> new ChaosEffect.ClockSkewEffect(BEYOND_NANOS, ChaosEffect.ClockSkewMode.FIXED))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith("skewAmount out of range")
          .hasCauseInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("a rate-limit window too large for nanoseconds fails at construction")
    void rateLimitWindow() {
      assertThatThrownBy(() -> new ActivationPolicy.RateLimit(1L, BEYOND_NANOS))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith("window is too large to represent in nanoseconds")
          .hasCauseInstanceOf(ArithmeticException.class);
    }
  }

  @Nested
  @DisplayName("activation policy")
  class Policy {

    @Test
    @DisplayName("NaN, zero and above-one probabilities are rejected")
    void probabilityOutsideRange() {
      for (final double probability : new double[] {Double.NaN, 0.0d, 1.5d}) {
        assertThatThrownBy(() -> policy(probability, null, null, null, null, 0L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("probability must be in (0.0, 1.0]");
      }
    }

    @Test
    @DisplayName("a window open on one side only is accepted; an empty window is rejected")
    void activeWindow() {
      final Instant now = Instant.parse("2026-01-01T00:00:00Z");

      assertThat(policy(1.0d, null, now, null, null, 0L).activeFrom()).isEqualTo(now);
      assertThat(policy(1.0d, null, null, now, null, 0L).activeUntil()).isEqualTo(now);
      assertThatThrownBy(() -> policy(1.0d, null, now, now, null, 0L))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith("activeFrom must be before activeUntil");
    }

    @Test
    @DisplayName("replayed match numbers must be present and positive")
    void replayMatchesPositive() {
      assertThatThrownBy(() -> policy(1.0d, null, null, null, Arrays.asList(1L, null), 0L))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("replayMatches must hold positive match numbers");
      assertThatThrownBy(() -> policy(1.0d, null, null, null, List.of(0L), 0L))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("replayMatches must hold positive match numbers");
    }

    @Test
    @DisplayName("a replay cannot be combined with a probability below one or a rate limit")
    void replayExcludesDraws() {
      assertThatThrownBy(() -> policy(0.5d, null, null, null, List.of(1L), 0L))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith("replayMatches replaces the probability draw");
      final ActivationPolicy.RateLimit limit =
          new ActivationPolicy.RateLimit(1L, Duration.ofSeconds(1));
      assertThatThrownBy(() -> policy(1.0d, limit, null, null, List.of(1L), 0L))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith("replayMatches replaces the probability draw");
    }

    @Test
    @DisplayName("an empty replay is kept and needs no match past the warm-up")
    void emptyReplay() {
      assertThat(policy(1.0d, null, null, null, List.of(), 5L).replayMatches()).isEmpty();
    }

    private ActivationPolicy policy(
        final double probability,
        final ActivationPolicy.RateLimit rateLimit,
        final Instant activeFrom,
        final Instant activeUntil,
        final List<Long> replayMatches,
        final long activateAfterMatches) {
      return new ActivationPolicy(
          ActivationPolicy.StartMode.AUTOMATIC,
          probability,
          activateAfterMatches,
          null,
          null,
          rateLimit,
          null,
          false,
          activeFrom,
          activeUntil,
          replayMatches);
    }
  }

  @Nested
  @DisplayName("scenario tags")
  class Tags {

    @Test
    @DisplayName("a tag map holding a null key or value is rejected")
    void nullInTagMap() {
      final Map<String, String> nullKey = new HashMap<>();
      nullKey.put(null, "v");
      final Map<String, String> nullValue = new HashMap<>();
      nullValue.put("k", null);

      assertThatThrownBy(() -> scenarioWithTags(nullKey))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("tag key must not be null");
      assertThatThrownBy(() -> scenarioWithTags(nullValue))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("tag value must not be null (key=k)");
    }

    @Test
    @DisplayName("the builder rejects a null tag key or value")
    void nullInBuilder() {
      assertThatThrownBy(() -> ChaosScenario.builder("t").tag(null, "v"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("tag key must not be null");
      assertThatThrownBy(() -> ChaosScenario.builder("t").tag("k", null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("tag value must not be null (key=k)");
    }

    private ChaosScenario scenarioWithTags(final Map<String, String> tags) {
      final ChaosScenario template =
          ChaosScenario.builder("t")
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ofMillis(1)))
              .build();
      return new ChaosScenario(
          template.id(),
          template.description(),
          template.scope(),
          template.selector(),
          template.effect(),
          template.activationPolicy(),
          template.precedence(),
          tags);
    }
  }

  @Nested
  @DisplayName("null components")
  class NullComponents {

    @Test
    @DisplayName("an event without attributes carries an empty map")
    void eventAttributes() {
      final ChaosEvent event =
          new ChaosEvent(Instant.EPOCH, ChaosEvent.Type.REGISTERED, "s", "registered", null);

      assertThat(event.attributes()).isEmpty();
    }

    @Test
    @DisplayName("a method selector without a class pattern matches any class")
    void methodSelectorClassPattern() {
      final ChaosSelector.MethodSelector selector =
          new ChaosSelector.MethodSelector(
              Set.of(OperationType.METHOD_ENTER), null, NamePattern.exact("run"), null);

      assertThat(selector.classPattern()).isEqualTo(NamePattern.any());
    }

    @Test
    @DisplayName("a non-ANY name pattern without a value falls back to '*'")
    void namePatternValue() {
      assertThat(new NamePattern(NamePattern.MatchMode.EXACT, null).value()).isEqualTo("*");
    }

    @Test
    @DisplayName("a path pattern needs a prefix")
    void pathPatternPrefix() {
      assertThatThrownBy(() -> new PathPattern(null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("path prefix must be non-blank");
    }

    @Test
    @DisplayName("the no-op metrics sink accepts durations")
    void noopSinkRecordsDuration() {
      assertThatCode(
              () -> ChaosMetricsSink.NOOP.recordDuration("d", Duration.ofMillis(1), Map.of()))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("name patterns")
  class NamePatterns {

    @Test
    @DisplayName("a malformed regex is rejected at construction, naming the mode")
    void malformedRegex() {
      assertThatThrownBy(() -> new NamePattern(NamePattern.MatchMode.REGEX, "(a["))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith("invalid regex pattern: ")
          .hasCauseInstanceOf(java.util.regex.PatternSyntaxException.class);
    }
  }
}
