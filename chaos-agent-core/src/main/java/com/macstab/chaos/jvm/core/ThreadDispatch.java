package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.Internal;
import com.macstab.chaos.jvm.api.OperationType;
import org.jspecify.annotations.Nullable;

/**
 * Dispatch of thread operations (starts, sleeps, parks, monitor entries, thread-local access) and
 * of the session hand-off between threads.
 *
 * <p>One domain of the dispatch the instrumentation layer calls through the bootstrap bridge; the
 * evaluation it shares with the other domains is {@link DispatchEngine}. Not part of the stable API
 * (see {@link ChaosDispatcher}).
 */
@Internal
public final class ThreadDispatch {

  private final DispatchEngine engine;

  ThreadDispatch(final DispatchEngine engine) {
    this.engine = engine;
  }

  /**
   * Called before {@link Thread#start()} to apply any active chaos scenario matching the thread.
   *
   * @param thread the thread about to be started; may be {@code null}
   * @param poolWorker whether a thread pool is starting one of its workers: such a thread serves
   *     many tasks, each carrying its submitter's session, so it does not inherit the starter's
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public void beforeThreadStart(final @Nullable Thread thread, final boolean poolWorker)
      throws Throwable {
    final OperationType operation =
        engine.featureSet.isVirtualThread(thread)
            ? OperationType.VIRTUAL_THREAD_START
            : OperationType.THREAD_START;
    final String sessionId = engine.scopeContext.currentSessionId();
    if (!engine.idle(operation)) {
      engine.applyPreDecision(
          engine.evaluate(
              new InvocationContext(
                  operation,
                  Thread.class.getName(),
                  null,
                  thread == null ? null : thread.getName(),
                  false,
                  thread == null ? null : thread.isDaemon(),
                  thread == null ? null : engine.featureSet.isVirtualThread(thread),
                  sessionId)));
    }
    // The started thread runs in the starting thread's session (see enterSession), unless it is
    // a pool worker: a worker grown by a submission inside a session served every later task in
    // that session, leaking it to other tests and to requests without a session.
    if (!poolWorker) {
      engine.sessionHandoff.capture(thread, sessionId);
    }
  }

  /**
   * Records the current session for a fork-join task being submitted or forked, so that the task
   * runs in it.
   *
   * @param carrier the task
   */
  public void captureSession(final Object carrier) {
    engine.sessionHandoff.capture(carrier, engine.scopeContext.currentSessionId());
  }

  /**
   * Binds the session recorded for a task or thread that starts running.
   *
   * @param carrier the task or thread
   */
  public void enterSession(final Object carrier) {
    engine.sessionHandoff.enter(carrier, engine.scopeContext);
  }

  /**
   * Releases the session bound by {@link #enterSession} when the task or thread finishes.
   *
   * @param carrier the task or thread
   */
  public void exitSession(final Object carrier) {
    engine.sessionHandoff.exit(carrier);
  }

  /**
   * Called before monitor entry (AQS acquire) to apply monitor-contention scenarios.
   *
   * @param lock the monitor or synchronizer being acquired; may be {@code null} when the advice
   *     site cannot bind the receiver
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public void beforeMonitorEnter(final @Nullable Object lock) throws Throwable {
    // When Byte Buddy cannot bind `@Advice.This` (e.g. static synchronized blocks advised via a
    // proxy site, or interception points where the receiver is not a stack slot), `lock` is null
    // and we fall back to the synchronizer hierarchy as a coarse default so MonitorSelector still
    // has *something* to filter on.
    final String targetClassName =
        lock == null
            ? "java.util.concurrent.locks.AbstractQueuedSynchronizer"
            : lock.getClass().getName();
    engine.applyPreDecision(
        (engine.idle(OperationType.MONITOR_ENTER)
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    OperationType.MONITOR_ENTER,
                    targetClassName,
                    null,
                    null,
                    false,
                    null,
                    null,
                    engine.scopeContext.currentSessionId()))));
  }

  /**
   * Called before {@link java.util.concurrent.locks.LockSupport#park()} variants to apply
   * scenarios.
   *
   * @throws Throwable if a matching scenario's terminal action mandates it
   */
  public void beforeThreadPark() throws Throwable {
    engine.applyPreDecision(
        (engine.idle(OperationType.THREAD_PARK)
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    OperationType.THREAD_PARK,
                    "java.util.concurrent.locks.LockSupport",
                    null,
                    null,
                    false,
                    null,
                    null,
                    engine.scopeContext.currentSessionId()))));
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
    // Idle first; the agent's own ThreadLocals are never evaluated (L3).
    final RuntimeDecision decision =
        (engine.idle(OperationType.THREAD_LOCAL_GET) || threadLocal instanceof AgentOwned
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    OperationType.THREAD_LOCAL_GET,
                    threadLocal == null
                        ? "java.lang.ThreadLocal"
                        : threadLocal.getClass().getName(),
                    null,
                    null,
                    false,
                    null,
                    null,
                    engine.scopeContext.currentSessionId())));
    return engine.suppressed(decision);
  }

  /**
   * Called before {@link ThreadLocal#set(Object)}; returns {@code true} to suppress the set.
   *
   * @param threadLocal the {@link ThreadLocal} being mutated
   * @param value the value being assigned
   * @return {@code true} to suppress the set; {@code false} for normal execution
   * @throws Throwable if an active scenario throws to simulate a failure
   */
  public boolean beforeThreadLocalSet(final Object threadLocal, final @Nullable Object value)
      throws Throwable {
    // Idle first; the agent's own ThreadLocals are never evaluated (L3).
    final RuntimeDecision decision =
        (engine.idle(OperationType.THREAD_LOCAL_SET) || threadLocal instanceof AgentOwned
            ? RuntimeDecision.none()
            : engine.evaluate(
                new InvocationContext(
                    OperationType.THREAD_LOCAL_SET,
                    threadLocal == null
                        ? "java.lang.ThreadLocal"
                        : threadLocal.getClass().getName(),
                    null,
                    null,
                    false,
                    null,
                    null,
                    engine.scopeContext.currentSessionId())));
    return engine.suppressed(decision);
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
    // The sleeping thread is the current thread. Its name, daemon flag and kind are reported so
    // ThreadSelector filters (threadNamePattern, daemon, kind) apply to THREAD_SLEEP.
    if (engine.idle(OperationType.THREAD_SLEEP)) {
      return false;
    }
    final Thread current = Thread.currentThread();
    final RuntimeDecision decision =
        (engine.evaluate(
            new InvocationContext(
                OperationType.THREAD_SLEEP,
                "java.lang.Thread",
                null,
                current.getName(),
                false,
                current.isDaemon(),
                engine.featureSet.isVirtualThread(current),
                engine.scopeContext.currentSessionId())));
    return engine.suppressed(decision);
  }
}
