package com.macstab.chaos.jvm.api;

/**
 * Thrown when a scenario cannot be activated because the instrumentation it needs cannot be woven
 * into the running JVM. The cause is the weaving failure. Unlike other activation failures this is
 * not a fault of the scenario, and the same scenario fails the same way again in this JVM.
 */
public class ChaosInstrumentationException extends ChaosActivationException {
  private static final long serialVersionUID = 1L;

  /**
   * Constructs the exception.
   *
   * @param message names the scenario whose hooks could not be woven
   * @param cause the weaving failure
   */
  public ChaosInstrumentationException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
