package com.macstab.chaos.jvm.api;

/**
 * Thrown when a scenario cannot be activated because a live scenario with the same id is already
 * active in the same scope. Unlike other activation failures, the same plan may succeed once the
 * other scenario has stopped.
 */
public class ChaosActivationConflictException extends ChaosActivationException {

  private static final long serialVersionUID = 1L;

  /**
   * Constructs the exception.
   *
   * @param message names the conflicting scenario key
   */
  public ChaosActivationConflictException(final String message) {
    super(message);
  }
}
