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
   * Blocks the calling thread until the gate is released or the timeout elapses.
   *
   * @param maxBlock the maximum time to wait; a value of {@link java.time.Duration#ZERO} or a
   *     non-positive duration means block indefinitely
   * @throws InterruptedException if the calling thread is interrupted while waiting
   */
  void await(final Duration maxBlock) throws InterruptedException {
    if (maxBlock == null) {
      latch.await();
      return;
    }
    latch.await(maxBlock.toMillis(), TimeUnit.MILLISECONDS);
  }
}
