package com.macstab.chaos.jvm.micronaut;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

/**
 * Micronaut {@link Factory} that exposes the installed {@link ChaosControlPlane} as a singleton
 * bean.
 *
 * <p>The factory is picked up by Micronaut's compile-time dependency injection at application
 * startup. The {@link Requires} condition makes the factory back off if the user supplies their own
 * {@link ChaosControlPlane} bean (mirroring the {@code @ConditionalOnMissingBean} pattern used by
 * the Spring Boot starters).
 *
 * <p>The returned control plane is the JVM-wide instance; it attaches the agent only under the same
 * opt-in as {@link ChaosContextConfigurer} (see {@link #chaosControlPlane()}). Closing the control
 * plane is the responsibility of the JVM shutdown sequence; this factory does not register a
 * bean-level {@code preDestroy} hook because the chaos platform tolerates multiple {@code close()}
 * calls and must remain live for the entire application lifetime.
 */
@Factory
public class ChaosFactory {

  /** Default constructor invoked by Micronaut when the factory bean is instantiated. */
  public ChaosFactory() {}

  /**
   * Exposes the JVM's {@link ChaosControlPlane} as a Micronaut singleton bean. The agent is
   * attached here only when {@value ChaosContextConfigurer#ENABLED_PROPERTY} is set, like the
   * context configurer does; otherwise the bean is the agent already installed ({@code
   * -javaagent:}, a chaos test extension), and injecting it without one fails.
   *
   * @return the JVM-wide control plane
   * @throws IllegalStateException when chaos is not enabled and no agent is installed
   */
  @Bean
  @Singleton
  @Requires(missingBeans = ChaosControlPlane.class)
  public ChaosControlPlane chaosControlPlane() {
    return ChaosContextConfigurer.chaosEnabled()
        ? ChaosPlatform.installLocally()
        : ChaosPlatform.current();
  }
}
