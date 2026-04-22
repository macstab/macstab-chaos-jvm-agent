package com.macstab.chaos.spring.boot4;

import com.macstab.chaos.bootstrap.ChaosPlatform;
import java.util.Collections;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

/**
 * Self-attaches the chaos JVM agent early in the Spring Boot startup sequence, before the
 * application context is refreshed and before most application beans (web server, HTTP clients,
 * connection pools) have been instantiated.
 *
 * <p>Registered via {@code META-INF/spring.factories} under the Spring Boot 4 key {@code
 * org.springframework.boot.EnvironmentPostProcessor}. Spring Boot 4 still discovers
 * EnvironmentPostProcessor implementations exclusively through {@code spring.factories}; the {@code
 * META-INF/spring/*.imports} mechanism is reserved for auto-configuration and does not cover the
 * EPP SPI. Only attaches when {@code macstab.chaos.enabled=true} is present in the resolved
 * environment from a trusted local source.
 *
 * <p>On successful attach a single-entry property source named {@value #ATTACH_MARKER_SOURCE} is
 * prepended to the environment exposing {@link #ATTACH_MARKER_PROPERTY}{@code = spring-boot-4}.
 * This lets integration tests assert the EPP actually fired rather than a later {@code
 * ChaosAutoConfiguration} bean.
 */
public class ChaosAgentEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  /** Name of the property source registered when this EPP attaches the agent. */
  public static final String ATTACH_MARKER_SOURCE = "macstabChaosAgentAttachMarker";

  /** Key whose presence proves this EPP ran and attached the agent. */
  public static final String ATTACH_MARKER_PROPERTY = "macstab.chaos.agent.attach-phase";

  /** Value written to {@link #ATTACH_MARKER_PROPERTY} to identify this starter variant. */
  private static final String ATTACH_PHASE_VALUE = "spring-boot-4";

  /** Property key consulted to decide whether chaos instrumentation should be installed. */
  private static final String CHAOS_ENABLED_PROPERTY = "macstab.chaos.enabled";

  private static final Logger LOGGER =
      Logger.getLogger(ChaosAgentEnvironmentPostProcessor.class.getName());

  /**
   * Property-source names from which {@code macstab.chaos.enabled} will be honoured. Every other
   * source — Spring Cloud Config, Consul, Vault, remote overrides — is intentionally ignored:
   * self-attaching a JVM agent based on a value flipped by a compromised Config Server turns any
   * config-server RCE into an instrumentation RCE. Operators who genuinely need to enable chaos
   * remotely can set {@code macstab.chaos.allow-remote-enable=true} (checked via {@link
   * #environmentAllowsRemoteEnable}) through a trusted local source first, which is itself subject
   * to this trust filter.
   */
  private static final String CLASSPATH_CONFIG_SOURCE_PREFIX =
      "Config resource 'class path resource";

  private static final Set<String> TRUSTED_ENABLE_SOURCES =
      Set.of(
          "systemProperties",
          "systemEnvironment",
          "commandLineArgs",
          CLASSPATH_CONFIG_SOURCE_PREFIX
              + " [application.properties]' via location 'optional:classpath:/'",
          CLASSPATH_CONFIG_SOURCE_PREFIX
              + " [application.yml]' via location 'optional:classpath:/'");

  /** System property / env-var name that opts in to honouring remote property sources. */
  private static final String ALLOW_REMOTE_ENABLE_PROPERTY = "macstab.chaos.allow-remote-enable";

  /**
   * Name of the synthetic aggregator property source that Spring Boot prepends before EPPs fire. It
   * delegates containsProperty() to every real backing source, so it must be skipped to avoid
   * false-positive trust evaluations.
   */
  private static final String CONFIGURATION_PROPERTIES_AGGREGATOR_SOURCE =
      "configurationProperties";

  /** Default constructor invoked by Spring Boot via SPI. */
  public ChaosAgentEnvironmentPostProcessor() {}

  @Override
  public void postProcessEnvironment(
      final ConfigurableEnvironment environment, final SpringApplication application) {
    if (!enabledByTrustedSource(environment)) {
      return;
    }
    try {
      ChaosPlatform.installLocally();
      environment.getPropertySources().addFirst(buildAttachMarkerPropertySource());
    } catch (final RuntimeException exception) {
      LOGGER.log(
          Level.WARNING,
          exception,
          () -> "chaos-agent: failed to self-attach during Spring Boot startup");
    }
  }

  private static MapPropertySource buildAttachMarkerPropertySource() {
    return new MapPropertySource(
        ATTACH_MARKER_SOURCE, Collections.singletonMap(ATTACH_MARKER_PROPERTY, ATTACH_PHASE_VALUE));
  }

  /**
   * Returns {@code true} only when {@code macstab.chaos.enabled=true} resolves from a launch-time
   * property source (system property, env var, or a classpath-bundled application.properties/yml).
   * Values originating from Spring Cloud Config, Consul, or any remote source are rejected unless
   * the operator has explicitly opted in via {@link #ALLOW_REMOTE_ENABLE_PROPERTY} through a
   * trusted source. This preserves the pattern that agent attachment is a launch-time decision, not
   * a runtime one.
   */
  private static boolean enabledByTrustedSource(final ConfigurableEnvironment environment) {
    final String resolved = environment.getProperty(CHAOS_ENABLED_PROPERTY);
    if (!Boolean.parseBoolean(resolved)) {
      return false;
    }
    if (valueComesFromTrustedSource(environment, CHAOS_ENABLED_PROPERTY)) {
      return true;
    }
    if (Boolean.parseBoolean(environment.getProperty(ALLOW_REMOTE_ENABLE_PROPERTY))
        && valueComesFromTrustedSource(environment, ALLOW_REMOTE_ENABLE_PROPERTY)) {
      return true;
    }
    LOGGER.log(
        Level.WARNING,
        () ->
            "chaos-agent: ignoring macstab.chaos.enabled=true because it did not originate from a"
                + " trusted property source; set macstab.chaos.allow-remote-enable=true via a"
                + " trusted source to opt in");
    return false;
  }

  private static boolean valueComesFromTrustedSource(
      final ConfigurableEnvironment environment, final String propertyName) {
    for (final PropertySource<?> source : environment.getPropertySources()) {
      // Skip the "configurationProperties" synthetic aggregator. Spring Boot's
      // ConfigurationPropertySources.attach() prepends this wrapper before EPPs fire; it
      // forwards containsProperty() to every real source beneath it, so it appears to contain
      // any key that any underlying source owns. Evaluating trust against this wrapper's name
      // would always yield false (it is not a real source). Skip it so the real backing sources
      // are evaluated in precedence order instead.
      if (CONFIGURATION_PROPERTIES_AGGREGATOR_SOURCE.equals(source.getName())) {
        continue;
      }
      if (!source.containsProperty(propertyName)) {
        continue;
      }
      // First-match wins: Spring walks sources in precedence order, so the first source that
      // contains the key is the one whose value .getProperty() would return. If that source is
      // trusted, the value is trusted; if not, a later trusted source's copy is irrelevant
      // because Spring would never consult it.
      return TRUSTED_ENABLE_SOURCES.contains(source.getName())
          || source.getName().startsWith(CLASSPATH_CONFIG_SOURCE_PREFIX);
    }
    return false;
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }
}
