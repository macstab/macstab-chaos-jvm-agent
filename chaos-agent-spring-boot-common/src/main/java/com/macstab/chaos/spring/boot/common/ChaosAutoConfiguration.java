package com.macstab.chaos.spring.boot.common;

import com.macstab.chaos.api.ChaosActivationHandle;
import com.macstab.chaos.api.ChaosControlPlane;
import com.macstab.chaos.api.ChaosPlan;
import com.macstab.chaos.bootstrap.ChaosPlatform;
import com.macstab.chaos.startup.ConfigLoadException;
import com.macstab.chaos.startup.StartupConfigLoader;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration that wires the chaos agent runtime into a Spring Boot application. Shared
 * between the Spring Boot 3 and Spring Boot 4 starters — this class only uses APIs that are stable
 * across both versions.
 *
 * <p>Registration: {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} in each
 * version-specific starter points at this class.
 *
 * <p>All beans are gated behind {@code macstab.chaos.enabled=true}. When disabled, no chaos
 * infrastructure is loaded into the application context.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "macstab.chaos", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ChaosProperties.class)
public class ChaosAutoConfiguration {

  private static final Logger LOGGER = Logger.getLogger(ChaosAutoConfiguration.class.getName());

  /** Default constructor invoked by Spring when the configuration is imported. */
  public ChaosAutoConfiguration() {}

  /**
   * Exposes the installed {@link ChaosControlPlane} as a Spring-managed bean.
   *
   * <p>The control plane is a JVM-wide singleton held by {@code ChaosAgentBootstrap.RUNTIME}; it
   * survives context close and is safely shared across multiple application contexts in the same
   * JVM (common in test suites). For that reason the bean intentionally does <b>not</b> declare
   * {@code destroyMethod="close"} — tearing it down with the context would inert the runtime for
   * every other context as well. Per-context cleanup is handled by {@link ChaosHandleRegistry}
   * which stops only the handles it registered.
   *
   * @return the installed control plane
   */
  @Bean
  @ConditionalOnMissingBean
  public ChaosControlPlane chaosControlPlane() {
    return ChaosPlatform.installLocally();
  }

  /**
   * Registry of activation handles managed by this starter.
   *
   * @return a new empty registry bean
   */
  @Bean
  @ConditionalOnMissingBean
  public ChaosHandleRegistry chaosHandleRegistry() {
    return new ChaosHandleRegistry();
  }

  /**
   * Applies the optional startup plan file and the optional debug dump once the application context
   * is fully started.
   *
   * @param controlPlane the chaos control plane bean
   * @param properties the chaos configuration properties
   * @param handleRegistry the starter-local handle registry
   * @return an {@link ApplicationListener} that performs the startup work
   */
  @Bean
  @ConditionalOnMissingBean(name = "chaosStartupApplier")
  public ApplicationListener<ApplicationReadyEvent> chaosStartupApplier(
      final ChaosControlPlane controlPlane,
      final ChaosProperties properties,
      final ChaosHandleRegistry handleRegistry) {
    return event -> applyStartup(controlPlane, properties, handleRegistry);
  }

  private static void applyStartup(
      final ChaosControlPlane controlPlane,
      final ChaosProperties properties,
      final ChaosHandleRegistry handleRegistry) {
    final String configFile = properties.getConfigFile();
    if (configFile != null && !configFile.isBlank()) {
      // Strip CR/LF/NUL from the operator-supplied path before it enters any log record.
      // Without this, a property value like "/etc/plan.json\n2026-04-20 FORGED: chaos
      // deactivated" forges a second log line downstream in structured-log pipelines (CRLF
      // injection / log-entry splitting). Audit integrity depends on every emitted line
      // being attributable to a single log call.
      final String safeConfigFile = sanitizeForLog(configFile);
      try {
        final ChaosPlan plan = StartupConfigLoader.loadPlanFromFile(configFile);
        final ChaosActivationHandle handle = controlPlane.activate(plan);
        handleRegistry.register(handle);
        LOGGER.log(
            Level.INFO,
            "chaos-agent: activated startup plan \"{0}\" from {1}",
            new Object[] {handle.id(), safeConfigFile});
      } catch (final ConfigLoadException exception) {
        // Do not include the Jackson cause chain message: it can echo raw excerpts of the
        // file contents, which for attacker-influenced paths leaks credential bytes into stdout.
        // Log the stack trace (safe) via the Throwable overload; the message is sanitised.
        LOGGER.log(
            Level.SEVERE,
            exception,
            () ->
                "chaos-agent: failed to load startup config file "
                    + safeConfigFile
                    + " (reason: "
                    + exception.getMessage()
                    + ")");
      } catch (final RuntimeException exception) {
        LOGGER.log(
            Level.SEVERE,
            exception,
            () -> "chaos-agent: failed to activate startup plan from " + safeConfigFile);
      }
    }
    if (properties.isDebugDumpOnStart()) {
      LOGGER.log(
          Level.INFO,
          () -> "chaos-agent: startup diagnostics\n" + controlPlane.diagnostics().debugDump());
    }
  }

