package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.Internal;
import com.macstab.chaos.jvm.api.OperationType;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Dispatch of executor, scheduler, fork-join, queue and future operations, and of shutdown hooks.
 *
 * <p>One domain of the dispatch the instrumentation layer calls through the bootstrap bridge; the
 * evaluation it shares with the other domains is {@link DispatchEngine}. Not part of the stable API
 * (see {@link ChaosDispatcher}).
 */
@Internal
public final class ExecutorDispatch {

  private final DispatchEngine engine;
  private final Map<Thread, Thread> shutdownHooks;

  ExecutorDispatch(final DispatchEngine engine, final Map<Thread, Thread> shutdownHooks) {
    this.engine = engine;
    this.shutdownHooks = shutdownHooks;
  }

  // Shared instances: decorated tasks are compared with them by identity.
  @SuppressWarnings("UnnecessaryLambda")
  private static final Runnable NO_OP_RUNNABLE = () -> {};

  @SuppressWarnings("UnnecessaryLambda")
  private static final Callable<?> NO_OP_CALLABLE = () -> null;

  private final Map<Thread, java.util.concurrent.atomic.AtomicReference<@Nullable Thread>>
      hookDelegateRefs = new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * Returns a (possibly session-scoped or suppressed) wrapper for an executor-submitted runnable.
   *
   * @param operation the {@link OperationType} name describing the submission site
   * @param executor the executor receiving the task; may be {@code null} when unknown
   * @param task the submitted runnable; must not be {@code null}
   * @return a runnable to submit in place of {@code task}, possibly a no-op when suppressed
   */
  public Runnable decorateExecutorRunnable(
      final String operation, final Object executor, final Runnable task) {
    Objects.requireNonNull(task, "task");
    final String sessionId = engine.scopeContext.currentSessionId();
    final Runnable scoped = sessionId == null ? task : engine.scopeContext.wrap(sessionId, task);
    final OperationType indexedOp = OperationType.valueOf(operation);
    if (engine.idle(indexedOp)) {
      // Before any decision: escape analysis does not remove one on this path (32 B per call).
      return scoped;
    }
    final RuntimeDecision decision =
        engine.evaluate(
            new InvocationContext(
                indexedOp,
                executor == null ? "unknown" : executor.getClass().getName(),
                DispatchEngine.taskClassName(task),
                null,
                false,
                null,
                null,
                sessionId));
    if (decision.terminalAction() != null
        && decision.terminalAction().kind() == TerminalKind.SUPPRESS) {
      // Honour composed "gate + delay + suppress" scenarios before substituting the no-op, so
      // the executor path matches beforeHttpSend / beforeScheduledTick / afterMethodExit which
      // already fire the gate and delay around their terminal; returning early here would turn
      // "pause 5 s then suppress" into instant suppression.
      try {
        engine.applyDelayAndGate(decision);
      } catch (final Throwable throwable) {
        throw engine.propagate(throwable);
      }
      // A submitted FutureTask must still complete, or Future.get() blocks forever: cancel it
      // instead of dropping it, so callers observe the suppression as a CancellationException.
      return task instanceof java.util.concurrent.Future<?> future
          ? () -> future.cancel(false)
          : NO_OP_RUNNABLE;
    }
    try {
      engine.applyPreDecision(decision);
    } catch (final Throwable throwable) {
      throw engine.propagate(throwable);
    }
    return scoped;
  }

