package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosScenario;
import com.macstab.chaos.jvm.api.Internal;
import com.macstab.chaos.jvm.api.OperationType;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Hot-path dispatch component of the chaos runtime.
 *
 * <p>Holds all {@code before*} / {@code after*} / {@code adjust*} / {@code decorate*} entry points
 * that the instrumentation layer calls through the classloader bridge (see {@code
 * BootstrapDispatcher}). Constructed by {@link ChaosControlPlaneImpl} and passed to {@code
 * ChaosBridge}.
 *
 * <h2>Thread safety</h2>
 *
 * <p>All methods are fully thread-safe.
 *
 * <h2>API stability</h2>
 *
 * <p><strong>Not part of the stable API.</strong> This class is {@code public} because it is called
 * from the instrumentation classloader bridge and from benchmarks, not because its method surface
 * is frozen. The stable public surface of the chaos-agent is {@code
 * com.macstab.chaos.jvm.api.ChaosControlPlane}; bind to that interface instead. The {@link
 * Internal @Internal} marker signals to API linters and bytecode-compat tools that this class can
 * change without notice in any release.
 */
@Internal
public final class ChaosDispatcher {
  private static final Runnable NO_OP_RUNNABLE = () -> {};
  private static final Callable<?> NO_OP_CALLABLE = () -> null;

  /**
   * Resolved lazily on first use; {@code null} until resolution succeeds.
   *
   * <p>Accessed from {@link #incrementDispatchDepth()} / {@link #decrementDispatchDepth()} which
   * are called by {@link ScenarioController#start()} to suppress clock-skew interception during
   * startup clock reads. BootstrapDispatcher lives in the bootstrap classpath (not a compile-time
   * dependency of this module), so reflection is the only way to reach it.
   */
  @SuppressWarnings("unchecked")
  private static volatile java.util.concurrent.atomic.AtomicReference<ThreadLocal<int[]>>
      bootstrapDepthRef = null;

  private static ThreadLocal<int[]> resolveBootstrapDepth() {
    if (bootstrapDepthRef != null) {
      return bootstrapDepthRef.get();
    }
    synchronized (ChaosDispatcher.class) {
      if (bootstrapDepthRef != null) {
        return bootstrapDepthRef.get();
      }
      try {
        final Class<?> cls =
            Class.forName(
                "com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher",
                false,
                ClassLoader.getPlatformClassLoader());
        @SuppressWarnings("unchecked")
        final ThreadLocal<int[]> tl =
            (ThreadLocal<int[]>) cls.getMethod("depthThreadLocal").invoke(null);
        bootstrapDepthRef = new java.util.concurrent.atomic.AtomicReference<>(tl);
        return tl;
      } catch (final Throwable ignored) {
        // Bootstrap dispatcher not yet installed (unit-test mode without agent).
        // Store null reference so subsequent callers skip the guard rather than
        // retrying reflection on every call.
        bootstrapDepthRef = new java.util.concurrent.atomic.AtomicReference<>(null);
        return null;
      }
    }
  }

  /**
   * Increments the bootstrap dispatcher's DEPTH counter on the current thread, suppressing any
   * active chaos interception on subsequent JVM calls (clock reads, park, sleep, …) until the
   * matching {@link #decrementDispatchDepth()} is called.
   *
   * <p>Designed to be called from {@code ScenarioController.start()} so that {@code
   * clock.instant()}, {@code System.currentTimeMillis()}, and {@code System.nanoTime()} during
   * scenario startup are not skewed by a concurrently active {@code ClockSkewEffect} scenario —
   * which would silently shift the {@code startedAt} anchor and corrupt the {@code activeFor}
   * window calculation.
   */
  static void incrementDispatchDepth() {
    final ThreadLocal<int[]> depth = resolveBootstrapDepth();
    if (depth != null) {
      depth.get()[0]++;
    }
  }

  /** Paired decrement for {@link #incrementDispatchDepth()}. Always call in a {@code finally}. */
  static void decrementDispatchDepth() {
    final ThreadLocal<int[]> depth = resolveBootstrapDepth();
    if (depth != null) {
      depth.get()[0]--;
    }
  }

  // Safe upper bound for an "indefinite" schedule delay in milliseconds.
  // ScheduledThreadPoolExecutor
  // internally converts the delay to nanoseconds and adds it to System.nanoTime() in
  // ScheduledFutureTask.triggerTime; returning Long.MAX_VALUE here overflows that multiplication
  // and
  // produces a negative trigger time that fires immediately — the opposite of the intent. ~100
  // years of millis multiplied by 1_000_000 still fits in a long, so the task effectively never
  // fires but the bookkeeping stays well-defined.
  private static final long INDEFINITE_SCHEDULE_DELAY_MILLIS = TimeUnit.DAYS.toMillis(365L * 100L);

  private final FeatureSet featureSet;
  private final ScopeContext scopeContext;
  private final ScenarioRegistry registry;
  private final Map<Thread, Thread> shutdownHooks;
  private final Map<Thread, java.util.concurrent.atomic.AtomicReference<Thread>> hookDelegateRefs =
      new java.util.concurrent.ConcurrentHashMap<>();

  ChaosDispatcher(final ChaosControlPlaneImpl controlPlane) {
    this.featureSet = controlPlane.featureSet();
    this.scopeContext = controlPlane.scopeContext();
    this.registry = controlPlane.registry();
    this.shutdownHooks = controlPlane.shutdownHooks();
  }

  /**
   * Returns the session id bound to the current thread, or {@code null} if none is active.
   *
   * @return the current thread's session id, or {@code null} when no session is bound
   */
  public String currentSessionId() {
    return scopeContext.currentSessionId();
  }

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
    final String sessionId = scopeContext.currentSessionId();
    final Runnable scoped = sessionId == null ? task : scopeContext.wrap(sessionId, task);
    final InvocationContext context =
        new InvocationContext(
            OperationType.valueOf(operation),
            executor == null ? "unknown" : executor.getClass().getName(),
            taskClassName(task),
            null,
            false,
            null,
            null,
            sessionId);
    final RuntimeDecision decision = evaluate(context);
    if (decision.terminalAction() != null
        && decision.terminalAction().kind() == TerminalKind.SUPPRESS) {
      // Honour composed "gate + delay + suppress" scenarios before substituting the no-op, so
      // the executor path matches beforeHttpSend / beforeScheduledTick / afterMethodExit which
      // already fire the gate and delay around their terminal. Previously the early return
      // silently dropped both, turning "pause 5 s then suppress" into instant suppression.
      try {
        applyGate(decision.gateAction());
        sleep(decision.delayMillis());
      } catch (final Throwable throwable) {
        throw propagate(throwable);
      }
      return NO_OP_RUNNABLE;
    }
    try {
      applyPreDecision(decision);
    } catch (final Throwable throwable) {
      throw propagate(throwable);
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
    final String sessionId = scopeContext.currentSessionId();
    final Callable<T> scoped = sessionId == null ? task : scopeContext.wrap(sessionId, task);
    final InvocationContext context =
        new InvocationContext(
            OperationType.valueOf(operation),
            executor == null ? "unknown" : executor.getClass().getName(),
            taskClassName(task),
            null,
            false,
            null,
            null,
            sessionId);
    final RuntimeDecision decision = evaluate(context);
    if (decision.terminalAction() != null
        && decision.terminalAction().kind() == TerminalKind.SUPPRESS) {
      // See decorateExecutorRunnable — apply gate + delay before returning the no-op Callable
      // so composed scenarios preserve the pre-terminal observables.
      try {
        applyGate(decision.gateAction());
        sleep(decision.delayMillis());
      } catch (final Throwable throwable) {
        throw propagate(throwable);
      }
      @SuppressWarnings("unchecked")
      final Callable<T> noOp = (Callable<T>) NO_OP_CALLABLE;
      return noOp;
    }
    try {
      applyPreDecision(decision);
    } catch (final Throwable throwable) {
      throw propagate(throwable);
    }
    return scoped;
  }

