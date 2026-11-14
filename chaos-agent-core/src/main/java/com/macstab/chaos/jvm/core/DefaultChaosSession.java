package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosActivationHandle;
import com.macstab.chaos.jvm.api.ChaosPlan;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.ChaosSession;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default package-private implementation of {@link com.macstab.chaos.jvm.api.ChaosSession}.
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
  private final ChaosControlPlaneImpl controlPlane;
  private final List<DefaultChaosActivationHandle> handles = new CopyOnWriteArrayList<>();
  private final ScopeBinding rootBinding;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  /**
   * Creates a new session, assigns it a random UUID, and pushes the session ID onto the calling
   * thread's scope context stack.
   *
   * @param displayName a human-readable label for this session, used in diagnostics
   * @param scopeContext the scope context used for thread-local session ID propagation
   * @param controlPlane the control plane used to activate scenarios within this session; held
   *     directly rather than indirected through {@link ChaosRuntime} because {@code
   *     activateInSession} lives on this type — removing the facade hop also lets {@link
   *     ChaosControlPlaneImpl#openSession(String)} work standalone
   */
  DefaultChaosSession(
      final String displayName,
      final ScopeContext scopeContext,
      final ChaosControlPlaneImpl controlPlane) {
    this.displayName = displayName;
    this.scopeContext = scopeContext;
    this.controlPlane = controlPlane;
    this.rootBinding = scopeContext.bindRoot(id);
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
   * <p>Delegates to {@link ChaosControlPlaneImpl#activateInSession} and registers the resulting
   * handle so that it is automatically destroyed when this session is {@linkplain #close() closed}.
   *
   * @param scenario the scenario to activate
   * @return a handle for controlling the activated scenario
   */
  @Override
  public ChaosActivationHandle activate(final ChaosScenario scenario) {
    if (closed.get()) {
      throw new IllegalStateException("cannot activate scenario on a closed session");
    }
    final DefaultChaosActivationHandle handle = controlPlane.activateInSession(this, scenario);
    // A handle the caller closed has nothing left for close() to end: dropped here, so a
    // long-lived session keeps no closed scenario reachable and copies no growing list.
    handles.removeIf(DefaultChaosActivationHandle::destroyed);
    handles.add(handle);
    // Guard against the race where close() iterated handles before add() above completed.
    // If the session was closed concurrently, destroy the handle immediately rather than leaking.
    if (closed.get()) {
      handles.remove(handle);
      handle.destroy();
      throw new IllegalStateException("session was closed during activation");
    }
    return handle;
  }

  /**
   * Activates all scenarios in the given plan within this session, as one unit: every scenario is
   * validated before any is registered, the plan's observability settings apply to its scenarios,
   * and a failure rolls back those already registered (see {@link
   * ChaosControlPlaneImpl#activatePlan}).
   *
   * @param plan the chaos plan whose scenarios should all be activated
   * @return a composite handle that delegates operations to every activated scenario
   */
  @Override
  public ChaosActivationHandle activate(final ChaosPlan plan) {
    if (closed.get()) {
      throw new IllegalStateException("cannot activate a plan on a closed session");
    }
    return controlPlane.activatePlan(
        plan,
        "session:" + id,
        "session-plan:",
        scenario -> controlPlane.validateInSession(this, scenario),
        this::activate,
        child -> {
          // Leave the session's handle list first, so close() never destroys it a second time.
          if (child instanceof DefaultChaosActivationHandle defaultHandle) {
            handles.remove(defaultHandle);
            defaultHandle.destroy();
          } else {
            child.stop();
          }
        });
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
    return scopeContext.wrap(id, Objects.requireNonNull(runnable, "runnable"));
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
    return scopeContext.wrap(id, Objects.requireNonNull(callable, "callable"));
  }

  /**
   * The number of handles this session keeps; package-private as a test seam.
   *
   * @return the handles kept for {@link #close()}
   */
  int handleCount() {
    return handles.size();
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
    // AutoCloseable.close() is documented as idempotent-recommended. Framework integrations
    // often pair try-with-resources with @AfterEach cleanup, so double close() is easy. Without
    // a guard the second call's rootBinding.close() would remove an unrelated session id from
    // the closer's scope deque (or throw IllegalStateException from the LIFO guard) and the
    // second destroy-loop would emit duplicate STOPPED events for every handle. Short-circuit
    // on the first successful transition.
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    controlPlane.sessionClosed(id);
    // Aggregate failures across destroy() calls and always close the root binding. Without the
    // try/finally, the first handle that throws during destroy skips every remaining handle AND
    // leaks the thread's scope binding — leaving session IDs stuck on the scope stack for the
    // rest of the thread's life. Collect throwables via addSuppressed so the caller gets a
    // complete picture instead of just the first failure.
    RuntimeException aggregate = null;
    for (final DefaultChaosActivationHandle handle : handles) {
      try {
        handle.destroy();
      } catch (final RuntimeException perHandle) {
        if (aggregate == null) {
          aggregate = new RuntimeException("one or more handles failed to destroy", perHandle);
        } else {
          aggregate.addSuppressed(perHandle);
        }
      }
    }
    // The plans of this session may still hold observability settings (a plan handle that was
    // never closed): its scenarios are gone, so are the settings.
    controlPlane.clearScopeObservability("session:" + id);
    try {
      rootBinding.close();
    } catch (final RuntimeException bindingFailure) {
      if (aggregate == null) {
        throw bindingFailure;
      }
      aggregate.addSuppressed(bindingFailure);
    }
    if (aggregate != null) {
      throw aggregate;
    }
  }
}