  /**
   * Returns a (possibly session-scoped or suppressed) wrapper for an executor-submitted callable.
   *
   * @param <T> the result type produced by the callable
   * @param operation the {@link OperationType} name describing the submission site
   * @param executor the executor receiving the task; may be {@code null} when unknown
   * @param task the submitted callable; must not be {@code null}
   * @return a callable to submit in place of {@code task}, possibly a no-op when suppressed
   */
  public <T> Callable<T> decorateExecutorCallable(
      final String operation, final Object executor, final Callable<T> task) {
    Objects.requireNonNull(task, "task");
    final String sessionId = engine.scopeContext.currentSessionId();
    final Callable<T> scoped = sessionId == null ? task : engine.scopeContext.wrap(sessionId, task);
    final OperationType indexedOp = OperationType.valueOf(operation);
    if (engine.idle(indexedOp)) {
      return scoped;
    }
    final RuntimeDecision decision =
        engine.evaluate(
            new InvocationContext(
                indexedOp,
                executor == null ? "unknown" : executor.getClass().getName(),
                DispatchEngine.taskClassName(task),
                null,
                false,
                null,
                null,
                sessionId));
    if (decision.terminalAction() != null
        && decision.terminalAction().kind() == TerminalKind.SUPPRESS) {
      // See decorateExecutorRunnable — apply gate + delay before returning the no-op Callable
      // so composed scenarios preserve the pre-terminal observables.
      try {
        engine.applyDelayAndGate(decision);
      } catch (final Throwable throwable) {
        throw engine.propagate(throwable);
      }
      @SuppressWarnings("unchecked")
      final Callable<T> noOp = (Callable<T>) NO_OP_CALLABLE;
      return noOp;
    }
    try {
      engine.applyPreDecision(decision);
    } catch (final Throwable throwable) {
      throw engine.propagate(throwable);
    }
    return scoped;
  }

