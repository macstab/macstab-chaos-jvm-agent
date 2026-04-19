package com.macstab.chaos.spring.boot4;

import com.macstab.chaos.bootstrap.ChaosPlatform;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

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
 * environment.
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
      environment
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  ATTACH_MARKER_SOURCE,
                  Collections.singletonMap(ATTACH_MARKER_PROPERTY, "spring-boot-4")));
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
