package com.macstab.chaos.jvm.api;

/**
 * Thrown by JDBC interceptors when an active chaos scenario requires the call to be suppressed
 * entirely.
 *
 * <p>This exception propagates up through the JDBC call stack so that the application observes a
 * terminal failure instead of a silent no-op. Use with {@link ChaosSelector.JdbcSelector} and a
 * {@link ChaosEffect.SuppressEffect} to short-circuit connection acquisition, statement execution,
 * or transaction boundaries for a configured subset of pool identifiers or SQL patterns.
 *
 * <p>Unchecked: callers are not required to declare or catch it. Standard application error
 * handling (retry policies, circuit breakers, transaction managers) will observe this as a runtime
 * failure.
 */
public class ChaosJdbcSuppressException extends RuntimeException {

  /**
   * Constructs a suppression exception without an underlying cause.
   *
   * @param message human-readable description; should identify the suppressed operation and
   *     scenario
   */
  public ChaosJdbcSuppressException(final String message) {
    super(message);
  }

  /**
   * Constructs a suppression exception with an underlying cause.
   *
   * @param message human-readable description
   * @param cause the root cause of the failure; may be null
   */
  public ChaosJdbcSuppressException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
