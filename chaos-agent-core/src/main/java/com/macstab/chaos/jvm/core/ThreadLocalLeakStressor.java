package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
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
 * entry is planted.
 *
 * <p>Each entry's value is held through a small {@link Planted} holder that this stressor keeps.
 * {@link #close()} empties the holders from the calling thread, so the planted bytes are released
 * no matter which worker holds the entry or whether that worker is busy; the emptied holders and
 * their ThreadLocals are cleared from the workers' maps as the JVM expunges stale entries.
 */
final class ThreadLocalLeakStressor implements ManagedStressor {

  private static final Logger LOGGER = Logger.getLogger("com.macstab.chaos.jvm");

  /** The value of one planted entry; emptied on {@link #close()}. */
  static final class Planted {
    volatile byte @Nullable [] value;

    Planted(final byte[] value) {
      this.value = value;
    }
  }

  private final List<Planted> planted;

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
    final List<Planted> holders = new ArrayList<>(totalLocals);
    final List<ForkJoinTask<?>> tasks = new ArrayList<>(totalLocals);
    final int valueSizeBytes = effect.valueSizeBytes();
    for (int i = 0; i < totalLocals; i++) {
      // One instance per entry is the leak this stressor produces.
      @SuppressWarnings("ThreadLocalUsage")
      final ThreadLocal<Planted> local = new ThreadLocal<>();
      final Planted holder = new Planted(new byte[valueSizeBytes]);
      holders.add(holder);
      tasks.add(
          ForkJoinPool.commonPool()
              .submit(
                  () -> {
                    local.set(holder);
                    LOGGER.fine(
                        () ->
                            "chaos thread-local-leak planted entry on "
                                + Thread.currentThread().getName());
                  }));
    }
    for (final ForkJoinTask<?> task : tasks) {
      task.join();
    }
    this.planted = List.copyOf(holders);
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
