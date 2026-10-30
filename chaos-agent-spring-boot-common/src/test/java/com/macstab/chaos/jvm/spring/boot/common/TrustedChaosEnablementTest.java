package com.macstab.chaos.jvm.spring.boot.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

@DisplayName("Trusted chaos enablement")
class TrustedChaosEnablementTest {

  @Nested
  @DisplayName("decision")
  class Decision {

    @Test
    @DisplayName("is DISABLED when the flag is absent")
    void disabledWhenAbsent() {
      assertThat(TrustedChaosEnablement.evaluate(environmentWith()))
          .isEqualTo(TrustedChaosEnablement.Decision.DISABLED);
    }

    @Test
    @DisplayName("is ENABLED for a command-line argument")
    void enabledFromCommandLine() {
      assertThat(
              TrustedChaosEnablement.evaluate(
                  environmentWith(source("commandLineArgs", "macstab.chaos.enabled", "true"))))
          .isEqualTo(TrustedChaosEnablement.Decision.ENABLED);
    }

    @Test
    @DisplayName("is ENABLED for classpath config data, including profile-specific files")
    void enabledFromClasspathConfigData() {
      assertThat(
              TrustedChaosEnablement.evaluate(
                  environmentWith(
                      source(
                          "Config resource 'class path resource [application-dev.yml]' via"
                              + " location 'optional:classpath:/'",
                          "macstab.chaos.enabled",
                          "true"))))
          .isEqualTo(TrustedChaosEnablement.Decision.ENABLED);
    }

    @Test
    @DisplayName("is REJECTED when the winning source is a remote config source")
    void rejectedFromRemoteSource() {
      assertThat(
              TrustedChaosEnablement.evaluate(
                  environmentWith(
                      source("configserver:app", "macstab.chaos.enabled", "true"),
                      source("commandLineArgs", "macstab.chaos.enabled", "false"))))
          .isEqualTo(TrustedChaosEnablement.Decision.REJECTED_UNTRUSTED_SOURCE);
    }

    @Test
    @DisplayName("is ENABLED for a remote source when allow-remote-enable comes from a trusted one")
    void remoteAllowedByTrustedOptIn() {
      assertThat(
              TrustedChaosEnablement.evaluate(
                  environmentWith(
                      source("configserver:app", "macstab.chaos.enabled", "true"),
                      source("systemProperties", "macstab.chaos.allow-remote-enable", "true"))))
          .isEqualTo(TrustedChaosEnablement.Decision.ENABLED);
    }

    @Test
    @DisplayName("stays REJECTED when allow-remote-enable itself comes from the remote source")
    void remoteOptInFromRemoteIsIgnored() {
      assertThat(
              TrustedChaosEnablement.evaluate(
                  environmentWith(
                      new MapPropertySource(
                          "configserver:app",
                          Map.of(
                              "macstab.chaos.enabled", "true",
                              "macstab.chaos.allow-remote-enable", "true")))))
          .isEqualTo(TrustedChaosEnablement.Decision.REJECTED_UNTRUSTED_SOURCE);
    }
  }

  @Nested
  @DisplayName("ChaosAutoConfiguration")
  class AutoConfigurationGate {

    private final ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ChaosAutoConfiguration.class))
            .withUserConfiguration(ChaosAutoConfigurationTest.StubControlPlaneConfiguration.class);

    @Test
    @DisplayName("does not activate when the flag comes only from an untrusted source")
    void notActivatedFromUntrustedSource() {
      runner
          .withInitializer(
              context ->
                  context
                      .getEnvironment()
                      .getPropertySources()
                      .addFirst(source("configserver:app", "macstab.chaos.enabled", "true")))
          .run(context -> assertThat(context).doesNotHaveBean(ChaosHandleRegistry.class));
    }

    @Test
    @DisplayName("activates for a trusted source")
    void activatedFromTrustedSource() {
      runner
          .withPropertyValues("macstab.chaos.enabled=true")
          .run(context -> assertThat(context).hasSingleBean(ChaosHandleRegistry.class));
    }
  }

  @Test
  @DisplayName("the control-plane bean is never closed with the context (destroyMethod = \"\")")
  void controlPlaneBeanHasNoDestroyMethod() throws Exception {
    // ChaosControlPlane is AutoCloseable: without an explicit empty destroyMethod, Spring infers
    // close() and tears down the JVM-wide runtime whenever any context shuts down. Building the
    // real bean would self-attach the agent into the test JVM, so the declaration is checked.
    final Bean bean =
        ChaosAutoConfiguration.class.getMethod("chaosControlPlane").getAnnotation(Bean.class);
    assertThat(bean.destroyMethod()).isEmpty();
  }

  private static StandardEnvironment environmentWith(final MapPropertySource... sources) {
    final StandardEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    for (final MapPropertySource source : sources) {
      environment.getPropertySources().addLast(source);
    }
    return environment;
  }

  private static MapPropertySource source(final String name, final String key, final String value) {
    return new MapPropertySource(name, Map.of(key, value));
  }
}
