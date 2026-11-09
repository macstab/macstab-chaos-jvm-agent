package com.macstab.chaos.jvm.quarkus;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import io.quarkus.arc.DefaultBean;
import io.quarkus.runtime.LaunchMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import java.lang.reflect.InvocationTargetException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

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
 * <h2>In a Quarkus test</h2>
 *
 * <p>In {@link LaunchMode#TEST} the bean is the control plane tracked by {@link
 * ChaosQuarkusExtension}: what a test activates through an injected {@code ChaosControlPlane} is
 * stopped when the last running chaos test class ends, or when the application stops. The extension
 * runs in the class loader JUnit loaded it from, the producer in Quarkus's, so the producer reaches
 * it through the system class loader; the api is shared because it is a parent-first artifact.
 * Where the extension is not on the system class path, the bean is the untracked control plane and
 * one warning says so.
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

  private static final Logger LOGGER = Logger.getLogger(ChaosArcProducer.class.getName());

  /** The JUnit extension that tracks the bean in a test, loaded by the system class loader. */
  private static final String EXTENSION = "com.macstab.chaos.jvm.quarkus.ChaosQuarkusExtension";

  /** Default constructor required by CDI. */
  public ChaosArcProducer() {}

  /**
   * Produces the singleton {@link ChaosControlPlane}.
   *
   * <p>The agent is attached only while {@code macstab.chaos.enabled} is true at run time.
   * Otherwise the bean is an agent already installed ({@code -javaagent:}, the test extension), and
   * using it without one fails with {@link IllegalStateException}.
   *
   * <p>When the attach failed at runtime-init ({@code ChaosQuarkusRecorder} logs it and the
   * application starts), creating this bean attaches again and fails with the same error: inject
   * {@code Instance<ChaosControlPlane>} where the application must run without the agent.
   *
   * @return the active {@link ChaosControlPlane}
   * @throws IllegalStateException when chaos is disabled at run time and no agent is installed
   */
  @Produces
  @ApplicationScoped
  @DefaultBean
  public ChaosControlPlane chaosControlPlane() {
    final ChaosControlPlane plane =
        ChaosQuarkusRecorder.chaosEnabled()
            ? ChaosPlatform.installLocally()
            : ChaosPlatform.current();
    if (LaunchMode.current() != LaunchMode.TEST) {
      return plane;
    }
    return callExtension("contextPlane", plane) instanceof ChaosControlPlane tracked
        ? tracked
        : plane;
  }

  /**
   * Releases a test's control plane when the application stops: what it activated stops.
   *
   * @param plane the bean being destroyed
   */
  void release(@Disposes final ChaosControlPlane plane) {
    if (LaunchMode.current() == LaunchMode.TEST) {
      callExtension("releaseContextPlane", plane);
    }
  }

  /**
   * Calls a static method of the system class loader's {@link ChaosQuarkusExtension} with {@code
   * plane}; returns its result, or {@code null} with one warning when it cannot.
   */
  private static @Nullable Object callExtension(
      final String method, final ChaosControlPlane plane) {
    try {
      final Class<?> extension = Class.forName(EXTENSION, true, ClassLoader.getSystemClassLoader());
      return extension.getMethod(method, ChaosControlPlane.class).invoke(null, plane);
    } catch (final ClassNotFoundException
        | NoSuchMethodException
        | IllegalAccessException
        | InvocationTargetException
        | IllegalArgumentException
        | LinkageError unreachable) {
      LOGGER.log(
          Level.WARNING,
          unreachable,
          () ->
              "[chaos-agent] the test extension is not reachable from the system class loader;"
                  + " what a test activates through the injected ChaosControlPlane is not stopped"
                  + " after the test class");
      return null;
    }
  }
}
