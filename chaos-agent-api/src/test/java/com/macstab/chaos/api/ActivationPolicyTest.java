package com.macstab.chaos.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ActivationPolicy")
class ActivationPolicyTest {

  @Nested
  @DisplayName("always()")
  class Always {

    @Test
    @DisplayName("probability is 1.0")
    void probabilityOne() {
      assertThat(ActivationPolicy.always().probability()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("startMode is AUTOMATIC")
    void startModeAutomatic() {
      assertThat(ActivationPolicy.always().startMode())
          .isEqualTo(ActivationPolicy.StartMode.AUTOMATIC);
    }

    @Test
    @DisplayName("activateAfterMatches is 0")
    void activateAfterMatchesZero() {
      assertThat(ActivationPolicy.always().activateAfterMatches()).isZero();
    }

    @Test
    @DisplayName("maxApplications is null")
    void maxApplicationsNull() {
      assertThat(ActivationPolicy.always().maxApplications()).isNull();
    }

    @Test
    @DisplayName("activeFor is null")
    void activeForNull() {
      assertThat(ActivationPolicy.always().activeFor()).isNull();
    }

    @Test
    @DisplayName("rateLimit is null")
    void rateLimitNull() {
      assertThat(ActivationPolicy.always().rateLimit()).isNull();
    }
  }

  @Nested
  @DisplayName("manual()")
  class Manual {

    @Test
    @DisplayName("startMode is MANUAL")
    void startModeManual() {
      assertThat(ActivationPolicy.manual().startMode())
          .isEqualTo(ActivationPolicy.StartMode.MANUAL);
    }

    @Test
    @DisplayName("probability is 1.0")
    void probabilityOne() {
      assertThat(ActivationPolicy.manual().probability()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("no other constraints are set")
    void noOtherConstraints() {
      ActivationPolicy policy = ActivationPolicy.manual();
      assertThat(policy.activateAfterMatches()).isZero();
      assertThat(policy.maxApplications()).isNull();
      assertThat(policy.activeFor()).isNull();
      assertThat(policy.rateLimit()).isNull();
    }
  }

  @Nested
  @DisplayName("probability 0.0 is rejected")
  class ProbabilityZeroRejection {

    @Test
    @DisplayName("probability 0.0 throws IllegalArgumentException pointing at omit-activation")
    void probabilityZeroRejected() {
      // Silent normalisation of 0.0 → 1.0 was the opposite of what a user who typed 0 meant.
      // The policy now rejects it explicitly so the ambiguity surfaces at construction time.
      assertThatThrownBy(
              () ->
                  new ActivationPolicy(
                      ActivationPolicy.StartMode.AUTOMATIC, 0.0d, 0, null, null, null, null, false))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("probability")
          .hasMessageContaining("omit the scenario activation");
    }
  }

  @Nested
  @DisplayName("probability validation")
  class ProbabilityValidation {

    @Test
    @DisplayName("probability 1.0 is valid")
    void probabilityOneValid() {
      assertThatCode(
              () ->
                  new ActivationPolicy(
                      ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 0, null, null, null, null, false))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("probability 0.5 is valid")
    void probabilityHalfValid() {
      assertThatCode(
              () ->
                  new ActivationPolicy(
                      ActivationPolicy.StartMode.AUTOMATIC, 0.5d, 0, null, null, null, null, false))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("probability above 1.0 throws")
    void probabilityAboveOneThrows() {
      assertThatThrownBy(
              () ->
                  new ActivationPolicy(
                      ActivationPolicy.StartMode.AUTOMATIC, 1.1d, 0, null, null, null, null, false))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("negative probability throws")
    void probabilityNegativeThrows() {
      assertThatThrownBy(
              () ->
                  new ActivationPolicy(
                      ActivationPolicy.StartMode.AUTOMATIC,
                      -0.1d,
                      0,
                      null,
                      null,
                      null,
                      null,
                      false))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("activateAfterMatches validation")
  class ActivateAfterMatchesValidation {

    @Test
    @DisplayName("zero is valid")
    void zeroValid() {
      assertThatCode(
              () ->
                  new ActivationPolicy(
                      ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 0, null, null, null, null, false))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("positive value is valid")
    void positiveValid() {
      assertThatCode(
              () ->
                  new ActivationPolicy(
                      ActivationPolicy.StartMode.AUTOMATIC,
                      1.0d,
                      10,
                      null,
                      null,
                      null,
                      null,
                      false))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("negative value throws")
    void negativeThrows() {
      assertThatThrownBy(
              () ->
                  new ActivationPolicy(
                      ActivationPolicy.StartMode.AUTOMATIC,
                      1.0d,
                      -1,
                      null,
                      null,
                      null,
                      null,
                      false))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("maxApplications validation")
  class MaxApplicationsValidation {

    @Test
    @DisplayName("null is valid")
    void nullValid() {
      assertThatCode(
              () ->
                  new ActivationPolicy(
                      ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 0, null, null, null, null, false))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("positive value is valid")
    void positiveValid() {
      assertThatCode(
              () ->
                  new ActivationPolicy(
                      ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 0, 100L, null, null, null, false))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("zero throws")
    void zeroThrows() {
      assertThatThrownBy(
              () ->
                  new ActivationPolicy(
                      ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 0, 0L, null, null, null, false))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("negative value throws")
    void negativeThrows() {
      assertThatThrownBy(
              () ->
                  new ActivationPolicy(
                      ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 0, -5L, null, null, null, false))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("activeFor validation")
  class ActiveForValidation {

    @Test
    @DisplayName("null is valid")
    void nullValid() {
      assertThatCode(
              () ->
                  new ActivationPolicy(
                      ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 0, null, null, null, null, false))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("positive duration is valid")
    void positiveValid() {
      assertThatCode(
              () ->
                  new ActivationPolicy(
                      ActivationPolicy.StartMode.AUTOMATIC,
                      1.0d,
                      0,
                      null,
                      Duration.ofMinutes(5),
                      null,
                      null,
                      false))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("zero duration throws")
    void zeroThrows() {
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
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("negative duration throws")
    void negativeThrows() {
      assertThatThrownBy(
              () ->
                  new ActivationPolicy(
                      ActivationPolicy.StartMode.AUTOMATIC,
                      1.0d,
                      0,
                      null,
                      Duration.ofSeconds(-1),
                      null,
                      null,
                      false))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("RateLimit validation")
  class RateLimitValidation {

    @Test
    @DisplayName("valid construction")
    void validConstruction() {
      assertThatCode(() -> new ActivationPolicy.RateLimit(10, Duration.ofSeconds(1)))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("zero permits throws")
    void zeroPermitsThrows() {
      assertThatThrownBy(() -> new ActivationPolicy.RateLimit(0, Duration.ofSeconds(1)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("negative permits throws")
    void negativePermitsThrows() {
      assertThatThrownBy(() -> new ActivationPolicy.RateLimit(-1, Duration.ofSeconds(1)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null window throws")
    void nullWindowThrows() {
      assertThatThrownBy(() -> new ActivationPolicy.RateLimit(10, null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("zero window throws")
    void zeroWindowThrows() {
      assertThatThrownBy(() -> new ActivationPolicy.RateLimit(10, Duration.ZERO))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("negative window throws")
    void negativeWindowThrows() {
      assertThatThrownBy(() -> new ActivationPolicy.RateLimit(10, Duration.ofMillis(-1)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("null startMode defaults to AUTOMATIC")
  class NullStartModeDefault {

    @Test
    @DisplayName("null startMode defaults to AUTOMATIC")
    void nullDefaultsToAutomatic() {
      ActivationPolicy policy = new ActivationPolicy(null, 1.0d, 0, null, null, null, null, false);
      assertThat(policy.startMode()).isEqualTo(ActivationPolicy.StartMode.AUTOMATIC);
    }
  }

  @Nested
  @DisplayName("allowDestructiveEffects flag")
  class AllowDestructiveEffectsFlag {

    @Test
    @DisplayName("always() has allowDestructiveEffects=false")
    void alwaysFlagIsFalse() {
      assertThat(ActivationPolicy.always().allowDestructiveEffects()).isFalse();
    }

    @Test
    @DisplayName("manual() has allowDestructiveEffects=false")
    void manualFlagIsFalse() {
      assertThat(ActivationPolicy.manual().allowDestructiveEffects()).isFalse();
    }

    @Test
    @DisplayName("withDestructiveEffects() has allowDestructiveEffects=true")
    void withDestructiveFlagIsTrue() {
      assertThat(ActivationPolicy.withDestructiveEffects().allowDestructiveEffects()).isTrue();
    }

    @Test
    @DisplayName("constructor with allowDestructiveEffects=true preserves flag")
    void constructorPreservesFlag() {
      ActivationPolicy policy =
          new ActivationPolicy(
              ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 0, null, null, null, null, true);
      assertThat(policy.allowDestructiveEffects()).isTrue();
    }

    @Test
    @DisplayName("constructor with allowDestructiveEffects=false preserves flag")
    void constructorPreservesFalseFlagToo() {
      ActivationPolicy policy =
          new ActivationPolicy(
              ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 0, null, null, null, null, false);
      assertThat(policy.allowDestructiveEffects()).isFalse();
    }
  }
}
