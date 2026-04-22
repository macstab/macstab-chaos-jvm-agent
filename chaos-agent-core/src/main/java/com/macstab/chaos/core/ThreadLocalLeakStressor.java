package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosEffect;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.logging.Logger;

/**
 * Plants {@link ThreadLocal} entries in threads from the common {@link ForkJoinPool}, simulating
 * the most common form of ThreadLocal memory leak in production: pool threads retaining large
 * request-scoped objects across requests.
 *
 * <p>For each of the {@link ForkJoinPool#getParallelism()} common-pool threads, {@link
 * ChaosEffect.ThreadLocalLeakEffect#entriesPerThread()} ThreadLocals are created and set to a
 * byte-array of {@link ChaosEffect.ThreadLocalLeakEffect#valueSizeBytes()} bytes. The ThreadLocals
 * are set by submitting tasks to the pool and running them synchronously to completion before
 * returning from the constructor.
 *
 * <p>The planted ThreadLocals are retained in a list so that {@link #close()} can remove them from
 * each pool thread by submitting cleanup tasks. Note: pool threads may be replaced by the JVM after
 * they terminate, so cleanup is best-effort.
 */
final class ThreadLocalLeakStressor implements ManagedStressor {

  private static final Logger LOGGER = Logger.getLogger("com.macstab.chaos");

  private final List<ThreadLocal<byte[]>> plantedLocals;

  ThreadLocalLeakStressor(final ChaosEffect.ThreadLocalLeakEffect effect) {
    final int parallelism = ForkJoinPool.commonPool().getParallelism();
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
    final List<ThreadLocal<byte[]>> locals = new ArrayList<>(totalLocals);

    // Create entriesPerThread ThreadLocals per pool thread. Submit one task per
    // (thread × entry) pair so that each task runs on a specific pool thread turn.
    // We submit all tasks then join, so the common pool distributes them.
    final List<ForkJoinTask<?>> tasks = new ArrayList<>(totalLocals);

    for (int e = 0; e < effect.entriesPerThread(); e++) {
      final int valueSizeBytes = effect.valueSizeBytes();
      for (int t = 0; t < parallelism; t++) {
        final ThreadLocal<byte[]> local = new ThreadLocal<>();
        locals.add(local);
        tasks.add(
            ForkJoinPool.commonPool()
                .submit(
                    () -> {
                      local.set(new byte[valueSizeBytes]);
                      LOGGER.fine(
                          () ->
                              "chaos thread-local-leak planted entry on "
                                  + Thread.currentThread().getName());
                    }));
      }
    }

    // Wait for all planting tasks to complete.
    for (final ForkJoinTask<?> task : tasks) {
      task.join();
    }

    this.plantedLocals = List.copyOf(locals);
  }

  @Override
  public void close() {
    // Submit `parallelism` removal tasks per ThreadLocal. Each planted entry was set on exactly
    // one worker thread, but work-stealing provides no guarantee that a single removal task lands
    // on the same worker. Submitting one task per worker per local ensures that every live pool
    // thread will execute local.remove(), regardless of which worker holds the entry.
    // remove() is a no-op on threads that never called set(), so the redundant calls are safe.
    final int parallelism = ForkJoinPool.commonPool().getParallelism();
    final List<ForkJoinTask<?>> cleanupTasks = new ArrayList<>(plantedLocals.size() * parallelism);
    for (final ThreadLocal<byte[]> local : plantedLocals) {
      for (int t = 0; t < parallelism; t++) {
        cleanupTasks.add(ForkJoinPool.commonPool().submit(local::remove));
      }
    }
    int failedTaskCount = 0;
    for (final ForkJoinTask<?> task : cleanupTasks) {
      try {
        task.join();
      } catch (final Exception taskFailure) {
        // Escalate individual failures beyond FINE: a silently swallowed cleanup task means a
        // planted entry is permanently retained, and operators watching INFO/WARNING logs would
        // otherwise never learn why plantedCount() reports the original size post-close. The
        // stack trace is preserved on the per-task warning so the concrete failure (pool
        // rejection, OOM, unchecked exception from remove()) is attributable.
        failedTaskCount++;
        LOGGER.log(
            java.util.logging.Level.WARNING, "chaos thread-local-leak cleanup task failed", taskFailure);
      }
    }
    if (failedTaskCount > 0) {
      final int capturedFailureCount = failedTaskCount;
      LOGGER.warning(
          () ->
              "chaos thread-local-leak cleanup completed with "
                  + capturedFailureCount
                  + " failed task(s) out of "
                  + plantedLocals.size()
                  + "; ThreadLocal entries on affected pool threads may remain until workers"
                  + " are recycled");
    }
  }

  /** Returns the number of planted ThreadLocals. */
  int plantedCount() {
    return plantedLocals.size();
  }
}
