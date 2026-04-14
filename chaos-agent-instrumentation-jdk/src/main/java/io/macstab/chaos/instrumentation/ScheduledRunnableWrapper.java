package io.macstab.chaos.instrumentation;

import io.macstab.chaos.instrumentation.bridge.BootstrapDispatcher;

final class ScheduledRunnableWrapper implements Runnable {
  private final Object executor;
  private final Runnable delegate;
  private final boolean periodic;

  ScheduledRunnableWrapper(Object executor, Runnable delegate, boolean periodic) {
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
      throw new IllegalStateException("scheduled runnable chaos hook failed", throwable);
    }
  }
}
