package com.macstab.chaos.instrumentation;

import com.macstab.chaos.instrumentation.bridge.BootstrapDispatcher;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ScheduledRunnableWrapper implements Runnable {
  private static final Logger LOGGER = Logger.getLogger("com.macstab.chaos");

  private final Object executor;
  private final Runnable delegate;
  private final boolean periodic;

  public ScheduledRunnableWrapper(
      final Object executor, final Runnable delegate, final boolean periodic) {
    this.executor = executor;
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
    final boolean proceed;
    try {
      proceed = BootstrapDispatcher.beforeScheduledTick(executor, delegate, periodic);
    } catch (final Throwable chaosFailure) {
      if (periodic) {
        LOGGER.log(
            Level.WARNING,
            "chaos: suppressing chaos-injected exception from beforeScheduledTick to preserve"
                + " periodicity (task="
                + delegate.getClass().getName()
                + ")",
            chaosFailure);
        return;
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
