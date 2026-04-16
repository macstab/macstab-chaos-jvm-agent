package com.macstab.chaos.bootstrap.jfr;

/**
 * Runtime probe for JFR availability.
 *
 * <p>JFR is part of the {@code jdk.jfr} module, present in every standard JDK 11+ distribution.
 * Stripped JREs (e.g., minimal Docker images built with {@code jlink --no-header-files}) may omit
 * it. This probe guards all JFR integration paths so the agent always starts successfully
 * regardless of the runtime environment.
 */
final class JfrAvailability {
  private JfrAvailability() {}

  /**
   * Returns {@code true} if {@code jdk.jfr.FlightRecorder} is loadable via the bootstrap
   * classloader chain. The check is performed without initializing the class (no JFR side-effects).
   */
  static boolean probe() {
    try {
      Class.forName("jdk.jfr.FlightRecorder", false, JfrAvailability.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException ignored) {
      return false;
    }
  }
}
