package com.macstab.chaos.jvm.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * A {@code PER_CLASS} test instance is injected in Micronaut's {@code beforeAll}, before {@link
 * ChaosMicronautExtension} installs the agent: the control-plane bean must install it itself. The
 * failure shows only when this is the first chaos test in its JVM, e.g. when run on its own.
 */
@MicronautChaosTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("@MicronautChaosTest with a PER_CLASS test instance")
class MicronautChaosTestPerClassIntegrationTest {

  @Inject ChaosControlPlane controlPlane;

  @Test
  @DisplayName("the control plane is injected and tracked")
  void controlPlaneIsInjected() {
    assertThat(controlPlane)
        .isInstanceOf(com.macstab.chaos.jvm.testkit.TrackingChaosControlPlane.class);
  }
}
