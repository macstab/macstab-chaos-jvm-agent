package com.macstab.chaos.jvm.api;

/**
 * Thrown when a chaos scenario requires a JVM feature or capability that is not available on the
 * current runtime.
 *
 * <p>If a scenario is activated on a JVM that cannot run it, this exception is thrown immediately
 * and the failure is recorded as {@link ChaosDiagnostics.FailureCategory#UNSUPPORTED_RUNTIME}.
 *
 * <p>Known scenarios that trigger this exception:
 *
 * <ul>
 *   <li>A {@link ChaosEffect.HeapPressureEffect}, a thread-local leak, a string-intern stressor or
 *       a reference-queue flood cycle above the free heap (the maximum heap less what is in use).
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
   * @param message description of what the runtime cannot provide; e.g., {@code
   *     "directBufferPressure of 2147483648 bytes exceeds the direct memory limit"}
   */
  public ChaosUnsupportedFeatureException(final String message) {
    super(message);
  }
}
