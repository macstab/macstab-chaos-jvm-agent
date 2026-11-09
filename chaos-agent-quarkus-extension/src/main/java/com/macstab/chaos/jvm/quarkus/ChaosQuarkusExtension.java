package com.macstab.chaos.jvm.quarkus;

import com.macstab.chaos.jvm.api.ActivationPolicy;
import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosSelector;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.api.OperationType;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import com.macstab.chaos.jvm.testkit.TrackingChaosControlPlane;
import com.macstab.chaos.jvm.testkit.WithChaosSupport;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
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
 *       class, and activate every {@link ChaosScenario} annotation present on the class. A
 *       {@code @Nested} class runs inside its outer class and shares its session.
 *   <li>{@code beforeTestExecution}: activate every {@link ChaosScenario} and {@link
 *       com.macstab.chaos.jvm.testkit.WithChaos} annotation present on the test method, right
 *       before the test method runs (after its {@code @BeforeEach} methods). The activations of
 *       either scope are tracked per method so {@code afterTestExecution} can tear them down —
 *       otherwise the second test method that declared the same scenario id would hit an {@code
 *       "already active"} activation conflict.
 *   <li>{@code afterTestExecution}: stop the scenarios activated by the current method's
 *       annotations, right after the test method ran (before its {@code @AfterEach} methods);
 *       class-level scenarios remain active for the whole class.
 *   <li>{@code afterAll}: stop the scenarios activated by class-level annotations and close the
 *       session (which stops every session-scoped scenario activated during the class).
 * </ul>
 *
 * <h2>Parameter injection</h2>
 *
 * <p>The extension injects {@link ChaosSession} and {@link ChaosControlPlane} parameters on test
 * methods and lifecycle methods. Constructor parameters are left to Quarkus' own extension, which
 * resolves every one of them (a {@link ChaosControlPlane} there is the CDI bean).
 */
