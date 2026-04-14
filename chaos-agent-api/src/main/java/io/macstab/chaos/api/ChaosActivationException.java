package io.macstab.chaos.api;

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
 * <p>For configuration errors (invalid selector ↔ effect pairings, illegal field values) see
 * {@link ChaosValidationException}. For missing JVM features see
 * {@link ChaosUnsupportedFeatureException}.
 */
public class ChaosActivationException extends RuntimeException {

  /**
   * @param message human-readable description of why activation failed
   */
  public ChaosActivationException(String message) {
    super(message);
  }

  /**
   * @param message human-readable description of why activation failed
   * @param cause   the underlying exception, if any
   */
  public ChaosActivationException(String message, Throwable cause) {
    super(message, cause);
  }
}
