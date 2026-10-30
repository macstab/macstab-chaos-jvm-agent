package com.macstab.chaos.jvm.spring.boot4;

import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import com.macstab.chaos.jvm.spring.boot.common.TrustedChaosEnablement;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
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
 * EPP SPI. Runs directly after {@link ConfigDataEnvironmentPostProcessor}, so {@code
 * application.properties} / {@code .yml} values are visible. Only attaches when {@code
 * macstab.chaos.enabled=true} is present in the resolved environment from a trusted local source.
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

  /**
   * Runs directly after config data processing. An earlier version ran at {@code
   * HIGHEST_PRECEDENCE}, before {@code application.properties} / {@code .yml} were loaded, so a
   * flag set there never triggered the early attach.
   */
  static final int ORDER = ConfigDataEnvironmentPostProcessor.ORDER + 1;

  private static final String ATTACH_PHASE_VALUE = "spring-boot-4";

  private static final Logger LOGGER =
      Logger.getLogger(ChaosAgentEnvironmentPostProcessor.class.getName());

  /** Default constructor invoked by Spring Boot via SPI. */
  public ChaosAgentEnvironmentPostProcessor() {}

  @Override
  public void postProcessEnvironment(
      final ConfigurableEnvironment environment, final SpringApplication application) {
    final TrustedChaosEnablement.Decision decision = TrustedChaosEnablement.evaluate(environment);
    if (decision == TrustedChaosEnablement.Decision.REJECTED_UNTRUSTED_SOURCE) {
      LOGGER.log(
          Level.WARNING,
          () ->
              "chaos-agent: ignoring macstab.chaos.enabled=true because it did not originate from a"
                  + " trusted property source; set macstab.chaos.allow-remote-enable=true via a"
                  + " trusted source to opt in");
      return;
    }
    if (decision != TrustedChaosEnablement.Decision.ENABLED) {
      return;
    }
    try {
      ChaosPlatform.installLocally();
      environment.getPropertySources().addFirst(buildAttachMarkerSource());
    } catch (final RuntimeException exception) {
      LOGGER.log(
          Level.WARNING,
          exception,
          () -> "chaos-agent: failed to self-attach during Spring Boot startup");
    }
  }

  private static MapPropertySource buildAttachMarkerSource() {
    return new MapPropertySource(
        ATTACH_MARKER_SOURCE, Collections.singletonMap(ATTACH_MARKER_PROPERTY, ATTACH_PHASE_VALUE));
  }

  @Override
  public int getOrder() {
    return ORDER;
  }
}
