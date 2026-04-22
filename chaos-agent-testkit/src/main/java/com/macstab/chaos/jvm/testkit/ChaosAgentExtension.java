package com.macstab.chaos.jvm.testkit;

import com.macstab.chaos.jvm.api.ChaosControlPlane;
import com.macstab.chaos.jvm.api.ChaosSession;
import com.macstab.chaos.jvm.bootstrap.ChaosPlatform;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * JUnit 5 extension that integrates the chaos agent into the JUnit lifecycle, providing per-test
 * chaos isolation through {@link ChaosSession} scoping.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * @ExtendWith(ChaosAgentExtension.class)
 * class MyServiceTest {
 *
 *     @Test
 *     void shouldHandleDelays(ChaosSession session) {
 *         session.activate(delayScenario);
 *         try (ChaosSession.ScopeBinding scope = session.bind()) {
 *             // chaos is active on this thread
 *             myService.call();
 *         }
 *     }
 * }
 * }</pre>
 *
 * <h2>Lifecycle</h2>
 *
 * <ul>
 *   <li><b>Before each test</b> ({@link #beforeEach}): ensures the chaos agent is installed
 *       (self-attaching if necessary via {@link ChaosPlatform#installLocally()}), then opens a
 *       fresh {@link ChaosSession} scoped to the test's display name. The {@link ChaosControlPlane}
 *       and {@link ChaosSession} are stored in the JUnit {@link ExtensionContext} store.
 *   <li><b>After each test</b> ({@link #afterEach}): closes the session, which stops all
 *       session-scoped scenarios and releases any session-bound threads. The session is removed
 *       from the store to prevent cross-test leakage.
 * </ul>
 *
 * <h2>Parameter injection</h2>
 *
 * <p>The extension implements {@link ParameterResolver} and can inject the following parameter
 * types into test methods and lifecycle methods:
 *
 * <ul>
 *   <li>{@link ChaosSession} — the session opened for the current test
 *   <li>{@link ChaosControlPlane} — the JVM-wide control plane, useful for activating JVM-scoped
 *       scenarios
 * </ul>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Each test gets its own {@link ChaosSession}. Parallel test execution is supported as long as
 * tests use session-scoped scenarios; JVM-scoped scenarios are shared across all threads and
 * parallel tests must be aware of this.
 */
public final class ChaosAgentExtension
    implements BeforeEachCallback, AfterEachCallback, ParameterResolver {
  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(ChaosAgentExtension.class);

  /**
   * Installs the chaos agent (if not already installed) and opens a new {@link ChaosSession} for
   * the current test, storing both the {@link ChaosControlPlane} and the session in the extension
   * context store.
   *
   * @param context the JUnit extension context for the test about to run
   */
  @Override
  public void beforeEach(final ExtensionContext context) {
    final TrackingChaosControlPlane tracker =
        new TrackingChaosControlPlane(ChaosPlatform.installLocally());
    final ChaosSession session = tracker.openSession(context.getDisplayName());
    context.getStore(NAMESPACE).put(ChaosControlPlane.class, tracker);
    context.getStore(NAMESPACE).put(ChaosSession.class, session);
  }

  /**
   * Closes the {@link ChaosSession} that was opened for this test, stopping all session-scoped
   * scenarios and unbinding any bound threads.
   *
   * <p>The session is removed from the store before closing to prevent it from being used after
   * this point. If {@link #beforeEach} did not complete (e.g., due to an earlier setup failure),
   * this method is a no-op.
   *
   * @param context the JUnit extension context for the test that just finished
   */
  @Override
  public void afterEach(final ExtensionContext context) {
    final ChaosSession session =
        context.getStore(NAMESPACE).remove(ChaosSession.class, ChaosSession.class);
    final ChaosControlPlane controlPlane =
        context.getStore(NAMESPACE).remove(ChaosControlPlane.class, ChaosControlPlane.class);
    // stopTracked() MUST run even if session.close() throws, otherwise any JVM-scoped handles
    // activated via controlPlane.activate(...) during the test survive into the next test —
    // TrackingChaosControlPlane documents this as the dominant source of test-suite flakiness.
    // Rare but the cost of getting wrong is cross-test contamination of JVM-wide instrumentation.
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

  /**
   * Returns {@code true} if the parameter type is {@link ChaosSession} or {@link
   * ChaosControlPlane}; {@code false} for all other types.
   *
   * @param parameterContext the context for the parameter to be resolved
   * @param extensionContext the JUnit extension context for the test
   * @return {@code true} if this extension can resolve the parameter
   */
  @Override
  public boolean supportsParameter(
      final ParameterContext parameterContext, final ExtensionContext extensionContext) {
    final Class<?> parameterType = parameterContext.getParameter().getType();
    return parameterType == ChaosSession.class || parameterType == ChaosControlPlane.class;
  }

  /**
   * Resolves the parameter value from the extension context store.
   *
   * <p>Returns the {@link ChaosSession} or {@link ChaosControlPlane} stored during {@link
   * #beforeEach}. The returned object is keyed and typed by the parameter's class, so the correct
   * instance is retrieved without a cast.
   *
   * @param parameterContext the context for the parameter to be resolved
   * @param extensionContext the JUnit extension context for the test
   * @return the resolved parameter value; never null if {@link #beforeEach} completed successfully
   */
  @Override
  public Object resolveParameter(
      final ParameterContext parameterContext, final ExtensionContext extensionContext) {
    final Class<?> parameterType = parameterContext.getParameter().getType();
    return extensionContext.getStore(NAMESPACE).get(parameterType, parameterType);
  }
}
