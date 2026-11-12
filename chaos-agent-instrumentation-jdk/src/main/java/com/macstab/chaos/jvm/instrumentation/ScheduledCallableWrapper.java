package com.macstab.chaos.jvm.instrumentation;

import com.macstab.chaos.jvm.instrumentation.bridge.BootstrapDispatcher;
import java.lang.ref.WeakReference;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Wraps a {@link Callable} scheduled on an executor so that chaos tick hooks fire around each
 * invocation.
 *
 * <p>A suppressed tick cancels the future {@code schedule} returned for the task ({@link
 * #bind(Future)}), as the JDK cancels a task it will not run (a shutdown that drops delayed tasks):
 * {@code get()} throws {@link CancellationException} and {@code isCancelled()} is {@code true} (ADR
 * 0005). The task's future is bound when {@code schedule} returns; a tick that runs before that
 * waits for it, at most one second, unless the scheduling thread runs the tick itself. A future
 * that is never bound (a pool whose {@code decorateTask} runs the task itself) leaves the tick to
 * throw {@link CancellationException}, which the future reports as the cause of an {@code
 * ExecutionException}.
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

  /** The longest wait of a suppressed tick for its future ({@link #bind(Future)}). */
  private static final long BIND_WAIT_NANOS = TimeUnit.SECONDS.toNanos(1L);

  /** The thread that scheduled the task: a tick it runs itself cannot wait for the binding. */
  private final Thread scheduler = Thread.currentThread();

  /** The future {@code schedule} returned for the task; a suppressed tick cancels it. */
  private volatile @Nullable Future<?> future;

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
  public @Nullable T call() throws Exception {
    final Object executor = executorRef.get();
    final boolean proceed;
    try {
      proceed =
          executor == null || BootstrapDispatcher.beforeScheduledTick(executor, delegate, false);
    } catch (final Throwable chaosFailure) {
      // Restore the interrupt flag before re-throwing: an InterruptedException thrown by the
      // chaos hook clears the worker thread's interrupt bit, defeating cooperative cancellation
      // in the enclosing ScheduledThreadPoolExecutor.
      if (chaosFailure instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      sneakyThrow(chaosFailure);
      return null; // unreachable
    }
    if (!proceed) {
      // The scenario vetoed the tick. Returning a value would hand the caller a result the task
      // never produced (a null that NPEs on unboxing): the task is cancelled instead, as the JDK
      // cancels a task it will not run. The cancelled future ignores the null returned here. The
      // cancel runs at depth 0: a future of the application's decorateTask runs its own cancel
      // and done, which are the application's code.
      final Future<?> own = boundFuture();
      if (own != null) {
        own.cancel(false);
        return null;
      }
      throw new CancellationException(
          "chaos: scheduled callable suppressed by scenario (task="
              + delegate.getClass().getName()
              + ")");
    }
    // The delegate's own exceptions pass through untouched, its interrupt status as it left it.
    return delegate.call();
  }

  /**
   * Binds the future the executor returned for this task, which a suppressed tick cancels. Called
   * by the scheduling advice once the task is queued.
   *
   * @param taskFuture the task's future
   */
  public void bind(final Future<?> taskFuture) {
    this.future = taskFuture;
  }

  /**
   * Returns the task's future, waiting up to {@link #BIND_WAIT_NANOS} for {@code schedule} to
   * return when the tick runs first; {@code null} when it is not bound by then, or when the
   * scheduling thread runs the tick itself.
   */
  private @Nullable Future<?> boundFuture() {
    Future<?> own = future;
    if (own != null || Thread.currentThread() == scheduler) {
      return own;
    }
    // Agent work: the spin is no application's call.
    BootstrapDispatcher.beginInternalCall();
    try {
      final long deadline = System.nanoTime() + BIND_WAIT_NANOS;
      while ((own = future) == null && System.nanoTime() - deadline < 0L) {
        Thread.onSpinWait();
      }
    } finally {
      BootstrapDispatcher.endInternalCall();
    }
    return own;
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
