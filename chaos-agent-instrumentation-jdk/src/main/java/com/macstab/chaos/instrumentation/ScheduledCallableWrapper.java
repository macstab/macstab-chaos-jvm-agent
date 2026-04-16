package com.macstab.chaos.instrumentation;

import com.macstab.chaos.instrumentation.bridge.BootstrapDispatcher;
import java.util.concurrent.Callable;

final class ScheduledCallableWrapper<T> implements Callable<T> {
  private final Object executor;
  private final Callable<T> delegate;

  ScheduledCallableWrapper(final Object executor, final Callable<T> delegate) {
    this.executor = executor;
    this.delegate = delegate;
  }

  @Override
  public T call() throws Exception {
    try {
      if (!BootstrapDispatcher.beforeScheduledTick(executor, delegate, false)) {
        return null;
      }
      return delegate.call();
    } catch (Throwable throwable) {
      sneakyThrow(throwable);
      return null; // unreachable
    }
  }

  @SuppressWarnings("unchecked")
  private static <E extends Throwable> void sneakyThrow(final Throwable throwable) throws E {
    throw (E) throwable;
  }
}