  /**
   * Called at the top of a pool worker's run loop before the next task is dequeued.
   *
   * @param executor the executor owning the worker
   * @param worker the worker thread about to run the task; may be {@code null}
   * @param task the task that the worker is about to execute; may be {@code null}
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public void beforeWorkerRun(final Object executor, final Thread worker, final Runnable task)
      throws Throwable {
    engine.applyPreDecision(
        (engine.idle(OperationType.EXECUTOR_WORKER_RUN)
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    OperationType.EXECUTOR_WORKER_RUN,
                    executor.getClass().getName(),
                    DispatchEngine.taskClassName(ScopeContext.unwrap(task)),
                    worker == null ? null : worker.getName(),
                    false,
                    worker == null ? null : worker.isDaemon(),
                    worker == null ? null : engine.featureSet.isVirtualThread(worker),
                    engine.taskSession(task)))));
  }

  /**
   * Called before a {@link java.util.concurrent.ForkJoinTask} begins execution on a pool worker.
   *
   * @param task the fork-join task about to execute; may be {@code null}
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public void beforeForkJoinTaskRun(final java.util.concurrent.ForkJoinTask<?> task)
      throws Throwable {
    engine.applyPreDecision(
        (engine.idle(OperationType.FORK_JOIN_TASK_RUN)
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    OperationType.FORK_JOIN_TASK_RUN,
                    "java.util.concurrent.ForkJoinPool",
                    DispatchEngine.taskClassName(task),
                    null,
                    false,
                    null,
                    null,
                    engine.scopeContext.currentSessionId()))));
  }

  /**
   * Called before a void-returning blocking queue operation such as {@code put} or {@code take}.
   *
   * @param operation the {@link OperationType} name describing the queue operation
   * @param queue the queue receiving the operation; may be {@code null} when unknown
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public void beforeQueueOperation(final String operation, final Object queue) throws Throwable {
    final OperationType indexedOp = OperationType.valueOf(operation);
    engine.applyPreDecision(
        (engine.idle(indexedOp)
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    indexedOp,
                    queue == null ? "unknown" : queue.getClass().getName(),
                    null,
                    null,
                    false,
                    null,
                    null,
                    engine.scopeContext.currentSessionId()))));
  }

  /**
   * Called before a boolean-returning queue operation (e.g. {@code offer}) to optionally override
   * its result.
   *
   * @param operation the {@link OperationType} name describing the queue operation
   * @param queue the queue receiving the operation; may be {@code null} when unknown
   * @return the forced boolean result to return to the caller, or {@code null} to keep the real
   *     result
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public @Nullable Boolean beforeBooleanQueueOperation(final String operation, final Object queue)
      throws Throwable {
    final OperationType indexedOp = OperationType.valueOf(operation);
    final RuntimeDecision decision =
        (engine.idle(indexedOp)
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    indexedOp,
                    queue == null ? "unknown" : queue.getClass().getName(),
                    null,
                    null,
                    false,
                    null,
                    null,
                    engine.scopeContext.currentSessionId())));
    engine.applyDelayAndGate(decision);
    if (decision.terminalAction() != null) {
      if (decision.terminalAction() instanceof TerminalAction.Throw thrown) {
        throw thrown.failure();
      }
      if (decision.terminalAction() instanceof TerminalAction.Return returned) {
        return returned.value();
      }
    }
    return null;
  }

  /**
   * Called before a {@link CompletableFuture} completion method to optionally override its outcome.
   *
   * @param operation the {@link OperationType} name describing the completion site
   * @param future the future being completed
   * @param payload the completion payload (value or exception); may be {@code null}
   * @return the forced boolean result to return to the caller, or {@code null} to keep the real
   *     result
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public @Nullable Boolean beforeCompletableFutureComplete(
      final String operation, final CompletableFuture<?> future, final Object payload)
      throws Throwable {
    final OperationType indexedOp = OperationType.valueOf(operation);
    final RuntimeDecision decision =
        (engine.idle(indexedOp)
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    indexedOp,
                    CompletableFuture.class.getName(),
                    payload == null ? null : payload.getClass().getName(),
                    null,
                    false,
                    null,
                    null,
                    engine.scopeContext.currentSessionId())));
    engine.applyDelayAndGate(decision);
    if (decision.terminalAction() != null) {
      final TerminalAction terminalAction = decision.terminalAction();
      if (terminalAction instanceof TerminalAction.Throw thrown) {
        throw thrown.failure();
      }
      if (terminalAction instanceof TerminalAction.Return returned) {
        return returned.value();
      }
      if (terminalAction instanceof TerminalAction.CompleteExceptionally completing) {
        return future.completeExceptionally(completing.failure());
      }
    }
    return null;
  }

  /**
   * Wraps a shutdown hook thread before it is registered with {@link
   * Runtime#addShutdownHook(Thread)}.
   *
   * @param hook the shutdown hook being registered; may be {@code null}
   * @return the thread to register in place of {@code hook}, or {@code hook} itself when decoration
   *     is skipped
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public Thread decorateShutdownHook(final Thread hook) throws Throwable {
    final RuntimeDecision decision =
        (engine.idle(OperationType.SHUTDOWN_HOOK_REGISTER)
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    OperationType.SHUTDOWN_HOOK_REGISTER,
                    hook == null ? Thread.class.getName() : hook.getClass().getName(),
                    null,
                    hook == null ? null : hook.getName(),
                    false,
                    hook == null ? null : hook.isDaemon(),
                    hook == null ? null : engine.featureSet.isVirtualThread(hook),
                    null)));
    // A throwing terminal (reject) unwinds here; suppress is rejected for this operation at
    // activation (CompatibilityValidator), so every other decision registers the wrapper.
    engine.applyPreDecision(decision);
    final java.util.concurrent.atomic.AtomicReference<@Nullable Thread> hookRef =
        new java.util.concurrent.atomic.AtomicReference<>(hook);
    final Thread decorated =
        new Thread(
            () -> {
              final Thread h = hookRef.getAndSet(null);
              if (h != null) {
                // The original hook is never started: its body runs on this wrapper thread,
                // which the runtime starts at shutdown in its place.
                runHookBody(h);
              }
            },
            hook.getName() + "-macstab-chaos-wrapper");
    decorated.setDaemon(hook.isDaemon());
    shutdownHooks.put(hook, decorated);
    hookDelegateRefs.put(hook, hookRef);
    return decorated;
  }

  /**
   * Resolves the registered wrapper thread for an original shutdown hook, removing the mapping on
   * lookup.
   *
   * @param original the original user-supplied shutdown hook
   * @return the decorated wrapper thread previously registered for {@code original}, or {@code
   *     original} itself when no wrapper is recorded
   */
  public Thread resolveShutdownHook(final Thread original) {
    // Destructive lookup: when Runtime.removeShutdownHook is called the JVM is about to drop its
    // own reference to the decorated thread, so we must release ours too. Leaving the mapping in
    // place would retain both the user hook and our wrapper for the life of the process — a slow
    // leak for long-running JVMs that churn shutdown-hook registrations (e.g. integration-test
    // suites that stop and restart embedded servers).
    final Thread decorated = shutdownHooks.remove(original);
    final java.util.concurrent.atomic.AtomicReference<@Nullable Thread> hookRef =
        hookDelegateRefs.remove(original);
    if (hookRef != null) {
      hookRef.set(null);
    }
    return decorated == null ? original : decorated;
  }

