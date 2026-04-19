package com.macstab.chaos.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

/**
 * Property-based tests for {@link ActivationPolicy}.
 *
 * <p>These tests use jqwik to exhaustively explore the input space and verify algebraic invariants
 * that must hold for all valid and invalid inputs. They complement the example-based tests in
 * {@link ActivationPolicyTest} by catching boundary violations that hand-crafted examples miss.
 */
class ActivationPolicyPropertyTest {

  // ── probability ─────────────────────────────────────────────────────────────

  @Property
  void anyProbabilityInOpenUnitIntervalIsAccepted(
      @ForAll @DoubleRange(min = 0.01, max = 1.0) double p) {
    assertThatCode(
            () ->
                new ActivationPolicy(
                    ActivationPolicy.StartMode.AUTOMATIC, p, 0, null, null, null, null, false))
        .doesNotThrowAnyException();
  }

  @Property
  void zeroProbabilityNormalizesToOne() {
    final ActivationPolicy policy =
        new ActivationPolicy(
            ActivationPolicy.StartMode.AUTOMATIC, 0.0d, 0, null, null, null, null, false);
    assertThat(policy.probability()).isEqualTo(1.0d);
  }

  @Property
  void negativeProbabilityAlwaysThrows(@ForAll @DoubleRange(min = -1000.0, max = -0.01) double p) {
    assertThatThrownBy(
            () ->
                new ActivationPolicy(
                    ActivationPolicy.StartMode.AUTOMATIC, p, 0, null, null, null, null, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("probability");
  }

  @Property
  void probabilityAboveOneAlwaysThrows(@ForAll @DoubleRange(min = 1.01, max = 1000.0) double p) {
    assertThatThrownBy(
            () ->
                new ActivationPolicy(
                    ActivationPolicy.StartMode.AUTOMATIC, p, 0, null, null, null, null, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("probability");
  }

  // ── activateAfterMatches ────────────────────────────────────────────────────

  @Property
  void nonNegativeActivateAfterMatchesIsAccepted(@ForAll @IntRange(min = 0, max = 10_000) int n) {
    assertThatCode(
            () ->
                new ActivationPolicy(
                    ActivationPolicy.StartMode.AUTOMATIC, 1.0d, n, null, null, null, null, false))
        .doesNotThrowAnyException();
  }

  @Property
  void negativeActivateAfterMatchesAlwaysThrows(@ForAll @IntRange(min = -10_000, max = -1) int n) {
    assertThatThrownBy(
            () ->
                new ActivationPolicy(
                    ActivationPolicy.StartMode.AUTOMATIC, 1.0d, n, null, null, null, null, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("activateAfterMatches");
  }

  // ── maxApplications ─────────────────────────────────────────────────────────

  @Property
  void positiveMaxApplicationsIsAccepted(@ForAll @LongRange(min = 1, max = 100_000) long max) {
    assertThatCode(
            () ->
                new ActivationPolicy(
                    ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 0, max, null, null, null, false))
        .doesNotThrowAnyException();
  }

  @Property
  void zeroOrNegativeMaxApplicationsAlwaysThrows(
      @ForAll @LongRange(min = -100_000, max = 0) long max) {
    assertThatThrownBy(
            () ->
                new ActivationPolicy(
                    ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 0, max, null, null, null, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxApplications");
  }

  // ── activeFor ───────────────────────────────────────────────────────────────

  @Property
  void positiveDurationForActiveForIsAccepted(
      @ForAll @LongRange(min = 1, max = 86_400_000) long millis) {
    assertThatCode(
            () ->
                new ActivationPolicy(
                    ActivationPolicy.StartMode.AUTOMATIC,
                    1.0d,
                    0,
                    null,
                    Duration.ofMillis(millis),
                    null,
                    null,
                    false))
        .doesNotThrowAnyException();
  }

  @Property
  void zeroActiveForAlwaysThrows() {
    assertThatThrownBy(
            () ->
                new ActivationPolicy(
                    ActivationPolicy.StartMode.AUTOMATIC,
                    1.0d,
                    0,
                    null,
                    Duration.ZERO,
                    null,
                    null,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("activeFor");
  }

  // ── startMode normalization ─────────────────────────────────────────────────

  @Property
  void nullStartModeAlwaysNormalizesToAutomatic(
      @ForAll @DoubleRange(min = 0.01, max = 1.0) double p) {
    final ActivationPolicy policy = new ActivationPolicy(null, p, 0, null, null, null, null, false);
    assertThat(policy.startMode()).isEqualTo(ActivationPolicy.StartMode.AUTOMATIC);
  }

  @Property
  void explicitStartModeIsPreserved(
      @ForAll ActivationPolicy.StartMode mode,
      @ForAll @DoubleRange(min = 0.01, max = 1.0) double p) {
    final ActivationPolicy policy = new ActivationPolicy(mode, p, 0, null, null, null, null, false);
    assertThat(policy.startMode()).isEqualTo(mode);
  }
}
