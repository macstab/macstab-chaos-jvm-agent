package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import com.macstab.chaos.jvm.api.ChaosUnsupportedFeatureException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Plants {@link ThreadLocal} entries in threads from the common {@link ForkJoinPool}, simulating
 * the most common form of ThreadLocal memory leak in production: pool threads retaining large
 * request-scoped objects across requests.
 *
 * <p>{@link ForkJoinPool#getParallelism()} × {@link
 * ChaosEffect.ThreadLocalLeakEffect#entriesPerThread()} ThreadLocals are created and each is set,
 * by a task submitted to the common pool, to a byte array of {@link
 * ChaosEffect.ThreadLocalLeakEffect#valueSizeBytes()} bytes. The pool spreads the tasks over its
 * workers; which worker holds which entry is up to the pool. The constructor returns once every
 * entry is planted, and fails with {@link ChaosUnsupportedFeatureException} when the pool has not
 * planted them all within {@value #PLANT_TIMEOUT_SECONDS} s (its workers are busy or blocked); the
 * entries are then emptied and the remaining tasks cancelled.
 *
 * <p>The planting is the agent's own work (L3): its tasks are {@link AgentOwned}, so no
 * FORK_JOIN_TASK_RUN scenario holds them, and they run with the dispatch depth raised, so no
 * THREAD_LOCAL_SET or logging scenario applies to them. The planted ThreadLocals themselves are
 * plain ones: they are the leak.
 *
 * <p>Each entry's value is held through a small {@link Planted} holder that this stressor keeps.
 * {@link #close()} empties the holders from the calling thread, so the planted bytes are released
 * no matter which worker holds the entry or whether that worker is busy; the emptied holders and
 * their ThreadLocals are cleared from the workers' maps as the JVM expunges stale entries.
 */
final class ThreadLocalLeakStressor implements ManagedStressor {

  private static final Logger LOGGER = Logger.getLogger("com.macstab.chaos.jvm");

  /** How long the constructor waits for the common pool to plant every entry. */
  static final long PLANT_TIMEOUT_SECONDS = 10L;

  /** The value of one planted entry; emptied on {@link #close()}. */
  static final class Planted {
    volatile byte @Nullable [] value;

    Planted(final byte[] value) {
      this.value = value;
    }
  }

  private final List<Planted> planted;

  ThreadLocalLeakStressor(final ChaosEffect.ThreadLocalLeakEffect effect) {
    this(effect, ForkJoinPool.commonPool().getParallelism());
  }

  /**
   * Plants {@code parallelism} × {@code entriesPerThread} entries. Test seam (U-72 c6): a test
   * fixes the parallelism, so its result does not depend on the processor count of the machine it
   * runs on.
   */
  ThreadLocalLeakStressor(final ChaosEffect.ThreadLocalLeakEffect effect, final int parallelism) {
    final int totalLocals;
    try {
      // Math.multiplyExact rejects overflow. Without this guard, `parallelism *
      // effect.entriesPerThread()` wraps to a negative int for pathological entriesPerThread
      // values and `new ArrayList<>(totalLocals)` throws NegativeArraySizeException inside the
      // stressor factory — the controller then logs a cryptic construction failure instead of
      // surfacing the real misconfiguration.
      totalLocals = Math.multiplyExact(parallelism, effect.entriesPerThread());
    } catch (final ArithmeticException overflow) {
      throw new IllegalArgumentException(
          "thread-local-leak total entries (parallelism * entriesPerThread) overflows int: "
              + parallelism
              + " * "
              + effect.entriesPerThread(),
          overflow);
    }
    final List<Planted> holders = new ArrayList<>(totalLocals);
    final List<ForkJoinTask<?>> tasks = new ArrayList<>(totalLocals);
    final int valueSizeBytes = effect.valueSizeBytes();
    for (int i = 0; i < totalLocals; i++) {
      // One instance per entry is the leak this stressor produces.
      @SuppressWarnings("ThreadLocalUsage")
      final ThreadLocal<Planted> local = new ThreadLocal<>();
      final Planted holder = new Planted(new byte[valueSizeBytes]);
      holders.add(holder);
      tasks.add(ForkJoinPool.commonPool().submit(new Planting(local, holder)));
    }
    awaitPlanting(tasks, holders);
    this.planted = List.copyOf(holders);
  }

  /**
   * Waits for every planting task; on a timeout, an interrupt or a failed task, empties every
   * holder and cancels the remaining tasks before it throws, so a task that runs late plants an
   * empty holder.
   */
  private static void awaitPlanting(
      final List<ForkJoinTask<?>> tasks, final List<Planted> holders) {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PLANT_TIMEOUT_SECONDS);
    try {
      for (final ForkJoinTask<?> task : tasks) {
        task.get(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
      }
    } catch (final TimeoutException timedOut) {
      abandon(tasks, holders);
      throw new ChaosUnsupportedFeatureException(
          "thread-local-leak: the common ForkJoinPool did not plant the "
              + holders.size()
              + " entries within "
              + PLANT_TIMEOUT_SECONDS
              + " s; its workers are busy or blocked");
    } catch (final InterruptedException interrupted) {
      abandon(tasks, holders);
      Thread.currentThread().interrupt();
      throw new IllegalStateException("thread-local-leak: interrupted while planting", interrupted);
    } catch (final ExecutionException failed) {
      abandon(tasks, holders);
      final Throwable cause = failed.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new IllegalStateException("thread-local-leak: planting failed", cause);
    }
  }

  private static void abandon(final List<ForkJoinTask<?>> tasks, final List<Planted> holders) {
    for (final Planted holder : holders) {
      holder.value = null;
    }
    for (final ForkJoinTask<?> task : tasks) {
      task.cancel(false);
    }
  }

  /** Plants one entry on the worker that runs it; agent-owned, so no scenario sees it. */
  private static final class Planting extends RecursiveAction implements AgentOwned {
    private static final long serialVersionUID = 1L;

    private final transient ThreadLocal<Planted> local;
    private final transient Planted holder;

    Planting(final ThreadLocal<Planted> local, final Planted holder) {
      this.local = local;
      this.holder = holder;
    }

    @Override
    protected void compute() {
      ChaosDispatcher.incrementDispatchDepth();
      try {
        local.set(holder);
        LOGGER.fine(
            () -> "chaos thread-local-leak planted entry on " + Thread.currentThread().getName());
      } finally {
        ChaosDispatcher.decrementDispatchDepth();
      }
    }
  }

  @Override
  public void close() {
    for (final Planted holder : planted) {
      holder.value = null;
    }
  }

  /** Returns the number of planted ThreadLocals. */
  int plantedCount() {
    return planted.size();
  }

  /** Returns the bytes the planted entries still hold. */
  long retainedBytes() {
    long retained = 0;
    for (final Planted holder : planted) {
      final byte[] value = holder.value;
      if (value != null) {
        retained += value.length;
      }
    }
    return retained;
  }
}