  /**
   * Called before an executor's {@code shutdown} or {@code shutdownNow} to apply matching
   * scenarios.
   *
   * @param operation the {@link OperationType} name describing the shutdown call
   * @param executor the executor being shut down; may be {@code null} when unknown
   * @param timeoutMillis the caller-supplied timeout in milliseconds (awaitTermination), or zero
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public void beforeExecutorShutdown(
      final String operation, final Object executor, final long timeoutMillis) throws Throwable {
    final OperationType indexedOp = OperationType.valueOf(operation);
    engine.applyPreDecision(
        (engine.idle(indexedOp)
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    indexedOp,
                    executor == null ? "unknown" : executor.getClass().getName(),
                    null,
                    Long.toString(timeoutMillis),
                    false,
                    null,
                    null,
                    engine.scopeContext.currentSessionId()))));
  }

  /**
   * Called before each execution of a scheduled task; returns {@code false} to suppress the tick.
   *
   * @param executor the scheduling executor; may be {@code null} when unknown
   * @param task the task about to run; may be {@code null}
   * @param periodic {@code true} when the task is scheduled at a fixed rate or with fixed delay
   * @return {@code true} to let the tick proceed, {@code false} to suppress this execution
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public boolean beforeScheduledTick(
      final Object executor, final Object task, final boolean periodic) throws Throwable {
    final RuntimeDecision decision =
        (engine.idle(OperationType.SCHEDULE_TICK)
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    OperationType.SCHEDULE_TICK,
                    executor == null ? "unknown" : executor.getClass().getName(),
                    DispatchEngine.taskClassName(ScopeContext.unwrap(task)),
                    null,
                    periodic,
                    null,
                    null,
                    engine.taskSession(task))));
    engine.applyDelayAndGate(decision);
    if (decision.terminalAction() != null) {
      if (decision.terminalAction() instanceof TerminalAction.Throw thrown) {
        throw thrown.failure();
      }
      if (decision.terminalAction().kind() == TerminalKind.RETURN
          || decision.terminalAction().kind() == TerminalKind.SUPPRESS) {
        return false;
      }
    }
    return true;
  }

  /**
   * Called before {@link CompletableFuture#cancel(boolean)}; returns {@code true} to suppress
   * cancellation.
   *
   * @param future the {@link CompletableFuture} being cancelled
   * @param mayInterruptIfRunning whether the cancel may interrupt a running task
   * @return {@code true} to suppress the cancel (caller short-circuits); {@code false} for normal
   *     execution
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public boolean beforeAsyncCancel(final Object future, final boolean mayInterruptIfRunning)
      throws Throwable {
    final RuntimeDecision decision =
        (engine.idle(OperationType.ASYNC_CANCEL)
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    OperationType.ASYNC_CANCEL,
                    CompletableFuture.class.getName(),
                    null,
                    null,
                    false,
                    null,
                    null,
                    engine.scopeContext.currentSessionId())));
    engine.applyDelayAndGate(decision);
    if (decision.terminalAction() != null) {
      final TerminalAction terminalAction = decision.terminalAction();
      if (terminalAction instanceof TerminalAction.Throw thrown) {
        throw thrown.failure();
      }
      if (terminalAction.kind() == TerminalKind.SUPPRESS) {
        return true;
      }
      // COMPLETE_EXCEPTIONALLY is documented as "complete a CompletableFuture exceptionally with
      // the enclosed throwable" — the ASYNC_CANCEL site is the canonical call site for that
      // semantic, so the terminal action is applied here and the real cancel() does not run.
      if (terminalAction instanceof TerminalAction.CompleteExceptionally completing
          && future instanceof CompletableFuture<?> completable) {
        completable.completeExceptionally(completing.failure());
        // Report "cancel succeeded" so the call site short-circuits and does not invoke the
        // real cancel (which would race against our completeExceptionally).
        return true;
      }
    }
    return false;
  }

  /** Runs a shutdown hook's body on the calling thread: the wrapper thread that replaced it. */
  @SuppressWarnings("DoNotCall")
  private static void runHookBody(final Thread hook) {
    hook.run();
  }
}
