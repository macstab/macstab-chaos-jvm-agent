package io.macstab.chaos.bootstrap.jfr;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.macstab.chaos.core.ChaosRuntime;
import org.junit.jupiter.api.Test;

class JfrAvailabilityTest {

  @Test
  void probeReturnsTrueOnCurrentJvm() {
    // JDK 25 always includes the jdk.jfr module; probe must return true
    assertTrue(JfrAvailability.probe(), "expected JFR to be available on JDK 25");
  }

  @Test
  void jfrIntegrationInstallDoesNotThrow() {
    ChaosRuntime runtime = new ChaosRuntime();
    assertDoesNotThrow(
        () -> JfrIntegration.installIfAvailable(runtime),
        "installIfAvailable must not throw on a JDK with JFR present");
  }
}
