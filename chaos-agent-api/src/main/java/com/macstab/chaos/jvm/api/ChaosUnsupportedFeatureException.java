package com.macstab.chaos.jvm.api;

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
 *   <li>{@link ChaosEffect.DirectBufferPressureEffect} asking for more than the JVM's direct memory
 *       limit ({@code -XX:MaxDirectMemorySize}, by default the maximum heap).
 *   <li>A stressor that cannot get its resources at start: an {@link OutOfMemoryError}, which is
 *       also how the JVM reports a thread it cannot start.
 * </ul>
 */
public class ChaosUnsupportedFeatureException extends RuntimeException {

  /**
   * Constructs an exception describing a missing JVM feature.
   *
   * @param message description of the unsupported feature and the minimum JDK version required to
   *     enable it; e.g., {@code "virtual thread selector requires JDK 21 or later"}
   */
  public ChaosUnsupportedFeatureException(final String message) {
    super(message);
  }
}