  /**
   * Called before {@link Thread#start()} to apply any active chaos scenario matching the thread.
   *
   * @param thread the thread about to be started; may be {@code null}
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public void beforeThreadStart(final Thread thread) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            featureSet.isVirtualThread(thread)
                ? OperationType.VIRTUAL_THREAD_START
                : OperationType.THREAD_START,
            Thread.class.getName(),
            null,
            thread == null ? null : thread.getName(),
            false,
            thread == null ? null : thread.isDaemon(),
            thread == null ? null : featureSet.isVirtualThread(thread),
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
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
    final InvocationContext context =
        new InvocationContext(
            OperationType.EXECUTOR_WORKER_RUN,
            executor.getClass().getName(),
            taskClassName(task),
            worker == null ? null : worker.getName(),
            false,
            worker == null ? null : worker.isDaemon(),
            worker == null ? null : featureSet.isVirtualThread(worker),
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  /**
   * Called before a {@link java.util.concurrent.ForkJoinTask} begins execution on a pool worker.
   *
   * @param task the fork-join task about to execute; may be {@code null}
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public void beforeForkJoinTaskRun(final java.util.concurrent.ForkJoinTask<?> task)
      throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.FORK_JOIN_TASK_RUN,
            "java.util.concurrent.ForkJoinPool",
            taskClassName(task),
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  /**
   * Allows an active scenario to alter the scheduling delay of a scheduled executor submission.
   *
   * @param operation the {@link OperationType} name describing the schedule site
   * @param executor the scheduling executor; may be {@code null} when unknown
   * @param task the task being scheduled; may be {@code null}
   * @param delay the caller-provided scheduling delay in milliseconds
   * @param periodic {@code true} when the task is scheduled at a fixed rate or with fixed delay
   * @return the (possibly adjusted) scheduling delay in milliseconds
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public long adjustScheduleDelay(
      final String operation,
      final Object executor,
      final Object task,
      final long delay,
      final boolean periodic)
      throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.valueOf(operation),
            executor == null ? "unknown" : executor.getClass().getName(),
            taskClassName(task),
            null,
            periodic,
            null,
            null,
            scopeContext.currentSessionId());
    final RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      final TerminalAction terminalAction = decision.terminalAction();
      if (terminalAction.kind() == TerminalKind.THROW) {
        throw terminalAction.throwable();
      }
      if (terminalAction.kind() == TerminalKind.RETURN
          || terminalAction.kind() == TerminalKind.SUPPRESS) {
        return INDEFINITE_SCHEDULE_DELAY_MILLIS;
      }
    }
    // Saturating add: a plain `delay + decision.delayMillis()` wraps to a negative long for
    // large inputs, and ScheduledThreadPoolExecutor.schedule with a negative delay fires the
    // task immediately — the exact opposite of the intended chaos. Mirrors the saturating add
    // already present in the sibling adjustExecuteDelay path (see applyPreDecision).
    final long chaosDelay = decision.delayMillis();
    if (chaosDelay > 0L && delay > Long.MAX_VALUE - chaosDelay) {
      return Long.MAX_VALUE;
    }
    if (chaosDelay < 0L && delay < Long.MIN_VALUE - chaosDelay) {
      return Long.MIN_VALUE;
    }
    return delay + chaosDelay;
  }

  /**
   * Called before a void-returning blocking queue operation such as {@code put} or {@code take}.
   *
   * @param operation the {@link OperationType} name describing the queue operation
   * @param queue the queue receiving the operation; may be {@code null} when unknown
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public void beforeQueueOperation(final String operation, final Object queue) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.valueOf(operation),
            queue == null ? "unknown" : queue.getClass().getName(),
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
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
  public Boolean beforeBooleanQueueOperation(final String operation, final Object queue)
      throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.valueOf(operation),
            queue == null ? "unknown" : queue.getClass().getName(),
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    final RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      if (decision.terminalAction().kind() == TerminalKind.THROW) {
        throw decision.terminalAction().throwable();
      }
      if (decision.terminalAction().kind() == TerminalKind.RETURN
          || decision.terminalAction().kind() == TerminalKind.SUPPRESS) {
        return (Boolean) decision.terminalAction().returnValue();
      }
    }
    sleep(decision.delayMillis());
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
  public Boolean beforeCompletableFutureComplete(
      final String operation, final CompletableFuture<?> future, final Object payload)
      throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.valueOf(operation),
            CompletableFuture.class.getName(),
            payload == null ? null : payload.getClass().getName(),
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    final RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      final TerminalAction terminalAction = decision.terminalAction();
      if (terminalAction.kind() == TerminalKind.THROW) {
        throw terminalAction.throwable();
      }
      if (terminalAction.kind() == TerminalKind.RETURN) {
        return (Boolean) terminalAction.returnValue();
      }
      if (terminalAction.kind() == TerminalKind.COMPLETE_EXCEPTIONALLY) {
        return future.completeExceptionally(terminalAction.throwable());
      }
    }
    sleep(decision.delayMillis());
    return null;
  }

  /**
   * Called before {@link ClassLoader#loadClass(String)} to apply scenarios targeting class loading.
   *
   * @param loader the loader performing the lookup; {@code null} for the bootstrap loader
   * @param className the binary name of the class being loaded
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public void beforeClassLoad(final ClassLoader loader, final String className) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.CLASS_LOAD,
            loader == null ? "bootstrap" : loader.getClass().getName(),
            null,
            className,
            false,
            null,
            null,
            null);
    applyPreDecision(evaluate(context));
  }

  /**
   * Called after {@link ClassLoader#getResource(String)} to optionally substitute the returned URL.
   *
   * @param loader the loader that performed the lookup; {@code null} for the bootstrap loader
   * @param name the resource name that was requested
   * @param currentValue the URL resolved by the real lookup; may be {@code null}
   * @return the (possibly substituted) URL to return to the caller
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public URL afterResourceLookup(
      final ClassLoader loader, final String name, final URL currentValue) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.RESOURCE_LOAD,
            loader == null ? "bootstrap" : loader.getClass().getName(),
            null,
            name,
            false,
            null,
            null,
            null);
    final RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      if (decision.terminalAction().kind() == TerminalKind.THROW) {
        throw decision.terminalAction().throwable();
      }
      return (URL) decision.terminalAction().returnValue();
    }
    sleep(decision.delayMillis());
    return currentValue;
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
    final InvocationContext context =
        new InvocationContext(
            OperationType.SHUTDOWN_HOOK_REGISTER,
            hook == null ? Thread.class.getName() : hook.getClass().getName(),
            null,
            hook == null ? null : hook.getName(),
            false,
            hook == null ? null : hook.isDaemon(),
            hook == null ? null : featureSet.isVirtualThread(hook),
            null);
    final RuntimeDecision decision = evaluate(context);
    applyPreDecision(decision);
    // SUPPRESS: `applyPreDecision` returns normally for SUPPRESS (unlike THROW, which unwinds)
    // but the hook should not reach Runtime.addShutdownHook if the operator asked for the
    // registration to be suppressed. Building and mapping a decorated wrapper here leaves the
    // JVM with a hook it cannot `removeShutdownHook` later (resolveShutdownHook would return
    // the wrapper, which Runtime doesn't know about). Return the original unchanged so the
    // registration still happens verbatim — the suppress intent is honoured by the agent not
    // introducing a wrapper, not by actively blocking registration.
    if (decision.terminalAction() != null
        && decision.terminalAction().kind() == TerminalKind.SUPPRESS) {
      return hook;
    }
    final java.util.concurrent.atomic.AtomicReference<Thread> hookRef =
        new java.util.concurrent.atomic.AtomicReference<>(hook);
    final Thread decorated =
        new Thread(
            () -> {
              final Thread h = hookRef.getAndSet(null);
              if (h != null) {
                h.run();
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
    final java.util.concurrent.atomic.AtomicReference<Thread> hookRef =
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
    final InvocationContext context =
        new InvocationContext(
            OperationType.valueOf(operation),
            executor == null ? "unknown" : executor.getClass().getName(),
            null,
            Long.toString(timeoutMillis),
            false,
            null,
            null,
            null);
    applyPreDecision(evaluate(context));
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
    final InvocationContext context =
        new InvocationContext(
            OperationType.SCHEDULE_TICK,
            executor == null ? "unknown" : executor.getClass().getName(),
            taskClassName(task),
            null,
            periodic,
            null,
            null,
            scopeContext.currentSessionId());
    final RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      if (decision.terminalAction().kind() == TerminalKind.THROW) {
        throw decision.terminalAction().throwable();
      }
      if (decision.terminalAction().kind() == TerminalKind.RETURN
          || decision.terminalAction().kind() == TerminalKind.SUPPRESS) {
        return false;
      }
    }
    sleep(decision.delayMillis());
    return true;
  }

  /**
   * Called from advice at method entry to apply any scenario targeting the given class and method.
   *
   * @param className the fully qualified name of the class containing the advised method
   * @param methodName the name of the advised method
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public void beforeMethodEnter(final String className, final String methodName) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.METHOD_ENTER,
            className,
            className,
            methodName,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  /**
   * Called from advice at method exit to optionally corrupt the return value per active scenarios.
   *
   * @param className the fully qualified name of the class containing the advised method
   * @param methodName the name of the advised method
   * @param returnType the declared return type used to guide corruption strategies
   * @param actualValue the real return value produced by the method; may be {@code null}
   * @return the (possibly corrupted) value to return to the caller
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public Object afterMethodExit(
      final String className,
      final String methodName,
      final Class<?> returnType,
      final Object actualValue)
      throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.METHOD_EXIT,
            className,
            className,
            methodName,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    final RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null
        && decision.terminalAction().kind() == TerminalKind.CORRUPT_RETURN) {
      // Sleep BEFORE substituting the corrupted return. RuntimeDecision documents "delay first,
      // then gate, then terminal action" and CORRUPT_RETURN still returns normally (unlike
      // THROW), so a composed "delay + corrupt" scenario must deliver both effects. Previously
      // the early return skipped sleep(), silently dropping the timing signal operators were
      // testing for.
      sleep(decision.delayMillis());
      final ChaosEffect.ReturnValueCorruptionEffect corruptEffect =
          (ChaosEffect.ReturnValueCorruptionEffect) decision.terminalAction().returnValue();
      // Propagate the scenario id (stored on the TerminalAction at construction time in
      // terminalActionFor) so that corruption fallbacks like "EMPTY → ZERO" name the scenario
      // that fired rather than the literal string "method-exit".
      final String scenarioId = decision.terminalAction().scenarioId();
      return ReturnValueCorruptor.corrupt(
          corruptEffect.strategy(),
          returnType,
          actualValue,
          scenarioId != null ? scenarioId : "method-exit");
    }
    sleep(decision.delayMillis());
    return actualValue;
  }

  /**
   * Applies any active clock-skew scenarios to a raw clock reading and returns the (possibly)
   * skewed value.
   *
   * @param realValue the real clock value read from the JVM
   * @param clockType the {@link OperationType} identifying which clock source was read
   * @return the (possibly skewed) clock value to return to the caller
   */
  public long applyClockSkew(final long realValue, final OperationType clockType) {
    // Clock-skew reads must NOT go through registry.match() → ScenarioController.evaluate().
    // evaluate() increments matchedCount, consumes rate-limit permits, rolls the probability
    // die, and decrements maxApplications — all designed for application-controlled events
    // (sleeps, network reads, lock acquires), not for JVM-internal clock calls that fire
    // thousands of times per second. Routing through evaluate() would exhaust any activation
    // policy budget within milliseconds. Instead, iterate controllers directly: check started
    // + selector + ClockSkewEffect without touching policy accounting.
    final InvocationContext context =
        new InvocationContext(clockType, "java.lang.System", null, null, false, null, null, null);
    final boolean isNanos = clockType == OperationType.SYSTEM_CLOCK_NANOS;
    long result = realValue;
    for (final ScenarioController controller : registry.controllers()) {
      final ClockSkewState state = controller.clockSkewState();
      if (state == null) {
        continue;
      }
      if (!SelectorMatcher.matches(controller.scenario().selector(), context)) {
        continue;
      }
      final ChaosEffect.ClockSkewEffect skewEffect =
          (ChaosEffect.ClockSkewEffect) controller.scenario().effect();
      result =
          isNanos
              ? state.applyNanos(skewEffect.mode(), result)
              : state.applyMillis(skewEffect.mode(), result);
    }
    return result;
  }

