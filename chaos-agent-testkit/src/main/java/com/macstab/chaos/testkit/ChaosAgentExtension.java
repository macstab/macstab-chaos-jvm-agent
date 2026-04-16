package com.macstab.chaos.testkit;

import com.macstab.chaos.api.ChaosControlPlane;
import com.macstab.chaos.api.ChaosSession;
import com.macstab.chaos.bootstrap.ChaosPlatform;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

public final class ChaosAgentExtension
    implements BeforeEachCallback, AfterEachCallback, ParameterResolver {
  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(ChaosAgentExtension.class);

  @Override
  public void beforeEach(final ExtensionContext context) {
    final ChaosControlPlane controlPlane = ChaosPlatform.installLocally();
    final ChaosSession session = controlPlane.openSession(context.getDisplayName());
    context.getStore(NAMESPACE).put(ChaosControlPlane.class, controlPlane);
    context.getStore(NAMESPACE).put(ChaosSession.class, session);
  }

  @Override
  public void afterEach(final ExtensionContext context) {
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
    return extensionContext.getStore(NAMESPACE).get(parameterType, parameterType);
  }
}