  /**
   * Strips control characters (CR, LF, NUL, and other C0 control codes) from operator-supplied
   * strings before they enter log records. Callers should pass values whose provenance is untrusted
   * — config paths, user-supplied IDs. Values are truncated at 1 KiB to bound the expense of
   * pathological inputs on hot paths.
   */
  private static final int LOG_SANITIZE_MAX_LENGTH = 1024;

  static String sanitizeForLog(final String value) {
    if (value == null) {
      return "";
    }
    final String truncated =
        value.length() > LOG_SANITIZE_MAX_LENGTH
            ? value.substring(0, LOG_SANITIZE_MAX_LENGTH)
            : value;
    final StringBuilder sanitized = new StringBuilder(truncated.length());
    for (int i = 0; i < truncated.length(); i++) {
      final char character = truncated.charAt(i);
      if (isControlCharacter(character)) {
        sanitized.append('_');
      } else {
        sanitized.append(character);
      }
    }
    return sanitized.toString();
  }

  private static boolean isControlCharacter(final char character) {
    return character == '\r'
        || character == '\n'
        || character == '\u0000'
        || (character < 0x20 && character != '\t');
  }

  /**
   * Nested configuration that exposes the Actuator endpoint when both the Actuator classes are on
   * the classpath and {@code macstab.chaos.actuator.enabled=true}.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(Endpoint.class)
  @ConditionalOnProperty(prefix = "macstab.chaos.actuator", name = "enabled", havingValue = "true")
  public static class ActuatorConfiguration {

    /** Default constructor invoked by Spring when the nested configuration is imported. */
    public ActuatorConfiguration() {}

    /**
     * Exposes the {@code /actuator/chaos} endpoint.
     *
     * <p>Emits a {@link Level#WARNING} log entry if Spring Security is not present on the
     * classpath: the endpoint exposes chaos activation/state over HTTP, and unauthenticated access
     * to it can be used to inject faults into a running application. Operators running the endpoint
     * in production without Security are responsible for protecting the endpoint via network-level
     * controls (reverse proxy auth, private management port, etc.).
     *
     * @param controlPlane the chaos control plane bean
     * @param handleRegistry the starter-local handle registry
     * @return the Actuator endpoint bean
     */
    @Bean
    @ConditionalOnMissingBean
    public ChaosActuatorEndpoint chaosActuatorEndpoint(
        final ChaosControlPlane controlPlane, final ChaosHandleRegistry handleRegistry) {
      if (!isSpringSecurityOnClasspath()) {
        LOGGER.log(
            Level.WARNING,
            "chaos-agent: /actuator/chaos endpoint is enabled but Spring Security is not on the"
                + " classpath. The endpoint exposes chaos activation/state; protect it with"
                + " Security or network-level controls before using in production.");
      }
      return new ChaosActuatorEndpoint(controlPlane, handleRegistry);
    }

    private static boolean isSpringSecurityOnClasspath() {
      try {
        Class.forName(
            "org.springframework.security.config.annotation.web.builders.HttpSecurity",
            false,
            ActuatorConfiguration.class.getClassLoader());
        return true;
      } catch (final ClassNotFoundException ignored) {
        return false;
      }
    }
  }
}
