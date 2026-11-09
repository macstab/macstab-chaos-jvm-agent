package com.macstab.chaos.jvm.micronaut;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import com.macstab.chaos.jvm.testkit.TrackingChaosControlPlane;
import com.macstab.chaos.jvm.testkit.WithChaosSupport;
import java.lang.annotation.Annotation;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 *       class. A {@code @Nested} class runs inside its outer class and shares its session.
 *   <li>{@code beforeTestExecution} / {@code afterTestExecution}: activate the method's {@link
 *       com.macstab.chaos.jvm.testkit.WithChaos} scenarios right before the test method runs and
 *       stop them right after it, so the test's {@code @BeforeEach} and {@code @AfterEach} methods
 *       run without them.
 *   <li>{@code afterAll}: close the session, stopping every session-scoped scenario activated
 *       during the class; a {@code @Nested} class only stops its own class-level scenarios.
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
 * avoid the collision this extension detects {@code @MicronautTest} on the test class, directly or
 * as a meta-annotation (walking parents so nested classes inherit the decision), and yields
 * ownership of the control-plane parameter to Micronaut when it resolves parameters ({@code
 * resolveParameters}, {@code true} by default). Without {@code @MicronautTest}, or with {@code
 * resolveParameters = false}, no competing resolver exists and this extension resolves the control
 * plane from its own store. The annotation is found by name, so micronaut-test need not be on the
 * class path.
 */
