package io.macstab.chaos.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ActivationPolicyTest {

  // ── always() ─────────────────────────────────────────────────────────────

  @Test
  void alwaysPolicyHasProbabilityOne() {
    assertEquals(1.0d, ActivationPolicy.always().probability(), 0.0001);
  }

  @Test
  void alwaysPolicyHasAutomaticStartMode() {
    assertEquals(ActivationPolicy.StartMode.AUTOMATIC, ActivationPolicy.always().startMode());
  }

  @Test
  void alwaysPolicyHasNoActivateAfterMatches() {
    assertEquals(0L, ActivationPolicy.always().activateAfterMatches());
  }

  @Test
  void alwaysPolicyHasNullMaxApplications() {
    assertNull(ActivationPolicy.always().maxApplications());
  }

  @Test
  void alwaysPolicyHasNullActiveFor() {
    assertNull(ActivationPolicy.always().activeFor());
  }

  @Test
  void alwaysPolicyHasNullRateLimit() {
    assertNull(ActivationPolicy.always().rateLimit());
  }

  // ── manual() ─────────────────────────────────────────────────────────────

  @Test
  void manualPolicyHasManualStartMode() {
    assertEquals(ActivationPolicy.StartMode.MANUAL, ActivationPolicy.manual().startMode());
  }

  @Test
  void manualPolicyHasProbabilityOne() {
    assertEquals(1.0d, ActivationPolicy.manual().probability(), 0.0001);
  }

  @Test
  void manualPolicyHasNoOtherConstraints() {
    ActivationPolicy policy = ActivationPolicy.manual();
    assertEquals(0L, policy.activateAfterMatches());
    assertNull(policy.maxApplications());
    assertNull(policy.activeFor());
    assertNull(policy.rateLimit());
  }

  // ── probability 0.0 → 1.0 normalisation ──────────────────────────────────

  @Test
  void probabilityZeroNormalisedToOne() {
    ActivationPolicy policy =
        new ActivationPolicy(ActivationPolicy.StartMode.AUTOMATIC, 0.0d, 0, null, null, null, null);
    assertEquals(1.0d, policy.probability(), 0.0001);
  }

  // ── probability validation ─────────────────────────────────────────────────

  @Test
  void probabilityOneIsValid() {
    assertDoesNotThrow(
        () ->
            new ActivationPolicy(
                ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 0, null, null, null, null));
  }

  @Test
  void probabilityHalfIsValid() {
    assertDoesNotThrow(
        () ->
            new ActivationPolicy(
                ActivationPolicy.StartMode.AUTOMATIC, 0.5d, 0, null, null, null, null));
  }

  @Test
  void probabilityAboveOneThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ActivationPolicy(
                ActivationPolicy.StartMode.AUTOMATIC, 1.1d, 0, null, null, null, null));
  }

  @Test
  void probabilityNegativeThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ActivationPolicy(
                ActivationPolicy.StartMode.AUTOMATIC, -0.1d, 0, null, null, null, null));
  }

  // ── activateAfterMatches validation ───────────────────────────────────────

  @Test
  void activateAfterMatchesZeroIsValid() {
    assertDoesNotThrow(
        () ->
            new ActivationPolicy(
                ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 0, null, null, null, null));
  }

  @Test
  void activateAfterMatchesPositiveIsValid() {
    assertDoesNotThrow(
        () ->
            new ActivationPolicy(
                ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 10, null, null, null, null));
  }

  @Test
  void activateAfterMatchesNegativeThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ActivationPolicy(
                ActivationPolicy.StartMode.AUTOMATIC, 1.0d, -1, null, null, null, null));
  }

  // ── maxApplications validation ─────────────────────────────────────────────

  @Test
  void maxApplicationsNullIsValid() {
    assertDoesNotThrow(
        () ->
            new ActivationPolicy(
                ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 0, null, null, null, null));
  }

  @Test
  void maxApplicationsPositiveIsValid() {
    assertDoesNotThrow(
        () ->
            new ActivationPolicy(
                ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 0, 100L, null, null, null));
  }

  @Test
  void maxApplicationsZeroThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ActivationPolicy(
                ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 0, 0L, null, null, null));
  }

  @Test
  void maxApplicationsNegativeThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ActivationPolicy(
                ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 0, -5L, null, null, null));
  }

  // ── activeFor validation ───────────────────────────────────────────────────

  @Test
  void activeForNullIsValid() {
    assertDoesNotThrow(
        () ->
            new ActivationPolicy(
                ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 0, null, null, null, null));
  }

  @Test
  void activeForPositiveIsValid() {
    assertDoesNotThrow(
        () ->
            new ActivationPolicy(
                ActivationPolicy.StartMode.AUTOMATIC,
                1.0d,
                0,
                null,
                Duration.ofMinutes(5),
                null,
                null));
  }

  @Test
  void activeForZeroThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ActivationPolicy(
                ActivationPolicy.StartMode.AUTOMATIC, 1.0d, 0, null, Duration.ZERO, null, null));
  }

  @Test
  void activeForNegativeThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ActivationPolicy(
                ActivationPolicy.StartMode.AUTOMATIC,
                1.0d,
                0,
                null,
                Duration.ofSeconds(-1),
                null,
                null));
  }

  // ── RateLimit validation ───────────────────────────────────────────────────

  @Test
  void rateLimitValidConstruction() {
    assertDoesNotThrow(
        () -> new ActivationPolicy.RateLimit(10, Duration.ofSeconds(1)));
  }

  @Test
  void rateLimitZeroPermitsThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ActivationPolicy.RateLimit(0, Duration.ofSeconds(1)));
  }

  @Test
  void rateLimitNegativePermitsThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ActivationPolicy.RateLimit(-1, Duration.ofSeconds(1)));
  }

  @Test
  void rateLimitNullWindowThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> new ActivationPolicy.RateLimit(10, null));
  }

  @Test
  void rateLimitZeroWindowThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ActivationPolicy.RateLimit(10, Duration.ZERO));
  }

  @Test
  void rateLimitNegativeWindowThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ActivationPolicy.RateLimit(10, Duration.ofMillis(-1)));
  }

  // ── null startMode defaults to AUTOMATIC ──────────────────────────────────

  @Test
  void nullStartModeDefaultsToAutomatic() {
    ActivationPolicy policy =
        new ActivationPolicy(null, 1.0d, 0, null, null, null, null);
    assertEquals(ActivationPolicy.StartMode.AUTOMATIC, policy.startMode());
  }
}
