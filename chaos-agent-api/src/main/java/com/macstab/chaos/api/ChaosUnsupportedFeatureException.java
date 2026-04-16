package com.macstab.chaos.api;

/**
 * Thrown when a chaos scenario requires a JVM feature or capability that is not available on the
 * current runtime.
 *
 * <p>The agent probes feature availability at startup via reflection so it does not hard-depend on
 * JDK 21+ APIs at the source level. If a scenario is activated on an incompatible JVM, this
 * exception is thrown immediately and the scenario is recorded as {@link
 * ChaosDiagnostics.FailureCategory#UNSUPPORTED_RUNTIME}.
 *
 * <p>Known scenarios that trigger this exception:
 *
 * <ul>
 *   <li>{@link ChaosSelector.ThreadSelector} with {@link ChaosSelector.ThreadKind#VIRTUAL} on JDK
 *       17 or 18 (virtual threads require JDK 21+).
 * </ul>
 */
public class ChaosUnsupportedFeatureException extends RuntimeException {

  /**
   * @param message description of the missing feature and the minimum JDK version required
   */
  public ChaosUnsupportedFeatureException(final String message) {
    super(message);
  }
}
