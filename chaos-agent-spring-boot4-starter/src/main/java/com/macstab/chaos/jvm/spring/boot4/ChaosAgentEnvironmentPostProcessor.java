package com.macstab.chaos.jvm.spring.boot4;

import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import com.macstab.chaos.jvm.spring.boot.common.TrustedChaosEnablement;
import com.macstab.chaos.jvm.startup.LogText;
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
 * prepended to the environment exposing {@link #ATTACH_MARKER_PROPERTY}{@code = spring-boot-4}. It
 * shows in the environment (e.g. {@code /actuator/env}) that the agent attached during environment
 * preparation, before any bean was created.
 *
 * <p>A failed attach ({@link RuntimeException} or {@link LinkageError}) is logged once and does not
 * abort startup: a property source named {@value #ATTACH_FAILED_SOURCE} sets {@link
 * TrustedChaosEnablement#ATTACH_FAILED_PROPERTY}{@code =true}, so the auto-configuration creates no
 * chaos bean and does not attach again.
 */
public class ChaosAgentEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  /** Name of the property source registered when this EPP attaches the agent. */
  public static final String ATTACH_MARKER_SOURCE = "macstabChaosAgentAttachMarker";

  /** Name of the property source registered when the agent failed to attach. */
  static final String ATTACH_FAILED_SOURCE = "macstabChaosAgentAttachFailure";

  /** Key whose presence proves this EPP ran and attached the agent. */
  public static final String ATTACH_MARKER_PROPERTY = "macstab.chaos.agent.attach-phase";

  /**
   * Runs directly after config data processing, so a flag set in {@code application.properties} /
   * {@code .yml} is already loaded and triggers the early attach; at {@code HIGHEST_PRECEDENCE} it
   * would not be.
   */
  static final int ORDER = ConfigDataEnvironmentPostProcessor.ORDER + 1;

  private static final String ATTACH_PHASE_VALUE = "spring-boot-4";

  private static final Logger LOGGER =
      Logger.getLogger(ChaosAgentEnvironmentPostProcessor.class.getName());

  private final Runnable install;

  /** Default constructor invoked by Spring Boot via SPI. */
  public ChaosAgentEnvironmentPostProcessor() {
    this(ChaosPlatform::installLocally);
  }

  /**
   * Test seam: runs {@code install} instead of {@link ChaosPlatform#installLocally()}.
   *
   * @param install the agent installation
   */
  ChaosAgentEnvironmentPostProcessor(final Runnable install) {
    this.install = install;
  }

  @Override
  public void postProcessEnvironment(
      final ConfigurableEnvironment environment, final SpringApplication application) {
    final TrustedChaosEnablement.Decision decision = TrustedChaosEnablement.evaluate(environment);
    if (decision == TrustedChaosEnablement.Decision.REJECTED_UNTRUSTED_SOURCE) {
      LOGGER.log(
          Level.WARNING,
          () ->
              "[chaos-agent] ignoring macstab.chaos.enabled=true because it did not originate from a"
                  + " trusted property source; set macstab.chaos.allow-remote-enable=true via a"
                  + " trusted source to opt in");
      return;
    }
    if (decision != TrustedChaosEnablement.Decision.ENABLED) {
      return;
    }
    try {
      install.run();
      environment.getPropertySources().addFirst(buildAttachMarkerSource());
    } catch (final RuntimeException | LinkageError failure) {
      // LinkageError: the attach mechanism or a class the agent needs is missing; that must not
      // abort application startup. The marker keeps the auto-configuration from attaching again.
      LOGGER.log(
          Level.WARNING,
          () ->
              "[chaos-agent] failed to self-attach during Spring Boot startup; chaos stays off"
                  + " (reason: "
                  + LogText.sanitize(failure.toString())
                  + ")");
      environment
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  ATTACH_FAILED_SOURCE,
                  Collections.singletonMap(
                      TrustedChaosEnablement.ATTACH_FAILED_PROPERTY, Boolean.TRUE.toString())));
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
