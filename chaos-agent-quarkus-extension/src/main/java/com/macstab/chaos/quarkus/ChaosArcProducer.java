package com.macstab.chaos.quarkus;

import com.macstab.chaos.api.ChaosControlPlane;
import com.macstab.chaos.bootstrap.ChaosPlatform;
import io.quarkus.arc.DefaultBean;
import io.quarkus.arc.properties.IfBuildProperty;
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
 * <h2>Build-time gate</h2>
 *
 * <p>The producer is gated by the {@code macstab.chaos.enabled} build-time property. When the
 * property is missing it defaults to {@code true}, so an application that does not opt out keeps
 * the existing behaviour. Setting {@code macstab.chaos.enabled=false} at build time removes the
 * producer from the CDI container entirely — production deployments that bundle the extension for
 * non-prod profiles but must never install the chaos plane at runtime can rely on this gate rather
 * than having to guarantee {@code ChaosPlatform.installLocally()} is never called from user code.
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
   * <p>Only registered when {@code macstab.chaos.enabled} is unset or {@code true}. When the
   * property is {@code false} at build time, the producer is excluded and injection of {@link
   * ChaosControlPlane} will fail with a CDI unsatisfied-dependency error unless the application
   * provides its own producer — exactly the behaviour we want for an operator who built the app
   * with chaos explicitly disabled.
   *
   * @return the active {@link ChaosControlPlane}, installed on demand if the agent has not yet
   *     attached
   */
  @Produces
  @ApplicationScoped
  @DefaultBean
  @IfBuildProperty(name = "macstab.chaos.enabled", stringValue = "true", enableIfMissing = true)
  public ChaosControlPlane chaosControlPlane() {
    return ChaosPlatform.installLocally();
  }
}
