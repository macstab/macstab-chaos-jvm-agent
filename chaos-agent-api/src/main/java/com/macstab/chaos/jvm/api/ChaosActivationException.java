package com.macstab.chaos.jvm.api;

/**
 * Thrown when a chaos scenario or plan fails to activate due to a runtime conflict or precondition
 * violation that is not a configuration error.
 *
 * <p>Common causes:
 *
 * <ul>
 *   <li>Attempting to activate a JVM-scoped scenario with {@link ChaosSession} — sessions only
 *       accept {@link ChaosScenario.ScenarioScope#SESSION SESSION}-scoped scenarios.
 *   <li>Activating a plan that contains SESSION-scoped scenarios at JVM level.
 *   <li>Duplicate activation of a scenario with the same ID in the same scope.
 * </ul>
 *
 * <p>For configuration errors (invalid selector ↔ effect pairings, illegal field values) see {@link
 * ChaosValidationException}. For missing JVM features see {@link ChaosUnsupportedFeatureException}.
 */
public class ChaosActivationException extends RuntimeException {

  /**
   * Constructs an activation exception without an underlying cause.
   *
   * @param message human-readable description of why activation failed; should identify the
   *     conflicting scenario ID and the nature of the conflict
   */
  public ChaosActivationException(final String message) {
    super(message);
  }

  /**
   * Constructs an activation exception with an underlying cause.
   *
   * @param message human-readable description of why activation failed
   * @param cause the root cause of the failure; may be null
   */
  public ChaosActivationException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
