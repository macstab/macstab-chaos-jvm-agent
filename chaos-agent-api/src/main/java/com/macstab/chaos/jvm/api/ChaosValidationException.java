package com.macstab.chaos.jvm.api;

/**
 * Thrown during scenario activation when the scenario's configuration violates an API contract.
 *
 * <p>Common causes:
 *
 * <ul>
 *   <li>Incompatible selector ↔ effect pairing (e.g., {@link ChaosEffect.ClockSkewEffect} with a
 *       non-{@link ChaosSelector.JvmRuntimeSelector} selector).
 *   <li>A stressor effect ({@link ChaosEffect.HeapPressureEffect}, {@link
 *       ChaosEffect.DeadlockEffect}, etc.) used with an interception selector instead of {@link
 *       ChaosSelector.StressSelector}.
 *   <li>A {@link ChaosSelector.StressSelector} whose {@link ChaosSelector.StressTarget} does not
 *       match the provided effect type.
 *   <li>A session-scoped scenario using a JVM-global selector.
 * </ul>
 *
 * <p>Validation is performed by the runtime at activation time. Constructors of the API value
 * objects ({@link ChaosScenario}, {@link ActivationPolicy}, effect records) perform field-level
 * validation and throw {@link IllegalArgumentException} directly.
 */
public class ChaosValidationException extends RuntimeException {

  /**
   * Constructs a validation exception with a human-readable explanation.
   *
   * @param message explanation of the validation failure; should identify the offending scenario
   *     field, the incompatible selector/effect pairing, or the violated API contract
   */
  public ChaosValidationException(final String message) {
    super(message);
  }
}
