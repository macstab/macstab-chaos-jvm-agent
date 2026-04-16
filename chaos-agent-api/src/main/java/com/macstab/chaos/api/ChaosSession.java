package com.macstab.chaos.api;

import java.util.concurrent.Callable;

/**
 * A named session scope that restricts chaos effects to threads explicitly bound to it.
 *
 * <p>Sessions enable per-test isolation when multiple tests share a single JVM: each test opens its
 * own session, activates scenarios scoped to that session, and only threads bound to that session
 * observe chaos. Threads without a binding are unaffected.
 *
 * <p><b>Basic usage:</b>
 *
 * <pre>{@code
 * try (ChaosSession session = controlPlane.openSession("my-test")) {
 *     session.activate(scenario);
 *     try (ChaosSession.ScopeBinding binding = session.bind()) {
 *         // operations on this thread are intercepted by the session scenario
 *         myService.call();
 *     }
 *     // thread is unbound; no more chaos on this thread
 * }
 * // session is closed; all session scenarios are stopped
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
   * Returns the unique identifier of this session. Used as a filter key in session-scoped scenario
   * evaluation.
   */
  String id();

  /**
   * Returns the human-readable display name provided when the session was opened. Used in
   * diagnostics and log messages.
   */
  String displayName();

  /**
   * Activates a {@link ChaosScenario.ScenarioScope#SESSION SESSION}-scoped scenario within this
   * session. Only threads bound to this session via {@link #bind()} will be affected.
   *
   * @param scenario must have {@link ChaosScenario.ScenarioScope#SESSION} scope; JVM-scoped
   *     scenarios cannot be activated on a session
   * @return a handle for per-scenario lifecycle control
   * @throws ChaosActivationException if the scenario scope is not SESSION
   */
  ChaosActivationHandle activate(ChaosScenario scenario);

  /**
   * Activates all scenarios in the given {@link ChaosPlan} within this session. All scenarios in
   * the plan must be {@link ChaosScenario.ScenarioScope#SESSION SESSION}-scoped.
   *
   * @param plan all scenarios must be SESSION-scoped
   * @return a composite handle controlling all scenarios in the plan
   */
  ChaosActivationHandle activate(ChaosPlan plan);

  /**
   * Binds the calling thread to this session, making it eligible for session-scoped chaos.
   *
   * <p>The binding is stored in a thread-local and remains active until {@link ScopeBinding#close}
   * is called. Bindings are not re-entrant — nested bind() calls on the same thread replace the
   * previous binding.
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
   */
  Runnable wrap(Runnable runnable);

  /**
   * Wraps a {@link Callable} so that it executes with this session's scope active on whatever
   * thread runs it.
   *
   * @param callable the task to wrap; must not be null
   * @param <T> the return type of the callable
   * @return a new Callable that activates the session context before delegating to the original
   */
  <T> Callable<T> wrap(Callable<T> callable);

  /**
   * Stops all session-scoped scenarios and releases the session. After this call, threads
   * previously bound to this session will no longer observe session-scoped chaos.
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
