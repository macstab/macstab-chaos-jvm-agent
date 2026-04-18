package com.macstab.chaos.micronaut;

import com.macstab.chaos.api.ChaosControlPlane;
import com.macstab.chaos.api.ChaosSession;
import com.macstab.chaos.bootstrap.ChaosPlatform;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
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
 * <p>The extension injects {@link ChaosSession} and {@link ChaosControlPlane} parameters on test
 * methods and lifecycle methods. The resolver walks parent {@link ExtensionContext} instances so
 * that {@code @Nested} test classes inherit the session opened by their outer class.
 */
public final class ChaosMicronautExtension
    implements BeforeAllCallback, AfterAllCallback, ParameterResolver {
  static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(ChaosMicronautExtension.class);

  /** Default constructor invoked by JUnit when the extension is registered. */
  public ChaosMicronautExtension() {}

  @Override
  public void beforeAll(final ExtensionContext context) {
    final ChaosControlPlane controlPlane = ChaosPlatform.installLocally();
    final ChaosSession session = controlPlane.openSession(context.getDisplayName());
    context.getStore(NAMESPACE).put(ChaosControlPlane.class, controlPlane);
    context.getStore(NAMESPACE).put(ChaosSession.class, session);
  }

  @Override
  public void afterAll(final ExtensionContext context) {
    final ChaosSession session =
        context.getStore(NAMESPACE).remove(ChaosSession.class, ChaosSession.class);
    if (session != null) {
      session.close();
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
    ExtensionContext current = extensionContext;
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
