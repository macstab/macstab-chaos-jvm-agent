package com.macstab.chaos.jvm.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosSession;
import io.micronaut.context.ApplicationContext;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runs a real Micronaut test context through {@link MicronautChaosTest}, checking that
 * ChaosControlPlane is a bean (its bean definitions are generated) and that the extension sees
 * {@code @MicronautTest} as a meta-annotation, so it does not compete with Micronaut's own
 * parameter resolver.
 */
@MicronautChaosTest
@DisplayName("@MicronautChaosTest (end-to-end)")
class MicronautChaosTestIntegrationTest {

  @Inject ChaosControlPlane injectedControlPlane;

  @Inject ApplicationContext applicationContext;

  @Test
  @DisplayName("ChaosControlPlane is a bean of the application context")
  void controlPlaneIsABean() {
    assertThat(applicationContext.containsBean(ChaosControlPlane.class)).isTrue();
    // The test environment's bean tracks its activations, released when the class ends.
    assertThat(injectedControlPlane)
        .isInstanceOf(com.macstab.chaos.jvm.testkit.TrackingChaosControlPlane.class);
  }

  @Test
  @DisplayName("parameters resolve without competing resolvers")
  void parametersResolve(final ChaosControlPlane controlPlane, final ChaosSession session) {
    assertThat(controlPlane).isNotNull();
    assertThat(session).isNotNull();
  }
}
