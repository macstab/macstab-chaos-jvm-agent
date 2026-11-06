package com.macstab.chaos.jvm.micronaut;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import jakarta.inject.Singleton;

/**
 * In Micronaut's {@code test} environment ({@code @MicronautTest}), replaces the control-plane bean
 * of {@link ChaosFactory} with one that tracks what it activates: JVM-scoped scenarios activated
 * through the injected bean are released by {@link ChaosMicronautExtension} when the last running
 * chaos test class ends; a context can serve several classes at once.
 */
@Factory
@Requires(env = Environment.TEST)
class ChaosTestFactory {

  /** Default constructor invoked by Micronaut when the factory bean is instantiated. */
  ChaosTestFactory() {}

  /**
   * Exposes the JVM's control plane, tracked until the last running chaos test class ends.
   *
   * @return the tracked control plane
   */
  @Bean
  @Singleton
  @Replaces(value = ChaosControlPlane.class, factory = ChaosFactory.class)
  ChaosControlPlane chaosControlPlane() {
    return ChaosMicronautExtension.contextPlane(new ChaosFactory().chaosControlPlane());
  }
}
