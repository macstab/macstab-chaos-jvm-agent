package com.macstab.chaos.jvm.spring.boot.common;

import java.util.Set;
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
 * EnvironmentPostProcessor} and {@link ChaosAutoConfiguration}. Gating only the former let the
 * auto-configuration attach the agent anyway from an untrusted source.
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

  /**
   * Synthetic aggregator Spring Boot attaches before config data is processed. It forwards {@code
   * containsProperty} to every real source beneath it, so it is skipped when looking for the source
   * that owns a value.
   */
  private static final String CONFIGURATION_PROPERTIES_AGGREGATOR = "configurationProperties";

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
    if (!Boolean.parseBoolean(environment.getProperty(ENABLED_PROPERTY))) {
      return Decision.DISABLED;
    }
    if (comesFromTrustedSource(environment, ENABLED_PROPERTY)) {
      return Decision.ENABLED;
    }
    if (Boolean.parseBoolean(environment.getProperty(ALLOW_REMOTE_ENABLE_PROPERTY))
        && comesFromTrustedSource(environment, ALLOW_REMOTE_ENABLE_PROPERTY)) {
      return Decision.ENABLED;
    }
    return Decision.REJECTED_UNTRUSTED_SOURCE;
  }

  /**
   * Returns {@code true} when the property source that wins for {@code propertyName} is trusted.
   * Sources are walked in precedence order and the first one containing the key decides, because
   * that is the source whose value {@link ConfigurableEnvironment#getProperty} returns.
   */
  private static boolean comesFromTrustedSource(
      final ConfigurableEnvironment environment, final String propertyName) {
    for (final PropertySource<?> source : environment.getPropertySources()) {
      if (CONFIGURATION_PROPERTIES_AGGREGATOR.equals(source.getName())) {
        continue;
      }
      if (!source.containsProperty(propertyName)) {
        continue;
      }
      final String name = source.getName();
      return TRUSTED_SOURCE_NAMES.contains(name) || name.startsWith(CLASSPATH_CONFIG_SOURCE_PREFIX);
    }
    return false;
  }
}
