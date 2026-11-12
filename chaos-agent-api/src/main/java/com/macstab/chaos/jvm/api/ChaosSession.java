package com.macstab.chaos.jvm.api;

import java.util.concurrent.Callable;

/**
 * A named session scope that restricts chaos effects to the threads bound to it.
 *
 * <p>Sessions enable per-test isolation when multiple tests share a single JVM: each test opens its
 * own session, activates scenarios scoped to that session, and only threads bound to that session
 * observe chaos. Threads without a binding are unaffected.
 *
 * <p>The thread that opens a session ({@link ChaosControlPlane#openSession(String)}) is bound to it
 * from then until {@link #close()}; other threads join it with {@link #bind()}, and work handed off
 * from a bound thread carries it (executor tasks, fork-join tasks, started threads).
 *
 * <p><b>Basic usage:</b>
 *
 * <pre>{@code
 * try (ChaosSession session = controlPlane.openSession("my-test")) {
 *     session.activate(scenario);
 *     // the opening thread is bound: operations on it are intercepted by the session scenario
 *     myService.call();
 * }
 * // session is closed: its scenarios are stopped and the opening thread is unbound
 * }</pre>
 *
 * <p>A thread the session did not hand work to (a server's request thread, a callback thread) joins
 * it for a block:
 *
 * <pre>{@code
 * try (ChaosSession.ScopeBinding binding = session.bind()) {
 *     myService.call(); // intercepted by the session scenario
 * }
 * // this thread is unbound again
 * }</pre>
 *
 * <p><b>Cross-thread propagation:</b> use {@link #wrap(Runnable)} or {@link #wrap(Callable)} to
 * propagate the session context into executor threads:
 *
 * <pre>{@code
 * executor.submit(session.wrap(() -> {
 *     // session context is active on this executor thread during the task
 *     myService.call();
 * }));
 * }</pre>
 *
 * <p>Sessions are created via {@link ChaosControlPlane#openSession(String)}.
 */
public interface ChaosSession extends AutoCloseable {

  /**
   * The HTTP header that carries a session id between services. Requests the JDK {@code HttpClient}
   * or {@code HttpURLConnection} sends from a thread bound to a session carry it automatically; a
   * server binds the request to the session with {@link ChaosControlPlane#bindSession(String)} (the
   * Spring Boot starters register a filter for that).
   */
  String HEADER = "X-Chaos-Session";

  /**
   * Returns the unique identifier of this session. Used as a filter key in session-scoped scenario
   * evaluation.
   *
   * @return the unique session ID
   */
  String id();

  /**
   * Returns the human-readable display name provided when the session was opened. Used in
   * diagnostics and log messages.
   *
   * @return the session display name
   */
  String displayName();

  /**
   * Activates a {@link ChaosScenario.ScenarioScope#SESSION SESSION}-scoped scenario within this
   * session. Only threads bound to this session are affected: the thread that opened it, until
   * {@link #close()}, and threads bound with {@link #bind()}.
   *
   * @param scenario must have {@link ChaosScenario.ScenarioScope#SESSION} scope; JVM-scoped
   *     scenarios cannot be activated on a session
   * @return a handle for per-scenario lifecycle control
   * @throws ChaosActivationException if the scenario scope is not SESSION
   * @throws ChaosValidationException if the scenario fails validation
   * @throws ChaosUnsupportedFeatureException if the scenario requires more than the runtime
   *     provides
   * @throws ChaosActivationConflictException if a scenario with the same ID is still registered or
   *     active in this session
   * @throws ChaosInstrumentationException if the instrumentation the scenario needs cannot be woven
   * @throws IllegalStateException if the session or its control plane is closed
   */
  ChaosActivationHandle activate(ChaosScenario scenario);

  /**
   * Activates all scenarios in the given {@link ChaosPlan} within this session. All scenarios in
   * the plan must be {@link ChaosScenario.ScenarioScope#SESSION SESSION}-scoped.
   *
   * @param plan all scenarios must be SESSION-scoped
   * @return a composite handle controlling all scenarios in the plan
   * @throws ChaosActivationException if a scenario in the plan is not SESSION-scoped
   * @throws ChaosValidationException if a scenario fails validation
   * @throws ChaosUnsupportedFeatureException if a scenario requires more than the runtime provides
   * @throws ChaosActivationConflictException if a scenario's ID is still registered or active in
   *     this session
   * @throws ChaosInstrumentationException if the instrumentation the plan needs cannot be woven
   * @throws IllegalStateException if the session or its control plane is closed
   */
  ChaosActivationHandle activate(ChaosPlan plan);

  /**
   * Binds the calling thread to this session, making it eligible for session-scoped chaos. The
   * thread that opened the session is bound already; use this on other threads.
   *
   * <p>The binding is stored in a thread-local and remains active until {@link ScopeBinding#close}
   * is called. Nested bind() calls on the same thread stack: the innermost binding applies until it
   * is closed, then the one beneath it applies again.
   *
   * <p>Always use in try-with-resources to guarantee the thread is unbound:
   *
   * <pre>{@code
   * try (ChaosSession.ScopeBinding binding = session.bind()) {
   *     // this thread is in scope
   * }
   * }</pre>
   *
   * @return a {@link ScopeBinding} that removes the binding when closed
   */
  ScopeBinding bind();

  /**
   * Wraps a {@link Runnable} so that it executes with this session's scope active on whatever
   * thread runs it.
   *
   * <p>Use this when submitting work to an executor pool: the wrapped task carries the session
   * context into the pool thread, enabling session-scoped chaos on executor operations.
   *
   * @param runnable the task to wrap; must not be null
   * @return a new Runnable that activates the session context before delegating to the original
   * @throws NullPointerException if {@code runnable} is {@code null}
   */
  Runnable wrap(Runnable runnable);

  /**
   * Wraps a {@link Callable} so that it executes with this session's scope active on whatever
   * thread runs it.
   *
   * @param callable the task to wrap; must not be null
   * @param <T> the return type of the callable
   * @return a new Callable that activates the session context before delegating to the original
   * @throws NullPointerException if {@code callable} is {@code null}
   */
  <T> Callable<T> wrap(Callable<T> callable);

  /**
   * Stops all session-scoped scenarios and releases the session, ending the binding of the thread
   * that opened it. After this call, threads previously bound to this session will no longer
   * observe session-scoped chaos.
   */
  @Override
  void close();

  /**
   * A handle that represents a thread's active binding to a {@link ChaosSession}.
   *
   * <p>Obtained from {@link ChaosSession#bind()}. Closing the binding removes the session context
   * from the current thread, making it invisible to session-scoped selector evaluation.
   */
  interface ScopeBinding extends AutoCloseable {

    /**
     * Removes the calling thread's binding to the session. After this call, the thread is invisible
     * to all session-scoped scenarios.
     */
    @Override
    void close();
  }
}
