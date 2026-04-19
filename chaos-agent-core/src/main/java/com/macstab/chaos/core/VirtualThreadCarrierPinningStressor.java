package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosEffect;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Pins JDK 21+ virtual-thread carrier platform threads by keeping them inside {@code synchronized}
 * blocks for a configurable duration per cycle.
 *
 * <h2>JDK 21 carrier-pinning mechanics</h2>
 *
 * <p>In JDK 21, a virtual thread that executes a {@code synchronized} block is "pinned" to its
 * carrier platform thread: the scheduler cannot unmount the virtual thread while it holds a
 * monitor, even if it would otherwise block on I/O or {@link Object#wait()}. A pinned carrier is
 * unavailable for mounting other virtual threads, effectively reducing the carrier pool and causing
 * throughput degradation or starvation under load.
 *
 * <p>This stressor simulates that condition without requiring virtual threads in the test: it
 * spawns {@code pinnedThreadCount} platform daemon threads (potential carriers), each of which
 * acquires and holds the shared {@link #PIN_MONITOR} inside a {@code synchronized} block for {@link
 * ChaosEffect.VirtualThreadCarrierPinningEffect#pinDuration()} per cycle. The loop runs until
 * {@link #close()} sets the stop flag.
 *
 * <p>All threads start simultaneously via a {@link CountDownLatch} so that the full carrier
 * pressure is applied from the first cycle rather than staggered by OS thread-startup jitter.
 *
 * <p>{@link #close()} sets the stop flag and interrupts all threads. Because threads park inside
 * {@code LockSupport.parkNanos} they unblock immediately on interrupt.
 */
final class VirtualThreadCarrierPinningStressor implements ManagedStressor {

  private static final Logger LOGGER = Logger.getLogger("com.macstab.chaos");

  private static final Object PIN_MONITOR = new Object();

  private final List<Thread> pinningThreads;
  private final AtomicBoolean stopped = new AtomicBoolean(false);

  /**
   * Creates and immediately starts {@link
   * ChaosEffect.VirtualThreadCarrierPinningEffect#pinnedThreadCount()} carrier-pinning platform
   * daemon threads.
   *
   * @param effect the effect configuration; must not be {@code null}
   */
  VirtualThreadCarrierPinningStressor(final ChaosEffect.VirtualThreadCarrierPinningEffect effect) {
    final long pinNanos = effect.pinDuration().toNanos();
    final int count = effect.pinnedThreadCount();
    final CountDownLatch ready = new CountDownLatch(count);
    final List<Thread> threads = new ArrayList<>(count);

    for (int i = 0; i < count; i++) {
      final String name = "chaos-carrier-pin-" + i;
      final Thread thread =
          Thread.ofPlatform()
              .daemon(true)
              .name(name)
              .start(
                  () -> {
                    ready.countDown();
                    try {
                      ready.await();
                    } catch (final InterruptedException e) {
                      Thread.currentThread().interrupt();
                      return;
                    }
                    while (!stopped.get()) {
                      synchronized (PIN_MONITOR) {
                        final long deadline = System.nanoTime() + pinNanos;
                        while (System.nanoTime() < deadline && !stopped.get()) {
                          java.util.concurrent.locks.LockSupport.parkNanos(10_000L /* 10 µs */);
                          if (Thread.interrupted()) {
                            stopped.set(true);
                            return;
                          }
                        }
                      }
                    }
                    LOGGER.fine(() -> "chaos carrier-pin thread terminated: " + name);
                  });
      threads.add(thread);
    }
    this.pinningThreads = List.copyOf(threads);
  }

  /**
   * Signals all carrier-pinning threads to stop and interrupts them so that parked threads wake
   * immediately. Returns without waiting for threads to terminate.
   */
  @Override
  public void close() {
    stopped.set(true);
    for (final Thread thread : pinningThreads) {
      thread.interrupt();
    }
  }

  /**
   * Returns the number of carrier-pinning threads that are still alive.
   *
   * @return count of live pinning threads; {@code 0} after {@link #close()} completes
   */
  long aliveCount() {
    return pinningThreads.stream().filter(Thread::isAlive).count();
  }
}
