package com.macstab.chaos.core;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A manually-operated gate that can hold threads at a chaos interception point until explicitly
 * released.
 *
 * <p>A {@code ManualGate} wraps a single-use {@link java.util.concurrent.CountDownLatch} (count 1).
 * The latch is replaced atomically on each {@link #reset()} call. Threads waiting via {@link
 * #await(java.time.Duration)} block on the current latch until {@link #release()} counts it down or
 * the timeout expires.
 *
 * <h2>Usage pattern</h2>
 *
 * <p>Test code that wants to pause an application thread at a precise point calls {@link #reset()}
 * before activating the scenario, then waits for the application thread to arrive (via a
 * side-channel notification), then calls {@link #release()} to let it proceed.
 *
 * <h2>Thread safety</h2>
 *
 * <p>All methods are thread-safe. {@code latch} is {@code volatile}; {@code reset()} and {@code
 * release()} perform a single volatile write/read each. Multiple threads may await concurrently;
 * {@link #release()} unblocks all of them at once.
 */
final class ManualGate {
  private volatile CountDownLatch latch = new CountDownLatch(1);

  /**
   * Installs a fresh, closed latch, discarding any previous latch.
   *
   * <p>Threads currently blocked in {@link #await(java.time.Duration)} on the old latch are
   * <em>not</em> released; they continue to wait on the old latch. To avoid surprising behaviour,
   * call {@link #reset()} before any thread can reach the gate.
   */
  void reset() {
    latch = new CountDownLatch(1);
  }

  /**
   * Opens the gate by counting down the current latch.
   *
   * <p>All threads blocked in {@link #await(java.time.Duration)} on the same latch instance are
   * released simultaneously. Subsequent callers of {@code await} will not block (the latch count is
   * already zero) until {@link #reset()} installs a new latch.
   */
  void release() {
    latch.countDown();
  }

  /**
   * Atomically releases any threads waiting on the current latch and installs a fresh closed latch.
   *
   * <p>Callers that enter {@link #await(java.time.Duration)} between a separate {@link #release()}
   * and {@link #reset()} would observe the already-released old latch and pass through without
   * blocking. This method eliminates that window by performing both operations under the same
   * monitor: {@code await()} snapshots the latch reference before this method acquires the monitor,
   * so any thread that read the old latch will still be released by {@code countDown()}; any thread
   * that reads the latch after this method returns will see the fresh closed latch.
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
  void await(final Duration maxBlock) throws InterruptedException {
    // Snapshot the volatile once so a concurrent reset() cannot make us observe two different
    // latch instances within a single call. Without this, a reset() interleaved between the
    // zero/negative check and the timed-await could cause us to block on a newly installed
    // latch while the original gate was never intended to be entered.
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
