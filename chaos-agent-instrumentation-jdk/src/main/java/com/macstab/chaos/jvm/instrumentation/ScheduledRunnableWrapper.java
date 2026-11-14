package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import java.lang.ref.WeakReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wraps a {@link Runnable} scheduled on an executor so that chaos tick hooks fire around each
 * invocation.
 */
public final class ScheduledRunnableWrapper implements Runnable {

  // Weak reference to break the retention cycle between ScheduledThreadPoolExecutor and this
  // wrapper. The pool's internal queue holds the wrapper for the full periodic schedule; a
  // strong reference back to the pool would keep it alive even after the caller's last
  // ScheduledExecutorService handle goes out of scope. If the pool has been GC'd before the
  // wrapper fires, the chaos hook is skipped and the delegate runs unchanged — losing chaos
  // targeting precision beats leaking the pool.
  private final WeakReference<Object> executorRef;
  private final Runnable delegate;
  private final boolean periodic;

  /**
   * Creates a wrapper holding a weak reference to {@code executor}, the delegate runnable, and
   * periodicity flag.
   *
   * @param executor the scheduling executor; held via {@link WeakReference} to avoid retention
   * @param delegate the wrapped runnable to invoke after chaos hooks have been applied
   * @param periodic {@code true} if the task is scheduled to run repeatedly; {@code false} for
   *     one-shot tasks
   */
  public ScheduledRunnableWrapper(
      final Object executor, final Runnable delegate, final boolean periodic) {
    this.executorRef = new WeakReference<>(executor);
    this.delegate = delegate;
    this.periodic = periodic;
  }

  @Override
  public void run() {
    // Split the try scope in two: the chaos hook must not mask application exceptions.
    //
    // beforeScheduledTick's exceptions (chaos-injected) are swallowed for periodic tasks to
    // preserve cadence. delegate.run()'s exceptions are always propagated, keeping the
    // scheduler's self-cancellation contract (any exception from a periodic task permanently
    // cancels all future executions). A single try around both would swallow genuine
    // RuntimeException/Error from the application too: broken tasks would keep firing forever
    // and a health-check / metric worker that is supposed to page someone would keep
    // "succeeding" on schedule.
    final Object executor = executorRef.get();
    final boolean proceed;
    try {
      proceed =
          executor == null || BootstrapDispatcher.beforeScheduledTick(executor, delegate, periodic);
    } catch (final Throwable chaosFailure) {
      if (periodic) {
        // Restore the interrupt flag before swallowing: an InterruptedException from the chaos
        // hook clears the thread's interrupt bit. If we return without restoring it, the worker
        // thread's interrupt status is silently cleared and cooperative-cancellation signals
        // that fired during the chaos hook are lost for the rest of the task's lifetime.
        if (chaosFailure instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        // The agent's own logging: a handler's file write is no FILE_IO of the application.
        BootstrapDispatcher.beginInternalCall();
        try {
          // A bridge class (bootstrap class loader): JUL, not the agent's AgentLog. Looked up here,
          // not
          // in a static field, so loading the class does not initialise the LogManager.
          Logger.getLogger("com.macstab.chaos.jvm")
              .log(
                  Level.WARNING,
                  chaosFailure,
                  () ->
                      "[chaos-agent] suppressing chaos-injected exception from"
                          + " beforeScheduledTick to"
                          + " preserve periodicity (task="
                          + delegate.getClass().getName()
                          + ")");
        } finally {
          BootstrapDispatcher.endInternalCall();
        }
        return;
      }
      // Restore the interrupt flag before sneaky-rethrow: otherwise an InterruptedException
      // thrown by the chaos hook would clear the worker thread's interrupt bit and defeat
      // cooperative cancellation in the enclosing executor.
      if (chaosFailure instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      sneakyThrow(chaosFailure);
      return;
    }
    if (proceed) {
      delegate.run();
    }
  }

  @SuppressWarnings("unchecked")
  private static <T extends Throwable> void sneakyThrow(final Throwable throwable) throws T {
    throw (T) throwable;
  }
}
