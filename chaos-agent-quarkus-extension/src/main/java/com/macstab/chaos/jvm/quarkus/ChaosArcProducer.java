package com.macstab.chaos.jvm.quarkus;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
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
 * <h2>Run-time switch</h2>
 *
 * <p>The producer is always registered; whether it attaches the agent is decided at run time by
 * {@code macstab.chaos.enabled}, honoured only from a launch-time local source (see {@link
 * ChaosQuarkusRecorder}). Without the switch {@link ChaosPlatform#installLocally()} is never
 * invoked, so dropping the extension jar onto a production classpath does not self-attach a
 * JVM-wide chaos plane; injecting the bean then fails on first use unless an agent is already
 * installed ({@code -javaagent:}, the test extension).
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
   * <p>The agent is attached only while {@code macstab.chaos.enabled} is true at run time.
   * Otherwise the bean is an agent already installed ({@code -javaagent:}, the test extension), and
   * using it without one fails with {@link IllegalStateException}.
   *
   * @return the active {@link ChaosControlPlane}
   * @throws IllegalStateException when chaos is disabled at run time and no agent is installed
   */
  @Produces
  @ApplicationScoped
  @DefaultBean
  public ChaosControlPlane chaosControlPlane() {
    return ChaosQuarkusRecorder.chaosEnabled()
        ? ChaosPlatform.installLocally()
        : ChaosPlatform.current();
  }
}
