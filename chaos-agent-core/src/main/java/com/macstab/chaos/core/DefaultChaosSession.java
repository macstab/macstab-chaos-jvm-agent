package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosActivationHandle;
import com.macstab.chaos.api.ChaosPlan;
import com.macstab.chaos.api.ChaosScenario;
import com.macstab.chaos.api.ChaosSession;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default package-private implementation of {@link com.macstab.chaos.api.ChaosSession}.
 *
 * <p>A session represents a logical grouping of chaos activations that share the same session ID
 * and are bound to the current thread's scope context for the session's lifetime.
 *
 * <h2>Session binding</h2>
 *
 * <p>The session ID is pushed onto the calling thread's {@link ScopeContext} stack in the
 * constructor (via {@link ScopeContext#bind(String)}). The resulting {@link AutoCloseable} root
 * binding is closed when the session is closed, removing the session ID from the stack.
 *
 * <h2>Thread-boundary propagation</h2>
 *
 * <p>To propagate the session ID to worker threads, wrap tasks with {@link
 * ScopeContext#wrap(String, Runnable)} or {@link ScopeContext#wrap(String,
 * java.util.concurrent.Callable)} before submitting them to an executor.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>{@link #close()} destroys all activation handles registered with this session and then closes
 * the root scope binding. After {@code close()}, no further activations should be performed on this
 * session.
 *
 * <h2>Thread safety</h2>
 *
 * <p>The handles list uses {@link java.util.concurrent.CopyOnWriteArrayList}. {@link #close()} is
 * safe to call from any thread.
 */
final class DefaultChaosSession implements ChaosSession {
  private final String id = UUID.randomUUID().toString();
  private final String displayName;
  private final ScopeContext scopeContext;
  private final ChaosRuntime runtime;
  private final List<DefaultChaosActivationHandle> handles = new CopyOnWriteArrayList<>();
  private final ScopeBinding rootBinding;

  /**
   * Creates a new session, assigns it a random UUID, and pushes the session ID onto the calling
   * thread's scope context stack.
   *
   * @param displayName a human-readable label for this session, used in diagnostics
   * @param scopeContext the scope context used for thread-local session ID propagation
   * @param runtime the chaos runtime used to activate scenarios within this session
   */
  DefaultChaosSession(
      final String displayName, final ScopeContext scopeContext, final ChaosRuntime runtime) {
    this.displayName = displayName;
    this.scopeContext = scopeContext;
    this.runtime = runtime;
    this.rootBinding = scopeContext.bind(id);
  }

  /**
   * Returns the unique identifier for this session.
   *
   * @return a randomly generated UUID string assigned at construction time
   */
  @Override
  public String id() {
    return id;
  }

  /**
   * Returns the human-readable display name for this session.
   *
   * @return the display name supplied at construction time
   */
  @Override
  public String displayName() {
    return displayName;
  }

  /**
   * Activates the given scenario within this session.
   *
   * <p>Delegates to {@link ChaosRuntime#activateInSession} and registers the resulting handle so
   * that it is automatically destroyed when this session is {@linkplain #close() closed}.
   *
   * @param scenario the scenario to activate
   * @return a handle for controlling the activated scenario
   */
  @Override
  public ChaosActivationHandle activate(final ChaosScenario scenario) {
    final DefaultChaosActivationHandle handle = runtime.activateInSession(this, scenario);
    handles.add(handle);
    return handle;
  }

  /**
   * Activates all scenarios in the given plan within this session and returns a composite handle.
   *
   * <p>Each scenario in {@link ChaosPlan#scenarios()} is activated individually via {@link
   * #activate(ChaosScenario)}, and all resulting handles are aggregated into a {@link
   * CompositeActivationHandle}.
   *
   * @param plan the chaos plan whose scenarios should all be activated
   * @return a composite handle that delegates operations to every activated scenario
   */
  @Override
  public ChaosActivationHandle activate(final ChaosPlan plan) {
    final List<ChaosActivationHandle> children =
        plan.scenarios().stream().map(this::activate).toList();
    return new CompositeActivationHandle("session-plan:" + plan.metadata().name(), children);
  }

  /**
   * Creates an additional scope binding for the current thread, pushing this session's ID onto the
   * scope context stack.
   *
   * <p>Useful when a single thread needs to re-enter the session's scope after it has been
   * temporarily popped. The caller is responsible for closing the returned {@link ScopeBinding}.
   *
   * @return a new {@link ScopeBinding} whose {@link ScopeBinding#close()} removes this push
   */
  @Override
  public ScopeBinding bind() {
    return scopeContext.bind(id);
  }

  /**
   * Wraps the given {@link Runnable} so that it executes within this session's scope on whatever
   * thread runs it.
   *
   * @param runnable the task to wrap
   * @return a new {@link Runnable} that pushes this session's ID onto the executing thread's scope
   *     context before delegating to {@code runnable}, and pops it afterwards
   */
  @Override
  public Runnable wrap(final Runnable runnable) {
    return scopeContext.wrap(id, runnable);
  }

  /**
   * Wraps the given {@link Callable} so that it executes within this session's scope on whatever
   * thread runs it.
   *
   * @param <T> the return type of the callable
   * @param callable the task to wrap
   * @return a new {@link Callable} that pushes this session's ID onto the executing thread's scope
   *     context before delegating to {@code callable}, and pops it afterwards
   */
  @Override
  public <T> Callable<T> wrap(final Callable<T> callable) {
    return scopeContext.wrap(id, callable);
  }

  /**
   * Destroys all activation handles registered with this session and closes the root scope binding.
   *
   * <p>Each handle is destroyed in iteration order, which stops and unregisters the corresponding
   * scenario. The root scope binding is closed last, removing this session's ID from the calling
   * thread's scope context stack.
   */
  @Override
  public void close() {
    handles.forEach(DefaultChaosActivationHandle::destroy);
    rootBinding.close();
  }
}
