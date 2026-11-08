package com.macstab.chaos.jvm.spring.boot3.test;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import com.macstab.chaos.jvm.testkit.TrackingChaosControlPlane;
import com.macstab.chaos.jvm.testkit.WithChaosSupport;
import java.util.List;
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
import org.springframework.beans.factory.annotation.ParameterResolutionDelegate;
import org.springframework.test.context.support.TestConstructorUtils;

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
 * <p>The extension injects {@link ChaosSession} and {@link ChaosControlPlane} parameters on test
 * methods and lifecycle methods. A parameter Spring autowires is left to Spring: one of an
 * autowired test constructor ({@code @Autowired}, {@code @TestConstructor(autowireMode = ALL)} or
 * {@code spring.test.constructor.autowire.mode=all}) and one annotated {@code @Autowired},
 * {@code @Value} or {@code @Qualifier}. Spring injects the context's {@link ChaosControlPlane} bean
 * there; a {@link ChaosSession} is not a bean, so Spring reports it as missing.
 */
public final class ChaosAgentExtension
    implements BeforeAllCallback,
        AfterAllCallback,
        BeforeTestExecutionCallback,
        AfterTestExecutionCallback,
        ParameterResolver {
  static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(ChaosAgentExtension.class);

  /**
   * The control planes chaos test contexts expose as beans ({@link ChaosTestAutoConfiguration}).
   * Spring caches a context across test classes and one bean serves every class using it, so what
   * is activated through an injected bean is released when the last running chaos test class ends.
   */
  static final Set<TrackingChaosControlPlane> CONTEXT_PLANES = ConcurrentHashMap.newKeySet();

  private static final Object RUNNING_LOCK = new Object();

  /** Outermost chaos test classes running now (several in a parallel run); guarded by the lock. */
  private static int runningClasses;

  /** Default constructor invoked by JUnit when the extension is registered. */
  public ChaosAgentExtension() {}

  /**
   * Returns a tracking control plane for a test context's bean, released when its context closes or
   * when the last running chaos test class ends.
   *
   * @param delegate the JVM-wide control plane
   * @return the tracking control plane
   */
  static TrackingChaosControlPlane contextPlane(final ChaosControlPlane delegate) {
    final TrackingChaosControlPlane plane = new TrackingChaosControlPlane(delegate);
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
        (ChaosControlPlane) findInContextHierarchy(context, ChaosControlPlane.class);
    ChaosSession session = (ChaosSession) findInContextHierarchy(context, ChaosSession.class);
    if (controlPlane == null || session == null) {
      // The outermost class owns the session. It wraps the JVM-wide control plane in a tracking
      // decorator, so JVM-scoped scenarios activated through the injected parameter are released
      // in afterAll: session close() only stops session-scoped scenarios.
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
        (ChaosControlPlane) findInContextHierarchy(context, ChaosControlPlane.class);
    final ChaosSession session = (ChaosSession) findInContextHierarchy(context, ChaosSession.class);
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
    // Close the session first so session-scoped scenarios stop via their owning session's
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
    if (parameterType != ChaosSession.class && parameterType != ChaosControlPlane.class) {
      return false;
    }
    // The SpringExtension resolves these itself; claiming them too makes JUnit fail the test with
    // competing resolvers. The same checks as SpringExtension.supportsParameter.
    return !TestConstructorUtils.isAutowirableConstructor(
            parameterContext.getDeclaringExecutable(),
            name -> extensionContext.getConfigurationParameter(name).orElse(null))
        && !ParameterResolutionDelegate.isAutowirable(
            parameterContext.getParameter(), parameterContext.getIndex());
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

  private @Nullable Object findInContextHierarchy(
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
