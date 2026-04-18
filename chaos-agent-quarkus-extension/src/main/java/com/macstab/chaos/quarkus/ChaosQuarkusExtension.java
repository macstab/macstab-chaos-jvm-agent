package com.macstab.chaos.quarkus;

import com.macstab.chaos.api.ActivationPolicy;
import com.macstab.chaos.api.ChaosControlPlane;
import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.ChaosSelector;
import com.macstab.chaos.api.ChaosSession;
import com.macstab.chaos.api.OperationType;
import com.macstab.chaos.bootstrap.ChaosPlatform;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * JUnit 5 extension that installs the chaos agent once per test JVM and opens a class-scoped {@link
 * ChaosSession} for Quarkus-based tests.
 *
 * <p>Installed automatically by {@link QuarkusChaosTest}, or directly via
 * {@code @ExtendWith(ChaosQuarkusExtension.class)} on any JUnit test class.
 *
 * <h2>Lifecycle</h2>
 *
 * <ul>
 *   <li>{@code beforeAll}: self-attach the agent (idempotent), open one session for the whole test
 *       class, and activate every {@link ChaosScenario} annotation present on the class.
 *   <li>{@code beforeEach}: activate every {@link ChaosScenario} annotation present on the test
 *       method.
 *   <li>{@code afterAll}: close the session, which stops every session-scoped scenario activated
 *       during the class.
 * </ul>
 *
 * <h2>Parameter injection</h2>
 *
 * <p>The extension injects {@link ChaosSession} and {@link ChaosControlPlane} parameters on test
 * methods and lifecycle methods.
 */
public final class ChaosQuarkusExtension
    implements BeforeAllCallback, BeforeEachCallback, AfterAllCallback, ParameterResolver {
  static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(ChaosQuarkusExtension.class);

  /** Default constructor invoked by JUnit when the extension is registered. */
  public ChaosQuarkusExtension() {}

  @Override
  public void beforeAll(final ExtensionContext context) {
    final ChaosControlPlane controlPlane = ChaosPlatform.installLocally();
    final ChaosSession session = controlPlane.openSession(context.getDisplayName());
    context.getStore(NAMESPACE).put(ChaosControlPlane.class, controlPlane);
    context.getStore(NAMESPACE).put(ChaosSession.class, session);
    final Class<?> testClass = context.getTestClass().orElse(null);
    if (testClass != null) {
      activateAnnotations(
          controlPlane, session, testClass.getAnnotationsByType(ChaosScenario.class));
    }
  }

  @Override
  public void beforeEach(final ExtensionContext context) {
    final Method testMethod = context.getTestMethod().orElse(null);
    if (testMethod == null) {
      return;
    }
    final ChaosSession session = lookupSession(context);
    final ChaosControlPlane controlPlane = lookupControlPlane(context);
    if (session == null || controlPlane == null) {
      return;
    }
    activateAnnotations(
        controlPlane, session, testMethod.getAnnotationsByType(ChaosScenario.class));
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

  private static ChaosSession lookupSession(final ExtensionContext context) {
    ExtensionContext current = context;
    while (current != null) {
      final ChaosSession session =
          current.getStore(NAMESPACE).get(ChaosSession.class, ChaosSession.class);
      if (session != null) {
        return session;
      }
      current = current.getParent().orElse(null);
    }
    return null;
  }

  private static ChaosControlPlane lookupControlPlane(final ExtensionContext context) {
    ExtensionContext current = context;
    while (current != null) {
      final ChaosControlPlane controlPlane =
          current.getStore(NAMESPACE).get(ChaosControlPlane.class, ChaosControlPlane.class);
      if (controlPlane != null) {
        return controlPlane;
      }
      current = current.getParent().orElse(null);
    }
    return null;
  }

  private static void activateAnnotations(
      final ChaosControlPlane controlPlane,
      final ChaosSession session,
      final ChaosScenario[] annotations) {
    if (annotations == null) {
      return;
    }
    for (final ChaosScenario annotation : annotations) {
      final com.macstab.chaos.api.ChaosScenario scenario = toScenario(annotation);
      if (scenario.scope() == com.macstab.chaos.api.ChaosScenario.ScenarioScope.JVM) {
        controlPlane.activate(scenario);
      } else {
        session.activate(scenario);
      }
    }
  }

  static com.macstab.chaos.api.ChaosScenario toScenario(final ChaosScenario annotation) {
    final com.macstab.chaos.api.ChaosScenario.ScenarioScope scope =
        com.macstab.chaos.api.ChaosScenario.ScenarioScope.valueOf(
            annotation.scope().toUpperCase(Locale.ROOT));
    final ChaosSelector selector = parseSelector(annotation.selector());
    final ChaosEffect effect = parseEffect(annotation.effect());
    return com.macstab.chaos.api.ChaosScenario.builder(annotation.id())
        .scope(scope)
        .selector(selector)
        .effect(effect)
        .activationPolicy(ActivationPolicy.always())
        .build();
  }

  private static ChaosSelector parseSelector(final String selector) {
    if (selector == null || selector.isBlank()) {
      throw new IllegalArgumentException("selector must be non-blank");
    }
    final String normalized = selector.trim();
    switch (normalized) {
      case "executor":
        return ChaosSelector.executor(
            EnumSet.of(OperationType.EXECUTOR_SUBMIT, OperationType.EXECUTOR_WORKER_RUN));
      case "jvmRuntime":
        return ChaosSelector.jvmRuntime(EnumSet.of(OperationType.SYSTEM_CLOCK_MILLIS));
      case "httpClient":
        return ChaosSelector.httpClient(
            EnumSet.of(OperationType.HTTP_CLIENT_SEND, OperationType.HTTP_CLIENT_SEND_ASYNC));
      case "jdbc":
        return ChaosSelector.jdbc();
      default:
        throw new IllegalArgumentException(
            "unsupported selector identifier: "
                + selector
                + "; expected one of executor, jvmRuntime, httpClient, jdbc");
    }
  }

  private static ChaosEffect parseEffect(final String effect) {
    if (effect == null || effect.isBlank()) {
      throw new IllegalArgumentException("effect must be non-blank");
    }
    final String normalized = effect.trim();
    if (normalized.startsWith("delay:")) {
      final String durationText = normalized.substring("delay:".length());
      final Duration delay = Duration.parse(durationText);
      return ChaosEffect.delay(delay);
    }
    switch (normalized) {
      case "suppress":
        return ChaosEffect.suppress();
      case "freeze":
        return ChaosEffect.skewClock(Duration.ofSeconds(1), ChaosEffect.ClockSkewMode.FREEZE);
      default:
        throw new IllegalArgumentException(
            "unsupported effect identifier: "
                + effect
                + "; expected delay:<duration>, suppress, or freeze");
    }
  }

  // Defensive, package-private helper so tests can verify the operation set used for executor
  // scenarios without depending on internal constants.
  static Set<OperationType> defaultExecutorOperations() {
    return EnumSet.of(OperationType.EXECUTOR_SUBMIT, OperationType.EXECUTOR_WORKER_RUN);
  }
}
