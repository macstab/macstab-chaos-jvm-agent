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
 * <p>The producer is gated by the {@code macstab.chaos.enabled} build-time property and is
 * <b>opt-in</b>: when the property is absent the producer is <i>not</i> registered and {@link
 * ChaosPlatform#installLocally()} is never invoked. Dropping the extension jar onto a production
 * classpath therefore does not silently self-attach a JVM-wide chaos plane; an operator must
 * explicitly set {@code macstab.chaos.enabled=true} at build time. This matches the default chosen
 * by the recorder side of the extension and the Spring Boot starter, so the behaviour across
 * frameworks is consistent: chaos is never active-by-default, only by affirmative build-time
 * opt-in.
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
   * <p>Only registered when {@code macstab.chaos.enabled=true} is set at build time. When the
   * property is unset or {@code false}, the producer is excluded from the CDI container and
   * injection of {@link ChaosControlPlane} will fail with an unsatisfied-dependency error unless
   * the application provides its own producer — exactly the behaviour we want so that merely
   * shipping the extension on the classpath does not self-attach chaos in production.
   *
   * @return the active {@link ChaosControlPlane}, installed on demand if the agent has not yet
   *     attached
   */
  @Produces
  @ApplicationScoped
  @DefaultBean
  @IfBuildProperty(name = "macstab.chaos.enabled", stringValue = "true", enableIfMissing = false)
  public ChaosControlPlane chaosControlPlane() {
    return ChaosPlatform.installLocally();
  }
}
