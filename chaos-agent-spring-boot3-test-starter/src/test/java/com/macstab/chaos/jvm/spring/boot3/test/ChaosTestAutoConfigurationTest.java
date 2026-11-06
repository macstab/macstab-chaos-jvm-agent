package com.macstab.chaos.jvm.spring.boot3.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The test auto-configuration exposes the JVM-wide control plane only in a context the initializer
 * marked as a chaos test.
 */
@DisplayName("ChaosTestAutoConfiguration")
class ChaosTestAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ChaosTestAutoConfiguration.class));

  @Test
  @DisplayName("in a chaos-test context the control plane bean tracks the installed one")
  void exposesInstalledControlPlane() {
    runner
        .withPropertyValues(ChaosTestAutoConfiguration.TEST_ENABLED_PROPERTY + "=true")
        .run(
            context -> {
              final ChaosControlPlane bean = context.getBean(ChaosControlPlane.class);
              assertThat(bean)
                  .isInstanceOf(com.macstab.chaos.jvm.testkit.TrackingChaosControlPlane.class);
              assertThat(bean.diagnostics().snapshot().runtimeDetails())
                  .isEqualTo(
                      ChaosPlatform.installLocally().diagnostics().snapshot().runtimeDetails());
            });
  }

  @Test
  @DisplayName("in any other context there is no control plane bean")
  void absentOtherwise() {
    runner.run(context -> assertThat(context).doesNotHaveBean(ChaosControlPlane.class));
  }
}
