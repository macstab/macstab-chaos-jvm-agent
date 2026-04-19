package com.macstab.chaos.spring.boot4;

import com.macstab.chaos.bootstrap.ChaosPlatform;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Self-attaches the chaos JVM agent early in the Spring Boot startup sequence, before the
 * application context is refreshed and before most application beans (web server, HTTP clients,
 * connection pools) have been instantiated.
 *
 * <p>Registered via {@code
 * META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports}. Only attaches
 * when {@code macstab.chaos.enabled=true} is present in the resolved environment.
 */
public class ChaosAgentEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  private static final Logger LOGGER =
      Logger.getLogger(ChaosAgentEnvironmentPostProcessor.class.getName());

  /** Default constructor invoked by Spring Boot via SPI. */
  public ChaosAgentEnvironmentPostProcessor() {}

  @Override
  public void postProcessEnvironment(
      final ConfigurableEnvironment environment, final SpringApplication application) {
    if (!Boolean.parseBoolean(environment.getProperty("macstab.chaos.enabled", "false"))) {
      return;
    }
    try {
      ChaosPlatform.installLocally();
    } catch (final RuntimeException exception) {
      LOGGER.log(
          Level.WARNING,
          exception,
          () -> "chaos-agent: failed to self-attach during Spring Boot startup");
    }
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }
}
