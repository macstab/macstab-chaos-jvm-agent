package io.macstab.chaos.instrumentation;

import io.macstab.chaos.instrumentation.bridge.BootstrapDispatcher;
import java.util.concurrent.Callable;

final class ScheduledCallableWrapper<T> implements Callable<T> {
  private final Object executor;
  private final Callable<T> delegate;

  ScheduledCallableWrapper(Object executor, Callable<T> delegate) {
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
    } catch (RuntimeException runtimeException) {
      throw runtimeException;
    } catch (Exception exception) {
      throw exception;
    } catch (Throwable throwable) {
      throw new IllegalStateException("scheduled callable chaos hook failed", throwable);
    }
  }
}
