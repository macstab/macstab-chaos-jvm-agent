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
 * through the injected bean are released at the end of each test class by {@link
 * ChaosMicronautExtension}, like those activated through its parameter.
 */
@Factory
@Requires(env = Environment.TEST)
class ChaosTestFactory {

  /** Default constructor invoked by Micronaut when the factory bean is instantiated. */
  ChaosTestFactory() {}

  /**
   * Exposes the JVM's control plane, tracked for the end of each test class.
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
