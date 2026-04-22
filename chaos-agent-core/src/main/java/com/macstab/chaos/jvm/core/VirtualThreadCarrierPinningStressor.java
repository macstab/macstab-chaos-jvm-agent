package com.macstab.chaos.jvm.core;

import com.macstab.chaos.jvm.api.ChaosEffect;
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
 * acquires and holds its own <em>private</em> {@code Object} monitor inside a {@code synchronized}
 * block for {@link ChaosEffect.VirtualThreadCarrierPinningEffect#pinDuration()} per cycle. Each
 * thread holds a distinct monitor so that the threads do not serialise on a single lock — which
 * would reduce the stressor to simulating only one pinned carrier regardless of configured count.
 * The loop runs until {@link #close()} sets the stop flag.
 *
 * <p>All threads start simultaneously via a {@link CountDownLatch} so that the full carrier
 * pressure is applied from the first cycle rather than staggered by OS thread-startup jitter.
 *
 * <p>{@link #close()} sets the stop flag and interrupts all threads. Because threads park inside
 * {@code LockSupport.parkNanos} they unblock immediately on interrupt.
 */
final class VirtualThreadCarrierPinningStressor implements ManagedStressor {

  private static final Logger LOGGER = Logger.getLogger("com.macstab.chaos.jvm");

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
      // Per-thread monitor: each carrier-pinning thread must hold a DIFFERENT monitor to
      // simulate N independent pinned carriers. Sharing one monitor serialises the threads
      // so that only one carrier is pinned at a time.
      final Object pinMonitor = new Object();
      final Thread thread =
          new Thread(
              () -> {
                ready.countDown();
                try {
                  ready.await();
                } catch (final InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                while (!stopped.get()) {
                  synchronized (pinMonitor) {
                    final long deadline = System.nanoTime() + pinNanos;
                    while (System.nanoTime() < deadline && !stopped.get()) {
                      // Thread.sleep(0, nanos) holds the synchronized monitor (unlike wait())
                      // and is not instrumented by the chaos agent (LockSupport.park* is), so
                      // an active chaos delay scenario cannot recursively target this stressor.
                      try {
                        Thread.sleep(0, 10_000 /* 10 µs */);
                      } catch (final InterruptedException ie) {
                        stopped.set(true);
                        Thread.currentThread().interrupt();
                        return;
                      }
                    }
                  }
                }
                LOGGER.fine(() -> "chaos carrier-pin thread terminated: " + name);
              },
              name);
      thread.setDaemon(true);
      thread.start();
      threads.add(thread);
    }
    this.pinningThreads = List.copyOf(threads);
  }

  /**
   * Per-thread join deadline on {@link #close()}. Pinning threads park in small (10 µs) slices
   * inside the synchronized block; the interrupt causes each to exit the monitor on its next
   * iteration, so the expected wake-up time is well under a millisecond. 200 ms is a safety ceiling
   * for OS scheduling jitter.
   */
  private static final long JOIN_TIMEOUT_MILLIS = 200L;

  /**
   * Signals all carrier-pinning threads to stop, interrupts them, and waits up to {@value
   * #JOIN_TIMEOUT_MILLIS} ms per thread for termination. The class docstring promises the monitor
   * is released on close(); without the join, close() returned while the synchronized (pinMonitor)
   * frames were still unwinding, so a deactivate-then-recheck still observed pinned carriers.
   */
  @Override
  public void close() {
    stopped.set(true);
    for (final Thread thread : pinningThreads) {
      thread.interrupt();
    }
    // Join all threads even when interrupted mid-loop. Returning early on the first
    // InterruptedException leaves un-joined threads running — violating close()'s contract
    // that carrier-pinning has stopped before the method returns. Collect the interrupt and
    // restore it after the full join sweep.
    boolean selfInterrupted = false;
    for (final Thread thread : pinningThreads) {
      try {
        thread.join(JOIN_TIMEOUT_MILLIS);
      } catch (final InterruptedException interrupted) {
        selfInterrupted = true;
      }
    }
    if (selfInterrupted) {
      Thread.currentThread().interrupt();
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
