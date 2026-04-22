package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import java.lang.ref.WeakReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ScheduledRunnableWrapper implements Runnable {
  private static final Logger LOGGER = Logger.getLogger("com.macstab.chaos.jvm");

  // Weak reference to break the retention cycle between ScheduledThreadPoolExecutor and this
  // wrapper. The pool's internal queue holds the wrapper for the full periodic schedule; a
  // strong reference back to the pool would keep it alive even after the caller's last
  // ScheduledExecutorService handle goes out of scope. If the pool has been GC'd before the
  // wrapper fires, the chaos hook is skipped and the delegate runs unchanged — losing chaos
  // targeting precision beats leaking the pool.
  private final WeakReference<Object> executorRef;
  private final Runnable delegate;
  private final boolean periodic;

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
    // Before: a single try enclosed both beforeScheduledTick() and delegate.run(). When the
    // task was periodic, the catch swallowed EVERY throwable — including genuine
    // RuntimeException/Error from the application's own delegate.run(). The scheduler's
    // self-cancellation contract (any exception from a periodic task permanently cancels all
    // future executions) was silently defeated: broken tasks kept firing forever, operator
    // dashboards missed the failure, and the health-check / metric worker that was supposed
    // to page someone kept "succeeding" on schedule.
    //
    // After: beforeScheduledTick's exceptions (chaos-injected) are swallowed for periodic
    // tasks to preserve cadence — the original intent. But delegate.run()'s exceptions are
    // always propagated, restoring the JDK contract for real application failures.
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
        LOGGER.log(
            Level.WARNING,
            "chaos: suppressing chaos-injected exception from beforeScheduledTick to preserve"
                + " periodicity (task="
                + delegate.getClass().getName()
                + ")",
            chaosFailure);
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
