package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

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
 * <p>The rate is held over time: every 50 ms the thread allocates the whole objects the rate owes
 * since the start, so the fraction of an object a batch cannot allocate carries over to the next
 * one, and the time spent allocating counts. The stressor stops allocating once the configured
 * {@link ChaosEffect.GcPressureEffect#duration()} has elapsed, and its scenario ends ({@code
 * INACTIVE}, reason {@code "duration elapsed"}).
 *
 * <p>{@link #close()} interrupts the allocation thread and nulls the ring buffer, making retained
 * objects eligible for collection.
 */
final class GcPressureStressor implements ManagedStressor {

  private static final AgentLog LOGGER = AgentLog.named("com.macstab.chaos.jvm");
  private static final int RING_SIZE = 100;
  private static final long BATCH_INTERVAL_NANOS =
      java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(50L);

  private final AtomicBoolean running = new AtomicBoolean(true);
  private final AtomicLong allocatedBytes = new AtomicLong();
  private final LongSupplier nanoClock;
  private final Pause pause;
  private final Thread allocationThread;
  private volatile byte @Nullable [][] ring;

  /** Waits between batches; a test seam replaces the sleep. */
  @FunctionalInterface
  interface Pause {
    /**
     * Waits.
     *
     * @param nanos how long
     * @throws InterruptedException if the thread is interrupted while it waits
     */
    void pause(long nanos) throws InterruptedException;
  }

  GcPressureStressor(final ChaosEffect.GcPressureEffect effect) {
    this(effect, NO_END);
  }

  /**
   * Creates the stressor; it ends the scenario through {@code onEnd} when its duration elapses or
   * its thread fails.
   *
   * @param effect the effect
   * @param onEnd the scenario's end callback
   */
  GcPressureStressor(final ChaosEffect.GcPressureEffect effect, final Consumer<String> onEnd) {
    this(effect, onEnd, System::nanoTime, nanos -> Thread.sleep(java.time.Duration.ofNanos(nanos)));
  }

  /**
   * Test seam: the clock that paces the allocation and the pause between batches.
   *
   * @param effect the effect
   * @param onEnd the scenario's end callback
   * @param nanoClock a monotonic clock in nanoseconds
   * @param pause the wait between batches
   */
  GcPressureStressor(
      final ChaosEffect.GcPressureEffect effect,
      final Consumer<String> onEnd,
      final LongSupplier nanoClock,
      final Pause pause) {
    this.nanoClock = nanoClock;
    this.pause = pause;
    if (effect.promoteToOldGen()) {
      ring = new byte[RING_SIZE][];
    }

    allocationThread =
        AgentThreads.start(
            "chaos-gc-pressure",
            true,
            allocationLoop(
                effect.allocationRateBytesPerSecond(),
                effect.objectSizeBytes(),
                effect.promoteToOldGen(),
                ManagedStressor.nanos(effect.duration()),
                onEnd),
            ManagedStressor.failure(onEnd, "GcPressure"));
  }

  /**
   * The bytes a rate owes after a time: {@code rate × elapsed}, saturated at {@link Long#MAX_VALUE}
   * (a {@code double} product, whose cast saturates instead of wrapping).
   */
  private static long bytesOwed(final long bytesPerSecond, final long elapsedNanos) {
    return (long) ((double) bytesPerSecond * elapsedNanos / 1_000_000_000d);
  }

  /**
   * Body of the allocation daemon thread: sustains the configured allocation rate until the
   * duration elapses, which it reports to {@code onEnd}, or {@link #close()} sets {@code
   * running=false}.
   */
  private Runnable allocationLoop(
      final long bytesPerSecond,
      final int objectSizeBytes,
      final boolean promote,
      final long durationNanos,
      final Consumer<String> onEnd) {
    return () -> {
      final long start = nanoClock.getAsLong();
      // long — not int — because a stressor running at 1M allocations/sec
      // (e.g. 1 GB/s at 1 KB objects, a plausible high-pressure scenario) overflows
      // int in ~35 minutes. Past overflow, `Integer.MIN_VALUE % RING_SIZE` returns a
      // negative index and the daemon thread dies on AIOOBE, silently dropping the
      // old-gen promotion that scenarios depend on for the rest of their duration.
      // Math.floorMod guards against any future signed-math slip too.
      long ringIndex = 0L;
      while (running.get()) {
        final long elapsed = Math.min(nanoClock.getAsLong() - start, durationNanos);
        // The owed bytes are cumulative, so what a batch cannot allocate in whole objects carries
        // over, and the time the batch itself took is paid for in the next one.
        final long objects =
            (bytesOwed(bytesPerSecond, elapsed) - allocatedBytes.get()) / objectSizeBytes;
        for (long i = 0; i < objects; i++) {
          // Re-check running every iteration: close() sets running=false and nulls
          // the ring, but without this guard a batch that started before close()
          // would keep writing into the local `snapshot` for up to one full
          // batch interval, keeping allocated byte[] arrays strongly reachable
          // past the documented close() boundary.
          if (!running.get()) {
            break;
          }
          final byte[] chunk = new byte[objectSizeBytes];
          allocatedBytes.addAndGet(objectSizeBytes);
          if (promote) {
            final byte[][] snapshot = ring;
            if (snapshot != null) {
              snapshot[(int) Math.floorMod(ringIndex, (long) RING_SIZE)] = chunk;
              ringIndex++;
            }
          }
          // else: short-lived — chunk is immediately unreachable
        }
        if (elapsed >= durationNanos) {
          running.set(false);
          LOGGER.fine(() -> "[chaos-agent] chaos-gc-pressure stressor completed");
          // The load ends with the duration, and so does the scenario (L17).
          onEnd.accept("duration elapsed");
          return;
        }
        try {
          pause.pause(Math.min(BATCH_INTERVAL_NANOS, durationNanos - elapsed));
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          ManagedStressor.interrupted(onEnd, "GcPressure", !running.get());
          break;
        }
      }
      running.set(false);
      LOGGER.fine(() -> "[chaos-agent] chaos-gc-pressure stressor completed");
    };
  }

  @Override
  public void close() {
    // Set running=false BEFORE nulling the ring. The allocation thread re-checks running at the
    // top of every inner iteration, so flipping it first guarantees that any in-flight batch
    // observes the stop signal and skips the pending writes. Then interrupt to unblock the
    // between-batch sleep, and join so close() only returns once the ring is guaranteed
    // unreachable — per ManagedStressor.close() contract.
    running.set(false);
    ring = null;
    // 500 ms bounded join: the allocation thread may be mid-batch and descheduled on a busy
    // JVM. If the join times out, close() returns while the thread's stack still holds a
    // local reference to the ring snapshot — heap pressure is not immediately released.
    // This is a best-effort stop; full release occurs once the thread exits naturally.
    AgentThreads.stopAll(java.util.List.of(allocationThread), 500L);
  }

  /** Returns the bytes allocated so far; a test seam. */
  long allocatedBytes() {
    return allocatedBytes.get();
  }

  /** Returns {@code true} if the allocation thread is still running. */
  boolean isRunning() {
    return running.get() && allocationThread.isAlive();
  }
}
