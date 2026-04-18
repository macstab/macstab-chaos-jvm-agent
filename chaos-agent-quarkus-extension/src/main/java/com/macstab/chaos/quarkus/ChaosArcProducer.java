package com.macstab.chaos.quarkus;

import com.macstab.chaos.api.ChaosControlPlane;
import com.macstab.chaos.bootstrap.ChaosPlatform;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * CDI producer that exposes the installed {@link ChaosControlPlane} as an application-scoped
 * Quarkus bean.
 *
 * <p>The producer returns the same control-plane instance that the agent installs on the JVM. The
 * bean is marked {@link DefaultBean} so user code can override it by declaring a higher-priority
 * producer or a {@code @Priority}-annotated {@code @Produces} method.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * @ApplicationScoped
 * public class MyService {
 *
 *   @Inject
 *   ChaosControlPlane chaos;
 *
 *   void perform() {
 *     try (var session = chaos.openSession("my-service")) {
 *       // ...
 *     }
 *   }
 * }
 * }</pre>
 */
@ApplicationScoped
public class ChaosArcProducer {

  /** Default constructor required by CDI. */
  public ChaosArcProducer() {}

  /**
   * Produces the singleton {@link ChaosControlPlane}.
   *
   * @return the active {@link ChaosControlPlane}, installed on demand if the agent has not yet
   *     attached
   */
  @Produces
  @ApplicationScoped
  @DefaultBean
  public ChaosControlPlane chaosControlPlane() {
    return ChaosPlatform.installLocally();
  }
}
