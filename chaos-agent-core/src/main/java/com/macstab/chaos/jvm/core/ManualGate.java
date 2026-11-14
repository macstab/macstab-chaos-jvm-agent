package com.macstab.chaos.jvm.core;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * A manually-operated gate that can hold threads at a chaos interception point until explicitly
 * released.
 *
 * <p>A {@code ManualGate} wraps a single-use {@link java.util.concurrent.CountDownLatch} (count 1).
 * The latch is replaced atomically on each {@link #releaseAndReset()} call. Threads waiting via
 * {@link #await(java.time.Duration)} block on the current latch until {@link #release()} counts it
 * down or the timeout expires.
 *
 * <p>A scenario holds intercepted threads at its gate until its handle's {@code release()} opens
 * it; starting the scenario again releases and recloses the gate in one step ({@link
 * #releaseAndReset()}).
 *
 * <h2>Thread safety</h2>
 *
 * <p>All methods are thread-safe. {@code latch} is {@code volatile}; {@code release()} performs a
 * single volatile read; {@link #releaseAndReset()} runs under the gate's monitor. Multiple threads
 * may await concurrently; {@link #release()} unblocks all of them at once.
 */
final class ManualGate {
  private volatile CountDownLatch latch = new CountDownLatch(1);

  /**
   * Opens the gate by counting down the current latch.
   *
   * <p>All threads blocked in {@link #await(java.time.Duration)} on the same latch instance are
   * released simultaneously. Subsequent callers of {@code await} will not block (the latch count is
   * already zero) until {@link #releaseAndReset()} installs a new latch.
   */
  void release() {
    latch.countDown();
  }

  /**
   * Atomically releases any threads waiting on the current latch and installs a fresh closed latch.
   *
   * <p>Both steps run under the gate's monitor: {@code await()} snapshots the latch reference
   * before this method acquires the monitor, so any thread that read the old latch will still be
   * released by {@code countDown()}; any thread that reads the latch after this method returns will
   * see the fresh closed latch.
   */
  synchronized void releaseAndReset() {
    latch.countDown();
    latch = new CountDownLatch(1);
  }

  /**
   * Blocks the calling thread until the gate is released or the timeout elapses.
   *
   * @param maxBlock the maximum time to wait; a value of {@link java.time.Duration#ZERO} or a
   *     non-positive duration means block indefinitely
   * @throws InterruptedException if the calling thread is interrupted while waiting
   */
  void await(final @Nullable Duration maxBlock) throws InterruptedException {
    // Snapshot the volatile once so a concurrent releaseAndReset() cannot make one call observe
    // two different latch instances.
    final CountDownLatch current = latch;
    // Duration.ZERO and any non-positive value mean "block indefinitely" per javadoc. The
    // CountDownLatch timed-await contract treats <= 0 as "do not wait at all", which is the
    // exact opposite — so route both cases through the untimed await.
    if (maxBlock == null || maxBlock.isZero() || maxBlock.isNegative()) {
      current.await();
      return;
    }
    // Route sub-millisecond positive durations (e.g. Duration.ofNanos(500_000)) through the
    // nanosecond-precision timed await. Using toMillis() here would truncate to 0 and return
    // immediately without blocking, collapsing a legitimate (if very short) gate into a
    // no-op. Nanosecond-precision CountDownLatch.await honours the full duration.
    //
    // Duration.toNanos() overflows to ArithmeticException at ~±292 years; a caller passing
    // Duration.ofDays(Long.MAX_VALUE) or similar would otherwise crash the advice thread.
    // A duration larger than the nanosecond-representable range is effectively indefinite, so
    // fall back to the untimed await in that case.
    final long nanos;
    try {
      nanos = maxBlock.toNanos();
    } catch (final ArithmeticException overflow) {
      current.await();
      return;
    }
    current.await(nanos, TimeUnit.NANOSECONDS);
  }
}
