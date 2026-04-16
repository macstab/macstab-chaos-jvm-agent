package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosEffect;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Sustains a target allocation rate to stress the garbage collector.
 *
 * <p>A daemon thread allocates byte arrays at the configured rate. When {@link
 * ChaosEffect.GcPressureEffect#promoteToOldGen()} is {@code false}, each allocated array is
 * immediately discarded — objects are short-lived and collected in the young generation. When
 * {@code true}, a rotating ring buffer of size {@code RING_SIZE} retains references long enough to
 * survive a young-gen collection, forcing objects into the old generation and triggering major GC
 * cycles.
 *
 * <p>Rate pacing is coarse-grained: the thread allocates a batch, sleeps for one batch interval,
 * then repeats. Sub-millisecond precision is not guaranteed. The stressor respects the configured
 * {@link ChaosEffect.GcPressureEffect#duration()} by stopping the allocation thread after that
 * period.
 *
 * <p>{@link #close()} interrupts the allocation thread and nulls the ring buffer, making retained
 * objects eligible for collection.
 */
final class GcPressureStressor implements ManagedStressor {

  private static final Logger LOGGER = Logger.getLogger("com.macstab.chaos");
  private static final int RING_SIZE = 100;
  private static final long BATCH_INTERVAL_MS = 50L;

  private final AtomicBoolean running = new AtomicBoolean(true);
  private final Thread allocationThread;
  private volatile byte[][] ring;

  GcPressureStressor(final ChaosEffect.GcPressureEffect effect) {
    if (effect.promoteToOldGen()) {
      ring = new byte[RING_SIZE][];
    }

    final long batchSizeBytes =
        Math.max(1L, effect.allocationRateBytesPerSecond() * BATCH_INTERVAL_MS / 1000L);
    final int objectSizeBytes = effect.objectSizeBytes();
    final int objectsPerBatch = (int) Math.max(1L, batchSizeBytes / objectSizeBytes);
    final long durationMillis = effect.duration().toMillis();
    final boolean promote = effect.promoteToOldGen();

    allocationThread =
        Thread.ofPlatform()
            .daemon(true)
            .name("chaos-gc-pressure")
            .start(
                () -> {
                  final long deadline = System.currentTimeMillis() + durationMillis;
                  int ringIndex = 0;
                  while (running.get() && System.currentTimeMillis() < deadline) {
                    for (int i = 0; i < objectsPerBatch; i++) {
                      final byte[] chunk = new byte[objectSizeBytes];
                      if (promote) {
                        final byte[][] snapshot = ring;
                        if (snapshot != null) {
                          snapshot[ringIndex % RING_SIZE] = chunk;
                          ringIndex++;
                        }
                      }
                      // else: short-lived — chunk is immediately unreachable
                    }
                    try {
                      Thread.sleep(BATCH_INTERVAL_MS);
                    } catch (final InterruptedException e) {
                      Thread.currentThread().interrupt();
                      break;
                    }
                  }
                  running.set(false);
                  LOGGER.fine(() -> "chaos-gc-pressure stressor completed");
                });
  }

  @Override
  public void close() {
    running.set(false);
    ring = null;
    allocationThread.interrupt();
  }

  /** Returns {@code true} if the allocation thread is still running. */
  boolean isRunning() {
    return running.get() && allocationThread.isAlive();
  }
}
