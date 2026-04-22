package com.macstab.chaos.micronaut;

import com.macstab.chaos.bootstrap.ChaosPlatform;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.ApplicationContextConfigurer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link ApplicationContextConfigurer} that ensures the chaos agent is installed before the
 * Micronaut {@link io.micronaut.context.ApplicationContext} starts.
 *
 * <p>Registered via {@code META-INF/services/io.micronaut.context.ApplicationContextConfigurer} so
 * that Micronaut picks it up automatically whenever this integration jar is on the classpath. The
 * configurer fires during application bootstrap, well before any user beans are instantiated, which
 * guarantees that chaos-aware instrumentation is in place when framework and application code
 * begins to execute.
 *
 * <p>Installation is gated on the {@value #ENABLED_PROPERTY} system property / environment variable
 * (see {@link #chaosEnabled()}). When the application leaves this unset or sets it to {@code
 * false}, the configurer is a no-op — the integration jar can be left on the classpath in
 * production without triggering byte-code instrumentation. This mirrors the {@code
 * macstab.chaos.enabled} gate used by the Spring Boot starters.
 *
 * <p>{@link ChaosPlatform#installLocally()} is idempotent; invoking this configurer on a JVM that
 * already has the chaos agent installed (for example, via {@code -javaagent:}) is a no-op. Any
 * {@link RuntimeException} thrown by the installation path is logged and swallowed so a failure to
 * attach does not abort Micronaut startup — production traffic must be able to serve even when the
 * chaos platform is unavailable.
 */
public class ChaosContextConfigurer implements ApplicationContextConfigurer {

  /**
   * Property / environment variable consulted to decide whether to install the chaos agent during
   * Micronaut bootstrap. The same key used by the Spring Boot starters so a single build-level
   * override gates both stacks.
   */
  public static final String ENABLED_PROPERTY = "macstab.chaos.enabled";

  private static final String ENABLED_ENV_VAR =
      ENABLED_PROPERTY.replace('.', '_').toUpperCase();

  private static final Logger LOGGER = Logger.getLogger(ChaosContextConfigurer.class.getName());

  /** Default constructor invoked by Micronaut when the configurer is discovered. */
  public ChaosContextConfigurer() {}

  @Override
  public void configure(final ApplicationContextBuilder builder) {
    if (!chaosEnabled()) {
      return;
    }
    try {
      ChaosPlatform.installLocally();
    } catch (final RuntimeException exception) {
      LOGGER.log(
          Level.WARNING,
          exception,
          () -> "chaos-agent: failed to self-attach during Micronaut context bootstrap");
    }
  }

  /**
   * Resolves the {@value #ENABLED_PROPERTY} flag, preferring the system property over the
   * environment variable. The environment fallback uses upper-snake-case ({@code
   * MACSTAB_CHAOS_ENABLED}) to match the twelve-factor convention Micronaut itself adopts.
   */
  private static boolean chaosEnabled() {
    final String sysProp = System.getProperty(ENABLED_PROPERTY);
    if (sysProp != null) {
      return Boolean.parseBoolean(sysProp);
    }
    final String envVar = System.getenv(ENABLED_ENV_VAR);
    return Boolean.parseBoolean(envVar);
  }
}
