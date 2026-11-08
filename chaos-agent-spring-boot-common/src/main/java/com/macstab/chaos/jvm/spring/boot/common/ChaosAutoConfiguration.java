package com.macstab.chaos.jvm.spring.boot.common;

import com.macstab.chaos.jvm.api.ChaosActivationConflictException;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import com.macstab.chaos.jvm.startup.ConfigLoadException;
import com.macstab.chaos.jvm.startup.LogText;
import com.macstab.chaos.jvm.startup.StartupConfigLoader;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Auto-configuration that wires the chaos agent runtime into a Spring Boot application. Shared
 * between the Spring Boot 3 and Spring Boot 4 starters — this class only uses APIs that are stable
 * across both versions.
 *
 * <p>Registration: {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} in each
 * version-specific starter points at this class.
 *
 * <p>All beans are gated behind {@code macstab.chaos.enabled=true} from a trusted property source,
 * decided by {@link TrustedChaosEnablement} — the same rule the early-attach {@code
 * EnvironmentPostProcessor} applies. When disabled, no chaos infrastructure is loaded into the
 * application context.
 */
@AutoConfiguration
@Conditional(OnTrustedChaosEnablementCondition.class)
@EnableConfigurationProperties(ChaosProperties.class)
@Import(ChaosSessionPropagationConfiguration.class)
public class ChaosAutoConfiguration {

  private static final Logger LOGGER = Logger.getLogger(ChaosAutoConfiguration.class.getName());

  private static final String CONFIG_FILE_PROPERTY = "macstab.chaos.config-file";

  /** Default constructor invoked by Spring when the configuration is imported. */
  public ChaosAutoConfiguration() {}

  /**
   * Exposes the installed {@link ChaosControlPlane} as a Spring-managed bean.
   *
   * <p>The control plane is a JVM-wide singleton held by {@code ChaosAgentBootstrap.RUNTIME}; it
   * outlives any single application context and is shared by every context in the JVM (common in
   * test suites), and by a {@code -javaagent} startup plan. {@code destroyMethod = ""} is required:
   * {@link ChaosControlPlane} is {@link AutoCloseable}, so Spring's default inferred destroy method
   * would call {@code close()} on context shutdown and tear down every scenario, listener and the
   * JFR sink JVM-wide. Per-context cleanup is handled by {@link ChaosHandleRegistry}, which stops
   * only the handles it registered.
   *
   * @return the installed control plane
   */
  @Bean(destroyMethod = "")
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
   * <p>The plan belongs to the context, not to the {@link ChaosControlPlane} bean: it is activated
   * on the JVM-wide control plane ({@link ChaosPlatform#installLocally()}), so a test's tracking
   * bean does not stop it when a test class ends, and it stops when the last context holding it
   * closes ({@link ChaosHandleRegistry#activateShared}). Only this context's own ready event
   * applies it; a child context's event, which reaches its parents too, does not.
   *
   * @param controlPlane the chaos control plane bean, for the debug dump
   * @param properties the chaos configuration properties
   * @param handleRegistry the starter-local handle registry
   * @param environment the environment, to check where {@code config-file} comes from
   * @param applicationContext this context
   * @return an {@link ApplicationListener} that performs the startup work
   */
  @Bean
  @ConditionalOnMissingBean(name = "chaosStartupApplier")
  public ApplicationListener<ApplicationReadyEvent> chaosStartupApplier(
      final ChaosControlPlane controlPlane,
      final ChaosProperties properties,
      final ChaosHandleRegistry handleRegistry,
      final ConfigurableEnvironment environment,
      final ApplicationContext applicationContext) {
    return event -> {
      if (event.getApplicationContext().equals(applicationContext)) {
        applyStartup(controlPlane, properties, handleRegistry, environment);
      }
    };
  }

  private static void applyStartup(
      final ChaosControlPlane controlPlane,
      final ChaosProperties properties,
      final ChaosHandleRegistry handleRegistry,
      final ConfigurableEnvironment environment) {
    final String configFile = properties.getConfigFile();
    // The plan file injects faults: an untrusted source may not name it, as for enabled.
    if (configFile != null && TrustedChaosEnablement.rejected(environment, CONFIG_FILE_PROPERTY)) {
      LOGGER.warning(
          "chaos-agent: "
              + CONFIG_FILE_PROPERTY
              + " comes from an untrusted property source and is ignored");
    } else if (configFile != null && !configFile.isBlank()) {
      // Strip line breaks and controls from the operator-supplied path before it is logged.
      // Without this, a property value like "/etc/plan.json\n2026-04-20 FORGED: chaos
      // deactivated" forges a second log line downstream in structured-log pipelines (CRLF
      // injection / log-entry splitting). Audit integrity depends on every emitted line
      // being attributable to a single log call.
      final String safeConfigFile = LogText.sanitize(configFile);
      try {
        final ChaosPlan plan = StartupConfigLoader.loadPlanFromFile(configFile);
        final ChaosActivationHandle handle =
            handleRegistry.activateShared(ChaosPlatform.installLocally(), plan);
        LOGGER.log(
            Level.INFO,
            "chaos-agent: activated startup plan \"{0}\" from {1}",
            new Object[] {handle.id(), safeConfigFile});
      } catch (final ConfigLoadException exception) {
        // Only the sanitised message, never the exception: its cause chain (Jackson's own
        // message) quotes an excerpt of the file, which can hold credentials.
        LOGGER.log(
            Level.SEVERE,
            () ->
                "chaos-agent: failed to load startup config file "
                    + safeConfigFile
                    + " (reason: "
                    + LogText.sanitize(exception.getMessage())
                    + ")");
      } catch (final ChaosActivationConflictException conflict) {
        // Another context holds a different plan with one of these scenario ids.
        LOGGER.log(
            Level.WARNING,
            () ->
                "chaos-agent: startup plan from "
                    + safeConfigFile
                    + " not activated: another context's startup plan holds one of its scenario"
                    + " ids (reason: "
                    + LogText.sanitize(conflict.getMessage())
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
   * Nested configuration that exposes the Actuator endpoint when both the Actuator classes are on
   * the classpath and {@code macstab.chaos.actuator.enabled=true}.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(Endpoint.class)
  @Conditional(OnTrustedActuatorEnablementCondition.class)
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
     * @return the Actuator endpoint bean
     */
    @Bean
    @ConditionalOnMissingBean
    public ChaosActuatorEndpoint chaosActuatorEndpoint(final ChaosControlPlane controlPlane) {
      if (!isSpringSecurityOnClasspath()) {
        LOGGER.log(
            Level.WARNING,
            "chaos-agent: /actuator/chaos endpoint is enabled but Spring Security is not on the"
                + " classpath. The endpoint exposes chaos activation/state; protect it with"
                + " Security or network-level controls before using in production.");
      }
      return new ChaosActuatorEndpoint(controlPlane);
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
