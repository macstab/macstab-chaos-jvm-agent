package com.macstab.chaos.micronaut;

import com.macstab.chaos.bootstrap.ChaosPlatform;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.ApplicationContextConfigurer;

/**
 * {@link ApplicationContextConfigurer} that ensures the chaos agent is installed before the
 * Micronaut {@link io.micronaut.context.ApplicationContext} starts.
 *
 * <p>Registered via {@code META-INF/micronaut/io.micronaut.context.ApplicationContextConfigurer} so
 * that Micronaut picks it up automatically whenever this integration jar is on the classpath. The
 * configurer fires during application bootstrap, well before any user beans are instantiated, which
 * guarantees that chaos-aware instrumentation is in place when framework and application code
 * begins to execute.
 *
 * <p>{@link ChaosPlatform#installLocally()} is idempotent; invoking this configurer on a JVM that
 * already has the chaos agent installed (for example, via {@code -javaagent:}) is a no-op.
 */
public class ChaosContextConfigurer implements ApplicationContextConfigurer {

  /** Default constructor invoked by Micronaut when the configurer is discovered. */
  public ChaosContextConfigurer() {}

  @Override
  public void configure(final ApplicationContextBuilder builder) {
    ChaosPlatform.installLocally();
  }
}