  /**
   * Returns the (possibly skewed) millisecond timestamp for {@link System#currentTimeMillis()}.
   *
   * @param realMillis the real value returned by {@link System#currentTimeMillis()}
   * @return the (possibly skewed) millisecond timestamp to return to the caller
   */
  public long adjustClockMillis(final long realMillis) {
    return applyClockSkew(realMillis, OperationType.SYSTEM_CLOCK_MILLIS);
  }

  /**
   * Returns the (possibly skewed) nanosecond timestamp for {@link System#nanoTime()}.
   *
   * @param realNanos the real value returned by {@link System#nanoTime()}
   * @return the (possibly skewed) nanosecond timestamp to return to the caller
   */
  public long adjustClockNanos(final long realNanos) {
    return applyClockSkew(realNanos, OperationType.SYSTEM_CLOCK_NANOS);
  }

  /**
   * Returns the (possibly skewed) {@link java.time.Instant} for {@code Instant.now()}.
   *
   * @param realInstant the real instant produced by {@code Instant.now()}
   * @return the (possibly skewed) instant to return to the caller
   */
  public java.time.Instant adjustInstantNow(final java.time.Instant realInstant) {
    final long realMillis = realInstant.toEpochMilli();
    final long skewed = applyClockSkew(realMillis, OperationType.INSTANT_NOW);
    final long delta = skewed - realMillis;
    return delta == 0L ? realInstant : realInstant.plusMillis(delta);
  }

