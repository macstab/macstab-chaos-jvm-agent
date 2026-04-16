package io.macstab.chaos.core;

import io.macstab.chaos.api.ChaosEffect;
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

  private static final Logger LOGGER = Logger.getLogger("io.macstab.chaos");

  private final List<ThreadLocal<byte[]>> plantedLocals;

  ThreadLocalLeakStressor(final ChaosEffect.ThreadLocalLeakEffect effect) {
    final int parallelism = ForkJoinPool.commonPool().getParallelism();
    final int totalLocals = parallelism * effect.entriesPerThread();
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
    // Remove each planted ThreadLocal from pool threads by submitting removal tasks.
    final List<ForkJoinTask<?>> cleanupTasks = new ArrayList<>(plantedLocals.size());
    for (final ThreadLocal<byte[]> local : plantedLocals) {
      cleanupTasks.add(
          ForkJoinPool.commonPool()
              .submit(
                  () -> {
                    local.remove();
                  }));
    }
    for (final ForkJoinTask<?> task : cleanupTasks) {
      try {
        task.join();
      } catch (final Exception e) {
        LOGGER.fine(() -> "chaos thread-local-leak cleanup task failed: " + e);
      }
    }
  }

  /** Returns the number of planted ThreadLocals. */
  int plantedCount() {
    return plantedLocals.size();
  }
}