public final class ChaosMicronautExtension
    implements BeforeAllCallback,
        AfterAllCallback,
        BeforeTestExecutionCallback,
        AfterTestExecutionCallback,
        ParameterResolver {
  static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(ChaosMicronautExtension.class);

  /** The annotation Micronaut's JUnit 5 extension is registered with; found by name. */
  private static final String MICRONAUT_TEST =
      "io.micronaut.test.extensions.junit5.annotation.MicronautTest";

  /**
   * The control planes test application contexts expose as beans ({@link ChaosTestFactory}); a
   * context may serve several test classes, so what is activated through an injected bean is
   * released when the last running chaos test class ends.
   */
  static final Set<TrackingChaosControlPlane> CONTEXT_PLANES = ConcurrentHashMap.newKeySet();

  private static final Object RUNNING_LOCK = new Object();

  /** Outermost chaos test classes running now (several in a parallel run); guarded by the lock. */
  private static int runningClasses;

  /** Default constructor invoked by JUnit when the extension is registered. */
  public ChaosMicronautExtension() {}

  /**
   * Returns a tracking control plane for a test context's bean, released when its context closes or
   * when the last running chaos test class ends. Only what it activated is released: the
   * application's event listeners and sessions stay with the application.
   *
   * @param delegate the JVM-wide control plane
   * @return the tracking control plane
   */
  static TrackingChaosControlPlane contextPlane(final ChaosControlPlane delegate) {
    final TrackingChaosControlPlane plane = new TrackingChaosControlPlane(delegate, false);
    CONTEXT_PLANES.add(plane);
    return plane;
  }

  /**
   * Releases a context's control plane when its context closes: what it activated stops, and it is
   * no longer released with the others.
   *
   * @param plane the plane {@link #contextPlane} returned for the closing context
   */
  static void releaseContextPlane(final TrackingChaosControlPlane plane) {
    CONTEXT_PLANES.remove(plane);
    plane.stopTracked();
  }

  @Override
  public void beforeAll(final ExtensionContext context) {
    final ExtensionContext.Store store = context.getStore(NAMESPACE);
    ChaosControlPlane controlPlane =
        (ChaosControlPlane) findInStoreHierarchy(context, ChaosControlPlane.class);
    ChaosSession session = (ChaosSession) findInStoreHierarchy(context, ChaosSession.class);
    if (controlPlane == null || session == null) {
      // The outermost class owns the session; a @Nested class runs inside it and shares it. The
      // JVM-wide control plane is wrapped in a tracking decorator so JVM-scoped scenarios
      // activated through it are released in afterAll: session close() only stops session-scoped
      // scenarios, and the JVM-wide control plane lives for the JVM lifetime.
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
    // Class-level @WithChaos ends with its own class: a nested class's before its outer class's.
    store.put(
        WithChaosSupport.class,
        WithChaosSupport.activate(context.getRequiredTestClass(), controlPlane, session));
  }

  @Override
  public void beforeTestExecution(final ExtensionContext context) {
    final ChaosControlPlane controlPlane =
        (ChaosControlPlane) findInStoreHierarchy(context, ChaosControlPlane.class);
    final ChaosSession session = (ChaosSession) findInStoreHierarchy(context, ChaosSession.class);
    if (controlPlane == null || session == null) {
      return;
    }
    context
        .getStore(NAMESPACE)
        .put(
            WithChaosSupport.class,
            WithChaosSupport.activate(context.getRequiredTestMethod(), controlPlane, session));
  }

  @Override
  @SuppressWarnings("unchecked")
  public void afterTestExecution(final ExtensionContext context) {
    // Method-level @WithChaos scenarios end with the method, not with the class.
    WithChaosSupport.close(
        (List<ChaosActivationHandle>)
            context.getStore(NAMESPACE).remove(WithChaosSupport.class, List.class));
  }

  @Override
  @SuppressWarnings("unchecked")
  public void afterAll(final ExtensionContext context) {
    final ExtensionContext.Store store = context.getStore(NAMESPACE);
    final List<ChaosActivationHandle> classHandles =
        (List<ChaosActivationHandle>) store.remove(WithChaosSupport.class, List.class);
    // Present only in the class that opened them: a nested class leaves them to its outer class.
    final ChaosSession session = store.remove(ChaosSession.class, ChaosSession.class);
    final ChaosControlPlane controlPlane =
        store.remove(ChaosControlPlane.class, ChaosControlPlane.class);
    // Close the session first so session-scoped scenarios are stopped via their owning session's
    // lifecycle, then drain any JVM-scoped handles the test class activated via the tracker.
    // try/finally ensures each step runs even if an earlier one throws.
    try {
      WithChaosSupport.close(classHandles);
    } finally {
      try {
        if (session != null) {
          session.close();
        }
      } finally {
        if (controlPlane instanceof TrackingChaosControlPlane tracker) {
          tracker.stopTracked();
        }
        if (session != null) {
          releaseContextPlanesIfLast();
        }
      }
    }
  }

  /** Releases what the context beans activated once no chaos test class is running any more. */
  private static void releaseContextPlanesIfLast() {
    synchronized (RUNNING_LOCK) {
      if (--runningClasses == 0) {
        CONTEXT_PLANES.forEach(TrackingChaosControlPlane::stopTracked);
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

  /** Whether a {@code @MicronautTest} that resolves parameters is on the class or its outers. */
  private static boolean isMicronautTest(final ExtensionContext context) {
    ExtensionContext current = context;
    while (current != null) {
      final Optional<Class<?>> testClass = current.getTestClass();
      if (testClass.isPresent()) {
        final Annotation micronautTest =
            findByName(testClass.get().getAnnotations(), new HashSet<>());
        if (micronautTest != null) {
          return resolvesParameters(micronautTest);
        }
      }
      current = current.getParent().orElse(null);
    }
    return false;
  }

  /**
   * The {@code @MicronautTest} among {@code annotations} or their meta-annotations (it is one on
   * {@code @MicronautChaosTest}); {@code visited} stops the walk at annotations already seen.
   */
  private static @Nullable Annotation findByName(
      final Annotation[] annotations, final Set<Class<?>> visited) {
    for (final Annotation annotation : annotations) {
      final Class<? extends Annotation> type = annotation.annotationType();
      if (type.getName().equals(MICRONAUT_TEST)) {
        return annotation;
      }
      if (visited.add(type)) {
        final Annotation meta = findByName(type.getAnnotations(), visited);
        if (meta != null) {
          return meta;
        }
      }
    }
    return null;
  }

  /** {@code resolveParameters} of a {@code @MicronautTest}; {@code true} when it cannot be read. */
  private static boolean resolvesParameters(final Annotation micronautTest) {
    try {
      return !Boolean.FALSE.equals(
          micronautTest.annotationType().getMethod("resolveParameters").invoke(micronautTest));
    } catch (final ReflectiveOperationException | RuntimeException unreadable) {
      return true;
    }
  }

  @Override
  public Object resolveParameter(
      final ParameterContext parameterContext, final ExtensionContext extensionContext) {
    final Class<?> parameterType = parameterContext.getParameter().getType();
    final Object stored = findInStoreHierarchy(extensionContext, parameterType);
    if (stored != null) {
      return stored;
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

  private static @Nullable Object findInStoreHierarchy(
      final ExtensionContext startContext, final Class<?> type) {
    ExtensionContext current = startContext;
    while (current != null) {
      final Object value = current.getStore(NAMESPACE).get(type, type);
      if (value != null) {
        return value;
      }
      current = current.getParent().orElse(null);
    }
    return null;
  }
}
