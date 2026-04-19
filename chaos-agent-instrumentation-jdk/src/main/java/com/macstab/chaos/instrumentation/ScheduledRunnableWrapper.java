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
    try {
      if (BootstrapDispatcher.beforeScheduledTick(executor, delegate, periodic)) {
        delegate.run();
      }
    } catch (Throwable throwable) {
      if (periodic) {
        // ScheduledExecutorService contract: any exception from a periodic task's run() method
        // permanently cancels all future executions with no notification. We refuse to let
        // chaos-injected exceptions silently kill the application's scheduled work. A
        // scenario that wants to kill a periodic task explicitly should use a different
        // effect (cancel, terminate scheduler) rather than propagating an exception here.
        LOGGER.log(
            Level.WARNING,
            "chaos: suppressing exception from periodic scheduled tick to preserve"
                + " periodicity (task="
                + delegate.getClass().getName()
                + ")",
            throwable);
      } else {
        sneakyThrow(throwable);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static <T extends Throwable> void sneakyThrow(final Throwable throwable) throws T {
    throw (T) throwable;
  }
}
