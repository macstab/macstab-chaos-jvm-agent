package com.macstab.chaos.jvm.api;

/**
 * Thrown by HTTP client interceptors when an active chaos scenario requires the call to be
 * suppressed entirely.
 *
 * <p>This exception propagates up through the HTTP client call stack so that the application
 * observes a terminal failure instead of a dropped request. Use with {@link
 * ChaosSelector.HttpClientSelector} and a {@link ChaosEffect.SuppressEffect} to short-circuit HTTP
 * traffic for a configured subset of destinations.
 *
 * <p>Unchecked: callers are not required to declare or catch it. Standard application error
 * handling (circuit breakers, retry policies) will observe this as a runtime failure.
 */
public class ChaosHttpSuppressException extends RuntimeException {

  /**
   * Constructs a suppression exception without an underlying cause.
   *
   * @param message human-readable description; should identify the suppressed URL and scenario
   */
  public ChaosHttpSuppressException(final String message) {
    super(message);
  }

  /**
   * Constructs a suppression exception with an underlying cause.
   *
   * @param message human-readable description
   * @param cause the root cause of the failure; may be null
   */
  public ChaosHttpSuppressException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
