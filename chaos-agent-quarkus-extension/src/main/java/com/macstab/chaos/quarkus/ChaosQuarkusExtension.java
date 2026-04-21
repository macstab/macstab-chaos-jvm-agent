package com.macstab.chaos.quarkus;

import com.macstab.chaos.api.ActivationPolicy;
import com.macstab.chaos.api.ChaosActivationHandle;
import com.macstab.chaos.api.ChaosControlPlane;
import com.macstab.chaos.api.ChaosEffect;
import com.macstab.chaos.api.ChaosSelector;
import com.macstab.chaos.api.ChaosSession;
import com.macstab.chaos.api.OperationType;
import com.macstab.chaos.bootstrap.ChaosPlatform;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
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
 *       method. JVM-scoped activations are tracked per-method so {@code afterEach} can tear them
 *       down — otherwise the second test method that declared the same scenario id would hit an
 *       {@code "already active"} activation conflict.
 *   <li>{@code afterEach}: stop JVM-scoped scenarios activated by the current method's annotations.
 *       Session-scoped scenarios remain active for the whole class.
 *   <li>{@code afterAll}: close the session (which stops every session-scoped scenario activated
 *       during the class) and stop any JVM-scoped scenarios activated by class-level annotations.
 * </ul>
 *
 * <h2>Parameter injection</h2>
 *
 * <p>The extension injects {@link ChaosSession} and {@link ChaosControlPlane} parameters on test
 * methods and lifecycle methods.
 */
