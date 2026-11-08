package com.macstab.chaos.jvm.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.PropertySource;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The factory hands out the JVM-wide control plane and attaches the agent only when enabled. */
@DisplayName("ChaosFactory")
class ChaosFactoryTest {

  @Test
  @DisplayName("enabled, it produces the control plane installed in this JVM")
  void enabledProducesTheInstalledControlPlane() {
    final String previous = System.getProperty(ChaosContextConfigurer.ENABLED_PROPERTY);
    System.setProperty(ChaosContextConfigurer.ENABLED_PROPERTY, "true");
    try (ApplicationContext context = ApplicationContext.run()) {
      assertThat(new ChaosFactory().chaosControlPlane(context.getEnvironment()))
          .isSameAs(ChaosPlatform.current());
    } finally {
      restore(previous);
    }
  }

  @Test
  @DisplayName("disabled, it produces an agent that is already installed")
  void disabledProducesTheAlreadyInstalledAgent() {
    final var installed = ChaosPlatform.installLocally();
    final String previous = System.getProperty(ChaosContextConfigurer.ENABLED_PROPERTY);
    System.setProperty(ChaosContextConfigurer.ENABLED_PROPERTY, "false");
    try (ApplicationContext context = ApplicationContext.run()) {
      assertThat(new ChaosFactory().chaosControlPlane(context.getEnvironment()))
          .isSameAs(installed);
    } finally {
      restore(previous);
    }
  }

  @Nested
  @DisplayName("the switch's source")
  class SwitchSource {

    @Test
    @DisplayName("ApplicationContext.run(Map) (the context source) switches chaos on")
    void contextSourceEnables() {
      try (ApplicationContext context =
          ApplicationContext.builder()
              .properties(Map.of(ChaosContextConfigurer.ENABLED_PROPERTY, "true"))
              .start()) {
        assertThat(ChaosFactory.chaosEnabled(context.getEnvironment())).isTrue();
      }
    }

    @Test
    @DisplayName("an application-<env>.properties file switches chaos on")
    void applicationFileEnables() {
      try (ApplicationContext context =
          ApplicationContext.builder().environments("chaosfile").start()) {
        assertThat(ChaosFactory.chaosEnabled(context.getEnvironment())).isTrue();
      }
    }

    @Test
    @DisplayName("a remote source (consul) does not switch chaos on")
    void remoteSourceIgnored() {
      try (ApplicationContext context =
          ApplicationContext.builder()
              .propertySources(
                  PropertySource.of(
                      "consul", Map.of(ChaosContextConfigurer.ENABLED_PROPERTY, "true")))
              .start()) {
        assertThat(ChaosFactory.chaosEnabled(context.getEnvironment())).isFalse();
      }
    }

    @Test
    @DisplayName("a remote source does so once allow-remote-enable comes from a local source")
    void remoteSourceAllowed() {
      try (ApplicationContext context =
          ApplicationContext.builder()
              .propertySources(
                  PropertySource.of(
                      "consul", Map.of(ChaosContextConfigurer.ENABLED_PROPERTY, "on")))
              .properties(Map.of("macstab.chaos.allow-remote-enable", "true"))
              .start()) {
        assertThat(ChaosFactory.chaosEnabled(context.getEnvironment())).isTrue();
      }
    }
  }

  private static void restore(final String previous) {
    if (previous == null) {
      System.clearProperty(ChaosContextConfigurer.ENABLED_PROPERTY);
    } else {
      System.setProperty(ChaosContextConfigurer.ENABLED_PROPERTY, previous);
    }
  }
}
