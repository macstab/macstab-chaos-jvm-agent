package com.macstab.chaos.jvm.spring.boot.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

@DisplayName("Trusted chaos enablement")
class TrustedChaosEnablementTest {

  @Nested
  @DisplayName("other fault-injecting settings (actuator switch, plan file)")
  class OtherSettings {

    private static final String ACTUATOR = "macstab.chaos.actuator.enabled";

    @Test
    @DisplayName("a trusted source sets the value")
    void trustedSourceSets() {
      final var environment = environmentWith(source("systemProperties", ACTUATOR, "true"));
      assertThat(TrustedChaosEnablement.trustedValue(environment, ACTUATOR)).isEqualTo("true");
      assertThat(TrustedChaosEnablement.rejected(environment, ACTUATOR)).isFalse();
    }

    @Test
    @DisplayName("an untrusted source is rejected, even when a trusted one holds the property too")
    void untrustedSourceIsRejected() {
      final var environment =
          environmentWith(
              source("configserver:app", ACTUATOR, "true"),
              source("commandLineArgs", ACTUATOR, "false"));
      assertThat(TrustedChaosEnablement.trustedValue(environment, ACTUATOR)).isNull();
      assertThat(TrustedChaosEnablement.rejected(environment, ACTUATOR)).isTrue();
    }

    @Test
    @DisplayName("allow-remote-enable from a trusted source admits an untrusted value")
    void remoteAllowed() {
      final var environment =
          environmentWith(
              source("configserver:app", ACTUATOR, "true"),
              source("systemProperties", "macstab.chaos.allow-remote-enable", "true"));
      assertThat(TrustedChaosEnablement.trustedValue(environment, ACTUATOR)).isEqualTo("true");
    }

    @Test
    @DisplayName("allow-remote-enable=false from a trusted source admits nothing")
    void remoteNotAllowed() {
      final var environment =
          environmentWith(
              source("configserver:app", ACTUATOR, "true"),
              source("systemProperties", "macstab.chaos.allow-remote-enable", "false"));
      assertThat(TrustedChaosEnablement.rejected(environment, ACTUATOR)).isTrue();
    }

    @Test
    @DisplayName("the actuator condition does not match without a configurable environment")
    void conditionNeedsConfigurableEnvironment() {
      final org.springframework.core.env.Environment plain =
          (org.springframework.core.env.Environment)
              java.lang.reflect.Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {org.springframework.core.env.Environment.class},
                  (proxy, method, args) -> null);
      final org.springframework.context.annotation.ConditionContext context =
          (org.springframework.context.annotation.ConditionContext)
              java.lang.reflect.Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {org.springframework.context.annotation.ConditionContext.class},
                  (proxy, method, args) ->
                      method.getName().equals("getEnvironment") ? plain : null);

      assertThat(
              new OnTrustedActuatorEnablementCondition().getMatchOutcome(context, null).isMatch())
          .isFalse();
    }

    @Test
    @DisplayName("an absent property is neither set nor rejected")
    void absent() {
      final var environment = environmentWith();
      assertThat(TrustedChaosEnablement.trustedValue(environment, ACTUATOR)).isNull();
      assertThat(TrustedChaosEnablement.rejected(environment, ACTUATOR)).isFalse();
    }
  }

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

    @Test
    @DisplayName("is REJECTED when an untrusted source holds the flag under another spelling")
    void rejectedUnderAnotherSpelling() {
      // Spring Boot's relaxed binding resolves another spelling of the flag; the source that
      // holds it decides, and it is untrusted.
      final StandardEnvironment environment =
          environmentWith(source("configserver:app", "macstab.chaos.Enabled", "true"));
      ConfigurationPropertySources.attach(environment);

      assertThat(environment.getProperty("macstab.chaos.enabled")).isEqualTo("true");
      assertThat(TrustedChaosEnablement.evaluate(environment))
          .isEqualTo(TrustedChaosEnablement.Decision.REJECTED_UNTRUSTED_SOURCE);
    }

    @Test
    @DisplayName(
        "an untrusted source cannot borrow a trusted source's trust through another spelling")
    void relaxedSpellingCannotBorrowTrust() {
      // getProperty answers from the remote source (first, relaxed spelling); the trusted source
      // holding the exact name says false and must not vouch for the remote value.
      final StandardEnvironment environment =
          environmentWith(
              source("configserver:app", "macstab.chaos.Enabled", "true"),
              source("commandLineArgs", "macstab.chaos.enabled", "false"));
      ConfigurationPropertySources.attach(environment);

      assertThat(environment.getProperty("macstab.chaos.enabled")).isEqualTo("true");
      assertThat(TrustedChaosEnablement.evaluate(environment))
          .isEqualTo(TrustedChaosEnablement.Decision.REJECTED_UNTRUSTED_SOURCE);
    }

    @Test
    @DisplayName("a trusted source is trusted under any spelling Spring resolves")
    void trustedSourceUnderRelaxedSpelling() {
      final StandardEnvironment environment =
          environmentWith(source("commandLineArgs", "macstab.chaos.Enabled", "true"));
      ConfigurationPropertySources.attach(environment);

      assertThat(TrustedChaosEnablement.evaluate(environment))
          .isEqualTo(TrustedChaosEnablement.Decision.ENABLED);
    }

    @Test
    @DisplayName("an environment variable enables chaos under its upper-case name")
    void environmentVariableSpelling() {
      final StandardEnvironment environment = environmentWith();
      environment
          .getPropertySources()
          .addFirst(
              new org.springframework.core.env.SystemEnvironmentPropertySource(
                  StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                  Map.of("MACSTAB_CHAOS_ENABLED", "true")));
      ConfigurationPropertySources.attach(environment);

      assertThat(TrustedChaosEnablement.evaluate(environment))
          .isEqualTo(TrustedChaosEnablement.Decision.ENABLED);
    }

    @Test
    @DisplayName("the condition does not match an environment it cannot inspect by source")
    void conditionNeedsConfigurableEnvironment() {
      final org.springframework.core.env.Environment plain =
          (org.springframework.core.env.Environment)
              java.lang.reflect.Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {org.springframework.core.env.Environment.class},
                  (self, method, args) -> null);
      final org.springframework.context.annotation.ConditionContext context =
          (org.springframework.context.annotation.ConditionContext)
              java.lang.reflect.Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {org.springframework.context.annotation.ConditionContext.class},
                  (self, method, args) -> method.getName().equals("getEnvironment") ? plain : null);

      final org.springframework.boot.autoconfigure.condition.ConditionOutcome outcome =
          new OnTrustedChaosEnablementCondition().getMatchOutcome(context, null);

      assertThat(outcome.isMatch()).isFalse();
      assertThat(outcome.getMessage()).isEqualTo("environment is not a ConfigurableEnvironment");
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