public final class ChaosQuarkusExtension
    implements BeforeAllCallback,
        BeforeEachCallback,
        AfterEachCallback,
        AfterAllCallback,
        ParameterResolver {
  static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(ChaosQuarkusExtension.class);

  /** Store key for the per-class list of JVM-scoped handles that must be stopped in afterAll. */
  private static final String JVM_HANDLES_KEY = "jvmScopedHandles";

  /**
   * Store key, scoped to the per-test-method {@link ExtensionContext.Store}, for JVM-scoped handles
   * activated from annotations on the test method itself. These must be stopped in afterEach —
   * otherwise a {@code @ChaosScenario(id="x", scope="JVM")} on two different test methods causes
   * the second method to throw {@code IllegalStateException("scenario x already active")} because
   * the first method's handle is still registered.
   */
  private static final String METHOD_JVM_HANDLES_KEY = "methodJvmScopedHandles";

  /** Default constructor invoked by JUnit when the extension is registered. */
  public ChaosQuarkusExtension() {}

  @Override
  public void beforeAll(final ExtensionContext context) {
    final ChaosControlPlane controlPlane = ChaosPlatform.installLocally();
    final ChaosSession session = controlPlane.openSession(context.getDisplayName());
    context.getStore(NAMESPACE).put(ChaosControlPlane.class, controlPlane);
    context.getStore(NAMESPACE).put(ChaosSession.class, session);
    // Class-scoped list of handles for JVM-scoped scenarios activated via @ChaosScenario.
    // Session-scoped scenarios are torn down by session.close(); JVM-scoped ones outlive any
    // session and previously leaked into subsequent tests — we now stop them in afterAll.
    context.getStore(NAMESPACE).put(JVM_HANDLES_KEY, new ArrayList<ChaosActivationHandle>());
    final Class<?> testClass = context.getTestClass().orElse(null);
    if (testClass != null) {
      activateAnnotations(
          controlPlane,
          session,
          testClass.getAnnotationsByType(ChaosScenario.class),
          jvmHandles(context));
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
    // Track JVM-scoped activations from *this method's* annotations in a method-scoped list so
    // afterEach can stop exactly them. context.getStore(NAMESPACE) in beforeEach/afterEach is
    // scoped to the test-method context, so values written here are isolated per method and do
    // not bleed into sibling tests. Class-level JVM-scoped annotations keep using the class-
    // scoped list populated by beforeAll (see JVM_HANDLES_KEY above).
    final List<ChaosActivationHandle> methodJvmHandles = new ArrayList<>();
    context.getStore(NAMESPACE).put(METHOD_JVM_HANDLES_KEY, methodJvmHandles);
    activateAnnotations(
        controlPlane,
        session,
        testMethod.getAnnotationsByType(ChaosScenario.class),
        methodJvmHandles);
  }

  @Override
  public void afterEach(final ExtensionContext context) {
    // Stop JVM-scoped scenarios activated by annotations on the current test method. Without
    // this, a second test method declaring the same scenario id would collide with the still-
    // active handle from the previous method and throw "scenario already active". Session-scoped
    // scenarios remain untouched here — they are intentionally class-long and are torn down by
    // session.close() in afterAll.
    @SuppressWarnings("unchecked")
    final List<ChaosActivationHandle> handles =
        (List<ChaosActivationHandle>)
            context.getStore(NAMESPACE).remove(METHOD_JVM_HANDLES_KEY, List.class);
    if (handles == null) {
      return;
    }
    for (final ChaosActivationHandle handle : handles) {
      try {
        handle.stop();
      } catch (final RuntimeException ignored) {
        // Best-effort teardown — a stop() failure must never mask a real test failure or
        // prevent sibling handles from being released.
      }
    }
  }

  @Override
  public void afterAll(final ExtensionContext context) {
    final ChaosSession session =
        context.getStore(NAMESPACE).remove(ChaosSession.class, ChaosSession.class);
    // try/finally ensures JVM-scoped handles are always stopped even if session.close() throws.
    try {
      if (session != null) {
        session.close();
      }
    } finally {
      // Stop every JVM-scoped handle we captured during the class; otherwise JVM-scoped
      // @ChaosScenario activations leak into every subsequent test in this JVM.
      @SuppressWarnings("unchecked")
      final List<ChaosActivationHandle> handles =
          (List<ChaosActivationHandle>)
              context.getStore(NAMESPACE).remove(JVM_HANDLES_KEY, List.class);
      if (handles != null) {
        for (final ChaosActivationHandle handle : handles) {
          try {
            handle.stop();
          } catch (final RuntimeException ignored) {
            // best-effort teardown — an exception here must not mask a real test failure
          }
        }
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
    ExtensionContext current = extensionContext;
    while (current != null) {
      final Object value = current.getStore(NAMESPACE).get(parameterType, parameterType);
      if (value != null) {
        return value;
      }
      current = current.getParent().orElse(null);
    }
    throw new ParameterResolutionException(
        "ChaosQuarkusExtension: no "
            + parameterType.getSimpleName()
            + " available — ensure the test class is annotated with @QuarkusChaosTest"
            + " and beforeAll() has run before parameter injection");
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
      final ChaosScenario[] annotations,
      final List<ChaosActivationHandle> jvmHandles) {
    if (annotations == null) {
      return;
    }
    for (final ChaosScenario annotation : annotations) {
      final com.macstab.chaos.api.ChaosScenario scenario = toScenario(annotation);
      if (scenario.scope() == com.macstab.chaos.api.ChaosScenario.ScenarioScope.JVM) {
        // Capture the handle so afterAll can stop it; without this, JVM-scope scenarios leak
        // across tests in the same JVM and the default scope ("JVM") silently pollutes the
        // entire test suite.
        jvmHandles.add(controlPlane.activate(scenario));
      } else {
        session.activate(scenario);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static List<ChaosActivationHandle> jvmHandles(final ExtensionContext context) {
    ExtensionContext current = context;
    while (current != null) {
      final List<ChaosActivationHandle> handles =
          (List<ChaosActivationHandle>) current.getStore(NAMESPACE).get(JVM_HANDLES_KEY);
      if (handles != null) {
        return handles;
      }
      current = current.getParent().orElse(null);
    }
    // Defensive fallback — beforeAll always installs the list. If somehow reached without
    // beforeAll (e.g. extension registered without @QuarkusChaosTest), create and store a
    // new list in the nearest writable context so afterAll can find and stop these handles.
    final List<ChaosActivationHandle> fallback = new ArrayList<>();
    context.getStore(NAMESPACE).put(JVM_HANDLES_KEY, fallback);
    return fallback;
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
      try {
        return ChaosEffect.delay(Duration.parse(durationText));
      } catch (final java.time.format.DateTimeParseException ex) {
        throw new IllegalArgumentException(
            "unsupported effect duration in '"
                + effect
                + "': expected ISO-8601 format, e.g. PT0.1S",
            ex);
      }
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
