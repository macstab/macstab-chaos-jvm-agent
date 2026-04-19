package com.macstab.chaos.instrumentation;

import com.macstab.chaos.instrumentation.bridge.BootstrapDispatcher;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;

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
        // Suppression contract: the scenario has vetoed execution. A Callable<T> cannot
        // honour that by silently returning null — the caller's Future.get() would then
        // yield null and NPE on unboxing downstream (for Callable<Integer> etc.) with no
        // chaos signal in the stack trace. Throwing CancellationException is the only
        // honest option: the ScheduledFutureTask wraps it into ExecutionException whose
        // cause clearly identifies the chaos scenario as the source. The Runnable path
        // can no-op because it has no return value; Callable must not.
        throw new CancellationException(
            "chaos: scheduled callable suppressed by scenario (task="
                + delegate.getClass().getName()
                + ")");
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
