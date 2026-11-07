package com.macstab.chaos.jvm.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import com.macstab.chaos.jvm.testkit.TrackingChaosControlPlane;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A test context's control plane is released when that context closes. */
@DisplayName("ChaosTestFactory")
class ChaosTestFactoryReleaseTest {

  @Test
  @DisplayName("closing the context releases its control plane and stops what it activated")
  void closingTheContextReleasesItsPlane() {
    final ChaosControlPlane plane;
    try (ApplicationContext context = ApplicationContext.run(Environment.TEST)) {
      plane = context.getBean(ChaosControlPlane.class);
      plane.activate(
          ChaosScenario.builder("micronaut-context-scoped")
              .selector(ChaosSelector.executor(Set.of(OperationType.EXECUTOR_SUBMIT)))
              .effect(ChaosEffect.delay(Duration.ofMillis(1)))
              .build());
      assertThat(ChaosMicronautExtension.CONTEXT_PLANES)
          .contains((TrackingChaosControlPlane) plane);
    }

    assertThat(ChaosMicronautExtension.CONTEXT_PLANES)
        .doesNotContain((TrackingChaosControlPlane) plane);
    assertThat(ChaosPlatform.installLocally().diagnostics().scenario("micronaut-context-scoped"))
        .isEmpty();
  }

  @Test
  @DisplayName("a context whose factory made no control plane releases nothing when it closes")
  void closingWithoutAPlaneReleasesNothing() {
    final int registered = ChaosMicronautExtension.CONTEXT_PLANES.size();
    try (ApplicationContext context = ApplicationContext.run(Environment.TEST)) {
      assertThat(context.getBean(ChaosTestFactory.class)).isNotNull();
    }

    assertThat(ChaosMicronautExtension.CONTEXT_PLANES).hasSize(registered);
  }
}
