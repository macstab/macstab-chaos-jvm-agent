package com.macstab.chaos.core;

import com.macstab.chaos.api.ChaosEffect;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * Creates high contention on a shared monitor by spawning threads that compete for a single {@link
 * ReentrantLock}.
 *
 * <p>Each contending thread repeatedly acquires the lock, holds it for {@link
 * ChaosEffect.MonitorContentionEffect#lockHoldDuration()}, then releases it — immediately retrying
 * the acquisition. This generates a lock convoy: all waiting threads pile up in the lock queue and
 * the scheduler is under pressure to fairly distribute ownership.
 *
 * <p>When {@link ChaosEffect.MonitorContentionEffect#unfair()} is {@code true} the lock is
 * constructed without FIFO ordering, increasing the probability of thread starvation (some threads
 * may never acquire the lock while others acquire it repeatedly).
 *
 * <p>All contending threads start together via a {@link CountDownLatch} so that contention begins
 * simultaneously rather than being biased by thread startup order.
 *
 * <p>{@link #close()} sets a stop flag and interrupts all threads.
 */
final class MonitorContentionStressor implements ManagedStressor {

  private static final Logger LOGGER = Logger.getLogger("com.macstab.chaos");

  private final List<Thread> contentionThreads;
  private final AtomicBoolean stopped = new AtomicBoolean(false);

  MonitorContentionStressor(final ChaosEffect.MonitorContentionEffect effect) {
    final ReentrantLock lock = new ReentrantLock(!effect.unfair()); // fair=true unless unfair
    final long holdNanos = effect.lockHoldDuration().toNanos();
    final int count = effect.contendingThreadCount();
    final CountDownLatch ready = new CountDownLatch(count);
    final List<Thread> threads = new ArrayList<>(count);

    for (int i = 0; i < count; i++) {
      final String name = "chaos-contention-" + i;
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
                      // lockInterruptibly, not lock(): a thread blocked waiting for the lock
                      // must unblock on close()'s interrupt(), otherwise close() returns while
                      // the thread can still be waiting unboundedly for the lock.
                      try {
                        lock.lockInterruptibly();
                      } catch (final InterruptedException e) {
                        stopped.set(true);
                        Thread.currentThread().interrupt();
                        return;
                      }
                      try {
                        final long holdDeadline = System.nanoTime() + holdNanos;
                        while (System.nanoTime() < holdDeadline && !stopped.get()) {
                          java.util.concurrent.locks.LockSupport.parkNanos(10_000L /* 10 µs */);
                          if (Thread.interrupted()) {
                            stopped.set(true);
                            return;
                          }
                        }
                      } finally {
                        lock.unlock();
                      }
                    }
                    LOGGER.fine(() -> "chaos monitor-contention thread terminated: " + name);
                  });
      threads.add(thread);
    }
    this.contentionThreads = List.copyOf(threads);
  }

  /**
   * Per-thread join deadline on {@link #close()}. Contention threads park in small (10 µs) slices
   * and check the stop flag each iteration, so the expected wake-up time on interrupt is well under
   * a millisecond; 200 ms is a safety ceiling for OS scheduling jitter.
   */
  private static final long JOIN_TIMEOUT_MILLIS = 200L;

  @Override
  public void close() {
    stopped.set(true);
    for (final Thread thread : contentionThreads) {
      thread.interrupt();
    }
    // Join after interrupt — without this, close() returns while contending threads are still
    // draining their 10 µs park loops, and test suites asserting "no contention threads alive"
    // immediately after deactivate observe flaky counts. Bounded join honours ManagedStressor's
    // "wait for termination" contract.
    for (final Thread thread : contentionThreads) {
      try {
        thread.join(JOIN_TIMEOUT_MILLIS);
      } catch (final InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  /** Returns the number of contending threads that are still alive. */
  long aliveCount() {
    return contentionThreads.stream().filter(Thread::isAlive).count();
  }
}