  /**
   * Returns the (possibly skewed) {@link java.time.LocalDateTime} for {@code LocalDateTime.now()}.
   *
   * @param realValue the real local-date-time produced by {@code LocalDateTime.now()}
   * @return the (possibly skewed) local-date-time to return to the caller
   */
  public java.time.LocalDateTime adjustLocalDateTimeNow(final java.time.LocalDateTime realValue) {
    final java.time.ZoneId zone = java.time.ZoneId.systemDefault();
    final long realMillis = realValue.atZone(zone).toInstant().toEpochMilli();
    final long skewed = applyClockSkew(realMillis, OperationType.LOCAL_DATE_TIME_NOW);
    if (skewed == realMillis) {
      return realValue;
    }
    return java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(skewed), zone);
  }

  /**
   * Returns the (possibly skewed) {@link java.time.ZonedDateTime} for {@code ZonedDateTime.now()}.
   *
   * @param realValue the real zoned-date-time produced by {@code ZonedDateTime.now()}
   * @return the (possibly skewed) zoned-date-time to return to the caller
   */
  public java.time.ZonedDateTime adjustZonedDateTimeNow(final java.time.ZonedDateTime realValue) {
    final long realMillis = realValue.toInstant().toEpochMilli();
    final long skewed = applyClockSkew(realMillis, OperationType.ZONED_DATE_TIME_NOW);
    if (skewed == realMillis) {
      return realValue;
    }
    return java.time.Instant.ofEpochMilli(skewed).atZone(realValue.getZone());
  }

  /**
   * Returns the (possibly skewed) millis value used when constructing a new {@link java.util.Date}.
   *
   * @param realMillis the real millis argument passed to {@code new Date(long)}
   * @return the (possibly skewed) millis value to use for the constructed {@link java.util.Date}
   */
  public long adjustDateNew(final long realMillis) {
    return applyClockSkew(realMillis, OperationType.DATE_NEW);
  }

  /**
   * Called before {@code System.gc()}; returns {@code true} to suppress the GC request.
   *
   * @return {@code true} to suppress the real {@code System.gc()} call, {@code false} to let it
   *     proceed
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public boolean beforeGcRequest() throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.SYSTEM_GC_REQUEST,
            "java.lang.System",
            null,
            null,
            false,
            null,
            null,
            null);
    final RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      final TerminalAction terminalAction = decision.terminalAction();
      if (terminalAction.kind() == TerminalKind.THROW) {
        throw terminalAction.throwable();
      }
      if (terminalAction.kind() == TerminalKind.SUPPRESS) {
        return true;
      }
    }
    sleep(decision.delayMillis());
    return false;
  }

  /**
   * Called before {@code System.exit(status)} to apply scenarios that may suppress or delay the
   * exit.
   *
   * @param status the exit status passed to {@code System.exit(int)}
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public void beforeExitRequest(final int status) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.SYSTEM_EXIT_REQUEST,
            "java.lang.System",
            null,
            Integer.toString(status),
            false,
            null,
            null,
            null);
    applyPreDecision(evaluate(context));
  }

  /**
   * Called before {@link java.lang.reflect.Method#invoke(Object, Object...)} to apply matching
   * scenarios.
   *
   * @param method the {@link java.lang.reflect.Method} or {@link java.lang.reflect.Constructor}
   *     being invoked; may be {@code null}
   * @param target the receiver of the reflective invocation (static methods pass {@code null})
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public void beforeReflectionInvoke(final Object method, final Object target) throws Throwable {
    final String methodName;
    final String declaringClass;
    if (method instanceof java.lang.reflect.Method reflectMethod) {
      methodName = reflectMethod.getName();
      declaringClass = reflectMethod.getDeclaringClass().getName();
    } else if (method instanceof java.lang.reflect.Constructor<?> ctor) {
      methodName = "<init>";
      declaringClass = ctor.getDeclaringClass().getName();
    } else {
      methodName = null;
      declaringClass = method == null ? "java.lang.reflect.Method" : method.getClass().getName();
    }
    final InvocationContext context =
        new InvocationContext(
            OperationType.REFLECTION_INVOKE,
            declaringClass,
            target == null ? null : target.getClass().getName(),
            methodName,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  /**
   * Called before {@link java.nio.ByteBuffer#allocateDirect(int)} to apply direct-buffer scenarios.
   *
   * @param capacity the capacity (in bytes) requested by the caller
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public void beforeDirectBufferAllocate(final int capacity) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.DIRECT_BUFFER_ALLOCATE,
            "java.nio.ByteBuffer",
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  /**
   * Called before {@link java.io.ObjectInputStream#readObject()} to apply deserialization
   * scenarios.
   *
   * @param stream the {@link java.io.ObjectInputStream} performing the deserialization; may be
   *     {@code null}
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public void beforeObjectDeserialize(final Object stream) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.OBJECT_DESERIALIZE,
            stream == null ? "java.io.ObjectInputStream" : stream.getClass().getName(),
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  /**
   * Called before {@code ClassLoader.defineClass(...)} to apply class-definition scenarios.
   *
   * @param loader the loader performing the definition; {@code null} for the bootstrap loader
   * @param className the binary name of the class being defined
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public void beforeClassDefine(final Object loader, final String className) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.CLASS_DEFINE,
            loader == null ? "bootstrap" : loader.getClass().getName(),
            null,
            className,
            false,
            null,
            null,
            null);
    applyPreDecision(evaluate(context));
  }

  /**
   * Called before monitor entry (AQS acquire) to apply monitor-contention scenarios.
   *
   * @param lock the monitor or synchronizer being acquired; may be {@code null} when the advice
   *     site cannot bind the receiver
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public void beforeMonitorEnter(final Object lock) throws Throwable {
    // When Byte Buddy cannot bind `@Advice.This` (e.g. static synchronized blocks advised via a
    // proxy site, or interception points where the receiver is not a stack slot), `lock` is null
    // and we fall back to the synchronizer hierarchy as a coarse default so MonitorSelector still
    // has *something* to filter on.
    final String targetClassName =
        lock == null
            ? "java.util.concurrent.locks.AbstractQueuedSynchronizer"
            : lock.getClass().getName();
    final InvocationContext context =
        new InvocationContext(
            OperationType.MONITOR_ENTER,
            targetClassName,
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  /**
   * Called before {@link java.util.concurrent.locks.LockSupport#park()} variants to apply
   * scenarios.
   *
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public void beforeThreadPark() throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.THREAD_PARK,
            "java.util.concurrent.locks.LockSupport",
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  /**
   * Called before {@link java.nio.channels.Selector#select()}; returns {@code true} to force a
   * spurious wakeup.
   *
   * @param selector the NIO selector being invoked
   * @param timeoutMillis the requested select timeout in milliseconds
   * @return {@code true} to force a spurious wakeup; {@code false} for normal execution
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public boolean beforeNioSelect(final Object selector, final long timeoutMillis) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.NIO_SELECTOR_SELECT,
            selector == null ? "java.nio.channels.Selector" : selector.getClass().getName(),
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    final RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      final TerminalAction terminalAction = decision.terminalAction();
      if (terminalAction.kind() == TerminalKind.THROW) {
        throw terminalAction.throwable();
      }
      if (terminalAction.kind() == TerminalKind.SUPPRESS) {
        return true;
      }
    }
    sleep(decision.delayMillis());
    return false;
  }

  /**
   * Called before a NIO channel read/write/connect/accept to apply scenarios targeting that
   * operation.
   *
   * @param operation the {@link OperationType} name for the channel operation
   * @param channel the NIO channel being operated on
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public void beforeNioChannelOp(final String operation, final Object channel) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.valueOf(operation),
            channel == null ? "java.nio.channels.Channel" : channel.getClass().getName(),
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  /**
   * Called before {@link java.net.Socket#connect(java.net.SocketAddress, int)} to apply socket
   * scenarios.
   *
   * @param socket the socket being connected
   * @param socketAddress the remote socket address being targeted
   * @param timeoutMillis the connect timeout in milliseconds
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public void beforeSocketConnect(
      final Object socket, final Object socketAddress, final int timeoutMillis) throws Throwable {
    final String remoteHost = extractRemoteHost(socketAddress);
    final InvocationContext context =
        new InvocationContext(
            OperationType.SOCKET_CONNECT,
            socket == null ? "java.net.Socket" : socket.getClass().getName(),
            null,
            remoteHost,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  /**
   * Called before {@link java.net.ServerSocket#accept()} to apply server-accept scenarios.
   *
   * @param serverSocket the server socket performing the accept
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public void beforeSocketAccept(final Object serverSocket) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.SOCKET_ACCEPT,
            serverSocket == null ? "java.net.ServerSocket" : serverSocket.getClass().getName(),
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  /**
   * Called before a socket input-stream read to apply scenarios targeting socket reads.
   *
   * @param stream the socket input stream being read from
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public void beforeSocketRead(final Object stream) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.SOCKET_READ,
            stream == null ? "java.net.SocketInputStream" : stream.getClass().getName(),
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  /**
   * Called before a socket output-stream write to apply scenarios targeting socket writes.
   *
   * @param stream the socket output stream being written to
   * @param len the number of bytes being written
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public void beforeSocketWrite(final Object stream, final int len) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.SOCKET_WRITE,
            stream == null ? "java.net.SocketOutputStream" : stream.getClass().getName(),
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  /**
   * Called before {@link java.net.Socket#close()} to apply socket-close scenarios.
   *
   * @param socket the socket being closed
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public void beforeSocketClose(final Object socket) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.SOCKET_CLOSE,
            socket == null ? "java.net.Socket" : socket.getClass().getName(),
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  /**
   * Called before {@link javax.naming.InitialContext#lookup(String)} to apply JNDI scenarios.
   *
   * @param context the JNDI context performing the lookup
   * @param name the JNDI name being looked up
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public void beforeJndiLookup(final Object context, final String name) throws Throwable {
    final InvocationContext invocationContext =
        new InvocationContext(
            OperationType.JNDI_LOOKUP,
            context == null ? "javax.naming.InitialContext" : context.getClass().getName(),
            null,
            name,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(invocationContext));
  }

  /**
   * Called before {@link java.io.ObjectOutputStream#writeObject(Object)} to apply serialization
   * scenarios.
   *
   * @param stream the object output stream performing the write
   * @param obj the object being serialized
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public void beforeObjectSerialize(final Object stream, final Object obj) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.OBJECT_SERIALIZE,
            stream == null ? "java.io.ObjectOutputStream" : stream.getClass().getName(),
            obj == null ? null : obj.getClass().getName(),
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  /**
   * Called before {@code System.loadLibrary} / {@code System.load} to apply native-library
   * scenarios.
   *
   * @param libraryName the name of the native library being loaded
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public void beforeNativeLibraryLoad(final String libraryName) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.NATIVE_LIBRARY_LOAD,
            "java.lang.System",
            null,
            libraryName,
            false,
            null,
            null,
            null);
    applyPreDecision(evaluate(context));
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
    final InvocationContext context =
        new InvocationContext(
            OperationType.ASYNC_CANCEL,
            CompletableFuture.class.getName(),
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    final RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      final TerminalAction terminalAction = decision.terminalAction();
      if (terminalAction.kind() == TerminalKind.THROW) {
        throw terminalAction.throwable();
      }
      if (terminalAction.kind() == TerminalKind.SUPPRESS) {
        return true;
      }
      // COMPLETE_EXCEPTIONALLY is documented as "complete a CompletableFuture exceptionally with
      // the enclosed throwable" — the ASYNC_CANCEL site is the canonical call site for that
      // semantic. Without this branch the scenario's terminal action would be silently dropped
      // and the cancel() would proceed normally, contradicting the plan.
      if (terminalAction.kind() == TerminalKind.COMPLETE_EXCEPTIONALLY
          && future instanceof CompletableFuture<?> completable) {
        completable.completeExceptionally(terminalAction.throwable());
        // Report "cancel succeeded" so the call site short-circuits and does not invoke the
        // real cancel (which would race against our completeExceptionally).
        return true;
      }
    }
    sleep(decision.delayMillis());
    return false;
  }

  /**
   * Called before {@code Inflater.inflate(...)} to apply decompression scenarios.
   *
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public void beforeZipInflate() throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.ZIP_INFLATE,
            "java.util.zip.Inflater",
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  /**
   * Called before {@code Deflater.deflate(...)} to apply compression scenarios.
   *
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public void beforeZipDeflate() throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.ZIP_DEFLATE,
            "java.util.zip.Deflater",
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  /**
   * Called before {@link ThreadLocal#get()}; returns {@code true} to suppress the get and return
   * {@code null}.
   *
   * @param threadLocal the {@link ThreadLocal} being accessed
   * @return {@code true} to suppress the get and return {@code null}; {@code false} for normal
   *     execution
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public boolean beforeThreadLocalGet(final Object threadLocal) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.THREAD_LOCAL_GET,
            threadLocal == null ? "java.lang.ThreadLocal" : threadLocal.getClass().getName(),
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    final RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      final TerminalAction terminalAction = decision.terminalAction();
      if (terminalAction.kind() == TerminalKind.THROW) {
        throw terminalAction.throwable();
      }
      if (terminalAction.kind() == TerminalKind.SUPPRESS) {
        return true;
      }
    }
    sleep(decision.delayMillis());
    return false;
  }

  /**
   * Called before {@link ThreadLocal#set(Object)}; returns {@code true} to suppress the set.
   *
   * @param threadLocal the {@link ThreadLocal} being mutated
   * @param value the value being assigned
   * @return {@code true} to suppress the set; {@code false} for normal execution
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public boolean beforeThreadLocalSet(final Object threadLocal, final Object value)
      throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.THREAD_LOCAL_SET,
            threadLocal == null ? "java.lang.ThreadLocal" : threadLocal.getClass().getName(),
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    final RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      final TerminalAction terminalAction = decision.terminalAction();
      if (terminalAction.kind() == TerminalKind.THROW) {
        throw terminalAction.throwable();
      }
      if (terminalAction.kind() == TerminalKind.SUPPRESS) {
        return true;
      }
    }
    sleep(decision.delayMillis());
    return false;
  }

  /**
   * Called before {@code MBeanServer.invoke(...)} to apply JMX invoke scenarios.
   *
   * @param server the {@code MBeanServer} receiving the invocation
   * @param objectName the target MBean {@code ObjectName}
   * @param operationName the name of the JMX operation being invoked
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public void beforeJmxInvoke(
      final Object server, final Object objectName, final String operationName) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.JMX_INVOKE,
            server == null ? "javax.management.MBeanServer" : server.getClass().getName(),
            null,
            operationName,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  /**
   * Called before {@code MBeanServer.getAttribute(...)} to apply JMX attribute-read scenarios.
   *
   * @param server the {@code MBeanServer} receiving the attribute read
   * @param objectName the target MBean {@code ObjectName}
   * @param attribute the attribute name being read
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public void beforeJmxGetAttr(final Object server, final Object objectName, final String attribute)
      throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.JMX_GET_ATTR,
            server == null ? "javax.management.MBeanServer" : server.getClass().getName(),
            null,
            attribute,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  /**
   * Called before an HTTP client send; returns {@code true} to suppress the call.
   *
   * @param url the target URL of the HTTP request
   * @param opType the specific {@link OperationType} for this send
   * @return {@code true} to suppress the call; {@code false} for normal execution
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public boolean beforeHttpSend(final String url, final OperationType opType) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            opType, "http.client", null, url, false, null, null, scopeContext.currentSessionId());
    final RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      final TerminalAction terminalAction = decision.terminalAction();
      if (terminalAction.kind() == TerminalKind.THROW) {
        throw terminalAction.throwable();
      }
      if (terminalAction.kind() == TerminalKind.SUPPRESS) {
        sleep(decision.delayMillis());
        return true;
      }
    }
    sleep(decision.delayMillis());
    return false;
  }

  /**
   * Called before acquiring a JDBC connection from a pool; returns {@code true} to suppress the
   * acquire.
   *
   * @param poolName the name of the JDBC connection pool
   * @return {@code true} to suppress the acquire; {@code false} for normal execution
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public boolean beforeJdbcConnectionAcquire(final String poolName) throws Throwable {
    return evaluateJdbc(OperationType.JDBC_CONNECTION_ACQUIRE, "jdbc.pool", poolName);
  }

  /**
   * Called before executing a JDBC {@link java.sql.Statement}; returns {@code true} to suppress the
   * call.
   *
   * @param sql the SQL statement being executed
   * @return {@code true} to suppress the call; {@code false} for normal execution
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public boolean beforeJdbcStatementExecute(final String sql) throws Throwable {
    return evaluateJdbc(OperationType.JDBC_STATEMENT_EXECUTE, "java.sql.Statement", snippet(sql));
  }

  /**
   * Called before preparing a JDBC {@link java.sql.PreparedStatement}; returns {@code true} to
   * suppress the call.
   *
   * @param sql the SQL being prepared
   * @return {@code true} to suppress the call; {@code false} for normal execution
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public boolean beforeJdbcPreparedStatement(final String sql) throws Throwable {
    return evaluateJdbc(OperationType.JDBC_PREPARED_STATEMENT, "java.sql.Connection", snippet(sql));
  }

  /**
   * Called before a JDBC transaction commit; returns {@code true} to suppress the commit.
   *
   * @return {@code true} to suppress the commit; {@code false} for normal execution
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public boolean beforeJdbcTransactionCommit() throws Throwable {
    return evaluateJdbc(OperationType.JDBC_TRANSACTION_COMMIT, "java.sql.Connection", null);
  }

  /**
   * Called before a JDBC transaction rollback; returns {@code true} to suppress the rollback.
   *
   * @return {@code true} to suppress the rollback; {@code false} for normal execution
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public boolean beforeJdbcTransactionRollback() throws Throwable {
    return evaluateJdbc(OperationType.JDBC_TRANSACTION_ROLLBACK, "java.sql.Connection", null);
  }

  /**
   * Called before {@link Thread#sleep(long)}.
   *
   * @param millis the requested sleep duration
   * @return {@code true} if the sleep should be suppressed (caller returns immediately); {@code
   *     false} for normal execution
   * @throws Throwable if an active scenario throws to simulate an {@link InterruptedException}
   */
  public boolean beforeThreadSleep(final long millis) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.THREAD_SLEEP,
            "java.lang.Thread",
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    final RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      final TerminalAction terminalAction = decision.terminalAction();
      if (terminalAction.kind() == TerminalKind.THROW) {
        throw terminalAction.throwable();
      }
      if (terminalAction.kind() == TerminalKind.SUPPRESS) {
        return true;
      }
    }
    sleep(decision.delayMillis());
    return false;
  }

  /**
   * Called before {@link java.net.InetAddress#getByName(String)}, {@link
   * java.net.InetAddress#getAllByName(String)}, or {@link java.net.InetAddress#getLocalHost()}.
   *
   * @param hostname the hostname being resolved; {@code null} for {@code getLocalHost()}
   * @throws Throwable if an active scenario throws to simulate a DNS failure
   */
  public void beforeDnsResolve(final String hostname) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.DNS_RESOLVE,
            "java.net.InetAddress",
            null,
            hostname,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  /**
   * Called before {@link javax.net.ssl.SSLSocket#startHandshake()} or {@link
   * javax.net.ssl.SSLEngine#beginHandshake()}.
   *
   * @param socket the {@code SSLSocket} or {@code SSLEngine} instance
   * @throws Throwable if an active scenario throws to simulate a TLS handshake failure
   */
  public void beforeSslHandshake(final Object socket) throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            OperationType.SSL_HANDSHAKE,
            socket == null ? "javax.net.ssl.SSLSocket" : socket.getClass().getName(),
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  /**
   * Called before a {@link java.io.FileInputStream#read} or {@link java.io.FileOutputStream#write}
   * call.
   *
   * @param operation {@code "FILE_IO_READ"} or {@code "FILE_IO_WRITE"}
   * @param stream the stream instance
   * @throws Throwable if an active scenario throws to simulate an I/O failure
   */
  public void beforeFileIo(final String operation, final Object stream) throws Throwable {
    final OperationType fileIoOpType = resolveFileIoOperationType(operation);
    final InvocationContext context =
        new InvocationContext(
            fileIoOpType,
            stream == null ? "java.io.FileInputStream" : stream.getClass().getName(),
            null,
            null,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    applyPreDecision(evaluate(context));
  }

  private static OperationType resolveFileIoOperationType(final String operationTag) {
    if ("FILE_IO_READ".equals(operationTag)) {
      return OperationType.FILE_IO_READ;
    }
    if ("FILE_IO_WRITE".equals(operationTag)) {
      return OperationType.FILE_IO_WRITE;
    }
    throw new IllegalArgumentException(
        "Unknown file I/O operation tag '"
            + operationTag
            + "'; expected FILE_IO_READ or FILE_IO_WRITE");
  }

  private boolean evaluateJdbc(
      final OperationType opType, final String targetClassName, final String targetName)
      throws Throwable {
    final InvocationContext context =
        new InvocationContext(
            opType,
            targetClassName,
            null,
            targetName,
            false,
            null,
            null,
            scopeContext.currentSessionId());
    final RuntimeDecision decision = evaluate(context);
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      final TerminalAction terminalAction = decision.terminalAction();
      if (terminalAction.kind() == TerminalKind.THROW) {
        throw terminalAction.throwable();
      }
      if (terminalAction.kind() == TerminalKind.SUPPRESS) {
        sleep(decision.delayMillis());
        return true;
      }
    }
    sleep(decision.delayMillis());
    return false;
  }

  private static String snippet(final String sql) {
    if (sql == null) {
      return null;
    }
    return sql.length() <= 200 ? sql : sql.substring(0, 200);
  }

  private RuntimeDecision evaluate(final InvocationContext context) {
    final List<ScenarioContribution> contributions = registry.match(context);
    if (contributions.isEmpty()) {
      return RuntimeDecision.none();
    }
    long delayMillis = 0L;
    GateAction gateAction = null;
    int gatePrecedence = Integer.MIN_VALUE;
    TerminalAction terminalAction = null;
    int terminalPrecedence = Integer.MIN_VALUE;
    for (final ScenarioContribution contribution : contributions) {
      // Saturating add: naive += with two large per-scenario delays would wrap to a negative
      // value, and the sleep() helper treats negative as "skip sleep" — silently turning an
      // intended multi-minute delay into no delay at all is exactly the kind of quiet bug we
      // should not ship. Cap at Long.MAX_VALUE instead so the intent ("a very long delay") is
      // preserved; downstream Thread.sleep still accepts it.
      final long contributionDelay = contribution.delayMillis();
      if (contributionDelay > 0L && delayMillis > Long.MAX_VALUE - contributionDelay) {
        delayMillis = Long.MAX_VALUE;
      } else {
        delayMillis += contributionDelay;
      }
      // Tie-break rule for both gate and terminal: the ScenarioRegistry sorts contributions by
      // precedence DESC then id ASC, so on strict `>` the first-iterated (highest precedence,
      // lowest id within that precedence) wins and subsequent equal-precedence candidates do
      // not overwrite it. Using `>=` inverted the id tie-break — the highest-id scenario won,
      // contradicting the class javadoc and quietly swapping which of two composed scenarios
      // applied at runtime.
      if (contribution.effect() instanceof ChaosEffect.GateEffect
          && contribution.scenario().precedence() > gatePrecedence) {
        gateAction = new GateAction(contribution.controller().gate(), contribution.gateTimeout());
        gatePrecedence = contribution.scenario().precedence();
      }
      final TerminalAction candidate =
          terminalActionFor(
              context.operationType(), contribution.effect(), contribution.scenario());
      if (candidate != null && contribution.scenario().precedence() > terminalPrecedence) {
        terminalAction = candidate;
        terminalPrecedence = contribution.scenario().precedence();
      }
    }
    return new RuntimeDecision(delayMillis, gateAction, terminalAction);
  }

  private TerminalAction terminalActionFor(
      final OperationType operationType, final ChaosEffect effect, final ChaosScenario scenario) {
    if (effect instanceof ChaosEffect.RejectEffect rejectEffect) {
      return rejectTerminal(operationType, rejectEffect.message());
    }
    if (effect instanceof ChaosEffect.SuppressEffect) {
      return suppressTerminal(operationType);
    }
    if (effect instanceof ChaosEffect.ExceptionalCompletionEffect exceptionalCompletionEffect) {
      return new TerminalAction(
          TerminalKind.COMPLETE_EXCEPTIONALLY,
          null,
          FailureFactory.completionFailure(
              exceptionalCompletionEffect.failureKind(), exceptionalCompletionEffect.message()));
    }
    if (effect instanceof ChaosEffect.ExceptionInjectionEffect injectionEffect) {
      return buildInjectedExceptionTerminal(injectionEffect);
    }
    if (effect instanceof ChaosEffect.ReturnValueCorruptionEffect corruptEffect) {
      return new TerminalAction(TerminalKind.CORRUPT_RETURN, corruptEffect, null, scenario.id());
    }
    if (effect instanceof ChaosEffect.SpuriousWakeupEffect) {
      // A spurious selector wakeup semantically means: return from select() immediately with
      // zero ready keys instead of blocking. beforeNioSelect already implements that exact
      // shape as its SUPPRESS terminal path (return true → advice substitutes `return 0`), so
      // route SpuriousWakeupEffect through SUPPRESS rather than introducing a redundant kind.
      // CompatibilityValidator restricts this effect to NioSelector + NIO_SELECTOR_SELECT, so
      // no other dispatch path can observe this terminal here.
      return suppressTerminal(operationType);
    }
    return null;
  }

  private TerminalAction buildInjectedExceptionTerminal(
      final ChaosEffect.ExceptionInjectionEffect effect) {
    try {
      // Class.forName(name, initialize=false, loader): deliberately skip static initialisation.
      // Running <clinit> of an attacker-chosen class during exception construction would turn
      // the injection path into a class-loading gadget. The package allow-list in
      // ExceptionInjectionEffect is the primary gate; this is defence in depth.
      final Class<?> exClass =
          Class.forName(effect.exceptionClassName(), false, ChaosDispatcher.class.getClassLoader());
      if (!Throwable.class.isAssignableFrom(exClass)) {
        return new TerminalAction(
            TerminalKind.THROW,
            null,
            new IllegalArgumentException(
                "chaos-agent: configured exception class does not extend Throwable: "
                    + effect.exceptionClassName()));
      }
      Throwable instance;
      try {
        final java.lang.reflect.Constructor<?> stringCtor =
            exClass.getDeclaredConstructor(String.class);
        stringCtor.setAccessible(true);
        instance = (Throwable) stringCtor.newInstance(effect.message());
      } catch (final NoSuchMethodException noMsg) {
        // Fallback: class has no (String) constructor. Silently dropping effect.message() here
        // made injected exceptions opaque in logs — operators could not tell which scenario
        // fired. If a message was configured, thread it through initCause so the message text
        // survives (visible via getCause().getMessage() in stack traces). With no message the
        // cause stays null and the exception behaves as before.
        final java.lang.reflect.Constructor<?> noArgCtor = exClass.getDeclaredConstructor();
        noArgCtor.setAccessible(true);
        instance = (Throwable) noArgCtor.newInstance();
        final String msg = effect.message();
        if (msg != null && !msg.isEmpty()) {
          try {
            instance.initCause(new RuntimeException(msg));
          } catch (final IllegalStateException alreadyInitialised) {
            // Some Throwable subclasses pre-initialise cause in their no-arg ctor; respect
            // that rather than overwriting. Opacity remains in that case, but we did our best.
          }
        }
      }
      if (!effect.withStackTrace()) {
        instance.setStackTrace(new StackTraceElement[0]);
      }
      return new TerminalAction(TerminalKind.THROW, null, instance);
    } catch (final ReflectiveOperationException reflective) {
      // Preserve the cause chain so operators can see *why* instantiation failed
      // (missing class, inaccessible ctor, ctor threw, etc.) without having to reproduce.
      final RuntimeException wrapped =
          new RuntimeException(
              "chaos-agent: failed to instantiate " + effect.exceptionClassName(), reflective);
      return new TerminalAction(TerminalKind.THROW, null, wrapped);
    }
  }

  private TerminalAction rejectTerminal(final OperationType operationType, final String message) {
    return switch (operationType) {
      case QUEUE_OFFER, ASYNC_COMPLETE, ASYNC_COMPLETE_EXCEPTIONALLY ->
          new TerminalAction(TerminalKind.RETURN, Boolean.FALSE, null);
      case RESOURCE_LOAD -> new TerminalAction(TerminalKind.RETURN, null, null);
      default ->
          new TerminalAction(
              TerminalKind.THROW, null, FailureFactory.reject(operationType, message));
    };
  }

  private TerminalAction suppressTerminal(final OperationType operationType) {
    return switch (operationType) {
      case THREAD_START, VIRTUAL_THREAD_START ->
          new TerminalAction(
              TerminalKind.THROW,
              null,
              new RejectedExecutionException("thread start suppressed by chaos agent"));
      case SYSTEM_EXIT_REQUEST ->
          new TerminalAction(
              TerminalKind.THROW, null, new SecurityException("exit suppressed by chaos agent"));
      case QUEUE_OFFER, ASYNC_COMPLETE, ASYNC_COMPLETE_EXCEPTIONALLY ->
          new TerminalAction(TerminalKind.RETURN, Boolean.FALSE, null);
      // QUEUE_POLL: routed through beforeBooleanQueueOperation which returns the Boolean to the
      // advice. A non-null return triggers PollAdvice's skipOn = OnNonDefaultValue and the real
      // poll() is skipped — the skipped body's default reference return is null, which is the
      // intended SUPPRESS semantics ("queue returned nothing"). Boolean.FALSE is used only as a
      // non-null sentinel; its boolean value never reaches the caller.
      case QUEUE_POLL -> new TerminalAction(TerminalKind.RETURN, Boolean.FALSE, null);
      case RESOURCE_LOAD -> new TerminalAction(TerminalKind.RETURN, null, null);
      default -> new TerminalAction(TerminalKind.SUPPRESS, null, null);
    };
  }

  private void applyPreDecision(final RuntimeDecision decision) throws Throwable {
    applyGate(decision.gateAction());
    if (decision.terminalAction() != null) {
      final TerminalAction terminalAction = decision.terminalAction();
      if (terminalAction.kind() == TerminalKind.THROW) {
        throw terminalAction.throwable();
      }
      if (terminalAction.kind() == TerminalKind.RETURN) {
        if (Boolean.FALSE.equals(terminalAction.returnValue())) {
          throw new RejectedExecutionException("operation suppressed by chaos agent");
        }
        return;
      }
      if (terminalAction.kind() == TerminalKind.SUPPRESS) {
        sleep(decision.delayMillis());
        return;
      }
      if (terminalAction.kind() == TerminalKind.CORRUPT_RETURN) {
        // CORRUPT_RETURN is only meaningful once a return value exists — it is applied by
        // afterMethodExit, never here. Reaching this point means an effect or policy paired
        // a corrupt-return terminal with a pre-invocation operation type; silently swallowing
        // it would hide the misconfiguration and the scenario would appear to be a harmless
        // no-op. Fail loud so the invariant is enforced and the bug gets attributed to the
        // plan rather than the runtime.
        throw new IllegalStateException(
            "CORRUPT_RETURN terminal reached applyPreDecision — only valid from afterMethodExit");
      }
    }
    sleep(decision.delayMillis());
  }

  private void applyGate(final GateAction gateAction) throws InterruptedException {
    if (gateAction != null) {
      gateAction.gate().await(gateAction.maxBlock());
    }
  }

  private void sleep(final long delayMillis) {
    if (delayMillis <= 0L) {
      return;
    }
    try {
      Thread.sleep(delayMillis);
    } catch (final InterruptedException interruptedException) {
      // Restore the interrupt flag and return. Wrapping this as IllegalStateException would break
      // the caller's interrupt contract: any subsequent blocking call they make will throw
      // InterruptedException anyway, which is the idiomatic signal that the thread should unwind.
      // A chaos-induced delay being cut short is not itself an error.
      Thread.currentThread().interrupt();
    }
  }

  private RuntimeException propagate(final Throwable throwable) {
    if (throwable instanceof RuntimeException runtimeException) {
      return runtimeException;
    }
    // Gate blocking inside applyPreDecision() can unwind as InterruptedException — for example
    // an operator scenario that parks the caller until an external signal arrives, then a thread
    // pool shutdown interrupts the worker. The previous implementation wrapped it as
    // IllegalStateException and silently dropped the interrupt bit: the worker then unwound with
    // the "chaos interception failed" exception but flag-clear, so the next blocking call
    // (Thread.sleep, Object.wait, Condition.await, …) would sit forever instead of honouring
    // the shutdown. Restore the interrupt flag before wrapping so subsequent blocking calls see
    // it; we still wrap in a RuntimeException because callers advised by Byte Buddy cannot
    // propagate a checked exception without advice-level `throws` declarations we don't want
    // to require everywhere.
    if (throwable instanceof InterruptedException) {
      Thread.currentThread().interrupt();
    }
    return new IllegalStateException("chaos interception failed", throwable);
  }

  /**
   * Strips the JVM-generated synthetic lambda suffix ({@code $$Lambda$N/0x...}) from a class name
   * so that {@code EXACT} and {@code GLOB} task-class patterns work transparently for lambda tasks.
   * Without stripping, a task defined as a lambda in {@code com.example.MyService} has the name
   * {@code com.example.MyService$$Lambda$14/0x00000007004a1c40}, which defeats exact patterns.
   */
  static String taskClassName(final Object task) {
    if (task == null) {
      return null;
    }
    final String fullName = task.getClass().getName();
    final int lambdaSuffixIndex = fullName.indexOf("$$Lambda$");
    return lambdaSuffixIndex == -1 ? fullName : fullName.substring(0, lambdaSuffixIndex);
  }

  private static String extractRemoteHost(final Object socketAddress) {
    if (socketAddress instanceof InetSocketAddress inetSocketAddress) {
      final String host = inetSocketAddress.getHostString();
      return host != null ? host : inetSocketAddress.toString();
    }
    return socketAddress == null ? null : socketAddress.toString();
  }
}
