package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import java.lang.ref.WeakReference;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;

/**
 * Wraps a {@link Callable} scheduled on an executor so that chaos tick hooks fire around each
 * invocation.
 *
 * @param <T> the result type produced by the wrapped callable
 */
public final class ScheduledCallableWrapper<T> implements Callable<T> {
  /**
   * Weak reference to break the retention cycle: the executor's internal queue holds this wrapper,
   * and a strong reference back would pin the executor for as long as the wrapper is queued. If the
   * executor has already been reclaimed by GC, the chaos hook is skipped and the callable proceeds
   * unchanged — losing chaos targeting precision is a strictly better outcome than leaking the
   * pool.
   */
  private final WeakReference<Object> executorRef;

  /** The wrapped callable to invoke after chaos hooks have been applied. */
  private final Callable<T> delegate;

  /**
   * Creates a wrapper holding a weak reference to {@code executor} and a strong reference to {@code
   * delegate}.
   *
   * @param executor the scheduling executor; held via {@link WeakReference} to avoid retention
   * @param delegate the wrapped callable to invoke after chaos hooks have been applied
   */
  public ScheduledCallableWrapper(final Object executor, final Callable<T> delegate) {
    this.executorRef = new WeakReference<>(executor);
    this.delegate = delegate;
  }

  @Override
  public T call() throws Exception {
    try {
      final Object executor = executorRef.get();
      if (executor != null && !BootstrapDispatcher.beforeScheduledTick(executor, delegate, false)) {
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
    } catch (final Throwable throwable) {
      // Restore the interrupt flag before re-throwing. Without this, an InterruptedException
      // thrown by delegate.call() (or by the chaos hook itself) would clear the worker
      // thread's interrupt bit, defeating cooperative cancellation in the enclosing
      // ScheduledThreadPoolExecutor — the worker keeps running subsequent tasks as if no
      // interrupt had occurred.
      if (throwable instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      sneakyThrow(throwable);
      return null; // unreachable
    }
  }

  /**
   * Rethrows {@code throwable} without requiring a checked-exception declaration on the caller.
   *
   * @param <E> inferred as {@link RuntimeException} by the compiler so the {@code throws E} clause
   *     does not propagate a checked-exception obligation
   * @param throwable the exception to rethrow; never {@code null}
   * @throws E always — this method never returns normally
   */
  @SuppressWarnings("unchecked")
  private static <E extends Throwable> void sneakyThrow(final Throwable throwable) throws E {
    throw (E) throwable;
  }
}
