package com.macstab.chaos.jvm.spring.boot.common;

import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

/**
 * Decides whether {@code macstab.chaos.enabled=true} may switch chaos on, based on which property
 * source the value came from.
 *
 * <p>Attaching a JVM agent is a launch-time decision. The flag is honoured only when the property
 * source that wins for {@code macstab.chaos.enabled} is a local, launch-time source:
 *
 * <ul>
 *   <li>JVM system properties, environment variables and command-line arguments;
 *   <li>config data bundled on the classpath ({@code application.properties} / {@code .yml},
 *       including profile-specific variants);
 *   <li>property sources created by Spring's test support ({@code @SpringBootTest(properties)},
 *       {@code @TestPropertySource(properties)}, {@code @DynamicPropertySource}, {@code
 *       TestPropertyValues}), which exist only in test contexts.
 * </ul>
 *
 * <p>Every other source — Spring Cloud Config, Consul, Vault, any remote or runtime override — is
 * ignored: honouring it would turn a compromised config server into the ability to instrument the
 * JVM. Operators who need remote enablement opt in by setting {@code
 * macstab.chaos.allow-remote-enable=true} through a trusted source, which is itself subject to the
 * same check.
 *
 * <p>The same decision gates both the early self-attach in the version-specific {@code
 * EnvironmentPostProcessor} and {@link ChaosAutoConfiguration}. Gating only the former would let
 * the auto-configuration attach the agent anyway from an untrusted source.
 */
public final class TrustedChaosEnablement {

  /** Property that switches chaos on. */
  public static final String ENABLED_PROPERTY = "macstab.chaos.enabled";

  /** Property that, set through a trusted source, allows any source to switch chaos on. */
  public static final String ALLOW_REMOTE_ENABLE_PROPERTY = "macstab.chaos.allow-remote-enable";

  /** Name prefix of config-data property sources loaded from the classpath. */
  static final String CLASSPATH_CONFIG_SOURCE_PREFIX = "Config resource 'class path resource";

  /**
   * Property-source names treated as launch-time sources. {@code "Inlined Test Properties"}, {@code
   * "Dynamic Test Properties"} and {@code "test"} are the names Spring's test support gives to the
   * sources it creates.
   */
  static final Set<String> TRUSTED_SOURCE_NAMES =
      Set.of(
          "systemProperties",
          "systemEnvironment",
          "commandLineArgs",
          "Inlined Test Properties",
          "Dynamic Test Properties",
          "test");

  private TrustedChaosEnablement() {}

  /** Outcome of evaluating the enablement flag against an environment. */
  public enum Decision {
    /** The flag is absent or not {@code true}. */
    DISABLED,
    /** The flag is {@code true} and comes from a trusted source (or remote enable is allowed). */
    ENABLED,
    /** The flag is {@code true} but comes from an untrusted source; it must be ignored. */
    REJECTED_UNTRUSTED_SOURCE
  }

  /**
   * Evaluates {@code macstab.chaos.enabled} in {@code environment}.
   *
   * @param environment the environment to inspect; must not be {@code null}
   * @return the decision; never {@code null}
   */
  public static Decision evaluate(final ConfigurableEnvironment environment) {
    final Winner enabled = winner(environment, ENABLED_PROPERTY);
    if (enabled == null || !Boolean.parseBoolean(enabled.value())) {
      return Decision.DISABLED;
    }
    if (enabled.trusted()) {
      return Decision.ENABLED;
    }
    return remoteAllowed(environment) ? Decision.ENABLED : Decision.REJECTED_UNTRUSTED_SOURCE;
  }

  /**
   * Returns the value of {@code propertyName} when the source it comes from is trusted, or when
   * {@value #ALLOW_REMOTE_ENABLE_PROPERTY} allows untrusted sources; {@code null} when the property
   * is absent or its source may not set it. For the settings that inject faults as {@value
   * #ENABLED_PROPERTY} does: the actuator switch and the startup plan file.
   *
   * @param environment the environment to inspect
   * @param propertyName the property, in canonical form
   * @return the trusted value, or {@code null}
   */
  static @Nullable String trustedValue(
      final ConfigurableEnvironment environment, final String propertyName) {
    final Winner winner = winner(environment, propertyName);
    if (winner == null) {
      return null;
    }
    return winner.trusted() || remoteAllowed(environment) ? winner.value() : null;
  }

  /**
   * Whether {@code propertyName} is set by a source that may not set it.
   *
   * @param environment the environment to inspect
   * @param propertyName the property, in canonical form
   * @return {@code true} when the property is present but its value is not trusted
   */
  static boolean rejected(final ConfigurableEnvironment environment, final String propertyName) {
    return winner(environment, propertyName) != null
        && trustedValue(environment, propertyName) == null;
  }

  private static boolean remoteAllowed(final ConfigurableEnvironment environment) {
    final Winner allowRemote = winner(environment, ALLOW_REMOTE_ENABLE_PROPERTY);
    return allowRemote != null
        && allowRemote.trusted()
        && Boolean.parseBoolean(allowRemote.value());
  }

  /** The value of a property and whether the source it came from is trusted. */
  private record Winner(String value, boolean trusted) {}

  /**
   * Finds the source that wins for {@code propertyName} the way Spring Boot resolves it: sources in
   * precedence order, each matched under every spelling relaxed binding accepts ({@code
   * macstab.chaos.Enabled}, {@code MACSTAB_CHAOS_ENABLED}, ...). The value and the trust verdict
   * come from that one source. Matching the exact name only let an untrusted source with another
   * spelling set the value while a trusted source holding the exact name vouched for it.
   */
  private static @Nullable Winner winner(
      final ConfigurableEnvironment environment, final String propertyName) {
    final ConfigurationPropertyName name = ConfigurationPropertyName.of(propertyName);
    for (final ConfigurationPropertySource source :
        ConfigurationPropertySources.from(environment.getPropertySources())) {
      // from() adapts each PropertySource and skips the configurationProperties aggregator.
      final ConfigurationProperty property = source.getConfigurationProperty(name);
      if (property == null) {
        continue;
      }
      final String sourceName = ((PropertySource<?>) source.getUnderlyingSource()).getName();
      return new Winner(
          String.valueOf(property.getValue()),
          TRUSTED_SOURCE_NAMES.contains(sourceName)
              || sourceName.startsWith(CLASSPATH_CONFIG_SOURCE_PREFIX));
    }
    return null;
  }
}
