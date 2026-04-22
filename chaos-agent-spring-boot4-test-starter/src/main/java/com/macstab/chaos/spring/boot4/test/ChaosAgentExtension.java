package com.macstab.chaos.spring.boot4.test;

import com.macstab.chaos.api.ChaosControlPlane;
import com.macstab.chaos.api.ChaosSession;
import com.macstab.chaos.bootstrap.ChaosPlatform;
import com.macstab.chaos.testkit.TrackingChaosControlPlane;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * JUnit 5 extension that installs the chaos agent once per test JVM and opens a class-scoped {@link
 * ChaosSession} for the annotated test class.
 *
 * <p>Used by {@link ChaosTest}. May also be combined directly via
 * {@code @ExtendWith(ChaosAgentExtension.class)} on any class annotated with {@link
 * org.springframework.boot.test.context.SpringBootTest}.
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
 * <p>The extension injects {@link ChaosSession} and {@link ChaosControlPlane} parameters on test
 * methods and lifecycle methods.
 */
public final class ChaosAgentExtension
    implements BeforeAllCallback, AfterAllCallback, ParameterResolver {
  static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(ChaosAgentExtension.class);

  /** Default constructor invoked by JUnit when the extension is registered. */
  public ChaosAgentExtension() {}

  @Override
  public void beforeAll(final ExtensionContext context) {
    // Wrap the JVM-wide control plane in a tracking decorator so any JVM-scoped scenarios the
    // test class activates through the injected control-plane parameter are released in afterAll.
    // Without tracking, a test calling controlPlane.activate(jvmScopedScenario) would leak the
    // handle into subsequent test classes — session close() only stops session-scoped scenarios.
    final TrackingChaosControlPlane tracker =
        new TrackingChaosControlPlane(ChaosPlatform.installLocally());
    final ChaosSession session = tracker.openSession(context.getDisplayName());
    final ExtensionContext.Store store = context.getStore(NAMESPACE);
    store.put(ChaosControlPlane.class, tracker);
    store.put(ChaosSession.class, session);
  }

  @Override
  public void afterAll(final ExtensionContext context) {
    final ExtensionContext.Store store = context.getStore(NAMESPACE);
    final ChaosSession session = store.remove(ChaosSession.class, ChaosSession.class);
    final ChaosControlPlane controlPlane =
        store.remove(ChaosControlPlane.class, ChaosControlPlane.class);
    // Close the session first so session-scoped scenarios stop via their owning session's
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
    return parameterType == ChaosSession.class || parameterType == ChaosControlPlane.class;
  }

  @Override
  public Object resolveParameter(
      final ParameterContext parameterContext, final ExtensionContext extensionContext) {
    final Class<?> parameterType = parameterContext.getParameter().getType();
    final Object resolved = findInContextHierarchy(extensionContext, parameterType);
    if (resolved != null) {
      return resolved;
    }
    throw new ParameterResolutionException(
        "ChaosAgentExtension: no "
            + parameterType.getSimpleName()
            + " available — ensure the test class is annotated with @ChaosTest"
            + " and beforeAll() has run before parameter injection");
  }

  private Object findInContextHierarchy(
      final ExtensionContext startContext, final Class<?> parameterType) {
    ExtensionContext current = startContext;
    while (current != null) {
      final Object value = current.getStore(NAMESPACE).get(parameterType, parameterType);
      if (value != null) {
        return value;
      }
      current = current.getParent().orElse(null);
    }
    return null;
  }
}