// Still honours the deprecated @ChaosScenario until it is removed.
@SuppressWarnings("removal")
public final class ChaosQuarkusExtension
    implements BeforeAllCallback,
        BeforeTestExecutionCallback,
        AfterTestExecutionCallback,
        AfterAllCallback,
        ParameterResolver {
  static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(ChaosQuarkusExtension.class);

  /** Store key for the per-class list of annotation handles that must be stopped in afterAll. */
  private static final String CLASS_HANDLES_KEY = "classHandles";

  /** Prefix that identifies a delay effect in the {@link ChaosScenario#effect()} string. */
  private static final String DELAY_EFFECT_PREFIX = "delay:";

  /**
   * Store key, scoped to the per-test-method {@link ExtensionContext.Store}, for the handles
   * activated from annotations on the test method itself. These must be stopped in
   * afterTestExecution — otherwise a {@code @ChaosScenario(id="x")} on two different test methods
   * causes the second method to fail with an activation conflict because the first method's handle
   * is still registered.
   */
  private static final String METHOD_HANDLES_KEY = "methodHandles";

  /**
   * The control planes the application's {@code ChaosControlPlane} beans hand out in a test ({@link
   * ChaosArcProducer}); one application serves several test classes, so what is activated through
   * an injected bean is released when the last running chaos test class ends.
   */
  static final Set<TrackingChaosControlPlane> CONTEXT_PLANES = ConcurrentHashMap.newKeySet();

  private static final Object RUNNING_LOCK = new Object();

  /** Outermost chaos test classes running now; guarded by the lock. */
  private static int runningClasses;

  /** Default constructor invoked by JUnit when the extension is registered. */
  public ChaosQuarkusExtension() {}

  /**
   * Returns a tracking control plane for the application's bean, released when the application
   * stops or when the last running chaos test class ends. Only what it activated is released: the
   * application's event listeners and sessions stay. Called by {@link ChaosArcProducer} in a test,
   * through the system class loader that loaded this extension; not for application code.
   *
   * @param delegate the JVM-wide control plane
   * @return the tracking control plane
   */
  public static ChaosControlPlane contextPlane(final ChaosControlPlane delegate) {
    final TrackingChaosControlPlane plane = new TrackingChaosControlPlane(delegate, false);
    CONTEXT_PLANES.add(plane);
    return plane;
  }

  /**
   * Releases a plane {@link #contextPlane} returned when its application stops: what it activated
   * stops, and it is no longer released with the others. Called by {@link ChaosArcProducer}; not
   * for application code.
   *
   * @param plane the plane being released
   */
  public static void releaseContextPlane(final ChaosControlPlane plane) {
    if (plane instanceof TrackingChaosControlPlane tracker && CONTEXT_PLANES.remove(tracker)) {
      tracker.stopTracked();
    }
  }

  @Override
  public void beforeAll(final ExtensionContext context) {
    final ExtensionContext.Store store = context.getStore(NAMESPACE);
    ChaosControlPlane controlPlane = lookupControlPlane(context);
    ChaosSession session = lookupSession(context);
    if (controlPlane == null || session == null) {
      // The outermost class owns the session; a @Nested class runs inside it and shares it. The
      // tracker records every scenario activated through the injected control plane, so afterAll
      // releases JVM-scoped ones however the test activated them.
      final TrackingChaosControlPlane tracker =
          new TrackingChaosControlPlane(ChaosPlatform.installLocally());
      controlPlane = tracker;
      session = tracker.openSession(context.getDisplayName());
      store.put(ChaosControlPlane.class, tracker);
      store.put(ChaosSession.class, session);
      synchronized (RUNNING_LOCK) {
        runningClasses++;
      }
    }
    // Class-scoped list of handles for the scenarios the class's annotations activate. JVM-scoped
    // ones outlive any session, so afterAll stops them to keep them from leaking into subsequent
    // tests; a nested class's session-scoped ones end with the nested class.
    final List<ChaosActivationHandle> classHandles = new ArrayList<>();
    store.put(CLASS_HANDLES_KEY, classHandles);
    final Class<?> testClass = context.getTestClass().orElse(null);
    if (testClass != null) {
      activateAnnotations(
          controlPlane, session, testClass.getAnnotationsByType(ChaosScenario.class), classHandles);
      // @WithChaos (full JSON model): both scopes are released in afterAll.
      classHandles.addAll(WithChaosSupport.activate(testClass, controlPlane, session));
    }
  }

  @Override
  public void beforeTestExecution(final ExtensionContext context) {
    final Method testMethod = context.getTestMethod().orElse(null);
    if (testMethod == null) {
      return;
    }
    final ChaosSession session = lookupSession(context);
    final ChaosControlPlane controlPlane = lookupControlPlane(context);
    if (session == null || controlPlane == null) {
      return;
    }
    // Track the activations from *this method's* annotations in a method-scoped list so
    // afterTestExecution can stop exactly them. context.getStore(NAMESPACE) here is
    // scoped to the test-method context, so values written here are isolated per method and do
    // not bleed into sibling tests. Class-level annotations keep using the class-scoped list
    // populated by beforeAll (see CLASS_HANDLES_KEY above).
    final List<ChaosActivationHandle> methodHandles = new ArrayList<>();
    context.getStore(NAMESPACE).put(METHOD_HANDLES_KEY, methodHandles);
    activateAnnotations(
        controlPlane, session, testMethod.getAnnotationsByType(ChaosScenario.class), methodHandles);
    // Method-level @WithChaos scenarios of either scope end with the method.
    methodHandles.addAll(WithChaosSupport.activate(testMethod, controlPlane, session));
  }

  @Override
  public void afterTestExecution(final ExtensionContext context) {
    // Stop the scenarios activated by annotations on the current test method. Without this, a
    // second test method declaring the same scenario id would collide with the still-active
    // handle from the previous method and fail with "scenario already active".
    @SuppressWarnings("unchecked")
    final List<ChaosActivationHandle> methodHandles =
        (List<ChaosActivationHandle>)
            context.getStore(NAMESPACE).remove(METHOD_HANDLES_KEY, List.class);
    stopHandlesSafely(methodHandles);
  }

  @Override
  public void afterAll(final ExtensionContext context) {
    final ExtensionContext.Store store = context.getStore(NAMESPACE);
    final ChaosSession session = store.remove(ChaosSession.class, ChaosSession.class);
    final ChaosControlPlane controlPlane =
        store.remove(ChaosControlPlane.class, ChaosControlPlane.class);
    // try/finally ensures the class's handles are always stopped even if session.close() throws.
    try {
      if (session != null) {
        session.close();
      }
    } finally {
      // Stop every handle the class's annotations activated; otherwise JVM-scoped
      // @ChaosScenario activations leak into every subsequent test in this JVM.
      @SuppressWarnings("unchecked")
      final List<ChaosActivationHandle> classHandles =
          (List<ChaosActivationHandle>) store.remove(CLASS_HANDLES_KEY, List.class);
      stopHandlesSafely(classHandles);
      if (controlPlane instanceof TrackingChaosControlPlane tracker) {
        tracker.stopTracked();
      }
      // Present only in the class that opened it: a nested class leaves the count to its outer.
      if (session != null) {
        releaseContextPlanesIfLast();
      }
    }
  }

  /** Releases what the application's beans activated once no chaos test class is running. */
  private static void releaseContextPlanesIfLast() {
    synchronized (RUNNING_LOCK) {
      if (--runningClasses == 0) {
        CONTEXT_PLANES.forEach(TrackingChaosControlPlane::stopTracked);
      }
    }
  }

  /**
   * Stops each handle in the list in a best-effort manner. A {@link RuntimeException} from any
   * individual {@link ChaosActivationHandle#close()} is logged as a {@code WARNING} and not thrown,
   * so that it cannot mask a real test failure and so that sibling handles are always released.
   */
  private static void stopHandlesSafely(final List<ChaosActivationHandle> handles) {
    if (handles == null) {
      return;
    }
    for (final ChaosActivationHandle handle : handles) {
      try {
        // close() also unregisters the scenario, so the next test can reuse its id.
        handle.close();
      } catch (final RuntimeException failure) {
        // Best-effort teardown: an exception here must not mask a real test failure.
        Logger.getLogger(ChaosQuarkusExtension.class.getName())
            .log(
                Level.WARNING,
                failure,
                () -> "[chaos-agent] failed to stop scenario " + handle.id() + " after the test");
      }
    }
  }

  @Override
  public boolean supportsParameter(
      final ParameterContext parameterContext, final ExtensionContext extensionContext) {
    final Class<?> parameterType = parameterContext.getParameter().getType();
    // QuarkusTestExtension claims every constructor parameter; claiming one too makes JUnit fail
    // the test with competing resolvers.
    return (parameterType == ChaosSession.class || parameterType == ChaosControlPlane.class)
        && !(parameterContext.getDeclaringExecutable() instanceof Constructor);
  }

  @Override
  public Object resolveParameter(
      final ParameterContext parameterContext, final ExtensionContext extensionContext) {
    final Class<?> parameterType = parameterContext.getParameter().getType();
    final Object value = lookupStoreValue(extensionContext, parameterType);
    if (value != null) {
      return value;
    }
    throw new ParameterResolutionException(
        "ChaosQuarkusExtension: no "
            + parameterType.getSimpleName()
            + " available — ensure the test class is annotated with @QuarkusChaosTest"
            + " and beforeAll() has run before parameter injection");
  }

  private static @Nullable ChaosSession lookupSession(final ExtensionContext context) {
    return lookupStoreValue(context, ChaosSession.class);
  }

  private static @Nullable ChaosControlPlane lookupControlPlane(final ExtensionContext context) {
    return lookupStoreValue(context, ChaosControlPlane.class);
  }

  /**
   * Walks the {@link ExtensionContext} parent chain until it finds a store entry for {@code type},
   * or returns {@code null} if none exists.
   */
  private static <T> @Nullable T lookupStoreValue(
      final ExtensionContext context, final Class<T> type) {
    ExtensionContext current = context;
    while (current != null) {
      final T value = current.getStore(NAMESPACE).get(type, type);
      if (value != null) {
        return value;
      }
      current = current.getParent().orElse(null);
    }
    return null;
  }

  private static void activateAnnotations(
      final ChaosControlPlane controlPlane,
      final ChaosSession session,
      final ChaosScenario[] annotations,
      final List<ChaosActivationHandle> handles) {
    if (annotations == null) {
      return;
    }
    for (final ChaosScenario annotation : annotations) {
      final com.macstab.chaos.jvm.api.ChaosScenario scenario = toScenario(annotation);
      // Capture the handle of either scope so the annotated element's end stops it: a JVM-scoped
      // one would leak across tests in the same JVM, and a session-scoped one declared on a
      // method would outlive the method in the class-long session.
      handles.add(
          scenario.scope() == com.macstab.chaos.jvm.api.ChaosScenario.ScenarioScope.JVM
              ? controlPlane.activate(scenario)
              : session.activate(scenario));
    }
  }

  static com.macstab.chaos.jvm.api.ChaosScenario toScenario(final ChaosScenario annotation) {
    final com.macstab.chaos.jvm.api.ChaosScenario.ScenarioScope scope =
        com.macstab.chaos.jvm.api.ChaosScenario.ScenarioScope.valueOf(
            annotation.scope().toUpperCase(Locale.ROOT));
    final ChaosEffect effect = parseEffect(annotation.effect());
    final ChaosSelector selector = parseSelector(annotation.selector(), effect);
    if (selector instanceof ChaosSelector.JvmRuntimeSelector
        && !(effect instanceof ChaosEffect.ClockSkewEffect)) {
      // Clock reads only take a clock skew; a delay or suppress on them would never apply.
      throw new IllegalArgumentException(
          "selector jvmRuntime only supports effect=freeze, got: " + annotation.effect());
    }
    if (effect instanceof ChaosEffect.ClockSkewEffect
        && !(selector instanceof ChaosSelector.JvmRuntimeSelector)) {
      throw new IllegalArgumentException(
          "effect=freeze requires selector=jvmRuntime, got: " + annotation.selector());
    }
    return com.macstab.chaos.jvm.api.ChaosScenario.builder(annotation.id())
        .scope(scope)
        .selector(selector)
        .effect(effect)
        .activationPolicy(ActivationPolicy.always())
        .build();
  }

  private static ChaosSelector parseSelector(final String selector, final ChaosEffect effect) {
    if (selector == null || selector.isBlank()) {
      throw new IllegalArgumentException("selector must be non-blank");
    }
    final String normalized = selector.trim();
    final boolean suppress = effect instanceof ChaosEffect.SuppressEffect;
    if (suppress && ("httpClient".equals(normalized) || "jdbc".equals(normalized))) {
      // An HTTP request or a JDBC statement cannot be skipped: there is no result to return.
      throw new IllegalArgumentException(
          "effect=suppress is not supported for " + normalized + "; use delay");
    }
    return switch (normalized) {
      // A pool worker's task run cannot be skipped; suppress drops the submission only.
      case "executor" ->
          ChaosSelector.executor(
              suppress ? EnumSet.of(OperationType.EXECUTOR_SUBMIT) : defaultExecutorOperations());
      // The clock reads that are actually instrumented: SYSTEM_CLOCK_MILLIS
      // (System.currentTimeMillis) is a JIT intrinsic that is never woven, so effect="freeze" on
      // it would do nothing.
      case "jvmRuntime" ->
          ChaosSelector.jvmRuntime(
              EnumSet.of(
                  OperationType.INSTANT_NOW,
                  OperationType.CLOCK_READ,
                  OperationType.LOCAL_DATE_TIME_NOW,
                  OperationType.ZONED_DATE_TIME_NOW,
                  OperationType.DATE_NEW));
      case "httpClient" ->
          ChaosSelector.httpClient(
              EnumSet.of(OperationType.HTTP_CLIENT_SEND, OperationType.HTTP_CLIENT_SEND_ASYNC));
      case "jdbc" -> ChaosSelector.jdbc();
      default ->
          throw new IllegalArgumentException(
              "unsupported selector identifier: "
                  + selector
                  + "; expected one of executor, jvmRuntime, httpClient, jdbc");
    };
  }

  private static ChaosEffect parseEffect(final String effect) {
    if (effect == null || effect.isBlank()) {
      throw new IllegalArgumentException("effect must be non-blank");
    }
    final String normalized = effect.trim();
    if (normalized.startsWith(DELAY_EFFECT_PREFIX)) {
      return parseDelayEffect(effect, normalized);
    }
    return switch (normalized) {
      case "suppress" -> ChaosEffect.suppress();
      case "freeze" -> ChaosEffect.skewClock(Duration.ZERO, ChaosEffect.ClockSkewMode.FREEZE);
      default ->
          throw new IllegalArgumentException(
              "unsupported effect identifier: "
                  + effect
                  + "; expected delay:<duration>, suppress, or freeze");
    };
  }

  private static ChaosEffect parseDelayEffect(final String rawEffect, final String normalized) {
    final String durationText = normalized.substring(DELAY_EFFECT_PREFIX.length());
    try {
      return ChaosEffect.delay(Duration.parse(durationText));
    } catch (final java.time.format.DateTimeParseException ex) {
      throw new IllegalArgumentException(
          "unsupported effect duration in '"
              + rawEffect
              + "': expected ISO-8601 format, e.g. PT0.1S",
          ex);
    }
  }

  // Defensive, package-private helper so tests can verify the operation set used for executor
  // scenarios without depending on internal constants.
  static Set<OperationType> defaultExecutorOperations() {
    return EnumSet.of(OperationType.EXECUTOR_SUBMIT, OperationType.EXECUTOR_WORKER_RUN);
  }
}
