package com.macstab.chaos.jvm.micronaut;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import com.macstab.chaos.jvm.testkit.TrackingChaosControlPlane;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

/**
 * In Micronaut's {@code test} environment ({@code @MicronautTest}), replaces the control-plane bean
 * of {@link ChaosFactory} with one that tracks what it activates: JVM-scoped scenarios activated
 * through the injected bean are released by {@link ChaosMicronautExtension} when the last running
 * chaos test class ends; a context can serve several classes at once.
 */
@Factory
@Requires(env = Environment.TEST)
class ChaosTestFactory {

  /** The control plane this context's factory created, released when the context closes. */
  private volatile @Nullable TrackingChaosControlPlane created;

  /** Default constructor invoked by Micronaut when the factory bean is instantiated. */
  ChaosTestFactory() {}

  /**
   * Exposes the JVM's control plane, tracked until its context closes or the last running chaos
   * test class ends.
   *
   * @return the tracked control plane
   */
  @Bean
  @Singleton
  @Replaces(value = ChaosControlPlane.class, factory = ChaosFactory.class)
  ChaosControlPlane chaosControlPlane() {
    // Installs the agent itself: Micronaut can create the bean before ChaosMicronautExtension's
    // beforeAll ran (a PER_CLASS test instance is injected in Micronaut's own beforeAll, an eager
    // bean when the context starts).
    final TrackingChaosControlPlane plane =
        ChaosMicronautExtension.contextPlane(ChaosPlatform.installLocally());
    created = plane;
    return plane;
  }

  /** Releases the context's control plane when the context closes: what it activated stops. */
  @PreDestroy
  void release() {
    final TrackingChaosControlPlane plane = created;
    if (plane != null) {
      ChaosMicronautExtension.releaseContextPlane(plane);
    }
  }
}
