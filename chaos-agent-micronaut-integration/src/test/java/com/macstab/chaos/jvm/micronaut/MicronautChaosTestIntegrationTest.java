package com.macstab.chaos.jvm.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosSession;
import io.micronaut.context.ApplicationContext;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runs a real Micronaut test context through {@link MicronautChaosTest}. Before the bean
 * definitions were generated ChaosControlPlane was not a bean, and once it was, the extension
 * competed with Micronaut's own parameter resolver because it did not see {@code @MicronautTest} as
 * a meta-annotation.
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
    assertThat(injectedControlPlane).isNotNull();
  }

  @Test
  @DisplayName("parameters resolve without competing resolvers")
  void parametersResolve(final ChaosControlPlane controlPlane, final ChaosSession session) {
    assertThat(controlPlane).isNotNull();
    assertThat(session).isNotNull();
  }
}
