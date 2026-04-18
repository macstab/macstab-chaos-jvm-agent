package com.macstab.chaos.spring.boot4;

import com.macstab.chaos.api.ChaosActivationHandle;
import com.macstab.chaos.api.ChaosControlPlane;
import com.macstab.chaos.api.ChaosPlan;
import com.macstab.chaos.bootstrap.ChaosPlatform;
import com.macstab.chaos.startup.ChaosPlanMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Auto-configuration that wires the chaos agent runtime into a Spring Boot 4 application.
 *
 * <p>Registration: {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
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
   * @return the installed control plane
   */
  @Bean(destroyMethod = "close")
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
      try {
        final Path path = Path.of(configFile);
        final String json = Files.readString(path);
        final ChaosPlan plan = ChaosPlanMapper.read(json);
        final ChaosActivationHandle handle = controlPlane.activate(plan);
        handleRegistry.register(handle);
        LOGGER.log(
            Level.INFO,
            "chaos-agent: activated startup plan \"{0}\" from {1}",
            new Object[] {handle.id(), path});
      } catch (final IOException exception) {
        LOGGER.log(
            Level.SEVERE,
            exception,
            () -> "chaos-agent: failed to read startup config file " + configFile);
      } catch (final RuntimeException exception) {
        LOGGER.log(
            Level.SEVERE,
            exception,
            () -> "chaos-agent: failed to activate startup plan from " + configFile);
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
  @ConditionalOnProperty(prefix = "macstab.chaos.actuator", name = "enabled", havingValue = "true")
  public static class ActuatorConfiguration {

    /** Default constructor invoked by Spring when the nested configuration is imported. */
    public ActuatorConfiguration() {}

    /**
     * Exposes the {@code /actuator/chaos} endpoint.
     *
     * @param controlPlane the chaos control plane bean
     * @param handleRegistry the starter-local handle registry
     * @return the Actuator endpoint bean
     */
    @Bean
    @ConditionalOnMissingBean
    public ChaosActuatorEndpoint chaosActuatorEndpoint(
        final ChaosControlPlane controlPlane, final ChaosHandleRegistry handleRegistry) {
      return new ChaosActuatorEndpoint(controlPlane, handleRegistry);
    }
  }
}
