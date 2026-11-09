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
  @DisplayName("the starter registers the test and the session propagation auto-configurations")
  void autoConfigurationImports() {
    assertThat(
            org.springframework.boot.context.annotation.ImportCandidates.load(
                    org.springframework.boot.autoconfigure.AutoConfiguration.class,
                    getClass().getClassLoader())
                .getCandidates())
        .contains(
            ChaosTestAutoConfiguration.class.getName(),
            com.macstab.chaos.jvm.spring.boot.common.ChaosSessionPropagationAutoConfiguration.class
                .getName());
  }

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
  @DisplayName("with the application starter enabled too, the tracked control plane is the bean")
  void trackedPlaneWinsOverTheApplicationStarter() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                com.macstab.chaos.jvm.spring.boot.common.ChaosAutoConfiguration.class,
                ChaosTestAutoConfiguration.class))
        .withPropertyValues(
            "macstab.chaos.enabled=true",
            ChaosTestAutoConfiguration.TEST_ENABLED_PROPERTY + "=true")
        .run(
            context ->
                assertThat(context.getBean(ChaosControlPlane.class))
                    .isInstanceOf(com.macstab.chaos.jvm.testkit.TrackingChaosControlPlane.class));
  }

  @Test
  @DisplayName("closing the context releases its control plane and stops what it activated")
  void closingTheContextReleasesItsPlane() {
    final java.util.concurrent.atomic.AtomicReference<ChaosControlPlane> plane =
        new java.util.concurrent.atomic.AtomicReference<>();
    runner
        .withPropertyValues(ChaosTestAutoConfiguration.TEST_ENABLED_PROPERTY + "=true")
        .run(
            context -> {
              plane.set(context.getBean(ChaosControlPlane.class));
              plane
                  .get()
                  .activate(
                      com.macstab.chaos.jvm.api.ChaosScenario.builder("context-scoped")
                          .selector(
                              com.macstab.chaos.jvm.api.ChaosSelector.executor(
                                  java.util.Set.of(
                                      com.macstab.chaos.jvm.api.OperationType.EXECUTOR_SUBMIT)))
                          .effect(
                              com.macstab.chaos.jvm.api.ChaosEffect.delay(
                                  java.time.Duration.ofMillis(1)))
                          .build());
              assertThat(ChaosAgentExtension.CONTEXT_PLANES)
                  .contains((com.macstab.chaos.jvm.testkit.TrackingChaosControlPlane) plane.get());
            });

    assertThat(ChaosAgentExtension.CONTEXT_PLANES)
        .doesNotContain((com.macstab.chaos.jvm.testkit.TrackingChaosControlPlane) plane.get());
    assertThat(ChaosPlatform.installLocally().diagnostics().scenario("context-scoped")).isEmpty();
  }

  @Test
  @DisplayName("an application's own control plane bean is left alone when the context closes")
  void ownControlPlaneIsNotReleased() {
    final ChaosControlPlane jvm = ChaosPlatform.installLocally();
    // The application's bean: the JVM plane behind a close() that does nothing, so closing the
    // context (Spring closes AutoCloseable beans) does not close the JVM-wide runtime.
    final ChaosControlPlane own =
        (ChaosControlPlane)
            java.lang.reflect.Proxy.newProxyInstance(
                ChaosControlPlane.class.getClassLoader(),
                new Class<?>[] {ChaosControlPlane.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("close")) {
                    return null;
                  }
                  try {
                    return method.invoke(jvm, args);
                  } catch (final java.lang.reflect.InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
    final int registered = ChaosAgentExtension.CONTEXT_PLANES.size();

    runner
        .withBean(ChaosControlPlane.class, () -> own)
        .withPropertyValues(ChaosTestAutoConfiguration.TEST_ENABLED_PROPERTY + "=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(ChaosControlPlane.class)).isSameAs(own);
            });

    assertThat(ChaosAgentExtension.CONTEXT_PLANES).hasSize(registered);
  }

  @Test
  @DisplayName("in any other context there is no control plane bean")
  void absentOtherwise() {
    runner.run(context -> assertThat(context).doesNotHaveBean(ChaosControlPlane.class));
  }
}
