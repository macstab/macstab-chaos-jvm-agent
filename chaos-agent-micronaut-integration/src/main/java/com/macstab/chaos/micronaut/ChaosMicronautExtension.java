package com.macstab.chaos.micronaut;

import com.macstab.chaos.api.ChaosControlPlane;
import com.macstab.chaos.api.ChaosSession;
import com.macstab.chaos.bootstrap.ChaosPlatform;
import com.macstab.chaos.testkit.TrackingChaosControlPlane;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * JUnit 5 extension that installs the chaos agent once per test JVM and opens a class-scoped {@link
 * ChaosSession} for the annotated Micronaut test class.
 *
 * <p>Used by {@link MicronautChaosTest}. May also be combined directly via
 * {@code @ExtendWith(ChaosMicronautExtension.class)} on any class annotated with
 * {@code @MicronautTest}.
 *
 * <h2>Lifecycle</h2>
 *
 * <ul>
 *   <li>{@code beforeAll}: self-attach the agent (idempotent) and open one session for the whole
 *       class.
 *   <li>{@code afterAll}: close the session, stopping every session-scoped scenario activated
 *       during the class.
 * </ul>
 *
 * <h2>Parameter injection</h2>
 *
 * <p>The extension always injects {@link ChaosSession} on test methods and lifecycle methods. The
 * resolver walks parent {@link ExtensionContext} instances so that {@code @Nested} test classes
 * inherit the session opened by their outer class.
 *
 * <p>{@link ChaosControlPlane} resolution is conditional. Micronaut's JUnit 5 extension (from
 * {@code micronaut-test-junit5}) resolves any parameter whose type is a bean in the {@code
 * ApplicationContext}, and {@link ChaosControlPlane} is exposed as a bean via {@code
 * ChaosFactory.chaosControlPlane()}. If both resolvers claimed the parameter, JUnit would raise
 * {@code ParameterResolutionException("Discovered multiple competing ParameterResolvers ...")}. To
 * avoid the collision this extension detects the presence of {@link MicronautTest} on the test
 * class (walking parents so nested classes inherit the decision) and yields ownership of the
 * control-plane parameter to Micronaut in that case. When the extension is used standalone (without
 * {@link MicronautTest}), no competing resolver exists and this extension resolves the control
 * plane from its own store.
 */
public final class ChaosMicronautExtension
    implements BeforeAllCallback, AfterAllCallback, ParameterResolver {
  static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(ChaosMicronautExtension.class);

  /** Default constructor invoked by JUnit when the extension is registered. */
  public ChaosMicronautExtension() {}

  @Override
  public void beforeAll(final ExtensionContext context) {
    // Wrap the JVM-wide control plane in a tracking decorator so any JVM-scoped scenarios the
    // test class activates through it are released in afterAll. Without tracking, an activation
    // via controlPlane.activate(jvmScopedScenario) leaks into subsequent test classes: session
    // close() only stops session-scoped scenarios, and the JVM-wide control-plane singleton lives
    // for the JVM lifetime. Tracking here is the only per-class cleanup hook available.
    final TrackingChaosControlPlane tracker =
        new TrackingChaosControlPlane(ChaosPlatform.installLocally());
    final ChaosSession session = tracker.openSession(context.getDisplayName());
    context.getStore(NAMESPACE).put(ChaosControlPlane.class, tracker);
    context.getStore(NAMESPACE).put(ChaosSession.class, session);
  }

  @Override
  public void afterAll(final ExtensionContext context) {
    final ChaosSession session =
        context.getStore(NAMESPACE).remove(ChaosSession.class, ChaosSession.class);
    final ChaosControlPlane controlPlane =
        context.getStore(NAMESPACE).remove(ChaosControlPlane.class, ChaosControlPlane.class);
    // Close the session first so session-scoped scenarios are stopped via their owning session's
    // lifecycle, then drain any JVM-scoped handles the test class activated via the tracker.
    // try/finally ensures stopTracked() runs even if session.close() throws.
    try {
      if (session != null) {
        session.close();
      }
    } finally {
      if (controlPlane instanceof TrackingChaosControlPlane tracker) {
        tracker.stopTracked();
      }
    }
  }

  @Override
  public boolean supportsParameter(
      final ParameterContext parameterContext, final ExtensionContext extensionContext) {
    final Class<?> parameterType = parameterContext.getParameter().getType();
    if (parameterType == ChaosSession.class) {
      return true;
    }
    if (parameterType == ChaosControlPlane.class) {
      // When the test class carries @MicronautTest, Micronaut's JUnit 5 extension is registered
      // and already resolves any parameter whose type is a bean in the ApplicationContext (which
      // includes ChaosControlPlane via ChaosFactory). Claiming the parameter here too would raise
      // ParameterResolutionException("Discovered multiple competing ParameterResolvers ..."). When
      // the test uses this extension standalone, no competing resolver exists and we must resolve
      // the control plane ourselves — see class Javadoc.
      return !isMicronautTest(extensionContext);
    }
    return false;
  }

  private static boolean isMicronautTest(final ExtensionContext context) {
    ExtensionContext current = context;
    while (current != null) {
      final var testClass = current.getTestClass();
      if (testClass.isPresent() && testClass.get().isAnnotationPresent(MicronautTest.class)) {
        return true;
      }
      current = current.getParent().orElse(null);
    }
    return false;
  }

  @Override
  public Object resolveParameter(
      final ParameterContext parameterContext, final ExtensionContext extensionContext) {
    final Class<?> parameterType = parameterContext.getParameter().getType();
    ExtensionContext current = extensionContext;
    while (current != null) {
      final Object value = current.getStore(NAMESPACE).get(parameterType, parameterType);
      if (value != null) {
        return value;
      }
      current = current.getParent().orElse(null);
    }
    // JUnit 5 contract: once supportsParameter() returns true, resolveParameter() must return
    // non-null or throw. Returning null silently injects null into the test parameter, causing
    // an NPE inside the test body with a misleading stack trace pointing at the test rather
    // than the missing setup (missing @MicronautChaosTest, beforeAll not yet run, etc.).
    throw new ParameterResolutionException(
        "ChaosMicronautExtension: no "
            + parameterType.getSimpleName()
            + " available — ensure the test class is annotated with @MicronautChaosTest"
            + " and beforeAll() has run before parameter injection");
  }
}
