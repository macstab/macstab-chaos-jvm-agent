package com.macstab.chaos.jvm.micronaut;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
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
 * <p>The returned control plane is the JVM-wide instance; it attaches the agent only when the
 * application's configuration switches chaos on (see {@link #chaosControlPlane(Environment)}).
 * Closing the control plane is the responsibility of the JVM shutdown sequence; this factory does
 * not register a bean-level {@code preDestroy} hook because the chaos platform tolerates multiple
 * {@code close()} calls and must remain live for the entire application lifetime.
 */
@Factory
public class ChaosFactory {

  /** Default constructor invoked by Micronaut when the factory bean is instantiated. */
  public ChaosFactory() {}

  /**
   * Exposes the JVM's {@link ChaosControlPlane} as a Micronaut singleton bean. The agent is
   * attached here only when {@value ChaosContextConfigurer#ENABLED_PROPERTY} is true in the
   * application's configuration and comes from a launch-time local source (see {@link
   * #chaosEnabled(Environment)}); otherwise the bean is the agent already installed ({@code
   * -javaagent:}, a chaos test extension), and injecting it without one fails. When the attach
   * failed during the context bootstrap ({@link ChaosContextConfigurer} logs it and the context
   * starts), creating this bean attaches again and fails with the same error: inject {@code
   * Optional<ChaosControlPlane>} or {@code BeanProvider<ChaosControlPlane>} where the application
   * must run without the agent.
   *
   * @param environment the application's environment
   * @return the JVM-wide control plane
   * @throws IllegalStateException when chaos is not enabled and no agent is installed
   */
  @Bean
  @Singleton
  @Requires(missingBeans = ChaosControlPlane.class)
  public ChaosControlPlane chaosControlPlane(final Environment environment) {
    return chaosEnabled(environment) ? ChaosPlatform.installLocally() : ChaosPlatform.current();
  }

  /**
   * Whether {@code environment} switches chaos on: {@value ChaosContextConfigurer#ENABLED_PROPERTY}
   * is true and comes from system properties, environment variables, the command line, {@code
   * ApplicationContext.run(Map)}, the {@code application} or {@code application-<env>} file, a file
   * of {@code micronaut.config.files} or Micronaut Test's {@code @Property}; from any other source
   * only when {@code macstab.chaos.allow-remote-enable=true} comes from one of those.
   */
  static boolean chaosEnabled(final Environment environment) {
    return TrustedSwitch.on(environment, ChaosContextConfigurer.ENABLED_PROPERTY);
  }
}
